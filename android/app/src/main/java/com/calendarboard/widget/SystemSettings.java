package com.calendarboard.widget;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import java.util.Locale;

/** Shared helpers for opening Android and Xiaomi/HyperOS system settings screens. */
final class SystemSettings {
    private static final String TAG = "CalendarBoardSettings";

    private SystemSettings() { }

    static boolean isXiaomiDevice() {
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        if ("xiaomi".equalsIgnoreCase(manufacturer)) return true;
        String brand = Build.BRAND == null ? "" : Build.BRAND.trim().toLowerCase(Locale.ROOT);
        return "xiaomi".equals(brand) || "redmi".equals(brand) || "poco".equals(brand);
    }

    static boolean isIgnoringBatteryOptimizations(Context context) {
        if (context == null) return true;
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return power != null && power.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    static void openNotificationSettings(Context context) {
        Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName());
        launchOrDetails(context, intent);
    }

    static void openPromotionSettings(Context context) {
        if (Build.VERSION.SDK_INT >= 36) {
            try {
                Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName());
                context.startActivity(intent);
                return;
            } catch (Exception error) {
                Log.w(TAG, "无法打开上岛设置页，退回通知设置", error);
            }
        }
        openNotificationSettings(context);
    }

    static void openChannelSettings(Context context, String channelId) {
        Intent intent = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName())
                .putExtra(Settings.EXTRA_CHANNEL_ID, channelId);
        launchOrDetails(context, intent);
    }

    static void openExactAlarmSettings(Context context) {
        if (Build.VERSION.SDK_INT < 31) {
            Toast.makeText(context, "当前系统默认允许精确闹钟", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:" + context.getPackageName()));
        launchOrDetails(context, intent);
    }

    static void openBatterySettings(Context context) {
        launchOrDetails(context, new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
    }

    static void openMiuiAutostart(Context context) {
        Intent intent = new Intent().setComponent(new ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"));
        launchOrDetails(context, intent);
    }

    static void openMiuiPowerSettings(Context context) {
        Intent intent = new Intent().setComponent(new ComponentName(
                "com.miui.securitycenter", "com.miui.powercenter.PowerSettings"));
        launchOrDetails(context, intent);
    }

    static void openLockScreenSettings(Context context) {
        openNotificationSettings(context);
    }

    static void openMiuiRecentLock(Context context) {
        Intent intent = new Intent("miui.intent.action.APP_PERM_EDITOR")
                .putExtra("extra_pkgname", context.getPackageName())
                .putExtra("packagename", context.getPackageName());
        launchOrDetails(context, intent);
    }

    static void launchOrDetails(Context context, Intent intent) {
        try {
            context.startActivity(intent);
        } catch (Exception error) {
            Log.w(TAG, "无法打开系统设置页，改为应用详情", error);
            openAppDetails(context);
        }
    }

    static void openAppDetails(Context context) {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + context.getPackageName()));
            context.startActivity(intent);
        } catch (Exception error) {
            Log.e(TAG, "无法打开应用详情设置", error);
            Toast.makeText(context, "无法打开系统设置，请手动前往应用设置", Toast.LENGTH_LONG).show();
        }
    }
}
