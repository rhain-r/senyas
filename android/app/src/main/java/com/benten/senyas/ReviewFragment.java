package com.benten.senyas;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.Calendar;

/**
 * Review booking + payment method. Card entry is UI only: nothing is stored or charged, and only the
 * masked last 4 digits are kept. A real build hands card entry to a PCI-compliant payment provider.
 */
public class ReviewFragment extends Fragment {

    private TextInputLayout tilNumber, tilExpiry, tilCvv, tilName;
    private TextInputEditText etNumber, etExpiry, etCvv, etName;
    private View cardForm;
    private MaterialButton btnPay;
    private ScrollView scroll;
    private int kind; // 0 GCash, 1 Maya, 2 Card

    /** The chosen slot travels in the arguments, so the screen survives the app being closed. */
    public static ReviewFragment newInstance(int lessonId, String day, int startMin, int duration, int price) {
        ReviewFragment f = new ReviewFragment();
        Bundle a = new Bundle();
        a.putInt("id", lessonId);
        a.putString("day", day);
        a.putInt("start", startMin);
        a.putInt("duration", duration);
        a.putInt("price", price);
        f.setArguments(a);
        return f;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle b) {
        return inflater.inflate(R.layout.fragment_review, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle b) {
        Ui.bindTopBar(v, getString(R.string.title_review_booking), requireActivity());
        if (!restoreDraft()) {
            // The lesson is gone: back to the schedule once the screen is restored.
            v.post(() -> {
                if (isAdded()) getParentFragmentManager().popBackStack();
            });
            return;
        }
        Lesson l = Repo.draftLesson;

        ((TextView) v.findViewById(R.id.tv_title)).setText(l.title);
        ((TextView) v.findViewById(R.id.tv_tutor)).setText(getString(R.string.tutor_line, l.tutor));

        LinearLayout rows = v.findViewById(R.id.rows);
        Ui.addRow(rows, getString(R.string.row_date), Repo.draftDay);
        Ui.addRow(rows, getString(R.string.row_time), getString(R.string.time_pht, Ui.timeRange(Repo.draftStartMin, Repo.draftDuration)));
        Ui.addRow(rows, getString(R.string.row_duration), getString(R.string.minutes_count, Repo.draftDuration));
        Ui.addRow(rows, getString(R.string.row_rate), Ui.peso(Repo.draftPrice));
        ((TextView) v.findViewById(R.id.tv_total)).setText(getString(R.string.total_line, Ui.peso(Repo.draftPrice)));

        scroll = v.findViewById(R.id.scroll);
        cardForm = v.findViewById(R.id.card_form);
        btnPay = v.findViewById(R.id.btn_pay);
        tilNumber = v.findViewById(R.id.til_card_number);
        tilExpiry = v.findViewById(R.id.til_expiry);
        tilCvv = v.findViewById(R.id.til_cvv);
        tilName = v.findViewById(R.id.til_card_name);
        etNumber = v.findViewById(R.id.et_card_number);
        etExpiry = v.findViewById(R.id.et_expiry);
        etCvv = v.findViewById(R.id.et_cvv);
        etName = v.findViewById(R.id.et_card_name);

        etNumber.addTextChangedListener(new Fmt(tilNumber) {
            @Override String format(String d) {
                if (d.length() > 16) d = d.substring(0, 16);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < d.length(); i++) {
                    if (i > 0 && i % 4 == 0) sb.append(' ');
                    sb.append(d.charAt(i));
                }
                return sb.toString();
            }
            @Override void after(String d) {
                String brand = brandOf(d);
                tilNumber.setHelperText(brand.isEmpty() ? null : brand); // brand shown as text, not colour
            }
        });
        etExpiry.addTextChangedListener(new Fmt(tilExpiry) {
            @Override String format(String d) {
                if (d.length() > 4) d = d.substring(0, 4);
                return d.length() > 2 ? d.substring(0, 2) + "/" + d.substring(2) : d;
            }
        });
        etCvv.addTextChangedListener(new Fmt(tilCvv) {
            @Override String format(String d) { return d.length() > 4 ? d.substring(0, 4) : d; }
        });
        etName.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) {}
            @Override public void onTextChanged(CharSequence s, int a, int c, int d) {}
            @Override public void afterTextChanged(Editable e) { tilName.setError(null); }
        });

        MaterialButtonToggleGroup toggle = v.findViewById(R.id.toggle_payment);
        toggle.addOnButtonCheckedListener((g, id, checked) -> {
            if (!checked) return;
            kind = id == R.id.btn_maya ? 1 : id == R.id.btn_card ? 2 : 0;
            Repo.draftPayKind = kind;
            updatePayUi(true);
        });
        int start = Repo.draftPayKind;
        toggle.check(start == 1 ? R.id.btn_maya : start == 2 ? R.id.btn_card : R.id.btn_gcash);

        btnPay.setOnClickListener(x -> {
            if (kind == 2) {
                String last4 = validateCard();
                if (last4 == null) return;
                Repo.draftPayment = getString(R.string.pay_card_masked, last4);
            } else {
                Repo.draftPayment = kind == 1 ? "Maya" : "GCash";
            }
            Repo.Booking bk = Repo.confirmDraft(); // TODO (Firebase batch): write booking to Firestore + start payment
            if (bk == null) return; // already booked by an earlier tap
            ((MainActivity) requireActivity()).showConfirmation(ConfirmedFragment.newInstance(bk.reference));
        });
    }

    /** Uses the draft in memory, or rebuilds it from the arguments after the app was closed. */
    private boolean restoreDraft() {
        if (Repo.draftLesson != null) return true;
        Bundle a = getArguments();
        Lesson l = a == null ? null : Repo.find(a.getInt("id", -1));
        if (l == null) return false;
        Repo.draftLesson = l;
        Repo.draftDay = a.getString("day", Repo.draftDay);
        Repo.draftStartMin = a.getInt("start", Repo.draftStartMin);
        Repo.draftDuration = a.getInt("duration", Repo.draftDuration);
        Repo.draftPrice = a.getInt("price", Repo.draftPrice);
        return true;
    }

    private void updatePayUi(boolean scrollDown) {
        boolean card = kind == 2;
        cardForm.setVisibility(card ? View.VISIBLE : View.GONE);
        btnPay.setText(card ? getString(R.string.pay_amount, Ui.peso(Repo.draftPrice))
                : getString(R.string.continue_to_payment));
        if (card && scrollDown) scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    /** Returns the last 4 digits when the form is valid, otherwise shows errors and returns null. */
    private String validateCard() {
        tilNumber.setError(null);
        tilExpiry.setError(null);
        tilCvv.setError(null);
        tilName.setError(null);
        boolean ok = true;
        View first = null;

        String num = digits(etNumber);
        if (num.length() != 16 || !luhn(num)) {
            tilNumber.setError(getString(R.string.err_card_number));
            ok = false;
            first = etNumber;
        }

        String exp = digits(etExpiry);
        if (exp.length() != 4 || Integer.parseInt(exp.substring(0, 2)) < 1 || Integer.parseInt(exp.substring(0, 2)) > 12) {
            tilExpiry.setError(getString(R.string.err_card_expiry));
            ok = false;
            if (first == null) first = etExpiry;
        } else {
            Calendar now = Calendar.getInstance();
            int curYear = now.get(Calendar.YEAR) % 100, curMonth = now.get(Calendar.MONTH) + 1;
            int mm = Integer.parseInt(exp.substring(0, 2)), yy = Integer.parseInt(exp.substring(2));
            if (yy < curYear || (yy == curYear && mm < curMonth)) {
                tilExpiry.setError(getString(R.string.err_card_expired));
                ok = false;
                if (first == null) first = etExpiry;
            }
        }

        if (digits(etCvv).length() < 3) {
            tilCvv.setError(getString(R.string.err_card_cvv));
            ok = false;
            if (first == null) first = etCvv;
        }
        if (etName.getText() == null || etName.getText().toString().trim().isEmpty()) {
            tilName.setError(getString(R.string.err_card_name));
            ok = false;
            if (first == null) first = etName;
        }
        if (!ok) {
            if (first != null) first.requestFocus();
            return null;
        }
        return num.substring(12);
    }

    private String digits(TextInputEditText et) {
        return et.getText() == null ? "" : et.getText().toString().replaceAll("\\D", "");
    }

    private static boolean luhn(String d) {
        int sum = 0;
        boolean alt = false;
        for (int i = d.length() - 1; i >= 0; i--) {
            int n = d.charAt(i) - '0';
            if (alt) { n *= 2; if (n > 9) n -= 9; }
            sum += n;
            alt = !alt;
        }
        return sum % 10 == 0;
    }

    private String brandOf(String d) {
        if (d.startsWith("4")) return getString(R.string.brand_visa);
        if (d.length() >= 2) {
            int p = Integer.parseInt(d.substring(0, 2));
            if (p >= 51 && p <= 55) return getString(R.string.brand_mastercard);
        }
        if (d.length() >= 4) {
            int q = Integer.parseInt(d.substring(0, 4));
            if (q >= 2221 && q <= 2720) return getString(R.string.brand_mastercard);
        }
        return "";
    }

    /** Re-formats digits as the user types and clears that field's error. */
    private abstract static class Fmt implements TextWatcher {
        private final TextInputLayout til;
        private boolean busy;

        Fmt(TextInputLayout til) { this.til = til; }

        abstract String format(String digits);
        void after(String digits) {}

        @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) {}
        @Override public void onTextChanged(CharSequence s, int a, int c, int d) {}

        @Override
        public void afterTextChanged(Editable e) {
            if (busy) return;
            busy = true;
            String d = e.toString().replaceAll("\\D", "");
            String out = format(d);
            if (!out.equals(e.toString())) e.replace(0, e.length(), out);
            busy = false;
            til.setError(null);
            after(out.replaceAll("\\D", ""));
        }
    }
}
