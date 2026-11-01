package com.benten.senyas;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.navigation.NavigationBarView;

/**
 * One shell, three experiences. The signed-in account's role (Repo.role) decides the bottom tabs:
 * Learner (Hearing Learner and Deaf Student), Tutor, or Admin (the admin console chosen at login).
 */
public class MainActivity extends AppCompatActivity {

    /** The signed-in account's email, and whether it opened the admin console. */
    public static final String EXTRA_ACCOUNT = "account", EXTRA_ADMIN = "admin";

    private NavigationBarView nav; // a bottom bar in portrait, a side rail in landscape
    private View root;
    private boolean tabShown = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // A fresh sign-in loads the account from the device. Rotation and other recreations keep
        // what is already loaded; after the process was killed, the intent loads it again.
        String email = getIntent().getStringExtra(EXTRA_ACCOUNT);
        boolean admin = getIntent().getBooleanExtra(EXTRA_ADMIN, false);
        if (savedInstanceState == null || !Repo.isSignedIn(email)) {
            AccountStore store = new AccountStore(this);
            Account account = store.find(email);
            if (account == null) {
                Toast.makeText(this, R.string.account_missing, Toast.LENGTH_LONG).show();
                startActivity(new Intent(this, LoginActivity.class));
                finish();
                return;
            }
            Repo.signIn(store, account, admin);
        }

        root = findViewById(R.id.root);
        // In landscape the camera cutout sits at one side of the screen; keep content clear of it.
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        // Dark screens (practice room, live classroom) tint the status-bar area dark.
        getSupportFragmentManager().registerFragmentLifecycleCallbacks(
                new FragmentManager.FragmentLifecycleCallbacks() {
                    @Override
                    public void onFragmentResumed(@NonNull FragmentManager fm, @NonNull Fragment f) {
                        setChrome(f instanceof DarkScreen);
                    }
                }, false);

        nav = findViewById(R.id.bottom_nav);
        nav.getMenu().clear();
        nav.inflateMenu(Repo.role == Repo.ROLE_ADMIN ? R.menu.bottom_nav_admin
                : Repo.role == Repo.ROLE_TUTOR ? R.menu.bottom_nav_tutor : R.menu.bottom_nav_menu);

        nav.setOnItemSelectedListener(item -> {
            tabShown = true;
            showTab(item.getItemId());
            return true;
        });
        // Tapping the current tab again returns to that tab's first screen.
        nav.setOnItemReselectedListener(item -> clearBackStack());

        if (savedInstanceState == null) {
            int start = Repo.role == Repo.ROLE_ADMIN ? R.id.nav_a_dashboard
                    : Repo.role == Repo.ROLE_TUTOR ? R.id.nav_t_home : R.id.nav_learn;
            nav.setSelectedItemId(start);
            // The bar already highlights its first tab, so selecting it again only fires the
            // "reselected" listener and the screen would stay blank. Show it ourselves in that case.
            if (!tabShown) showTab(start);
        }
    }

    @Override
    protected void onPause() {
        Repo.save();
        super.onPause();
    }

    /** Saves the account, clears it from memory and returns to Login. */
    public void signOut() {
        Repo.signOut();
        Intent i = new Intent(this, LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    private void setChrome(boolean dark) {
        boolean night = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        root.setBackgroundColor(getColor(dark ? R.color.dark_bg : R.color.background));
        WindowCompat.getInsetsController(getWindow(), root).setAppearanceLightStatusBars(!dark && !night);
    }

    private void showTab(int itemId) {
        clearBackStack();
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.nav_host, fragmentFor(itemId))
                .commit();
    }

    private void clearBackStack() {
        getSupportFragmentManager().popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
    }

    /** Push a sub-screen on top of the current tab. */
    public void open(Fragment f) {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.nav_host, f)
                .addToBackStack(null)
                .commit();
    }

    /** Show the confirmation screen; Back then returns to the tab's first screen. */
    public void showConfirmation(Fragment f) {
        FragmentManager fm = getSupportFragmentManager();
        fm.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        fm.beginTransaction().replace(R.id.nav_host, f).addToBackStack(null).commit();
    }

    public void selectTab(int menuId) {
        nav.setSelectedItemId(menuId);
    }

    private Fragment fragmentFor(int id) {
        // Shared
        if (id == R.id.nav_profile) return new ProfileFragment();
        // Learner
        if (id == R.id.nav_practice) return new PracticeFragment();
        if (id == R.id.nav_tutors) return new TutorsFragment();
        if (id == R.id.nav_bookings) return new BookingsFragment();
        // Tutor
        if (id == R.id.nav_t_home) return new TutorHomeFragment();
        if (id == R.id.nav_t_sessions) return new TutorSessionsFragment();
        if (id == R.id.nav_t_lessons) return new MyListingsFragment();
        if (id == R.id.nav_t_payouts) return new PayoutsFragment();
        // Admin
        if (id == R.id.nav_a_dashboard) return new AdminDashboardFragment();
        if (id == R.id.nav_a_listings) return new AdminListingsFragment();
        if (id == R.id.nav_a_users) return new AdminUsersFragment();
        if (id == R.id.nav_a_payouts) return new AdminPayoutsFragment();
        return new LearnFragment();
    }
}
