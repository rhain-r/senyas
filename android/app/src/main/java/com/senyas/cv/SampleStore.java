package com.senyas.cv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Sign recordings kept on the phone (files/fsl_dataset/{label}/*.json). Export zips them for the
 * training script, so a Deaf tutor's recordings become training data at no cost.
 */
public final class SampleStore {
    private final File root;
    private final File exports;

    public SampleStore(Context context) {
        root = new File(context.getFilesDir(), "fsl_dataset");
        exports = new File(context.getCacheDir(), "exports");
    }

    public File save(SignSample sample) throws IOException {
        File dir = new File(root, sample.label);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Could not create " + dir);
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        File file = new File(dir, sample.label + "_" + stamp + ".json");
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(sample.toJson().getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    /** Recordings per label, in label order. */
    public Map<String, Integer> counts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (File dir : labelDirs()) {
            File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
            if (files != null && files.length > 0) counts.put(dir.getName(), files.length);
        }
        return counts;
    }

    public List<SignSample> loadAll() {
        List<SignSample> out = new ArrayList<>();
        for (File dir : labelDirs()) {
            File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
            if (files == null) continue;
            Arrays.sort(files);
            for (File f : files) {
                try {
                    out.add(read(f));
                } catch (IOException | org.json.JSONException ignored) {
                    // A damaged file is skipped rather than blocking the rest.
                }
            }
        }
        return out;
    }

    /** Removes the newest recording of a label (undo a bad take). */
    public boolean deleteLast(String label) {
        File[] files = new File(root, label).listFiles((d, n) -> n.endsWith(".json"));
        if (files == null || files.length == 0) return false;
        Arrays.sort(files);
        return files[files.length - 1].delete();
    }

    /** Zips every recording into one file for sharing. */
    public File exportZip() throws IOException {
        if (!exports.isDirectory() && !exports.mkdirs()) throw new IOException("Could not create " + exports);
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new Date());
        File zip = new File(exports, "senyas_fsl_recordings_" + stamp + ".zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            for (File dir : labelDirs()) {
                File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
                if (files == null) continue;
                for (File f : files) {
                    out.putNextEntry(new ZipEntry(dir.getName() + "/" + f.getName()));
                    // InputStream.transferTo needs Android 13; this app supports Android 8.
                    try (FileInputStream in = new FileInputStream(f)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                    out.closeEntry();
                }
            }
        }
        return zip;
    }

    private List<File> labelDirs() {
        File[] dirs = root.listFiles(File::isDirectory);
        List<File> out = dirs == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(dirs));
        out.sort((a, b) -> a.getName().compareTo(b.getName()));
        return out;
    }

    static SignSample read(File f) throws IOException, org.json.JSONException {
        JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        JSONArray frames = o.getJSONArray("frames");
        List<HandFrame> list = new ArrayList<>(frames.length());
        for (int i = 0; i < frames.length(); i++) {
            JSONObject fo = frames.getJSONObject(i);
            JSONArray hands = fo.getJSONArray("hands");
            List<Hand> hs = new ArrayList<>(hands.length());
            for (int h = 0; h < hands.length(); h++) {
                JSONObject ho = hands.getJSONObject(h);
                JSONArray lm = ho.getJSONArray("lm");
                float[] arr = new float[lm.length()];
                for (int k = 0; k < arr.length; k++) arr[k] = (float) lm.getDouble(k);
                hs.add(new Hand(ho.getString("label"), (float) ho.optDouble("score", 1.0), arr));
            }
            list.add(new HandFrame(fo.getLong("t"), hs));
        }
        return new SignSample(o.getString("label"), o.optString("source", ""), list);
    }
}
