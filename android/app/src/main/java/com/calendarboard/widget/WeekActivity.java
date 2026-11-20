package com.calendarboard.widget;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Expanded week opened from the compact widget, with no per-day line limit. */
public final class WeekActivity extends Activity {
    private int dp(int value) { return (int)(value * getResources().getDisplayMetrics().density + .5f); }
    private void label(LinearLayout parent, String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text); view.setTextSize(size); view.setTextColor(color);
        view.setPadding(0, dp(8), 0, dp(8));
        parent.addView(view);
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(12));
        root.setBackgroundColor(Color.rgb(28, 44, 60));
        Button close = new Button(this); close.setText("收起七天日程 · 返回桌面");
        close.setOnClickListener(v -> finish()); root.addView(close);
        ScrollView scroll = new ScrollView(this);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);
        label(content, "明天起 7 天日程", 22, Color.WHITE);
        JSONArray rows = SessionStore.session(this) == null ? null : SessionStore.cache(this).optJSONArray("week_schedules");
        String[] weekdays = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        for (int offset = 1; offset <= 7; offset++) {
            LocalDate date = LocalDate.now().plusDays(offset);
            label(content, date.getMonthValue() + "月" + date.getDayOfMonth() + "日 · " + weekdays[date.getDayOfWeek().getValue() - 1], 17, Color.rgb(191,217,244));
            List<JSONObject> entries = new ArrayList<>();
            if (rows != null) for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                JSONObject item = row == null ? null : row.optJSONObject("payload");
                if (item != null && "日程".equals(item.optString("type")) && date.toString().equals(item.optString("date"))) entries.add(item);
            }
            entries.sort(Comparator.comparing(e -> e.optString("time")));
            if (entries.isEmpty()) label(content, rows == null ? "请返回小组件点 ↻ 同步" : "暂无日程", 14, Color.LTGRAY);
            for (JSONObject item : entries) {
                String time = item.optString("time");
                String place = item.optString("place", item.optString("room"));
                label(content, "• " + (time.isEmpty() ? "" : time + "  ") + item.optString("title")
                        + (place.isEmpty() ? "" : "\n场地：" + place), 16, Color.WHITE);
            }
        }
        setContentView(root);
    }
}

