package com.benten.senyas;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A camera owned by this screen's view lifecycle, shared by practice and classroom self-view. */
public class CameraPreviewFragment extends Fragment {
    interface FeedbackListener {
        void onSignFeedback(int label, SequenceRecognizer.Prediction prediction);
        void onSignFeedbackUnavailable(int label, boolean unsupported);
    }
    private static final String ASKED = "asked_camera_permission";
    private PreviewView preview;
    private HandOverlayView overlay;
    private TextView status;
    private MaterialButton action;
    private ProcessCameraProvider provider;
    private Preview previewUseCase;
    private ImageAnalysis analysis;
    private Camera camera;
    private HandTracker tracker;
    private ExecutorService executor;
    private boolean binding, requestingPermission;
    private int generation;
    private int targetLabel = -1, targetRevision;

    public void setTargetLabel(int label) {
        targetLabel = label;
        ++targetRevision;
        if (getArguments() != null) getArguments().putInt("target_label", label);
        if (tracker != null) tracker.setTarget(label, targetRevision);
    }

    private final ActivityResultLauncher<String> permission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                CameraDiagnostics.event("camera_permission_result granted=" + granted);
                requestingPermission = false;
                if (getView() != null) refresh();
            });

    public static CameraPreviewFragment newInstance(boolean trackHands) {
        CameraPreviewFragment fragment = new CameraPreviewFragment();
        Bundle args = new Bundle();
        args.putBoolean("track_hands", trackHands);
        fragment.setArguments(args);
        return fragment;
    }

    public static CameraPreviewFragment newInstance(boolean trackHands, int label) {
        CameraPreviewFragment fragment = newInstance(trackHands);
        fragment.requireArguments().putInt("target_label", label);
        return fragment;
    }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent,
                             @Nullable Bundle state) {
        return inflater.inflate(R.layout.fragment_camera_preview, parent, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        targetLabel = requireArguments().getInt("target_label", -1);
        preview = view.findViewById(R.id.camera_preview);
        preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        preview.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        overlay = view.findViewById(R.id.hand_overlay);
        overlay.setVisibility(tracksHands() ? View.VISIBLE : View.GONE);
        status = view.findViewById(R.id.camera_status);
        action = view.findViewById(R.id.camera_action);
        action.setOnClickListener(v -> {
            if (hasPermission()) {
                stopCamera();
                startCamera();
            } else if (permissionBlocked()) {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", requireContext().getPackageName(), null)));
            } else {
                askPermission();
            }
        });
    }

    private boolean tracksHands() { return requireArguments().getBoolean("track_hands"); }

    private boolean hasPermission() {
        return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean askedBefore() {
        return requireContext().getSharedPreferences(SenyasApp.PREFS, Context.MODE_PRIVATE)
                .getBoolean(ASKED, false);
    }

    private boolean permissionBlocked() {
        return askedBefore() && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA);
    }

    private void askPermission() {
        if (requestingPermission) return;
        CameraDiagnostics.event("camera_permission_requested");
        requestingPermission = true;
        action.setEnabled(false);
        requireContext().getSharedPreferences(SenyasApp.PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(ASKED, true).apply();
        permission.launch(Manifest.permission.CAMERA);
    }

    @Override public void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        if (hasPermission()) {
            startCamera();
        } else {
            stopCamera();
            showAction(R.string.camera_permission_needed,
                    permissionBlocked() ? R.string.open_camera_settings : R.string.camera_allow);
            if (!askedBefore() && !requestingPermission) askPermission();
        }
    }

    private void startCamera() {
        if (binding || previewUseCase != null || getView() == null) return;
        binding = true;
        CameraDiagnostics.event("camera_start tracking=" + tracksHands() + " permission=" + hasPermission());
        final int request = ++generation;
        final View owner = requireView();
        status.setText(R.string.camera_starting);
        action.setVisibility(View.GONE);
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(requireContext());
        future.addListener(() -> {
            if (getView() != owner || generation != request) return;
            binding = false;
            if (!isResumed()) return;
            try {
                provider = future.get();
                CameraDiagnostics.event("camera_provider_ready");
                boolean front = provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA);
                CameraSelector selector = front ? CameraSelector.DEFAULT_FRONT_CAMERA
                        : CameraSelector.DEFAULT_BACK_CAMERA;
                CameraDiagnostics.event("camera_selected front=" + front);
                if (!provider.hasCamera(selector)) {
                    showAction(R.string.camera_no_hardware, R.string.try_again);
                    return;
                }
                ResolutionSelector resolution = new ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(new ResolutionStrategy(new Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build();
                previewUseCase = new Preview.Builder().setResolutionSelector(resolution).build();
                previewUseCase.setSurfaceProvider(preview.getSurfaceProvider());
                if (tracksHands()) {
                    executor = Executors.newSingleThreadExecutor();
                    tracker = new HandTracker(requireContext(), front, new HandTracker.Listener() {
                        @Override public void onHands(List<List<NormalizedLandmark>> hands, int width, int height) {
                            owner.post(() -> {
                                if (getView() != owner || request != generation || !isResumed()) return;
                                overlay.setHands(hands, width, height);
                                status.setText(hands.isEmpty() ? getString(R.string.camera_show_hands)
                                        : getResources().getQuantityString(R.plurals.camera_hands, hands.size(), hands.size()));
                            });
                        }
                        @Override public void onError() {
                            owner.post(() -> {
                                if (getView() == owner && request == generation) {
                                    overlay.clear();
                                    status.setText(R.string.camera_tracking_unavailable);
                                }
                            });
                        }
                        @Override public void onFeedback(int label, int revision, SequenceRecognizer.Prediction result) {
                            owner.post(() -> {
                                if (getView() != owner || request != generation || !isResumed()
                                        || label != targetLabel || revision != targetRevision) return;
                                Fragment parent = getParentFragment();
                                if (parent instanceof FeedbackListener)
                                    ((FeedbackListener) parent).onSignFeedback(label, result);
                            });
                        }
                        @Override public void onFeedbackUnavailable(int label, int revision, boolean unsupported) {
                            owner.post(() -> {
                                if (getView() != owner || request != generation || !isResumed()
                                        || label != targetLabel || revision != targetRevision) return;
                                Fragment parent = getParentFragment();
                                if (parent instanceof FeedbackListener)
                                    ((FeedbackListener) parent).onSignFeedbackUnavailable(label, unsupported);
                            });
                        }
                    });
                    tracker.setTarget(targetLabel, targetRevision);
                    analysis = new ImageAnalysis.Builder().setResolutionSelector(resolution)
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                    analysis.setAnalyzer(executor, tracker);
                    camera = provider.bindToLifecycle(getViewLifecycleOwner(), selector, previewUseCase, analysis);
                } else {
                    camera = provider.bindToLifecycle(getViewLifecycleOwner(), selector, previewUseCase);
                }
                CameraDiagnostics.event("camera_bound tracking=" + tracksHands());
                preview.getPreviewStreamState().removeObservers(getViewLifecycleOwner());
                preview.getPreviewStreamState().observe(getViewLifecycleOwner(), stream -> {
                    if (request == generation && stream == PreviewView.StreamState.STREAMING) {
                        CameraDiagnostics.event("camera_preview_streaming");
                        status.setText(tracksHands() ? R.string.camera_show_hands : R.string.camera_live);
                        action.setVisibility(View.GONE);
                    }
                });
                camera.getCameraInfo().getCameraState().observe(getViewLifecycleOwner(), state -> {
                    if (request == generation && state.getError() != null) {
                        CameraDiagnostics.event("camera_state_error code=" + state.getError().getCode());
                        overlay.clear();
                        showAction(R.string.camera_cannot_open, R.string.try_again);
                    }
                });
                preview.setKeepScreenOn(true);
            } catch (Exception error) {
                CameraDiagnostics.event("camera_binding_failed type=" + error.getClass().getName());
                android.util.Log.e("SenyasCamera", "Camera binding failed", error);
                stopCamera();
                showAction(R.string.camera_cannot_open, R.string.try_again);
            }
        }, ContextCompat.getMainExecutor(requireContext()));
    }

    private void showAction(int message, int button) {
        status.setText(message);
        action.setText(button);
        action.setEnabled(!requestingPermission);
        action.setVisibility(View.VISIBLE);
    }

    private void stopCamera() {
        if (binding || previewUseCase != null) CameraDiagnostics.event("camera_stopping");
        ++generation;
        binding = false;
        if (camera != null && getView() != null) {
            camera.getCameraInfo().getCameraState().removeObservers(getViewLifecycleOwner());
        }
        if (analysis != null) analysis.clearAnalyzer();
        if (tracker != null) tracker.stop();
        if (provider != null) {
            if (previewUseCase != null) provider.unbind(previewUseCase);
            if (analysis != null) provider.unbind(analysis);
        }
        if (executor != null) {
            if (tracker != null) executor.execute(tracker::close);
            executor.shutdown();
        }
        tracker = null;
        executor = null;
        analysis = null;
        previewUseCase = null;
        camera = null;
        if (preview != null) preview.setKeepScreenOn(false);
        if (overlay != null) overlay.clear();
    }

    @Override public void onStop() {
        stopCamera();
        super.onStop();
    }

    @Override public void onDestroyView() {
        stopCamera();
        preview = null;
        overlay = null;
        status = null;
        action = null;
        super.onDestroyView();
    }
}
