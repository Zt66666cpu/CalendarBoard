package com.calendarboard.widget;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Archived items browser with filters, restore, delete and 90-day cleanup. */
public final class ArchiveActivity extends AppCompatActivity implements Repo.Listener {
    static final String EXTRA_FOCUS_DATE = "extra_focus_date";

    private static final int PAGE_SIZE = 200;
    private static final String FILTER_ALL = "all";
    private static final String FILTER_TODO = "待办";
    private static final String FILTER_SCHEDULE = "日程";

    private String filter = FILTER_ALL;
    private int limit = PAGE_SIZE;
    private String focusDate;
    private String today = LocalDate.now().toString();

    private RecyclerView recyclerView;
    private TextView emptyView;
    private TextView moreButton;
    private TextView allChip;
    private TextView todoChip;
    private TextView scheduleChip;
    private ArchiveAdapter adapter;

    private int dp(float value) {
        return KnitTheme.dp(this, value);
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        KnitTheme.load(this);
        Repo.get().loadFromCache(this);
        focusDate = getIntent().getStringExtra(EXTRA_FOCUS_DATE);
        buildUi();
        Repo.get().addListener(this);
        render();
    }

    @Override protected void onDestroy() {
        Repo.get().removeListener(this);
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(KnitTheme.knitCanvasBackground(this));
        root.setPadding(dp(18), dp(16), dp(18), dp(14));

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        back.setTypeface(Typeface.DEFAULT_BOLD);
        back.setTextColor(KnitTheme.INK_PRIMARY);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription("返回");
        back.setBackground(KnitTheme.circle(this, KnitTheme.SURFACE_SAND,
                KnitTheme.BORDER_LINEN, 1));
        back.setOnClickListener(v -> finish());
        topBar.addView(back, new LinearLayout.LayoutParams(dp(36), dp(36)));

        TextView title = new TextView(this);
        title.setText("归档");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(KnitTheme.INK_PRIMARY);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        titleLp.leftMargin = dp(12);
        topBar.addView(title, titleLp);
        root.addView(topBar);

        LinearLayout filters = new LinearLayout(this);
        filters.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams filtersLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        filtersLp.topMargin = dp(14);
        allChip = filterChip("全部", FILTER_ALL);
        todoChip = filterChip("待办", FILTER_TODO);
        scheduleChip = filterChip("日程", FILTER_SCHEDULE);
        filters.addView(allChip, weightedLp(0));
        filters.addView(todoChip, weightedLp(8));
        filters.addView(scheduleChip, weightedLp(8));
        root.addView(filters, filtersLp);

        recyclerView = new RecyclerView(this);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setClipToPadding(false);
        recyclerView.setPadding(0, dp(6), 0, dp(6));
        adapter = new ArchiveAdapter();
        recyclerView.setAdapter(adapter);
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        listLp.topMargin = dp(6);
        root.addView(recyclerView, listLp);

        emptyView = new TextView(this);
        emptyView.setText("暂无归档事项");
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        emptyView.setTextColor(KnitTheme.INK_MUTED);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setVisibility(View.GONE);
        root.addView(emptyView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        moreButton = new TextView(this);
        moreButton.setText("加载更多");
        moreButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        moreButton.setTypeface(Typeface.DEFAULT_BOLD);
        moreButton.setTextColor(KnitTheme.ACCENT_COCOA);
        moreButton.setGravity(Gravity.CENTER);
        moreButton.setPadding(dp(16), dp(11), dp(16), dp(11));
        moreButton.setBackground(KnitTheme.roundedRect(this, KnitTheme.ACCENT_COCOA_SOFT,
                KnitTheme.BORDER_LINEN, 1, 999));
        moreButton.setVisibility(View.GONE);
        moreButton.setOnClickListener(v -> {
            limit += PAGE_SIZE;
            render();
        });
        LinearLayout.LayoutParams moreLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        moreLp.topMargin = dp(8);
        root.addView(moreButton, moreLp);

        TextView cleanup = new TextView(this);
        cleanup.setText("清理 90 天前的归档");
        cleanup.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        cleanup.setTypeface(Typeface.DEFAULT_BOLD);
        cleanup.setTextColor(KnitTheme.DANGER_CLAY);
        cleanup.setGravity(Gravity.CENTER);
        cleanup.setPadding(dp(16), dp(11), dp(16), dp(11));
        cleanup.setBackground(KnitTheme.roundedRect(this, KnitTheme.DANGER_CLAY_BG,
                KnitTheme.DANGER_BORDER, 1, 999));
        cleanup.setOnClickListener(v -> confirmCleanup());
        LinearLayout.LayoutParams cleanupLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cleanupLp.topMargin = dp(10);
        root.addView(cleanup, cleanupLp);

        setContentView(root);
        updateFilterChips();
    }

    private TextView filterChip(String label, final String key) {
        TextView chip = new TextView(this);
        chip.setText(label);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(10), dp(9), dp(10), dp(9));
        chip.setOnClickListener(v -> {
            if (filter.equals(key)) return;
            filter = key;
            limit = PAGE_SIZE;
            focusDate = null;
            updateFilterChips();
            render();
        });
        return chip;
    }

