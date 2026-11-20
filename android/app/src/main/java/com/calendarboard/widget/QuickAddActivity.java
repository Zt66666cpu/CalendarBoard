package com.calendarboard.widget;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;

public final class QuickAddActivity extends Activity {
    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density + .5f); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        JSONArray items = SessionStore.cache(this).optJSONArray("editable_items");
        if (items == null) items = new JSONArray();
        final JSONArray editable = items;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(16), dp(20), dp(16));
        TextView heading = new TextView(this);
        heading.setText("快速编辑日历"); heading.setTextSize(20);
        box.addView(heading);
        Spinner existing = new Spinner(this);
        String[] names = new String[editable.length() + 1];
        names[0] = "＋ 新建事项";
        for (int i = 0; i < editable.length(); i++) {
            JSONObject item = editable.optJSONObject(i);
            names[i + 1] = item == null ? "事项" : item.optString("type") + " · " + item.optString("title");
        }
        existing.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        box.addView(existing);
        Button date = new Button(this);
        date.setAllCaps(false); date.setText(LocalDate.now().toString()); box.addView(date);
        date.setOnClickListener(v -> {
            LocalDate selected;
            try { selected = LocalDate.parse(date.getText().toString()); }
            catch (Exception ignored) { selected = LocalDate.now(); }
            new DatePickerDialog(this, (picker, year, month, day) ->
                    date.setText(LocalDate.of(year, month + 1, day).toString()),
                    selected.getYear(), selected.getMonthValue() - 1, selected.getDayOfMonth()).show();
        });
        Spinner type = new Spinner(this);
        type.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"待办", "日程", "课程"}));
        box.addView(type);
        EditText title = new EditText(this);
        title.setHint("事项名称"); title.setSingleLine(true); box.addView(title);
        EditText time = new EditText(this);
        time.setHint("时间，如 14:30-16:00（可不填）"); time.setSingleLine(true); box.addView(time);
        EditText place = new EditText(this);
        place.setHint("场地（可不填）"); place.setSingleLine(true); box.addView(place);
        existing.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(AdapterView<?> parent) { }
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == 0) {
                    date.setText(LocalDate.now().toString()); type.setSelection(0);
                    title.setText(""); time.setText(""); place.setText("");
                    return;
                }
                JSONObject item = editable.optJSONObject(position - 1);
                if (item == null) return;
                date.setText(item.optString("date", LocalDate.now().toString()));
                String kind = item.optString("type");
                type.setSelection("日程".equals(kind) ? 1 : "课程".equals(kind) ? 2 : 0);
                title.setText(item.optString("title"));
                time.setText(item.optString("time"));
                place.setText(item.optString("place", item.optString("room")));
            }
        });
        TextView status = new TextView(this); box.addView(status);
        Button save = new Button(this); save.setText("保存并同步"); box.addView(save);
        setContentView(box);
        save.setOnClickListener((View v) -> {
            String name = title.getText().toString().trim();
            if (name.isEmpty()) { title.setError("请填写事项名称"); return; }
            int position = existing.getSelectedItemPosition();
            JSONObject original = position > 0 ? editable.optJSONObject(position - 1) : null;
            String id = original == null ? null : original.optString("_row_id", null);
            if (original != null && (id == null || id.isEmpty())) {
                status.setText("无法识别原事项，请刷新小组件后重试。"); return;
            }
            String day = date.getText().toString();
            String kind = type.getSelectedItem().toString();
            String hour = time.getText().toString().trim();
            String room = place.getText().toString().trim();
            save.setEnabled(false); status.setText("正在保存…");
            new Thread(() -> {
                try {
                    CalendarApi.saveItem(this, id, original, day, kind, name, hour, room);
                    CalendarApi.syncToday(this);
                    runOnUiThread(() -> { CalendarWidget.renderAll(this); finish(); });
                } catch (Exception error) {
                    runOnUiThread(() -> { status.setText("保存失败：" + error.getMessage()); save.setEnabled(true); });
                }
            }).start();
        });
    }
}

