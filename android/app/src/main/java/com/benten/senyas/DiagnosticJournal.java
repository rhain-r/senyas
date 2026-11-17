package com.benten.senyas;

import java.io.File;
import java.io.IOException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Bounded, local diagnostic artifacts; contains no account or camera image data. */
final class DiagnosticJournal {
    private static final int LOG_LIMIT = 65_536;
    private static final String[] ARTIFACTS = {"current-run.txt", "previous-run.txt",
            "exit-info.txt", "java-crash.txt", "native-tombstone.pb"};
    private final File directory;

    DiagnosticJournal(File directory) { this.directory = directory; }

    synchronized void start() throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create diagnostic folder");
        File current = new File(directory, "current-run.txt");
        if (current.exists()) {
            File previous = new File(directory, "previous-run.txt");
            if (previous.exists() && !previous.delete()) throw new IOException("Cannot rotate diagnostics");
            if (!current.renameTo(previous)) throw new IOException("Cannot preserve previous diagnostics");
        }
        save("current-run.txt", new byte[0], LOG_LIMIT);
    }

    synchronized void event(String message) throws IOException {
        String stamp = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(new Date());
        byte[] entry = (stamp + " " + message + "\n").getBytes(StandardCharsets.UTF_8);
        // Keep the most recent events, including the stage immediately before a process death.
        File current = new File(directory, "current-run.txt");
        byte[] previous;
        try (FileInputStream input = new FileInputStream(current)) {
            previous = readBounded(input, LOG_LIMIT);
        }
        int entryLength = Math.min(entry.length, LOG_LIMIT / 2);
        int retained = Math.min(previous.length, LOG_LIMIT - entryLength);
        try (FileOutputStream output = new FileOutputStream(current)) {
            output.write(previous, previous.length - retained, retained);
            output.write(entry, 0, entryLength);
        }
    }

    synchronized void save(String name, byte[] bytes, int limit) throws IOException {
        try (FileOutputStream output = new FileOutputStream(new File(directory, name))) {
            output.write(bytes, 0, Math.min(bytes.length, limit));
        }
    }

    synchronized void export(File target) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(target))) {
            for (String name : ARTIFACTS) {
                File file = new File(directory, name);
                if (!file.isFile()) continue;
                zip.putNextEntry(new ZipEntry(name));
                try (FileInputStream input = new FileInputStream(file)) {
                    byte[] buffer = new byte[8_192];
                    int count;
                    while ((count = input.read(buffer)) != -1) zip.write(buffer, 0, count);
                }
                zip.closeEntry();
            }
        }
    }

    static byte[] readBounded(java.io.InputStream input, int limit) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8_192];
        while (output.size() < limit) {
            int count = input.read(buffer, 0, Math.min(buffer.length, limit - output.size()));
            if (count < 0) break;
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
}
