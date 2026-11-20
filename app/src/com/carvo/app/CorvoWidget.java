package com.carvo.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

public class CorvoWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        RunStore store = new RunStore(c);
        int streak = store.currentStreak();
        for (int id : ids) {
            RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_streak);
            rv.setTextViewText(R.id.wStreakNum, String.valueOf(streak));
            Intent i = new Intent(c, MainActivity.class);
            PendingIntent pi = PendingIntent.getActivity(c, 79, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            rv.setOnClickPendingIntent(R.id.wRoot, pi);
            m.updateAppWidget(id, rv);
        }
    }

    public static void refresh(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        if (m == null) return;
        int[] ids = m.getAppWidgetIds(new ComponentName(c, CorvoWidget.class));
        if (ids != null && ids.length > 0) {
            new CorvoWidget().onUpdate(c, m, ids);
        }
    }
}