    private LinearLayout.LayoutParams weightedLp(int leftMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.leftMargin = dp(leftMargin);
        return lp;
    }

    private void updateFilterChips() {
        styleChip(allChip, FILTER_ALL.equals(filter));
        styleChip(todoChip, FILTER_TODO.equals(filter));
        styleChip(scheduleChip, FILTER_SCHEDULE.equals(filter));
    }

    private void styleChip(TextView chip, boolean active) {
        if (chip == null) return;
        chip.setBackground(KnitTheme.roundedRect(this,
                active ? KnitTheme.ACCENT_COCOA_SOFT : KnitTheme.SURFACE_SAND,
                active ? KnitTheme.ACCENT_COCOA : KnitTheme.BORDER_LINEN,
                active ? 1.5f : 1f, 999));
        chip.setTextColor(active ? KnitTheme.ACCENT_COCOA : KnitTheme.INK_SECONDARY);
    }

    private void render() {
        Repo.Snapshot snapshot = Repo.get().snapshot();
        today = snapshot.today == null || snapshot.today.isEmpty()
                ? LocalDate.now().toString()
                : snapshot.today;
        List<Items.ArchiveGroup> groups = Items.archiveList(snapshot.items, today, filter);
        int total = 0;
        for (Items.ArchiveGroup group : groups) total += group.entries.size();

        int effectiveLimit = limit;
        if (focusDate != null) {
            int cumulative = 0;
            for (Items.ArchiveGroup group : groups) {
                cumulative += group.entries.size();
                if (group.iso.equals(focusDate)) {
                    effectiveLimit = Math.max(effectiveLimit, cumulative);
                    break;
                }
            }
        }

        List<ArchiveRow> rows = new ArrayList<>();
        int shown = 0;
        int focusPosition = -1;
        for (Items.ArchiveGroup group : groups) {
            if (shown >= effectiveLimit) break;
            int remain = effectiveLimit - shown;
            int take = Math.min(remain, group.entries.size());
            if (take <= 0) continue;
            if (focusPosition < 0 && group.iso.equals(focusDate)) {
                focusPosition = rows.size();
            }
            rows.add(ArchiveRow.header(group.iso));
            for (int i = 0; i < take; i++) {
                rows.add(ArchiveRow.entry(group.iso, group.entries.get(i)));
            }
            shown += take;
        }

        boolean empty = total == 0;
        recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        moreButton.setVisibility(total > shown ? View.VISIBLE : View.GONE);

        final String scrollToIso = focusDate;
        final int scrollPosition = focusPosition;
        focusDate = null;
        adapter.submitList(rows, () -> {
            if (scrollToIso != null && scrollPosition >= 0) {
                recyclerView.scrollToPosition(scrollPosition);
            }
        });
    }

