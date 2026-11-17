package com.benten.senyas;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.pm.ProviderInfo;
import android.net.Uri;

import androidx.core.content.FileProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.zip.ZipFile;

@RunWith(AndroidJUnit4.class)
public class CameraDiagnosticsTest {
    @Test public void applicationRecordsCameraStagesAndExportProviderRejectsPrivateFiles() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue("The diagnostics application must actually be registered", context.getApplicationContext() instanceof SenyasApp);
        CameraDiagnostics.event("diagnostic_test_camera_stage");
        File directory = new File(context.getFilesDir(), "camera-diagnostics");
        File archiveDirectory = new File(context.getCacheDir(), "diagnostics");
        assertTrue(archiveDirectory.isDirectory() || archiveDirectory.mkdirs());
        File archive = new File(archiveDirectory, "test-diagnostics.zip");
        new DiagnosticJournal(directory).export(archive);
        try (ZipFile zip = new ZipFile(archive)) {
            assertNotNull(zip.getEntry("current-run.txt"));
            String log = new String(DiagnosticJournal.readBounded(zip.getInputStream(zip.getEntry("current-run.txt")), 65_536),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(log.contains("diagnostic_test_camera_stage"));
        }
        String authority = context.getPackageName() + ".diagnostics";
        ProviderInfo provider = context.getPackageManager().resolveContentProvider(authority, 0);
        assertNotNull(provider);
        assertFalse(provider.exported);
        Uri shared = FileProvider.getUriForFile(context, authority, archive);
        assertEquals("content", shared.getScheme());
        try {
            FileProvider.getUriForFile(context, authority, new File(context.getFilesDir(), "senyas_accounts.xml"));
            fail("Private app files must not be exportable through the diagnostic provider");
        } catch (IllegalArgumentException expected) {
            // Only the diagnostic cache subdirectory can be shared.
        } finally {
            archive.delete();
        }
    }
}
