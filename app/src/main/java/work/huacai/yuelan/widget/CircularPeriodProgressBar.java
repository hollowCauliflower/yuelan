package work.huacai.yuelan.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

import androidx.annotation.NonNull;

import java.util.Locale;

import work.huacai.yuelan.R;
import work.huacai.yuelan.model.MenstrualCycle;

/**
 * 环形周期进度条控件。
 *
 * <p>把一整圈 360° 按四个生理阶段的天数比例划分成四段彩色圆弧，圆环起点在正上方
 * （-90°）并带一个 "S" 起始标记；圆弧外侧有指向当前进度的三角形指针，圆心显示
 * 已完成的百分比和当前阶段名称。支持阴影、入场动画和深浅色配色。</p>
 *
 * <p>典型使用顺序：
 * <ol>
 *     <li>{@link #setPhaseDays(int[])} 传入四个阶段的天数；</li>
 *     <li>{@link #setCurrentDay(int)} 传入"当前是周期的第几天"，控件内部换算阶段与进度；</li>
 *     <li>{@link #setDarkMode(boolean)} 切换深浅色；{@link #setAnimationEnabled(boolean)} 开关进度动画。</li>
 * </ol>
 * 控件首次挂载 500ms 后会播放一次入场动画（前一半时间展开圆弧，后一半时间转动指针），
 * 该动画只播放一次，之后仅响应 {@link #setCurrentDay(int)} 触发的进度动画。</p>
 */
public class CircularPeriodProgressBar extends View {

    // ========== 默认尺寸（dp） ==========
    /** 默认圆环描边宽度 */
    private static final int DEFAULT_STROKE_WIDTH = 14;
    /** 中心百分比文字的默认字号（单位为 dp，通过 dip2px 换算成像素） */
    private static final int DEFAULT_CENTER_TEXT_SIZE = 32;

    // ========== 布局尺寸（dp） ==========
    /** 圆环半径到控件边界的外侧留白 */
    private static final float RADIUS_OUTER_MARGIN_DP = 20f;
    /** 在留白基础上再向内收缩的距离，避免描边和阴影被控件边界裁切 */
    private static final float RADIUS_INNER_INSET_DP = 10f;
    /** 起始标记与刻度指针相对圆环半径向外延伸的距离 */
    private static final float MARKER_OFFSET_DP = 8f;
    /** 起始标记（"S" 圆点）的半径 */
    private static final float START_MARKER_RADIUS_DP = 6f;
    /** 起始标记内 "S" 字母的字号 */
    private static final float START_TEXT_SIZE_DP = 8f;
    /** "S" 字母的向下微调量，使其在圆点内视觉居中 */
    private static final float START_TEXT_OFFSET_DP = 2.5f;
    /** 进度指针三角形的高度（宽度为其一半） */
    private static final float POINTER_SIZE_DP = 12f;
    /** 当前阶段高亮弧线比主弧线细的差值 */
    private static final float HIGHLIGHT_STROKE_INSET_DP = 4f;
    /** 中心百分比文字相对圆心的上移量 */
    private static final float CENTER_TEXT_OFFSET_DP = 4f;
    /** 阶段名称文字相对圆心的下移量 */
    private static final float PHASE_NAME_OFFSET_DP = 20f;
    /** 阶段名称文字的字号 */
    private static final float PHASE_NAME_TEXT_SIZE_DP = 12f;

    // ========== 颜色 ==========
    /** 浅色模式下的圆环底色 */
    private static final int BACKGROUND_COLOR_LIGHT = 0xFFF0F0F0;
    /** 深色模式下的圆环底色 */
    private static final int BACKGROUND_COLOR_DARK = 0xFF444444;
    /** 圆环阴影叠色（极低透明度的黑色，直接填充整个圆面） */
    private static final int SHADOW_COLOR = 0x10000000;

