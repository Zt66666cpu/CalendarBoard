package com.calendarboard.widget;

import android.Manifest;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationManagerCompat;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.snackbar.Snackbar;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** CalendarBoard main screen with a swipeable month grid and a diffed agenda list. */
public final class MainActivity extends AppCompatActivity implements Repo.Listener {
    static final String EXTRA_OPEN_REVIEW = "extra_open_review";

    private static final String WEBSITE = "https://zt66666cpu.github.io/CalendarBoard/";
    private static final int TOTAL_MONTH_PAGES = 2400;
    private static final int BASE_MONTH_POS = 1200;
    private static final long SYNC_STALE_MS = 60_000L;
    private static final long MINUTE_MS = 60_000L;
    private static final String[] WEEKDAY_SHORT = {"一", "二", "三", "四", "五", "六", "日"};

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler minuteHandler = new Handler(Looper.getMainLooper());
    private final Runnable minuteTick = new Runnable() {
        @Override public void run() {
            if (!loggedIn || !started) return;
            updateHeaderLabels();
            submitAgendaRows();
            minuteHandler.postDelayed(this, MINUTE_MS);
        }
    };

    private final YearMonth anchorMonth = YearMonth.now();
    private int generation = 0;
    private boolean loggedIn = false;
    private boolean started = false;
    private boolean syncing = false;
    private boolean rollingOver = false;
    private boolean pendingOpenReview = false;
    private boolean showingWeekTab = false;
    private boolean permissionDialogPending = false;

    private LocalDate selectedDate = LocalDate.now();
    private YearMonth displayMonth = YearMonth.now();

    private RecyclerView screenRoot;
    private SetupBanner setupBanner;
    private TextView statusLabel;
    private EditText emailInput;
    private EditText passwordInput;
    private TextView monthHeading;
    private TextView termPill;
    private TextView monthNavLabel;
    private TextView selectedDateHeading;
    private TextView todayTag;
    private TextView addButton;
    private TextView monthTab;
    private TextView weekTab;
    private View monthCard;
    private View weekCard;
    private View selectedHeader;
    private ViewPager2 monthPager;
    private MonthPagerAdapter monthPagerAdapter;
    private AgendaAdapter agendaAdapter;

    private int dp(float value) {
        return KnitTheme.dp(this, value);
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        KnitTheme.load(this);
        Repo.get().loadFromCache(this);
        Window window = getWindow();
        if (window != null) {
            window.setStatusBarColor(KnitTheme.BG_CREAM);
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        pendingOpenReview = getIntent().getBooleanExtra(EXTRA_OPEN_REVIEW, false);
        if (Repo.get().snapshot().loggedIn) {
            showHome();
        } else {
            showLogin();
        }
    }

    @Override protected void onStart() {
        super.onStart();
        started = true;
        Repo.get().addListener(this);
        minuteHandler.removeCallbacks(minuteTick);
        minuteHandler.postDelayed(minuteTick, MINUTE_MS);
    }

    @Override protected void onResume() {
        super.onResume();
        KnitTheme.load(this);
        if (!loggedIn) return;
        ensureNotificationPermission();
        if (setupBanner != null) setupBanner.refresh();
        renderFromSnapshot(Repo.get().snapshot());
        checkDayRollover();
        maybeSilentSync();
        executor.execute(() -> ReminderScheduler.rescheduleAll(getApplicationContext()));
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                                     int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1001) {
            permissionDialogPending = false;
            if (setupBanner != null) setupBanner.refresh();
        }
    }

    @Override protected void onStop() {
        started = false;
        Repo.get().removeListener(this);
        minuteHandler.removeCallbacks(minuteTick);
        super.onStop();
    }

