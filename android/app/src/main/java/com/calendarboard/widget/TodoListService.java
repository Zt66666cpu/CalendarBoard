package com.calendarboard.widget;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Collection adapter for checkable todo rows in the large calendar widget. */
public final class TodoListService extends RemoteViewsService {
    private static final String TAG = "CalendarBoardTodoList";

    @Override public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new TodoFactory(getApplicationContext());
    }

    private static final class TodoFactory implements RemoteViewsFactory {
        private final Context context;
        private final List<JSONObject> entries = new ArrayList<>();

        TodoFactory(Context context) {
            this.context = context;
        }

        @Override public void onCreate() {
            onDataSetChanged();
        }

        @Override public void onDataSetChanged() {
            entries.clear();
            if (context == null || SessionStore.session(context) == null) return;

            String today = LocalDate.now().toString();
            JSONObject cache = SessionStore.cache(context);
            if (!today.equals(cache.optString("date", ""))) {
                try {
                    cache = CalendarApi.buildCache(cache.optJSONArray("all_items"),
                            cache.optJSONObject("meta"), today,
                            LocalTime.now().withSecond(0).withNano(0).toString(),
                            System.currentTimeMillis());
                } catch (Exception error) {
                    Log.e(TAG, "重算今日待办缓存失败", error);
                    return;
                }
            }

            Map<String, JSONObject> unique = new LinkedHashMap<>();
            append(unique, cache.optJSONArray("todos_today"));
            append(unique, cache.optJSONArray("todos_pending"));
            entries.addAll(unique.values());
            entries.sort(Items.BY_TIME);
        }

        private void append(Map<String, JSONObject> unique, JSONArray rows) {
            if (rows == null) return;
            for (int i = 0; i < rows.length(); i++) {
                JSONObject item = rows.optJSONObject(i);
                if (item == null) continue;
                String id = rowIdOf(item);
                if (id.isEmpty()) continue;
                unique.put(id, item);
            }
        }

        @Override public int getCount() {
            return Math.max(1, entries.size());
        }

        @Override public RemoteViews getViewAt(int position) {
            RemoteViews row = new RemoteViews(context.getPackageName(), R.layout.today_schedule_row);
            if (entries.isEmpty()) {
                row.setViewVisibility(R.id.todoCheck, View.GONE);
                row.setTextViewText(R.id.todoTitle, "暂无待办");
                row.setTextViewText(R.id.todoMeta, "");
                row.setOnClickFillInIntent(R.id.todoRowRoot, openAppIntent(""));
                return row;
            }
            if (position < 0 || position >= entries.size()) return null;

            JSONObject item = entries.get(position);
            String rowId = rowIdOf(item);
            row.setViewVisibility(R.id.todoCheck, View.VISIBLE);
            row.setTextViewText(R.id.todoTitle, item.optString("title", "未命名待办"));
            row.setTextViewText(R.id.todoMeta, metaOf(item));

            Intent openApp = openAppIntent(rowId);
            if (Build.VERSION.SDK_INT >= 31) {
                row.setCompoundButtonChecked(R.id.todoCheck, false);
                Intent fillIn = new Intent().putExtra(CalendarWidget.EXTRA_ROW_ID, rowId);
                row.setOnCheckedChangeResponse(R.id.todoCheck,
                        RemoteViews.RemoteResponse.fromFillInIntent(fillIn));
                row.setOnClickFillInIntent(R.id.todoRowRoot, openApp);
            } else {
                row.setOnClickFillInIntent(R.id.todoRowRoot, openApp);
            }
            return row;
        }

        private Intent openAppIntent(String rowId) {
            Intent intent = new Intent()
                    .setAction(CalendarWidget.ACTION_OPEN_APP)
                    .putExtra(CalendarWidget.EXTRA_ROW_ID, rowId);
            return intent;
        }

        private String metaOf(JSONObject item) {
            StringBuilder meta = new StringBuilder();
            String date = item.optString("date", "");
            String today = LocalDate.now().toString();
            if (!date.isEmpty() && !today.equals(date)) {
                try {
                    meta.append(CalendarUtils.shortDateHeading(LocalDate.parse(date))).append(" · ");
                } catch (RuntimeException error) {
                    Log.w(TAG, "待办日期格式异常：" + date, error);
                }
            }
            String time = item.optString("time", "");
            if (time.isEmpty()) {
                Integer start = Items.itemStart(item);
                if (start != null) time = Items.formatMinutes(start);
            }
            if (!time.isEmpty()) meta.append(time);
            String place = item.optString("place", "");
            if (place.isEmpty()) place = item.optString("room", "");
            if (!place.isEmpty()) {
                if (meta.length() > 0) meta.append(" · ");
                meta.append(place);
            }
            return meta.toString();
        }

        private static String rowIdOf(JSONObject item) {
            if (item == null) return "";
            String id = item.optString("_row_id", "");
            return id.isEmpty() ? item.optString("id", "") : id;
        }

        @Override public RemoteViews getLoadingView() {
            return null;
        }

        @Override public int getViewTypeCount() {
            return 1;
        }

        @Override public long getItemId(int position) {
            if (entries.isEmpty() || position < 0 || position >= entries.size()) {
                return Long.MIN_VALUE;
            }
            return rowIdOf(entries.get(position)).hashCode() & 0xffffffffL;
        }

        @Override public boolean hasStableIds() {
            return true;
        }

        @Override public void onDestroy() {
            entries.clear();
        }
    }
}
