package com.calendarboard.widget;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.util.Log;

public final class SyncService extends JobService {
    @Override public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            try {
                if (SessionStore.session(this) != null) CalendarApi.syncToday(this);
            } catch (Exception error) {
                Log.w("CalendarBoard", "Widget sync failed: " + error.getClass().getSimpleName());
            } finally {
                CalendarWidget.renderAll(this);
                jobFinished(params, false);
            }
        }, "calendar-widget-sync").start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        return true;
    }
}

