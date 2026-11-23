package com.calendarboard.widget;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Warm knit 7-day agenda opened from the home screen widget. */
public final class WeekActivity extends Activity {
    private static final int REQ_EDIT = 201;

    private int dp(float v) { return KnitTheme.dp(this, v); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        KnitTheme.load(this);
        Window win = getWindow();
        if (win != null) {
            win.setStatusBarColor(KnitTheme.BG_CREAM);
            win.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        render();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_EDIT && resultCode == RESULT_OK) render();
    }

    private void render() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(KnitTheme.knitCanvasBackground(this));
        root.setPadding(dp(18), dp(16), dp(18), dp(14));

        // Top Bar
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("明天起 7 天安排");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(KnitTheme.INK_PRIMARY);
        topBar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView close = new TextView(this);
        close.setText("返回桌面");
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        close.setTypeface(Typeface.DEFAULT_BOLD);
        close.setTextColor(KnitTheme.SURFACE_OAT);
        close.setPadding(dp(16), dp(9), dp(16), dp(9));
        close.setBackground(KnitTheme.roundedRect(this, KnitTheme.ACCENT_COCOA, Color.TRANSPARENT, 0, 999));
        close.setOnClickListener(v -> finish());
        topBar.addView(close);
        root.addView(topBar);

        ScrollView scroll = new ScrollView(this);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        scrollLp.topMargin = dp(14);
        root.addView(scroll, scrollLp);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, dp(16));
        scroll.addView(content);

        boolean loggedIn = SessionStore.session(this) != null;
        JSONObject cache = SessionStore.cache(this);
        JSONArray allItems = loggedIn ? cache.optJSONArray("all_items") : null;
        List<JSONObject> itemList = new ArrayList<>();
        if (allItems != null) {
            for (int i = 0; i < allItems.length(); i++) {
                JSONObject item = allItems.optJSONObject(i);
                if (item != null) itemList.add(item);
            }
        }
        List<Items.WeekDay> weekDays = Items.weekAhead(itemList, LocalDate.now().toString());

        boolean firstDay = true;
        for (Items.WeekDay day : weekDays) {
            LocalDate date = LocalDate.parse(day.iso);

            LinearLayout dayCard = new LinearLayout(this);
            dayCard.setOrientation(LinearLayout.VERTICAL);
            dayCard.setPadding(dp(16), dp(14), dp(16), dp(14));
            dayCard.setBackground(KnitTheme.knitCard(this, KnitTheme.SURFACE_OAT, KnitTheme.BORDER_LINEN, 22, true));
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (!firstDay) cardLp.topMargin = dp(11);
            firstDay = false;

            LinearLayout dayHeader = new LinearLayout(this);
            dayHeader.setOrientation(LinearLayout.HORIZONTAL);
            dayHeader.setGravity(Gravity.CENTER_VERTICAL);

            TextView dayTitle = new TextView(this);
            dayTitle.setText(CalendarUtils.shortDateHeading(date));
            dayTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            dayTitle.setTypeface(Typeface.DEFAULT_BOLD);
            dayTitle.setTextColor(KnitTheme.INK_PRIMARY);
            dayHeader.addView(dayTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            String weekLbl = CalendarUtils.semesterWeekShort(date);
            if (!weekLbl.isEmpty()) {
                TextView badge = new TextView(this);
                badge.setText(weekLbl);
                badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                badge.setTextColor(KnitTheme.INK_SECONDARY);
                badge.setPadding(dp(9), dp(3), dp(9), dp(3));
                badge.setBackground(KnitTheme.roundedRect(this, KnitTheme.SURFACE_SAND, KnitTheme.BORDER_LINEN, 1, 999));
                dayHeader.addView(badge);
            }
            dayCard.addView(dayHeader);

            if (day.courseCount > 0) {
                TextView courseSummary = new TextView(this);
                courseSummary.setText(day.firstCourseStart != null
                        ? (day.courseCount + " 节课 · 首节 " + Items.formatMinutes(day.firstCourseStart))
                        : (day.courseCount + " 节课"));
                courseSummary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
                courseSummary.setTypeface(Typeface.DEFAULT_BOLD);
                courseSummary.setTextColor(KnitTheme.YARN_COURSE);
                courseSummary.setPadding(dp(10), dp(6), dp(10), dp(6));
                courseSummary.setBackground(KnitTheme.roundedRect(this, KnitTheme.YARN_COURSE_BG, Color.TRANSPARENT, 0, 999));
                LinearLayout.LayoutParams summaryLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                summaryLp.topMargin = dp(8);
                dayCard.addView(courseSummary, summaryLp);
            }

            if (day.courseCount == 0 && day.items.isEmpty()) {
                TextView empty = new TextView(this);
                empty.setText(loggedIn ? "暂无安排" : "请先登录并同步");
                empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                empty.setTextColor(KnitTheme.INK_MUTED);
                empty.setPadding(0, dp(8), 0, dp(2));
                dayCard.addView(empty);
            } else {
                for (JSONObject item : day.items) {
                    dayCard.addView(buildItemRow(item));
                }
            }
            content.addView(dayCard, cardLp);
        }

        setContentView(root);
    }

    private View buildItemRow(JSONObject item) {
        String type = item.optString("type", "日程");
        int yarn = KnitTheme.colorForType(type);
        int tint = KnitTheme.tintForType(type);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setBackground(KnitTheme.roundedRect(this, tint, Color.TRANSPARENT, 0, 15));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        row.setLayoutParams(lp);

        View dot = new View(this);
        dot.setBackground(KnitTheme.circle(this, yarn, Color.TRANSPARENT, 0));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotLp.rightMargin = dp(10);
        row.addView(dot, dotLp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText(item.optString("title", "未命名"));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(KnitTheme.INK_PRIMARY);
        col.addView(title);

        String time = item.optString("time", "");
        String place = item.optString("place", item.optString("room", ""));
        StringBuilder meta = new StringBuilder(type);
        if (!time.isEmpty()) meta.append(" · ").append(time);
        if (!place.isEmpty()) meta.append(" · ").append(place);

        TextView sub = new TextView(this);
        sub.setText(meta.toString());
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        sub.setTextColor(KnitTheme.INK_SECONDARY);
        sub.setPadding(0, dp(2), 0, 0);
        col.addView(sub);

        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        row.setOnClickListener(v -> {
            Intent intent = new Intent(this, QuickAddActivity.class);
            intent.putExtra(QuickAddActivity.EXTRA_ITEM_JSON, item.toString());
            startActivityForResult(intent, REQ_EDIT);
        });
        return row;
    }
}