    // ========== 周期数据 ==========
    /** 四个阶段的天数，顺序与 {@link MenstrualCycle} 中的阶段顺序一致 */
    private int[] phaseDays = {5, 8, 5, 10};
    /** 各阶段占整圈的百分比（0~100），由 {@link #updatePhasePercentages()} 计算 */
    private final float[] phasePercentages = new float[4];
    /** 动画过程中实际绘制用的阶段百分比，入场动画时从 0 增长到目标值 */
    private final float[] animatedPercentages = new float[4];
    /** 当前处于哪个阶段（0~3） */
    private int currentPhase = 0;
    /** 当前进度（0~1 的归一化值，1 表示整圈走完） */
    private float currentProgress = 0f;
    /** 动画过程中的显示进度，绘制指针与中心百分比使用它 */
    private float animatedProgress = 0f;

    // ========== 绘制对象（统一在 initPaintObjects 中初始化） ==========
    /** 圆环底色画笔（空心描边） */
    private Paint backgroundPaint;
    /** 各阶段彩色圆弧画笔（空心描边） */
    private Paint phasePaint;
    /** 当前阶段的高亮叠加弧线画笔（空心描边、低透明度） */
    private Paint highlightPaint;
    /** 圆心百分比文字画笔 */
    private Paint centerTextPaint;
    /** 圆周进度指针画笔（实心） */
    private Paint pointerPaint;
    /** 起始标记圆点画笔（实心） */
    private Paint startPointPaint;
    /** 起始标记 "S" 文字画笔 */
    private Paint startTextPaint;
    /** 阴影叠色画笔（实心） */
    private Paint shadowPaint;
    /** 圆心下方阶段名称文字画笔 */
    private Paint phaseNamePaint;
    /** 圆弧绘制所用的圆环外接矩形（复用以避免重复分配对象） */
    private RectF oval;
    /** 指针三角形的路径（复用以避免重复分配对象） */
    private Path pointerPath;

    // ========== 控件尺寸（像素，在 onSizeChanged 中计算） ==========
    /** 圆心 X 坐标 */
    private int centerX;
    /** 圆心 Y 坐标 */
    private int centerY;
    /** 圆环半径 */
    private int radius;
    /** 圆环描边宽度 */
    private int strokeWidth;
    /** 中心百分比文字字号 */
    private int centerTextSize;
    /** 控件内边距（初始化时从 View 属性中取出） */
    private float paddingLeft, paddingTop, paddingRight, paddingBottom;

    // ========== 动画相关 ==========
    /** 进度变化动画 */
    private ValueAnimator progressAnimator;
    /** 入场动画（只播放一次） */
    private ValueAnimator entranceAnimator;
    /** 是否正在播放动画中（为 true 时不绘制当前阶段高亮） */
    private boolean isAnimating = false;
    /** 入场动画是否已经播放过，保证只播放一次 */
    private boolean hasPlayedEntranceAnimation = false;
    /** 入场动画后半段是否允许绘制指针 */
    private boolean showPointerDuringAnimation = false;
    /** 圆心显示的百分比对应的小数进度（0~1） */
    private float centerPercentage = 0f;

    // ========== 字符串构建器（复用，避免每次绘制都新建字符串） ==========
    /** 圆心百分比文字的构建器，容量按 "100%" 预留 */
    private final StringBuilder progressTextBuilder = new StringBuilder(8);
    /** 圆心阶段名称文字的构建器 */
    private final StringBuilder currentPhaseTextBuilder = new StringBuilder(16);

    // ========== 角度与动画常量 ==========
    /** 相邻阶段圆弧之间为消除缝隙而做的角度微调量（度） */
    private static final float ANGLE_ADJUSTMENT = 0.1f;
    /** 圆环起始角度，-90° 即十二点方向 */
    private static final float START_ANGLE = -90f;
    /** 控件最小边长（dp） */
    private static final int MIN_SIZE_DP = 180;
    /** 入场动画总时长（毫秒） */
    private static final int ENTRANCE_ANIMATION_DURATION = 5000;
    /** 进度变化动画时长（毫秒） */
    private static final int PROGRESS_ANIMATION_DURATION = 4000;