    @Override public void onRepoChanged(Repo.Snapshot snapshot) {
        if (recyclerView == null) return;
        render();
    }

    private void restoreItem(final JSONObject item) {
        final String rowId = rowIdOf(item);
        if (rowId.isEmpty()) {
            Toast.makeText(this, "没有保存成功，请重试", Toast.LENGTH_SHORT).show();
            return;
        }
        Repo.get().applyLocal(rowId, Items.applyRestore(item));
        final Context app = getApplicationContext();
        new Thread(() -> {
            try {
                CalendarApi.restore(app, rowId, item);
                runOnUiThread(() -> CalendarWidget.renderAll(app));
            } catch (Exception error) {
                Repo.get().applyLocal(rowId, item);
                runOnUiThread(() -> Toast.makeText(app, "没有保存成功，请重试",
                        Toast.LENGTH_SHORT).show());
            }
        }, "ArchiveRestore").start();
    }

    private void confirmDelete(final JSONObject item) {
        String title = item.optString("title", "事项");
        new AlertDialog.Builder(this)
                .setTitle("删除「" + title + "」？")
                .setPositiveButton("删除", (dialog, which) -> deleteItem(item))
                .setNegativeButton("取消", null)
                .show();
    }

    private void deleteItem(final JSONObject item) {
        final String rowId = rowIdOf(item);
        if (rowId.isEmpty()) {
            Toast.makeText(this, "没有保存成功，请重试", Toast.LENGTH_SHORT).show();
            return;
        }
        Repo.get().applyLocal(rowId, null);
        final Context app = getApplicationContext();
        new Thread(() -> {
            try {
                CalendarApi.deleteItem(app, rowId);
                CalendarApi.syncToday(app);
                runOnUiThread(() -> CalendarWidget.renderAll(app));
            } catch (Exception error) {
                Repo.get().applyLocal(rowId, item);
                runOnUiThread(() -> Toast.makeText(app, "没有保存成功，请重试",
                        Toast.LENGTH_SHORT).show());
            }
        }, "ArchiveDelete").start();
    }

    private List<JSONObject> oldArchivedItems() {
        List<JSONObject> result = new ArrayList<>();
        Repo.Snapshot snapshot = Repo.get().snapshot();
        String base = snapshot.today == null || snapshot.today.isEmpty()
                ? LocalDate.now().toString()
                : snapshot.today;
        String cutoff;
        try {
            cutoff = LocalDate.parse(base).minusDays(90).toString();
        } catch (RuntimeException error) {
            cutoff = LocalDate.now().minusDays(90).toString();
        }
        for (Items.ArchiveGroup group : Items.archiveList(snapshot.items, base, FILTER_ALL)) {
            if (group.iso.isEmpty() || group.iso.compareTo(cutoff) >= 0) continue;
            for (Items.ArchiveEntry entry : group.entries) result.add(entry.item);
        }
        return result;
    }

