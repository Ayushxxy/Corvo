package com.carvo.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.HashMap;
import java.util.Map;

/**
 * Time Machine track: run-day dots, TODAY marker, dashed future zone,
 * draggable thumb. Pure math + canvas — zero background work.
 */
public class TimeBar extends View {

    public interface Listener {
        void onDay(String isoDate);
    }

    private long minDay = 0, todayDay = 0, maxDay = 0, selDay = 0;
    private final Map<Long, Float> dots = new HashMap<Long, Float>();
    private Listener listener;

    private final Paint lineP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dashP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint txtP = new Paint(Paint.ANTI_ALIAS_FLAG);

    public TimeBar(Context c, AttributeSet a) {
        super(c, a);
        lineP.setColor(0xFF232C42);
        lineP.setStrokeWidth(dp(3));
        lineP.setStrokeCap(Paint.Cap.ROUND);
        dashP.setColor(0xFF2A3148);
        dashP.setStrokeWidth(dp(2));
        dashP.setPathEffect(new DashPathEffect(new float[]{dp(6), dp(7)}, 0));
        dotP.setColor(0xFF00E5A0);
        thumbP.setColor(0xFFF2F5FA);
        ringP.setColor(0xFF00E5A0);
        ringP.setStyle(Paint.Style.STROKE);
        ringP.setStrokeWidth(dp(2));
        glowP.setColor(0x6600E5A0);
        tickP.setColor(0xFF8A94A6);
        tickP.setStrokeWidth(dp(2));
        txtP.setColor(0xFF66708A);
        txtP.setTextSize(dp(9));
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setRange(long min, long today, long max) {
        minDay = min;
        todayDay = today;
        maxDay = max;
        if (selDay < minDay || selDay > maxDay) selDay = todayDay;
        invalidate();
    }

    public void setDots(Map<Long, Float> d) {
        dots.clear();
        if (d != null) dots.putAll(d);
        invalidate();
    }

    public void setSelected(long day) {
        selDay = day;
        invalidate();
    }

    private float pad() {
        return dp(18);
    }

    private float dayToX(long d) {
        long span = Math.max(1, maxDay - minDay);
        return pad() + (float) (d - minDay) * (getWidth() - 2 * pad()) / span;
    }

    private long xToDay(float x) {
        long span = Math.max(1, maxDay - minDay);
        long d = minDay + Math.round((x - pad()) * span / Math.max(1f, getWidth() - 2 * pad()));
        return Math.max(minDay, Math.min(maxDay, d));
    }

    @Override
    protected void onDraw(Canvas c) {
        if (maxDay <= minDay) return;
        float y = getHeight() / 2f;
        float todayX = dayToX(todayDay);

        c.drawLine(pad(), y, todayX, y, lineP);
        c.drawLine(todayX, y, getWidth() - pad(), y, dashP);

        c.drawLine(todayX, y - dp(12), todayX, y + dp(12), tickP);
        txtP.setTextAlign(Paint.Align.CENTER);
        c.drawText("TODAY", todayX, y - dp(18), txtP);

        txtP.setTextAlign(Paint.Align.LEFT);
        c.drawText(RunStore.fromEpochDay(minDay), pad(), getHeight() - dp(2), txtP);
        txtP.setTextAlign(Paint.Align.RIGHT);
        c.drawText(RunStore.fromEpochDay(maxDay), getWidth() - pad(), getHeight() - dp(2), txtP);

        for (Map.Entry<Long, Float> e : dots.entrySet()) {
            float x = dayToX(e.getKey());
            float rel = Math.max(0f, Math.min(1f, e.getValue()));
            dotP.setAlpha(210);
            c.drawCircle(x, y, dp(3) + dp(4) * rel, dotP);
        }
        dotP.setAlpha(255);

        float sx = dayToX(selDay);
        c.drawCircle(sx, y, dp(16), glowP);
        c.drawCircle(sx, y, dp(11), ringP);
        c.drawCircle(sx, y, dp(7), thumbP);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (maxDay <= minDay) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
            case MotionEvent.ACTION_MOVE:
                long d = xToDay(e.getX());
                if (d != selDay) {
                    selDay = d;
                    invalidate();
                    if (listener != null) listener.onDay(RunStore.fromEpochDay(selDay));
                }
                return true;
            default:
                return true;
        }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
