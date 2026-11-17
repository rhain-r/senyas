package com.benten.senyas;

import static org.junit.Assert.*;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipFile;

public class DiagnosticJournalTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void restartPreservesTheCameraMilestoneFromThePreviousProcess() throws Exception {
        File directory = temporary.newFolder();
        DiagnosticJournal first = new DiagnosticJournal(directory);
        first.start();
        first.event("hand_tracker_initializing");
        DiagnosticJournal second = new DiagnosticJournal(directory);
        second.start();
        second.event("application_started");
        String previous = Files.readString(new File(directory, "previous-run.txt").toPath());
        assertTrue(previous.contains("hand_tracker_initializing"));
        assertFalse(previous.contains("application_started"));
    }

    @Test public void journalAndCopiedSystemTraceStayBounded() throws Exception {
        File directory = temporary.newFolder();
        DiagnosticJournal journal = new DiagnosticJournal(directory);
        journal.start();
        String oversized = "x".repeat(200_000);
        journal.event(oversized);
        journal.event("camera_stopped");
        File log = new File(directory, "current-run.txt");
        assertTrue(log.length() <= 65_536);
        assertTrue(Files.readString(log.toPath()).contains("camera_stopped"));
        journal.save("exit-info.txt", oversized.getBytes(StandardCharsets.UTF_8), 1_024);
        assertEquals(1_024, new File(directory, "exit-info.txt").length());
    }

    @Test public void exportIncludesOnlyDiagnosticFiles() throws Exception {
        File directory = temporary.newFolder();
        DiagnosticJournal journal = new DiagnosticJournal(directory);
        journal.start();
        journal.event("camera_bound");
        Files.writeString(new File(directory, "unrelated-private-file.txt").toPath(), "private");
        journal.save("exit-info.txt", "native crash signal 11".getBytes(StandardCharsets.UTF_8), 1_024);
        File archive = temporary.newFile("diagnostics.zip");
        journal.export(archive);
        try (ZipFile zip = new ZipFile(archive)) {
            assertNotNull(zip.getEntry("current-run.txt"));
            assertNotNull(zip.getEntry("exit-info.txt"));
            assertNull(zip.getEntry("unrelated-private-file.txt"));
            assertTrue(new String(zip.getInputStream(zip.getEntry("exit-info.txt")).readAllBytes(),
                    StandardCharsets.UTF_8).contains("signal 11"));
        }
    }
}