    @Override protected void onDestroy() {
        generation++;
        Repo.get().removeListener(this);
        minuteHandler.removeCallbacks(minuteTick);
        executor.shutdown();
        super.onDestroy();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent == null) return;
        setIntent(intent);
        if (intent.getBooleanExtra(EXTRA_OPEN_REVIEW, false)) {
            if (loggedIn) {
                openReview();
            } else {
                pendingOpenReview = true;
            }
        }
    }

    @Override public void onRepoChanged(Repo.Snapshot snapshot) {
        if (snapshot == null) return;
        if (snapshot.loggedIn != loggedIn) {
            if (snapshot.loggedIn) showHome();
            else showLogin();
            return;
        }
        if (!loggedIn) return;
        renderFromSnapshot(snapshot);
    }

    // ==================== Login ====================

    private void showLogin() {
        loggedIn = false;
        syncing = false;
        generation++;
        setContentView(buildLoginView());
    }

    private ScrollView buildLoginView() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setBackground(KnitTheme.knitCanvasBackground(this));

        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setGravity(Gravity.CENTER);
        outer.setPadding(dp(24), dp(36), dp(24), dp(36));
        scroll.addView(outer);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(24), dp(28), dp(24), dp(24));
        card.setBackground(KnitTheme.knitCard(this, KnitTheme.SURFACE_OAT,
                KnitTheme.BORDER_LINEN, 28, false));
        outer.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout dots = new LinearLayout(this);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER_VERTICAL);
        int[] yarnColors = {KnitTheme.YARN_COURSE, KnitTheme.YARN_TODO, KnitTheme.YARN_SCHEDULE};
        for (int i = 0; i < yarnColors.length; i++) {
            View dot = new View(this);
            dot.setBackground(KnitTheme.circle(this, yarnColors[i], Color.TRANSPARENT, 0));
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(9), dp(9));
            if (i > 0) dotLp.leftMargin = dp(6);
            dots.addView(dot, dotLp);
        }
        card.addView(dots);

        TextView title = new TextView(this);
        title.setText("课表日历");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(KnitTheme.INK_PRIMARY);
        title.setPadding(0, dp(12), 0, dp(4));
        card.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("登录与 Mac / 网页端相同的账号，即可实时同步课表、待办与桌面小组件。");
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        subtitle.setTextColor(KnitTheme.INK_SECONDARY);
        subtitle.setLineSpacing(dp(3), 1f);
        subtitle.setPadding(0, 0, 0, dp(18));
        card.addView(subtitle);

        emailInput = buildInput("邮箱地址",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        card.addView(emailInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        passwordInput = buildInput("密码",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams passwordLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        passwordLp.topMargin = dp(12);
        card.addView(passwordInput, passwordLp);

        TextView loginButton = new TextView(this);
        loginButton.setText("登录并同步");
        loginButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        loginButton.setTypeface(Typeface.DEFAULT_BOLD);
        loginButton.setTextColor(KnitTheme.SURFACE_OAT);
        loginButton.setGravity(Gravity.CENTER);
        loginButton.setPadding(dp(16), dp(14), dp(16), dp(14));
        loginButton.setBackground(KnitTheme.roundedRect(this, KnitTheme.ACCENT_COCOA,
                Color.TRANSPARENT, 0, 999));
        loginButton.setOnClickListener(v -> doLogin());
        LinearLayout.LayoutParams loginLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        loginLp.topMargin = dp(20);
        card.addView(loginButton, loginLp);

        statusLabel = new TextView(this);
        statusLabel.setText("登录后长按桌面空白处可添加暖米白小组件");
        statusLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        statusLabel.setTextColor(KnitTheme.INK_MUTED);
        statusLabel.setGravity(Gravity.CENTER);
        statusLabel.setPadding(0, dp(14), 0, 0);
        card.addView(statusLabel);
        return scroll;
    }

    private EditText buildInput(String hint, int inputType) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setHintTextColor(KnitTheme.INK_MUTED);
        input.setTextColor(KnitTheme.INK_PRIMARY);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        input.setSingleLine(true);
        input.setInputType(inputType);
        input.setPadding(dp(16), dp(13), dp(16), dp(13));
        input.setBackground(KnitTheme.roundedRect(this, KnitTheme.SURFACE_SAND,
                KnitTheme.BORDER_LINEN, 1, 18));
        return input;
    }

    private void doLogin() {
        if (emailInput == null || passwordInput == null) return;
        String userEmail = emailInput.getText().toString().trim();
        String userPassword = passwordInput.getText().toString();
        if (userEmail.isEmpty() || userPassword.isEmpty()) {
            statusLabel.setText("请填写邮箱和密码。");
            return;
        }
        statusLabel.setText("正在登录并同步日历…");
        int requestGeneration = ++generation;
        executor.execute(() -> {
            try {
                CalendarApi.login(this, userEmail, userPassword);
                CalendarApi.syncToday(this);
                CalendarWidget.scheduleNow(this);
                runOnUiThread(() -> {
                    if (requestGeneration != generation || isFinishing()) return;
                    if (!loggedIn) showHome();
                    else renderFromSnapshot(Repo.get().snapshot());
                    ReminderScheduler.rescheduleAll(this);
                    ensureNotificationPermission();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (requestGeneration == generation && statusLabel != null) {
                        statusLabel.setText("登录失败：" + error.getMessage());
                    }
                });
            }
        });
    }

    private void ensureNotificationPermission() {
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) return;
        if (permissionDialogPending) return;
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            SharedPreferences p = getSharedPreferences(Notifier.PREFS, MODE_PRIVATE);
            int asked = p.getInt("post_notifications_ask_count", 0);
            // 系统规定：被拒两次后 requestPermissions 不再弹窗，此后只能引导去设置
            if (asked < 2) {
                p.edit().putInt("post_notifications_ask_count", asked + 1).apply();
                permissionDialogPending = true;
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
                return;
            }
        }
        // 走到这里说明系统不会再弹窗，或者用户在系统设置里手动关了：交给首页提示条
    }

    // ==================== Main screen ====================

    private void showHome() {
        loggedIn = true;
        generation++;
        screenRoot = buildHomeView();
        setContentView(screenRoot);
        renderFromSnapshot(Repo.get().snapshot());
        if (pendingOpenReview) {
            pendingOpenReview = false;
            screenRoot.post(this::openReview);
        }
        ensureNotificationPermission();
        if (setupBanner != null) setupBanner.refresh();
        CalendarWidget.schedulePeriodic(this);
    }

    private RecyclerView buildHomeView() {
        RecyclerView list = new RecyclerView(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setClipToPadding(false);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setPadding(dp(16), dp(10), dp(16), dp(24));
        list.setBackground(KnitTheme.knitCanvasBackground(this));

        agendaAdapter = new AgendaAdapter();
        statusLabel = buildStatusLabel();
        StaticAdapter headerAdapter = new StaticAdapter(buildHeaderView());
        StaticAdapter footerAdapter = new StaticAdapter(statusLabel);
        list.setAdapter(new ConcatAdapter(headerAdapter, agendaAdapter, footerAdapter));
        return list;
    }

    private TextView buildStatusLabel() {
        TextView label = new TextView(this);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        label.setTextColor(KnitTheme.INK_MUTED);
        label.setGravity(Gravity.CENTER);
        label.setPadding(dp(4), dp(18), dp(4), dp(8));
        return label;
    }

    private View buildHeaderView() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setupBanner = new SetupBanner(this);
        header.addView(setupBanner);
        header.addView(buildTopHeader());
        header.addView(buildTabSwitcher());
        header.addView(buildMonthCard());
        header.addView(buildWeekCard());
        header.addView(buildSelectedDayHeader());
        updateTabVisibility();
        return header;
    }

    private View buildTopHeader() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(2), dp(2), dp(2), dp(10));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);

        monthHeading = new TextView(this);
        monthHeading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        monthHeading.setTypeface(Typeface.DEFAULT_BOLD);
        monthHeading.setTextColor(KnitTheme.INK_PRIMARY);
        left.addView(monthHeading);

        termPill = new TextView(this);
        termPill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        termPill.setTypeface(Typeface.DEFAULT_BOLD);
        termPill.setTextColor(KnitTheme.INK_SECONDARY);
        termPill.setPadding(dp(10), dp(3), dp(10), dp(3));
        termPill.setBackground(KnitTheme.roundedRect(this, KnitTheme.SURFACE_SAND,
                KnitTheme.BORDER_LINEN, 1, 999));
        LinearLayout.LayoutParams termLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        termLp.topMargin = dp(4);
        left.addView(termPill, termLp);

        bar.addView(left, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bar.addView(circleAction("今", v -> selectToday()));
        bar.addView(iconAction(R.drawable.ic_refresh, "刷新", v -> refreshData(false)));
        bar.addView(iconAction(R.drawable.ic_more, "更多", this::showMoreMenu));
        return bar;
    }

    private View circleAction(String label, View.OnClickListener listener) {
        TextView button = new TextView(this);
        button.setText(label);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setGravity(Gravity.CENTER);
        button.setTextColor(KnitTheme.INK_PRIMARY);
        button.setBackground(KnitTheme.circle(this, KnitTheme.SURFACE_OAT,
                KnitTheme.BORDER_LINEN, 1));
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(36));
        lp.leftMargin = dp(7);
        button.setLayoutParams(lp);
        return button;
    }

    private View iconAction(int drawableId, String description, View.OnClickListener listener) {
        ImageView button = new ImageView(this);
        button.setImageResource(drawableId);
        button.setContentDescription(description);
        button.setPadding(dp(8), dp(8), dp(8), dp(8));
        button.setBackground(KnitTheme.circle(this, KnitTheme.SURFACE_OAT,
                KnitTheme.BORDER_LINEN, 1));
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(36));
        lp.leftMargin = dp(7);
        button.setLayoutParams(lp);
        return button;
    }

    private View buildTabSwitcher() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setPadding(dp(4), dp(4), dp(4), dp(4));
        container.setBackground(KnitTheme.roundedRect(this, KnitTheme.SURFACE_SAND,
                KnitTheme.BORDER_LINEN, 1, 999));
        LinearLayout.LayoutParams containerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        containerLp.bottomMargin = dp(12);
        container.setLayoutParams(containerLp);

        monthTab = tabPill("月历视图", v -> showTab(false));
        weekTab = tabPill("未来 7 天", v -> showTab(true));
        container.addView(monthTab);
        container.addView(weekTab);
        return container;
    }

    private TextView tabPill(String text, View.OnClickListener listener) {
        TextView tab = new TextView(this);
        tab.setText(text);
        tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tab.setTypeface(Typeface.DEFAULT_BOLD);
        tab.setGravity(Gravity.CENTER);
        tab.setPadding(dp(10), dp(8), dp(10), dp(8));
        tab.setOnClickListener(listener);
        tab.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return tab;
    }

    private View buildMonthCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setBackground(KnitTheme.knitCard(this, KnitTheme.SURFACE_OAT,
                KnitTheme.BORDER_LINEN, 26, true));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = dp(4);
        card.setLayoutParams(cardLp);

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setPadding(dp(4), 0, dp(4), dp(10));

        TextView previous = navArrow("‹", v -> {
            if (monthPager != null) monthPager.setCurrentItem(
                    Math.max(0, monthPager.getCurrentItem() - 1), true);
        });
        TextView next = navArrow("›", v -> {
            if (monthPager != null) monthPager.setCurrentItem(
                    Math.min(TOTAL_MONTH_PAGES - 1, monthPager.getCurrentItem() + 1), true);
        });
        monthNavLabel = new TextView(this);
        monthNavLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        monthNavLabel.setTypeface(Typeface.DEFAULT_BOLD);
        monthNavLabel.setTextColor(KnitTheme.INK_SECONDARY);
        monthNavLabel.setGravity(Gravity.CENTER);

        nav.addView(previous);
        nav.addView(monthNavLabel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        nav.addView(next);
        card.addView(nav);

        LinearLayout weekRow = new LinearLayout(this);
        weekRow.setOrientation(LinearLayout.HORIZONTAL);
        weekRow.setPadding(0, 0, 0, dp(6));
        for (int i = 0; i < 7; i++) {
            TextView weekday = new TextView(this);
            weekday.setText(WEEKDAY_SHORT[i]);
            weekday.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            weekday.setTypeface(Typeface.DEFAULT_BOLD);
            weekday.setTextColor(i >= 5 ? KnitTheme.YARN_SCHEDULE : KnitTheme.INK_MUTED);
            weekday.setGravity(Gravity.CENTER);
            weekRow.addView(weekday, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        card.addView(weekRow);

        monthPager = new ViewPager2(this);
        monthPager.setOrientation(ViewPager2.ORIENTATION_HORIZONTAL);
        monthPagerAdapter = new MonthPagerAdapter();
        monthPager.setAdapter(monthPagerAdapter);
        monthPager.setOffscreenPageLimit(1);
        monthPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) {
                displayMonth = pageMonth(position);
                updateMonthLabels();
            }
        });
        card.addView(monthPager, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(286)));
        monthCard = card;
        monthPager.setCurrentItem(pageForMonth(displayMonth), false);
        return card;
    }

    private TextView navArrow(String symbol, View.OnClickListener listener) {
        TextView arrow = new TextView(this);
        arrow.setText(symbol);
        arrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        arrow.setTypeface(Typeface.DEFAULT_BOLD);
        arrow.setTextColor(KnitTheme.INK_PRIMARY);
        arrow.setGravity(Gravity.CENTER);
        arrow.setBackground(KnitTheme.circle(this, KnitTheme.SURFACE_SAND,
                KnitTheme.BORDER_LINEN, 1));
        arrow.setOnClickListener(listener);
        arrow.setLayoutParams(new LinearLayout.LayoutParams(dp(30), dp(30)));
        return arrow;
    }

    private View buildWeekCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(KnitTheme.knitCard(this, KnitTheme.SURFACE_OAT,
                KnitTheme.BORDER_LINEN, 24, false));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = dp(8);
        card.setLayoutParams(cardLp);

        TextView title = new TextView(this);
        title.setText("未来 7 天");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(KnitTheme.INK_PRIMARY);
        card.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("从明天起逐日展开，课程、待办与日程一目了然。");
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        subtitle.setTextColor(KnitTheme.INK_SECONDARY);
        subtitle.setPadding(0, dp(4), 0, 0);
        card.addView(subtitle);
        weekCard = card;
        return card;
    }

    private View buildSelectedDayHeader() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(16);
        rowLp.bottomMargin = dp(10);
        row.setLayoutParams(rowLp);

        selectedDateHeading = new TextView(this);
        selectedDateHeading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        selectedDateHeading.setTypeface(Typeface.DEFAULT_BOLD);
        selectedDateHeading.setTextColor(KnitTheme.INK_PRIMARY);
        row.addView(selectedDateHeading);

        todayTag = new TextView(this);
        todayTag.setText("今天");
        todayTag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        todayTag.setTypeface(Typeface.DEFAULT_BOLD);
        todayTag.setTextColor(KnitTheme.YARN_TODO);
        todayTag.setPadding(dp(8), dp(3), dp(8), dp(3));
        todayTag.setBackground(KnitTheme.roundedRect(this, KnitTheme.YARN_TODO_BG,
                KnitTheme.YARN_TODO, 1, 999));
        LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tagLp.leftMargin = dp(8);
        row.addView(todayTag, tagLp);

        row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));

        addButton = new TextView(this);
        addButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        addButton.setTypeface(Typeface.DEFAULT_BOLD);
        addButton.setTextColor(KnitTheme.ACCENT_COCOA);
        addButton.setPadding(dp(12), dp(6), dp(12), dp(6));
        addButton.setBackground(KnitTheme.roundedRect(this, KnitTheme.SURFACE_OAT,
                KnitTheme.BORDER_LINEN, 1, 999));
        addButton.setOnClickListener(v -> openQuickAdd(selectedDate, null));
        row.addView(addButton);
        selectedHeader = row;
        return row;
    }

    private void updateMonthLabels() {
        if (monthHeading == null) return;
        monthHeading.setText(displayMonth.getYear() + "年 " + displayMonth.getMonthValue() + "月");
        if (monthNavLabel != null) {
            int month = displayMonth.getMonthValue();
            monthNavLabel.setText(displayMonth.getYear() + " · " + (month < 10 ? "0" : "") + month);
        }
    }

    private void updateHeaderLabels() {
        if (monthHeading == null) return;
        updateMonthLabels();
        Repo.Snapshot snapshot = Repo.get().snapshot();
        String term = CalendarUtils.semesterWeekFull(snapshot.meta, selectedDate);
        if (termPill != null) {
            termPill.setText(term);
            termPill.setVisibility(term.isEmpty() ? View.GONE : View.VISIBLE);
        }
        if (selectedDateHeading != null) {
            selectedDateHeading.setText(CalendarUtils.shortDateHeading(selectedDate));
        }
        if (todayTag != null) {
            todayTag.setVisibility(selectedDate.equals(LocalDate.now())
                    ? View.VISIBLE : View.GONE);
        }
        if (addButton != null) {
            boolean readOnly = snapshot.readOnly;
            addButton.setText("＋ 添加到 " + selectedDate.getMonthValue() + "月"
                    + selectedDate.getDayOfMonth() + "日");
            addButton.setEnabled(!readOnly);
            addButton.setAlpha(readOnly ? 0.45f : 1f);
        }
    }

    private void updateTabVisibility() {
        if (monthCard != null) monthCard.setVisibility(showingWeekTab ? View.GONE : View.VISIBLE);
        if (weekCard != null) weekCard.setVisibility(showingWeekTab ? View.VISIBLE : View.GONE);
        if (selectedHeader != null) selectedHeader.setVisibility(
                showingWeekTab ? View.GONE : View.VISIBLE);
        if (monthTab != null) {
            monthTab.setTextColor(showingWeekTab ? KnitTheme.INK_SECONDARY : KnitTheme.INK_PRIMARY);
            monthTab.setBackground(showingWeekTab ? null : KnitTheme.roundedRect(this,
                    KnitTheme.SURFACE_OAT, KnitTheme.BORDER_LINEN, 1, 999));
        }
        if (weekTab != null) {
            weekTab.setTextColor(showingWeekTab ? KnitTheme.INK_PRIMARY : KnitTheme.INK_SECONDARY);
            weekTab.setBackground(showingWeekTab ? KnitTheme.roundedRect(this,
                    KnitTheme.SURFACE_OAT, KnitTheme.BORDER_LINEN, 1, 999) : null);
        }
    }

    private void showTab(boolean week) {
        if (showingWeekTab == week) return;
        showingWeekTab = week;
        updateTabVisibility();
        submitAgendaRows();
        View target = week ? weekCard : monthCard;
        if (target != null) {
            target.setAlpha(0f);
            target.animate().alpha(1f).setDuration(200).start();
        }
    }

    private void renderFromSnapshot(Repo.Snapshot snapshot) {
        if (snapshot == null || !loggedIn) return;
        updateHeaderLabels();
        updateTabVisibility();
        if (monthPagerAdapter != null) monthPagerAdapter.refreshAll();
        submitAgendaRows();
        updateFooter(snapshot);
    }

    private void updateFooter(Repo.Snapshot snapshot) {
        if (statusLabel == null) return;
        if (snapshot.readOnly) {
            statusLabel.setText("请升级应用");
        } else if (syncing) {
            statusLabel.setText("正在与云端同步…");
        } else if (snapshot.syncedAt.isEmpty()) {
            statusLabel.setText("点右上角刷新同步最新日程");
        } else {
            statusLabel.setText(snapshot.syncedAt + " 已同步 · 长按桌面可添加小组件");
        }
    }

    // ==================== Month grid ====================

    private YearMonth pageMonth(int position) {
        return anchorMonth.plusMonths(position - BASE_MONTH_POS);
    }

    private int pageForMonth(YearMonth month) {
        long delta = ChronoUnit.MONTHS.between(anchorMonth, month);
        int position = BASE_MONTH_POS + (int) delta;
        return Math.max(0, Math.min(TOTAL_MONTH_PAGES - 1, position));
    }

    private void selectDate(LocalDate date) {
        LocalDate previous = selectedDate;
        if (date.equals(previous)) return;
        selectedDate = date;
        displayMonth = YearMonth.from(date);
        if (monthPagerAdapter != null) {
            monthPagerAdapter.notifyDateChanged(previous);
            monthPagerAdapter.notifyDateChanged(date);
        }
        if (monthPager != null) {
            int target = pageForMonth(displayMonth);
            if (target != monthPager.getCurrentItem()) monthPager.setCurrentItem(target, false);
        }
        updateHeaderLabels();
        submitAgendaRows();
    }

    private void selectToday() {
        LocalDate previous = selectedDate;
        LocalDate today = LocalDate.now();
        selectedDate = today;
        displayMonth = YearMonth.now();
        if (monthPagerAdapter != null) {
            monthPagerAdapter.notifyDateChanged(previous);
            monthPagerAdapter.notifyDateChanged(today);
        }
        if (monthPager != null) {
            monthPager.setCurrentItem(pageForMonth(displayMonth), false);
        }
        updateHeaderLabels();
        submitAgendaRows();
    }

    private final class MonthPagerAdapter extends RecyclerView.Adapter<MonthPageHolder> {
        private final Map<Integer, MonthGridAdapter> adapters = new HashMap<>();

        @Override public int getItemCount() {
            return TOTAL_MONTH_PAGES;
        }

        @Override public MonthPageHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            RecyclerView grid = new RecyclerView(parent.getContext());
            grid.setLayoutManager(new GridLayoutManager(MainActivity.this, 7));
            grid.setHasFixedSize(true);
            grid.setNestedScrollingEnabled(false);
            grid.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return new MonthPageHolder(grid);
        }

        @Override public void onBindViewHolder(MonthPageHolder holder, int position) {
            MonthGridAdapter adapter = new MonthGridAdapter(pageMonth(position));
            adapters.put(position, adapter);
            holder.grid.setAdapter(adapter);
        }

        void refreshAll() {
            for (MonthGridAdapter adapter : new ArrayList<>(adapters.values())) {
                adapter.refresh();
            }
        }

        void notifyDateChanged(LocalDate date) {
            for (MonthGridAdapter adapter : new ArrayList<>(adapters.values())) {
                adapter.notifyDateChanged(date);
            }
        }
    }

    private static final class MonthPageHolder extends RecyclerView.ViewHolder {
        final RecyclerView grid;

        MonthPageHolder(RecyclerView grid) {
            super(grid);
            this.grid = grid;
        }
    }

    private final class MonthGridAdapter extends RecyclerView.Adapter<DayCellHolder> {
        private final YearMonth month;
        private final List<LocalDate> dates = new ArrayList<>();

        MonthGridAdapter(YearMonth month) {
            this.month = month;
            LocalDate first = month.atDay(1);
            int leading = first.getDayOfWeek().getValue() - 1;
            int totalCells = leading + month.lengthOfMonth() <= 35 ? 35 : 42;
            LocalDate start = first.minusDays(leading);
            for (int i = 0; i < totalCells; i++) dates.add(start.plusDays(i));
        }

        @Override public int getItemCount() {
            return dates.size();
        }

        @Override public DayCellHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            LinearLayout cell = new LinearLayout(MainActivity.this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(dp(2), dp(5), dp(2), dp(4));
            cell.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));
            return new DayCellHolder(cell);
        }

        @Override public void onBindViewHolder(DayCellHolder holder, int position) {
            LocalDate date = dates.get(position);
            LinearLayout cell = holder.cell;
            cell.removeAllViews();
            cell.setBackground(null);

            boolean inMonth = date.getMonthValue() == month.getMonthValue();
            boolean isToday = date.equals(LocalDate.now());
            boolean isSelected = date.equals(selectedDate);
            if (isSelected) {
                cell.setBackground(KnitTheme.roundedRect(MainActivity.this,
                        KnitTheme.ACCENT_COCOA, Color.TRANSPARENT, 0, 15));
            } else if (isToday) {
                cell.setBackground(KnitTheme.roundedRect(MainActivity.this,
                        KnitTheme.SURFACE_SAND, KnitTheme.YARN_TODO, 1.3f, 15));
            }

            TextView number = new TextView(MainActivity.this);
            number.setText(String.valueOf(date.getDayOfMonth()));
            number.setTextSize(TypedValue.COMPLEX_UNIT_SP, isSelected || isToday ? 14 : 12);
            number.setTypeface(isSelected || isToday ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            if (isSelected) number.setTextColor(KnitTheme.SURFACE_OAT);
            else if (isToday) number.setTextColor(KnitTheme.YARN_TODO);
            else if (!inMonth) number.setTextColor(KnitTheme.BORDER_LINEN);
            else number.setTextColor(KnitTheme.INK_PRIMARY);
            cell.addView(number);

            String holiday = inMonth ? CalendarUtils.holidayLabel(date) : "";
            Items.DayDots dots = Items.dayDots(Repo.get().snapshot().items,
                    date.toString(), LocalDate.now().toString());
            if (dots.course || dots.todo || dots.schedule) {
                LinearLayout dotRow = new LinearLayout(MainActivity.this);
                dotRow.setOrientation(LinearLayout.HORIZONTAL);
                dotRow.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                dotLp.topMargin = dp(3);
                int selectedColor = KnitTheme.SURFACE_OAT;
                if (dots.course) dotRow.addView(tinyDot(isSelected ? selectedColor : KnitTheme.YARN_COURSE));
                if (dots.todo) dotRow.addView(tinyDot(isSelected ? selectedColor : KnitTheme.YARN_TODO));
                if (dots.schedule) dotRow.addView(tinyDot(isSelected ? selectedColor : KnitTheme.YARN_SCHEDULE));
                cell.addView(dotRow, dotLp);
            } else if (!holiday.isEmpty()) {
                TextView holidayLabel = new TextView(MainActivity.this);
                holidayLabel.setText(holiday);
                holidayLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
                holidayLabel.setTextColor(isSelected ? KnitTheme.ACCENT_COCOA_SOFT : KnitTheme.YARN_SCHEDULE);
                holidayLabel.setSingleLine(true);
                cell.addView(holidayLabel);
            }

            cell.setContentDescription(date.toString());
            cell.setOnClickListener(v -> selectDate(date));
        }

        void notifyDateChanged(LocalDate date) {
            int position = dates.indexOf(date);
            if (position >= 0) notifyItemChanged(position);
        }

        void refresh() {
            if (getItemCount() > 0) notifyItemRangeChanged(0, getItemCount());
        }
    }

    private static final class DayCellHolder extends RecyclerView.ViewHolder {
        final LinearLayout cell;

        DayCellHolder(LinearLayout cell) {
            super(cell);
            this.cell = cell;
        }
    }

    private View tinyDot(int color) {
        View dot = new View(this);
        dot.setBackground(KnitTheme.circle(this, color, Color.TRANSPARENT, 0));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(4.5f), dp(4.5f));
        dotLp.setMargins(dp(1.5f), 0, dp(1.5f), 0);
        dot.setLayoutParams(dotLp);
        return dot;
    }

    // ==================== Agenda list ====================

    private void submitAgendaRows() {
        if (agendaAdapter != null) agendaAdapter.submitList(buildAgendaRows());
    }

    private List<AgendaRow> buildAgendaRows() {
        Repo.Snapshot snapshot = Repo.get().snapshot();
        List<AgendaRow> rows = new ArrayList<>();
        String today = snapshot.today == null || snapshot.today.isEmpty()
                ? LocalDate.now().toString() : snapshot.today;
        if (showingWeekTab) {
            buildWeekRows(rows, snapshot, today);
            return rows;
        }

        String iso = selectedDate.toString();
        boolean todaySelected = iso.equals(today);
        int now = nowMinutes();
        Items.DayItemsResult day = Items.dayItems(snapshot.items, iso, today);
        Items.NowNext nowNext = todaySelected
                ? Items.nowNext(snapshot.items, today, now) : null;
        boolean any = !day.courses.isEmpty() || !day.todos.isEmpty()
                || !day.schedules.isEmpty() || !day.pending.isEmpty();

        if (todaySelected && !any) {
            rows.add(AgendaRow.empty(iso, "今天没有安排", "点上方「添加」记录课程、待办或日程"));
            return rows;
        }

        if (!day.courses.isEmpty() || todaySelected) {
            rows.add(AgendaRow.section("course", "课程 · " + day.courses.size(),
                    day.courses.isEmpty() ? "今天没课" : "", ""));
            for (JSONObject course : day.courses) {
                rows.add(courseRow(course, todaySelected, day.past, now, nowNext));
            }
        }
        if (!day.todos.isEmpty()) {
            rows.add(AgendaRow.section("todo", "待办 · " + day.todos.size(), "", ""));
            for (JSONObject todo : day.todos) {
                rows.add(AgendaRow.item(todo, false, false, false, false, 0, 0, 0));
            }
        }
        if (!day.pending.isEmpty()) {
            rows.add(AgendaRow.section("pending", "待处理 · " + day.pending.size(),
                    "尚未完成的旧待办", "去回顾"));
            for (JSONObject pending : day.pending) {
                rows.add(AgendaRow.item(pending, true, false, false, false, 0, 0, 0));
            }
        }
        if (!day.schedules.isEmpty()) {
            rows.add(AgendaRow.section("schedule", "日程 · " + day.schedules.size(), "", ""));
            for (JSONObject schedule : day.schedules) {
                rows.add(AgendaRow.item(schedule, false, false, false, false, 0, 0, 0));
            }
        }
        if (!any && !(day.past && day.archivedCount > 0)) {
            rows.add(AgendaRow.empty(iso, "这一天没有安排", "可以选择其他日期，或点上方「添加」新建事项"));
        }
        if (day.past && day.archivedCount > 0) {
            rows.add(AgendaRow.archived(iso, day.archivedCount));
        }
        return rows;
    }

    private AgendaRow courseRow(JSONObject course, boolean todaySelected, boolean past,
                                int now, Items.NowNext nowNext) {
        String id = rowIdOf(course);
        boolean current = todaySelected && nowNext != null && nowNext.current != null
                && id.equals(nowNext.current.id);
        boolean next = todaySelected && nowNext != null && nowNext.next != null
                && id.equals(nowNext.next.id);
        Integer start = Items.itemStart(course);
        Integer end = Items.itemEnd(course);
        boolean finished = !current && (end != null
                ? end <= now
                : start != null && start <= now);
        if (past) finished = true;

        int progress = 0;
        int remaining = 0;
        int nextIn = 0;
        if (current) {
            int startMinute = start == null ? now : start;
            int endMinute = end == null ? now : end;
            int span = Math.max(1, endMinute - startMinute);
            progress = Math.max(0, Math.min(100, (now - startMinute) * 100 / span));
            remaining = Math.max(0, endMinute - now);
        }
        if (next && nowNext.next.start != null) {
            nextIn = Math.max(0, nowNext.next.start - now);
        }
        return AgendaRow.item(course, false, current, next, finished,
                progress, remaining, nextIn);
    }

    private void buildWeekRows(List<AgendaRow> rows, Repo.Snapshot snapshot, String today) {
        for (Items.WeekDay day : Items.weekAhead(snapshot.items, today)) {
            LocalDate date = LocalDate.parse(day.iso);
            String subtitle = day.courseCount > 0
                    ? day.courseCount + " 节课 · 首节 "
                    + (day.firstCourseStart == null ? "待定" : Items.formatMinutes(day.firstCourseStart))
                    : (day.items.isEmpty() ? "暂无安排" : day.items.size() + " 项安排");
            rows.add(AgendaRow.section("week:" + day.iso,
                    CalendarUtils.shortDateHeading(date), subtitle, ""));
            for (JSONObject item : day.items) {
                rows.add(AgendaRow.item(item, false, false, false, false, 0, 0, 0));
            }
        }
    }

    private final class AgendaAdapter extends ListAdapter<AgendaRow, RecyclerView.ViewHolder> {
        AgendaAdapter() {
            super(DIFF);
        }

        @Override public int getItemViewType(int position) {
            return getItem(position).type;
        }

        @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            if (viewType == AgendaRow.TYPE_SECTION) return buildSectionHolder();
            if (viewType == AgendaRow.TYPE_EMPTY) return new EmptyHolder(buildEmptyView());
            if (viewType == AgendaRow.TYPE_ARCHIVED) return new ArchivedHolder(buildArchivedView());
            return buildItemHolder();
        }

        @Override public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            AgendaRow row = getItem(position);
            if (holder instanceof SectionHolder) {
                bindSection((SectionHolder) holder, row);
            } else if (holder instanceof EmptyHolder) {
                ((EmptyHolder) holder).text.setText(row.title);
            } else if (holder instanceof ArchivedHolder) {
                ArchivedHolder archived = (ArchivedHolder) holder;
                archived.text.setText("这一天有 " + row.archivedCount + " 条已归档 ›");
                archived.text.setOnClickListener(v -> openArchive(row.keyDate));
            } else if (holder instanceof ItemHolder) {
                bindItem((ItemHolder) holder, row);
            }
        }
    }

    private SectionHolder buildSectionHolder() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(4), dp(10), dp(4), dp(4));
        root.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        View dot = new View(this);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotLp.rightMargin = dp(8);
        head.addView(dot, dotLp);

        TextView title = new TextView(this);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(KnitTheme.INK_PRIMARY);
        head.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView action = new TextView(this);
        action.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        action.setTypeface(Typeface.DEFAULT_BOLD);
        action.setTextColor(KnitTheme.ACCENT_COCOA);
        action.setPadding(dp(10), dp(4), dp(10), dp(4));
        action.setBackground(KnitTheme.roundedRect(this, KnitTheme.SURFACE_OAT,
                KnitTheme.BORDER_LINEN, 1, 999));
        head.addView(action);
        root.addView(head);

        TextView subtitle = new TextView(this);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        subtitle.setTextColor(KnitTheme.INK_MUTED);
        subtitle.setPadding(dp(16), dp(3), 0, 0);
        root.addView(subtitle);

        SectionHolder holder = new SectionHolder(root);
        holder.dot = dot;
        holder.title = title;
        holder.action = action;
        holder.subtitle = subtitle;
        return holder;
    }

    private View buildEmptyView() {
        TextView text = new TextView(this);
        text.setGravity(Gravity.CENTER);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        text.setTextColor(KnitTheme.INK_SECONDARY);
        text.setPadding(dp(20), dp(26), dp(20), dp(26));
        text.setBackground(KnitTheme.knitCard(this, KnitTheme.SURFACE_OAT,
                KnitTheme.BORDER_LINEN, 24, false));
        text.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return text;
    }

    private View buildArchivedView() {
        TextView text = new TextView(this);
        text.setGravity(Gravity.CENTER);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setTextColor(KnitTheme.INK_MUTED);
        text.setPadding(dp(8), dp(14), dp(8), dp(14));
        text.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return text;
    }

    private ItemHolder buildItemHolder() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(dp(12), dp(10), dp(12), dp(10));
        RecyclerView.LayoutParams rootLp = new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rootLp.topMargin = dp(6);
        root.setLayoutParams(rootLp);

        TextView check = new TextView(this);
        check.setGravity(Gravity.CENTER);
        check.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        check.setTypeface(Typeface.DEFAULT_BOLD);
        check.setTextColor(KnitTheme.SURFACE_OAT);
        LinearLayout.LayoutParams checkLp = new LinearLayout.LayoutParams(dp(22), dp(22));
        checkLp.rightMargin = dp(10);
        root.addView(check, checkLp);

        View accent = new View(this);
        LinearLayout.LayoutParams accentLp = new LinearLayout.LayoutParams(dp(4), dp(26));
        accentLp.rightMargin = dp(10);
        root.addView(accent, accentLp);

        LinearLayout textColumn = new LinearLayout(this);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        root.addView(textColumn, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView title = new TextView(this);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(KnitTheme.INK_PRIMARY);
        textColumn.addView(title);

        TextView meta = new TextView(this);
        meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        meta.setTextColor(KnitTheme.INK_SECONDARY);
        meta.setPadding(0, dp(2), 0, 0);
        meta.setFontFeatureSettings("tnum");
        textColumn.addView(meta);

        ProgressBar progress = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(ColorStateList.valueOf(KnitTheme.YARN_COURSE));
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(4));
        progressLp.topMargin = dp(6);
        textColumn.addView(progress, progressLp);

        TextView progressLabel = new TextView(this);
        progressLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        progressLabel.setTextColor(KnitTheme.YARN_COURSE);
        progressLabel.setFontFeatureSettings("tnum");
        progressLabel.setVisibility(View.GONE);
        textColumn.addView(progressLabel);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setVisibility(View.GONE);
        root.addView(actions);

        TextView complete = smallAction("已完成");
        TextView postpone = smallAction("顺延");
        TextView drop = smallAction("放弃");
        actions.addView(complete);
        actions.addView(postpone);
        actions.addView(drop);

        TextView chevron = new TextView(this);
        chevron.setText("›");
        chevron.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        chevron.setTextColor(KnitTheme.INK_MUTED);
        chevron.setPadding(dp(6), 0, dp(2), 0);
        root.addView(chevron);

        ItemHolder holder = new ItemHolder(root);
        holder.check = check;
        holder.accent = accent;
        holder.title = title;
        holder.meta = meta;
        holder.progress = progress;
        holder.progressLabel = progressLabel;
        holder.actions = actions;
        holder.complete = complete;
        holder.postpone = postpone;
        holder.drop = drop;
        holder.chevron = chevron;
        return holder;
    }

    private TextView smallAction(String text) {
        TextView button = new TextView(this);
        button.setText(text);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextColor(KnitTheme.INK_SECONDARY);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(6), dp(4), dp(6), dp(4));
        button.setBackground(KnitTheme.roundedRect(this, KnitTheme.SURFACE_OAT,
                KnitTheme.BORDER_LINEN, 1, 8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(5);
        button.setLayoutParams(lp);
        return button;
    }

    private void bindSection(SectionHolder holder, AgendaRow row) {
        int color;
        if ("课程".equals(row.group)) color = KnitTheme.YARN_COURSE;
        else if ("日程".equals(row.group)) color = KnitTheme.YARN_SCHEDULE;
        else color = KnitTheme.YARN_TODO;
        holder.dot.setBackground(KnitTheme.circle(this, color, Color.TRANSPARENT, 0));
        holder.title.setText(row.title);
        holder.title.setTextColor(color);
        if (row.subtitle.isEmpty()) {
            holder.subtitle.setVisibility(View.GONE);
        } else {
            holder.subtitle.setText(row.subtitle);
            holder.subtitle.setVisibility(View.VISIBLE);
        }
        if (row.action.isEmpty()) {
            holder.action.setVisibility(View.GONE);
        } else {
            holder.action.setText(row.action);
            holder.action.setVisibility(View.VISIBLE);
            holder.action.setOnClickListener(v -> openReview());
        }
    }

    private void bindItem(ItemHolder holder, AgendaRow row) {
        JSONObject item = row.item;
        String type = item.optString("type", "待办");
        boolean isTodo = "待办".equals(type);
        boolean done = item.optBoolean("done");
        int yarn = KnitTheme.colorForType(type);
        int tint = done ? KnitTheme.SURFACE_SAND : KnitTheme.tintForType(type);

        holder.root.setAlpha(row.finished ? 0.55f : 1f);
        holder.root.setBackground(KnitTheme.roundedRect(this, tint,
                row.current ? yarn : Color.TRANSPARENT, row.current ? 1.5f : 0, 16));
        holder.root.setOnClickListener(v -> openQuickAdd(itemDate(item), item));

        holder.check.setVisibility(isTodo && !row.pendingRow ? View.VISIBLE : View.GONE);
        holder.accent.setVisibility(isTodo && !row.pendingRow ? View.GONE : View.VISIBLE);
        holder.accent.setBackground(KnitTheme.roundedRect(this, yarn, Color.TRANSPARENT, 0, 999));

        holder.check.setText(done ? "✓" : "");
        holder.check.setBackground(KnitTheme.circle(this,
                done ? yarn : KnitTheme.SURFACE_OAT, yarn, 1.5f));
        holder.check.setEnabled(!Repo.get().snapshot().readOnly);
        holder.check.setAlpha(Repo.get().snapshot().readOnly ? 0.45f : 1f);
        holder.check.setOnClickListener(v -> toggleTodo(v, item, !done));

        holder.title.setText(item.optString("title", "未命名"));
        holder.title.setTextColor(done ? KnitTheme.INK_MUTED : KnitTheme.INK_PRIMARY);
        if (done) {
            holder.title.setPaintFlags(holder.title.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        } else {
            holder.title.setPaintFlags(holder.title.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
        }

        String time = item.optString("time", "");
        String place = item.optString("place", item.optString("room", ""));
        StringBuilder meta = new StringBuilder();
        if (row.pendingRow) {
            meta.append(originalDateLabel(item.optString("date", "")));
        } else {
            meta.append(type);
            if (!time.isEmpty()) meta.append(" · ").append(time);
            if (!place.isEmpty()) meta.append(" · ").append(place);
            if (row.next && row.nextIn > 0) {
                meta.append("（").append(row.nextIn).append(" 分钟后）");
            }
        }
        holder.meta.setText(meta.toString());
        holder.meta.setFontFeatureSettings("tnum");

        holder.progress.setVisibility(row.current ? View.VISIBLE : View.GONE);
        holder.progress.setProgress(row.progress);
        holder.progressLabel.setVisibility(row.current ? View.VISIBLE : View.GONE);
        holder.progressLabel.setText("还剩 " + row.remaining + " 分钟");

        holder.actions.setVisibility(row.pendingRow ? View.VISIBLE : View.GONE);
        holder.chevron.setVisibility(row.pendingRow ? View.GONE : View.VISIBLE);
        if (row.pendingRow) {
            holder.complete.setOnClickListener(v -> toggleTodo(v, item, true));
            holder.postpone.setOnClickListener(v -> postponePending(v, item, tomorrow()));
            holder.postpone.setOnLongClickListener(v -> {
                showPostponePicker(v, item);
                return true;
            });
            holder.drop.setOnClickListener(v -> dropPending(v, item));
        }
    }

    private static final class SectionHolder extends RecyclerView.ViewHolder {
        View dot;
        TextView title;
        TextView subtitle;
        TextView action;

        SectionHolder(View view) {
            super(view);
        }
    }

    private static final class EmptyHolder extends RecyclerView.ViewHolder {
        final TextView text;

        EmptyHolder(View view) {
            super(view);
            text = (TextView) view;
        }
    }

    private static final class ArchivedHolder extends RecyclerView.ViewHolder {
        final TextView text;

        ArchivedHolder(View view) {
            super(view);
            text = (TextView) view;
        }
    }

    private static final class ItemHolder extends RecyclerView.ViewHolder {
        final LinearLayout root;
        TextView check;
        View accent;
        TextView title;
        TextView meta;
        ProgressBar progress;
        TextView progressLabel;
        LinearLayout actions;
        TextView complete;
        TextView postpone;
        TextView drop;
        TextView chevron;

        ItemHolder(LinearLayout root) {
            super(root);
            this.root = root;
        }
    }

    private static final class AgendaRow {
        static final int TYPE_SECTION = 0;
        static final int TYPE_ITEM = 1;
        static final int TYPE_EMPTY = 2;
        static final int TYPE_ARCHIVED = 3;

        final int type;
        final String key;
        final String group;
        final String title;
        final String subtitle;
        final String action;
        final String keyDate;
        final JSONObject item;
        final boolean current;
        final boolean next;
        final boolean finished;
        final boolean pendingRow;
        final int progress;
        final int remaining;
        final int nextIn;
        final int archivedCount;
        final String signature;

        private AgendaRow(int type, String key, String group, String title, String subtitle,
                          String action, String keyDate, JSONObject item, boolean current,
                          boolean next, boolean finished, boolean pendingRow, int progress,
                          int remaining, int nextIn, int archivedCount, String signature) {
            this.type = type;
            this.key = key;
            this.group = group;
            this.title = title;
            this.subtitle = subtitle;
            this.action = action;
            this.keyDate = keyDate;
            this.item = item;
            this.current = current;
            this.next = next;
            this.finished = finished;
            this.pendingRow = pendingRow;
            this.progress = progress;
            this.remaining = remaining;
            this.nextIn = nextIn;
            this.archivedCount = archivedCount;
            this.signature = signature;
        }

        static AgendaRow section(String groupKey, String title, String subtitle, String action) {
            String group;
            if (groupKey.startsWith("week:")) group = "week";
            else if (groupKey.equals("course")) group = "课程";
            else if (groupKey.equals("schedule")) group = "日程";
            else if (groupKey.equals("pending")) group = "待处理";
            else group = "待办";
            return new AgendaRow(TYPE_SECTION, "section:" + groupKey, group, title, subtitle,
                    action, "", null, false, false, false, false, 0, 0, 0, 0,
                    "section|" + title + "|" + subtitle + "|" + action);
        }

        static AgendaRow item(JSONObject item, boolean pendingRow, boolean current,
                              boolean next, boolean finished, int progress,
                              int remaining, int nextIn) {
            String id = rowIdOf(item);
            String signature = item.toString() + "|" + pendingRow + "|" + current + "|"
                    + next + "|" + finished + "|" + progress + "|" + remaining + "|" + nextIn;
            return new AgendaRow(TYPE_ITEM, "item:" + id, item.optString("type", "待办"),
                    "", "", "", item.optString("date", ""), item, current, next, finished,
                    pendingRow, progress, remaining, nextIn, 0, signature);
        }

        static AgendaRow empty(String iso, String title, String subtitle) {
            return new AgendaRow(TYPE_EMPTY, "empty:" + iso, "", title, subtitle, "",
                    iso, null, false, false, false, false, 0, 0, 0, 0,
                    "empty|" + title + "|" + subtitle);
        }

        static AgendaRow archived(String iso, int count) {
            return new AgendaRow(TYPE_ARCHIVED, "archived:" + iso, "", "", "", "",
                    iso, null, false, false, false, false, 0, 0, 0, count,
                    "archived|" + iso + "|" + count);
        }
    }

    private static final DiffUtil.ItemCallback<AgendaRow> DIFF =
            new DiffUtil.ItemCallback<AgendaRow>() {
                @Override public boolean areItemsTheSame(AgendaRow oldItem, AgendaRow newItem) {
                    return oldItem.key.equals(newItem.key);
                }

                @Override public boolean areContentsTheSame(AgendaRow oldItem, AgendaRow newItem) {
                    return oldItem.signature.equals(newItem.signature);
                }
            };

    // ==================== Actions ====================

    private void openQuickAdd(LocalDate date, JSONObject editingItem) {
        if (Repo.get().snapshot().readOnly) {
            if (statusLabel != null) statusLabel.setText("请升级应用");
            return;
        }
        LocalDate targetDate = date == null ? selectedDate : date;
        String itemJson = editingItem == null ? null : editingItem.toString();
        QuickAddSheet.newInstance(targetDate.toString(), "日程", itemJson)
                .show(getSupportFragmentManager(), "quick_add");
    }

    private void openReview() {
        if (!loggedIn || isFinishing()) return;
        if (getSupportFragmentManager().findFragmentByTag("review") == null) {
            ReviewSheet.newInstance().show(getSupportFragmentManager(), "review");
        }
    }

    private void openArchive(String focusDate) {
        Intent intent = new Intent(this, ArchiveActivity.class);
        if (focusDate != null && !focusDate.isEmpty()) {
            intent.putExtra(ArchiveActivity.EXTRA_FOCUS_DATE, focusDate);
        }
        startActivity(intent);
    }

    private void showMoreMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "今日回顾");
        menu.getMenu().add(0, 2, 1, "归档");
        menu.getMenu().add(0, 3, 2, "学期设置");
        menu.getMenu().add(0, 4, 3, "打开网页版日历");
        menu.getMenu().add(0, 5, 4, "刷新桌面小组件");
        menu.getMenu().add(0, 7, 5, "提醒与自检");
        menu.getMenu().add(0, 6, 6, "退出登录");
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                openReview();
                return true;
            }
            if (item.getItemId() == 2) {
                openArchive(null);
                return true;
            }
            if (item.getItemId() == 3) {
                showSemesterDialog();
                return true;
            }
            if (item.getItemId() == 4) {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(WEBSITE)));
                return true;
            }
            if (item.getItemId() == 5) {
                CalendarWidget.renderAll(this);
                refreshData(false);
                return true;
            }
            if (item.getItemId() == 6) {
                logout();
                return true;
            }
            if (item.getItemId() == 7) {
                startActivity(new Intent(this, DiagnosticsActivity.class));
                return true;
            }
            return false;
        });
        menu.show();
    }

    private void logout() {
        generation++;
        SessionStore.clear(this);
        CalendarWidget.renderAll(this);
        Repo.get().loadFromCache(this);
        if (loggedIn) showLogin();
    }

    private void showSemesterDialog() {
        if (Repo.get().snapshot().readOnly) {
            if (statusLabel != null) statusLabel.setText("请升级应用");
            return;
        }
        JSONObject cachedMeta = Repo.get().snapshot().meta;
        final JSONObject meta = cachedMeta == null || cachedMeta.length() == 0
                ? Items.defaultMeta() : cachedMeta;

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(10), dp(20), dp(4));

        EditText nameInput = buildInput("学期名称（1–12 个字）", InputType.TYPE_CLASS_TEXT);
        nameInput.setText(meta.optString("termName", "2026秋"));
        form.addView(nameInput);

        final EditText startInput = buildInput("学期第一天（YYYY-MM-DD）", InputType.TYPE_CLASS_TEXT);
        startInput.setText(meta.optString("termStart", "2026-08-31"));
        startInput.setFocusable(false);
        startInput.setClickable(true);
        startInput.setOnClickListener(v -> {
            LocalDate initial;
            try {
                initial = LocalDate.parse(startInput.getText().toString().trim());
            } catch (Exception error) {
                initial = LocalDate.now();
            }
            new DatePickerDialog(this, (picker, year, month, day) ->
                    startInput.setText(LocalDate.of(year, month + 1, day).toString()),
                    initial.getYear(), initial.getMonthValue() - 1,
                    initial.getDayOfMonth()).show();
        });
        LinearLayout.LayoutParams startLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        startLp.topMargin = dp(10);
        form.addView(startInput, startLp);

        EditText weeksInput = buildInput("学期周数（1–30）", InputType.TYPE_CLASS_NUMBER);
        weeksInput.setText(String.valueOf(meta.optInt("termWeeks", 20)));
        LinearLayout.LayoutParams weeksLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        weeksLp.topMargin = dp(10);
        form.addView(weeksInput, weeksLp);

        TextView errorLabel = new TextView(this);
        errorLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        errorLabel.setTextColor(KnitTheme.DANGER_CLAY);
        errorLabel.setPadding(dp(4), dp(10), dp(4), 0);
        form.addView(errorLabel);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("学期设置")
                .setView(form)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String termName = nameInput.getText().toString().trim();
                    if (termName.isEmpty() || termName.length() > 12) {
                        errorLabel.setText("学期名称需为 1–12 个字");
                        return;
                    }
                    LocalDate termStart;
                    try {
                        termStart = LocalDate.parse(startInput.getText().toString().trim());
                    } catch (Exception error) {
                        errorLabel.setText("学期第一天格式应为 YYYY-MM-DD");
                        return;
                    }
                    int termWeeks;
                    try {
                        termWeeks = Integer.parseInt(weeksInput.getText().toString().trim());
                    } catch (Exception error) {
                        errorLabel.setText("学期周数需为 1–30 的整数");
                        return;
                    }
                    if (termWeeks < 1 || termWeeks > 30) {
                        errorLabel.setText("学期周数需为 1–30 的整数");
                        return;
                    }
                    JSONObject updated;
                    try {
                        updated = new JSONObject(meta.toString());
                        updated.put("termName", termName);
                        updated.put("termStart", termStart.toString());
                        updated.put("termWeeks", termWeeks);
                        updated.put("type", "meta");
                    } catch (Exception error) {
                        errorLabel.setText("无法保存学期设置");
                        return;
                    }

                    dialog.dismiss();
                    if (statusLabel != null) statusLabel.setText("正在保存学期设置…");
                    final JSONObject updatedMeta = updated;
                    int requestGeneration = ++generation;
                    executor.execute(() -> {
                        try {
                            CalendarApi.saveMeta(this, updatedMeta);
                            runOnUiThread(() -> {
                                if (requestGeneration == generation) {
                                    CalendarWidget.renderAll(this);
                                    updateFooter(Repo.get().snapshot());
                                }
                            });
                        } catch (Exception error) {
                            runOnUiThread(() -> {
                                if (requestGeneration == generation && statusLabel != null) {
                                    statusLabel.setText("学期设置保存失败：" + error.getMessage());
                                }
                            });
                        }
                    });
                }));
        dialog.show();
    }

    private void refreshData(boolean silent) {
        if (syncing) return;
        syncing = true;
        if (statusLabel != null) {
            statusLabel.setText(silent ? "正在安静同步…" : "正在同步云端日历…");
        }
        int requestGeneration = ++generation;
        executor.execute(() -> {
            try {
                CalendarApi.syncToday(this);
                runOnUiThread(() -> {
                    syncing = false;
                    if (requestGeneration != generation || !loggedIn) return;
                    CalendarWidget.renderAll(this);
                    updateFooter(Repo.get().snapshot());
                    submitAgendaRows();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    syncing = false;
                    if (requestGeneration == generation && statusLabel != null) {
                        statusLabel.setText("同步失败（已保留离线数据）：" + error.getMessage());
                    }
                });
            }
        });
    }

    private void checkDayRollover() {
        if (rollingOver) return;
        final Repo.Snapshot snapshot = Repo.get().snapshot();
        if (!snapshot.loggedIn) return;
        final String today = LocalDate.now().toString();
        if (today.equals(snapshot.today)) return;
        rollingOver = true;
        executor.execute(() -> {
            try {
                JSONArray all = new JSONArray();
                for (JSONObject item : snapshot.items) {
                    all.put(new JSONObject(item.toString()));
                }
                JSONObject cache = CalendarApi.buildCache(all, snapshot.meta, today,
                        snapshot.syncedAt, snapshot.syncedAtMs);
                SessionStore.saveCache(this, cache);
                runOnUiThread(() -> {
                    rollingOver = false;
                    Repo.get().onCacheSaved(cache);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    rollingOver = false;
                    if (statusLabel != null) {
                        statusLabel.setText("日期更新失败：" + error.getMessage());
                    }
                });
            }
        });
    }

    private void maybeSilentSync() {
        Repo.Snapshot snapshot = Repo.get().snapshot();
        if (!snapshot.loggedIn || syncing) return;
        if (System.currentTimeMillis() - snapshot.syncedAtMs > SYNC_STALE_MS) {
            refreshData(true);
        }
    }

    private void toggleTodo(View anchorView, JSONObject item, boolean done) {
        if (Repo.get().snapshot().readOnly) {
            if (statusLabel != null) statusLabel.setText("请升级应用");
            return;
        }
        String rowId = rowIdOf(item);
        if (rowId.isEmpty()) {
            if (statusLabel != null) statusLabel.setText("无法识别事项，请先刷新");
            return;
        }
        if (anchorView != null) {
            anchorView.performHapticFeedback(Build.VERSION.SDK_INT >= 30
                    ? HapticFeedbackConstants.CONFIRM
                    : HapticFeedbackConstants.VIRTUAL_KEY);
        }
        JSONObject previousPayload = copyJson(item);
        JSONObject updated = Items.applyDone(item, done);
        try {
            updated.put("_row_id", rowId);
        } catch (JSONException error) {
            if (statusLabel != null) statusLabel.setText("无法更新本地事项");
            return;
        }
        Repo.get().applyLocal(rowId, updated);
        if (done && screenRoot != null) {
            Snackbar.make(screenRoot, "已完成 · 撤销", 5000)
                    .setAction("撤销", v -> toggleTodo(v, updated, false))
                    .show();
        }
        executor.execute(() -> {
            try {
                CalendarApi.setTodoDone(this, rowId, item, done);
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    Repo.get().applyLocal(rowId, previousPayload);
                    Toast.makeText(this, "没有保存成功，请重试", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void postponePending(View anchorView, JSONObject item, LocalDate target) {
        if (Repo.get().snapshot().readOnly) {
            if (statusLabel != null) statusLabel.setText("请升级应用");
            return;
        }
        String rowId = rowIdOf(item);
        if (rowId.isEmpty()) {
            if (statusLabel != null) statusLabel.setText("无法识别事项，请先刷新");
            return;
        }
        if (anchorView != null) {
            anchorView.performHapticFeedback(Build.VERSION.SDK_INT >= 30
                    ? HapticFeedbackConstants.CONFIRM
                    : HapticFeedbackConstants.VIRTUAL_KEY);
        }
        JSONObject previousPayload = copyJson(item);
        JSONObject updated = Items.applyPostpone(item, target.toString());
        Repo.get().applyLocal(rowId, updated);
        if (statusLabel != null) {
            statusLabel.setText("已顺延到 " + target.getMonthValue() + "月"
                    + target.getDayOfMonth() + "日");
        }
        executor.execute(() -> {
            try {
                CalendarApi.postpone(this, rowId, item, target.toString());
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    Repo.get().applyLocal(rowId, previousPayload);
                    Toast.makeText(this, "没有保存成功，请重试", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void dropPending(View anchorView, JSONObject item) {
        if (Repo.get().snapshot().readOnly) {
            if (statusLabel != null) statusLabel.setText("请升级应用");
            return;
        }
        String rowId = rowIdOf(item);
        if (rowId.isEmpty()) {
            if (statusLabel != null) statusLabel.setText("无法识别事项，请先刷新");
            return;
        }
        if (anchorView != null) {
            anchorView.performHapticFeedback(Build.VERSION.SDK_INT >= 30
                    ? HapticFeedbackConstants.CONFIRM
                    : HapticFeedbackConstants.VIRTUAL_KEY);
        }
        JSONObject previousPayload = copyJson(item);
        JSONObject updated = Items.applyDrop(item);
        Repo.get().applyLocal(rowId, updated);
        if (statusLabel != null) statusLabel.setText("已放弃该待办");
        executor.execute(() -> {
            try {
                CalendarApi.drop(this, rowId, item);
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    Repo.get().applyLocal(rowId, previousPayload);
                    Toast.makeText(this, "没有保存成功，请重试", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void showPostponePicker(View anchorView, JSONObject item) {
        LocalDate initial = LocalDate.now().plusDays(1);
        new DatePickerDialog(this, (picker, year, month, day) ->
                postponePending(anchorView, item, LocalDate.of(year, month + 1, day)),
                initial.getYear(), initial.getMonthValue() - 1,
                initial.getDayOfMonth()).show();
    }

    private LocalDate tomorrow() {
        try {
            return LocalDate.parse(Repo.get().snapshot().today).plusDays(1);
        } catch (RuntimeException error) {
            return LocalDate.now().plusDays(1);
        }
    }

    private static JSONObject copyJson(JSONObject value) {
        try {
            return new JSONObject(value.toString());
        } catch (JSONException error) {
            throw new IllegalStateException("无法复制本地事项", error);
        }
    }

    private static String rowIdOf(JSONObject item) {
        if (item == null) return "";
        String rowId = item.optString("_row_id", "");
        return rowId.isEmpty() ? item.optString("id", "") : rowId;
    }

    private static LocalDate itemDate(JSONObject item) {
        try {
            return LocalDate.parse(item.optString("date", ""));
        } catch (RuntimeException error) {
            return LocalDate.now();
        }
    }

    private static String originalDateLabel(String iso) {
        try {
            LocalDate date = LocalDate.parse(iso);
            return "原定 " + date.getMonthValue() + "月" + date.getDayOfMonth() + "日";
        } catch (RuntimeException error) {
            return "原定 " + iso;
        }
    }

    private static int nowMinutes() {
        LocalTime now = LocalTime.now();
        return now.getHour() * 60 + now.getMinute();
    }

    private static final class StaticHolder extends RecyclerView.ViewHolder {
        final FrameLayout container;

        StaticHolder(FrameLayout container) {
            super(container);
            this.container = container;
        }
    }

    private final class StaticAdapter extends RecyclerView.Adapter<StaticHolder> {
        private final View content;

        StaticAdapter(View content) {
            this.content = content;
        }

        @Override public int getItemCount() {
            return 1;
        }

        @Override public StaticHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            FrameLayout container = new FrameLayout(MainActivity.this);
            container.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new StaticHolder(container);
        }

        @Override public void onBindViewHolder(StaticHolder holder, int position) {
            if (content.getParent() instanceof ViewGroup) {
                ((ViewGroup) content.getParent()).removeView(content);
            }
            holder.container.removeAllViews();
            holder.container.addView(content, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }
}