    // ========== 动画监听器 ==========
    /** 入场动画进度回调：前半段展开圆弧，后半段推进指针 */
    private final ValueAnimator.AnimatorUpdateListener entranceUpdateListener =
            animation -> {
                isAnimating = true;
                float fraction = animation.getAnimatedFraction();

                // 前一半时间：四段圆弧按比例从 0 逐渐铺满整圈
                if (fraction < 0.5f) {
                    float phaseFraction = fraction / 0.5f;
                    for (int i = 0; i < animatedPercentages.length; i++) {
                        animatedPercentages[i] = phasePercentages[i] * phaseFraction;
                    }
                    centerPercentage = 0f;
                } else {
                    // 后一半时间：圆弧保持铺满，指针从起点转动到当前进度
                    System.arraycopy(phasePercentages, 0, animatedPercentages, 0, animatedPercentages.length);
                    float pointerFraction = (fraction - 0.5f) / 0.5f;
                    animatedProgress = currentProgress * pointerFraction;
                    centerPercentage = currentProgress * pointerFraction;

                    // 指针离开起点一小段距离后才显示，避免起点处视觉重叠
                    if (pointerFraction > 0.1f) {
                        showPointerDuringAnimation = true;
                    }
                }

                invalidate();

                // 动画结束时归位到目标值，允许后续正常绘制指针
                if (fraction == 1) {
                    isAnimating = false;
                    showPointerDuringAnimation = true;
                    centerPercentage = currentProgress;
                }
            };

    /** 进度变化动画回调：把插值结果同步到指针与圆心百分比 */
    private final ValueAnimator.AnimatorUpdateListener progressUpdateListener =
            animation -> {
                isAnimating = true;
                float progressValue = (float) animation.getAnimatedValue();
                animatedProgress = progressValue;
                centerPercentage = progressValue;
                invalidate();

                if (animation.getAnimatedFraction() == 1) {
                    isAnimating = false;
                }
            };

    // ========== 自定义属性 ==========
    /** 是否启用进度动画（对应 XML 属性 enableAnimation） */
    private boolean enableAnimation = true;
    /** 是否绘制阴影（对应 XML 属性 showShadow） */
    private boolean showShadow = true;
    /** 当前是否为深色模式，影响底色、文字与高亮颜色 */
    private boolean isDarkMode = false;
    /** 保存 Context，用于取阶段名称、颜色等资源 */
    private final Context context;

    /**
     * 代码中创建控件时使用，全部样式取默认值。
     *
     * @param context 上下文
     */
    public CircularPeriodProgressBar(Context context) {
        super(context);
        this.context = context;
        init(context, null);
    }

