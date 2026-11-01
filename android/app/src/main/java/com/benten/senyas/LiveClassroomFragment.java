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

/** Live classroom (UI only). Video streaming (60 fps) and shared drawing sync arrive in a later batch. */
public class LiveClassroomFragment extends Fragment implements DarkScreen {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int remaining = 42 * 60 + 18; // demo: session already running
    private String tutor;
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
        return inflater.inflate(R.layout.fragment_live_classroom, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle b) {
        String ref = requireArguments().getString("ref", "");
        tutor = getString(R.string.your_tutor);
        for (Repo.Booking bk : Repo.BOOKINGS) if (bk.reference.equals(ref)) tutor = bk.tutor;
        String other = requireArguments().getString("other");
        if (other != null) tutor = other;

        tvSession = v.findViewById(R.id.tv_session);
        chatList = v.findViewById(R.id.chat_list);
        chatScroll = v.findViewById(R.id.chat_scroll);
        View board = v.findViewById(R.id.board_card);
        View chat = v.findViewById(R.id.chat_card);
        WhiteboardView wb = v.findViewById(R.id.whiteboard);
        EditText et = v.findViewById(R.id.et_chat);

        addMessage(getString(R.string.chat_tutor_hello, tutor.split(" ")[0]), false);
        addMessage(getString(R.string.chat_you_ready), true);

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

        tick();
    }

    @Override
    public void onDestroyView() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroyView();
    }

    private void tick() {
        tvSession.setText(getString(R.string.session_line, tutor, remaining / 60, remaining % 60));
        if (remaining > 0) {
            remaining--;
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
