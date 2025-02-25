/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */
package com.android.providers.blockednumber;

import static com.android.providers.blockednumber.Utils.piiHandle;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.app.backup.BackupManager;
import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriMatcher;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.BlockedNumberContract;
import android.provider.BlockedNumberContract.SystemContract;
import android.telecom.TelecomManager;
import android.telephony.CarrierConfigManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.common.content.ProjectionMap;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.flags.Flags;
import com.android.providers.blockednumber.BlockedNumberDatabaseHelper.Tables;

import java.util.Arrays;

/**
 * Blocked phone number provider.
 *
 * <p>Note the provider allows emergency numbers.  The caller (telecom) should never call it with
 * emergency numbers.
 */
public class BlockedNumberProvider extends ContentProvider {
    static final String TAG = "BlockedNumbers";

    private static final boolean DEBUG = false; // DO NOT SUBMIT WITH TRUE.

    private static final int BLOCKED_LIST = 1000;
    private static final int BLOCKED_ID = 1001;

    private static final UriMatcher sUriMatcher;

    private static final String PREF_FILE = "block_number_provider_prefs";
    private static final String BLOCK_SUPPRESSION_EXPIRY_TIME_PREF =
            "block_suppression_expiry_time_pref";
    private static final int MAX_BLOCKING_DISABLED_DURATION_SECONDS = 7 * 24 * 3600; // 1 week
    private static final long BLOCKING_DISABLED_FOREVER = -1;
    // Normally, we allow calls from self, *except* in unit tests, where we clear this flag
    // to emulate calls from other apps.
    @VisibleForTesting
    static boolean ALLOW_SELF_CALL = true;

    static {
        sUriMatcher = new UriMatcher(0);
        sUriMatcher.addURI(BlockedNumberContract.AUTHORITY, "blocked", BLOCKED_LIST);
        sUriMatcher.addURI(BlockedNumberContract.AUTHORITY, "blocked/#", BLOCKED_ID);
    }

    private static final ProjectionMap sBlockedNumberColumns = ProjectionMap.builder()
            .add(BlockedNumberContract.BlockedNumbers.COLUMN_ID)
            .add(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER)
            .add(BlockedNumberContract.BlockedNumbers.COLUMN_E164_NUMBER)
            .build();

    private static final String ID_SELECTION =
            BlockedNumberContract.BlockedNumbers.COLUMN_ID + "=?";

    private static final String ORIGINAL_NUMBER_SELECTION =
            BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER + "=?";

    private static final String E164_NUMBER_SELECTION =
            BlockedNumberContract.BlockedNumbers.COLUMN_E164_NUMBER + "=?";

    @VisibleForTesting
    protected BlockedNumberDatabaseHelper mDbHelper;
    @VisibleForTesting
    protected BackupManager mBackupManager;
    protected AppOpsManager mAppOpsManager;

    @Override
    public boolean onCreate() {
        mDbHelper = BlockedNumberDatabaseHelper.getInstance(getContext());
        mBackupManager = new BackupManager(getContext());
        mAppOpsManager = getAppOpsManager();
        return true;
    }

    @Override
    public String getType(@NonNull Uri uri) {
        final int match = sUriMatcher.match(uri);
        switch (match) {
            case BLOCKED_LIST:
                return BlockedNumberContract.BlockedNumbers.CONTENT_TYPE;
            case BLOCKED_ID:
                return BlockedNumberContract.BlockedNumbers.CONTENT_ITEM_TYPE;
            default:
                throw new IllegalArgumentException("Unsupported URI: " + uri);
        }
    }

    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        enforceWritePermissionAndMainUser();

