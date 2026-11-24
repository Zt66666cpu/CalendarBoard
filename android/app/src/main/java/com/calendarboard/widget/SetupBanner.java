package com.calendarboard.widget;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.app.NotificationManagerCompat;

/**
 * Single-slot setup guidance banner shown at the top of the home screen when notification
 * permissions, Android 16 promoted notifications, or Xiaomi background settings need attention.
 */
final class SetupBanner extends LinearLayout {
    static final String KEY_XIAOMI_ACK = "setup_xiaomi_ack";

    private final TextView messageView;
    private final TextView ackButton;

    SetupBanner(Context context) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        int padH = dp(14);
        int padV = dp(12);
        setPadding(padH, padV, padH, padV);
        setBackground(KnitTheme.roundedRect(context, KnitTheme.SURFACE_SAND,
                KnitTheme.BORDER_LINEN, 1, 16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        setLayoutParams(lp);

        messageView = new TextView(context);
        messageView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        messageView.setTextColor(KnitTheme.INK_PRIMARY);
        messageView.setLineSpacing(dp(2), 1f);
        addView(messageView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        ackButton = new TextView(context);
        ackButton.setText("我已设置");
        ackButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        ackButton.setTypeface(Typeface.DEFAULT_BOLD);
        ackButton.setTextColor(KnitTheme.ACCENT_COCOA);
        ackButton.setGravity(Gravity.CENTER);
        ackButton.setPadding(dp(10), dp(6), dp(10), dp(6));
        ackButton.setBackground(KnitTheme.roundedRect(context, KnitTheme.SURFACE_OAT,
                KnitTheme.BORDER_LINEN, 1, 12));
        LinearLayout.LayoutParams ackLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ackLp.leftMargin = dp(10);
        addView(ackButton, ackLp);

        setVisibility(View.GONE);
    }

    private int dp(float value) {
        return KnitTheme.dp(getContext(), value);
    }

    void refresh() {
        Context context = getContext();
        setBackground(KnitTheme.roundedRect(context, KnitTheme.SURFACE_SAND,
                KnitTheme.BORDER_LINEN, 1, 16));
        messageView.setTextColor(KnitTheme.INK_PRIMARY);
        ackButton.setTextColor(KnitTheme.ACCENT_COCOA);
        ackButton.setBackground(KnitTheme.roundedRect(context, KnitTheme.SURFACE_OAT,
                KnitTheme.BORDER_LINEN, 1, 12));

        // Priority 1: Notifications disabled
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            messageView.setText("通知已关闭，提醒和课程岛都不会出现");
            ackButton.setVisibility(View.GONE);
            setOnClickListener(v -> SystemSettings.openNotificationSettings(context));
            setVisibility(View.VISIBLE);
            return;
        }

        // Priority 2: Android 16 promoted notifications disabled while course island is enabled
        if (!Notifier.promotionAllowedOrNotApplicable(context)
                && Notifier.isIslandEnabled(context)) {
            messageView.setText("打开“上岛”，上课倒计时才能显示在超级岛");
            ackButton.setVisibility(View.GONE);
            setOnClickListener(v -> SystemSettings.openPromotionSettings(context));
            setVisibility(View.VISIBLE);
            return;
        }

        // Priority 3: Xiaomi background restrictions
        SharedPreferences prefs = context.getSharedPreferences(Notifier.PREFS, Context.MODE_PRIVATE);
        if (SystemSettings.isXiaomiDevice()
                && !SystemSettings.isIgnoringBatteryOptimizations(context)
                && !prefs.getBoolean(KEY_XIAOMI_ACK, false)) {
            messageView.setText("小米需开启自启动和省电无限制，否则提醒可能收不到");
            ackButton.setVisibility(View.VISIBLE);
            ackButton.setOnClickListener(v -> {
                prefs.edit().putBoolean(KEY_XIAOMI_ACK, true).apply();
                refresh();
            });
            setOnClickListener(v -> {
                Intent intent = new Intent(context, DiagnosticsActivity.class);
                intent.putExtra(DiagnosticsActivity.EXTRA_SCROLL_XIAOMI, true);
                context.startActivity(intent);
            });
            setVisibility(View.VISIBLE);
            return;
        }

        setOnClickListener(null);
        setVisibility(View.GONE);
    }
}
