package work.huacai.yuelan.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import work.huacai.yuelan.R;

/**
 * 引导页加载进度环。
 * <p>
 * 由三部分组成：浅色轨道、渐变进度弧（圆头）、弧线外的一层呼吸光晕。
 * 配合中间的 Logo 呼吸，替代原来那种一直转圈的加载图。
 */
public class LoadingProgressRingView extends View {

    /** 圆环线宽（dp） */
    private static final float STROKE_WIDTH_DP = 6f;
    /** 光晕呼吸周期（毫秒） */
    private static final long GLOW_DURATION = 2600L;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect = new RectF();

    /** 当前展示的进度（0~100） */
    private float currentProgress;
    /** 光晕透明度系数（0~1），用于呼吸动画 */
    private float glowAlpha = 0.12f;

    private ValueAnimator glowAnimator;

    public LoadingProgressRingView(Context context) {
        this(context, null);
    }

    public LoadingProgressRingView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        initPaints();
    }

    private void initPaints() {
        float stroke = dp(STROKE_WIDTH_DP);

        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(stroke);
        trackPaint.setColor(ContextCompat.getColor(getContext(), R.color.pink_secondary));

        progressPaint.setStyle(Paint.Style.STROKE);
        progressPaint.setStrokeWidth(stroke);
        progressPaint.setStrokeCap(Paint.Cap.ROUND);

        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeWidth(stroke * 2f);
        glowPaint.setStrokeCap(Paint.Cap.ROUND);
        glowPaint.setColor(ContextCompat.getColor(getContext(), R.color.brand_primary));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);

        float cx = w / 2f;
        float cy = h / 2f;
        // 留出光晕的线宽，避免被裁掉
        float radius = Math.min(w, h) / 2f - dp(STROKE_WIDTH_DP) * 1.6f;
        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius);

        // 渐变从正上方开始顺时针铺开，用两种饱和色，避免弧线起点淡得像轨道
        SweepGradient gradient = new SweepGradient(cx, cy,
                new int[]{
                        ContextCompat.getColor(getContext(), R.color.brand_primary),
                        ContextCompat.getColor(getContext(), R.color.pink_accent)
                }, null);
        Matrix matrix = new Matrix();
        matrix.setRotate(-90f, cx, cy);
        gradient.setLocalMatrix(matrix);
        progressPaint.setShader(gradient);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        canvas.drawCircle(arcRect.centerX(), arcRect.centerY(),
                arcRect.width() / 2f, trackPaint);

        float sweep = currentProgress / 100f * 360f;
        if (sweep > 0f) {
            // 光晕跟随进度弧，透明度随时间呼吸
            glowPaint.setAlpha(Math.round(255 * glowAlpha));
            canvas.drawArc(arcRect, -90f, sweep, false, glowPaint);
            canvas.drawArc(arcRect, -90f, sweep, false, progressPaint);
        }
    }

    /**
     * 更新进度。调用方本身就是按小步长连续推进的，这里直接落值重绘，
     * 不再做二次动画——否则高频调用会不停打断动画，进度弧反而一直停在 0。
     *
     * @param progress 0~100 的进度值
     */
    public void setProgress(int progress) {
        currentProgress = Math.max(0f, Math.min(100f, progress));
        invalidate();
    }

    /** 启动光晕呼吸动画 */
    public void startGlowAnimation() {
        if (glowAnimator == null) {
            glowAnimator = ValueAnimator.ofFloat(0.08f, 0.22f);
            glowAnimator.setDuration(GLOW_DURATION);
            glowAnimator.setRepeatCount(ValueAnimator.INFINITE);
            glowAnimator.setRepeatMode(ValueAnimator.REVERSE);
            glowAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
            glowAnimator.addUpdateListener(animation -> {
                glowAlpha = (float) animation.getAnimatedValue();
                invalidate();
            });
        }
        if (!glowAnimator.isStarted()) {
            glowAnimator.start();
        }
    }

    /** 页面对不可见时暂停呼吸，节省绘制 */
    public void pauseGlowAnimation() {
        if (glowAnimator != null && glowAnimator.isStarted()) {
            glowAnimator.pause();
        }
    }

    /** 页面回到前台时恢复呼吸 */
    public void resumeGlowAnimation() {
        if (glowAnimator != null && glowAnimator.isPaused()) {
            glowAnimator.resume();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (glowAnimator != null) {
            glowAnimator.cancel();
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
