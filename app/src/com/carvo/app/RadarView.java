package com.carvo.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/**
 * Pentagon profile radar. The static drawRadar() is shared with the
 * share poster so the on-screen card and the PNG always match.
 */
public class RadarView extends View {

    public static final String[] AXES = {"PACE", "KM", "RHYTHM", "STREAK", "SCORE"};

    private double[] vals = new double[]{0, 0, 0, 0, 0};

    public RadarView(Context c, AttributeSet a) {
        super(c, a);
    }

    public void setData(double[] v) {
        if (v != null && v.length == 5) {
            System.arraycopy(v, 0, vals, 0, 5);
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        float r = Math.min(getWidth(), getHeight()) / 2f - dp(30);
        drawRadar(c, getWidth() / 2f, getHeight() / 2f, r, vals, dp(9), dp(2));
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    /** Draws the pentagon radar — shared with the share poster. */
    public static void drawRadar(Canvas c, float cx, float cy, float r, double[] v,
                                 float labelSize, float strokeW) {
        Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
        grid.setColor(0xFF232C42);
        grid.setStyle(Paint.Style.STROKE);
        grid.setStrokeWidth(2);
        Paint axis = new Paint(grid);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(0x6600E5A0);
        fill.setStyle(Paint.Style.FILL);
        Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        edge.setColor(0xFF00E5A0);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(strokeW);
        edge.setStrokeCap(Paint.Cap.ROUND);
        Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
        pt.setColor(0xFFF2F5FA);
        Paint lbl = new Paint(Paint.ANTI_ALIAS_FLAG);
        lbl.setColor(0xFF8A94A6);
        lbl.setTextSize(labelSize);
        lbl.setTextAlign(Paint.Align.CENTER);

        double[] cl = new double[5];
        for (int i = 0; i < 5; i++) cl[i] = Math.max(0, Math.min(1, v[i]));

        for (int ring = 1; ring <= 3; ring++) {
            float rr = r * ring / 3f;
            for (int i = 0; i < 5; i++) {
                float a1 = (float) Math.toRadians(-90 + i * 72);
                float a2 = (float) Math.toRadians(-90 + (i + 1) % 5 * 72);
                c.drawLine(cx + rr * cos(a1), cy + rr * sin(a1),
                        cx + rr * cos(a2), cy + rr * sin(a2), grid);
            }
        }
        for (int i = 0; i < 5; i++) {
            float a = (float) Math.toRadians(-90 + i * 72);
            c.drawLine(cx, cy, cx + r * cos(a), cy + r * sin(a), axis);
        }
        for (int i = 0; i < 5; i++) {
            float a = (float) Math.toRadians(-90 + i * 72);
            c.drawText(AXES[i],
                    cx + (r + labelSize * 1.6f) * cos(a),
                    cy + (r + labelSize * 1.6f) * sin(a) + labelSize / 3f, lbl);
        }
        Path p = new Path();
        for (int i = 0; i < 5; i++) {
            float a = (float) Math.toRadians(-90 + i * 72);
            float x = cx + r * (float) cl[i] * cos(a);
            float y = cy + r * (float) cl[i] * sin(a);
            if (i == 0) p.moveTo(x, y);
            else p.lineTo(x, y);
        }
        p.close();
        c.drawPath(p, fill);
        c.drawPath(p, edge);
        for (int i = 0; i < 5; i++) {
            float a = (float) Math.toRadians(-90 + i * 72);
            c.drawCircle(cx + r * (float) cl[i] * cos(a),
                    cy + r * (float) cl[i] * sin(a), strokeW * 2.2f, pt);
        }
    }

    private static float cos(double rad) {
        return (float) Math.cos(rad);
    }

    private static float sin(double rad) {
        return (float) Math.sin(rad);
    }
}
