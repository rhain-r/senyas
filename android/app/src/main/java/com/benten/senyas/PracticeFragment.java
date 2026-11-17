package com.benten.senyas;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.progressindicator.LinearProgressIndicator;

/** Real camera practice. Scores and XP require an FSL recognizer, not just a detected hand. */
public class PracticeFragment extends Fragment implements DarkScreen, CameraPreviewFragment.FeedbackListener {
    private Scenario scenario;
    private int index;
    private int acceptedWindows;
    private long lastEvaluation = -1;
    private boolean matched;
    private boolean completedAttempt;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent, @Nullable Bundle state) {
        return inflater.inflate(R.layout.fragment_practice, parent, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        view.findViewById(R.id.btn_back).setOnClickListener(v ->
                ((MainActivity) requireActivity()).selectTab(R.id.nav_learn));
        view.findViewById(R.id.tv_xp_reward).setVisibility(View.GONE);
        ((TextView) view.findViewById(R.id.tv_score_title)).setText(R.string.score_keep);
        ((TextView) view.findViewById(R.id.tv_pct)).setText(R.string.match_pct_ungraded);
        ((ScoreBarView) view.findViewById(R.id.score_bar)).setScore(0);
        ((TextView) view.findViewById(R.id.tv_guide)).setText(R.string.practice_camera_guide);
        view.findViewById(R.id.btn_watch).setOnClickListener(v ->
                ((MainActivity) requireActivity()).open(SignLessonFragment.newInstance(scenario.id, index)));
        view.findViewById(R.id.btn_skip).setOnClickListener(v -> nextSign());
        bindSign();
        if (getChildFragmentManager().findFragmentById(R.id.practice_camera) == null) {
            getChildFragmentManager().beginTransaction()
                    .replace(R.id.practice_camera, CameraPreviewFragment.newInstance(true,
                            FslSigns.label(scenario.signs[index][0]))).commit();
        }
    }

    @Override public void onResume() {
        super.onResume();
        bindSign();
    }

    private void bindSign() {
        if (getView() == null) return;
        boolean changed = scenario == null || scenario.id != Repo.practiceScenario || index != Repo.practiceIndex;
        scenario = Repo.SCENARIOS.get(Repo.practiceScenario);
        index = Repo.practiceIndex;
        View view = requireView();
        String[] sign = scenario.signs[index];
        ((TextView) view.findViewById(R.id.tv_room_title)).setText(getString(R.string.sign_title, sign[0]));
        ((TextView) view.findViewById(R.id.tv_scenario)).setText(scenario.label);
        ((TextView) view.findViewById(R.id.tv_word)).setText(sign[0]);
        ((TextView) view.findViewById(R.id.tv_translation)).setText(sign[1]);
        ((LinearProgressIndicator) view.findViewById(R.id.progress))
                .setProgress((index + 1) * 100 / scenario.signs.length);
        if (changed) {
            acceptedWindows = 0; lastEvaluation = -1; matched = false; completedAttempt = false;
            ((ScoreBarView) view.findViewById(R.id.score_bar)).setScore(0);
            ((TextView) view.findViewById(R.id.tv_pct)).setText(R.string.match_pct_ungraded);
            ((TextView) view.findViewById(R.id.tv_score_title)).setText(R.string.score_keep);
            ((TextView) view.findViewById(R.id.tv_feedback)).setText(FslSigns.label(sign[0]) < 0
                    ? R.string.feedback_unsupported : R.string.feedback_prepare);
            ((TextView) view.findViewById(R.id.btn_skip)).setText(R.string.skip_sign);
            view.findViewById(R.id.tv_xp_reward).setVisibility(View.GONE);
            Fragment camera = getChildFragmentManager().findFragmentById(R.id.practice_camera);
            if (camera instanceof CameraPreviewFragment)
                ((CameraPreviewFragment) camera).setTargetLabel(FslSigns.label(sign[0]));
        }
    }

    @Override public void onSignFeedback(int label, SequenceRecognizer.Prediction result) {
        if (getView() == null || !isResumed() || scenario == null
                || label != FslSigns.label(scenario.signs[index][0]) || result.targetClassId != label || matched) return;
        if (completedAttempt && !result.completed && !result.ready) return;
        if (result.ready) completedAttempt = result.completed;
        if (result.evaluatedAtMs == lastEvaluation) return;
        lastEvaluation = result.evaluatedAtMs;
        acceptedWindows = result.accepted ? acceptedWindows + 1 : 0;
        int score = Math.min(84, result.scorePercent);
        TextView feedback = requireView().findViewById(R.id.tv_feedback);
        if (result.completed && result.accepted) {
            score = result.scorePercent;
            int previousXp = Repo.xp;
            if (Repo.recordVerifiedMatch(scenario.id, index, score)) {
                matched = true;
                ((TextView) requireView().findViewById(R.id.tv_score_title)).setText(R.string.score_matched);
                feedback.setText(R.string.feedback_matched);
                TextView reward = requireView().findViewById(R.id.tv_xp_reward);
                reward.setText("+10");
                reward.setVisibility(Repo.xp > previousXp ? View.VISIBLE : View.GONE);
                ((TextView) requireView().findViewById(R.id.btn_skip)).setText(R.string.practice_next_sign);
            } else feedback.setText(R.string.local_save_failed);
        } else {
            int message;
            switch (result.reason) {
                case "missing_hands": message = R.string.camera_show_hands; break;
                case "missing_body": message = R.string.feedback_show_body; break;
                case "insufficient_motion": message = R.string.feedback_move; break;
                case "warming_up": message = R.string.feedback_prepare; break;
                case "unknown_target": message = R.string.feedback_unsupported; break;
                default: message = result.accepted ? R.string.feedback_hold : R.string.feedback_try_again;
            }
            feedback.setText(message);
        }
        ((ScoreBarView) requireView().findViewById(R.id.score_bar)).setScore(score);
        ((TextView) requireView().findViewById(R.id.tv_pct)).setText(getString(R.string.match_pct, score));
    }

    @Override public void onSignFeedbackUnavailable(int label, boolean unsupported) {
        if (getView() == null || scenario == null || label != FslSigns.label(scenario.signs[index][0]) || matched) return;
        acceptedWindows = 0; lastEvaluation = -1;
        ((ScoreBarView) requireView().findViewById(R.id.score_bar)).setScore(0);
        ((TextView) requireView().findViewById(R.id.tv_pct)).setText(R.string.match_pct_ungraded);
        ((TextView) requireView().findViewById(R.id.tv_feedback)).setText(unsupported
                ? R.string.feedback_unsupported : R.string.feedback_unavailable);
    }

    @Override public void onDestroyView() {
        scenario = null; acceptedWindows = 0; lastEvaluation = -1; matched = false; completedAttempt = false;
        super.onDestroyView();
    }

    private void nextSign() {
        if (!Repo.skipPracticeSign()) {
            ((MainActivity) requireActivity()).open(new PracticeSummaryFragment());
        } else {
            bindSign();
        }
    }
}
