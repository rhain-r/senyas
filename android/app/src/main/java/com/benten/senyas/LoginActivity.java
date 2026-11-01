package com.benten.senyas;

import android.content.Intent;
import android.os.Bundle;
import android.util.Patterns;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Create an account or log in to one saved on this phone (AccountStore). The role comes from the
 * account; "Admin console" in the log-in dropdown opens the admin screens for the class demo and
 * still needs the account's email and password. Passwords are only ever hashed, never logged or shown.
 */
public class LoginActivity extends AppCompatActivity {

    private TextInputLayout tilName, tilEmail, tilPassword;
    private TextInputEditText etName, etEmail, etPassword;
    private MaterialButtonToggleGroup toggle;
    private MaterialButton btnSubmit;
    private TextView tvHeadline, tvSwitch;
    private View roleSection;
    private MaterialCardView[] roleCards;
    private View loginRoleSection;
    private MaterialAutoCompleteTextView actvTarget;

    private SignIn signIn;
    private boolean registerMode = true, adminConsole, busy;
    private int selectedRole = 0; // 0 learner, 1 deaf student, 2 deaf tutor

    /**
     * Hashes the password off the main thread. Kept across rotation, so a sign-in still running
     * when the phone turns reaches the new screen instead of being lost.
     */
    public static class SignIn extends ViewModel {
        private final ExecutorService worker = Executors.newSingleThreadExecutor();
        /** The AccountStore result once the check is done; null while none is waiting. */
        final MutableLiveData<Integer> result = new MutableLiveData<>();
        boolean running, admin;
        String email;

        void start(AccountStore store, boolean register, String name, int role, String email, String password, boolean admin) {
            running = true;
            this.email = email;
            this.admin = admin;
            worker.execute(() -> {
                int r = register ? store.register(name, role, email, password) : store.check(email, password);
                if (register && r == AccountStore.EMAIL_TAKEN) {
                    // A retry after a lost result (e.g. the screen turned): the account already has this password.
                    int c = store.check(email, password);
                    if (c == AccountStore.OK || c == AccountStore.SUSPENDED) r = c;
                }
                result.postValue(r);
            });
        }

        @Override
        protected void onCleared() {
            worker.shutdown();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);
        signIn = new ViewModelProvider(this).get(SignIn.class);

        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
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
        loginRoleSection = findViewById(R.id.login_role_section);
        actvTarget = findViewById(R.id.actv_role);
        String[] targets = {getString(R.string.login_target_account), getString(R.string.login_target_admin)};
        actvTarget.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, targets));
        actvTarget.setOnItemClickListener((parent, view, position, id) -> adminConsole = position == 1);

        if (savedInstanceState != null) {
            registerMode = savedInstanceState.getBoolean("register", true);
            adminConsole = savedInstanceState.getBoolean("admin", false);
            selectedRole = savedInstanceState.getInt("role", 0);
        }

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

        setMode(registerMode);
        setBusy(signIn.running);
        signIn.result.observe(this, result -> {
            if (result == null) return;
            signIn.result.setValue(null); // handled: a later screen turn does not repeat it
            signIn.running = false;
            setBusy(false);
            onResult(result, signIn.email, signIn.admin);
        });
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean("register", registerMode);
        out.putBoolean("admin", adminConsole);
        out.putInt("role", selectedRole);
    }

    private void setMode(boolean register) {
        registerMode = register;
        int wanted = register ? R.id.btn_register : R.id.btn_login;
        if (toggle.getCheckedButtonId() != wanted) toggle.check(wanted);

        tilName.setVisibility(register ? View.VISIBLE : View.GONE);
        roleSection.setVisibility(register ? View.VISIBLE : View.GONE);
        loginRoleSection.setVisibility(register ? View.GONE : View.VISIBLE);
        if (!register) actvTarget.setText(actvTarget.getAdapter().getItem(adminConsole ? 1 : 0).toString(), false);
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
        if (busy) return;
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
        if (password.length() < 6) {
            tilPassword.setError(getString(R.string.err_password));
            ok = false;
        }
        if (!ok) {
            // In landscape the form scrolls: bring the first field with an error into view.
            (tilName.getError() != null ? etName : tilEmail.getError() != null ? etEmail : etPassword).requestFocus();
            return;
        }

        // TODO (Firebase batch): FirebaseAuth sign-in / registration replaces the local store.
        boolean register = registerMode, admin = !registerMode && adminConsole;
        int role = selectedRole;
        setBusy(true);
        signIn.start(new AccountStore(this), register, name, role, email, password, admin);
    }

    private void onResult(int result, String email, boolean admin) {
        switch (result) {
            case AccountStore.OK:
                Intent intent = new Intent(this, MainActivity.class);
                intent.putExtra(MainActivity.EXTRA_ACCOUNT, Account.key(email));
                intent.putExtra(MainActivity.EXTRA_ADMIN, admin);
                startActivity(intent);
                finish();
                break;
            case AccountStore.EMAIL_TAKEN:
                tilEmail.setError(getString(R.string.err_email_taken));
                etEmail.requestFocus();
                break;
            case AccountStore.NO_ACCOUNT:
                tilEmail.setError(getString(R.string.err_no_account));
                etEmail.requestFocus();
                break;
            case AccountStore.SUSPENDED:
                tilEmail.setError(getString(R.string.err_account_suspended));
                etEmail.requestFocus();
                break;
            default:
                tilPassword.setError(getString(R.string.err_wrong_password));
                etPassword.requestFocus();
                break;
        }
    }

    private void setBusy(boolean on) {
        busy = on;
        btnSubmit.setEnabled(!on);
        if (on) btnSubmit.setText(R.string.login_checking);
        else btnSubmit.setText(registerMode ? R.string.btn_create_account : R.string.btn_log_in);
    }

    private String text(TextInputEditText et) {
        return et.getText() == null ? "" : et.getText().toString().trim();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
