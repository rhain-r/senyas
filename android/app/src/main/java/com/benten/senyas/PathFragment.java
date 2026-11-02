package com.benten.senyas;

import android.graphics.Rect;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

/**
 * Learning path tab (Duolingo-style): each scenario is a unit and each of its signs a round node on a
 * winding path, from the signed-in account's progress. A passed sign (best score 85+) is done (blue
 * check), the first sign not passed yet is current (yellow hand, START), the signs after it are locked.
 * Done and current open the lesson at that sign.
 */
public class PathFragment extends Fragment implements StatusTint {

    // Horizontal wiggle of the nodes, in dp
    private static final int[] WIGGLE = {0, 40, 64, 40, 0, -40, -64, -40};

    private LinearLayout units;
    private ScrollView scroll;
    private boolean scrolled; // brought the current sign into view once for this view

    @Override
    public int statusColor() {
        return R.color.senyas_blue;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle b) {
        return inflater.inflate(R.layout.fragment_path, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle b) {
        units = v.findViewById(R.id.units);
        scroll = v.findViewById(R.id.path_scroll);
        // After a rotation the ScrollView restores its own position; don't jump away from it.
        scrolled = b != null;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (units == null) return;
        Repo.save(); // keeps the Practice Room's latest scores before showing progress
        render();
    }

    private void render() {
        Progress pr = Repo.progress();
        Scenario resume = LearnFragment.resumeScenario(pr);
        LayoutInflater inf = LayoutInflater.from(requireContext());
        View target = null; // the current sign of the scenario the learner is on

        units.removeAllViews();
        for (int u = 0; u < Repo.SCENARIOS.size(); u++) {
            Scenario sc = Repo.SCENARIOS.get(u);
            int[] scores = pr.scores(sc);
            int total = sc.signs.length, passed = pr.passed(sc);
            int current = -1;
            for (int k = 0; k < scores.length; k++) {
                if (scores[k] < Progress.PASS) { current = k; break; }
            }

            View unit = inf.inflate(R.layout.item_path_unit, units, false);
            ((TextView) unit.findViewById(R.id.tv_unit)).setText(getString(R.string.path_unit, u + 1));
            ((TextView) unit.findViewById(R.id.tv_unit_title)).setText(sc.title(requireContext()));
            ((TextView) unit.findViewById(R.id.tv_unit_meta)).setText(passed == 0
                    ? getResources().getQuantityString(R.plurals.scenario_signs, total, total)
                    : getResources().getQuantityString(R.plurals.signs_passed, total, passed, total));
            LinearLayout nodes = unit.findViewById(R.id.nodes);

            for (int k = 0; k < total; k++) {
                final int idx = k;
                View row = inf.inflate(R.layout.item_path_node, nodes, false);
                View col = row.findViewById(R.id.node_col);
                View node = row.findViewById(R.id.node);
                ImageView icon = row.findViewById(R.id.node_icon);
                col.setTranslationX(Ui.dp(requireContext(), WIGGLE[k % WIGGLE.length]));

                String word = sc.signs[k][0];
                if (scores[k] >= Progress.PASS) {               // done
                    node.setBackgroundResource(R.drawable.bg_node_done);
                    icon.setImageResource(R.drawable.ic_check);
                    icon.setColorFilter(requireContext().getColor(R.color.white));
                    node.setContentDescription(getString(R.string.path_node_done, word));
                    node.setOnClickListener(x -> openSign(sc, idx));
                } else if (k == current) {                      // current
                    node.setBackgroundResource(R.drawable.bg_node_current);
                    icon.setImageResource(R.drawable.ic_hand);
                    icon.setColorFilter(requireContext().getColor(R.color.navy));
                    row.findViewById(R.id.tv_start).setVisibility(View.VISIBLE);
                    node.setContentDescription(getString(R.string.path_node_current, word));
                    node.setOnClickListener(x -> openSign(sc, idx));
                    if (target == null || sc.id == resume.id) target = node;
                } else {                                        // locked
                    node.setBackgroundResource(R.drawable.bg_node_locked);
                    icon.setImageResource(R.drawable.ic_lock);
                    icon.setColorFilter(requireContext().getColor(R.color.outline));
                    node.setContentDescription(getString(R.string.path_node_locked, word));
                    node.setOnClickListener(x -> Toast.makeText(requireContext(),
                            R.string.path_locked_toast, Toast.LENGTH_SHORT).show());
                }
                nodes.addView(row);
            }
            units.addView(unit);
        }

        // Bring the current sign into view the first time the path is shown.
        if (!scrolled && target != null) {
            scrolled = true;
            View node = target;
            scroll.post(() -> {
                if (!isAdded()) return;
                Rect r = new Rect(0, 0, node.getWidth(), node.getHeight());
                scroll.offsetDescendantRectToMyCoords(node, r);
                scroll.scrollTo(0, Math.max(0, r.top - scroll.getHeight() / 3));
            });
        }
    }

    private void openSign(Scenario sc, int idx) {
        ((MainActivity) requireActivity()).open(SignLessonFragment.newInstance(sc.id, idx));
    }
}
