package com.benten.senyas;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

/** Camera access is off: open the phone's settings, or watch the signed lesson instead. */
public class CameraUnavailableFragment extends Fragment implements StatusTint {

    /** True when this opened because access was refused (not the long-press preview). */
    private boolean deniedAtOpen;

    @Override
    public int statusColor() {
        return R.color.surface; // the screen's own background, up to the top edge
    }

    @Override
    public void onCreate(@Nullable Bundle b) {
        super.onCreate(b);
        deniedAtOpen = !cameraAllowed();
    }

    @Override
    public void onResume() {
        super.onResume();
        // Back from the phone's settings with access turned on: return to the Practice Room.
        if (deniedAtOpen && cameraAllowed()) requireActivity().getSupportFragmentManager().popBackStack();
    }

    private boolean cameraAllowed() {
        return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle b) {
        return inflater.inflate(R.layout.fragment_camera_unavailable, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle b) {
        Ui.bindTopBar(v, getString(R.string.title_camera_unavailable), requireActivity());

        v.findViewById(R.id.btn_settings).setOnClickListener(x -> {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", requireContext().getPackageName(), null));
            startActivity(i);
        });
        v.findViewById(R.id.btn_watch).setOnClickListener(x -> {
            MainActivity act = (MainActivity) requireActivity();
            int sc = Math.max(Repo.practiceScenario, 0);
            act.selectTab(R.id.nav_learn);
            act.open(SignLessonFragment.newInstance(sc, Repo.practiceIndex));
        });
    }
}
