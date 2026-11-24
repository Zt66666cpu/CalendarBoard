package com.calendarboard.widget;

import android.app.Application;
import android.content.Intent;
import android.content.IntentFilter;

import androidx.core.content.ContextCompat;

/** Registers runtime-only system broadcasts (unlock and date rollover) while the process is alive. */
public final class CalendarBoardApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_DATE_CHANGED);
        ContextCompat.registerReceiver(this, new ReminderReceiver(), filter,
                ContextCompat.RECEIVER_NOT_EXPORTED);
    }
}
