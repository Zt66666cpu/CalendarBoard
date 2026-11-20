package com.calendarboard.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;

final class KnitTheme {
    // Warm Cream Knit Palette
    static final int BG_CREAM = Color.parseColor("#F6F1E7");
    static final int SURFACE_OAT = Color.parseColor("#FFFDF9");
    static final int SURFACE_SAND = Color.parseColor("#EFE8DA");
    static final int SURFACE_SAND_DEEP = Color.parseColor("#E6DCCB");
    static final int BORDER_LINEN = Color.parseColor("#DFD5C3");
    static final int STITCH_WOOL = Color.parseColor("#D5C9B4");

    static final int INK_PRIMARY = Color.parseColor("#3A312A");
    static final int INK_SECONDARY = Color.parseColor("#7D7166");
    static final int INK_MUTED = Color.parseColor("#A79B8E");

    static final int ACCENT_COCOA = Color.parseColor("#5C4D42");
    static final int ACCENT_COCOA_SOFT = Color.parseColor("#EAE1D3");

    // Morandi Yarn Category Colors
    static final int YARN_COURSE = Color.parseColor("#6B8FA3");
    static final int YARN_COURSE_BG = Color.parseColor("#EDF3F6");

    static final int YARN_TODO = Color.parseColor("#C68B59");
    static final int YARN_TODO_BG = Color.parseColor("#FAF1E7");

    static final int YARN_SCHEDULE = Color.parseColor("#B67B7E");
    static final int YARN_SCHEDULE_BG = Color.parseColor("#F7ECEC");

    static final int DANGER_CLAY = Color.parseColor("#B85D57");
    static final int DANGER_CLAY_BG = Color.parseColor("#F8E9E7");

    private KnitTheme() { }

    static int dp(Context context, float value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics()));
    }

    static int colorForType(String type) {
        if ("课程".equals(type)) return YARN_COURSE;
        if ("日程".equals(type)) return YARN_SCHEDULE;
        return YARN_TODO;
    }

    static int tintForType(String type) {
        if ("课程".equals(type)) return YARN_COURSE_BG;
        if ("日程".equals(type)) return YARN_SCHEDULE_BG;
        return YARN_TODO_BG;
    }

    /** Subtle procedural knit/woven linen canvas background. */
    static Drawable knitCanvasBackground(Context context) {
        return new KnitPatternDrawable(context);
    }

    /** Large-radius warm card with optional delicate inner dashed stitch border. */
    static Drawable knitCard(Context context, int fillColor, int borderColor, float radiusDp, boolean stitched) {
        return new StitchedCardDrawable(context, fillColor, borderColor, radiusDp, stitched);
    }

    /** Smooth pill or rounded rectangle drawable. */
    static GradientDrawable roundedRect(Context context, int fillColor, int strokeColor, float strokeDp, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fillColor);
        d.setCornerRadius(dp(context, radiusDp));
        if (strokeDp > 0 && strokeColor != Color.TRANSPARENT) {
            d.setStroke(Math.max(1, dp(context, strokeDp)), strokeColor);
        }
        return d;
    }

    static GradientDrawable circle(Context context, int fillColor, int strokeColor, float strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(fillColor);
        if (strokeDp > 0 && strokeColor != Color.TRANSPARENT) {
            d.setStroke(Math.max(1, dp(context, strokeDp)), strokeColor);
        }
        return d;
    }

    private static final class KnitPatternDrawable extends Drawable {
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shaderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        KnitPatternDrawable(Context context) {
            bgPaint.setColor(BG_CREAM);
            int size = Math.max(16, dp(context, 14));
            Bitmap tile = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(tile);

            // Subtle stockinette V-knit micro-pattern
            Paint shadowYarn = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadowYarn.setColor(Color.argb(28, 155, 138, 114));
            shadowYarn.setStrokeWidth(Math.max(1f, dp(context, 0.9f)));
            shadowYarn.setStrokeCap(Paint.Cap.ROUND);

            Paint highlightYarn = new Paint(Paint.ANTI_ALIAS_FLAG);
            highlightYarn.setColor(Color.argb(46, 255, 253, 249));
            highlightYarn.setStrokeWidth(Math.max(1f, dp(context, 0.8f)));
            highlightYarn.setStrokeCap(Paint.Cap.ROUND);

            float half = size / 2f;
            float q = size / 4f;
            // Left and right yarn loops forming gentle knit V-stitches
            c.drawLine(q * 0.6f, q * 0.7f, half - 1f, half - 1f, shadowYarn);
            c.drawLine(size - q * 0.6f, q * 0.7f, half + 1f, half - 1f, highlightYarn);

            c.drawLine(q * 0.6f, half + q * 0.7f, half - 1f, size - 1f, highlightYarn);
            c.drawLine(size - q * 0.6f, half + q * 0.7f, half + 1f, size - 1f, shadowYarn);

            BitmapShader shader = new BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
            shaderPaint.setShader(shader);
        }

        @Override public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            canvas.drawRect(bounds, bgPaint);
            canvas.drawRect(bounds, shaderPaint);
        }

        @Override public void setAlpha(int alpha) { shaderPaint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter colorFilter) { shaderPaint.setColorFilter(colorFilter); }
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }

    private static final class StitchedCardDrawable extends Drawable {
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stitchPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF outerRect = new RectF();
        private final RectF innerRect = new RectF();
        private final float radius;
        private final float stitchInset;
        private final boolean stitched;

        StitchedCardDrawable(Context context, int fillColor, int borderColor, float radiusDp, boolean stitched) {
            this.radius = dp(context, radiusDp);
            this.stitchInset = dp(context, 4.5f);
            this.stitched = stitched;

            fillPaint.setStyle(Paint.Style.FILL);
            fillPaint.setColor(fillColor);

            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setColor(borderColor);
            borderPaint.setStrokeWidth(Math.max(1f, dp(context, 1)));

            stitchPaint.setStyle(Paint.Style.STROKE);
            stitchPaint.setColor(STITCH_WOOL);
            stitchPaint.setAlpha(135);
            stitchPaint.setStrokeWidth(Math.max(1f, dp(context, 0.9f)));
            stitchPaint.setPathEffect(new DashPathEffect(
                    new float[]{dp(context, 4), dp(context, 3.5f)}, 0));
        }

        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            float halfStroke = borderPaint.getStrokeWidth() / 2f;
            outerRect.set(b.left + halfStroke, b.top + halfStroke, b.right - halfStroke, b.bottom - halfStroke);
            canvas.drawRoundRect(outerRect, radius, radius, fillPaint);
            canvas.drawRoundRect(outerRect, radius, radius, borderPaint);
            if (stitched && b.width() > stitchInset * 4 && b.height() > stitchInset * 4) {
                innerRect.set(b.left + stitchInset, b.top + stitchInset, b.right - stitchInset, b.bottom - stitchInset);
                float innerRadius = Math.max(4f, radius - stitchInset);
                canvas.drawRoundRect(innerRect, innerRadius, innerRadius, stitchPaint);
            }
        }

        @Override public void setAlpha(int alpha) { fillPaint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter colorFilter) { fillPaint.setColorFilter(colorFilter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
