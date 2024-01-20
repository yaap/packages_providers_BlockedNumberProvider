package com.android.providers.blockednumber;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import android.app.backup.BackupDataInput;
import android.test.AndroidTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.IOException;

@RunWith(JUnit4.class)
public class BlockedNumberBackupAgentTest extends AndroidTestCase {

    private BlockedNumberBackupAgent mBlockedNumberBackupAgent;

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();
        initMocks(this);

        mBlockedNumberBackupAgent = new BlockedNumberBackupAgent();
    }

    /**
     * Verifies that attempting to restore from a version newer than what the backup agent defines
     * will result in no restored rows.
     */
    @Test
    public void testRestoreFromHigherVersion() throws IOException {
        BackupDataInput backupDataInput = Mockito.mock(BackupDataInput.class);
        mBlockedNumberBackupAgent.onRestore(backupDataInput, Integer.MAX_VALUE, null);

        verify(backupDataInput, never()).readNextHeader();
    }
}
