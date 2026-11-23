package com.calendarboard.widget;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalTime;

public final class QuickAddActivity extends Activity {
    static final String EXTRA_DATE = "extra_date";
    static final String EXTRA_ITEM_JSON = "extra_item_json";
    static final String EXTRA_TYPE = "extra_type";

    private static final String[] TYPES = {"日程", "待办", "课程"};
    private String selectedType = "日程";
    private LocalDate selectedDate = LocalDate.now();
    private JSONObject directEditingItem = null;
    private TextView[] typeChips;
    private String startTimeStr = "";
    private String endTimeStr = "";
    private TextView startPill;
    private TextView endPill;
    private TextView startClear;
    private TextView endClear;

    private int dp(float n) { return KnitTheme.dp(this, n); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Window win = getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        String dateExtra = getIntent().getStringExtra(EXTRA_DATE);
        if (dateExtra != null && !dateExtra.isEmpty()) {
            try { selectedDate = LocalDate.parse(dateExtra); } catch (Exception ignored) { }
        }
        String typeExtra = getIntent().getStringExtra(EXTRA_TYPE);
        if (typeExtra != null) {
            for (String type : TYPES) {
                if (type.equals(typeExtra)) {
                    selectedType = type;
                    break;
                }
            }
        }
        String itemExtra = getIntent().getStringExtra(EXTRA_ITEM_JSON);
        if (itemExtra != null && !itemExtra.isEmpty()) {
            try { directEditingItem = new JSONObject(itemExtra); } catch (Exception ignored) { }
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(22), dp(22), dp(22), dp(20));
        card.setBackground(KnitTheme.knitCard(this, KnitTheme.SURFACE_OAT, KnitTheme.BORDER_LINEN, 26, true));
        scroll.addView(card, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Top Header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = new TextView(this);
        heading.setText(directEditingItem != null
                ? "编辑事项"
                : "添加到 " + CalendarUtils.shortDateHeading(selectedDate));
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setTextColor(KnitTheme.INK_PRIMARY);
        header.addView(heading, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView closeBtn = new TextView(this);
        closeBtn.setText("✕");
        closeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        closeBtn.setTextColor(KnitTheme.INK_SECONDARY);
        closeBtn.setGravity(Gravity.CENTER);
        closeBtn.setBackground(KnitTheme.circle(this, KnitTheme.SURFACE_SAND, KnitTheme.BORDER_LINEN, 1));
        closeBtn.setOnClickListener(v -> finish());
        header.addView(closeBtn, new LinearLayout.LayoutParams(dp(32), dp(32)));
        card.addView(header);

        // Category Pill Selector
        LinearLayout typeRow = new LinearLayout(this);
        typeRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams typeRowLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        typeRowLp.topMargin = dp(14);
        typeChips = new TextView[TYPES.length];
        for (int i = 0; i < TYPES.length; i++) {
            final String t = TYPES[i];
            TextView chip = new TextView(this);
            chip.setText(t);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            chip.setTypeface(Typeface.DEFAULT_BOLD);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(10), dp(9), dp(10), dp(9));
            chip.setOnClickListener(v -> {
                selectedType = t;
                updateTypeChips();
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (i > 0) clp.leftMargin = dp(8);
            typeRow.addView(chip, clp);
            typeChips[i] = chip;
        }
        card.addView(typeRow, typeRowLp);

        // Date Picker Pill
        TextView dateBtn = new TextView(this);
        dateBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        dateBtn.setTextColor(KnitTheme.INK_PRIMARY);
        dateBtn.setGravity(Gravity.CENTER_VERTICAL);
        dateBtn.setPadding(dp(16), dp(12), dp(16), dp(12));
        dateBtn.setBackground(KnitTheme.roundedRect(this, KnitTheme.SURFACE_SAND, KnitTheme.BORDER_LINEN, 1, 16));
        LinearLayout.LayoutParams dateLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dateLp.topMargin = dp(12);
        card.addView(dateBtn, dateLp);

        Runnable refreshDateLabel = () -> {
            dateBtn.setText("日期  ·  " + selectedDate + "  " + CalendarUtils.weekdayLabel(selectedDate));
            if (directEditingItem == null) {
                heading.setText("添加到 " + CalendarUtils.shortDateHeading(selectedDate));
            }
        };
        refreshDateLabel.run();
        dateBtn.setOnClickListener(v -> new DatePickerDialog(this, (picker, year, month, day) -> {
            selectedDate = LocalDate.of(year, month + 1, day);
            refreshDateLabel.run();
        }, selectedDate.getYear(), selectedDate.getMonthValue() - 1, selectedDate.getDayOfMonth()).show());

        // Time picker pills
        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout startBox = buildTimeControl("开始时间", true);
        LinearLayout endBox = buildTimeControl("结束时间", false);
        LinearLayout.LayoutParams startLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        LinearLayout.LayoutParams endLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        endLp.leftMargin = dp(10);
        timeRow.addView(startBox, startLp);
        timeRow.addView(endBox, endLp);
        card.addView(timeRow, inputLp());

        // Inputs
        EditText title = buildInput("事项名称");
        EditText place = buildInput("场地 / 教室（选填）");
        card.addView(title, inputLp());
        card.addView(place, inputLp());

        // Populate if editing directly
        if (directEditingItem != null) {
            String d = directEditingItem.optString("date", selectedDate.toString());
            try { selectedDate = LocalDate.parse(d); } catch (Exception ignored) { }
            refreshDateLabel.run();
            String kind = directEditingItem.optString("type", "日程");
            selectedType = ("日程".equals(kind) || "课程".equals(kind) || "待办".equals(kind)) ? kind : "日程";
            title.setText(directEditingItem.optString("title"));
            place.setText(directEditingItem.optString("place", directEditingItem.optString("room")));
            startTimeStr = Items.formatMinutes(Items.itemStart(directEditingItem));
            endTimeStr = Items.formatMinutes(Items.itemEnd(directEditingItem));
        }
        updateTypeChips();
        refreshTimePills();

        // Status text
        TextView status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        status.setTextColor(KnitTheme.INK_SECONDARY);
        status.setPadding(dp(4), dp(10), dp(4), dp(4));
        card.addView(status);

        // Action Buttons Row
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actLp.topMargin = dp(8);

        TextView deleteBtn = new TextView(this);
        deleteBtn.setText("删除");
        deleteBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        deleteBtn.setTypeface(Typeface.DEFAULT_BOLD);
        deleteBtn.setTextColor(KnitTheme.DANGER_CLAY);
        deleteBtn.setGravity(Gravity.CENTER);
        deleteBtn.setPadding(dp(18), dp(13), dp(18), dp(13));
        deleteBtn.setBackground(KnitTheme.roundedRect(this, KnitTheme.DANGER_CLAY_BG, Color.parseColor("#E3C2BE"), 1, 999));
        LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        delLp.rightMargin = dp(10);
        deleteBtn.setVisibility(directEditingItem != null ? View.VISIBLE : View.GONE);
        actions.addView(deleteBtn, delLp);

        TextView saveBtn = new TextView(this);
        saveBtn.setText("保存并同步");
        saveBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        saveBtn.setTypeface(Typeface.DEFAULT_BOLD);
        saveBtn.setTextColor(KnitTheme.SURFACE_OAT);
        saveBtn.setGravity(Gravity.CENTER);
        saveBtn.setPadding(dp(18), dp(13), dp(18), dp(13));
        saveBtn.setBackground(KnitTheme.roundedRect(this, KnitTheme.ACCENT_COCOA, Color.TRANSPARENT, 0, 999));
        actions.addView(saveBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(actions, actLp);

        deleteBtn.setOnClickListener(v -> {
            JSONObject target = directEditingItem;
            String rowId = target == null ? null : target.optString("_row_id", null);
            if (rowId == null || rowId.isEmpty()) {
                status.setText("无法识别原事项，请先刷新。");
                return;
            }
            saveBtn.setEnabled(false);
            deleteBtn.setEnabled(false);
            status.setText("正在删除…");
            new Thread(() -> {
                try {
                    CalendarApi.deleteItem(this, rowId);
                    CalendarApi.syncToday(this);
                    runOnUiThread(() -> {
                        CalendarWidget.renderAll(this);
                        setResult(RESULT_OK);
                        finish();
                    });
                } catch (Exception error) {
                    runOnUiThread(() -> {
                        status.setText("删除失败：" + error.getMessage());
                        saveBtn.setEnabled(true);
                        deleteBtn.setEnabled(true);
                    });
                }
            }).start();
        });

        saveBtn.setOnClickListener(v -> {
            String name = title.getText().toString().trim();
            if (name.isEmpty()) {
                title.setError("请填写事项名称");
                return;
            }
            if (!endTimeStr.isEmpty()) {
                Integer startMinutes = Items.parseMinutes(startTimeStr);
                Integer endMinutes = Items.parseMinutes(endTimeStr);
                if (startTimeStr.isEmpty() || startMinutes == null || endMinutes == null
                        || endMinutes <= startMinutes) {
                    status.setText("结束时间需要晚于开始时间。");
                    return;
                }
            }
            JSONObject original = directEditingItem;
            String id = original == null ? null : original.optString("_row_id", null);
            if (original != null && (id == null || id.isEmpty())) {
                status.setText("无法识别原事项，请刷新后重试。");
                return;
            }
            final JSONObject origFinal = original;
            final String day = selectedDate.toString();
            final String kind = selectedType;
            final String start = startTimeStr;
            final String end = endTimeStr;
            final String room = place.getText().toString().trim();
            saveBtn.setEnabled(false);
            deleteBtn.setEnabled(false);
            status.setText("正在保存并同步…");
            new Thread(() -> {
                try {
                    CalendarApi.saveItem(this, id, origFinal, day, kind, name, start, end, room);
                    CalendarApi.syncToday(this);
                    runOnUiThread(() -> {
                        CalendarWidget.renderAll(this);
                        setResult(RESULT_OK);
                        finish();
                    });
                } catch (Exception error) {
                    runOnUiThread(() -> {
                        status.setText("保存失败：" + error.getMessage());
                        saveBtn.setEnabled(true);
                        deleteBtn.setEnabled(true);
                    });
                }
            }).start();
        });

        setContentView(scroll);
        if (win != null) {
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.90f);
            win.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private LinearLayout buildTimeControl(String label, boolean isStart) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView caption = new TextView(this);
        caption.setText(label);
        caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        caption.setTypeface(Typeface.DEFAULT_BOLD);
        caption.setTextColor(KnitTheme.INK_MUTED);
        caption.setPadding(dp(4), 0, dp(4), dp(4));
        box.addView(caption);

        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);

        TextView pill = new TextView(this);
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        pill.setTextColor(KnitTheme.INK_MUTED);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(14), dp(11), dp(14), dp(11));
        pill.setBackground(KnitTheme.roundedRect(this, KnitTheme.SURFACE_SAND, KnitTheme.BORDER_LINEN, 1, 16));
        pill.setOnClickListener(v -> {
            Integer parsed = Items.parseMinutes(isStart ? startTimeStr : endTimeStr);
            LocalTime now = LocalTime.now();
            int hour = parsed == null ? now.getHour() : parsed / 60;
            int minute = parsed == null ? now.getMinute() : parsed % 60;
            new TimePickerDialog(this, (picker, h, m) -> {
                String value = Items.formatMinutes(Integer.valueOf(h * 60 + m));
                if (isStart) startTimeStr = value;
                else endTimeStr = value;
                refreshTimePills();
            }, hour, minute, true).show();
        });
        line.addView(pill, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView clear = new TextView(this);
        clear.setText("✕");
        clear.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        clear.setGravity(Gravity.CENTER);
        clear.setTextColor(KnitTheme.INK_SECONDARY);
        clear.setBackground(KnitTheme.circle(this, KnitTheme.SURFACE_SAND, KnitTheme.BORDER_LINEN, 1));
        clear.setOnClickListener(v -> {
            if (isStart) {
                startTimeStr = "";
                endTimeStr = "";
            } else {
                endTimeStr = "";
            }
            refreshTimePills();
        });
        LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(dp(32), dp(32));
        clearLp.leftMargin = dp(6);
        line.addView(clear, clearLp);

        box.addView(line);
        if (isStart) {
            startPill = pill;
            startClear = clear;
        } else {
            endPill = pill;
            endClear = clear;
        }
        return box;
    }

    private void refreshTimePills() {
        if (startPill != null) {
            startPill.setText(startTimeStr.isEmpty() ? "选择开始时间" : startTimeStr);
            startPill.setTextColor(startTimeStr.isEmpty() ? KnitTheme.INK_MUTED : KnitTheme.INK_PRIMARY);
        }
        boolean hasStart = !startTimeStr.isEmpty();
        if (endPill != null) {
            endPill.setText(endTimeStr.isEmpty() ? "选择结束时间" : endTimeStr);
            endPill.setTextColor(endTimeStr.isEmpty() ? KnitTheme.INK_MUTED : KnitTheme.INK_PRIMARY);
            endPill.setEnabled(hasStart);
            endPill.setAlpha(hasStart ? 1f : 0.45f);
        }
        if (startClear != null) {
            startClear.setEnabled(!startTimeStr.isEmpty());
            startClear.setAlpha(startTimeStr.isEmpty() ? 0.45f : 1f);
        }
        if (endClear != null) {
            endClear.setEnabled(hasStart && !endTimeStr.isEmpty());
            endClear.setAlpha(hasStart && !endTimeStr.isEmpty() ? 1f : 0.45f);
        }
    }

    private EditText buildInput(String hint) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setHintTextColor(KnitTheme.INK_MUTED);
        input.setTextColor(KnitTheme.INK_PRIMARY);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        input.setSingleLine(true);
        input.setPadding(dp(16), dp(12), dp(16), dp(12));
        input.setBackground(KnitTheme.roundedRect(this, KnitTheme.SURFACE_SAND, KnitTheme.BORDER_LINEN, 1, 16));
        return input;
    }

    private LinearLayout.LayoutParams inputLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        return lp;
    }

    private void updateTypeChips() {
        for (int i = 0; i < TYPES.length; i++) {
            String t = TYPES[i];
            boolean active = t.equals(selectedType);
            int yarn = KnitTheme.colorForType(t);
            int tint = KnitTheme.tintForType(t);
            typeChips[i].setBackground(KnitTheme.roundedRect(
                    this,
                    active ? tint : KnitTheme.SURFACE_SAND,
                    active ? yarn : KnitTheme.BORDER_LINEN,
                    active ? 1.5f : 1f,
                    999));
            typeChips[i].setTextColor(active ? yarn : KnitTheme.INK_SECONDARY);
        }
    }
}
