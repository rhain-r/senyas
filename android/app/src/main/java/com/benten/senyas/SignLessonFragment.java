package com.benten.senyas;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.chip.Chip;
import com.google.android.material.progressindicator.LinearProgressIndicator;

/**
 * "Learn this sign": the sign's signed video with slow motion and captions. Opened from a scenario
 * (with practice) or from a dictionary search result (practice only for signs that have a scenario).
 */
public class SignLessonFragment extends Fragment implements StatusTint {

    public static SignLessonFragment newInstance(int scenarioId, int signIndex) {
        SignLessonFragment f = new SignLessonFragment();
        Bundle a = new Bundle();
        a.putInt("sc", scenarioId);
        a.putInt("idx", signIndex);
        f.setArguments(a);
        return f;
    }

    public static SignLessonFragment newInstanceForWord(String word, String english) {
        SignLessonFragment f = new SignLessonFragment();
        Bundle a = new Bundle();
        a.putString("word", word);
        a.putString("english", english);
        f.setArguments(a);
        return f;
    }

    @Override
    public int statusColor() {
        return R.color.surface;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle b) {
        return inflater.inflate(R.layout.fragment_sign_lesson, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle b) {
        Bundle args = requireArguments();
        boolean fromDictionary = args.containsKey("word");
        String word, english;
        int sc = -1, idx = -1;
        if (fromDictionary) {
            word = args.getString("word");
            english = args.getString("english");
            int[] where = Repo.findSign(word); // practice is only offered for signs that have a scenario
            if (where != null) { sc = where[0]; idx = where[1]; }
        } else {
            sc = args.getInt("sc");
            idx = args.getInt("idx");
            Scenario scenario = Repo.SCENARIOS.get(sc);
            word = scenario.signs[idx][0];
            english = scenario.signs[idx][1];
        }
        final int scId = sc, signIdx = idx;

        v.findViewById(R.id.btn_back).setOnClickListener(x ->
                requireActivity().getOnBackPressedDispatcher().onBackPressed());

        LinearProgressIndicator bar = v.findViewById(R.id.lesson_progress);
        TextView count = v.findViewById(R.id.tv_count);
        if (fromDictionary) {
            bar.setVisibility(View.INVISIBLE);
            count.setText(R.string.dictionary_title);
        } else {
            Scenario scenario = Repo.SCENARIOS.get(scId);
            int total = scenario.signs.length;
            String position = getString(R.string.sign_x_of_y, signIdx + 1, total);
            bar.setProgress((signIdx + 1) * 100 / total);
            bar.setContentDescription(position);
            count.setText(getString(R.string.practice_join, scenario.title(requireContext()), position));
        }
        ((TextView) v.findViewById(R.id.tv_word)).setText(word);
        ((TextView) v.findViewById(R.id.tv_translation)).setText(english);

        TextView caption = v.findViewById(R.id.tv_caption);
        caption.setText(getString(R.string.practice_join, word, english));
        Chip captions = v.findViewById(R.id.chip_captions);
        captions.setOnCheckedChangeListener((btn, checked) ->
                caption.setVisibility(checked ? View.VISIBLE : View.GONE));

        // The signed demonstration: our own clip if the app has one, else an FSL-105 clip, else none yet.
        String clip = SignVideos.find(requireContext(), word);
        SignVideoView video = v.findViewById(R.id.sign_video);
        View box = v.findViewById(R.id.video_box);
        box.setClipToOutline(true); // rounded corners also clip the video
        Chip slow = v.findViewById(R.id.chip_slow);
        View replay = v.findViewById(R.id.btn_replay);
        View watchAgain = v.findViewById(R.id.btn_watch_again);
        if (clip != null) {
            video.setVisibility(View.VISIBLE);
            video.setAsset(clip);
            video.setContentDescription(getString(R.string.sign_video_desc, word, english));
            v.findViewById(R.id.video_placeholder).setVisibility(View.GONE);
            v.findViewById(R.id.tv_video_credit).setVisibility(SignVideos.isFsl105(clip) ? View.VISIBLE : View.GONE);
            slow.setOnCheckedChangeListener((btn, checked) -> video.setSpeed(checked ? 0.5f : 1f));
            replay.setOnClickListener(x -> video.replay());
            watchAgain.setOnClickListener(x -> video.replay());
        } else {
            v.findViewById(R.id.iv_play).setVisibility(View.GONE);
            ((TextView) v.findViewById(R.id.tv_video_state)).setText(R.string.no_sign_video);
            slow.setEnabled(false);
            replay.setEnabled(false);
            replay.setAlpha(0.4f);
            watchAgain.setVisibility(View.GONE);
        }

        TextView practice = v.findViewById(R.id.btn_practice);
        if (scId < 0) {
            // A dictionary sign with no scenario: nothing to practise, so the main button goes back.
            practice.setText(R.string.done);
            practice.setOnClickListener(x ->
                    requireActivity().getOnBackPressedDispatcher().onBackPressed());
        } else {
            practice.setOnClickListener(x -> {
                Repo.startPractice(scId, signIdx);
                ((MainActivity) requireActivity()).selectTab(R.id.nav_practice);
            });
        }
    }
}
