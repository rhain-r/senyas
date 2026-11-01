package com.senyas.cv;

import android.content.Context;
import android.content.res.AssetFileDescriptor;

import org.json.JSONObject;
import org.tensorflow.lite.Interpreter;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The team's custom-trained TensorFlow Lite model (made by ml/train.py), run on the phone with
 * LiteRT. Expects three files in assets: fsl_model.tflite, fsl_labels.txt and fsl_model.json.
 */
public final class TfliteSignClassifier implements SignClassifier {
    public static final String MODEL = "fsl_model.tflite";
    public static final String LABELS = "fsl_labels.txt";
    public static final String INFO = "fsl_model.json";

    private final Interpreter interpreter;
    private final List<String> labels;
    private final float[][][] input = new float[1][FeatureEncoder.SEQUENCE_LENGTH][FeatureEncoder.FEATURES];
    private final float[][] output;
    private final String name;

    private TfliteSignClassifier(Interpreter interpreter, List<String> labels, String name) {
        this.interpreter = interpreter;
        this.labels = labels;
        this.output = new float[1][labels.size()];
        this.name = name;
    }

    /** Null when the app has no trained model yet. Throws when the model does not fit this app version. */
    public static TfliteSignClassifier loadFromAssets(Context context) throws IOException {
        if (!exists(context, MODEL)) return null;
        List<String> labels = readLines(context, LABELS);
        String name = "Trained model (" + labels.size() + " signs)";
        if (exists(context, INFO)) {
            try {
                JSONObject info = new JSONObject(String.join("\n", readLines(context, INFO)));
                int version = info.optInt("featureVersion", FeatureEncoder.VERSION);
                int length = info.optInt("sequenceLength", FeatureEncoder.SEQUENCE_LENGTH);
                int features = info.optInt("features", FeatureEncoder.FEATURES);
                if (version != FeatureEncoder.VERSION || length != FeatureEncoder.SEQUENCE_LENGTH || features != FeatureEncoder.FEATURES) {
                    throw new IOException("The model was trained for feature version " + version
                            + " but this app uses version " + FeatureEncoder.VERSION + ". Retrain it with ml/train.py.");
                }
                double acc = info.optDouble("testAccuracy", -1);
                if (acc >= 0) name += String.format(java.util.Locale.US, ", %.0f%% test accuracy", acc * 100);
            } catch (org.json.JSONException e) {
                throw new IOException("assets/" + INFO + " is not valid JSON", e);
            }
        }
        Interpreter.Options options = new Interpreter.Options().setNumThreads(2);
        Interpreter interpreter = new Interpreter(map(context, MODEL), options);
        int outputs = interpreter.getOutputTensor(0).shape()[1];
        if (outputs != labels.size()) {
            interpreter.close();
            throw new IOException("The model has " + outputs + " outputs but " + LABELS + " lists " + labels.size() + " signs.");
        }
        return new TfliteSignClassifier(interpreter, labels, name);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public List<String> labels() {
        return labels;
    }

    @Override
    public synchronized List<Prediction> classify(float[][] sequence) {
        for (int t = 0; t < FeatureEncoder.SEQUENCE_LENGTH; t++) {
            System.arraycopy(sequence[t], 0, input[0][t], 0, FeatureEncoder.FEATURES);
        }
        interpreter.run(input, output);
        List<Prediction> out = new ArrayList<>(labels.size());
        for (int i = 0; i < labels.size(); i++) out.add(new Prediction(labels.get(i), output[0][i]));
        out.sort((a, b) -> Float.compare(b.probability, a.probability));
        return out;
    }

    @Override
    public void close() {
        interpreter.close();
    }

    private static boolean exists(Context context, String asset) {
        try (InputStream ignored = context.getAssets().open(asset)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static List<String> readLines(Context context, String asset) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(context.getAssets().open(asset), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.trim().isEmpty()) lines.add(line.trim());
            }
        }
        return lines;
    }

    private static MappedByteBuffer map(Context context, String asset) throws IOException {
        try (AssetFileDescriptor fd = context.getAssets().openFd(asset);
             FileInputStream in = new FileInputStream(fd.getFileDescriptor());
             FileChannel channel = in.getChannel()) {
            return channel.map(FileChannel.MapMode.READ_ONLY, fd.getStartOffset(), fd.getDeclaredLength());
        }
    }
}
