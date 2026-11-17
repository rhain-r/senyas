package com.benten.senyas;

import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.content.Intent;
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

import com.google.android.material.bottomnavigation.BottomNavigationView;

/**
 * One shell, three experiences. The role chosen at login (Repo.role) decides the bottom tabs:
 * Learner (Hearing Learner and Deaf Student), Tutor, or Admin.
 */
public class MainActivity extends AppCompatActivity {

    private BottomNavigationView nav;
    private View root, navContainer, navDivider;
    private boolean tabShown = false;
    private int topInset = 0;
    private int tintColor = 0;   // non-zero while a StatusTint screen is showing
    private AccountStore accounts;
    private String signedInId;
    private int signedInRole;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        accounts = new AccountStore(this);
        AccountStore.Account account = accounts.current();
        boolean sameAccount = account != null && savedInstanceState != null
                && account.id.equals(savedInstanceState.getString("account_id"));
        super.onCreate(sameAccount ? savedInstanceState : null);
        if (account == null) { Repo.clearIdentity(); redirectToLogin(); return; }
        signedInId = account.id;
        signedInRole = account.role;
        Repo.activateAccount(account);
        if (sameAccount) PracticeSnapshot.restore(savedInstanceState.getBundle("practice"));
        Repo.syncAccountUsers(accounts);
        setContentView(R.layout.activity_main);

