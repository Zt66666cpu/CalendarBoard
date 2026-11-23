package com.calendarboard.widget;

import android.content.Context;
import android.content.res.Configuration;
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

import androidx.core.content.ContextCompat;

import java.util.HashMap;
import java.util.Map;

final class KnitTheme {
    // Warm Cream Knit Palette. Values are refreshed from resources by load(Context).
    static int BG_CREAM = 0xFFF6F1E7;
    static int SURFACE_OAT = 0xFFFFFDF9;
    static int SURFACE_SAND = 0xFFEFE8DA;
    static int SURFACE_SAND_DEEP = 0xFFE6DCCB;
    static int BORDER_LINEN = 0xFFDFD5C3;
    static int STITCH_WOOL = 0xFFD5C9B4;

    static int INK_PRIMARY = 0xFF3A312A;
    static int INK_SECONDARY = 0xFF7D7166;
    static int INK_MUTED = 0xFFA79B8E;

    static int ACCENT_COCOA = 0xFF5C4D42;
    static int ACCENT_ON_COCOA = 0xFFFFFDF9;
    static int ACCENT_COCOA_SOFT = 0xFFEAE1D3;

    // Morandi Yarn Category Colors
    static int YARN_COURSE = 0xFF6B8FA3;
    static int YARN_COURSE_BG = 0xFFEDF3F6;

    static int YARN_TODO = 0xFFC68B59;
    static int YARN_TODO_BG = 0xFFFAF1E7;

    static int YARN_SCHEDULE = 0xFFB67B7E;
    static int YARN_SCHEDULE_BG = 0xFFF7ECEC;

    static int DANGER_CLAY = 0xFFB85D57;
    static int DANGER_CLAY_BG = 0xFFF8E9E7;
    static int DANGER_BORDER = 0xFFE3C2BE;

    private static final Map<String, BitmapShader> SHADER_CACHE = new HashMap<>();

    private KnitTheme() { }

    /** Loads the active light or night palette from color resources. */
    static void load(Context context) {
        if (context == null) return;
        BG_CREAM = ContextCompat.getColor(context, R.color.bg_cream);
        SURFACE_OAT = ContextCompat.getColor(context, R.color.surface_oat);
        SURFACE_SAND = ContextCompat.getColor(context, R.color.surface_sand);
        SURFACE_SAND_DEEP = ContextCompat.getColor(context, R.color.surface_sand_deep);
        BORDER_LINEN = ContextCompat.getColor(context, R.color.border_linen);
        STITCH_WOOL = ContextCompat.getColor(context, R.color.stitch_wool);

        INK_PRIMARY = ContextCompat.getColor(context, R.color.ink_primary);
        INK_SECONDARY = ContextCompat.getColor(context, R.color.ink_secondary);
        INK_MUTED = ContextCompat.getColor(context, R.color.ink_muted);

        ACCENT_COCOA = ContextCompat.getColor(context, R.color.accent_cocoa);
        ACCENT_ON_COCOA = ContextCompat.getColor(context, R.color.accent_on_cocoa);
        ACCENT_COCOA_SOFT = ContextCompat.getColor(context, R.color.accent_cocoa_soft);

        YARN_COURSE = ContextCompat.getColor(context, R.color.yarn_course);
        YARN_COURSE_BG = ContextCompat.getColor(context, R.color.yarn_course_bg);
        YARN_TODO = ContextCompat.getColor(context, R.color.yarn_todo);
        YARN_TODO_BG = ContextCompat.getColor(context, R.color.yarn_todo_bg);
        YARN_SCHEDULE = ContextCompat.getColor(context, R.color.yarn_schedule);
        YARN_SCHEDULE_BG = ContextCompat.getColor(context, R.color.yarn_schedule_bg);

        DANGER_CLAY = ContextCompat.getColor(context, R.color.danger_clay);
        DANGER_CLAY_BG = ContextCompat.getColor(context, R.color.danger_clay_bg);
        DANGER_BORDER = ContextCompat.getColor(context, R.color.danger_border);
    }

    static boolean isDark(Context context) {
        if (context == null) return false;
        int mode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

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

    private static BitmapShader shaderFor(Context context, boolean dark) {
        String key = context.getResources().getDisplayMetrics().densityDpi + "_" + dark;
        synchronized (SHADER_CACHE) {
            BitmapShader cached = SHADER_CACHE.get(key);
            if (cached != null) return cached;

            int size = Math.max(16, dp(context, 14));
            Bitmap tile = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(tile);

            // Subtle stockinette V-knit micro-pattern. Night mode dims the yarn strokes.
            Paint shadowYarn = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadowYarn.setColor(Color.argb(dark ? 18 : 28, 155, 138, 114));
            shadowYarn.setStrokeWidth(Math.max(1f, dp(context, 0.9f)));
            shadowYarn.setStrokeCap(Paint.Cap.ROUND);

            Paint highlightYarn = new Paint(Paint.ANTI_ALIAS_FLAG);
            highlightYarn.setColor(Color.argb(dark ? 28 : 46, 255, 253, 249));
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
            SHADER_CACHE.put(key, shader);
            return shader;
        }
    }

    private static final class KnitPatternDrawable extends Drawable {
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shaderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        KnitPatternDrawable(Context context) {
            load(context);
            bgPaint.setColor(BG_CREAM);
            shaderPaint.setShader(shaderFor(context, isDark(context)));
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
