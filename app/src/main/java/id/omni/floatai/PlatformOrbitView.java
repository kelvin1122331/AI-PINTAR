package id.omni.floatai;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.animation.LinearInterpolator;

final class PlatformOrbitView extends View {
    static final String[] PLATFORMS = {"Wayground", "Kahoot!", "Google Forms", "Microsoft Forms",
        "Socrative", "Nearpod", "Blooket", "Wordwall"};
    private static final int[] COLORS = {0xff693cd0, 0xff5722ad, 0xff7549c6, 0xff006b70,
        0xff2458a8, 0xff0879c5, 0xff007575, 0xffb94b09};
    private static final String[] MARKS = {"W", "K!", "", "F", "S", "n", "B", ""};
    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private static final Typeface HEAVY = Typeface.create("sans-serif-black", Typeface.NORMAL);
    private static final double TAU = 2 * Math.PI;
    private static final int[] STARS = {-163,-72, -145,83, -125,-105, -76,128, -53,-129, 20,118, 45,-128,
        88,119, 122,-100, 151,85, 163,-51, 170,16, -174,7};
    private static final int[] WORDWALL_TILES = {0xffffefae, 0xffffd66a, 0xffffad73, 0xfffff9e9};
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path detail = new Path();
    private final Drawable logo;
    private final ValueAnimator orbit = ValueAnimator.ofFloat(0, 1);
    private float phase;

