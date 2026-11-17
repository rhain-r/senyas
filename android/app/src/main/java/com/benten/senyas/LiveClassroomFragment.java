package com.benten.senyas;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.util.Locale;

/** Classroom with a real local self-view. Remote calling and shared drawing still need a backend. */
public class LiveClassroomFragment extends Fragment implements DarkScreen {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int remaining;
    private String reference;
    private boolean admitted;
    private String tutor = "Your tutor";
    private TextView tvSession;
    private LinearLayout chatList;
    private ScrollView chatScroll;

    public static LiveClassroomFragment newInstance(String reference) {
        LiveClassroomFragment f = new LiveClassroomFragment();
        Bundle a = new Bundle();
        a.putString("ref", reference);
        f.setArguments(a);
        return f;
    }

    /** Tutor side: "other" is the learner's name. */
    public static LiveClassroomFragment newInstance(String reference, String other) {
        LiveClassroomFragment f = newInstance(reference);
        f.requireArguments().putString("other", other);
        return f;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle b) {
        reference = getArguments() == null ? "" : getArguments().getString("ref", "");
        admitted = Repo.canJoinSession(reference, System.currentTimeMillis());
        if (!admitted) {
            Fragment oldCamera = getChildFragmentManager().findFragmentById(R.id.classroom_camera);
            if (oldCamera != null) getChildFragmentManager().beginTransaction().remove(oldCamera).commitNow();
        }
        return inflater.inflate(admitted ? R.layout.fragment_live_classroom : R.layout.fragment_session_blocked, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle b) {
        if (!admitted) {
            v.findViewById(R.id.btn_back).setOnClickListener(x -> requireActivity().getOnBackPressedDispatcher().onBackPressed());
            return;
        }
        Repo.TutorSession session = Repo.sessionByReference(reference);
        tutor = Repo.accountRole() == Repo.ROLE_TUTOR ? session.learner : session.tutor;

        tvSession = v.findViewById(R.id.tv_session);
        if (getChildFragmentManager().findFragmentById(R.id.classroom_camera) == null) {
            getChildFragmentManager().beginTransaction()
                    .replace(R.id.classroom_camera, CameraPreviewFragment.newInstance(false)).commit();
        }
        chatList = v.findViewById(R.id.chat_list);
        chatScroll = v.findViewById(R.id.chat_scroll);
        View board = v.findViewById(R.id.board_card);
        View chat = v.findViewById(R.id.chat_card);
        WhiteboardView wb = v.findViewById(R.id.whiteboard);
        EditText et = v.findViewById(R.id.et_chat);

        v.findViewById(R.id.btn_chat).setOnClickListener(x -> { board.setVisibility(View.GONE); chat.setVisibility(View.VISIBLE); });
        v.findViewById(R.id.btn_board).setOnClickListener(x -> { chat.setVisibility(View.GONE); board.setVisibility(View.VISIBLE); });
        v.findViewById(R.id.btn_clear).setOnClickListener(x -> wb.clear());
        v.findViewById(R.id.btn_send).setOnClickListener(x -> {
            String t = et.getText().toString().trim();
            if (t.isEmpty()) return;
            addMessage(getString(R.string.chat_you, t), true);
            et.setText("");
        });
        View.OnClickListener leave = x -> {
            Toast.makeText(requireContext(), R.string.toast_left_class, Toast.LENGTH_SHORT).show();
            requireActivity().getOnBackPressedDispatcher().onBackPressed();
        };
        v.findViewById(R.id.btn_leave).setOnClickListener(leave);
        v.findViewById(R.id.btn_back).setOnClickListener(leave);

    }

    @Override public void onStart() { super.onStart(); if (admitted && tvSession != null) tick(); }
    @Override public void onStop() { handler.removeCallbacksAndMessages(null); super.onStop(); }

    @Override
    public void onDestroyView() {
        handler.removeCallbacksAndMessages(null);
        tvSession = null;
        chatList = null;
        chatScroll = null;
        super.onDestroyView();
    }

    private void tick() {
        long now = System.currentTimeMillis();
        if (!Repo.canJoinSession(reference, now)) {
            tvSession.setText(R.string.session_not_open);
            if (isResumed() && !getParentFragmentManager().isStateSaved()) {
                admitted = false;
                Toast.makeText(requireContext(), R.string.session_not_open, Toast.LENGTH_LONG).show();
                requireActivity().getOnBackPressedDispatcher().onBackPressed();
            } else handler.postDelayed(this::tick, 1000);
            return;
        }
        remaining = (int) ((Repo.sessionByReference(reference).endAtMillis - now + 999) / 1000);
        tvSession.setText(getString(R.string.session_line, tutor, remaining / 60, remaining % 60));
        if (remaining > 0) {
            handler.postDelayed(this::tick, 1000);
        }
    }

    private void addMessage(String text, boolean mine) {
        TextView tv = new TextView(requireContext());
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTextColor(requireContext().getColor(R.color.navy));
        tv.setBackgroundResource(R.drawable.bg_tile);
        tv.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                requireContext().getColor(mine ? R.color.paper_blue : R.color.paper_grey)));
        int p = Ui.dp(requireContext(), 10);
        tv.setPadding(p, p / 2, p, p / 2);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(requireContext(), 6);
        lp.gravity = mine ? android.view.Gravity.END : android.view.Gravity.START;
        chatList.addView(tv, lp);
        chatScroll.post(() -> chatScroll.fullScroll(View.FOCUS_DOWN));
    }
}
