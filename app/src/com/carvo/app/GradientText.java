package com.carvo.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.widget.TextView;

/** CORVO wordmark — diagonal green-to-cyan material gradient. */
public class GradientText extends TextView {

    public GradientText(Context c) {
        super(c);
    }

    public GradientText(Context c, AttributeSet a) {
        super(c, a);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (getWidth() > 0 && getHeight() > 0) {
            getPaint().setShader(new LinearGradient(0, 0, getWidth(), getHeight(),
                    new int[]{0xFF00E5A0, 0xFF00B4D8},
                    null, Shader.TileMode.CLAMP));
        }
        super.onDraw(canvas);
    }
}