    PlatformOrbitView(Context context) {
        super(context);
        logo = context.getDrawable(R.drawable.omni).mutate();
        setContentDescription("Orbit platform kuis");
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        orbit.setDuration(26_000);
        orbit.setRepeatCount(ValueAnimator.INFINITE);
        orbit.setInterpolator(new LinearInterpolator());
        orbit.addUpdateListener(animator -> {
            phase = (float) animator.getAnimatedValue();
            invalidate();
        });
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = View.MeasureSpec.getSize(widthSpec);
        int height = Math.min(Chat.dp(getContext(), 318), Math.round(width * .86f));
        setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(height, heightSpec));
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); updateAnimation(); }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (orbit != null) updateAnimation();
    }
    @Override protected void onDetachedFromWindow() {
        orbit.cancel();
        super.onDetachedFromWindow();
    }

    private void updateAnimation() {
        if (isAttachedToWindow() && getWindowVisibility() == VISIBLE && ValueAnimator.areAnimatorsEnabled()) {
            if (!orbit.isStarted()) orbit.start();
        } else if (orbit.isStarted()) {
            orbit.cancel();
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float unit = Math.min(getWidth() / 380f, getHeight() / 315f);
        if (unit <= 0) return;
        canvas.save();
        canvas.translate(getWidth() / 2f, getHeight() / 2f - unit * 4);
        canvas.scale(unit, unit);

        paint.setShader(new RadialGradient(0, -10, 151,
            new int[]{0x80743bd1, 0x29224791, 0x00224791}, null, Shader.TileMode.CLAMP));
        canvas.drawCircle(0, -10, 151, paint);
        paint.setShader(null);

        for (int i = 0; i < STARS.length; i += 2) {
            paint.setColor(i % 6 == 0 ? 0xffffbd92 : 0xff826bb6);
            canvas.drawCircle(STARS[i], STARS[i+1], i % 6 == 0 ? 2.1f : 1.35f, paint);
        }

        ring(canvas, -12, 143, 96, 0x807d59bb);
        ring(canvas, 19, 105, 71, 0x664e63a9);
        orbitBadges(canvas, false);
        drawCore(canvas);
        orbitBadges(canvas, true);
        canvas.restore();
    }

    private void ring(Canvas canvas, int tilt, float width, float height, int color) {
        canvas.save();
        canvas.rotate(tilt);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1);
        paint.setColor(color);
        canvas.drawOval(-width, -height, width, height, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.restore();
    }

    private void orbitBadges(Canvas canvas, boolean front) {
        for (int i = 0; i < PLATFORMS.length; i++) {
            boolean outer = i < 4;
            int position = i % 4;
            double angle = TAU * (position / 4.0 + (outer ? phase + .07 : -phase + .187));
            float depth = (float) Math.sin(angle);
            if ((depth >= 0) != front) continue;
            float x = (float) Math.cos(angle) * (outer ? 143 : 105);
            float y = depth * (outer ? 96 : 71);
            double tilt = Math.toRadians(outer ? -12 : 19);
            float placeX = (float)(x * Math.cos(tilt) - y * Math.sin(tilt));
            float placeY = (float)(x * Math.sin(tilt) + y * Math.cos(tilt));
            canvas.save();
            canvas.translate(placeX, placeY);
            float size = .89f + .13f * depth;
            canvas.scale(size, size);
            drawBadge(canvas, i);
            canvas.restore();
        }
    }

    private void drawCore(Canvas canvas) {
        paint.setShader(new RadialGradient(0, 0, 99, 0x91ff915d, 0x00ff915d, Shader.TileMode.CLAMP));
        canvas.drawCircle(0, 0, 99, paint);
        paint.setShader(null);
        paint.setColor(0x553b1e64);
        canvas.drawCircle(0, 0, 71, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.2f);
        paint.setColor(0x91ffbb91);
        canvas.drawCircle(0, 0, 67, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(-45, -54, 51, 54, 0xff8c58de, 0xff281443, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(-52, -52, 52, 52, 29, 29, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.25f);
        paint.setColor(0xffcc99ff);
        canvas.drawRoundRect(-52, -52, 52, 52, 29, 29, paint);
        paint.setStyle(Paint.Style.FILL);
        logo.setBounds(-34, -34, 34, 34);
        logo.draw(canvas);
    }

    private void drawBadge(Canvas canvas, int index) {
        paint.setColor(0x7522083b);
        canvas.drawRoundRect(-25, -22, 25, 30, 15, 15, paint);
        paint.setShader(new LinearGradient(-23, -23, 23, 23, 0xfff8f1ff, 0xff4a2877, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(-25, -25, 25, 25, 15, 15, paint);
        paint.setShader(null);
        paint.setColor(COLORS[index]);
        canvas.drawRoundRect(-23, -23, 23, 23, 13, 13, paint);

        paint.setColor(Color.WHITE);
        paint.setTypeface(HEAVY);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(25);
        if (index == 2) {
            canvas.drawRoundRect(-11, -15, 11, 15, 3, 3, paint);
            paint.setColor(COLORS[index]);
            for (int line = -5; line <= 7; line += 6) {
                canvas.drawCircle(-6, line, 1.2f, paint);
                canvas.drawRoundRect(-2, line-1, 7, line+1, 1, 1, paint);
            }
            detail.reset();
            detail.moveTo(4, -15);
            detail.lineTo(11, -8);
            detail.lineTo(4, -8);
            detail.close();
            canvas.drawPath(detail, paint);
        } else if (index == 7) {
            for (int tile = 0; tile < WORDWALL_TILES.length; tile++) {
                float x = -13 + (tile % 2) * 14;
                float y = -13 + (tile / 2) * 14;
                paint.setColor(WORDWALL_TILES[tile]);
                canvas.drawRoundRect(x, y, x+12, y+12, 2.5f, 2.5f, paint);
            }
        } else {
            canvas.drawText(MARKS[index], 0, 8, paint);
        }

        String name = PLATFORMS[index];
        paint.setTextSize(10);
        paint.setTypeface(MEDIUM);
        float half = paint.measureText(name) / 2 + 6;
        paint.setColor(0xe822123c);
        canvas.drawRoundRect(-half, 28, half, 44, 8, 8, paint);
        paint.setColor(0xfff3eaff);
        canvas.drawText(name, 0, 39.4f, paint);
    }
}
