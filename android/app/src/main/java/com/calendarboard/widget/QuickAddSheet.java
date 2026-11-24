package com.calendarboard.widget;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.Dialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/** Bottom sheet form for creating, editing and deleting a single calendar item. */
public final class QuickAddSheet extends BottomSheetDialogFragment {
    private static final String ARG_DATE = "date_iso";
    private static final String ARG_TYPE = "type";
    private static final String ARG_ITEM = "item_json";
    private static final String[] TYPES = {"日程", "待办", "课程"};

    private String selectedType = "日程";
    private LocalDate selectedDate = LocalDate.now();
    private JSONObject editingItem = null;
    private TextView[] typeChips;
    private String startTimeStr = "";
    private String endTimeStr = "";
    private TextView startPill;
    private TextView endPill;
    private android.widget.ImageView startClear;
    private android.widget.ImageView endClear;
    private TextView headingView;
    private TextView dateBtn;
    private EditText titleInput;
    private EditText placeInput;
    private TextView statusView;
    private TextView saveBtn;
    private TextView deleteBtn;

    static QuickAddSheet newInstance(String dateIso, String defaultType, String editingItemJson) {
        QuickAddSheet sheet = new QuickAddSheet();
        Bundle args = new Bundle();
        if (dateIso != null && !dateIso.isEmpty()) args.putString(ARG_DATE, dateIso);
        if (defaultType != null && !defaultType.isEmpty()) args.putString(ARG_TYPE, defaultType);
        if (editingItemJson != null && !editingItemJson.isEmpty()) args.putString(ARG_ITEM, editingItemJson);
        sheet.setArguments(args);
        return sheet;
    }

    private int dp(float value) {
        return KnitTheme.dp(requireContext(), value);
    }

    @Override public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
        Context context = requireContext();
        applyArguments();

