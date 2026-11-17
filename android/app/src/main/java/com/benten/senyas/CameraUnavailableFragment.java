package com.benten.senyas;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

public class CameraUnavailableFragment extends Fragment {

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
