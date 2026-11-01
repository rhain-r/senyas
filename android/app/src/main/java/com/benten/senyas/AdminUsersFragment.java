package com.benten.senyas;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** Admin: see everyone on the platform and suspend or reactivate accounts. */
public class AdminUsersFragment extends Fragment {

    private int filter = 0; // 0 all, 1 learners (hearing + deaf students), 2 tutors
    private LinearLayout list;
    private TextView tvSub;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle b) {
        return inflater.inflate(R.layout.fragment_admin_list, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle b) {
        ((TextView) v.findViewById(R.id.tv_title)).setText(R.string.nav_users);
        tvSub = v.findViewById(R.id.tv_sub);
        list = v.findViewById(R.id.list);

        MaterialButtonToggleGroup toggle = v.findViewById(R.id.toggle_filter);
        toggle.setVisibility(View.VISIBLE);
        toggle.addOnButtonCheckedListener((g, id, checked) -> {
            if (!checked) return;
            filter = id == R.id.btn_f_learners ? 1 : id == R.id.btn_f_tutors ? 2 : 0;
            render();
        });
        toggle.check(R.id.btn_f_all);
        render();
    }

    private void render() {
        list.removeAllViews();
        tvSub.setText(getString(R.string.admin_users_sub, Repo.USERS.size()));
        LayoutInflater inf = LayoutInflater.from(requireContext());
        for (Repo.User u : Repo.USERS) {
            boolean isTutor = u.role == Repo.ROLE_TUTOR;
            if (filter == 1 && isTutor) continue;
            if (filter == 2 && !isTutor) continue;

            View row = inf.inflate(R.layout.item_admin_user, list, false);
            ((TextView) row.findViewById(R.id.tv_initials)).setText(Repo.initialsOf(u.name));
            ((TextView) row.findViewById(R.id.tv_name)).setText(u.name);
            ChipGroup tags = row.findViewById(R.id.tags);
            Ui.addTag(tags, Ui.roleLabel(requireContext(), u.role), Ui.Tag.BLUE);
            Ui.addTag(tags, getString(u.active ? R.string.tag_active : R.string.tag_suspended),
                    u.active ? Ui.Tag.GREEN : Ui.Tag.YELLOW);

            MaterialButton toggleBtn = row.findViewById(R.id.btn_toggle);
            toggleBtn.setText(u.active ? R.string.btn_suspend : R.string.btn_reactivate);
            toggleBtn.setOnClickListener(x -> {
                if (u.active && u.email != null && Repo.isSignedIn(u.email)) {
                    Toast.makeText(requireContext(), R.string.toast_cannot_suspend_self, Toast.LENGTH_LONG).show();
                } else if (u.active) {
                    new MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.suspend_title)
                            .setMessage(getString(R.string.suspend_message, u.name))
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(R.string.btn_suspend, (d, w) -> {
                                Repo.setActive(u, false); // TODO (Firebase batch): disable the account
                                Toast.makeText(requireContext(), R.string.toast_suspended, Toast.LENGTH_SHORT).show();
                                render();
                            })
                            .show();
                } else {
                    Repo.setActive(u, true);
                    Toast.makeText(requireContext(), R.string.toast_reactivated, Toast.LENGTH_SHORT).show();
                    render();
                }
            });
            list.addView(row);
        }
    }
}
