package com.calendarboard.widget;

import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Transparent host activity that immediately opens the quick-add bottom sheet. Keeping the
 * activity lets the home-screen widget keep its existing PendingIntent contract.
 */
public final class QuickAddActivity extends AppCompatActivity {
    static final String EXTRA_DATE = "extra_date";
    static final String EXTRA_ITEM_JSON = "extra_item_json";
    static final String EXTRA_TYPE = "extra_type";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        KnitTheme.load(this);
        Repo.get().loadFromCache(this);
        if (getWindow() != null) {
            getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        if (state != null) return;

        Intent intent = getIntent();
        String date = sanitizeDate(intent.getStringExtra(EXTRA_DATE));
        String type = sanitizeType(intent.getStringExtra(EXTRA_TYPE));
        String itemJson = sanitizeItemJson(intent.getStringExtra(EXTRA_ITEM_JSON));
        QuickAddSheet.newInstance(date, type, itemJson)
                .show(getSupportFragmentManager(), "QuickAddSheet");
    }

    private static String sanitizeDate(String value) {
        if (value == null || !value.matches("\\d{4}-\\d{2}-\\d{2}")) return null;
        try {
            LocalDate.parse(value);
            return value;
        } catch (DateTimeParseException error) {
            return null;
        }
    }

    private static String sanitizeType(String value) {
        if ("待办".equals(value) || "课程".equals(value) || "日程".equals(value)) return value;
        return "日程";
    }

    private String sanitizeItemJson(String value) {
        if (value == null || value.isEmpty() || !isTrustedItemSource()) return null;
        try {
            new JSONObject(value);
            return value;
        } catch (JSONException error) {
            return null;
        }
    }

    private boolean isTrustedItemSource() {
        ComponentName caller = getCallingActivity();
        if (caller != null && getPackageName().equals(caller.getPackageName())) return true;
        Uri referrer = getReferrer();
        return referrer != null
                && "android-app".equals(referrer.getScheme())
                && getPackageName().equals(referrer.getHost());
    }
}