        final int match = sUriMatcher.match(uri);
        switch (match) {
            case BLOCKED_LIST:
                Uri blockedUri = insertBlockedNumber(values);
                getContext().getContentResolver().notifyChange(blockedUri, null);
                mBackupManager.dataChanged();
                return blockedUri;
            default:
                throw new IllegalArgumentException("Unsupported URI: " + uri);
        }
    }

    /**
     * Implements the "blocked/" insert.
     */
    private Uri insertBlockedNumber(ContentValues cv) {
        throwIfSpecified(cv, BlockedNumberContract.BlockedNumbers.COLUMN_ID);

        final String phoneNumber = cv.getAsString(
                BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER);

        if (TextUtils.isEmpty(phoneNumber)) {
            throw new IllegalArgumentException("Missing a required column " +
                    BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER);
        }

        // Fill in with autogenerated columns.
        final String e164Number = Utils.getE164Number(getContext(), phoneNumber,
                cv.getAsString(BlockedNumberContract.BlockedNumbers.COLUMN_E164_NUMBER));
        cv.put(BlockedNumberContract.BlockedNumbers.COLUMN_E164_NUMBER, e164Number);

        if (DEBUG) {
            Log.d(TAG, String.format("inserted blocked number: %s", cv));
        }

        // Then insert.
        final long id = mDbHelper.getWritableDatabase().insertWithOnConflict(
                BlockedNumberDatabaseHelper.Tables.BLOCKED_NUMBERS, null, cv,
                SQLiteDatabase.CONFLICT_REPLACE);

        return ContentUris.withAppendedId(BlockedNumberContract.BlockedNumbers.CONTENT_URI, id);
    }

    private static void throwIfSpecified(ContentValues cv, String column) {
        if (cv.containsKey(column)) {
            throw new IllegalArgumentException("Column " + column + " must not be specified");
        }
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection,
            @Nullable String[] selectionArgs) {
        enforceWritePermissionAndMainUser();

        throw new UnsupportedOperationException(
                "Update is not supported.  Use delete + insert instead");
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection,
            @Nullable String[] selectionArgs) {
        enforceWritePermissionAndMainUser();

        final int match = sUriMatcher.match(uri);
        int numRows;
        switch (match) {
            case BLOCKED_LIST:
                numRows = deleteBlockedNumber(selection, selectionArgs);
                break;
            case BLOCKED_ID:
                numRows = deleteBlockedNumberWithId(ContentUris.parseId(uri), selection);
                break;
            default:
                throw new IllegalArgumentException("Unsupported URI: " + uri);
        }
        getContext().getContentResolver().notifyChange(uri, null);
        mBackupManager.dataChanged();
        return numRows;
    }

    /**
     * Implements the "blocked/#" delete.
     */
    private int deleteBlockedNumberWithId(long id, String selection) {
        throwForNonEmptySelection(selection);

        return deleteBlockedNumber(ID_SELECTION, new String[]{Long.toString(id)});
    }

    /**
     * Implements the "blocked/" delete.
     */
    private int deleteBlockedNumber(String selection, String[] selectionArgs) {
        final SQLiteDatabase db = mDbHelper.getWritableDatabase();

        // When selection is specified, compile it within (...) to detect SQL injection.
        if (!TextUtils.isEmpty(selection)) {
            db.validateSql("select 1 FROM " + Tables.BLOCKED_NUMBERS + " WHERE " +
                    Utils.wrapSelectionWithParens(selection),
                    /* cancellationSignal =*/ null);
        }

        return db.delete(
                BlockedNumberDatabaseHelper.Tables.BLOCKED_NUMBERS,
                selection, selectionArgs);
    }

    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection,
            @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        enforceReadPermissionAndMainUser();

        return query(uri, projection, selection, selectionArgs, sortOrder, null);
    }

    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection,
            @Nullable String[] selectionArgs, @Nullable String sortOrder,
            @Nullable CancellationSignal cancellationSignal) {
        enforceReadPermissionAndMainUser();

        final int match = sUriMatcher.match(uri);
        Cursor cursor;
        switch (match) {
            case BLOCKED_LIST:
                cursor = queryBlockedList(projection, selection, selectionArgs, sortOrder,
                        cancellationSignal);
                break;
            case BLOCKED_ID:
                cursor = queryBlockedListWithId(ContentUris.parseId(uri), projection, selection,
                        cancellationSignal);
                break;
            default:
                throw new IllegalArgumentException("Unsupported URI: " + uri);
        }
        // Tell the cursor what uri to watch, so it knows when its source data changes
        cursor.setNotificationUri(getContext().getContentResolver(), uri);
        return cursor;
    }

    /**
     * Implements the "blocked/#" query.
     */
    private Cursor queryBlockedListWithId(long id, String[] projection, String selection,
            CancellationSignal cancellationSignal) {
        throwForNonEmptySelection(selection);

        return queryBlockedList(projection, ID_SELECTION, new String[]{Long.toString(id)},
                null, cancellationSignal);
    }

    /**
     * Implements the "blocked/" query.
     */
    private Cursor queryBlockedList(String[] projection, String selection, String[] selectionArgs,
            String sortOrder, CancellationSignal cancellationSignal) {
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setStrict(true);
        qb.setTables(BlockedNumberDatabaseHelper.Tables.BLOCKED_NUMBERS);
        qb.setProjectionMap(sBlockedNumberColumns);

        return qb.query(mDbHelper.getReadableDatabase(), projection, selection, selectionArgs,
                /* groupBy =*/ null, /* having =*/null, sortOrder,
                /* limit =*/ null, cancellationSignal);
    }

    private void throwForNonEmptySelection(String selection) {
        if (!TextUtils.isEmpty(selection)) {
            throw new IllegalArgumentException(
                    "When ID is specified in URI, selection must be null");
        }
    }

    @Override
    public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
        final Bundle res = new Bundle();
        switch (method) {
            case BlockedNumberContract.METHOD_IS_BLOCKED:
                enforceReadPermissionAndMainUser();
                boolean isBlocked = isBlocked(arg);
                res.putBoolean(BlockedNumberContract.RES_NUMBER_IS_BLOCKED, isBlocked);
                res.putInt(BlockedNumberContract.RES_BLOCK_STATUS,
                        isBlocked ? BlockedNumberContract.STATUS_BLOCKED_IN_LIST
                                : BlockedNumberContract.STATUS_NOT_BLOCKED);
                break;
            case BlockedNumberContract.METHOD_CAN_CURRENT_USER_BLOCK_NUMBERS:
                // No permission checks: any app should be able to access this API.
                res.putBoolean(
                        BlockedNumberContract.RES_CAN_BLOCK_NUMBERS, canCurrentUserBlockUsers());
                break;
            case BlockedNumberContract.METHOD_UNBLOCK:
                enforceWritePermissionAndMainUser();

                res.putInt(BlockedNumberContract.RES_NUM_ROWS_DELETED, unblock(arg));
                break;
            case SystemContract.METHOD_NOTIFY_EMERGENCY_CONTACT:
                enforceSystemWritePermissionAndMainUser();

                notifyEmergencyContact();
                break;
            case SystemContract.METHOD_END_BLOCK_SUPPRESSION:
                enforceSystemWritePermissionAndMainUser();

                endBlockSuppression();
                break;
            case SystemContract.METHOD_GET_BLOCK_SUPPRESSION_STATUS:
                enforceSystemReadPermissionAndMainUser();

                SystemContract.BlockSuppressionStatus status = getBlockSuppressionStatus();
                res.putBoolean(SystemContract.RES_IS_BLOCKING_SUPPRESSED, status.isSuppressed);
                res.putLong(SystemContract.RES_BLOCKING_SUPPRESSED_UNTIL_TIMESTAMP,
                        status.untilTimestampMillis);
                break;
            case SystemContract.METHOD_SHOULD_SYSTEM_BLOCK_NUMBER:
                enforceSystemReadPermissionAndMainUser();
                int blockReason = shouldSystemBlockNumber(arg, extras);
                res.putBoolean(BlockedNumberContract.RES_NUMBER_IS_BLOCKED,
                        blockReason != BlockedNumberContract.STATUS_NOT_BLOCKED);
                res.putInt(BlockedNumberContract.RES_BLOCK_STATUS, blockReason);
                break;
            case SystemContract.METHOD_SHOULD_SHOW_EMERGENCY_CALL_NOTIFICATION:
                enforceSystemReadPermissionAndMainUser();
                res.putBoolean(BlockedNumberContract.RES_SHOW_EMERGENCY_CALL_NOTIFICATION,
                        shouldShowEmergencyCallNotification());
                break;
            case SystemContract.METHOD_GET_ENHANCED_BLOCK_SETTING:
                enforceSystemReadPermissionAndMainUser();
                if (extras != null) {
                    String key = extras.getString(BlockedNumberContract.EXTRA_ENHANCED_SETTING_KEY);
                    boolean value = getEnhancedBlockSetting(key);
                    res.putBoolean(BlockedNumberContract.RES_ENHANCED_SETTING_IS_ENABLED, value);
                }
                break;
            case SystemContract.METHOD_SET_ENHANCED_BLOCK_SETTING:
                enforceSystemWritePermissionAndMainUser();
                if (extras != null) {
                    String key = extras.getString(BlockedNumberContract.EXTRA_ENHANCED_SETTING_KEY);
                    boolean value = extras.getBoolean(
                            BlockedNumberContract.EXTRA_ENHANCED_SETTING_VALUE, false);
                    setEnhancedBlockSetting(key, value);
                }
                break;
            default:
            enforceReadPermissionAndMainUser();

                throw new IllegalArgumentException("Unsupported method " + method);
        }
        return res;
    }

    private int unblock(String phoneNumber) {
        if (TextUtils.isEmpty(phoneNumber)) {
            return 0;
        }

        StringBuilder selectionBuilder = new StringBuilder(ORIGINAL_NUMBER_SELECTION);
        String[] selectionArgs = new String[]{phoneNumber};
        final String e164Number = Utils.getE164Number(getContext(), phoneNumber, null);
        if (!TextUtils.isEmpty(e164Number)) {
            selectionBuilder.append(" or " + E164_NUMBER_SELECTION);
            selectionArgs = new String[]{phoneNumber, e164Number};
        }
        String selection = selectionBuilder.toString();
        if (DEBUG) {
            Log.d(TAG, String.format("Unblocking numbers using selection: %s, args: %s",
                    selection, Arrays.toString(selectionArgs)));
        }
        return deleteBlockedNumber(selection, selectionArgs);
    }

    private boolean isEmergencyNumber(String phoneNumber) {
        if (TextUtils.isEmpty(phoneNumber)) {
            return false;
        }

        Context context = getContext();
        final String e164Number = Utils.getE164Number(context, phoneNumber, null);
        TelephonyManager tm = context.getSystemService(TelephonyManager.class);

        if (!Flags.enforceTelephonyFeatureMapping()) {
            return tm.isEmergencyNumber(phoneNumber) || tm.isEmergencyNumber(e164Number);
        } else {
            if (tm == null) {
                return false;
            }
            try {
                return tm.isEmergencyNumber(phoneNumber) || tm.isEmergencyNumber(e164Number);
            } catch (UnsupportedOperationException | IllegalStateException e) {
                return false;
            }
        }
    }

    private boolean isBlocked(String phoneNumber) {
        if (TextUtils.isEmpty(phoneNumber)) {
            Log.i(TAG, "isBlocked: NOT BLOCKED; empty #");
            return false;
        }

        final String inE164 = Utils.getE164Number(getContext(), phoneNumber, null); // may be empty.

        final Cursor c = mDbHelper.getReadableDatabase().rawQuery(
                "SELECT " +
                BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER + "," +
                BlockedNumberContract.BlockedNumbers.COLUMN_E164_NUMBER +
                " FROM " + BlockedNumberDatabaseHelper.Tables.BLOCKED_NUMBERS +
                " WHERE " + BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER + "=?1" +
                " OR (?2 != '' AND " +
                        BlockedNumberContract.BlockedNumbers.COLUMN_E164_NUMBER + "=?2)",
                new String[] {phoneNumber, inE164}
                );
        try {
            while (c.moveToNext()) {
                final String original = c.getString(0);
                final String e164 = c.getString(1);
                Log.i(TAG, String.format("isBlocked: BLOCKED; number=%s, e164=%s, foundOrig=%s, "
                                + "foundE164=%s",
                        piiHandle(phoneNumber),
                        piiHandle(inE164),
                        piiHandle(original),
                        piiHandle(e164)));
                return true;
            }
        } finally {
            c.close();
        }
        // No match found.
        Log.i(TAG, String.format("isBlocked: NOT BLOCKED; number=%s, e164=%s",
                piiHandle(phoneNumber), piiHandle(inE164)));
        return false;
    }

    private boolean canCurrentUserBlockUsers() {
        int currentUserId = getContext().getUserId();

        if (!android.multiuser.Flags.allowMainUserToAccessBlockedNumberProvider()) {
            UserManager userManager = getContext().getSystemService(UserManager.class);
            // Allow USER_SYSTEM and managed profile to block users
            return (currentUserId == UserHandle.USER_SYSTEM ||
                (userManager != null && userManager.isManagedProfile(currentUserId)));
        } else {
            // Allow SYSTEM user and users with messaging support to block users
            return (currentUserId == UserHandle.USER_SYSTEM
                || isMainUserOrManagedProfile(currentUserId));
        }
    }

    private boolean isMainUserOrManagedProfile(int currentUserId) {
        UserManager userManager = getContext().getSystemService(UserManager.class);
        // Only MAIN User and Managed profile users can have full messaging support.
        return userManager != null
        && (userManager.isMainUser() || userManager.isManagedProfile(currentUserId));
    }

    private void notifyEmergencyContact() {
        long sec = getBlockSuppressSecondsFromCarrierConfig();
        long millisToWrite = sec < 0
                ? BLOCKING_DISABLED_FOREVER : System.currentTimeMillis() + (sec * 1000);
        writeBlockSuppressionExpiryTimePref(millisToWrite);
        writeEmergencyCallNotificationPref(true);
        notifyBlockSuppressionStateChange();
    }

    private void endBlockSuppression() {
        // Nothing to do if blocks are not being suppressed.
        if (getBlockSuppressionStatus().isSuppressed) {
            writeBlockSuppressionExpiryTimePref(0);
            writeEmergencyCallNotificationPref(false);
            notifyBlockSuppressionStateChange();
        }
    }

    private SystemContract.BlockSuppressionStatus getBlockSuppressionStatus() {
        SharedPreferences pref = getContext().getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
        long blockSuppressionExpiryTimeMillis = pref.getLong(BLOCK_SUPPRESSION_EXPIRY_TIME_PREF, 0);
        boolean isSuppressed = blockSuppressionExpiryTimeMillis == BLOCKING_DISABLED_FOREVER
                || System.currentTimeMillis() < blockSuppressionExpiryTimeMillis;
        return new SystemContract.BlockSuppressionStatus(isSuppressed,
                blockSuppressionExpiryTimeMillis);
    }

    private int shouldSystemBlockNumber(String phoneNumber, Bundle extras) {
        if (getBlockSuppressionStatus().isSuppressed) {
            return BlockedNumberContract.STATUS_NOT_BLOCKED;
        }
        if (isEmergencyNumber(phoneNumber)) {
            return BlockedNumberContract.STATUS_NOT_BLOCKED;
        }

        int blockReason = BlockedNumberContract.STATUS_NOT_BLOCKED;
        if (extras != null && !extras.isEmpty()) {
            // check enhanced blocking setting
            boolean contactExist = extras.getBoolean(BlockedNumberContract.EXTRA_CONTACT_EXIST);
            int presentation = extras.getInt(BlockedNumberContract.EXTRA_CALL_PRESENTATION);
            switch (presentation) {
                case TelecomManager.PRESENTATION_ALLOWED:
                    if (getEnhancedBlockSetting(
                            SystemContract.ENHANCED_SETTING_KEY_BLOCK_UNREGISTERED)
                                    && !contactExist) {
                        blockReason = BlockedNumberContract.STATUS_BLOCKED_NOT_IN_CONTACTS;
                    }
                    break;
                case TelecomManager.PRESENTATION_RESTRICTED:
                    if (getEnhancedBlockSetting(
                            SystemContract.ENHANCED_SETTING_KEY_BLOCK_PRIVATE)) {
                        blockReason = BlockedNumberContract.STATUS_BLOCKED_RESTRICTED;
                    }
                    break;
                case TelecomManager.PRESENTATION_PAYPHONE:
                    if (getEnhancedBlockSetting(
                            SystemContract.ENHANCED_SETTING_KEY_BLOCK_PAYPHONE)) {
                        blockReason = BlockedNumberContract.STATUS_BLOCKED_PAYPHONE;
                    }
                    break;
                case TelecomManager.PRESENTATION_UNKNOWN:
                    if (getEnhancedBlockSetting(
                            SystemContract.ENHANCED_SETTING_KEY_BLOCK_UNKNOWN)) {
                        blockReason = BlockedNumberContract.STATUS_BLOCKED_UNKNOWN_NUMBER;
                    }
                    break;
                case TelecomManager.PRESENTATION_UNAVAILABLE:
                    if (getEnhancedBlockSetting(
                                    SystemContract.ENHANCED_SETTING_KEY_BLOCK_UNKNOWN)) {
                        blockReason = BlockedNumberContract.STATUS_BLOCKED_UNAVAILABLE;
                    }
                    break;
                default:
                    break;
            }
        }
        if (blockReason == BlockedNumberContract.STATUS_NOT_BLOCKED && isBlocked(phoneNumber)) {
            blockReason = BlockedNumberContract.STATUS_BLOCKED_IN_LIST;
        }
        return blockReason;
    }

    private boolean shouldShowEmergencyCallNotification() {
        return isEnhancedCallBlockingEnabledByPlatform()
                && (isShowCallBlockingDisabledNotificationAlways()
                        || isAnyEnhancedBlockingSettingEnabled())
                && getBlockSuppressionStatus().isSuppressed
                && getEnhancedBlockSetting(
                        SystemContract.ENHANCED_SETTING_KEY_SHOW_EMERGENCY_CALL_NOTIFICATION);
    }

    private PersistableBundle getCarrierConfig() {
        CarrierConfigManager configManager = (CarrierConfigManager) getContext().getSystemService(
                Context.CARRIER_CONFIG_SERVICE);
        PersistableBundle carrierConfig = configManager.getConfig();
        if (carrierConfig == null) {
            carrierConfig = configManager.getDefaultConfig();
        }
        return carrierConfig;
    }

    private boolean isEnhancedCallBlockingEnabledByPlatform() {
        return getCarrierConfig().getBoolean(
                CarrierConfigManager.KEY_SUPPORT_ENHANCED_CALL_BLOCKING_BOOL);
    }

    private boolean isShowCallBlockingDisabledNotificationAlways() {
        return getCarrierConfig().getBoolean(
                CarrierConfigManager.KEY_SHOW_CALL_BLOCKING_DISABLED_NOTIFICATION_ALWAYS_BOOL);
    }

    private boolean isAnyEnhancedBlockingSettingEnabled() {
        return getEnhancedBlockSetting(SystemContract.ENHANCED_SETTING_KEY_BLOCK_UNREGISTERED)
                || getEnhancedBlockSetting(SystemContract.ENHANCED_SETTING_KEY_BLOCK_PRIVATE)
                || getEnhancedBlockSetting(SystemContract.ENHANCED_SETTING_KEY_BLOCK_PAYPHONE)
                || getEnhancedBlockSetting(SystemContract.ENHANCED_SETTING_KEY_BLOCK_UNKNOWN);
    }

    private boolean getEnhancedBlockSetting(String key) {
        SharedPreferences pref = getContext().getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
        return pref.getBoolean(key, false);
    }

    private void setEnhancedBlockSetting(String key, boolean value) {
        SharedPreferences pref = getContext().getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = pref.edit();
        editor.putBoolean(key, value);
        editor.apply();
    }

    private void writeEmergencyCallNotificationPref(boolean show) {
        if (!isEnhancedCallBlockingEnabledByPlatform()) {
            return;
        }
        setEnhancedBlockSetting(
                SystemContract.ENHANCED_SETTING_KEY_SHOW_EMERGENCY_CALL_NOTIFICATION, show);
    }

    private void writeBlockSuppressionExpiryTimePref(long expiryTimeMillis) {
        SharedPreferences pref = getContext().getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = pref.edit();
        editor.putLong(BLOCK_SUPPRESSION_EXPIRY_TIME_PREF, expiryTimeMillis);
        editor.apply();
    }

    private long getBlockSuppressSecondsFromCarrierConfig() {
        CarrierConfigManager carrierConfigManager =
                getContext().getSystemService(CarrierConfigManager.class);
        int carrierConfigValue = carrierConfigManager.getConfig().getInt
                (CarrierConfigManager.KEY_DURATION_BLOCKING_DISABLED_AFTER_EMERGENCY_INT);
        boolean isValidValue = carrierConfigValue <= MAX_BLOCKING_DISABLED_DURATION_SECONDS;
        return isValidValue ? carrierConfigValue : CarrierConfigManager.getDefaultConfig().getInt(
                CarrierConfigManager.KEY_DURATION_BLOCKING_DISABLED_AFTER_EMERGENCY_INT);
    }

    /**
     * Returns {@code false} when the caller is not root, the user selected dialer, the
     * default SMS app or a carrier app.
     */
    private boolean checkForPrivilegedApplications() {
        if (Binder.getCallingUid() == Process.ROOT_UID) {
            return true;
        }

        final String callingPackage = getCallingPackage();
        if (TextUtils.isEmpty(callingPackage)) {
            Log.w(TAG, "callingPackage not accessible");
        } else {
            final TelecomManager telecom = getContext().getSystemService(TelecomManager.class);

            if (callingPackage.equals(telecom.getDefaultDialerPackage())
                    || callingPackage.equals(telecom.getSystemDialerPackage())) {
                return true;
            }
            final AppOpsManager appOps = getContext().getSystemService(AppOpsManager.class);
            if (appOps.noteOp(AppOpsManager.OP_WRITE_SMS,
                    Binder.getCallingUid(), callingPackage) == AppOpsManager.MODE_ALLOWED) {
                return true;
            }

            final TelephonyManager telephonyManager =
                    getContext().getSystemService(TelephonyManager.class);
            final long token = Binder.clearCallingIdentity();
            try {
                return telephonyManager.checkCarrierPrivilegesForPackageAnyPhone(callingPackage) ==
                        TelephonyManager.CARRIER_PRIVILEGE_STATUS_HAS_ACCESS;
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
        return false;
    }

    private void notifyBlockSuppressionStateChange() {
        Intent intent = new Intent(SystemContract.ACTION_BLOCK_SUPPRESSION_STATE_CHANGED);
        getContext().sendBroadcast(intent, Manifest.permission.READ_BLOCKED_NUMBERS);
    }

    private void enforceReadPermission() {
        checkForPermission(android.Manifest.permission.READ_BLOCKED_NUMBERS);
    }

    private void enforceReadPermissionAndMainUser() {
        checkForPermissionAndMainUser(android.Manifest.permission.READ_BLOCKED_NUMBERS);
    }

    private void enforceWritePermissionAndMainUser() {
        checkForPermissionAndMainUser(android.Manifest.permission.WRITE_BLOCKED_NUMBERS);
    }

    private void checkForPermissionAndMainUser(String permission) {
        checkForPermission(permission);
        if (!canCurrentUserBlockUsers()) {
            throwCurrentUserNotPermittedSecurityException();
        }
    }

    private void checkForPermission(String permission) {
        boolean permitted = passesSystemPermissionCheck(permission)
                || checkForPrivilegedApplications() || isSelf();
        if (!permitted) {
            throwSecurityException();
        }
    }

    private void enforceSystemReadPermissionAndMainUser() {
        enforceSystemPermissionAndUser(android.Manifest.permission.READ_BLOCKED_NUMBERS);
    }

    private void enforceSystemWritePermissionAndMainUser() {
        enforceSystemPermissionAndUser(android.Manifest.permission.WRITE_BLOCKED_NUMBERS);
    }

    private void enforceSystemPermissionAndUser(String permission) {
        if (!canCurrentUserBlockUsers()) {
            throwCurrentUserNotPermittedSecurityException();
        }

        if (!passesSystemPermissionCheck(permission)) {
            throwSecurityException();
        }
    }

    private boolean passesSystemPermissionCheck(String permission) {
        return getContext().checkCallingPermission(permission)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isSelf() {
        return ALLOW_SELF_CALL && Binder.getCallingPid() == Process.myPid();
    }

    private void throwSecurityException() {
        throw new SecurityException("Caller must be system, default dialer or default SMS app");
    }

    private void throwCurrentUserNotPermittedSecurityException() {
        throw new SecurityException("The current user cannot perform this operation");
    }
}
