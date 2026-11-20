package com.calendarboard.widget;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.time.LocalDate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String WEBSITE = "https://zt66666cpu.github.io/CalendarBoard/";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private LinearLayout root;
    private TextView status;
    private EditText email;
    private EditText password;
    private int generation = 0;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        showScreen();
    }

    @Override protected void onDestroy() {
        generation++;
        executor.shutdown();
        super.onDestroy();
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setPadding(0, dp(8), 0, dp(8));
        return view;
    }

    private Button button(String label, View.OnClickListener action) {
        Button view = new Button(this);
        view.setText(label);
        view.setOnClickListener(action);
        root.addView(view, new LinearLayout.LayoutParams(-1, dp(52)));
        return view;
    }

    private void showScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(235, 241, 248));
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(28), dp(24), dp(24));
        scroll.addView(root);
        setContentView(scroll);

        TextView heading = text("课表日历 · 桌面小组件", 24, Color.rgb(32, 47, 68));
        heading.setGravity(Gravity.START);
        root.addView(heading);
        boolean loggedIn = SessionStore.session(this) != null;
        if (!loggedIn) {
            root.addView(text("用手机日历的同一个账号登录。密码只发送给你的 Supabase 项目。", 14, Color.DKGRAY));
            email = new EditText(this);
            email.setHint("邮箱");
            email.setSingleLine(true);
            email.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
            root.addView(email);
            password = new EditText(this);
            password.setHint("密码");
            password.setSingleLine(true);
            password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            root.addView(password);
            button("登录并同步", view -> login());
        } else {
            root.addView(text("今天 · " + LocalDate.now(), 18, Color.rgb(32, 47, 68)));
            JSONObject cached = SessionStore.cache(this);
            if (LocalDate.now().toString().equals(cached.optString("date"))) {
                root.addView(text(cached.optString("course", "课程：无"), 16, Color.DKGRAY));
                root.addView(text(cached.optString("todo", "待办：无"), 16, Color.DKGRAY));
                root.addView(text(cached.optString("schedule", "日程：无"), 16, Color.DKGRAY));
            } else root.addView(text("还没有今天的数据，点下面的按钮同步。", 16, Color.DKGRAY));
            button("刷新小组件", view -> refresh());
            button("打开完整日历（可添加、编辑）", view -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(WEBSITE))));
            button("退出登录", view -> {
                generation++;
                SessionStore.clear(this);
                CalendarWidget.renderAll(this);
                showScreen();
            });
        }
        status = text(loggedIn ? "桌面长按空白处 → 小组件 → 课表日历" : "登录后再添加桌面小组件", 13, Color.rgb(76, 91, 111));
        root.addView(status);
    }

    private void login() {
        String userEmail = email.getText().toString().trim();
        String userPassword = password.getText().toString();
        if (userEmail.isEmpty() || userPassword.isEmpty()) { status.setText("请填写邮箱和密码。"); return; }
        status.setText("正在登录…");
        int requestGeneration = ++generation;
        executor.execute(() -> {
            try {
                CalendarApi.login(this, userEmail, userPassword);
                CalendarWidget.scheduleNow(this);
                runOnUiThread(() -> { if (requestGeneration == generation) { CalendarWidget.renderAll(this); showScreen(); } });
            } catch (Exception error) {
                runOnUiThread(() -> { if (requestGeneration == generation) status.setText("登录失败：" + error.getMessage()); });
            }
        });
    }

    private void refresh() {
        status.setText("正在同步…");
        int requestGeneration = ++generation;
        executor.execute(() -> {
            try {
                CalendarApi.syncToday(this);
                runOnUiThread(() -> { if (requestGeneration == generation) { CalendarWidget.renderAll(this); showScreen(); } });
            } catch (Exception error) {
                runOnUiThread(() -> { if (requestGeneration == generation) status.setText("同步失败，保留上次数据：" + error.getMessage()); });
            }
        });
    }
}

