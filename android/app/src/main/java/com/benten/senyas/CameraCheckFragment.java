package com.benten.senyas;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

/** A local camera check that does not join or change a scheduled session. */
public class CameraCheckFragment extends Fragment implements DarkScreen {
    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent,
                             @Nullable Bundle state) {
        return inflater.inflate(R.layout.fragment_camera_check, parent, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        Ui.bindTopBar(view, getString(R.string.camera_check_title), requireActivity());
        view.findViewById(R.id.btn_share_diagnostics).setOnClickListener(v ->
                CameraDiagnostics.share(requireActivity()));
        if (getChildFragmentManager().findFragmentById(R.id.camera_check_preview) == null) {
            getChildFragmentManager().beginTransaction()
                    .replace(R.id.camera_check_preview, CameraPreviewFragment.newInstance(false)).commit();
        }
    }
}
