package com.calendarboard.widget;

import android.app.DatePickerDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/** Evening review sheet for undecided todos from today and earlier. */
public final class ReviewSheet extends BottomSheetDialogFragment implements Repo.Listener {
    private TextView headingView;
    private LinearLayout listContainer;
    private TextView postponeAllBtn;
    private Runnable emptyDismissRunnable;

    static ReviewSheet newInstance() {
        return new ReviewSheet();
    }

    private int dp(float value) {
        return KnitTheme.dp(requireContext(), value);
    }

    @Override public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
        Context context = requireContext();
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(16));
        root.setBackground(KnitTheme.roundedRect(context, KnitTheme.SURFACE_OAT,
                KnitTheme.BORDER_LINEN, 1, 26));

        headingView = new TextView(context);
        headingView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        headingView.setTypeface(Typeface.DEFAULT_BOLD);
        headingView.setTextColor(KnitTheme.INK_PRIMARY);
        root.addView(headingView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(context);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        scrollLp.topMargin = dp(12);
        root.addView(scroll, scrollLp);

        listContainer = new LinearLayout(context);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setPadding(0, 0, 0, dp(6));
        scroll.addView(listContainer, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        postponeAllBtn = new TextView(context);
        postponeAllBtn.setText("全部顺延到明天");
        postponeAllBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        postponeAllBtn.setTypeface(Typeface.DEFAULT_BOLD);
        postponeAllBtn.setTextColor(KnitTheme.ACCENT_COCOA);
        postponeAllBtn.setGravity(Gravity.CENTER);
        postponeAllBtn.setPadding(dp(18), dp(13), dp(18), dp(13));
        postponeAllBtn.setBackground(KnitTheme.roundedRect(context,
                KnitTheme.ACCENT_COCOA_SOFT, KnitTheme.BORDER_LINEN, 1, 999));
        postponeAllBtn.setOnClickListener(v -> postponeAll());
        LinearLayout.LayoutParams footerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        footerLp.topMargin = dp(12);
        root.addView(postponeAllBtn, footerLp);
        return root;
    }

    @Override public void onViewCreated(View view, Bundle state) {
        super.onViewCreated(view, state);
        Repo.get().addListener(this);
        render(Repo.get().snapshot());
    }

    @Override public void onDestroyView() {
        Repo.get().removeListener(this);
        if (emptyDismissRunnable != null) {
            listContainer.removeCallbacks(emptyDismissRunnable);
            emptyDismissRunnable = null;
        }
        listContainer = null;
        headingView = null;
        postponeAllBtn = null;
        super.onDestroyView();
    }

    @Override public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog instanceof BottomSheetDialog) {
            BottomSheetBehavior<FrameLayout> behavior = ((BottomSheetDialog) dialog).getBehavior();
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            behavior.setSkipCollapsed(true);
        }
    }

    @Override public void onRepoChanged(Repo.Snapshot snapshot) {
        if (getView() == null || listContainer == null) return;
        render(snapshot);
    }

    private void render(Repo.Snapshot snapshot) {
        if (snapshot == null || listContainer == null) return;
        final List<JSONObject> list = Items.reviewList(
                snapshot.items, snapshot.today, nowMinutes());
        headingView.setText("今日回顾 · 还有 " + list.size() + " 项待决定");
        listContainer.removeAllViews();

        if (list.isEmpty()) {
            if (emptyDismissRunnable != null) {
                listContainer.removeCallbacks(emptyDismissRunnable);
            }
            TextView done = new TextView(requireContext());
            done.setText("都处理好了");
            done.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            done.setTextColor(KnitTheme.INK_SECONDARY);
            done.setGravity(Gravity.CENTER);
            done.setPadding(dp(8), dp(28), dp(8), dp(28));
            listContainer.addView(done, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            emptyDismissRunnable = () -> {
                if (isAdded()) dismiss();
            };
            listContainer.postDelayed(emptyDismissRunnable, 600);
            return;
        }

        if (emptyDismissRunnable != null) {
            listContainer.removeCallbacks(emptyDismissRunnable);
            emptyDismissRunnable = null;
        }

        for (JSONObject item : list) {
            listContainer.addView(buildRow(item, snapshot.today));
        }
    }

    private View buildRow(final JSONObject item, String today) {
        Context context = requireContext();
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(KnitTheme.roundedRect(context, KnitTheme.SURFACE_SAND,
                KnitTheme.BORDER_LINEN, 1, 18));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(8);
        card.setLayoutParams(cardLp);

        TextView title = new TextView(context);
        title.setText(item.optString("title", "未命名"));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(KnitTheme.INK_PRIMARY);
        card.addView(title);

        String date = item.optString("date", "");
        if (!date.isEmpty() && !date.equals(today)) {
            TextView meta = new TextView(context);
            meta.setText(originalDateLabel(date));
            meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            meta.setTextColor(KnitTheme.INK_SECONDARY);
            meta.setPadding(0, dp(3), 0, 0);
            card.addView(meta);
        }

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsLp.topMargin = dp(8);
        card.addView(actions, actionsLp);

        TextView done = actionButton("已完成", KnitTheme.YARN_COURSE);
        TextView postpone = actionButton("顺延", KnitTheme.ACCENT_COCOA);
        TextView drop = actionButton("放弃", KnitTheme.DANGER_CLAY);
        actions.addView(done, weightedLp(0));
        actions.addView(postpone, weightedLp(8));
        actions.addView(drop, weightedLp(8));

        done.setOnClickListener(v -> complete(item, v));
        postpone.setOnClickListener(v -> postpone(item, tomorrowIso(today), v));
        postpone.setOnLongClickListener(v -> {
            showDatePicker(item, today, v);
            return true;
        });
        drop.setOnClickListener(v -> drop(item, v));
        return card;
    }

    private TextView actionButton(String label, int color) {
        TextView button = new TextView(requireContext());
        button.setText(label);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextColor(color);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(8), dp(9), dp(8), dp(9));
        button.setBackground(KnitTheme.roundedRect(requireContext(), KnitTheme.SURFACE_OAT,
                KnitTheme.BORDER_LINEN, 1, 999));
        return button;
    }

    private LinearLayout.LayoutParams weightedLp(int leftMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.leftMargin = dp(leftMargin);
        return lp;
    }

    private void complete(final JSONObject item, View feedback) {
        final JSONObject updated = Items.applyDone(item, true);
        decide(item, updated, (context, id) ->
                CalendarApi.setTodoDone(context, id, item, true), feedback);
    }

    private void postpone(final JSONObject item, final String targetIso, View feedback) {
        final JSONObject updated = Items.applyPostpone(item, targetIso);
        decide(item, updated, (context, id) ->
                CalendarApi.postpone(context, id, item, targetIso), feedback);
    }

    private void drop(final JSONObject item, View feedback) {
        final JSONObject updated = Items.applyDrop(item);
        decide(item, updated, (context, id) ->
                CalendarApi.drop(context, id, item), feedback);
    }

    private void decide(final JSONObject original, final JSONObject updated,
                        final ApiAction action, View feedback) {
        final String rowId = rowIdOf(original);
        if (rowId.isEmpty()) {
            Toast.makeText(requireContext(), "没有保存成功，请重试", Toast.LENGTH_SHORT).show();
            return;
        }
        if (feedback != null) {
            feedback.performHapticFeedback(Build.VERSION.SDK_INT >= 30
                    ? HapticFeedbackConstants.CONFIRM
                    : HapticFeedbackConstants.VIRTUAL_KEY);
        }
        Repo.get().applyLocal(rowId, updated);

        final Context app = requireContext().getApplicationContext();
        final android.app.Activity host = requireActivity();
        new Thread(() -> {
            try {
                action.run(app, rowId);
            } catch (Exception error) {
                Repo.get().applyLocal(rowId, original);
                host.runOnUiThread(() -> {
                    if (!isAdded()) return;
                    Toast.makeText(requireContext(), "没有保存成功，请重试",
                            Toast.LENGTH_SHORT).show();
                });
            }
        }, "ReviewDecision").start();
    }

    private void postponeAll() {
        Repo.Snapshot snapshot = Repo.get().snapshot();
        List<JSONObject> list = new ArrayList<>(Items.reviewList(
                snapshot.items, snapshot.today, nowMinutes()));
        if (list.isEmpty()) return;
        String target = tomorrowIso(snapshot.today);
        for (JSONObject item : list) postpone(item, target, null);
    }

    private void showDatePicker(final JSONObject item, String today, final View feedback) {
        LocalDate initial;
        try {
            initial = LocalDate.parse(tomorrowIso(today));
        } catch (RuntimeException error) {
            initial = LocalDate.now().plusDays(1);
        }
        new DatePickerDialog(requireContext(), (picker, year, month, day) -> {
            String target = LocalDate.of(year, month + 1, day).toString();
            postpone(item, target, feedback);
        }, initial.getYear(), initial.getMonthValue() - 1, initial.getDayOfMonth()).show();
    }

    private static String tomorrowIso(String today) {
        try {
            return LocalDate.parse(today).plusDays(1).toString();
        } catch (RuntimeException error) {
            return LocalDate.now().plusDays(1).toString();
        }
    }

    private static String originalDateLabel(String iso) {
        try {
            LocalDate date = LocalDate.parse(iso);
            return "原定 " + date.getMonthValue() + "月" + date.getDayOfMonth() + "日";
        } catch (RuntimeException error) {
            return "原定 " + iso;
        }
    }

    private static int nowMinutes() {
        LocalTime now = LocalTime.now();
        return now.getHour() * 60 + now.getMinute();
    }

    private static String rowIdOf(JSONObject item) {
        if (item == null) return "";
        String rowId = item.optString("_row_id", "");
        return rowId.isEmpty() ? item.optString("id", "") : rowId;
    }

    private interface ApiAction {
        void run(Context context, String rowId) throws Exception;
    }
}
