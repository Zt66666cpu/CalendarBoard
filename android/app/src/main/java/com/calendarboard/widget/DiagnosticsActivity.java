package com.calendarboard.widget;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationManagerCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** In-app health check for reminders, permissions and Xiaomi background restrictions. */
public final class DiagnosticsActivity extends AppCompatActivity {
    static final String EXTRA_SCROLL_XIAOMI = "extra_scroll_xiaomi";
    private static final DateTimeFormatter NEXT_FORMAT =
            DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.ROOT);

    private final Map<String, TextView> timeValues = new LinkedHashMap<>();
    private final Map<String, TextView> permissionValues = new LinkedHashMap<>();

    private ScrollView scrollView;
    private View xiaomiCard;
    private TextView syncValue;
    private TextView cacheCountValue;
    private Switch islandSwitch;

    private int dp(float value) {
        return KnitTheme.dp(this, value);
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        KnitTheme.load(this);
        KnitTheme.configureEdgeToEdge(this);
        setContentView(buildContent());
        if (getIntent().getBooleanExtra(EXTRA_SCROLL_XIAOMI, false)
                && scrollView != null && xiaomiCard != null) {
            scrollView.post(() -> scrollView.smoothScrollTo(0, xiaomiCard.getTop()));
        }
    }

    @Override protected void onResume() {
        super.onResume();
        KnitTheme.load(this);
        refreshValues();
    }

    private View buildContent() {
        scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackground(KnitTheme.knitCanvasBackground(this));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(28));
        KnitTheme.applySystemBarPadding(root, false);
        scrollView.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(buildTopBar());
        root.addView(buildSummaryCard());
        root.addView(buildReminderCard());
        root.addView(buildPermissionCard());
        xiaomiCard = buildXiaomiCard();
        root.addView(xiaomiCard);
        root.addView(buildActions());
        return scrollView;
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("提醒与自检");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTextColor(KnitTheme.INK_PRIMARY);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        titles.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("检查通知、精确闹钟与后台限制");
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        subtitle.setTextColor(KnitTheme.INK_MUTED);
        subtitle.setPadding(0, dp(3), 0, 0);
        titles.addView(subtitle);
        bar.addView(titles, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView back = button("返回", false);
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        return bar;
    }

    private View buildSummaryCard() {
        LinearLayout card = card();
        card.addView(sectionTitle("同步状态"));
        syncValue = new TextView(this);
        syncValue.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        syncValue.setTextColor(KnitTheme.INK_PRIMARY);
        card.addView(infoRow("上次同步时间", syncValue));

        cacheCountValue = new TextView(this);
        cacheCountValue.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        cacheCountValue.setTextColor(KnitTheme.INK_PRIMARY);
        card.addView(infoRow("缓存事项数", cacheCountValue));
        return card;
    }

    private View buildReminderCard() {
        LinearLayout card = card();
        card.addView(sectionTitle("下一次提醒"));
        addTimeRow(card, ReminderScheduler.KEY_NEXT_MORNING, "早安兜底");
        addTimeRow(card, ReminderScheduler.KEY_NEXT_NOON, "12:00 待办");
        addTimeRow(card, ReminderScheduler.KEY_NEXT_EVENING, "18:00 待办");
        addTimeRow(card, ReminderScheduler.KEY_NEXT_SUMMARY, "晚间总结");
        addTimeRow(card, ReminderScheduler.KEY_NEXT_AHEAD, "提前提醒");
        addTimeRow(card, ReminderScheduler.KEY_NEXT_REVIEW, "每日回顾");
        addTimeRow(card, ReminderScheduler.KEY_NEXT_ISLAND, "课程岛");
        return card;
    }

    private View buildPermissionCard() {
        LinearLayout card = card();
        card.addView(sectionTitle("权限状态"));
        card.addView(buildIslandToggle());

        addPermissionRow(card, "notifications", "通知总开关",
                () -> SystemSettings.openNotificationSettings(this));
        addPermissionRow(card, "promoted", "超级岛（实时更新）",
                () -> SystemSettings.openPromotionSettings(this));
        addPermissionRow(card, Notifier.CHANNEL_MORNING, "早安摘要渠道",
                () -> SystemSettings.openChannelSettings(this, Notifier.CHANNEL_MORNING));
        addPermissionRow(card, Notifier.CHANNEL_TODO, "待办提醒渠道",
                () -> SystemSettings.openChannelSettings(this, Notifier.CHANNEL_TODO));
        addPermissionRow(card, Notifier.CHANNEL_AHEAD, "提前提醒渠道",
                () -> SystemSettings.openChannelSettings(this, Notifier.CHANNEL_AHEAD));
        addPermissionRow(card, Notifier.CHANNEL_ISLAND, "课程岛渠道",
                () -> SystemSettings.openChannelSettings(this, Notifier.CHANNEL_ISLAND));
        addPermissionRow(card, Notifier.CHANNEL_SUMMARY, "晚间总结渠道",
                () -> SystemSettings.openChannelSettings(this, Notifier.CHANNEL_SUMMARY));
        addPermissionRow(card, "exact", "精确闹钟",
                () -> SystemSettings.openExactAlarmSettings(this));
        addPermissionRow(card, "battery", "忽略电池优化",
                () -> SystemSettings.openBatterySettings(this));
        return card;
    }

    private View buildXiaomiCard() {
        LinearLayout card = card();
        card.addView(sectionTitle("小米手机专项设置"));
        addGuideRow(card, "自启动", "允许应用被系统唤醒，避免重启后提醒失效。",
                "去设置", () -> SystemSettings.openMiuiAutostart(this));
        addGuideRow(card, "省电策略设为无限制", "避免后台同步与闹钟被系统切断。",
                "去设置", () -> SystemSettings.openMiuiPowerSettings(this));
        addGuideRow(card, "锁屏通知", "允许锁屏时显示提醒，便于直接看到待办。",
                "去设置", () -> SystemSettings.openLockScreenSettings(this));
        addGuideRow(card, "最近任务里加锁", "在最近任务卡片中下拉加锁，防止应用被清理。",
                "去设置", () -> SystemSettings.openMiuiRecentLock(this));
        return card;
    }

    private View buildActions() {
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams container = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        container.topMargin = dp(2);
        actions.setLayoutParams(container);

        TextView test = button("发送测试通知", true);
        test.setOnClickListener(v -> {
            Notifier.sendTestNotifications(this);
            Toast.makeText(this, "已发送四个渠道的测试通知", Toast.LENGTH_SHORT).show();
        });
        actions.addView(test);

        TextView testHint = new TextView(this);
        testHint.setText("课程岛测试会显示 1 分钟，Android 16 且允许上岛时应出现在超级岛。");
        testHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        testHint.setTextColor(KnitTheme.INK_MUTED);
        testHint.setPadding(dp(4), dp(6), dp(4), dp(2));
        actions.addView(testHint);

        TextView reschedule = button("立即重排所有提醒", false);
        LinearLayout.LayoutParams rescheduleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rescheduleLp.topMargin = dp(10);
        reschedule.setOnClickListener(v -> {
            ReminderScheduler.rescheduleAll(this);
            refreshValues();
            Toast.makeText(this, "已按当前缓存重排提醒", Toast.LENGTH_SHORT).show();
        });
        actions.addView(reschedule, rescheduleLp);
        return actions;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(15), dp(16), dp(15));
        card.setBackground(KnitTheme.knitCard(this, KnitTheme.SURFACE_OAT,
                KnitTheme.BORDER_LINEN, 24, false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(14);
        card.setLayoutParams(lp);
        return card;
    }

    private View buildIslandToggle() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        android.widget.ImageView islandIcon = new android.widget.ImageView(this);
        islandIcon.setImageResource(R.drawable.ic_island);
        islandIcon.setImageTintList(android.content.res.ColorStateList.valueOf(KnitTheme.INK_PRIMARY));
        islandIcon.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(18), dp(18));
        iconLp.rightMargin = dp(6);
        titleRow.addView(islandIcon, iconLp);

        TextView name = new TextView(this);
        name.setText("课程岛");
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        name.setTextColor(KnitTheme.INK_PRIMARY);
        name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        titleRow.addView(name);
        text.addView(titleRow);

        TextView detail = new TextView(this);
        detail.setText("开启后，上课前与上课中在系统实时活动中显示进度");
        detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        detail.setTextColor(KnitTheme.INK_SECONDARY);
        detail.setPadding(0, dp(2), 0, 0);
        text.addView(detail);
        row.addView(text, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        islandSwitch = new Switch(this);
        islandSwitch.setChecked(Notifier.isIslandEnabled(this));
        islandSwitch.setOnCheckedChangeListener((button, checked) -> onIslandToggled(checked));
        row.addView(islandSwitch);
        return row;
    }

    private void onIslandToggled(boolean enabled) {
        Notifier.setIslandEnabled(this, enabled);
        if (enabled) Notifier.updateCourseIsland(this, SessionStore.cache(this));
        ReminderScheduler.rescheduleAll(this);
        refreshValues();
        Toast.makeText(this, enabled ? "课程岛已开启" : "课程岛已关闭", Toast.LENGTH_SHORT).show();
    }

    private TextView sectionTitle(String text) {
        TextView title = new TextView(this);
        title.setText(text);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(KnitTheme.INK_PRIMARY);
        title.setPadding(0, 0, 0, dp(8));
        return title;
    }

    private View infoRow(String label, TextView value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        TextView name = new TextView(this);
        name.setText(label);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        name.setTextColor(KnitTheme.INK_SECONDARY);
        row.addView(name, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(value);
        return row;
    }

    private void addTimeRow(LinearLayout parent, String key, String label) {
        TextView value = new TextView(this);
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        value.setTextColor(KnitTheme.INK_PRIMARY);
        timeValues.put(key, value);
        parent.addView(infoRow(label, value));
    }

    private void addPermissionRow(LinearLayout parent, String key, String label, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(label);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        name.setTextColor(KnitTheme.INK_PRIMARY);
        text.addView(name);

        TextView value = new TextView(this);
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        value.setTextColor(KnitTheme.INK_MUTED);
        value.setPadding(0, dp(2), 0, 0);
        text.addView(value);
        permissionValues.put(key, value);
        row.addView(text, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView settingsButton = button("去设置", false);
        settingsButton.setOnClickListener(v -> action.run());
        row.addView(settingsButton);
        parent.addView(row);
    }

    private void addGuideRow(LinearLayout parent, String title, String detail, String buttonText,
                             Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(title);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        name.setTextColor(KnitTheme.INK_PRIMARY);
        name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        text.addView(name);

        TextView detailView = new TextView(this);
        detailView.setText(detail);
        detailView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        detailView.setTextColor(KnitTheme.INK_SECONDARY);
        detailView.setPadding(0, dp(3), 0, 0);
        text.addView(detailView);
        row.addView(text, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView settingsButton = button(buttonText, false);
        settingsButton.setOnClickListener(v -> action.run());
        row.addView(settingsButton);
        parent.addView(row);
    }

    private TextView button(String text, boolean primary) {
        TextView button = new TextView(this);
        button.setText(text);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        button.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(14), dp(9), dp(14), dp(9));
        if (primary) {
            button.setTextColor(KnitTheme.ACCENT_ON_COCOA);
            button.setBackground(KnitTheme.roundedRect(this, KnitTheme.ACCENT_COCOA,
                    KnitTheme.ACCENT_COCOA, 0, 16));
        } else {
            button.setTextColor(KnitTheme.INK_PRIMARY);
            button.setBackground(KnitTheme.roundedRect(this, KnitTheme.SURFACE_SAND,
                    KnitTheme.BORDER_LINEN, 1, 16));
        }
        return button;
    }

    private void refreshValues() {
        JSONObject cache = SessionStore.cache(this);
        String syncedAt = cache.optString("synced_at", "");
        String date = cache.optString("date", "");
        if (syncedAt.isEmpty()) {
            syncValue.setText("尚未同步");
        } else if (date.isEmpty()) {
            syncValue.setText(syncedAt);
        } else {
            syncValue.setText(date + " " + syncedAt);
        }

        JSONArray allItems = cache.optJSONArray("all_items");
        cacheCountValue.setText(String.valueOf(allItems == null ? 0 : allItems.length()));

        SharedPreferences prefs = getSharedPreferences(Notifier.PREFS, MODE_PRIVATE);
        for (Map.Entry<String, TextView> entry : timeValues.entrySet()) {
            TextView value = entry.getValue();
            long next = prefs.getLong(entry.getKey(), 0L);
            value.setText(formatNext(next));
            value.setTextColor(next > 0 ? KnitTheme.YARN_COURSE : KnitTheme.INK_MUTED);
        }

        if (islandSwitch != null) {
            islandSwitch.setOnCheckedChangeListener(null);
            islandSwitch.setChecked(Notifier.isIslandEnabled(this));
            islandSwitch.setOnCheckedChangeListener((button, checked) -> onIslandToggled(checked));
        }

        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        TextView promoted = permissionValues.get("promoted");
        if (promoted != null) {
            if (Build.VERSION.SDK_INT < 36) {
                setPermissionState(promoted, false, "系统低于 Android 16（普通通知）");
            } else if (Notifier.canPostPromoted(this)) {
                setPermissionState(promoted, true, "已开启（上岛）");
            } else {
                setPermissionState(promoted, false, "已关闭（退化为普通通知）");
            }
        }

        TextView total = permissionValues.get("notifications");
        if (total != null) {
            boolean enabled = NotificationManagerCompat.from(this).areNotificationsEnabled();
            setPermissionState(total, enabled, enabled ? "已开启" : "已关闭");
        }

        String[] channels = {Notifier.CHANNEL_MORNING, Notifier.CHANNEL_TODO,
                Notifier.CHANNEL_AHEAD, Notifier.CHANNEL_ISLAND, Notifier.CHANNEL_SUMMARY};
        for (String channelId : channels) {
            TextView value = permissionValues.get(channelId);
            if (value == null) continue;
            NotificationChannel channel = manager == null
                    ? null
                    : manager.getNotificationChannel(channelId);
            if (channel == null) {
                setPermissionState(value, false, "未创建");
            } else {
                boolean enabled = channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
                setPermissionState(value, enabled, enabled ? "已开启" : "已关闭");
            }
        }

        AlarmManager alarms = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        boolean exact = alarms != null
                && (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms());
        TextView exactValue = permissionValues.get("exact");
        if (exactValue != null) {
            setPermissionState(exactValue, exact, exact ? "已允许" : "未允许");
        }

        PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean ignoring = power != null
                && power.isIgnoringBatteryOptimizations(getPackageName());
        TextView batteryValue = permissionValues.get("battery");
        if (batteryValue != null) {
            setPermissionState(batteryValue, ignoring, ignoring ? "已忽略" : "仍受限制");
        }
    }

    private void setPermissionState(TextView view, boolean enabled, String text) {
        view.setText(text);
        view.setTextColor(enabled ? KnitTheme.YARN_COURSE : KnitTheme.DANGER_CLAY);
    }

    private String formatNext(long millis) {
        if (millis <= 0L) return "未排定";
        return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(NEXT_FORMAT);
    }
}
