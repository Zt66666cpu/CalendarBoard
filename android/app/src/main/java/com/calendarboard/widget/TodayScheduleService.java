package com.calendarboard.widget;

import android.content.Intent;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class TodayScheduleService extends RemoteViewsService {
    @Override public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new RemoteViewsFactory() {
            final List<JSONObject> entries = new ArrayList<>();
            @Override public void onCreate() { onDataSetChanged(); }
            @Override public void onDataSetChanged() {
                entries.clear();
                if (SessionStore.session(TodayScheduleService.this) == null) return;
                JSONArray rows = SessionStore.cache(TodayScheduleService.this).optJSONArray("week_schedules");
                if (rows != null) for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.optJSONObject(i);
                    JSONObject item = row == null ? null : row.optJSONObject("payload");
                    if (item != null && LocalDate.now().toString().equals(item.optString("date"))) entries.add(item);
                }
                entries.sort(Comparator.comparing(e -> e.optString("time")));
            }
            @Override public int getCount() { return Math.max(1, entries.size()); }
            @Override public RemoteViews getViewAt(int position) {
                RemoteViews row = new RemoteViews(getPackageName(), R.layout.today_schedule_row);
                if (entries.isEmpty()) row.setTextViewText(R.id.itemText, "今天暂无日程 · 点 ↻ 可刷新");
                else if (position < entries.size()) {
                    JSONObject item = entries.get(position);
                    String time = item.optString("time");
                    String place = item.optString("place", item.optString("room"));
                    row.setTextViewText(R.id.itemText, (time.isEmpty() ? "" : time + "  ") + item.optString("title")
                            + (place.isEmpty() ? "" : "\n" + place));
                }
                return row;
            }
            @Override public RemoteViews getLoadingView() { return null; }
            @Override public int getViewTypeCount() { return 1; }
            @Override public long getItemId(int position) { return position; }
            @Override public boolean hasStableIds() { return false; }
            @Override public void onDestroy() { entries.clear(); }
        };
    }
}

