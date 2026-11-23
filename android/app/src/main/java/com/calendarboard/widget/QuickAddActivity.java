package com.calendarboard.widget;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

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

        String date = getIntent().getStringExtra(EXTRA_DATE);
        String type = getIntent().getStringExtra(EXTRA_TYPE);
        String itemJson = getIntent().getStringExtra(EXTRA_ITEM_JSON);
        QuickAddSheet.newInstance(date, type, itemJson)
                .show(getSupportFragmentManager(), "QuickAddSheet");
    }
}