        ScrollView scroll = new ScrollView(context);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(22), dp(18), dp(22), dp(20));
        card.setBackground(KnitTheme.knitCard(context, KnitTheme.SURFACE_OAT,
                KnitTheme.BORDER_LINEN, 26, true));
        scroll.addView(card, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        buildHeader(context, card);
        buildTypePicker(context, card);
        buildDatePicker(context, card);
        buildTimePickers(context, card);
        buildInputs(context, card);

        if (editingItem != null) populateFromEditingItem();

        statusView = new TextView(context);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        statusView.setTextColor(KnitTheme.INK_SECONDARY);
        statusView.setPadding(dp(4), dp(10), dp(4), dp(4));
        card.addView(statusView);

        buildActions(context, card);

        updateTypeChips();
        refreshTimePills();
        refreshDateLabel();
        return scroll;
    }

    private void applyArguments() {
        Bundle args = getArguments();
        if (args == null) return;
        String date = args.getString(ARG_DATE);
        if (date != null && !date.isEmpty()) {
            try {
                selectedDate = LocalDate.parse(date);
            } catch (RuntimeException ignored) {
                selectedDate = LocalDate.now();
            }
        }
        String type = args.getString(ARG_TYPE);
        if (isKnownType(type)) selectedType = type;
        String item = args.getString(ARG_ITEM);
        if (item != null && !item.isEmpty()) {
            try {
                editingItem = new JSONObject(item);
            } catch (JSONException ignored) {
                editingItem = null;
            }
        }
    }

    private void buildHeader(Context context, LinearLayout card) {
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        headingView = new TextView(context);
        headingView.setText(editingItem != null ? "编辑事项" : "添加到 " + CalendarUtils.shortDateHeading(selectedDate));
        headingView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        headingView.setTypeface(Typeface.DEFAULT_BOLD);
        headingView.setTextColor(KnitTheme.INK_PRIMARY);
        header.addView(headingView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        android.widget.ImageView close = new android.widget.ImageView(context);
        close.setImageResource(R.drawable.ic_close);
        close.setImageTintList(android.content.res.ColorStateList.valueOf(KnitTheme.INK_SECONDARY));
        close.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        close.setPadding(dp(7), dp(7), dp(7), dp(7));
        close.setContentDescription("关闭");
        close.setBackground(KnitTheme.circle(context, KnitTheme.SURFACE_SAND,
                KnitTheme.BORDER_LINEN, 1));
        close.setOnClickListener(v -> dismiss());
        header.addView(close, new LinearLayout.LayoutParams(dp(32), dp(32)));
        card.addView(header);
    }

    private void buildTypePicker(Context context, LinearLayout card) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(14);

        typeChips = new TextView[TYPES.length];
        for (int i = 0; i < TYPES.length; i++) {
            final String type = TYPES[i];
            TextView chip = new TextView(context);
            chip.setText(type);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            chip.setTypeface(Typeface.DEFAULT_BOLD);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(10), dp(9), dp(10), dp(9));
            chip.setOnClickListener(v -> {
                selectedType = type;
                updateTypeChips();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (i > 0) lp.leftMargin = dp(8);
            row.addView(chip, lp);
            typeChips[i] = chip;
        }
        card.addView(row, rowLp);
    }

    private void buildDatePicker(Context context, LinearLayout card) {
        dateBtn = new TextView(context);
        dateBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        dateBtn.setTextColor(KnitTheme.INK_PRIMARY);
        dateBtn.setGravity(Gravity.CENTER_VERTICAL);
        dateBtn.setPadding(dp(16), dp(12), dp(16), dp(12));
        dateBtn.setBackground(KnitTheme.roundedRect(context, KnitTheme.SURFACE_SAND,
                KnitTheme.BORDER_LINEN, 1, 16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        card.addView(dateBtn, lp);

        dateBtn.setOnClickListener(v -> new DatePickerDialog(context,
                (picker, year, month, day) -> {
                    selectedDate = LocalDate.of(year, month + 1, day);
                    refreshDateLabel();
                },
                selectedDate.getYear(), selectedDate.getMonthValue() - 1,
                selectedDate.getDayOfMonth()).show());
    }

    private void buildTimePickers(Context context, LinearLayout card) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout startBox = buildTimeControl("开始时间", true);
        LinearLayout endBox = buildTimeControl("结束时间", false);
        LinearLayout.LayoutParams startLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        LinearLayout.LayoutParams endLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        endLp.leftMargin = dp(10);
        row.addView(startBox, startLp);
        row.addView(endBox, endLp);
        card.addView(row, inputLp());
    }

    private void buildInputs(Context context, LinearLayout card) {
        titleInput = buildInput("事项名称");
        placeInput = buildInput("场地 / 教室（选填）");
        card.addView(titleInput, inputLp());
        card.addView(placeInput, inputLp());
    }

    private void buildActions(Context context, LinearLayout card) {
        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsLp.topMargin = dp(8);

        deleteBtn = new TextView(context);
        deleteBtn.setText("删除");
        deleteBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        deleteBtn.setTypeface(Typeface.DEFAULT_BOLD);
        deleteBtn.setTextColor(KnitTheme.DANGER_CLAY);
        deleteBtn.setGravity(Gravity.CENTER);
        deleteBtn.setPadding(dp(18), dp(13), dp(18), dp(13));
        deleteBtn.setBackground(KnitTheme.roundedRect(context, KnitTheme.DANGER_CLAY_BG,
                KnitTheme.DANGER_BORDER, 1, 999));
        LinearLayout.LayoutParams deleteLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        deleteLp.rightMargin = dp(10);
        deleteBtn.setVisibility(editingItem != null ? View.VISIBLE : View.GONE);
        actions.addView(deleteBtn, deleteLp);

        saveBtn = new TextView(context);
        saveBtn.setText("保存并同步");
        saveBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        saveBtn.setTypeface(Typeface.DEFAULT_BOLD);
        saveBtn.setTextColor(KnitTheme.SURFACE_OAT);
        saveBtn.setGravity(Gravity.CENTER);
        saveBtn.setPadding(dp(18), dp(13), dp(18), dp(13));
        saveBtn.setBackground(KnitTheme.roundedRect(context, KnitTheme.ACCENT_COCOA,
                android.graphics.Color.TRANSPARENT, 0, 999));
        actions.addView(saveBtn, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(actions, actionsLp);

        deleteBtn.setOnClickListener(v -> confirmDelete());
        saveBtn.setOnClickListener(v -> saveItem());
    }

    private LinearLayout buildTimeControl(String label, boolean isStart) {
        Context context = requireContext();
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView caption = new TextView(context);
        caption.setText(label);
        caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        caption.setTypeface(Typeface.DEFAULT_BOLD);
        caption.setTextColor(KnitTheme.INK_MUTED);
        caption.setPadding(dp(4), 0, dp(4), dp(4));
        box.addView(caption);

        LinearLayout line = new LinearLayout(context);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);

        TextView pill = new TextView(context);
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        pill.setTextColor(KnitTheme.INK_MUTED);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(14), dp(11), dp(14), dp(11));
        pill.setBackground(KnitTheme.roundedRect(context, KnitTheme.SURFACE_SAND,
                KnitTheme.BORDER_LINEN, 1, 16));
        pill.setOnClickListener(v -> {
            Integer parsed = Items.parseMinutes(isStart ? startTimeStr : endTimeStr);
            LocalTime now = LocalTime.now();
            int hour = parsed == null ? now.getHour() : parsed / 60;
            int minute = parsed == null ? now.getMinute() : parsed % 60;
            new TimePickerDialog(context, (picker, h, m) -> {
                String value = Items.formatMinutes(Integer.valueOf(h * 60 + m));
                if (isStart) startTimeStr = value;
                else endTimeStr = value;
                refreshTimePills();
            }, hour, minute, true).show();
        });
        line.addView(pill, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        android.widget.ImageView clear = new android.widget.ImageView(context);
        clear.setImageResource(R.drawable.ic_close);
        clear.setImageTintList(android.content.res.ColorStateList.valueOf(KnitTheme.INK_SECONDARY));
        clear.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        clear.setPadding(dp(8), dp(8), dp(8), dp(8));
        clear.setContentDescription("清除" + label);
        clear.setBackground(KnitTheme.circle(context, KnitTheme.SURFACE_SAND,
                KnitTheme.BORDER_LINEN, 1));
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

    private void populateFromEditingItem() {
        if (editingItem == null) return;
        String date = editingItem.optString("date", selectedDate.toString());
        try {
            selectedDate = LocalDate.parse(date);
        } catch (RuntimeException ignored) {
            selectedDate = LocalDate.now();
        }
        String kind = editingItem.optString("type", "日程");
        selectedType = isKnownType(kind) ? kind : "日程";
        titleInput.setText(editingItem.optString("title"));
        placeInput.setText(editingItem.optString("place", editingItem.optString("room")));
        startTimeStr = Items.formatMinutes(Items.itemStart(editingItem));
        endTimeStr = Items.formatMinutes(Items.itemEnd(editingItem));
    }

    private void saveItem() {
        String name = titleInput.getText().toString().trim();
        if (name.isEmpty()) {
            titleInput.setError("请填写事项名称");
            return;
        }
        if (!endTimeStr.isEmpty()) {
            Integer startMinutes = Items.parseMinutes(startTimeStr);
            Integer endMinutes = Items.parseMinutes(endTimeStr);
            if (startTimeStr.isEmpty() || startMinutes == null || endMinutes == null
                    || endMinutes <= startMinutes) {
                statusView.setText("结束时间需要晚于开始时间。");
                return;
            }
        }
        final JSONObject original = editingItem;
        final String rowId = original == null ? null : rowIdOf(original);
        if (original != null && (rowId == null || rowId.isEmpty())) {
            statusView.setText("无法识别原事项，请刷新后重试。");
            return;
        }
        final JSONObject payload;
        try {
            payload = buildPayload(original, name);
        } catch (JSONException error) {
            statusView.setText("无法保存事项，请重试。");
            return;
        }

        final String localId = rowId == null
                ? "local-" + UUID.randomUUID().toString().replace("-", "")
                : rowId;
        final String apiId = rowId;
        final String day = selectedDate.toString();
        final String kind = selectedType;
        final String start = startTimeStr;
        final String end = endTimeStr;
        final String place = placeInput.getText().toString().trim();

        Repo.get().applyLocal(localId, payload);
        setBusy(true, "正在保存并同步…");

        final Context app = requireContext().getApplicationContext();
        final android.app.Activity host = requireActivity();
        new Thread(() -> {
            try {
                CalendarApi.saveItem(app, apiId, original, day, kind, name, start, end, place);
                host.runOnUiThread(() -> {
                    CalendarWidget.renderAll(app);
                    markResultOk();
                    if (isAdded()) dismiss();
                });
            } catch (Exception error) {
                Repo.get().applyLocal(localId, original);
                host.runOnUiThread(() -> {
                    if (!isAdded()) return;
                    setBusy(false, "没有保存成功，请重试");
                    Toast.makeText(requireContext(), "没有保存成功，请重试", Toast.LENGTH_SHORT).show();
                });
            }
        }, "QuickAddSave").start();
    }

    private JSONObject buildPayload(JSONObject original, String name) throws JSONException {
        JSONObject item = original == null ? new JSONObject() : new JSONObject(original.toString());
        item.remove("_row_id");
        item.remove("id");
        item.put("type", selectedType)
                .put("title", name)
                .put("date", selectedDate.toString())
                .put("startTime", startTimeStr)
                .put("endTime", endTimeStr)
                .put("time", Items.buildTime(startTimeStr, endTimeStr))
                .put("place", placeInput.getText().toString().trim());
        if (item.has("room")) item.put("room", placeInput.getText().toString().trim());
        if (item.has("display")) item.put("display", name);
        if ("待办".equals(selectedType) && original == null) item.put("done", false);
        return item;
    }

    private void confirmDelete() {
        if (editingItem == null) return;
        final JSONObject target = editingItem;
        final String rowId = rowIdOf(target);
        if (rowId.isEmpty()) {
            statusView.setText("无法识别原事项，请先刷新。");
            return;
        }
        String itemTitle = titleInput.getText().toString().trim();
        if (itemTitle.isEmpty()) itemTitle = target.optString("title", "事项");
        new AlertDialog.Builder(requireContext())
                .setTitle("删除「" + itemTitle + "」？")
                .setPositiveButton("删除", (dialog, which) -> deleteItem(target, rowId))
                .setNegativeButton("取消", null)
                .show();
    }

    private void deleteItem(JSONObject target, String rowId) {
        Repo.get().applyLocal(rowId, null);
        setBusy(true, "正在删除…");
        final Context app = requireContext().getApplicationContext();
        final android.app.Activity host = requireActivity();
        new Thread(() -> {
            try {
                CalendarApi.deleteItem(app, rowId);
                CalendarApi.syncToday(app);
                host.runOnUiThread(() -> {
                    CalendarWidget.renderAll(app);
                    markResultOk();
                    if (isAdded()) dismiss();
                });
            } catch (Exception error) {
                Repo.get().applyLocal(rowId, target);
                host.runOnUiThread(() -> {
                    if (!isAdded()) return;
                    setBusy(false, "没有保存成功，请重试");
                    Toast.makeText(requireContext(), "没有保存成功，请重试", Toast.LENGTH_SHORT).show();
                });
            }
        }, "QuickAddDelete").start();
    }

    private void markResultOk() {
        android.app.Activity activity = getActivity();
        if (activity != null) activity.setResult(android.app.Activity.RESULT_OK);
    }

    private void setBusy(boolean busy, String message) {
        if (saveBtn != null) saveBtn.setEnabled(!busy);
        if (deleteBtn != null) deleteBtn.setEnabled(!busy);
        if (statusView != null) statusView.setText(message);
    }

    private void refreshDateLabel() {
        if (dateBtn == null) return;
        dateBtn.setText("日期  ·  " + selectedDate + "  " + CalendarUtils.weekdayLabel(selectedDate));
        if (editingItem == null && headingView != null) {
            headingView.setText("添加到 " + CalendarUtils.shortDateHeading(selectedDate));
        }
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
        Context context = requireContext();
        EditText input = new EditText(context);
        input.setHint(hint);
        input.setHintTextColor(KnitTheme.INK_MUTED);
        input.setTextColor(KnitTheme.INK_PRIMARY);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        input.setSingleLine(true);
        input.setPadding(dp(16), dp(12), dp(16), dp(12));
        input.setBackground(KnitTheme.roundedRect(context, KnitTheme.SURFACE_SAND,
                KnitTheme.BORDER_LINEN, 1, 16));
        return input;
    }

    private LinearLayout.LayoutParams inputLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        return lp;
    }

    private void updateTypeChips() {
        if (typeChips == null) return;
        for (int i = 0; i < TYPES.length; i++) {
            String type = TYPES[i];
            boolean active = type.equals(selectedType);
            int yarn = KnitTheme.colorForType(type);
            int tint = KnitTheme.tintForType(type);
            typeChips[i].setBackground(KnitTheme.roundedRect(requireContext(),
                    active ? tint : KnitTheme.SURFACE_SAND,
                    active ? yarn : KnitTheme.BORDER_LINEN,
                    active ? 1.5f : 1f, 999));
            typeChips[i].setTextColor(active ? yarn : KnitTheme.INK_SECONDARY);
        }
    }

    private static boolean isKnownType(String type) {
        if (type == null) return false;
        for (String known : TYPES) {
            if (known.equals(type)) return true;
        }
        return false;
    }

    private static String rowIdOf(JSONObject item) {
        if (item == null) return "";
        String rowId = item.optString("_row_id", "");
        return rowId.isEmpty() ? item.optString("id", "") : rowId;
    }

    @Override public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog instanceof BottomSheetDialog) {
            BottomSheetBehavior<FrameLayout> behavior = ((BottomSheetDialog) dialog).getBehavior();
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            behavior.setSkipCollapsed(true);
        }
        if (titleInput != null) {
            titleInput.postDelayed(() -> {
                if (!isAdded()) return;
                titleInput.requestFocus();
                InputMethodManager manager = (InputMethodManager)
                        requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                if (manager != null) {
                    manager.showSoftInput(titleInput, InputMethodManager.SHOW_IMPLICIT);
                }
            }, 200);
        }
    }

    @Override public void onDismiss(android.content.DialogInterface dialog) {
        super.onDismiss(dialog);
        android.app.Activity activity = getActivity();
        if (activity instanceof QuickAddActivity) activity.finish();
    }
}