    private void confirmCleanup() {
        final List<JSONObject> targets = oldArchivedItems();
        if (targets.isEmpty()) {
            Toast.makeText(this, "没有 90 天前的归档事项", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("清理归档")
                .setMessage("将清理 " + targets.size() + " 条 90 天前的归档，确认？")
                .setPositiveButton("清理", (dialog, which) -> cleanup(targets))
                .setNegativeButton("取消", null)
                .show();
    }

    private void cleanup(final List<JSONObject> targets) {
        final int count = targets.size();
        for (JSONObject item : targets) {
            String rowId = rowIdOf(item);
            if (!rowId.isEmpty()) Repo.get().applyLocal(rowId, null);
        }
        final Context app = getApplicationContext();
        new Thread(() -> {
            int failures = 0;
            for (JSONObject item : targets) {
                String rowId = rowIdOf(item);
                if (rowId.isEmpty()) continue;
                try {
                    CalendarApi.deleteItem(app, rowId);
                } catch (Exception error) {
                    failures++;
                }
            }
            try {
                CalendarApi.syncToday(app);
            } catch (Exception error) {
                failures++;
            }
            final int failed = failures;
            runOnUiThread(() -> {
                Repo.get().loadFromCache(app);
                Toast.makeText(app, failed > 0 ? "没有保存成功，请重试"
                        : "已清理 " + count + " 条归档", Toast.LENGTH_SHORT).show();
            });
        }, "ArchiveCleanup").start();
    }

    private static String rowIdOf(JSONObject item) {
        if (item == null) return "";
        String rowId = item.optString("_row_id", "");
        return rowId.isEmpty() ? item.optString("id", "") : rowId;
    }

    private static String stateLabel(String state) {
        if ("done".equals(state)) return "已完成";
        if ("dropped".equals(state)) return "已放弃";
        return "已结束";
    }

    private static int stateColor(String state) {
        if ("done".equals(state)) return KnitTheme.YARN_COURSE;
        if ("dropped".equals(state)) return KnitTheme.DANGER_CLAY;
        return KnitTheme.INK_SECONDARY;
    }

    private static int stateBackground(String state) {
        if ("done".equals(state)) return KnitTheme.YARN_COURSE_BG;
        if ("dropped".equals(state)) return KnitTheme.DANGER_CLAY_BG;
        return KnitTheme.SURFACE_SAND;
    }

    private static String groupLabel(String iso) {
        try {
            return CalendarUtils.shortDateHeading(LocalDate.parse(iso));
        } catch (RuntimeException error) {
            return iso.isEmpty() ? "未知日期" : iso;
        }
    }

    private final class ArchiveAdapter extends ListAdapter<ArchiveRow, RecyclerView.ViewHolder> {
        ArchiveAdapter() {
            super(DIFF);
        }

        @Override public int getItemViewType(int position) {
            return getItem(position).type;
        }

        @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            if (viewType == ArchiveRow.TYPE_HEADER) {
                return new HeaderHolder(buildGroupHeader());
            }
            return buildEntryHolder();
        }

        @Override public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            ArchiveRow row = getItem(position);
            if (holder instanceof HeaderHolder) {
                ((HeaderHolder) holder).label.setText(groupLabel(row.iso));
            } else if (holder instanceof EntryHolder) {
                bindEntry((EntryHolder) holder, row);
            }
        }
    }

    private TextView buildGroupHeader() {
        TextView label = new TextView(this);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextColor(KnitTheme.INK_MUTED);
        label.setPadding(dp(4), dp(14), dp(4), dp(6));
        return label;
    }

