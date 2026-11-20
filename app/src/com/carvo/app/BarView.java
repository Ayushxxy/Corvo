package com.carvo.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import java.util.Locale;

public class BarView extends View {

    private double[] vals = new double[0];
    private String[] labels = new String[0];
    private int todayIdx = -1;

    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dimBar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint txtDim = new Paint(Paint.ANTI_ALIAS_FLAG);

    public BarView(Context c, AttributeSet a) {
        super(c, a);
        float dp = getResources().getDisplayMetrics().density;
        dimBar.setColor(0xFF232C42);
        stub.setColor(0xFF232C42);
        stub.setAlpha(90);
        txt.setColor(0xFF00E5A0);
        txtDim.setColor(0xFF66708A);
        txt.setTextSize(11 * dp);
        txtDim.setTextSize(10 * dp);
        txt.setTextAlign(Paint.Align.CENTER);
        txtDim.setTextAlign(Paint.Align.CENTER);
        txt.setFakeBoldText(true);
    }

    public void setData(double[] v, String[] l, int today) {
        vals = v;
        labels = l;
        todayIdx = today;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        int n = vals.length;
        if (n == 0) return;
        float dp = getResources().getDisplayMetrics().density;
        float w = getWidth(), h = getHeight();
        float bottom = h - 16 * dp;
        double max = 0;
        for (double v : vals) max = Math.max(max, v);
        if (max <= 0) max = 1;
        float gap = 8 * dp;
        float bw = (w - gap * (n - 1)) / n;
        float maxH = bottom - 24 * dp;
        for (int i = 0; i < n; i++) {
            double v = vals[i];
            float bh = (float) (v / max * maxH);
            if (bh < 4 * dp) bh = 4 * dp;
            float left = i * (bw + gap);
            float r = Math.min(bw / 2, bh / 2);
            RectF rf = new RectF(left, bottom - bh, left + bw, bottom);
            if (i == todayIdx && v > 0) {
                bar.setShader(new LinearGradient(left, bottom - bh, left + bw, bottom,
                        0xFF00E5A0, 0xFF00B3FF, Shader.TileMode.CLAMP));
                c.drawRoundRect(rf, r, r, bar);
                c.drawText(String.format(Locale.US, "%.1f", v),
                        left + bw / 2, bottom - bh - 6 * dp, txt);
            } else if (v > 0) {
                c.drawRoundRect(rf, r, r, dimBar);
            } else {
                RectF sf = new RectF(left, bottom - 3 * dp, left + bw, bottom);
                c.drawRoundRect(sf, 1.5f * dp, 1.5f * dp, stub);
            }
            if (labels != null && i < labels.length) {
                c.drawText(labels[i], left + bw / 2, h - 2 * dp, txtDim);
            }
        }
    }
}
