package com.carvo.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.Locale;

public class LineView extends View {

    private double[] paces = new double[0];

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bestRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint avg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);

    public LineView(Context c, AttributeSet a) {
        super(c, a);
        float dp = getResources().getDisplayMetrics().density;
        line.setColor(0xFF00E5A0);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2.5f * dp);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        dot.setColor(0xFFF2F5FA);
        bestRing.setColor(0xFF00E5A0);
        bestRing.setStyle(Paint.Style.STROKE);
        bestRing.setStrokeWidth(2 * dp);
        grid.setColor(0xFF232C42);
        grid.setStyle(Paint.Style.STROKE);
        grid.setStrokeWidth(1 * dp);
        avg.setColor(0xFF4A5268);
        avg.setStyle(Paint.Style.STROKE);
        avg.setStrokeWidth(1.2f * dp);
        avg.setPathEffect(new DashPathEffect(new float[]{6 * dp, 6 * dp}, 0));
        txt.setColor(0xFF00E5A0);
        txt.setTextSize(10 * dp);
        txt.setFakeBoldText(true);
    }

    public void setData(double[] p) {
        paces = p == null ? new double[0] : p;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        int n = paces.length;
        if (n == 0) return;
        float dp = getResources().getDisplayMetrics().density;
        float w = getWidth(), h = getHeight();
        float padL = 8 * dp, padR = 8 * dp, padT = 12 * dp, padB = 12 * dp;
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE, sum = 0;
        for (double v : paces) {
            min = Math.min(min, v);
            max = Math.max(max, v);
            sum += v;
        }
        if (max - min < 0.0001) max = min + 1;
        double avgV = sum / n;
        float iw = w - padL - padR, ih = h - padT - padB;
        for (int g = 1; g <= 2; g++) {
            float y = padT + ih * g / 3f;
            c.drawLine(padL, y, w - padR, y, grid);
        }
        float ay = padT + (float) ((avgV - min) / (max - min)) * ih;
        c.drawLine(padL, ay, w - padR, ay, avg);
        float[] xs = new float[n], ys = new float[n];
        for (int i = 0; i < n; i++) {
            xs[i] = n == 1 ? w / 2 : padL + iw * i / (n - 1);
            ys[i] = padT + (float) ((paces[i] - min) / (max - min)) * ih;
        }
        for (int i = 1; i < n; i++) c.drawLine(xs[i - 1], ys[i - 1], xs[i], ys[i], line);
        int best = 0;
        for (int i = 1; i < n; i++) if (paces[i] < paces[best]) best = i;
        float r = 3.5f * dp;
        for (int i = 0; i < n; i++) {
            if (i == best) {
                c.drawCircle(xs[i], ys[i], r + 3.5f * dp, bestRing);
                c.drawCircle(xs[i], ys[i], r + 1.5f * dp, dot);
            } else {
                c.drawCircle(xs[i], ys[i], r, dot);
            }
        }
        long t = Math.round(paces[best] * 60.0);
        String bs = String.format(Locale.US, "%d:%02d /KM", t / 60, (int) (t % 60));
        float tw = txt.measureText(bs);
        float tx = Math.max(padL, Math.min(xs[best] - tw / 2, w - padR - tw));
        float ty = ys[best] - 10 * dp;
        if (ty < padT + 10 * dp) ty = ys[best] + 18 * dp;
        c.drawText(bs, tx, ty, txt);
    }
}