    private EntryHolder buildEntryHolder() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(10));
        card.setBackground(KnitTheme.roundedRect(this, KnitTheme.SURFACE_OAT,
                KnitTheme.BORDER_LINEN, 1, 18));
        RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        card.setLayoutParams(lp);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(KnitTheme.INK_PRIMARY);
        titleRow.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView tag = new TextView(this);
        tag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tag.setTypeface(Typeface.DEFAULT_BOLD);
        tag.setPadding(dp(9), dp(3), dp(9), dp(3));
        titleRow.addView(tag);
        card.addView(titleRow);

        TextView meta = new TextView(this);
        meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        meta.setTextColor(KnitTheme.INK_SECONDARY);
        meta.setPadding(0, dp(3), 0, 0);
        card.addView(meta);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsLp.topMargin = dp(8);

        TextView restore = new TextView(this);
        restore.setText("恢复");
        restore.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        restore.setTypeface(Typeface.DEFAULT_BOLD);
        restore.setTextColor(KnitTheme.ACCENT_COCOA);
        restore.setGravity(Gravity.CENTER);
        restore.setPadding(dp(14), dp(7), dp(14), dp(7));
        restore.setBackground(KnitTheme.roundedRect(this, KnitTheme.ACCENT_COCOA_SOFT,
                KnitTheme.BORDER_LINEN, 1, 999));
        LinearLayout.LayoutParams restoreLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        restoreLp.rightMargin = dp(8);
        actions.addView(restore, restoreLp);

        TextView delete = new TextView(this);
        delete.setText("删除");
        delete.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        delete.setTypeface(Typeface.DEFAULT_BOLD);
        delete.setTextColor(KnitTheme.DANGER_CLAY);
        delete.setGravity(Gravity.CENTER);
        delete.setPadding(dp(14), dp(7), dp(14), dp(7));
        delete.setBackground(KnitTheme.roundedRect(this, KnitTheme.DANGER_CLAY_BG,
                KnitTheme.DANGER_BORDER, 1, 999));
        actions.addView(delete);
        card.addView(actions, actionsLp);

        EntryHolder holder = new EntryHolder(card);
        holder.title = title;
        holder.meta = meta;
        holder.tag = tag;
        holder.restore = restore;
        holder.delete = delete;
        return holder;
    }

    private void bindEntry(EntryHolder holder, ArchiveRow row) {
        final JSONObject item = row.entry.item;
        holder.title.setText(item.optString("title", "未命名"));

        String time = item.optString("time", "");
        if (time.isEmpty()) {
            time = Items.buildTime(item.optString("startTime"), item.optString("endTime"));
        }
        String place = item.optString("place", item.optString("room", ""));
        StringBuilder meta = new StringBuilder();
        if (!time.isEmpty()) meta.append(time);
        if (!place.isEmpty()) {
            if (meta.length() > 0) meta.append(" · ");
            meta.append(place);
        }
        holder.meta.setText(meta.toString());
        holder.meta.setVisibility(meta.length() > 0 ? View.VISIBLE : View.GONE);

        holder.tag.setText(stateLabel(row.entry.state));
        holder.tag.setTextColor(stateColor(row.entry.state));
        holder.tag.setBackground(KnitTheme.roundedRect(this, stateBackground(row.entry.state),
                KnitTheme.BORDER_LINEN, 1, 999));

        boolean isTodo = "待办".equals(item.optString("type"));
        holder.restore.setVisibility(isTodo ? View.VISIBLE : View.GONE);
        holder.restore.setOnClickListener(v -> restoreItem(item));
        holder.delete.setOnClickListener(v -> confirmDelete(item));
    }

    private static final class HeaderHolder extends RecyclerView.ViewHolder {
        final TextView label;

        HeaderHolder(TextView view) {
            super(view);
            label = view;
        }
    }

    private static final class EntryHolder extends RecyclerView.ViewHolder {
        TextView title;
        TextView meta;
        TextView tag;
        TextView restore;
        TextView delete;

        EntryHolder(View view) {
            super(view);
        }
    }

    private static final class ArchiveRow {
        static final int TYPE_HEADER = 0;
        static final int TYPE_ENTRY = 1;

        final int type;
        final String iso;
        final String key;
        final String signature;
        final Items.ArchiveEntry entry;

        private ArchiveRow(int type, String iso, String key, String signature,
                           Items.ArchiveEntry entry) {
            this.type = type;
            this.iso = iso;
            this.key = key;
            this.signature = signature;
            this.entry = entry;
        }

        static ArchiveRow header(String iso) {
            return new ArchiveRow(TYPE_HEADER, iso, "h:" + iso, "h:" + iso, null);
        }

        static ArchiveRow entry(String iso, Items.ArchiveEntry entry) {
            String id = rowIdOf(entry.item);
            return new ArchiveRow(TYPE_ENTRY, iso, "e:" + iso + ":" + id,
                    id + "|" + entry.state + "|" + entry.item, entry);
        }
    }

    private static final DiffUtil.ItemCallback<ArchiveRow> DIFF =
            new DiffUtil.ItemCallback<ArchiveRow>() {
                @Override public boolean areItemsTheSame(ArchiveRow oldItem, ArchiveRow newItem) {
                    return oldItem.key.equals(newItem.key);
                }

                @Override public boolean areContentsTheSame(ArchiveRow oldItem, ArchiveRow newItem) {
                    return oldItem.signature.equals(newItem.signature);
                }
            };
}
