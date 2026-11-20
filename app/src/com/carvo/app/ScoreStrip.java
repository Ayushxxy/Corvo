package com.carvo.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

public class ScoreStrip extends View {

    private int[] scores = new int[0];
    private final Paint barP = new Paint(Paint.ANTI_ALIAS_FLAG);

    public ScoreStrip(Context c, AttributeSet a) {
        super(c, a);
    }

    public void setData(int[] s) {
        scores = s == null ? new int[0] : s;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        int n = scores.length;
        if (n == 0) return;
        float dp = getResources().getDisplayMetrics().density;
        float w = getWidth(), h = getHeight();
        float gap = 3 * dp;
        float bw = (w - gap * (n - 1)) / n;
        for (int i = 0; i < n; i++) {
            float v = Math.max(0f, Math.min(1f, (scores[i] - 40) / 58f));
            float bh = Math.max(3 * dp, v * (h - 4 * dp));
            float left = i * (bw + gap);
            barP.setColor(i == n - 1 ? 0xFF00E5A0 : 0xFF2E3A55);
            RectF rf = new RectF(left, h - bh, left + bw, h);
            float r = Math.min(bw / 2, 2 * dp);
            c.drawRoundRect(rf, r, r, barP);
        }
    }
}
