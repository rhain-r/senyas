package com.benten.senyas;

import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.navigation.NavigationBarView;
import com.google.android.material.navigationrail.NavigationRailView;

/**
 * One shell, three experiences. The signed-in account's role (Repo.role) decides the bottom tabs:
 * Learner (Hearing Learner and Deaf Student), Tutor, or Admin (the admin console chosen at login).
 */
public class MainActivity extends AppCompatActivity {

    /** The signed-in account's email, and whether it opened the admin console. */
    public static final String EXTRA_ACCOUNT = "account", EXTRA_ADMIN = "admin";

    private NavigationBarView nav; // a bottom bar in portrait, a side rail in landscape
    private View root, navContainer, navDivider;
    private boolean tabShown = false;
    private int topInset = 0;
    private int tintColor = 0; // non-zero while a StatusTint screen is showing

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
        navContainer = findViewById(R.id.nav_container);
        navDivider = findViewById(R.id.nav_divider);
        nav = findViewById(R.id.bottom_nav);
        boolean rail = nav instanceof NavigationRailView;

        // Keep content clear of the system bars, the keyboard and (in landscape) the camera cutout.
        // Portrait: the bar's container takes the bottom inset, so the gesture area gets the bar's colour.
        // Landscape: the rail's container takes the start-side inset, so the rail reaches the edge.
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
            if (rail) {
                boolean rtl = v.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
                int start = rtl ? bars.right : bars.left, end = rtl ? bars.left : bars.right;
                v.setPaddingRelative(0, bars.top, end, bars.bottom);
                navContainer.setPaddingRelative(start, 0, 0, 0);
            } else {
                v.setPadding(bars.left, bars.top, bars.right, 0);
                navContainer.setPadding(0, 0, 0, bars.bottom);
            }
            topInset = bars.top;
            if (tintColor != 0) paintStatusArea(tintColor);
            return insets;
        });

        // Each screen sets the status-bar area: dark (practice room, live classroom), its header
        // colour (StatusTint: blue Home and Path), or the normal app background.
        getSupportFragmentManager().registerFragmentLifecycleCallbacks(
                new FragmentManager.FragmentLifecycleCallbacks() {
                    @Override
                    public void onFragmentResumed(@NonNull FragmentManager fm, @NonNull Fragment f) {
                        setChrome(f);
                    }
                }, false);

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

    /**
     * Status-bar area colouring for the screen that just resumed:
     *  - DarkScreen  -> whole window dark, light icons
     *  - StatusTint  -> only the status-bar strip gets the screen's colour (blue Home and Path headers)
     *  - otherwise   -> the normal light/dark app background
     */
    private void setChrome(Fragment f) {
        if (f.getId() != R.id.nav_host) return; // only the screens shown in the shell
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        boolean dark = f instanceof DarkScreen;
        navDivider.setVisibility(dark ? View.GONE : View.VISIBLE);
        int barColor;
        boolean lightBar; // true = dark icons on a light status bar
        if (dark) {
            tintColor = 0;
            barColor = getColor(R.color.dark_bg);
            root.setBackgroundColor(barColor);
            lightBar = false;
        } else if (f instanceof StatusTint) {
            tintColor = getColor(((StatusTint) f).statusColor());
            barColor = tintColor;
            paintStatusArea(tintColor);
            lightBar = ColorUtils.calculateLuminance(tintColor) > 0.5;
        } else {
            tintColor = 0;
            barColor = getColor(R.color.background);
            root.setBackgroundColor(barColor);
            lightBar = !night;
        }
        setLegacyStatusBarColor(barColor);
        WindowCompat.getInsetsController(getWindow(), root).setAppearanceLightStatusBars(lightBar);
    }

    /**
     * Android 14 and older do not draw this window behind the status bar, so the strip painted by
     * paintStatusArea is not seen there: colour the bar itself, or light icons would sit on a light bar.
     */
    @SuppressWarnings("deprecation")
    private void setLegacyStatusBarColor(int color) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) getWindow().setStatusBarColor(color);
    }

    /** Surface everywhere, with the status-bar strip (the top inset) in the given colour. */
    private void paintStatusArea(int color) {
        LayerDrawable ld = new LayerDrawable(new Drawable[]{
                new ColorDrawable(getColor(R.color.surface)), new ColorDrawable(color)});
        ld.setLayerGravity(1, Gravity.TOP | Gravity.FILL_HORIZONTAL);
        ld.setLayerHeight(1, Math.max(topInset, 1));
        root.setBackground(ld);
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
        // Learner (Bookings opens from Profile, see ProfileFragment)
        if (id == R.id.nav_path) return new PathFragment();
        if (id == R.id.nav_practice) return new PracticeFragment();
        if (id == R.id.nav_tutors) return new TutorsFragment();
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
