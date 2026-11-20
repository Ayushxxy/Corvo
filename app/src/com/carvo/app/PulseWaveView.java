package com.carvo.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

public class PulseWaveView extends View {

    private static final int CAP = 140;
    private final float[] buf = new float[CAP];
    private int count = 0;
    private int head = 0;

    private int waveColor = 0xFFFF5C5C;
    private final Paint gridP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lineP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path fillPath = new Path();

    public PulseWaveView(Context ctx) {
        super(ctx);
        gridP.setColor(0xFF151C28);
        gridP.setStrokeWidth(2f);
        gridP.setStyle(Paint.Style.STROKE);

        lineP.setStyle(Paint.Style.STROKE);
        lineP.setStrokeWidth(5f);
        lineP.setStrokeCap(Paint.Cap.ROUND);
        lineP.setStrokeJoin(Paint.Join.ROUND);

        fillP.setStyle(Paint.Style.FILL);
        dotP.setStyle(Paint.Style.FILL);
    }

    public void setWaveColor(int color) {
        if (this.waveColor != color) {
            this.waveColor = color;
            invalidate();
        }
    }

    public void clear() {
        count = 0;
        head = 0;
        invalidate();
    }

    public void push(float v) {
        buf[head] = v;
        head = (head + 1) % CAP;
        if (count < CAP) count++;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        // Subtle ECG-style grid
        for (int i = 1; i <= 3; i++) {
            float y = h * i / 4f;
            c.drawLine(0, y, w, y, gridP);
        }
        for (int i = 1; i <= 5; i++) {
            float x = w * i / 6f;
            c.drawLine(x, 0, x, h, gridP);
        }

        if (count < 3) {
            gridP.setColor(0xFF232C42);
            c.drawLine(0, h / 2f, w, h / 2f, gridP);
            gridP.setColor(0xFF151C28);
            return;
        }

        // Compute dynamic range across current window for dramatic smooth wave
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            int idx = (head - count + i + CAP) % CAP;
            float v = buf[idx];
            if (v < min) min = v;
            if (v > max) max = v;
        }
        float span = Math.max(0.15f, max - min);
        float padY = h * 0.16f;
        float drawH = h - padY * 2f;

        path.reset();
        fillPath.reset();
        float lastX = 0f, lastY = h / 2f;
        for (int i = 0; i < count; i++) {
            int idx = (head - count + i + CAP) % CAP;
            float x = (w * i) / Math.max(1, CAP - 1);
            float norm = (buf[idx] - min) / span; // 0..1
            float y = padY + (1f - norm) * drawH;
            if (i == 0) {
                path.moveTo(x, y);
                fillPath.moveTo(x, h);
                fillPath.lineTo(x, y);
            } else {
                path.lineTo(x, y);
                fillPath.lineTo(x, y);
            }
            lastX = x;
            lastY = y;
        }
        fillPath.lineTo(lastX, h);
        fillPath.close();

        fillP.setShader(new LinearGradient(0, 0, 0, h,
                (waveColor & 0x00FFFFFF) | 0x44000000,
                (waveColor & 0x00FFFFFF) | 0x02000000,
                Shader.TileMode.CLAMP));
        c.drawPath(fillPath, fillP);

        lineP.setColor(waveColor);
        c.drawPath(path, lineP);

        dotP.setColor((waveColor & 0x00FFFFFF) | 0x44000000);
        c.drawCircle(lastX, lastY, 13f, dotP);
        dotP.setColor(0xFFF2F5FA);
        c.drawCircle(lastX, lastY, 6f, dotP);
    }
}
