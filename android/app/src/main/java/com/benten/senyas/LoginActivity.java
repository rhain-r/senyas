package com.benten.senyas;

import android.content.Intent;
import android.os.Bundle;
import android.util.Patterns;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

public class LoginActivity extends AppCompatActivity {

    private TextInputLayout tilName, tilEmail, tilPassword;
    private TextInputEditText etName, etEmail, etPassword;
    private MaterialButtonToggleGroup toggle;
    private MaterialButton btnSubmit;
    private TextView tvHeadline, tvSwitch;
    private View roleSection;
    private MaterialCardView[] roleCards;
    private AccountStore accounts;
    private final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();

    private boolean registerMode = true;
    private int selectedRole = 0; // 0 learner, 1 deaf student, 2 deaf tutor

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        accounts = new AccountStore(this);
        if (accounts.current() != null) { enterApp(); return; }
        setContentView(R.layout.activity_login);

        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        tilName = findViewById(R.id.til_name);
        tilEmail = findViewById(R.id.til_email);
        tilPassword = findViewById(R.id.til_password);
        etName = findViewById(R.id.et_name);
        etEmail = findViewById(R.id.et_email);
        etPassword = findViewById(R.id.et_password);
        toggle = findViewById(R.id.toggle_mode);
        btnSubmit = findViewById(R.id.btn_submit);
        tvHeadline = findViewById(R.id.tv_headline);
        tvSwitch = findViewById(R.id.tv_switch);
        roleSection = findViewById(R.id.role_section);

        roleCards = new MaterialCardView[]{
                findViewById(R.id.role_learner),
                findViewById(R.id.role_student),
                findViewById(R.id.role_tutor)
        };
        for (int i = 0; i < roleCards.length; i++) {
            final int index = i;
            roleCards[i].setOnClickListener(v -> {
                selectedRole = index;
                updateRoleCards();
            });
        }
        updateRoleCards();

        toggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) setMode(checkedId == R.id.btn_register);
        });
        tvSwitch.setOnClickListener(v -> setMode(!registerMode));
        btnSubmit.setOnClickListener(v -> submit());
        findViewById(R.id.btn_admin_login).setOnClickListener(v -> {
            setMode(false);
            tilEmail.setHelperText(getString(R.string.admin_login_help));
            etEmail.requestFocus();
        });

        setMode(true);
    }

    private void setMode(boolean register) {
        registerMode = register;
        tilEmail.setHelperText(null);
        int wanted = register ? R.id.btn_register : R.id.btn_login;
        if (toggle.getCheckedButtonId() != wanted) toggle.check(wanted);

        tilName.setVisibility(register ? View.VISIBLE : View.GONE);
        roleSection.setVisibility(register ? View.VISIBLE : View.GONE);
        tvHeadline.setText(register ? R.string.login_headline_create : R.string.login_headline_welcome);
        btnSubmit.setText(register ? R.string.btn_create_account : R.string.btn_log_in);
        tvSwitch.setText(register ? R.string.login_switch_to_login : R.string.login_switch_to_register);
        clearErrors();
    }

    private void updateRoleCards() {
        for (int i = 0; i < roleCards.length; i++) {
            boolean on = i == selectedRole;
            roleCards[i].setStrokeWidth(dp(on ? 2 : 1));
            roleCards[i].setStrokeColor(getColor(on ? R.color.accent : R.color.outline));
            roleCards[i].setCardBackgroundColor(getColor(on ? R.color.blue_container : R.color.surface));
            roleCards[i].setSelected(on);
        }
    }

    private void clearErrors() {
        tilName.setError(null);
        tilEmail.setError(null);
        tilPassword.setError(null);
    }

    private void submit() {
        clearErrors();
        boolean ok = true;

        String name = text(etName);
        String email = text(etEmail);
        String password = etPassword.getText() == null ? "" : etPassword.getText().toString();

        if (registerMode && name.isEmpty()) {
            tilName.setError(getString(R.string.err_name));
            ok = false;
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            tilEmail.setError(getString(R.string.err_email));
            ok = false;
        }
        if (password.length() < 8) {
            tilPassword.setError(getString(R.string.account_password_requirement));
            ok = false;
        }
        if (!ok) return;

        final boolean registering = registerMode;
        final int registrationRole = selectedRole;
        btnSubmit.setEnabled(false);
        worker.execute(() -> {
            AccountStore.Account account = null;
            int error = R.string.account_login_failed;
            try {
                account = registering ? accounts.register(name, email, password, registrationRole)
                        : accounts.login(email, password);
            } catch (AccountStore.AccountException failure) {
                error = failure.problem == AccountStore.Problem.EMAIL_EXISTS ? R.string.account_email_exists
                        : R.string.account_registration_failed;
            }
            final AccountStore.Account result = account;
            final int message = error;
            runOnUiThread(() -> {
                if (isDestroyed() || isFinishing()) return;
                btnSubmit.setEnabled(true);
                if (result != null) enterApp();
                else tilPassword.setError(getString(message));
            });
        });
    }

    private void enterApp() {
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }

    @Override protected void onDestroy() { worker.shutdown(); super.onDestroy(); }

    /** "ana.reyes@email.com" -> "Ana Reyes" (the real name will come from the Firebase profile). */
    private String nameFromEmail(String email) {
        String local = email.contains("@") ? email.substring(0, email.indexOf('@')) : email;
        String[] parts = local.replaceAll("[._\\-]+", " ").trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.length() == 0 ? email : sb.toString();
    }

    private String text(TextInputEditText et) {
        return et.getText() == null ? "" : et.getText().toString().trim();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
