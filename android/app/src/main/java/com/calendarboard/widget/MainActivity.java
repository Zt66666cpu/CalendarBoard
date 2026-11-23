package com.calendarboard.widget;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String WEBSITE = "https://zt66666cpu.github.io/CalendarBoard/";
    private static final int REQ_QUICK_ADD = 101;
    private static final String[] WEEKDAY_SHORT = {"一", "二", "三", "四", "五", "六", "日"};
    private static final String[] CATEGORIES = {"课程", "待办", "日程"};

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private int generation = 0;

    private LocalDate selectedDate = LocalDate.now();
    private YearMonth displayMonth = YearMonth.now();
    private boolean showingWeekTab = false;
    private boolean syncing = false;

    private TextView statusLabel;
    private EditText emailInput;
    private EditText passwordInput;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window win = getWindow();
        if (win != null) {
            win.setStatusBarColor(KnitTheme.BG_CREAM);
            win.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        renderScreen();
        if (SessionStore.session(this) != null) {
            JSONObject cache = SessionStore.cache(this);
            if (!LocalDate.now().toString().equals(cache.optString("date")) || cache.optJSONArray("all_items") == null) {
                refreshData(true);
            }
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_QUICK_ADD && resultCode == RESULT_OK) {
            renderScreen();
        }
    }

    @Override protected void onDestroy() {
        generation++;
        executor.shutdown();
        super.onDestroy();
    }

    private int dp(float v) { return KnitTheme.dp(this, v); }

    private void renderScreen() {
        boolean loggedIn = SessionStore.session(this) != null;
        if (!loggedIn) {
            renderLoginScreen();
        } else {
            renderCalendarScreen();
        }
    }

    // ==================== LOGIN SCREEN ====================

    private void renderLoginScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setBackground(KnitTheme.knitCanvasBackground(this));

        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setGravity(Gravity.CENTER);
        outer.setPadding(dp(24), dp(36), dp(24), dp(36));
        scroll.addView(outer);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(24), dp(28), dp(24), dp(24));
        card.setBackground(KnitTheme.knitCard(this, KnitTheme.SURFACE_OAT, KnitTheme.BORDER_LINEN, 28, true));
        outer.addView(card, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Decorative Yarn Dots
        LinearLayout dots = new LinearLayout(this);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER_VERTICAL);
        int[] yarnColors = {KnitTheme.YARN_COURSE, KnitTheme.YARN_TODO, KnitTheme.YARN_SCHEDULE};
        for (int i = 0; i < yarnColors.length; i++) {
            View dot = new View(this);
            dot.setBackground(KnitTheme.circle(this, yarnColors[i], Color.TRANSPARENT, 0));
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(9), dp(9));
            if (i > 0) dlp.leftMargin = dp(6);
            dots.addView(dot, dlp);
        }
        card.addView(dots);

        TextView title = new TextView(this);
        title.setText("课表日历");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 25);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(KnitTheme.INK_PRIMARY);
        title.setPadding(0, dp(12), 0, dp(4));
        card.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("登录与 Mac / 网页端相同的账号，即可实时同步课表、待办与桌面小组件。");
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        subtitle.setTextColor(KnitTheme.INK_SECONDARY);
        subtitle.setLineSpacing(dp(3), 1f);
        subtitle.setPadding(0, 0, 0, dp(18));
        card.addView(subtitle);

        emailInput = buildInput("邮箱地址", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        card.addView(emailInput, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        passwordInput = buildInput("密码", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams pwLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pwLp.topMargin = dp(12);
        card.addView(passwordInput, pwLp);

        TextView loginBtn = new TextView(this);
        loginBtn.setText("登录并同步");
        loginBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        loginBtn.setTypeface(Typeface.DEFAULT_BOLD);
        loginBtn.setTextColor(KnitTheme.SURFACE_OAT);
        loginBtn.setGravity(Gravity.CENTER);
        loginBtn.setPadding(dp(16), dp(14), dp(16), dp(14));
        loginBtn.setBackground(KnitTheme.roundedRect(this, KnitTheme.ACCENT_COCOA, Color.TRANSPARENT, 0, 999));
        loginBtn.setOnClickListener(v -> doLogin());
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.topMargin = dp(20);
        card.addView(loginBtn, btnLp);

        statusLabel = new TextView(this);
        statusLabel.setText("登录后长按桌面空白处可添加暖米白小组件");
        statusLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        statusLabel.setTextColor(KnitTheme.INK_MUTED);
        statusLabel.setGravity(Gravity.CENTER);
        statusLabel.setPadding(0, dp(14), 0, 0);
        card.addView(statusLabel);

        setContentView(scroll);
    }

    private EditText buildInput(String hint, int inputType) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setHintTextColor(KnitTheme.INK_MUTED);
        input.setTextColor(KnitTheme.INK_PRIMARY);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        input.setSingleLine(true);
        input.setInputType(inputType);
        input.setPadding(dp(16), dp(13), dp(16), dp(13));
        input.setBackground(KnitTheme.roundedRect(this, KnitTheme.SURFACE_SAND, KnitTheme.BORDER_LINEN, 1, 18));
        return input;
    }

    // ==================== MAIN CALENDAR SCREEN ====================

    private void renderCalendarScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setBackground(KnitTheme.knitCanvasBackground(this));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(28));
        scroll.addView(root);

        JSONObject cache = SessionStore.cache(this);
        List<JSONObject> allItems = loadAllItems(cache);
        Map<String, List<JSONObject>> byDate = groupByDate(allItems);

        // 1. Top Header Bar
        root.addView(buildTopHeader());

        // 2. Segmented Tab Switcher
        root.addView(buildTabSwitcher());

        if (!showingWeekTab) {
            // 3. Interactive Month Calendar Knit Card
            root.addView(buildMonthCard(byDate, allItems));

            // 4. Selected Day Details
            root.addView(buildSelectedDayHeader());
            root.addView(buildDayAgenda(selectedDate, allItems));
        } else {
            // 7-Day Agenda View
            root.addView(buildSevenDayList(allItems));
        }

        // 5. Status Footer
        statusLabel = new TextView(this);
        String syncedAt = cache.optString("synced_at", "");
        String footerText = isReadOnly() ? "请升级应用"
                : (syncing ? "正在与云端同步…"
                : (syncedAt.isEmpty() ? "点右上角 ↻ 同步最新日程" : syncedAt + " 已同步 · 长按桌面可添加小组件"));
        statusLabel.setText(footerText);
        statusLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        statusLabel.setTextColor(KnitTheme.INK_MUTED);
        statusLabel.setGravity(Gravity.CENTER);
        statusLabel.setPadding(0, dp(18), 0, dp(6));
        root.addView(statusLabel);

        setContentView(scroll);
    }

    private View buildTopHeader() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(2), dp(2), dp(2), dp(10));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);

        TextView heading = new TextView(this);
        heading.setText(displayMonth.getYear() + "年 " + displayMonth.getMonthValue() + "月");
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setTextColor(KnitTheme.INK_PRIMARY);
        left.addView(heading);

        String term = CalendarUtils.semesterWeekFull(this, selectedDate);
        if (!term.isEmpty()) {
            TextView termPill = new TextView(this);
            termPill.setText(term);
            termPill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            termPill.setTypeface(Typeface.DEFAULT_BOLD);
            termPill.setTextColor(KnitTheme.INK_SECONDARY);
            termPill.setPadding(dp(10), dp(3), dp(10), dp(3));
            termPill.setBackground(KnitTheme.roundedRect(this, KnitTheme.SURFACE_SAND, KnitTheme.BORDER_LINEN, 1, 999));
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.topMargin = dp(4);
            left.addView(termPill, tlp);
        }
        bar.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        bar.addView(circleAction("今", 13, false, v -> {
            selectedDate = LocalDate.now();
            displayMonth = YearMonth.now();
            renderScreen();
        }));
        bar.addView(circleAction("↻", 16, false, v -> refreshData(false)));
        bar.addView(circleAction("⋯", 16, false, this::showMoreMenu));

        return bar;
    }

    private View circleAction(String label, float sp, boolean primary, View.OnClickListener listener) {
        TextView btn = new TextView(this);
        btn.setText(label);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setGravity(Gravity.CENTER);
        btn.setTextColor(primary ? KnitTheme.SURFACE_OAT : KnitTheme.INK_PRIMARY);
        btn.setBackground(KnitTheme.circle(
                this,
                primary ? KnitTheme.ACCENT_COCOA : KnitTheme.SURFACE_OAT,
                primary ? Color.TRANSPARENT : KnitTheme.BORDER_LINEN,
                primary ? 0 : 1));
        btn.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(36));
        lp.leftMargin = dp(7);
        btn.setLayoutParams(lp);
        return btn;
    }

    private void showMoreMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "打开网页版日历");
        menu.getMenu().add(0, 2, 1, "刷新桌面小组件");
        menu.getMenu().add(0, 4, 2, "学期设置");
        menu.getMenu().add(0, 3, 3, "退出登录");
        boolean readOnly = isReadOnly();
        menu.getMenu().findItem(4).setEnabled(!readOnly);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(WEBSITE)));
                return true;
            }
            if (item.getItemId() == 2) {
                refreshData(false);
                return true;
            }
            if (item.getItemId() == 4) {
                showSemesterDialog();
                return true;
            }
            if (item.getItemId() == 3) {
                generation++;
                SessionStore.clear(this);
                CalendarWidget.renderAll(this);
                renderScreen();
                return true;
            }
            return false;
        });
        menu.show();
    }

    private boolean isReadOnly() {
        JSONObject meta = SessionStore.cache(this).optJSONObject("meta");
        return meta != null && meta.optInt("schemaVersion", 2) > Items.SUPPORTED_SCHEMA;
    }

    private void showSemesterDialog() {
        if (isReadOnly()) {
            if (statusLabel != null) statusLabel.setText("请升级应用");
            return;
        }
        JSONObject cachedMeta = SessionStore.cache(this).optJSONObject("meta");
        final JSONObject meta = cachedMeta == null ? Items.defaultMeta() : cachedMeta;

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(10), dp(20), dp(4));

        EditText nameInput = buildInput("学期名称（1–12 个字）", InputType.TYPE_CLASS_TEXT);
        nameInput.setText(meta.optString("termName", "2026秋"));
        form.addView(nameInput);

        final EditText startInput = buildInput("学期第一天（YYYY-MM-DD）", InputType.TYPE_CLASS_TEXT);
        startInput.setText(meta.optString("termStart", "2026-08-31"));
        startInput.setFocusable(false);
        startInput.setClickable(true);
        startInput.setOnClickListener(v -> {
            LocalDate initial;
            try {
                initial = LocalDate.parse(startInput.getText().toString().trim());
            } catch (Exception error) {
                initial = LocalDate.now();
            }
            new DatePickerDialog(this, (picker, year, month, day) -> {
                startInput.setText(LocalDate.of(year, month + 1, day).toString());
            }, initial.getYear(), initial.getMonthValue() - 1, initial.getDayOfMonth()).show();
        });
        LinearLayout.LayoutParams startLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        startLp.topMargin = dp(10);
        form.addView(startInput, startLp);

        EditText weeksInput = buildInput("学期周数（1–30）", InputType.TYPE_CLASS_NUMBER);
        weeksInput.setText(String.valueOf(meta.optInt("termWeeks", 20)));
        LinearLayout.LayoutParams weeksLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        weeksLp.topMargin = dp(10);
        form.addView(weeksInput, weeksLp);

        TextView errorLabel = new TextView(this);
        errorLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        errorLabel.setTextColor(KnitTheme.DANGER_CLAY);
        errorLabel.setPadding(dp(4), dp(10), dp(4), 0);
        form.addView(errorLabel);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("学期设置")
                .setView(form)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String termName = nameInput.getText().toString().trim();
                    if (termName.isEmpty() || termName.length() > 12) {
                        errorLabel.setText("学期名称需为 1–12 个字");
                        return;
                    }
                    String startText = startInput.getText().toString().trim();
                    LocalDate termStart;
                    try {
                        termStart = LocalDate.parse(startText);
                    } catch (Exception error) {
                        errorLabel.setText("学期第一天格式应为 YYYY-MM-DD");
                        return;
                    }
                    int termWeeks;
                    try {
                        termWeeks = Integer.parseInt(weeksInput.getText().toString().trim());
                    } catch (Exception error) {
                        errorLabel.setText("学期周数需为 1–30 的整数");
                        return;
                    }
                    if (termWeeks < 1 || termWeeks > 30) {
                        errorLabel.setText("学期周数需为 1–30 的整数");
                        return;
                    }

                    JSONObject updated = new JSONObject();
                    try {
                        updated = new JSONObject(meta.toString());
                        updated.put("termName", termName);
                        updated.put("termStart", termStart.toString());
                        updated.put("termWeeks", termWeeks);
                        updated.put("type", "meta");
                    } catch (Exception error) {
                        errorLabel.setText("无法保存学期设置");
                        return;
                    }

                    dialog.dismiss();
                    if (statusLabel != null) statusLabel.setText("正在保存学期设置…");
                    final JSONObject updatedMeta = updated;
                    int reqGen = ++generation;
                    executor.execute(() -> {
                        try {
                            CalendarApi.saveMeta(this, updatedMeta);
                            runOnUiThread(() -> {
                                if (reqGen == generation) {
                                    CalendarWidget.renderAll(this);
                                    renderScreen();
                                }
                            });
                        } catch (Exception error) {
                            runOnUiThread(() -> {
                                if (reqGen == generation && statusLabel != null) {
                                    statusLabel.setText("学期设置保存失败：" + error.getMessage());
                                }
                            });
                        }
                    });
                }));
        dialog.show();
    }

    private View buildTabSwitcher() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setPadding(dp(4), dp(4), dp(4), dp(4));
        container.setBackground(KnitTheme.roundedRect(this, KnitTheme.SURFACE_SAND, KnitTheme.BORDER_LINEN, 1, 999));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = dp(12);
        container.setLayoutParams(clp);

        container.addView(tabPill("月历视图", !showingWeekTab, v -> {
            if (showingWeekTab) { showingWeekTab = false; renderScreen(); }
        }));
        container.addView(tabPill("未来 7 天", showingWeekTab, v -> {
            if (!showingWeekTab) { showingWeekTab = true; renderScreen(); }
        }));
        return container;
    }

    private View tabPill(String text, boolean active, View.OnClickListener listener) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(10), dp(8), dp(10), dp(8));
        tv.setTextColor(active ? KnitTheme.INK_PRIMARY : KnitTheme.INK_SECONDARY);
        tv.setBackground(active
                ? KnitTheme.roundedRect(this, KnitTheme.SURFACE_OAT, KnitTheme.BORDER_LINEN, 1, 999)
                : null);
        tv.setOnClickListener(listener);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return tv;
    }

    private View buildMonthCard(Map<String, List<JSONObject>> byDate, List<JSONObject> allItems) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setBackground(KnitTheme.knitCard(this, KnitTheme.SURFACE_OAT, KnitTheme.BORDER_LINEN, 26, true));

        // Month Navigation Row
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setPadding(dp(4), 0, dp(4), dp(10));

        TextView prev = navArrow("‹", v -> {
            displayMonth = displayMonth.minusMonths(1);
            renderScreen();
        });
        TextView next = navArrow("›", v -> {
            displayMonth = displayMonth.plusMonths(1);
            renderScreen();
        });
        TextView monthLbl = new TextView(this);
        monthLbl.setText(displayMonth.getYear() + " · " + String.format("%02d", displayMonth.getMonthValue()));
        monthLbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        monthLbl.setTypeface(Typeface.DEFAULT_BOLD);
        monthLbl.setTextColor(KnitTheme.INK_SECONDARY);
        monthLbl.setGravity(Gravity.CENTER);

        nav.addView(prev);
        nav.addView(monthLbl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        nav.addView(next);
        card.addView(nav);

        // Weekday Labels
        LinearLayout weekRow = new LinearLayout(this);
        weekRow.setOrientation(LinearLayout.HORIZONTAL);
        weekRow.setPadding(0, 0, 0, dp(6));
        for (int i = 0; i < 7; i++) {
            TextView w = new TextView(this);
            w.setText(WEEKDAY_SHORT[i]);
            w.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            w.setTypeface(Typeface.DEFAULT_BOLD);
            w.setTextColor(i >= 5 ? KnitTheme.YARN_SCHEDULE : KnitTheme.INK_MUTED);
            w.setGravity(Gravity.CENTER);
            weekRow.addView(w, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        card.addView(weekRow);

        // Calendar Grid (Monday-first)
        LocalDate firstOfMonth = displayMonth.atDay(1);
        int leadingEmpty = firstOfMonth.getDayOfWeek().getValue() - 1; // 0 = Mon .. 6 = Sun
        int daysInMonth = displayMonth.lengthOfMonth();
        int totalCells = ((leadingEmpty + daysInMonth + 6) / 7) * 7;
        LocalDate startCell = firstOfMonth.minusDays(leadingEmpty);
        LocalDate today = LocalDate.now();

        for (int rowIdx = 0; rowIdx < totalCells / 7; rowIdx++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int col = 0; col < 7; col++) {
                LocalDate cellDate = startCell.plusDays(rowIdx * 7L + col);
                boolean inMonth = cellDate.getMonthValue() == displayMonth.getMonthValue();
                boolean isToday = cellDate.equals(today);
                boolean isSelected = cellDate.equals(selectedDate);
                row.addView(buildDayCell(cellDate, inMonth, isToday, isSelected, allItems));
            }
            card.addView(row);
        }

        return card;
    }

    private TextView navArrow(String symbol, View.OnClickListener listener) {
        TextView tv = new TextView(this);
        tv.setText(symbol);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(KnitTheme.INK_PRIMARY);
        tv.setGravity(Gravity.CENTER);
        tv.setBackground(KnitTheme.circle(this, KnitTheme.SURFACE_SAND, KnitTheme.BORDER_LINEN, 1));
        tv.setOnClickListener(listener);
        tv.setLayoutParams(new LinearLayout.LayoutParams(dp(30), dp(30)));
        return tv;
    }

    private View buildDayCell(LocalDate date, boolean inMonth, boolean isToday, boolean isSelected, List<JSONObject> allItems) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(dp(2), dp(6), dp(2), dp(5));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1);
        lp.setMargins(dp(2), dp(2), dp(2), dp(2));
        cell.setLayoutParams(lp);

        if (isSelected) {
            cell.setBackground(KnitTheme.roundedRect(this, KnitTheme.ACCENT_COCOA, Color.TRANSPARENT, 0, 15));
        } else if (isToday) {
            cell.setBackground(KnitTheme.roundedRect(this, KnitTheme.SURFACE_SAND, KnitTheme.YARN_TODO, 1.3f, 15));
        }

        TextView num = new TextView(this);
        num.setText(String.valueOf(date.getDayOfMonth()));
        num.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        num.setTypeface((isSelected || isToday) ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        if (isSelected) num.setTextColor(KnitTheme.SURFACE_OAT);
        else if (isToday) num.setTextColor(KnitTheme.YARN_TODO);
        else if (!inMonth) num.setTextColor(KnitTheme.BORDER_LINEN);
        else num.setTextColor(KnitTheme.INK_PRIMARY);
        cell.addView(num);

        // Holiday or Dots
        String holiday = inMonth ? CalendarUtils.holidayLabel(date) : "";
        Items.DayDots dotState = Items.dayDots(allItems, date.toString(), LocalDate.now().toString());
        boolean hasCourse = dotState.course;
        boolean hasTodo = dotState.todo;
        boolean hasSchedule = dotState.schedule;

        if (hasCourse || hasTodo || hasSchedule) {
            LinearLayout dots = new LinearLayout(this);
            dots.setOrientation(LinearLayout.HORIZONTAL);
            dots.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dlp.topMargin = dp(4);
            if (hasCourse) dots.addView(tinyDot(isSelected ? Color.parseColor("#B9D5E6") : KnitTheme.YARN_COURSE));
            if (hasTodo) dots.addView(tinyDot(isSelected ? Color.parseColor("#F1CBA6") : KnitTheme.YARN_TODO));
            if (hasSchedule) dots.addView(tinyDot(isSelected ? Color.parseColor("#EAC0C2") : KnitTheme.YARN_SCHEDULE));
            cell.addView(dots, dlp);
        } else if (!holiday.isEmpty()) {
            TextView h = new TextView(this);
            h.setText(holiday);
            h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f);
            h.setTextColor(isSelected ? KnitTheme.ACCENT_COCOA_SOFT : KnitTheme.YARN_SCHEDULE);
            h.setSingleLine(true);
            cell.addView(h);
        }

        cell.setOnClickListener(v -> {
            selectedDate = date;
            displayMonth = YearMonth.from(date);
            renderScreen();
        });
        return cell;
    }

    private View tinyDot(int color) {
        View d = new View(this);
        d.setBackground(KnitTheme.circle(this, color, Color.TRANSPARENT, 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(4.5f), dp(4.5f));
        lp.setMargins(dp(1.5f), 0, dp(1.5f), 0);
        d.setLayoutParams(lp);
        return d;
    }

    private View buildSelectedDayHeader() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        lp.bottomMargin = dp(10);
        row.setLayoutParams(lp);

        TextView heading = new TextView(this);
        heading.setText(CalendarUtils.shortDateHeading(selectedDate));
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setTextColor(KnitTheme.INK_PRIMARY);
        row.addView(heading);

        if (selectedDate.equals(LocalDate.now())) {
            TextView todayTag = new TextView(this);
            todayTag.setText("今天");
            todayTag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
            todayTag.setTypeface(Typeface.DEFAULT_BOLD);
            todayTag.setTextColor(KnitTheme.YARN_TODO);
            todayTag.setPadding(dp(8), dp(2.5f), dp(8), dp(2.5f));
            todayTag.setBackground(KnitTheme.roundedRect(this, KnitTheme.YARN_TODO_BG, KnitTheme.YARN_TODO, 1, 999));
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.leftMargin = dp(8);
            row.addView(todayTag, tlp);
        }

        View spacer = new View(this);
        row.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));

        TextView addBtn = new TextView(this);
        addBtn.setText("＋ 添加到 " + selectedDate.getMonthValue() + "月" + selectedDate.getDayOfMonth() + "日");
        addBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        addBtn.setTypeface(Typeface.DEFAULT_BOLD);
        addBtn.setTextColor(KnitTheme.ACCENT_COCOA);
        addBtn.setPadding(dp(12), dp(6), dp(12), dp(6));
        addBtn.setBackground(KnitTheme.roundedRect(this, KnitTheme.SURFACE_OAT, KnitTheme.BORDER_LINEN, 1, 999));
        boolean readOnly = isReadOnly();
        addBtn.setEnabled(!readOnly);
        addBtn.setAlpha(readOnly ? 0.45f : 1f);
        addBtn.setOnClickListener(v -> openQuickAdd(selectedDate, null));
        row.addView(addBtn);

        return row;
    }

    private View buildDayAgenda(LocalDate date, List<JSONObject> allItems) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);

        String dateStr = date.toString();
        String todayStr = LocalDate.now().toString();
        Items.DayItemsResult dayData = Items.dayItems(allItems, dateStr, todayStr);

        boolean anyGroup = false;
        for (String category : CATEGORIES) {
            List<JSONObject> list = new ArrayList<>();
            if ("课程".equals(category)) {
                list.addAll(dayData.courses);
            } else if (!dayData.past && "待办".equals(category)) {
                list.addAll(dayData.todos);
                if (dateStr.equals(todayStr)) list.addAll(dayData.pending);
            } else if (!dayData.past && "日程".equals(category)) {
                list.addAll(dayData.schedules);
            }
            if (list.isEmpty()) continue;
            list.sort(Items.BY_TIME);

            anyGroup = true;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(15), dp(13), dp(15), dp(13));
            card.setBackground(KnitTheme.knitCard(this, KnitTheme.SURFACE_OAT, KnitTheme.BORDER_LINEN, 22, true));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.bottomMargin = dp(10);

            // Category header
            LinearLayout ch = new LinearLayout(this);
            ch.setOrientation(LinearLayout.HORIZONTAL);
            ch.setGravity(Gravity.CENTER_VERTICAL);
            View dot = new View(this);
            dot.setBackground(KnitTheme.circle(this, KnitTheme.colorForType(category), Color.TRANSPARENT, 0));
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(8), dp(8));
            dlp.rightMargin = dp(8);
            ch.addView(dot, dlp);

            TextView cTitle = new TextView(this);
            cTitle.setText(category + " · " + list.size());
            cTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            cTitle.setTypeface(Typeface.DEFAULT_BOLD);
            cTitle.setTextColor(KnitTheme.colorForType(category));
            ch.addView(cTitle);
            card.addView(ch);

            for (JSONObject item : list) {
                View row = buildItemRow(item, true);
                if (dayData.past && "课程".equals(category)) row.setAlpha(0.55f);
                card.addView(row);
            }
            container.addView(card, clp);
        }

        if (!anyGroup) {
            LinearLayout emptyCard = new LinearLayout(this);
            emptyCard.setOrientation(LinearLayout.VERTICAL);
            emptyCard.setGravity(Gravity.CENTER);
            emptyCard.setPadding(dp(20), dp(26), dp(20), dp(26));
            emptyCard.setBackground(KnitTheme.knitCard(this, KnitTheme.SURFACE_OAT, KnitTheme.BORDER_LINEN, 24, true));

            TextView eTitle = new TextView(this);
            eTitle.setText("这一天很轻松，暂无安排");
            eTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
            eTitle.setTypeface(Typeface.DEFAULT_BOLD);
            eTitle.setTextColor(KnitTheme.INK_SECONDARY);
            emptyCard.addView(eTitle);

            TextView eSub = new TextView(this);
            eSub.setText("点上方「添加」可随时记录课程、待办或日程");
            eSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            eSub.setTextColor(KnitTheme.INK_MUTED);
            eSub.setPadding(0, dp(5), 0, 0);
            emptyCard.addView(eSub);

            container.addView(emptyCard);
        }

        if (dayData.past && dayData.archivedCount > 0) {
            TextView archived = new TextView(this);
            archived.setText("这一天有 " + dayData.archivedCount + " 条已归档 ›");
            archived.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            archived.setTypeface(Typeface.DEFAULT_BOLD);
            archived.setTextColor(KnitTheme.INK_MUTED);
            archived.setGravity(Gravity.CENTER);
            archived.setPadding(0, dp(6), 0, dp(6));
            container.addView(archived);
        }

        return container;
    }

    private View buildSevenDayList(List<JSONObject> allItems) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);

        boolean firstDay = true;
        for (Items.WeekDay day : Items.weekAhead(allItems, LocalDate.now().toString())) {
            LocalDate date = LocalDate.parse(day.iso);

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(15), dp(13), dp(15), dp(13));
            card.setBackground(KnitTheme.knitCard(this, KnitTheme.SURFACE_OAT, KnitTheme.BORDER_LINEN, 22, true));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (!firstDay) clp.topMargin = dp(10);
            firstDay = false;

            LinearLayout header = new LinearLayout(this);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);

            TextView dTitle = new TextView(this);
            dTitle.setText(CalendarUtils.shortDateHeading(date));
            dTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
            dTitle.setTypeface(Typeface.DEFAULT_BOLD);
            dTitle.setTextColor(KnitTheme.INK_PRIMARY);
            header.addView(dTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            TextView quickBtn = new TextView(this);
            quickBtn.setText("＋");
            quickBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            quickBtn.setTypeface(Typeface.DEFAULT_BOLD);
            quickBtn.setTextColor(KnitTheme.INK_SECONDARY);
            quickBtn.setGravity(Gravity.CENTER);
            quickBtn.setBackground(KnitTheme.circle(this, KnitTheme.SURFACE_SAND, KnitTheme.BORDER_LINEN, 1));
            boolean readOnly = isReadOnly();
            quickBtn.setEnabled(!readOnly);
            quickBtn.setAlpha(readOnly ? 0.45f : 1f);
            quickBtn.setOnClickListener(v -> openQuickAdd(date, null));
            header.addView(quickBtn, new LinearLayout.LayoutParams(dp(26), dp(26)));
            card.addView(header);

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
                card.addView(courseSummary, summaryLp);
            }

            if (day.courseCount == 0 && day.items.isEmpty()) {
                TextView empty = new TextView(this);
                empty.setText("暂无安排");
                empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
                empty.setTextColor(KnitTheme.INK_MUTED);
                empty.setPadding(0, dp(7), 0, dp(2));
                card.addView(empty);
            } else {
                for (JSONObject item : day.items) {
                    card.addView(buildItemRow(item, false));
                }
            }
            container.addView(card, clp);
        }
        return container;
    }

    private View buildItemRow(JSONObject item, boolean hideTypeTag) {
        String type = item.optString("type", "待办");
        boolean isTodo = "待办".equals(type);
        boolean done = item.optBoolean("done");
        int yarn = KnitTheme.colorForType(type);
        int tint = done ? KnitTheme.SURFACE_SAND : KnitTheme.tintForType(type);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setBackground(KnitTheme.roundedRect(this, tint, Color.TRANSPARENT, 0, 16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        row.setLayoutParams(lp);

        if (isTodo) {
            TextView check = new TextView(this);
            check.setText(done ? "✓" : "");
            check.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            check.setTypeface(Typeface.DEFAULT_BOLD);
            check.setTextColor(KnitTheme.SURFACE_OAT);
            check.setGravity(Gravity.CENTER);
            check.setBackground(KnitTheme.circle(
                    this,
                    done ? yarn : KnitTheme.SURFACE_OAT,
                    yarn,
                    1.5f));
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(dp(22), dp(22));
            cLp.rightMargin = dp(10);
            boolean readOnly = isReadOnly();
            check.setEnabled(!readOnly);
            check.setAlpha(readOnly ? 0.45f : 1f);
            check.setOnClickListener(v -> toggleTodo(item, !done));
            row.addView(check, cLp);
        } else {
            View bar = new View(this);
            bar.setBackground(KnitTheme.roundedRect(this, yarn, Color.TRANSPARENT, 0, 999));
            LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(dp(4), dp(26));
            bLp.rightMargin = dp(10);
            row.addView(bar, bLp);
        }

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText(item.optString("title", "未命名"));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(done ? KnitTheme.INK_MUTED : KnitTheme.INK_PRIMARY);
        if (done) title.setPaintFlags(title.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        textCol.addView(title);

        String time = item.optString("time", "");
        String place = item.optString("place", item.optString("room", ""));
        StringBuilder meta = new StringBuilder();
        if (!hideTypeTag) meta.append(type);
        if (!time.isEmpty()) {
            if (meta.length() > 0) meta.append(" · ");
            meta.append(time);
        }
        if (!place.isEmpty()) {
            if (meta.length() > 0) meta.append(" · ");
            meta.append(place);
        }
        if (meta.length() > 0) {
            TextView sub = new TextView(this);
            sub.setText(meta.toString());
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            sub.setTextColor(KnitTheme.INK_SECONDARY);
            sub.setPadding(0, dp(2), 0, 0);
            textCol.addView(sub);
        }

        row.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView chevron = new TextView(this);
        chevron.setText("›");
        chevron.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        chevron.setTextColor(KnitTheme.INK_MUTED);
        chevron.setPadding(dp(6), 0, dp(2), 0);
        row.addView(chevron);

        row.setOnClickListener(v -> openQuickAdd(selectedDate, item));
        return row;
    }

    // ==================== DATA & ACTIONS ====================

    private List<JSONObject> loadAllItems(JSONObject cache) {
        List<JSONObject> result = new ArrayList<>();
        JSONArray arr = cache.optJSONArray("all_items");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item != null) result.add(item);
            }
            return result;
        }
        // Fallback if upgrading from v1.6 cache before first sync completes
        JSONArray editable = cache.optJSONArray("editable_items");
        if (editable != null) {
            for (int i = 0; i < editable.length(); i++) {
                JSONObject item = editable.optJSONObject(i);
                if (item != null) result.add(item);
            }
        }
        return result;
    }

    private Map<String, List<JSONObject>> groupByDate(List<JSONObject> items) {
        Map<String, List<JSONObject>> map = new HashMap<>();
        for (JSONObject item : items) {
            String d = item.optString("date", "");
            if (d.isEmpty()) continue;
            map.computeIfAbsent(d, k -> new ArrayList<>()).add(item);
        }
        return map;
    }

    private void openQuickAdd(LocalDate date, JSONObject editingItem) {
        Intent intent = new Intent(this, QuickAddActivity.class);
        if (date != null) intent.putExtra(QuickAddActivity.EXTRA_DATE, date.toString());
        if (editingItem != null) intent.putExtra(QuickAddActivity.EXTRA_ITEM_JSON, editingItem.toString());
        startActivityForResult(intent, REQ_QUICK_ADD);
    }

    private void doLogin() {
        String userEmail = emailInput.getText().toString().trim();
        String userPassword = passwordInput.getText().toString();
        if (userEmail.isEmpty() || userPassword.isEmpty()) {
            statusLabel.setText("请填写邮箱和密码。");
            return;
        }
        statusLabel.setText("正在登录并同步日历…");
        int reqGen = ++generation;
        executor.execute(() -> {
            try {
                CalendarApi.login(this, userEmail, userPassword);
                CalendarApi.syncToday(this);
                CalendarWidget.scheduleNow(this);
                runOnUiThread(() -> {
                    if (reqGen == generation) {
                        CalendarWidget.renderAll(this);
                        renderScreen();
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (reqGen == generation) statusLabel.setText("登录失败：" + error.getMessage());
                });
            }
        });
    }

    private void refreshData(boolean silent) {
        syncing = true;
        if (!silent && statusLabel != null) statusLabel.setText("正在同步云端日历…");
        int reqGen = ++generation;
        executor.execute(() -> {
            try {
                CalendarApi.syncToday(this);
                runOnUiThread(() -> {
                    syncing = false;
                    if (reqGen == generation) {
                        CalendarWidget.renderAll(this);
                        renderScreen();
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    syncing = false;
                    if (reqGen == generation && statusLabel != null) {
                        statusLabel.setText("同步失败（已保留离线数据）：" + error.getMessage());
                    }
                });
            }
        });
    }

    private void toggleTodo(JSONObject item, boolean done) {
        if (isReadOnly()) {
            if (statusLabel != null) statusLabel.setText("请升级应用");
            return;
        }
        String rowId = item.optString("_row_id", "");
        if (rowId.isEmpty()) {
            if (statusLabel != null) statusLabel.setText("无法识别事项，请先点击 ↻ 刷新");
            return;
        }
        if (statusLabel != null) statusLabel.setText(done ? "正在标记完成…" : "正在恢复待办…");
        int reqGen = ++generation;
        executor.execute(() -> {
            try {
                CalendarApi.setTodoDone(this, rowId, item, done);
                CalendarApi.syncToday(this);
                runOnUiThread(() -> {
                    if (reqGen == generation) {
                        CalendarWidget.renderAll(this);
                        renderScreen();
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (reqGen == generation && statusLabel != null) {
                        statusLabel.setText("更新失败：" + error.getMessage());
                    }
                });
            }
        });
    }
}