    /**
     * XML 布局中使用，解析 {@code CircularPeriodProgressBar} 自定义属性。
     *
     * @param context 上下文
     * @param attrs   XML 属性集合，可为 null
     */
    public CircularPeriodProgressBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.context = context;
        init(context, attrs);
    }

    /**
     * 统一的初始化入口：读取内边距、创建画笔与路径、解析属性、计算阶段占比，
     * 并安排一次延迟入场动画。
     *
     * @param context 上下文
     * @param attrs   XML 属性集合；为 null 时使用默认尺寸与默认底色
     */
    private void init(Context context, AttributeSet attrs) {
        // 缓存内边距，onSizeChanged 中计算圆心与半径时使用
        paddingLeft = getPaddingLeft();
        paddingTop = getPaddingTop();
        paddingRight = getPaddingRight();
        paddingBottom = getPaddingBottom();

        initPaintObjects();

        pointerPath = new Path();

        // 有 XML 属性时按属性解析；否则使用默认描边宽度、字号与底色
        if (attrs != null) {
            parseAttributes(context, attrs);
        } else {
            strokeWidth = dip2px(DEFAULT_STROKE_WIDTH);
            centerTextSize = dip2px(DEFAULT_CENTER_TEXT_SIZE);
            updateBackgroundColor();
        }

        // 根据阶段天数换算各阶段占整圈的百分比
        updatePhasePercentages();

        // 延迟 500ms 播放一次入场动画，等待控件完成首次布局，避免动画从错误的尺寸开始
        postDelayed(() -> {
            if (enableAnimation && !hasPlayedEntranceAnimation) {
                startEntranceAnimation();
                hasPlayedEntranceAnimation = true;
            }
        }, 500);
    }

    /**
     * 创建并配置全部画笔。所有画笔只在初始化时创建一次，
     * 绘制过程中仅修改颜色、字号等属性，避免在 onDraw 中反复分配对象。
     */
    private void initPaintObjects() {
        backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        backgroundPaint.setStyle(Paint.Style.STROKE);
        backgroundPaint.setStrokeCap(Paint.Cap.BUTT);

        phasePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        phasePaint.setStyle(Paint.Style.STROKE);
        phasePaint.setStrokeCap(Paint.Cap.BUTT);

        highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        highlightPaint.setStyle(Paint.Style.STROKE);
        highlightPaint.setStrokeCap(Paint.Cap.BUTT);
        highlightPaint.setColor(Color.WHITE);

        centerTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        centerTextPaint.setTextAlign(Paint.Align.CENTER);
        centerTextPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

        pointerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pointerPaint.setStyle(Paint.Style.FILL);
        pointerPaint.setAntiAlias(true);

        startPointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        startPointPaint.setStyle(Paint.Style.FILL);
        startPointPaint.setAntiAlias(true);

        startTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        startTextPaint.setColor(Color.WHITE);
        startTextPaint.setTextAlign(Paint.Align.CENTER);
        startTextPaint.setFakeBoldText(true);

        shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadowPaint.setStyle(Paint.Style.FILL);

        phaseNamePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        phaseNamePaint.setTextAlign(Paint.Align.CENTER);
        phaseNamePaint.setFakeBoldText(true);

        oval = new RectF();
    }

    /**
     * 解析 XML 自定义属性：动画开关、阴影开关、描边宽度、中心字号、深浅色与底色。
     *
     * @param context 上下文
     * @param attrs   XML 属性集合
     */
    private void parseAttributes(Context context, AttributeSet attrs) {
        TypedArray a = context.obtainStyledAttributes(
                attrs,
                R.styleable.CircularPeriodProgressBar
        );

        try {
            enableAnimation = a.getBoolean(
                    R.styleable.CircularPeriodProgressBar_enableAnimation,
                    true
            );

            showShadow = a.getBoolean(
                    R.styleable.CircularPeriodProgressBar_showShadow,
                    true
            );

            strokeWidth = a.getDimensionPixelSize(
                    R.styleable.CircularPeriodProgressBar_strokeWidth,
                    dip2px(DEFAULT_STROKE_WIDTH)
            );

            centerTextSize = a.getDimensionPixelSize(
                    R.styleable.CircularPeriodProgressBar_centerTextSize,
                    dip2px(DEFAULT_CENTER_TEXT_SIZE)
            );

            // 未显式指定 darkMode 时，跟随系统深浅色
            isDarkMode = a.getBoolean(
                    R.styleable.CircularPeriodProgressBar_darkMode,
                    (context.getResources().getConfiguration().uiMode
                            & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            );

            int bgColor = a.getColor(
                    R.styleable.CircularPeriodProgressBar_backgroundColor,
                    isDarkMode ? BACKGROUND_COLOR_DARK : BACKGROUND_COLOR_LIGHT
            );

            backgroundPaint.setColor(bgColor);

        } finally {
            a.recycle();
        }
    }

    /** 取四个阶段在当前的深浅色模式下对应的颜色数组 */
    private int[] getPhaseColors() {
        return MenstrualCycle.getColors(context, isDarkMode);
    }

    /** 取当前阶段的显示名称（已本地化） */
    private String getCurrentPhaseName() {
        MenstrualCycle cycle = MenstrualCycle.getByIndex(currentPhase);
        return cycle.getDisplayName(context);
    }

    /** 取当前阶段在当前的深浅色模式下对应的颜色（用于圆心阶段名称文字） */
    private int getCurrentPhaseColor() {
        MenstrualCycle cycle = MenstrualCycle.getByIndex(currentPhase);
        return cycle.getColor(context, isDarkMode);
    }

    /** 取当前阶段在深色模式下对应的颜色（用于指针，保证在浅色圆弧上足够醒目） */
    private int getDarkColorForCurrentPhase() {
        MenstrualCycle cycle = MenstrualCycle.getByIndex(currentPhase);
        return cycle.getColor(context, true);
    }

    /**
     * 尺寸变化时重新计算圆心、半径、描边宽度与外接矩形。绘制相关的位置全部在这里预计算，
     * onDraw 中直接使用，避免每次绘制重复计算。
     *
     * @param width     新的控件宽度（像素）
     * @param height    新的控件高度（像素）
     * @param oldWidth  旧的控件宽度（像素）
     * @param oldHeight 旧的控件高度（像素）
     */
    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);

        // 圆心取"去掉内边距后的可用区域"的中心，再加上内边距偏移
        centerX = (int) ((width - paddingLeft - paddingRight) / 2 + paddingLeft);
        centerY = (int) ((height - paddingTop - paddingBottom) / 2 + paddingTop);

        int minDimension = (int) Math.min(
                width - paddingLeft - paddingRight,
                height - paddingTop - paddingBottom
        );

        // 半径 = 可用区域的一半，减去外侧留白与内缩量，保证描边和指针不被裁切
        int availableRadius = minDimension / 2 - dip2px(RADIUS_OUTER_MARGIN_DP);
        radius = availableRadius - dip2px(RADIUS_INNER_INSET_DP);

        backgroundPaint.setStrokeWidth(strokeWidth);
        phasePaint.setStrokeWidth(strokeWidth);
        highlightPaint.setStrokeWidth(strokeWidth - dip2px(HIGHLIGHT_STROKE_INSET_DP));

        centerTextPaint.setTextSize(centerTextSize);

        // 圆弧沿描边中线绘制，因此外接矩形要向内收缩半个描边宽度
        int ovalPadding = strokeWidth / 2;
        oval.set(centerX - radius + ovalPadding,
                centerY - radius + ovalPadding,
                centerX + radius - ovalPadding,
                centerY + radius - ovalPadding);
    }

    /**
     * 测量阶段：控件至少保证 {@link #MIN_SIZE_DP} 的宽高，保证圆环与文字不被挤压。
     *
     * @param widthMeasureSpec  宽度测量规格
     * @param heightMeasureSpec 高度测量规格
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        int minSize = dip2px(MIN_SIZE_DP);
        int width = Math.max(getMeasuredWidth(), minSize);
        int height = Math.max(getMeasuredHeight(), minSize);

        setMeasuredDimension(width, height);
    }

    /**
     * 绘制入口，绘制顺序（自底向上）：
     * 阴影叠色 → 圆环底色 → 四段阶段圆弧（含当前阶段高亮）→ 起始标记 → 进度指针 → 圆心文字。
     *
     * @param canvas 目标画布
     */
    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);

        if (showShadow) {
            drawShadow(canvas);
        }

        // 底环：一个完整的空心圆，各阶段圆弧覆盖在其上方
        canvas.drawCircle(centerX, centerY, radius, backgroundPaint);

        drawPhaseArcs(canvas);

        drawStartMarker(canvas);

        // 动画中只有后半段才显示指针，避免入场时指针乱跳
        if (!isAnimating || showPointerDuringAnimation) {
            float animatedAngle = START_ANGLE + 360 * animatedProgress;
            drawPointer(canvas, animatedAngle);
        }

        drawCenterText(canvas);
    }

    /**
     * 绘制阴影：用极低透明度的黑色填充整个圆面，形成圆环内侧的柔和过渡效果。
     *
     * @param canvas 目标画布
     */
    private void drawShadow(Canvas canvas) {
        shadowPaint.setColor(SHADOW_COLOR);
        canvas.drawCircle(centerX, centerY, radius, shadowPaint);
    }

    /**
     * 绘制四段阶段圆弧。每段圆弧的扫描角由 {@link #animatedPercentages} 决定，
     * 相邻圆弧首尾各让出/补上 {@link #ANGLE_ADJUSTMENT} 度，消除抗锯齿造成的白色缝隙。
     *
     * @param canvas 目标画布
     */
    private void drawPhaseArcs(Canvas canvas) {
        float startAngle = START_ANGLE;
        int[] colors = getPhaseColors();

        for (int i = 0; i < animatedPercentages.length; i++) {
            float sweepAngle = 360 * animatedPercentages[i] / 100;

            // 第 2 段开始向前多让出一点角度；最后一段不补，保证整圈在起点处闭合
            float adjustedStartAngle = startAngle;
            if (i > 0) {
                adjustedStartAngle -= ANGLE_ADJUSTMENT;
            }

            float adjustedSweepAngle = sweepAngle;
            if (i < animatedPercentages.length - 1) {
                adjustedSweepAngle += ANGLE_ADJUSTMENT;
            }

            phasePaint.setColor(colors[i]);
            canvas.drawArc(oval, adjustedStartAngle, adjustedSweepAngle, false, phasePaint);

            // 动画结束（静止）状态下，为当前阶段叠加一层高亮，便于快速定位
            if (i == currentPhase && !isAnimating) {
                highlightPaint.setColor(isDarkMode ? 0x40FFFFFF : Color.WHITE);
                highlightPaint.setAlpha(60);
                canvas.drawArc(oval, adjustedStartAngle, adjustedSweepAngle, false, highlightPaint);
            }

            startAngle += sweepAngle;
        }
    }

    /**
     * 绘制起点标记：在正上方（{@link #START_ANGLE}）画一个阶段色圆点，并在其中写 "S"。
     *
     * @param canvas 目标画布
     */
    private void drawStartMarker(Canvas canvas) {
        float markerRadius = radius + dip2px(MARKER_OFFSET_DP);
        float markerX = centerX + (float) (markerRadius * Math.cos(Math.toRadians(START_ANGLE)));
        float markerY = centerY + (float) (markerRadius * Math.sin(Math.toRadians(START_ANGLE)));

        int[] colors = getPhaseColors();
        startPointPaint.setColor(colors[0]);
        canvas.drawCircle(markerX, markerY, dip2px(START_MARKER_RADIUS_DP), startPointPaint);

        startTextPaint.setTextSize(dip2px(START_TEXT_SIZE_DP));
        canvas.drawText("S", markerX, markerY + dip2px(START_TEXT_OFFSET_DP), startTextPaint);
    }

    /**
     * 绘制进度指针：以圆周计算点作为锚点，旋转画布后绘制一个三角形加圆点的箭头。
     *
     * @param canvas 目标画布
     * @param angle  指针在圆周上的角度（度，0° 为三点方向，-90° 为正上方）
     */
    private void drawPointer(Canvas canvas, float angle) {
        float pointerRadius = radius + dip2px(MARKER_OFFSET_DP);
        float pointerX = centerX + (float) (pointerRadius * Math.cos(Math.toRadians(angle)));
        float pointerY = centerY + (float) (pointerRadius * Math.sin(Math.toRadians(angle)));

        canvas.save();
        canvas.translate(pointerX, pointerY);

        // 让箭头尖端始终指向圆心：旋转角度 = 角度 + 90° - 180°（箭头模型默认朝上）
        float pointerAngle = angle + 90 - 180;
        canvas.rotate(pointerAngle);

        int size = dip2px(POINTER_SIZE_DP);
        pointerPath.reset();
        pointerPath.moveTo(0, -size);
        pointerPath.lineTo((float) size / 2, 0);
        pointerPath.lineTo((float) -size / 2, 0);
        pointerPath.close();
        // 在三角形底部加一个小圆，使指针根部更圆润
        pointerPath.addCircle(0, 0, (float) size / 6, Path.Direction.CW);

        pointerPaint.setColor(getDarkColorForCurrentPhase());
        canvas.drawPath(pointerPath, pointerPaint);

        canvas.restore();
    }

    /**
     * 绘制圆心文字：上方为完成百分比（大字号），下方为当前阶段名称（小字号）。
     *
     * @param canvas 目标画布
     */
    private void drawCenterText(Canvas canvas) {
        centerTextPaint.setColor(isDarkMode ? Color.WHITE : Color.BLACK);
        centerTextPaint.setTextSize(centerTextSize);

        // 复用 StringBuilder 拼百分比文本，避免每秒多次绘制时产生临时字符串
        progressTextBuilder.setLength(0);
        progressTextBuilder.append(String.format(Locale.getDefault(), "%.0f%%", centerPercentage * 100));
        String progressText = progressTextBuilder.toString();

        // 文字过宽（接近圆环直径）时缩小字号，防止溢出圆环
        float progressWidth = centerTextPaint.measureText(progressText);
        if (progressWidth > radius * 1.5) {
            centerTextPaint.setTextSize((float) radius / 2);
        }

        canvas.drawText(progressText, centerX, centerY - dip2px(CENTER_TEXT_OFFSET_DP), centerTextPaint);

        phaseNamePaint.setTextSize(dip2px(PHASE_NAME_TEXT_SIZE_DP));
        phaseNamePaint.setColor(getCurrentPhaseColor());

        currentPhaseTextBuilder.setLength(0);
        currentPhaseTextBuilder.append(context.getString(R.string.circular_period_progressbar_current_label)).append(getCurrentPhaseName());

        canvas.drawText(currentPhaseTextBuilder.toString(),
                centerX, centerY + dip2px(PHASE_NAME_OFFSET_DP), phaseNamePaint);
    }

    /** 按当前深浅色模式刷新圆环底色 */
    private void updateBackgroundColor() {
        backgroundPaint.setColor(isDarkMode ? BACKGROUND_COLOR_DARK : BACKGROUND_COLOR_LIGHT);
    }

    /**
     * 播放入场动画：先把所有阶段占比与进度归零（留 0.01 保证圆弧可见），
     * 再由 0~1 的动画同时驱动"圆弧展开"和"指针转动"。
     */
    private void startEntranceAnimation() {
        showPointerDuringAnimation = false;
        centerPercentage = 0f;

        // 留 0.01 的兜底值，避免扫描角为 0 时圆弧完全不可见
        java.util.Arrays.fill(animatedPercentages, 0.01f);
        animatedProgress = 0.01f;

        // 重复播放前先取消上一次动画，避免回调叠加
        if (entranceAnimator != null && entranceAnimator.isRunning()) {
            entranceAnimator.cancel();
        }

        entranceAnimator = ValueAnimator.ofFloat(0, 1);
        entranceAnimator.setDuration(ENTRANCE_ANIMATION_DURATION);
        entranceAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        entranceAnimator.addUpdateListener(entranceUpdateListener);
        entranceAnimator.start();
    }

    /**
     * 设置四个阶段的天数，并重置到周期第 1 天。
     * 数组长度不是 4 时忽略本次调用。
     *
     * @param days 四个阶段的天数，单位：天；顺序需与 {@link MenstrualCycle} 的阶段顺序一致
     */
    public void setPhaseDays(int[] days) {
        if (days.length == 4) {
            this.phaseDays = days;
            updatePhasePercentages();
            setCurrentDay(1);
        }
    }

    /**
     * 设置"当前是周期的第几天"，内部据此换算出所处阶段和归一化进度，并刷新显示。
     * 小于 1 的值会被忽略；开启动画时会从当前显示进度平滑过渡到新进度。
     *
     * @param currentDay 周期内的第几天，从 1 开始计数
     */
    public void setCurrentDay(int currentDay) {
        if (currentDay < 1) {
            return;
        }

        // 累加各阶段天数，定位当前天落在哪个阶段
        int accumulatedDays = 0;
        for (int i = 0; i < 4; i++) {
            accumulatedDays += phaseDays[i];
            if (currentDay <= accumulatedDays) {
                currentPhase = i;
                break;
            }
        }

        // 当前阶段之前所有阶段的总天数，用于得到"本阶段内的第几天"
        int phaseStartDay = 0;
        for (int i = 0; i < currentPhase; i++) {
            phaseStartDay += phaseDays[i];
        }
        int dayInPhase = currentDay - phaseStartDay;
        // 阶段内进度：按"已过天数 / 本阶段天数"计，周期最后一天正好画满 100%
        int phaseDaysOfCurrent = phaseDays[currentPhase];
        float phaseProgress = phaseDaysOfCurrent > 0
                ? (float) dayInPhase / phaseDaysOfCurrent
                : 0f;

        // 归一化总进度 = 之前各阶段占比之和 + 本阶段内进度
        float totalProgress = 0;
        for (int i = 0; i < currentPhase; i++) {
            totalProgress += phasePercentages[i] / 100;
        }
        totalProgress += (phasePercentages[currentPhase] / 100) * phaseProgress;

        this.currentProgress = Math.min(Math.max(totalProgress, 0), 1);

        if (enableAnimation) {
            startProgressAnimation(animatedProgress, currentProgress);
        } else {
            this.animatedProgress = currentProgress;
            this.centerPercentage = currentProgress;
            invalidate();
        }
    }

    /** 根据阶段天数重新计算每个阶段占整圈的百分比（总和为 100） */
    private void updatePhasePercentages() {
        int totalDays = 0;
        for (int day : phaseDays) {
            totalDays += day;
        }

        for (int i = 0; i < 4; i++) {
            phasePercentages[i] = (float) phaseDays[i] / totalDays * 100;
            animatedPercentages[i] = phasePercentages[i];
        }

        centerPercentage = currentProgress;
    }

    /**
     * 播放进度变化动画。
     *
     * @param startValue 起始进度（0~1）
     * @param endValue   目标进度（0~1）
     */
    private void startProgressAnimation(float startValue, float endValue) {
        if (progressAnimator != null && progressAnimator.isRunning()) {
            progressAnimator.cancel();
        }

        progressAnimator = ValueAnimator.ofFloat(startValue, endValue);
        progressAnimator.setDuration(PROGRESS_ANIMATION_DURATION);
        progressAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        progressAnimator.addUpdateListener(progressUpdateListener);
        progressAnimator.start();
    }

    /**
     * 切换深浅色模式：刷新底色与文字配色后重绘。
     *
     * @param darkMode true 表示深色模式
     */
    public void setDarkMode(boolean darkMode) {
        this.isDarkMode = darkMode;
        updateBackgroundColor();
        invalidate();
    }

    /**
     * 开关进度动画。关闭后 {@link #setCurrentDay(int)} 会直接跳到目标进度，不再做动画过渡。
     *
     * @param enabled true 表示允许播放进度动画
     */
    public void setAnimationEnabled(boolean enabled) {
        this.enableAnimation = enabled;
    }

    /** 从窗口移除时取消所有正在运行的动画，避免内存泄漏与无效回调 */
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (progressAnimator != null && progressAnimator.isRunning()) {
            progressAnimator.cancel();
        }
        if (entranceAnimator != null && entranceAnimator.isRunning()) {
            entranceAnimator.cancel();
        }
    }

    /**
     * dp 转像素。
     *
     * @param dpValue dp 值
     * @return 对应的像素值（四舍五入）
     */
    private int dip2px(float dpValue) {
        final float scale = getContext().getResources().getDisplayMetrics().density;
        return (int) (dpValue * scale + 0.5f);
    }
}