        root = findViewById(R.id.root);
        navContainer = findViewById(R.id.nav_container);
        navDivider = findViewById(R.id.nav_divider);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            // top/left/right go to the window; the bottom inset goes to the nav bar's own container
            v.setPadding(bars.left, bars.top, bars.right, 0);
            navContainer.setPadding(0, 0, 0, bars.bottom);
            topInset = bars.top;
            if (tintColor != 0) paintStatusArea(tintColor);
            return insets;
        });

        // Dark screens (practice room, live classroom) tint the status-bar area dark.
        getSupportFragmentManager().registerFragmentLifecycleCallbacks(
                new FragmentManager.FragmentLifecycleCallbacks() {
                    @Override
                    public void onFragmentResumed(@NonNull FragmentManager fm, @NonNull Fragment f) {
                        setChrome(f);
                    }
                }, false);

        nav = findViewById(R.id.bottom_nav);
        nav.getMenu().clear();
        nav.inflateMenu(Repo.role == Repo.ROLE_ADMIN ? R.menu.bottom_nav_admin
                : Repo.role == Repo.ROLE_TUTOR ? R.menu.bottom_nav_tutor : R.menu.bottom_nav_menu);

        nav.setOnItemSelectedListener(item -> {
            if (!authorize(areaForTab(item.getItemId()))) return false;
            tabShown = true;
            showTab(item.getItemId());
            return true;
        });
        // Tapping the current tab again returns to that tab's first screen.
        nav.setOnItemReselectedListener(item -> clearBackStack());

        if (!sameAccount) {
            int start = Repo.role == Repo.ROLE_ADMIN ? R.id.nav_a_dashboard
                    : Repo.role == Repo.ROLE_TUTOR ? R.id.nav_t_home : R.id.nav_learn;
            nav.setSelectedItemId(start);
            // The bar already highlights its first tab, so selecting it again only fires the
            // "reselected" listener and the screen would stay blank. Show it ourselves in that case.
            if (!tabShown) showTab(start);
        }
    }

    /**
     * Status-bar area colouring:
     *  - DarkScreen   -> whole window dark, light icons
     *  - StatusTint   -> only the status-bar strip gets that colour (blue home/path, white lesson)
     *  - everything else -> normal light/dark app background
     */
    private void setChrome(Fragment f) {
        boolean night = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        boolean lightIcons;
        navDivider.setVisibility(f instanceof DarkScreen ? View.GONE : View.VISIBLE);
        if (f instanceof DarkScreen) {
            tintColor = 0;
            root.setBackgroundColor(getColor(R.color.dark_bg));
            lightIcons = false;
        } else if (f instanceof StatusTint) {
            tintColor = getColor(((StatusTint) f).statusColor());
            paintStatusArea(tintColor);
            lightIcons = ColorUtils.calculateLuminance(tintColor) > 0.5;
        } else {
            tintColor = 0;
            root.setBackgroundColor(getColor(R.color.background));
            lightIcons = !night;
        }
        WindowCompat.getInsetsController(getWindow(), root).setAppearanceLightStatusBars(lightIcons);
    }

    private void paintStatusArea(int color) {
        LayerDrawable ld = new LayerDrawable(new Drawable[]{
                new ColorDrawable(getColor(R.color.surface)), new ColorDrawable(color)});
        ld.setLayerGravity(1, Gravity.TOP | Gravity.FILL_HORIZONTAL);
        ld.setLayerHeight(1, Math.max(topInset, 1));
        root.setBackground(ld);
    }

    private void showTab(int itemId) {
        if (!authorize(areaForTab(itemId))) return;
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
        if (!authorizeFragment(f)) return;
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.nav_host, f)
                .addToBackStack(null)
                .commit();
    }

    /** Show the confirmation screen; Back then returns to the tab's first screen. */
    public void showConfirmation(Fragment f) {
        if (!authorizeFragment(f)) return;
        FragmentManager fm = getSupportFragmentManager();
        fm.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        fm.beginTransaction().replace(R.id.nav_host, f).addToBackStack(null).commit();
    }

    public void selectTab(int menuId) {
        if (!authorize(areaForTab(menuId))) return;
        nav.setSelectedItemId(menuId);
    }

    @Override protected void onResume() {
        super.onResume();
        if (isFinishing() || accounts == null) return;
        AccountStore.Account account = accounts.current();
        if (account == null || !account.id.equals(signedInId) || account.role != signedInRole) {
            Repo.clearIdentity();
            redirectToLogin();
        } else Repo.activateAccount(account);
    }

    @Override protected void onSaveInstanceState(@NonNull Bundle state) {
        state.putString("account_id", signedInId);
        state.putBundle("practice", PracticeSnapshot.capture());
        super.onSaveInstanceState(state);
    }

    @Override protected void onStop() {
        Repo.persist();
        super.onStop();
    }

    private void redirectToLogin() {
        startActivity(new Intent(this, LoginActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }

    private boolean authorize(AccessPolicy.Area area) {
        AccountStore.Account account = accounts.current();
        if (account == null || !account.id.equals(signedInId) || account.role != signedInRole) {
            Repo.clearIdentity();
            redirectToLogin();
            return false;
        }
        Repo.activateAccount(account);
        if (!AccessPolicy.allows(account.role, area)) {
            Toast.makeText(this, R.string.account_access_denied, Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    private AccessPolicy.Area areaForTab(int id) {
        if (id == R.id.nav_profile) return AccessPolicy.Area.SHARED;
        if (id == R.id.nav_learn || id == R.id.nav_path || id == R.id.nav_practice || id == R.id.nav_tutors)
            return AccessPolicy.Area.LEARNER;
        if (id == R.id.nav_t_home || id == R.id.nav_t_sessions || id == R.id.nav_t_lessons || id == R.id.nav_t_payouts)
            return AccessPolicy.Area.TUTOR;
        if (id == R.id.nav_a_dashboard || id == R.id.nav_a_listings || id == R.id.nav_a_users || id == R.id.nav_a_payouts)
            return AccessPolicy.Area.ADMIN;
        return null;
    }

    private boolean authorizeFragment(Fragment fragment) {
        AccessPolicy.Area area;
        if (fragment instanceof AdminDashboardFragment || fragment instanceof AdminListingsFragment
                || fragment instanceof AdminUsersFragment || fragment instanceof AdminPayoutsFragment) area = AccessPolicy.Area.ADMIN;
        else if (fragment instanceof TutorHomeFragment || fragment instanceof TutorSessionsFragment
                || fragment instanceof MyListingsFragment || fragment instanceof LessonFormFragment
                || fragment instanceof MediaFragment || fragment instanceof PayoutsFragment) area = AccessPolicy.Area.TUTOR;
        else if (fragment instanceof CameraCheckFragment || fragment instanceof LiveClassroomFragment) area = AccessPolicy.Area.SESSION;
        else if (fragment instanceof ProfileFragment || fragment instanceof NotificationsFragment) area = AccessPolicy.Area.SHARED;
        else if (fragment instanceof LearnFragment || fragment instanceof PathFragment || fragment instanceof PracticeFragment
                || fragment instanceof PracticeSummaryFragment || fragment instanceof SignLessonFragment
                || fragment instanceof CameraUnavailableFragment || fragment instanceof TutorsFragment
                || fragment instanceof BookingsFragment || fragment instanceof LessonDetailFragment
                || fragment instanceof FilterFragment || fragment instanceof ScheduleFragment
                || fragment instanceof ReviewFragment || fragment instanceof ConfirmedFragment) area = AccessPolicy.Area.LEARNER;
        else area = null;
        if (!authorize(area)) return false;
        if (fragment instanceof LessonFormFragment || fragment instanceof MediaFragment) {
            int id = fragment.getArguments() == null ? -1 : fragment.getArguments().getInt("id", -1);
            if ((id >= 0 && Repo.ownedLesson(id) == null) || (id < 0 && Repo.draft != null && !Repo.ownsLesson(Repo.draft))) {
                Toast.makeText(this, R.string.account_access_denied, Toast.LENGTH_SHORT).show();
                return false;
            }
        }
        if (fragment instanceof LiveClassroomFragment) {
            Bundle args = fragment.getArguments();
            String reference = args == null ? null : args.getString("ref");
            if (!Repo.canJoinSession(reference, System.currentTimeMillis())) {
                Toast.makeText(this, R.string.session_not_open, Toast.LENGTH_LONG).show();
                return false;
            }
        }
        return true;
    }

    private Fragment fragmentFor(int id) {
        // Shared
        if (id == R.id.nav_profile) return new ProfileFragment();
        // Learner
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
