package com.calendarboard.widget;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.util.Log;

import org.json.JSONObject;

import java.time.LocalTime;

public final class SyncService extends JobService {
    @Override public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            boolean syncSucceeded = false;
            try {
                if (SessionStore.session(this) != null) {
                    CalendarApi.syncToday(this);
                    syncSucceeded = true;
                }
            } catch (Exception error) {
                Log.w("CalendarBoard", "Widget sync failed: " + error.getClass().getSimpleName());
            } finally {
                JSONObject cache = SessionStore.cache(this);
                NextClassWidget.renderAll(this);
                CalendarWidget.renderAll(this);
                Notifier.updateCourseIsland(this, cache);
                int hour = LocalTime.now().getHour();
                if (hour >= 5 && hour <= 11) {
                    Notifier.maybeSendMorning(this, cache, syncSucceeded);
                }
                ReminderScheduler.rescheduleAll(this);
                jobFinished(params, false);
            }
        }, "calendar-widget-sync").start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        return true;
    }
}

