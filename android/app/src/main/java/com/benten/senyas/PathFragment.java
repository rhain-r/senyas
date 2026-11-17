package com.benten.senyas;

import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;

/**
 * Learning path TAB (Duolingo-style): each scenario is a "unit" and each sign is a round node on a
 * winding path. Done = blue check, current = yellow hand with START, locked = grey padlock.
 */
public class PathFragment extends Fragment implements StatusTint {

    // Horizontal wiggle of the nodes, in dp
    private static final int[] WIGGLE = {0, 40, 64, 40, 0, -40, -64, -40};

    private ScrollView pathScroll;
    private Runnable scrollToCurrent;
    private Handler scrollHandler;

    @Override
    public int statusColor() { return R.color.senyas_blue; }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle b) {
        return inflater.inflate(R.layout.fragment_path, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle b) {
        LinearLayout units = v.findViewById(R.id.units);
        ScrollView scroll = v.findViewById(R.id.path_scroll);
        LayoutInflater inf = LayoutInflater.from(requireContext());
        View[] firstCurrent = {null};

        for (int u = 0; u < Repo.SCENARIOS.size(); u++) {
            Scenario sc = Repo.SCENARIOS.get(u);
            View unit = inf.inflate(R.layout.item_path_unit, units, false);
            ((TextView) unit.findViewById(R.id.tv_unit)).setText(getString(R.string.path_unit, u + 1));
            ((TextView) unit.findViewById(R.id.tv_unit_title)).setText(sc.title);
            LinearLayout nodes = unit.findViewById(R.id.nodes);
            int resumeIndex = Repo.resumeSignIndex(sc.id);

            for (int k = 0; k < sc.signs.length; k++) {
                final int idx = k;
                View row = inf.inflate(R.layout.item_path_node, nodes, false);
                View col = row.findViewById(R.id.node_col);
                View node = row.findViewById(R.id.node);
                ImageView icon = row.findViewById(R.id.node_icon);
                col.setTranslationX(Ui.dp(requireContext(), WIGGLE[k % WIGGLE.length]));

                String word = sc.signs[k][0];
                if (Repo.pathScore(sc.id, k) >= 85) {           // verified as correct
                    node.setBackgroundResource(R.drawable.bg_node_done);
                    icon.setImageResource(R.drawable.ic_check);
                    icon.setColorFilter(getResources().getColor(R.color.white, null));
                    node.setContentDescription(getString(R.string.path_node_done, word));
                    node.setOnClickListener(x -> openSign(sc, idx));
                } else if (k <= resumeIndex) {                  // current or previously skipped
                    node.setBackgroundResource(R.drawable.bg_node_current);
                    icon.setImageResource(R.drawable.ic_hand);
                    icon.setColorFilter(getResources().getColor(R.color.navy, null));
                    row.findViewById(R.id.tv_start).setVisibility(k == resumeIndex ? View.VISIBLE : View.GONE);
                    node.setContentDescription(getString(R.string.path_node_current, word));
                    node.setOnClickListener(x -> openSign(sc, idx));
                    if (firstCurrent[0] == null && k == resumeIndex) firstCurrent[0] = node;
                } else {                                        // locked
                    node.setBackgroundResource(R.drawable.bg_node_locked);
                    icon.setImageResource(R.drawable.ic_lock);
                    icon.setColorFilter(getResources().getColor(R.color.outline, null));
                    node.setContentDescription(getString(R.string.path_node_locked, word));
                    node.setOnClickListener(x -> Toast.makeText(requireContext(),
                            R.string.path_locked_toast, Toast.LENGTH_SHORT).show());
                }
                nodes.addView(row);
            }
            units.addView(unit);
        }

        // Bring the current node into view
        if (firstCurrent[0] != null) {
            View target = firstCurrent[0];
            Lifecycle viewLifecycle = getViewLifecycleOwner().getLifecycle();
            int topOffset = Ui.dp(scroll.getContext(), 220);
            pathScroll = scroll;
            scrollToCurrent = () -> {
                if (viewLifecycle.getCurrentState() == Lifecycle.State.DESTROYED) return;
                Rect r = new Rect(0, 0, target.getWidth(), target.getHeight());
                scroll.offsetDescendantRectToMyCoords(target, r);
                scroll.scrollTo(0, Math.max(0, r.top - topOffset));
            };
            scrollHandler = scroll.getHandler();
            scroll.post(scrollToCurrent);
        }
    }

    @Override
    public void onDestroyView() {
        if (scrollToCurrent != null) {
            if (pathScroll != null) pathScroll.removeCallbacks(scrollToCurrent);
            // FragmentManager may have detached the view before this lifecycle callback.
            if (scrollHandler != null) scrollHandler.removeCallbacks(scrollToCurrent);
        }
        pathScroll = null;
        scrollToCurrent = null;
        scrollHandler = null;
        super.onDestroyView();
    }

    private void openSign(Scenario sc, int idx) {
        ((MainActivity) requireActivity()).open(SignLessonFragment.newInstance(sc.id, idx));
    }
}
