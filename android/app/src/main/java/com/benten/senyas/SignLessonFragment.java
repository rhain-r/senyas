package com.benten.senyas;

import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.VideoView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.chip.Chip;
import com.google.android.material.progressindicator.LinearProgressIndicator;

/** "Learn this sign" (Duolingo-style). Opened from the path (with practice) or from a dictionary result. */
public class SignLessonFragment extends Fragment implements StatusTint {

    private VideoView video;
    private MediaPlayer player;
    private Chip slow;
    private Uri videoUri;

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
    public int statusColor() { return R.color.surface; }

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
            count.setVisibility(View.GONE);
        } else {
            int total = Repo.SCENARIOS.get(scId).signs.length;
            bar.setProgress((signIdx + 1) * 100 / total);
            count.setText(getString(R.string.sign_x_of_y, signIdx + 1, total));
        }
        ((TextView) v.findViewById(R.id.tv_word)).setText(word);
        ((TextView) v.findViewById(R.id.tv_translation)).setText(english);

        TextView caption = v.findViewById(R.id.tv_caption);
        caption.setText(word + " · " + english);

        Chip captions = v.findViewById(R.id.chip_captions);
        captions.setOnCheckedChangeListener((btn, checked) ->
                caption.setVisibility(checked ? View.VISIBLE : View.GONE));

        slow = v.findViewById(R.id.chip_slow);
        video = v.findViewById(R.id.lesson_video);
        int resource = LessonVideos.forWord(word);
        boolean available = resource != 0;
        videoUri = available ? Uri.parse("android.resource://" + requireContext().getPackageName() + "/" + resource) : null;
        video.setVisibility(available ? View.VISIBLE : View.GONE);
        v.findViewById(R.id.tv_video_status).setVisibility(available ? View.GONE : View.VISIBLE);
        v.findViewById(R.id.tv_video_credit).setVisibility(available ? View.VISIBLE : View.GONE);
        caption.setVisibility(available && captions.isChecked() ? View.VISIBLE : View.GONE);
        captions.setEnabled(available);
        slow.setEnabled(available);
        slow.setOnCheckedChangeListener((button, checked) -> applySpeed());
        VideoView currentVideo = video;
        video.setOnPreparedListener(prepared -> {
            if (video != currentVideo || getView() == null) return;
            player = prepared;
            prepared.setLooping(true);
            prepared.setVolume(0f, 0f);
            if (isResumed()) {
                applySpeed();
                currentVideo.start();
            }
        });
        video.setOnErrorListener((failed, what, extra) -> {
            if (video != currentVideo || getView() == null) return true;
            player = null;
            currentVideo.setVisibility(View.GONE);
            TextView status = requireView().findViewById(R.id.tv_video_status);
            status.setText(R.string.lesson_video_error);
            status.setVisibility(View.VISIBLE);
            caption.setVisibility(View.GONE);
            return true;
        });
        View.OnClickListener replay = x -> {
            if (video != null && videoUri != null) {
                if (player == null) video.setVideoURI(videoUri);
                video.setVisibility(View.VISIBLE);
                v.findViewById(R.id.tv_video_status).setVisibility(View.GONE);
                caption.setVisibility(captions.isChecked() ? View.VISIBLE : View.GONE);
                video.seekTo(0);
                applySpeed();
                video.start();
            }
        };
        v.findViewById(R.id.btn_replay).setEnabled(available);
        v.findViewById(R.id.btn_replay).setAlpha(available ? 1f : 0.4f);
        v.findViewById(R.id.btn_watch_again).setVisibility(available ? View.VISIBLE : View.GONE);
        v.findViewById(R.id.btn_replay).setOnClickListener(replay);
        v.findViewById(R.id.btn_watch_again).setOnClickListener(replay);

        TextView practice = v.findViewById(R.id.btn_practice);
        View skip = v.findViewById(R.id.btn_lesson_skip);
        skip.setVisibility(scId < 0 ? View.GONE : View.VISIBLE);
        skip.setOnClickListener(x -> {
            Repo.startPractice(scId, signIdx);
            boolean hasNext = Repo.skipPracticeSign();
            ((MainActivity) requireActivity()).open(hasNext
                    ? SignLessonFragment.newInstance(scId, Repo.practiceIndex)
                    : new PracticeSummaryFragment());
        });
        if (scId < 0) {
            // Dictionary-only sign: nothing to practise, so the main button just goes back.
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

    private void applySpeed() {
        if (player == null) return;
        try {
            player.setPlaybackParams(new PlaybackParams().setSpeed(slow.isChecked() ? 0.5f : 1f));
        } catch (IllegalStateException | IllegalArgumentException ignored) {
            // A device decoder may not support changing speed; normal playback remains available.
        }
    }

    @Override public void onResume() {
        super.onResume();
        if (video != null && videoUri != null) {
            if (player == null) video.setVideoURI(videoUri);
            else applySpeed();
            video.start();
        }
    }

    @Override public void onPause() {
        if (video != null) video.pause();
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        if (video != null) {
            video.setOnPreparedListener(null);
            video.setOnErrorListener(null);
            video.stopPlayback();
        }
        video = null;
        player = null;
        slow = null;
        videoUri = null;
        super.onDestroyView();
    }
}
