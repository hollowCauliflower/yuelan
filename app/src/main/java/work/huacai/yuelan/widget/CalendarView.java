package work.huacai.yuelan.widget;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import work.huacai.yuelan.R;
import work.huacai.yuelan.helper.CycleCalculator;

/**
 * 月经周期日历控件。
 * <p>
 * 按月绘制网格，支持上下月滑动与日期点击/长按；格子底色由分段周期（{@link PeriodSegment}）
 * 推算的阶段决定，实际记录日使用实色阶段色，预测日带"预"标记。
 * 事件指示支持点号与文字两种展示模式，白点固定代表"当天有实际记录"。
 */
public class CalendarView
extends View {
    // ---------- 外观尺寸：从 XML 属性读取，loadAttributes 中已换算为 px ----------
    private float dayCornerRadius;
    private float daySpacing;
    private float daySize;
    /** 是否绘制上下月补位日期（否则对应格子留空） */
    private boolean showOtherMonthDays;
    /** 是否在日历下方绘制选中日期的日程列表 */
    private boolean showEventList = true;
    /** 日程列表可视高度（px），内容超出时在内部滚动 */
    private float eventListHeight;
    // ---------- 颜色与文字尺寸 ----------
    private int normalTextColor;
    private int todayTextColor;
    private int weekendTextColor;
    private int otherMonthTextColor;
    private int selectedDayColor;
    private int pressedDayColor;
    private int todayBgColor;
    private int weekendBgColor;
    private float dayTextSize;
    private float weekTextSize;
    private float eventTextSize;
    private boolean showNavigationBar = true;
    private float navigationBarHeight;
    private float monthYearTextSize;
    private int monthYearTextColor;
    private int navigationIconColor;

    // ---------- 当前展示与交互状态 ----------
    /** 当前展示的月份（只关心年月） */
    private Calendar currentMonth;
    /** 当前选中日期；下方日程列表默认展示该天数据 */
    private Calendar selectedDate;
    /** 今天，用于高亮、“今天”角标与回到今天 */
    private Calendar today;
    /** 手指按下命中的日期，仅用于绘制按下态 */
    private Calendar pressedDate;
    /** 长按已触发，抬手时不再按普通点击处理 */
    private boolean isLongPressMode = false;
    /** 上次点击年月文字的时间，用于识别双击 */
    private long lastMonthClickTime = 0L;
    private RectF monthYearTextRect;

    // ---------- 周期阶段数据（驱动日期底色与“预”标记） ----------
    /** 周期分段（升序），相邻分段决定中间段的实际长度 */
    private List<PeriodSegment> periodSegments;
    /** 预测阶段色，下标与四阶段一致：0 经期、1 卵泡、2 排卵、3 黄体（半透明） */
    private int[] periodColors;
    /** 实际记录阶段色，下标同上（实色） */
    private int[] actualPhaseColors;
    /** 是否显示阶段底色（导航栏的经期图标可切换） */
    private boolean showPeriodColors = true;
    /** 实际记录日期 → 阶段下标；存在的日期绘制实色并显示“已记录”指示 */
    private Map<String, Integer> actualPhaseMap;

    // ---------- 画笔：按用途拆分，避免绘制时反复改配置互相污染 ----------
    private Paint dayPaint;
    private TextPaint dayTextPaint;
    private TextPaint weekTextPaint;
    private TextPaint monthYearPaint;
    private TextPaint customTextPaint;
    private TextPaint eventListPaint;
    private Paint eventListBgPaint;
    private Paint eventItemBgPaint;
    private Paint todayBadgePaint;
    private Paint todayStrokePaint;
    private Paint selectionRingPaint;
    private Paint indicatorPaint;

    // ---------- 布局结果与按钮热区 ----------
    /** 每个日期格子的绘制区域，同时用于触摸命中判断 */
    private RectF[][] dayRect;
    /** 当前月份需要几行（4~6），随月份起始星期变化 */
    private int rows = 6;
    private RectF eventListRect;
    private RectF prevMonthBtnRect;
    private RectF nextMonthBtnRect;
    private RectF togglePeriodBtnRect;
    private RectF toggleEventDisplayBtnRect;
    private boolean prevMonthPressed = false;
    private boolean nextMonthPressed = false;
    private boolean togglePeriodPressed = false;
    private boolean toggleEventDisplayPressed = false;

    // ---------- 日程内容与列表滚动 ----------
    /** true：日程用彩色圆点表示；false：用“已记录/会议”等短文字表示 */
    private boolean showEventDots = true;
    private Drawable prevMonthIcon;
    private Drawable nextMonthIcon;
    private Drawable periodIcon;
    private Drawable noPeriodIcon;
    private Drawable dotIcon;
    private Drawable textIcon;
    /** 日期 id（yyyy-MM-dd）→ 当天事件，按重要度排序 */
    private final Map<String, List<EventData>> eventMap = new HashMap<String, List<EventData>>();
    /** 本次绘制出的日程条目热区，供点击命中使用（每次 onDraw 重建） */
    private final List<EventItemRect> eventItemReacts = new ArrayList<EventItemRect>();
    private GestureDetector gestureDetector;
    private EventItemRect pressedEventItem;
    /** 日程列表当前滚动偏移与内容总高度（px） */
    private float eventListScrollY = 0.0f;
    private float eventListContentHeight = 0.0f;
    private boolean isScrollingEventList = false;
    private float lastTouchY = 0.0f;
    private boolean touchStartedInEventList = false;
    private ValueAnimator flingAnimator;
    private boolean isHandlingScroll = false;
    private float initialTouchY = 0.0f;
    /** 按下后的第一次移动是否仍在校准阶段（用于扣除 touchSlop，避免列表轻微抖动就滚动） */
    private boolean isFirstMoveAfterDown = true;
    /** 判定为滚动所需的最小位移（8dp） */
    private final float touchSlop = this.dp2px(8.0f);

    // ---------- 对外回调 ----------
    private OnDateClickListener dateClickListener;
    private OnDateLongClickListener dateLongClickListener;
    private OnMonthChangeListener monthChangeListener;
    private OnCalendarActionListener calendarActionListener;

    public CalendarView(Context context) {
        this(context, null);
    }

    public CalendarView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CalendarView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        this.init(context, attrs);
    }

    /**
     * 控件初始化：建立日期基准、读取 XML 属性、创建画笔与图标、初始化按钮热区和手势检测。
     * 三个构造方法最终都汇聚到这里，避免在构造方法中重复初始化。
     */
    private void init(Context context, AttributeSet attrs) {
        this.currentMonth = Calendar.getInstance();
        this.selectedDate = Calendar.getInstance();
        this.today = Calendar.getInstance();
        this.loadAttributes(context, attrs);
        this.initPaints();
        this.initIcons(context);
        this.prevMonthBtnRect = new RectF();
        this.nextMonthBtnRect = new RectF();
        this.togglePeriodBtnRect = new RectF();
        this.toggleEventDisplayBtnRect = new RectF();
        this.eventListRect = new RectF();
        this.monthYearTextRect = new RectF();
        this.setClickable(true);
        this.initGestureDetector(context);
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    /**
     * 读取 XML 自定义属性（CalendarView 样式）。
     * <p>
     * attrs 为 null 表示代码直接 new 出控件，此时全部使用默认值；
     * TypedArray 必须 recycle，否则会泄漏资源。
     */
    private void loadAttributes(Context context, AttributeSet attrs) {
        if (attrs == null) {
            return;
        }
        TypedArray ta = context.obtainStyledAttributes(attrs, R.styleable.CalendarView);
        try {
            this.dayCornerRadius = ta.getDimension(R.styleable.CalendarView_cv_dayCornerRadius, (float)this.dp2px(8.0f));
            this.daySpacing = ta.getDimension(R.styleable.CalendarView_cv_daySpacing, (float)this.dp2px(2.0f));
            this.daySize = ta.getDimension(R.styleable.CalendarView_cv_daySize, (float)this.dp2px(36.0f));
            this.showOtherMonthDays = ta.getBoolean(R.styleable.CalendarView_cv_showOtherMonthDays, true);
            this.showEventList = ta.getBoolean(R.styleable.CalendarView_cv_showEventList, true);
            this.eventListHeight = ta.getDimension(R.styleable.CalendarView_cv_eventListHeight, (float)this.dp2px(200.0f));
            this.normalTextColor = ta.getColor(R.styleable.CalendarView_cv_normalTextColor, this.color(R.color.text_body));
            this.todayTextColor = ta.getColor(R.styleable.CalendarView_cv_todayTextColor, -1);
            this.weekendTextColor = ta.getColor(R.styleable.CalendarView_cv_weekendTextColor, this.color(R.color.text_body));
            this.otherMonthTextColor = ta.getColor(R.styleable.CalendarView_cv_otherMonthTextColor, this.color(R.color.text_hint));
            this.selectedDayColor = ta.getColor(R.styleable.CalendarView_cv_selectedDayColor, this.color(R.color.calendar_selection));
            this.pressedDayColor = ta.getColor(R.styleable.CalendarView_cv_pressedDayColor, this.darkenColor(this.selectedDayColor));
            this.todayBgColor = ta.getColor(R.styleable.CalendarView_cv_todayBgColor, -49023);
            this.weekendBgColor = ta.getColor(R.styleable.CalendarView_cv_weekendBgColor, 0xD000000);
            this.dayTextSize = ta.getDimension(R.styleable.CalendarView_cv_dayTextSize, (float)this.sp2px(14.0f));
            this.weekTextSize = ta.getDimension(R.styleable.CalendarView_cv_weekTextSize, (float)this.sp2px(12.0f));
            this.eventTextSize = ta.getDimension(R.styleable.CalendarView_cv_eventTextSize, (float)this.sp2px(12.0f));
            this.showNavigationBar = ta.getBoolean(R.styleable.CalendarView_cv_showNavigationBar, true);
            this.navigationBarHeight = ta.getDimension(R.styleable.CalendarView_cv_navigationBarHeight, (float)this.dp2px(48.0f));
            this.monthYearTextSize = ta.getDimension(R.styleable.CalendarView_cv_monthYearTextSize, (float)this.sp2px(16.0f));
            this.monthYearTextColor = ta.getColor(R.styleable.CalendarView_cv_monthYearTextColor, this.color(R.color.text_title));
            this.navigationIconColor = ta.getColor(R.styleable.CalendarView_cv_navigationIconColor, this.color(R.color.text_sub));
        }
        finally {
            ta.recycle();
        }
    }

    /** 初始化各画笔的抗锯齿/对齐方式；颜色与字号随后由 loadAttributes 覆盖 */
    private void initPaints() {
        this.dayPaint = new Paint(1);
        this.dayPaint.setStyle(Paint.Style.FILL);
        this.dayTextPaint = new TextPaint(1);
        this.dayTextPaint.setTextAlign(Paint.Align.CENTER);
        this.dayTextPaint.setTextSize(this.dayTextSize);
        this.dayTextPaint.setFakeBoldText(true);
        this.weekTextPaint = new TextPaint(1);
        this.weekTextPaint.setTextAlign(Paint.Align.CENTER);
        this.weekTextPaint.setTextSize(this.weekTextSize);
        this.weekTextPaint.setColor(this.color(R.color.text_sub));
        this.monthYearPaint = new TextPaint(1);
        this.monthYearPaint.setTextAlign(Paint.Align.CENTER);
        this.monthYearPaint.setTextSize(this.monthYearTextSize);
        this.monthYearPaint.setColor(this.monthYearTextColor);
        this.monthYearPaint.setFakeBoldText(true);
        this.customTextPaint = new TextPaint(1);
        this.customTextPaint.setColor(this.color(R.color.text_sub));
        this.customTextPaint.setTextSize((float)this.sp2px(9.0f));
        this.customTextPaint.setTextAlign(Paint.Align.CENTER);
        this.customTextPaint.setAntiAlias(true);
        this.customTextPaint.setSubpixelText(true);
        this.eventListPaint = new TextPaint(1);
        this.eventListPaint.setTextSize(this.eventTextSize);
        this.eventListPaint.setColor(this.color(R.color.text_body));
        this.eventListBgPaint = new Paint(1);
        this.eventListBgPaint.setColor(this.color(R.color.inset_surface));
        this.eventListBgPaint.setStyle(Paint.Style.FILL);
        this.eventItemBgPaint = new Paint(1);
        this.eventItemBgPaint.setColor(this.color(R.color.card_surface));
        this.eventItemBgPaint.setStyle(Paint.Style.FILL);
        this.todayBadgePaint = new Paint(1);
        this.todayBadgePaint.setStyle(Paint.Style.FILL);
        this.todayBadgePaint.setColor(this.todayBgColor);
        this.todayStrokePaint = new Paint(1);
        this.todayStrokePaint.setStyle(Paint.Style.STROKE);
        this.todayStrokePaint.setStrokeWidth((float)this.dp2px(2.0f));
        this.todayStrokePaint.setColor(this.todayBgColor);
        this.selectionRingPaint = new Paint(1);
        this.selectionRingPaint.setStyle(Paint.Style.STROKE);
        this.selectionRingPaint.setStrokeWidth((float)this.dp2px(2.5f));
        this.selectionRingPaint.setColor(this.selectedDayColor);
        this.indicatorPaint = new Paint(1);
        this.indicatorPaint.setStyle(Paint.Style.FILL);
    }

    private int color(int resId) {
        return ContextCompat.getColor((Context)this.getContext(), (int)resId);
    }

    private void initIcons(Context context) {
        this.prevMonthIcon = ContextCompat.getDrawable((Context)context, (int)R.drawable.ic_cal_prev);
        this.nextMonthIcon = ContextCompat.getDrawable((Context)context, (int)R.drawable.ic_cal_next);
        this.periodIcon = ContextCompat.getDrawable((Context)context, (int)R.drawable.ic_period_on);
        this.noPeriodIcon = ContextCompat.getDrawable((Context)context, (int)R.drawable.ic_period_off);
        this.dotIcon = ContextCompat.getDrawable((Context)context, (int)R.drawable.ic_dots);
        this.textIcon = ContextCompat.getDrawable((Context)context, (int)R.drawable.ic_text);
        this.updateIconColors();
    }

    /** 阶段底色开关状态变化后同步两个导航图标的着色（开启=实色，关闭=灰度） */
    private void updateIconColors() {
        int brandPink = this.color(R.color.primary_color);
        int gray = this.navigationIconColor;
        if (this.prevMonthIcon != null) {
            DrawableCompat.setTint((Drawable)this.prevMonthIcon, (int)gray);
        }
        if (this.nextMonthIcon != null) {
            DrawableCompat.setTint((Drawable)this.nextMonthIcon, (int)gray);
        }
        if (this.periodIcon != null) {
            DrawableCompat.setTint((Drawable)this.periodIcon, (int)brandPink);
        }
        if (this.noPeriodIcon != null) {
            DrawableCompat.setTint((Drawable)this.noPeriodIcon, (int)gray);
        }
        if (this.dotIcon != null) {
            DrawableCompat.setTint((Drawable)this.dotIcon, (int)(this.showEventDots ? brandPink : gray));
        }
        if (this.textIcon != null) {
            DrawableCompat.setTint((Drawable)this.textIcon, (int)(this.showEventDots ? gray : brandPink));
        }
    }

    private void initGestureDetector(Context context) {
        this.gestureDetector = new GestureDetector(context, (GestureDetector.OnGestureListener)new GestureDetector.SimpleOnGestureListener(){

            public void onLongPress(@NonNull MotionEvent e) {
                if (!CalendarView.this.isHandlingScroll) {
                    CalendarView.this.isLongPressMode = true;
                    CalendarView.this.handleLongPress(e);
                }
            }

            public boolean onScroll(@NonNull MotionEvent e1, @NonNull MotionEvent e2, float distanceX, float distanceY) {
                float y = e2.getY();
                if (CalendarView.this.showEventList && y >= CalendarView.this.getCalendarBottom()) {
                    CalendarView.this.isScrollingEventList = true;
                    CalendarView.this.isHandlingScroll = true;
                    float newScrollY = CalendarView.this.eventListScrollY - distanceY;
                    float maxScroll = Math.max(0.0f, CalendarView.this.eventListContentHeight - CalendarView.this.eventListHeight);
                    CalendarView.this.eventListScrollY = Math.max(0.0f, Math.min(newScrollY, maxScroll));
                    CalendarView.this.invalidate();
                    return true;
                }
                return false;
            }

            public boolean onDown(@NonNull MotionEvent e) {
                return true;
            }

            public boolean onFling(@NonNull MotionEvent e1, @NonNull MotionEvent e2, float velocityX, float velocityY) {
                float y = e2.getY();
                if (CalendarView.this.showEventList && y >= CalendarView.this.getCalendarBottom()) {
                    if (CalendarView.this.flingAnimator != null && CalendarView.this.flingAnimator.isRunning()) {
                        CalendarView.this.flingAnimator.cancel();
                    }
                    float flingDistance = -velocityY * 0.25f / 1000.0f;
                    float targetScrollY = CalendarView.this.eventListScrollY + flingDistance;
                    float maxScroll = Math.max(0.0f, CalendarView.this.eventListContentHeight - CalendarView.this.eventListHeight);
                    targetScrollY = Math.max(0.0f, Math.min(targetScrollY, maxScroll));
                    CalendarView.this.flingAnimator = ValueAnimator.ofFloat((float[])new float[]{CalendarView.this.eventListScrollY, targetScrollY});
                    CalendarView.this.flingAnimator.setDuration(300L);
                    CalendarView.this.flingAnimator.setInterpolator((TimeInterpolator)new DecelerateInterpolator());
                    CalendarView.this.flingAnimator.addUpdateListener(animation -> {
                        CalendarView.this.eventListScrollY = ((Float)animation.getAnimatedValue()).floatValue();
                        CalendarView.this.invalidate();
                    });
                    CalendarView.this.flingAnimator.start();
                    return true;
                }
                return false;
            }
        });
        this.gestureDetector.setIsLongpressEnabled(true);
    }

    /**
     * 测量控件高度。
     * <p>
     * 高度包含：导航栏 + 周标题栏 + 日期网格（行数随月份动态变化） + 日程列表。
     * 外部给定精确高度（EXACTLY）时以外部为准，否则按内容自算，避免 wrap_content 被截断。
     */
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int height;
        int width = View.MeasureSpec.getSize((int)widthMeasureSpec);
        this.calculateRows();
        if (View.MeasureSpec.getMode((int)heightMeasureSpec) == 0x40000000) {
            height = View.MeasureSpec.getSize((int)heightMeasureSpec);
        } else {
            float navigationHeight = this.showNavigationBar ? this.navigationBarHeight : 0.0f;
            float weekBarHeight = this.weekTextSize * 2.5f;
            float calendarHeight = (float)this.rows * (this.daySize + this.daySpacing * 2.0f) + this.daySpacing;
            float eventListAreaHeight = this.showEventList ? this.eventListHeight : 0.0f;
            float totalHeight = navigationHeight + weekBarHeight + calendarHeight + eventListAreaHeight;
            height = (int)Math.ceil(totalHeight);
        }
        this.setMeasuredDimension(width, height);
    }

    /**
     * 根据当前月份计算需要几行，并重建日期热区数组。
     * <p>
     * 总格子数 = 1 号之前的空位数 + 当月天数；除以 7 向上取整。
     * dayRect 的行数必须与 rows 保持一致，否则触摸命中和绘制会越界。
     */
    private void calculateRows() {
        Calendar firstDay = this.getMonthFirstDay();
        int firstDayOfWeek = firstDay.get(7) - 1;
        int daysInMonth = this.currentMonth.getActualMaximum(5);
        int totalCells = firstDayOfWeek + daysInMonth;
        this.rows = (totalCells + 6) / 7;
        this.dayRect = new RectF[this.rows][7];
        for (int i = 0; i < this.rows; ++i) {
            for (int j = 0; j < 7; ++j) {
                this.dayRect[i][j] = new RectF();
            }
        }
    }

    /**
     * 绘制顺序：导航栏 → 周标题 → 日期网格 → 下方日程列表。
     * 日程列表最后画，保证其背景能盖住下方的日历内容。
     */
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // 每次绘制都重新对齐“今天”：应用若跨零点仍在运行，缓存的 today 会停留在昨天，
        // 导致新一天既不显示今日高亮、也被当成“未来日期”处理
        this.refreshToday();
        if (this.showNavigationBar) {
            this.drawNavigationBar(canvas);
        }
        this.drawWeekBar(canvas);
        this.drawDays(canvas);
        if (this.showEventList) {
            this.drawEventList(canvas);
        }
    }

    /**
     * 绘制顶部导航栏：中间是“年月”文字（双击回到今天），
     * 左侧为事件展示模式切换，右侧为上一月/下一月与阶段底色开关。
     * 按钮热区在此处一并更新，供触摸事件命中。
     */
    private void drawNavigationBar(Canvas canvas) {
        float width = this.getWidth();
        float centerY = this.navigationBarHeight / 2.0f;
        this.dayPaint.setColor(this.color(R.color.card_surface));
        canvas.drawRect(0.0f, 0.0f, width, this.navigationBarHeight, this.dayPaint);
        this.dayPaint.setColor(this.color(R.color.divider));
        canvas.drawLine(0.0f, this.navigationBarHeight, width, this.navigationBarHeight, this.dayPaint);
        String monthYear = this.getMonthYearString(this.currentMonth);
        float monthYearTextWidth = this.monthYearPaint.measureText(monthYear);
        float monthYearX = width / 2.0f;
        float monthYearY = centerY - (this.monthYearPaint.descent() + this.monthYearPaint.ascent()) / 2.0f;
        float padding = this.dp2px(8.0f);
        this.monthYearTextRect.set(monthYearX - monthYearTextWidth / 2.0f - padding, monthYearY + this.monthYearPaint.ascent() - padding, monthYearX + monthYearTextWidth / 2.0f + padding, monthYearY + this.monthYearPaint.descent() + padding);
        this.dayPaint.setColor(this.color(R.color.inset_surface));
        canvas.drawRoundRect(this.monthYearTextRect.left, this.monthYearTextRect.top, this.monthYearTextRect.right, this.monthYearTextRect.bottom, (float)this.dp2px(16.0f), (float)this.dp2px(16.0f), this.dayPaint);
        canvas.drawText(monthYear, monthYearX, monthYearY, (Paint)this.monthYearPaint);
        float btnSize = this.dp2px(40.0f);
        float btnY = centerY - btnSize / 2.0f;
        float spacing = this.dp2px(12.0f);
        float edgePadding = this.dp2px(16.0f);
        float prevMonthBtnX = edgePadding + btnSize + spacing;
        float periodBtnX = width - edgePadding - btnSize;
        float nextMonthBtnX = periodBtnX - btnSize - spacing;
        this.toggleEventDisplayBtnRect.set(edgePadding, btnY, edgePadding + btnSize, btnY + btnSize);
        Drawable eventDisplayIcon = this.showEventDots ? this.dotIcon : this.textIcon;
        this.drawNavigationButton(canvas, eventDisplayIcon, this.toggleEventDisplayBtnRect, this.toggleEventDisplayPressed, this.color(R.color.divider));
        this.prevMonthBtnRect.set(prevMonthBtnX, btnY, prevMonthBtnX + btnSize, btnY + btnSize);
        this.drawNavigationButton(canvas, this.prevMonthIcon, this.prevMonthBtnRect, this.prevMonthPressed, this.color(R.color.divider));
        this.nextMonthBtnRect.set(nextMonthBtnX, btnY, nextMonthBtnX + btnSize, btnY + btnSize);
        this.drawNavigationButton(canvas, this.nextMonthIcon, this.nextMonthBtnRect, this.nextMonthPressed, this.color(R.color.divider));
        this.togglePeriodBtnRect.set(periodBtnX, btnY, periodBtnX + btnSize, btnY + btnSize);
        Drawable periodToggleIcon = this.showPeriodColors ? this.periodIcon : this.noPeriodIcon;
        this.drawNavigationButton(canvas, periodToggleIcon, this.togglePeriodBtnRect, this.togglePeriodPressed, this.color(R.color.divider));
    }

    /** 绘制圆形按钮：按下态换底色，图标按按钮内边距缩放绘制 */
    private void drawNavigationButton(Canvas canvas, Drawable icon, RectF rect, boolean pressed, int pressedColor) {
        if (icon == null) {
            return;
        }
        this.dayPaint.setColor(pressed ? pressedColor : this.color(R.color.inset_surface));
        canvas.drawCircle(rect.centerX(), rect.centerY(), rect.width() / 2.0f, this.dayPaint);
        if (pressed) {
            this.dayPaint.setColor(0x22000000);
            canvas.drawCircle(rect.centerX() + (float)this.dp2px(1.0f), rect.centerY() + (float)this.dp2px(1.0f), rect.width() / 2.0f - (float)this.dp2px(1.0f), this.dayPaint);
        }
        int iconPadding = this.dp2px(8.0f);
        icon.setBounds((int)(rect.left + (float)iconPadding), (int)(rect.top + (float)iconPadding), (int)(rect.right - (float)iconPadding), (int)(rect.bottom - (float)iconPadding));
        icon.draw(canvas);
    }

    /** 单个日期格子的宽度（7 等分，扣除左右内边距与格子间距） */
    private float getCellWidth() {
        return ((float)this.getWidth() - this.daySpacing * 8.0f) / 7.0f;
    }

    /**
     * 绘制周标题栏（周日到周六）。
     * 注意：标题栏高度字面量 2.8f 必须与 onMeasure/getCalendarBottom 中的 2.5f/2.8f 口径保持一致，
     * 否则会出现文字与日期格重叠或留白。
     */
    private void drawWeekBar(Canvas canvas) {
        float startY = this.showNavigationBar ? this.navigationBarHeight : 0.0f;
        float width = this.getWidth();
        float weekBarHeight = this.weekTextSize * 2.8f;
        this.dayPaint.setColor(this.color(R.color.inset_surface));
        canvas.drawRect(0.0f, startY, width, startY + weekBarHeight, this.dayPaint);
        this.dayPaint.setColor(this.color(R.color.divider));
        canvas.drawLine(0.0f, startY + weekBarHeight, width, startY + weekBarHeight, this.dayPaint);
        float cellWidth = this.getCellWidth();
        String[] weekDays = new String[]{this.getContext().getString(R.string.calendar_weekday_sun), this.getContext().getString(R.string.calendar_weekday_mon), this.getContext().getString(R.string.calendar_weekday_tue), this.getContext().getString(R.string.calendar_weekday_wed), this.getContext().getString(R.string.calendar_weekday_thu), this.getContext().getString(R.string.calendar_weekday_fri), this.getContext().getString(R.string.calendar_weekday_sat)};
        for (int i = 0; i < 7; ++i) {
            float x = this.daySpacing + (float)i * (cellWidth + this.daySpacing) + cellWidth / 2.0f;
            float y = startY + weekBarHeight * 0.7f;
            if (i == 0 || i == 6) {
                this.weekTextPaint.setColor(this.weekendTextColor);
                this.weekTextPaint.setFakeBoldText(true);
            } else {
                this.weekTextPaint.setColor(this.color(R.color.text_sub));
                this.weekTextPaint.setFakeBoldText(false);
            }
            canvas.drawText(weekDays[i], x, y, (Paint)this.weekTextPaint);
        }
    }

    /**
     * 绘制日期网格：逐行逐列调用 drawDay。
     * 列下标需按“周起始日”偏移，不能直接用 Calendar.DAY_OF_WEEK。
     */
    private void drawDays(Canvas canvas) {
        float startY = (this.showNavigationBar ? this.navigationBarHeight : 0.0f) + this.weekTextSize * 2.8f;
        float cellWidth = this.getCellWidth();
        Calendar firstDay = this.getMonthFirstDay();
        int firstDayOfWeek = firstDay.get(7) - 1;
        int daysInMonth = this.currentMonth.getActualMaximum(5);
        for (int row = 0; row < this.rows; ++row) {
            for (int col = 0; col < 7; ++col) {
                float left = this.daySpacing + (float)col * (cellWidth + this.daySpacing) + (cellWidth - this.daySize) / 2.0f;
                float top = startY + this.daySpacing + (float)row * (this.daySize + this.daySpacing * 2.0f);
                this.dayRect[row][col].set(left, top, left + this.daySize, top + this.daySize);
                Calendar currentDate = this.getDateAtCell(row, col, firstDay, firstDayOfWeek, daysInMonth);
                if (currentDate == null) continue;
                int displayDay = currentDate.get(5);
                boolean isCurrentMonth = this.isSameMonth(currentDate, this.currentMonth);
                boolean isWeekend = col == 0 || col == 6;
                this.drawDay(canvas, this.dayRect[row][col], currentDate, displayDay, isCurrentMonth, isWeekend);
            }
        }
    }

    /**
     * 把网格坐标换算成具体日期，支持上下月补位。
     * <p>
     * position 是从当月 1 号所在格子开始数的绝对格位（0 起）：
     * 小于首日偏移时属于上月，超出当月天数时属于下月；
     * showOtherMonthDays 为 false 时这些格子返回 null（不绘制）。
     *
     * @return 该格子对应的日期；不需要绘制补位日期时返回 null
     */
    private Calendar getDateAtCell(int row, int col, Calendar firstDay, int firstDayOfWeek, int daysInMonth) {
        int position = row * 7 + col;
        if (position < firstDayOfWeek) {
            if (!this.showOtherMonthDays) {
                return null;
            }
            Calendar prevMonth = (Calendar)firstDay.clone();
            prevMonth.add(2, -1);
            int prevMonthDays = prevMonth.getActualMaximum(5);
            prevMonth.set(5, prevMonthDays - (firstDayOfWeek - position - 1));
            return prevMonth;
        }
        int dayOfMonth = position - firstDayOfWeek + 1;
        if (dayOfMonth <= daysInMonth) {
            Calendar date = (Calendar)firstDay.clone();
            date.set(5, dayOfMonth);
            return date;
        }
        if (!this.showOtherMonthDays) {
            return null;
        }
        Calendar nextMonth = (Calendar)firstDay.clone();
        nextMonth.add(2, 1);
        nextMonth.set(5, dayOfMonth - daysInMonth);
        return nextMonth;
    }

    /**
     * 绘制单个日期格子。
     * <p>
     * 优先级：阶段底色（实际记录用实色、预测用半透明色）> 选中 > 今天 > 周末；
     * 非本月日期整体降低透明度，但仍可点击（点击后自动切换到对应月份）。
     * 右下角“预”标记只画在预测日上，避免与用户实际记录混淆。
     */
    private void drawDay(Canvas canvas, RectF rect, Calendar date, int displayDay, boolean isCurrentMonth, boolean isWeekend) {
        boolean hasEvents;
        float inset;
        float left = rect.left;
        float top = rect.top;
        boolean isToday = this.isSameDay(date, this.today);
        boolean isSelected = this.isSameDay(date, this.selectedDate);
        String dateKey = this.formatDate(date);
        boolean isActual = this.isActualRecord(dateKey);
        int periodColor = this.getPeriodColorForDate(date);
        int bgColor = this.getDayBgColor(periodColor, isCurrentMonth, isToday, isWeekend, isSelected);
        int textColor = this.getDayTextColor(isCurrentMonth, isSelected, isToday, isWeekend);
        if (bgColor != 0) {
            textColor = this.getTextColorForBackground(bgColor);
        }
        if (bgColor != 0) {
            this.dayPaint.setColor(bgColor);
            canvas.drawRoundRect(rect, this.dayCornerRadius, this.dayCornerRadius, this.dayPaint);
            if (isToday) {
                this.todayBadgePaint.setColor(0x20FFFFFF);
                canvas.drawCircle(rect.centerX(), rect.centerY(), this.daySize * 0.6f, this.todayBadgePaint);
            }
        }
        if (isToday && isCurrentMonth) {
            inset = this.todayStrokePaint.getStrokeWidth() / 2.0f;
            canvas.drawRoundRect(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset, this.dayCornerRadius, this.dayCornerRadius, this.todayStrokePaint);
        }
        if (isSelected && isCurrentMonth) {
            inset = this.selectionRingPaint.getStrokeWidth() / 2.0f;
            canvas.drawRoundRect(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset, this.dayCornerRadius, this.dayCornerRadius, this.selectionRingPaint);
        }
        if (!isCurrentMonth) {
            textColor = Color.argb((int)128, (int)Color.red((int)textColor), (int)Color.green((int)textColor), (int)Color.blue((int)textColor));
        }
        if (isToday && isCurrentMonth && bgColor == 0) {
            textColor = this.todayBgColor;
        }
        this.dayTextPaint.setColor(textColor);
        float centerX = left + this.daySize / 2.0f;
        float dateY = top + this.daySize / 2.0f - (this.dayTextPaint.descent() + this.dayTextPaint.ascent()) / 2.0f;
        List<EventData> events = isCurrentMonth ? this.eventMap.get(dateKey) : null;
        boolean bl = hasEvents = events != null && !events.isEmpty();
        // 底部还要画事件/“已记录”文字时，把日期数字整体上移一点，
        // 否则 14sp 的数字与 7~8sp 的底部文字在 36dp 格子里会互相压住
        if (hasEvents || (isActual && isCurrentMonth && !this.showEventDots)) {
            dateY -= (float)this.dp2px(3.0f);
        }
        this.dayTextPaint.setFakeBoldText(isToday);
        canvas.drawText(String.valueOf(displayDay), centerX, dateY, (Paint)this.dayTextPaint);
        // 文字模式下当天已有实际记录时不再叠加“今日”，底部统一显示“已记录”，
        // 避免两个小字挤在同一个格子里
        if (isToday && isCurrentMonth && !hasEvents
                && !(isActual && !this.showEventDots)) {
            this.customTextPaint.setColor(bgColor != 0 ? textColor : this.todayBgColor);
            this.customTextPaint.setTextSize((float)this.sp2px(8.0f));
            canvas.drawText(this.getContext().getString(R.string.calendar_today), centerX, dateY + (float)this.dp2px(10.0f), (Paint)this.customTextPaint);
        }
        if (hasEvents || isActual && isCurrentMonth) {
            this.drawEventIndicators(canvas, events != null ? events : new ArrayList<EventData>(), top, centerX, textColor, isActual);
        }
        if (isCurrentMonth && !isActual && periodColor != 0) {
            if (this.isNightMode()) {
                this.customTextPaint.setColor(-1711276033);
            } else {
                this.customTextPaint.setColor(periodColor & 0xFFFFFF | 0xCC000000);
            }
            this.customTextPaint.setTextSize((float)this.sp2px(7.0f));
            this.customTextPaint.setTextAlign(Paint.Align.RIGHT);
            float markerX = rect.right - (float)this.dp2px(3.0f);
            Paint.FontMetrics fm = this.customTextPaint.getFontMetrics();
            float markerY = rect.top + (float)this.dp2px(3.0f) - fm.ascent;
            canvas.drawText(this.getContext().getString(R.string.calendar_prediction_marker), markerX, markerY, (Paint)this.customTextPaint);
            this.customTextPaint.setTextAlign(Paint.Align.CENTER);
        }
    }

    private boolean isNightMode() {
        return (this.getResources().getConfiguration().uiMode & 0x30) == 32;
    }

    /**
     * 绘制格子底部的日程指示。
     * <p>
     * 圆点模式最多展示 3 个槽位：若“实际记录白点 + 事件”超过容量，
     * 前两个画圆点、最后一个画“+N”，兼顾信息量与格子宽度；
     * 文字模式优先展示高重要度事件的标题，否则退化为“N 个日程”。
     *
     * @param isActual 当天是否有用户实际记录（白点固定占用第一个槽位）
     */
    private void drawEventIndicators(Canvas canvas, List<EventData> events, float top, float centerX, int textColor, boolean isActual) {
        this.customTextPaint.setTextAlign(Paint.Align.CENTER);
        if (this.showEventDots) {
            this.drawDotIndicators(canvas, events, top, centerX, textColor, isActual);
        } else {
            this.drawTextIndicators(canvas, events, top, centerX, textColor, isActual);
        }
    }

    /**
     * 圆点模式：白点固定代表实际记录，彩色点代表事件，超过 3 个槽位时最后一个画“+N”。
     */
    private void drawDotIndicators(Canvas canvas, List<EventData> events, float top, float centerX, int textColor, boolean isActual) {
        float indicatorRadius = this.dp2px(2.0f);
        float indicatorSpacing = this.dp2px(5.0f);
        float startY = top + this.daySize - (float) this.dp2px(6.0f);
        int maxSlots = 3;
        int recordSlot = isActual ? 1 : 0;
        int totalCount = events.size() + recordSlot;
        boolean hasMore = totalCount > maxSlots;
        int dotCount = hasMore ? maxSlots - 1 : totalCount;
        ArrayList<EventData> sortedEvents = new ArrayList<EventData>(events);
        sortedEvents.sort((e1, e2) -> {
            int importance1 = this.getEventImportance(e1.tag);
            int importance2 = this.getEventImportance(e2.tag);
            return Integer.compare(importance2, importance1);
        });
        int elementCount = hasMore ? dotCount + 1 : dotCount;
        for (int i = 0; i < elementCount; ++i) {
            float elementX = centerX - (float) (elementCount - 1) * indicatorSpacing / 2.0f + (float) i * indicatorSpacing;
            if (hasMore && i == elementCount - 1) {
                this.customTextPaint.setColor(textColor);
                this.customTextPaint.setTextSize((float) this.sp2px(8.0f));
                String moreText = "+" + (totalCount - dotCount);
                canvas.drawText(moreText, elementX, startY + (float) this.dp2px(2.0f), (Paint) this.customTextPaint);
                continue;
            }
            if (isActual && i == 0) {
                this.indicatorPaint.setColor(-1);
                canvas.drawCircle(elementX, startY, indicatorRadius, this.indicatorPaint);
                this.indicatorPaint.setColor(0x22000000);
                canvas.drawCircle(elementX + 0.5f, startY + 0.5f, indicatorRadius, this.indicatorPaint);
                continue;
            }
            EventData event = (EventData) sortedEvents.get(i - recordSlot);
            this.indicatorPaint.setColor(event.color != 0 ? event.color : this.getEventColor(event.tag));
            canvas.drawCircle(elementX, startY, indicatorRadius, this.indicatorPaint);
            this.indicatorPaint.setColor(0x22000000);
            canvas.drawCircle(elementX + 0.5f, startY + 0.5f, indicatorRadius, this.indicatorPaint);
        }
    }

    /**
     * 文字模式：提醒事件改为画圆点。
     * <p>
     * 提醒不是“记录”，若和会议/截止日一样退化成“1项”计数，会让用户误以为
     * 当天有记录（典型场景：阶段区间内每天都有吃药提醒，整月每格都显示“1项”）。
     * 因此只有提醒的日期画粉色圆点；有实际记录时仍显示“已记录”；
     * 其他事件类型保持原有的标题/数量逻辑。
     */
    private void drawTextIndicators(Canvas canvas, List<EventData> events, float top, float centerX, int textColor, boolean isActual) {
        ArrayList<EventData> reminderEvents = new ArrayList<EventData>();
        ArrayList<EventData> otherEvents = new ArrayList<EventData>();
        for (EventData event : events) {
            if ("reminder".equals(event.tag)) {
                reminderEvents.add(event);
            } else {
                otherEvents.add(event);
            }
        }
        if (!otherEvents.isEmpty()) {
            String displayText = this.getDisplayTextForEvents(otherEvents);
            if (isActual) {
                displayText = displayText.isEmpty()
                        ? this.getContext().getString(R.string.calendar_recorded_mark)
                        : this.getContext().getString(R.string.calendar_recorded_prefix) + displayText;
            }
            this.drawBottomText(canvas, displayText, top, centerX, textColor);
        } else if (!reminderEvents.isEmpty()) {
            // 提醒统一画圆点；当天还有实际记录时第一个槽位画白点，避免“有提醒却看不到标识”
            this.drawDotIndicators(canvas, reminderEvents, top, centerX, textColor, isActual);
        } else if (isActual) {
            this.drawBottomText(canvas,
                    this.getContext().getString(R.string.calendar_recorded_mark),
                    top, centerX, textColor);
        }
    }

    /** 在格子底部绘制单行文字（自动缩放/截断，避免超出格子） */
    private void drawBottomText(Canvas canvas, String displayText, float top, float centerX, int textColor) {
        if (displayText == null || displayText.isEmpty()) {
            return;
        }
        this.customTextPaint.setColor(textColor);
        this.customTextPaint.setTextSize((float) this.sp2px(7.0f));
        this.customTextPaint.setTextAlign(Paint.Align.CENTER);
        float startY = top + this.daySize - (float) this.dp2px(1.5f);
        float maxWidth = this.daySize * 0.86f;
        float textWidth = this.customTextPaint.measureText(displayText);
        if (textWidth > maxWidth) {
            if (displayText.length() > 3) {
                displayText = displayText.substring(0, 3) + "\u2026";
            } else {
                float scale = maxWidth / textWidth;
                this.customTextPaint.setTextSize(this.customTextPaint.getTextSize() * scale);
            }
        }
        canvas.drawText(displayText, centerX, startY, (Paint) this.customTextPaint);
        this.customTextPaint.setTextSize((float) this.sp2px(8.0f));
    }

    /**
     * 文字模式下选择要展示的内容：重要事件取标题前 4 个字，否则显示事件数量。
     * 只截取不换行，保证格子内始终单行绘制。
     */
    private String getDisplayTextForEvents(List<EventData> events) {
        if (events.isEmpty()) {
            return "";
        }
        ArrayList<EventData> importantEvents = new ArrayList<EventData>();
        for (EventData event : events) {
            if (this.getEventImportance(event.tag) < 3) continue;
            importantEvents.add(event);
        }
        if (!importantEvents.isEmpty()) {
            EventData mostImportantEvent = (EventData)importantEvents.get(0);
            String title = mostImportantEvent.title.trim();
            int maxChars = 4;
            if (title.length() <= maxChars) {
                return title;
            }
            return title.substring(0, maxChars);
        }
        if (events.size() == 1) {
            return this.getContext().getString(R.string.calendar_event_count_1);
        }
        if (events.size() <= 3) {
            return this.getContext().getString(R.string.calendar_event_count_n, new Object[]{events.size()});
        }
        return this.getContext().getString(R.string.calendar_event_count_many);
    }

    /**
     * 事件标签的重要度（数值越大越靠前）。
     * 用于排序：“重要/会议/截止”排在最上面，普通标签排在后面；
     * 新增标签时需同步维护这里的优先级，否则会默认落到最低档。
     */
    private int getEventImportance(String tag) {
        switch (tag) {
            case "important": {
                return 5;
            }
            case "meeting": 
            case "deadline": {
                return 4;
            }
            case "review": 
            case "birthday": {
                return 3;
            }
            case "reminder": {
                return 2;
            }
            case "travel": 
            case "visit": {
                return 2;
            }
        }
        return 1;
    }

    /**
     * 绘制日历下方的“某天日程”列表。
     * <p>
     * 采用自绘 + 内部滚动：先按内容总高度裁剪，再整体 translate 滚动偏移；
     * 只绘制视口内的条目，同时把条目热区写入 eventItemReacts 供点击命中。
     * 无日程时绘制空态插图与文案。
     */
    private void drawEventList(Canvas canvas) {
        float startY = this.getCalendarBottom();
        float width = this.getWidth();
        this.eventListRect.set(0.0f, startY, width, startY + this.eventListHeight);
        canvas.save();
        canvas.clipRect(this.eventListRect);
        float maxScroll = Math.max(0.0f, this.eventListContentHeight - this.eventListHeight);
        if (this.eventListScrollY > maxScroll) {
            this.eventListScrollY = maxScroll;
        }
        if (this.eventListScrollY < 0.0f) {
            this.eventListScrollY = 0.0f;
        }
        canvas.translate(0.0f, -this.eventListScrollY);
        this.eventListBgPaint.setColor(this.color(R.color.inset_surface));
        canvas.drawRect(0.0f, startY, width, startY + this.eventListHeight + this.eventListScrollY, this.eventListBgPaint);
        this.dayPaint.setColor(this.color(R.color.divider));
        canvas.drawLine(0.0f, startY, width, startY, this.dayPaint);
        this.eventItemReacts.clear();
        Calendar displayDate = this.selectedDate != null ? this.selectedDate : this.today;
        String displayDateKey = this.formatDate(displayDate);
        float titleX = this.dp2px(16.0f);
        float titleY = startY + (float)this.dp2px(20.0f);
        this.eventListPaint.setColor(this.color(R.color.text_body));
        this.eventListPaint.setTextSize((float)this.sp2px(17.0f));
        this.eventListPaint.setFakeBoldText(true);
        boolean isToday = this.isSameDay(displayDate, this.today);
        String title = isToday ? this.getContext().getString(R.string.calendar_today_schedule) : this.formatDateShort(displayDate) + this.getContext().getString(R.string.calendar_schedule_suffix);
        canvas.drawText(title, titleX, titleY, (Paint)this.eventListPaint);
        List<EventData> events = this.eventMap.get(displayDateKey);
        float itemStartY = titleY + (float)this.dp2px(25.0f);
        float itemHeight = this.dp2px(68.0f);
        float itemSpacing = this.dp2px(8.0f);
        this.eventListContentHeight = events != null && !events.isEmpty() ? (float)this.dp2px(45.0f) + (float)events.size() * (itemHeight + itemSpacing) - itemSpacing + (float)this.dp2px(40.0f) : (float)(this.dp2px(45.0f) + this.dp2px(100.0f));
        maxScroll = Math.max(0.0f, this.eventListContentHeight - this.eventListHeight);
        if (this.eventListScrollY > maxScroll) {
            this.eventListScrollY = maxScroll;
        }
        float visibleTop = startY + this.eventListScrollY;
        float visibleBottom = startY + this.eventListHeight + this.eventListScrollY;
        if (events != null && !events.isEmpty()) {
            events.sort((e1, e2) -> {
                int importance2;
                int importance1 = this.getEventImportance(e1.tag);
                if (importance1 != (importance2 = this.getEventImportance(e2.tag))) {
                    return Integer.compare(importance2, importance1);
                }
                return e1.title.compareTo(e2.title);
            });
            for (int i = 0; i < events.size(); ++i) {
                EventData event = events.get(i);
                float itemTop = itemStartY + (float)i * (itemHeight + itemSpacing);
                if (!(itemTop + itemHeight > visibleTop) || !(itemTop < visibleBottom)) continue;
                this.drawEventItem(canvas, event, displayDateKey, titleX, itemTop, width - (float)this.dp2px(32.0f), itemHeight);
                RectF itemRect = new RectF(titleX, itemTop, width - (float)this.dp2px(16.0f), itemTop + itemHeight);
                this.eventItemReacts.add(new EventItemRect(itemRect, event, displayDateKey));
            }
        } else {
            float noEventY = itemStartY + (float)this.dp2px(40.0f);
            if (noEventY > visibleTop && noEventY < visibleBottom) {
                this.dayPaint.setColor(this.color(R.color.divider));
                float iconSize = this.dp2px(48.0f);
                float iconX = width / 2.0f - iconSize / 2.0f;
                float iconY = noEventY - (float)this.dp2px(60.0f);
                this.dayPaint.setStyle(Paint.Style.STROKE);
                this.dayPaint.setStrokeWidth((float)this.dp2px(1.5f));
                canvas.drawRoundRect(iconX + (float)this.dp2px(12.0f), iconY + (float)this.dp2px(12.0f), iconX + iconSize - (float)this.dp2px(12.0f), iconY + iconSize - (float)this.dp2px(4.0f), (float)this.dp2px(4.0f), (float)this.dp2px(4.0f), this.dayPaint);
                this.dayPaint.setStyle(Paint.Style.FILL);
                this.dayPaint.setColor(this.color(R.color.inset_surface));
                canvas.drawRect(iconX + (float)this.dp2px(14.0f), iconY + (float)this.dp2px(14.0f), iconX + iconSize - (float)this.dp2px(14.0f), iconY + iconSize - (float)this.dp2px(6.0f), this.dayPaint);
                this.eventListPaint.setColor(this.color(R.color.text_hint));
                this.eventListPaint.setTextSize((float)this.sp2px(14.0f));
                this.eventListPaint.setFakeBoldText(false);
                String noEventText = isToday ? this.getContext().getString(R.string.calendar_no_schedule_today) : this.getContext().getString(R.string.calendar_no_schedule_that_day);
                float textWidth = this.eventListPaint.measureText(noEventText);
                float textX = (width - textWidth) / 2.0f;
                canvas.drawText(noEventText, textX, noEventY + (float)this.dp2px(10.0f), (Paint)this.eventListPaint);
            }
        }
        canvas.restore();
        if (this.eventListContentHeight > this.eventListHeight) {
            this.drawScrollBar(canvas, startY, width);
        }
    }

    /** 绘制日程列表滚动条：滑块高度与“可视高度/内容高度”成正比，并夹在两端之间 */
    private void drawScrollBar(Canvas canvas, float startY, float width) {
        float scrollBarWidth = this.dp2px(3.0f);
        float scrollBarX = width - scrollBarWidth - (float)this.dp2px(4.0f);
        this.dayPaint.setColor(0x22000000);
        canvas.drawRect(scrollBarX, startY + (float)this.dp2px(8.0f), scrollBarX + scrollBarWidth, startY + this.eventListHeight - (float)this.dp2px(8.0f), this.dayPaint);
        float scrollBarHeight = this.eventListHeight - (float)this.dp2px(16.0f);
        float thumbHeight = Math.max((float)this.dp2px(20.0f), scrollBarHeight * (this.eventListHeight / this.eventListContentHeight));
        float maxScroll = Math.max(1.0f, this.eventListContentHeight - this.eventListHeight);
        float thumbY = startY + (float)this.dp2px(8.0f) + (scrollBarHeight - thumbHeight) * (this.eventListScrollY / maxScroll);
        this.dayPaint.setColor(this.color(R.color.text_hint));
        canvas.drawRect(scrollBarX, thumbY, scrollBarX + scrollBarWidth, thumbY + thumbHeight, this.dayPaint);
    }

    /**
     * 绘制单条日程卡片：左侧色条 + 标题 + 时间 + 标签/描述。
     * <p>
     * 标题和描述超宽时按字符数截断并补省略号：先按“W”的宽度估算可容纳字符数，
     * 再用 Math.max(0, ...) 兜底，防止可用宽度为负时 substring 抛异常。
     */
    private void drawEventItem(Canvas canvas, EventData event, String dateStr, float left, float top, float width, float height) {
        // 日期键非法时直接跳过该条事件，避免把详情渲染到错误日期上
        Calendar eventDate = this.getCalendarFromString(dateStr);
        if (eventDate == null) {
            return;
        }
        boolean isPressed = this.pressedEventItem != null && this.pressedEventItem.event == event && this.pressedEventItem.dateKey.equals(dateStr);
        this.eventItemBgPaint.setColor(isPressed ? this.color(R.color.divider) : this.color(R.color.card_surface));
        RectF itemRect = new RectF(left, top, left + width, top + height);
        float cornerRadius = this.dp2px(10.0f);
        canvas.drawRoundRect(itemRect, cornerRadius, cornerRadius, this.eventItemBgPaint);
        if (isPressed) {
            this.dayPaint.setColor(0x1A000000);
            canvas.drawRoundRect(itemRect, cornerRadius, cornerRadius, this.dayPaint);
        } else {
            this.dayPaint.setColor(0xA000000);
            canvas.drawRoundRect(itemRect.left + (float)this.dp2px(0.5f), itemRect.top + (float)this.dp2px(1.0f), itemRect.right + (float)this.dp2px(0.5f), itemRect.bottom + (float)this.dp2px(1.0f), cornerRadius, cornerRadius, this.dayPaint);
        }
        float markerLeft = left + (float)this.dp2px(6.0f);
        float markerTop = top + (float)this.dp2px(10.0f);
        float markerBottom = top + height - (float)this.dp2px(10.0f);
        float markerWidth = this.dp2px(3.0f);
        int markerColor = event.color != 0 ? event.color : this.getEventColor(event.tag);
        this.indicatorPaint.setColor(markerColor);
        canvas.drawRoundRect(markerLeft, markerTop, markerLeft + markerWidth, markerBottom, (float)this.dp2px(1.5f), (float)this.dp2px(1.5f), this.indicatorPaint);
        float textLeft = markerLeft + markerWidth + (float)this.dp2px(12.0f);
        float textRight = left + width - (float)this.dp2px(12.0f);
        this.eventListPaint.setColor(this.color(R.color.text_body));
        this.eventListPaint.setTextSize((float)this.sp2px(15.0f));
        this.eventListPaint.setFakeBoldText(true);
        String title = event.title;
        float maxTitleWidth = textRight - textLeft - (float)this.dp2px(60.0f);
        String displayTitle = title;
        float titleWidth = this.eventListPaint.measureText(title);
        if (titleWidth > maxTitleWidth) {
            int charsToShow = (int)(maxTitleWidth / this.eventListPaint.measureText("W"));
            displayTitle = title.substring(0, Math.max(0, Math.min(charsToShow - 3, title.length()))) + "...";
        }
        float titleY = top + (float)this.dp2px(24.0f);
        canvas.drawText(displayTitle, textLeft, titleY, (Paint)this.eventListPaint);
        if (event.startTime != null) {
            this.eventListPaint.setColor(this.color(R.color.text_hint));
            this.eventListPaint.setTextSize((float)this.sp2px(11.0f));
            this.eventListPaint.setFakeBoldText(false);
            SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
            String timeText = timeFormat.format(event.startTime.getTime());
            if (event.endTime != null) {
                timeText = timeText + " - " + timeFormat.format(event.endTime.getTime());
            }
            float timeWidth = this.eventListPaint.measureText(timeText);
            float timeX = textRight - timeWidth;
            canvas.drawText(timeText, timeX, titleY, (Paint)this.eventListPaint);
        }
        this.eventListPaint.setColor(this.color(R.color.text_sub));
        this.eventListPaint.setTextSize((float)this.sp2px(12.0f));
        this.eventListPaint.setFakeBoldText(false);
        String tagInfo = this.getTagDisplayName(event.tag);
        String detail = event.description != null && !event.description.isEmpty() ? event.description : this.formatDateShort(eventDate);
        String fullInfo = tagInfo + " \u00b7 " + detail;
        float detailY = top + (float)this.dp2px(46.0f);
        float infoWidth = this.eventListPaint.measureText(fullInfo);
        float availableWidth = textRight - textLeft;
        if (infoWidth > availableWidth) {
            int charsToShow = (int)(availableWidth / this.eventListPaint.measureText("W"));
            fullInfo = tagInfo + " \u00b7 " + detail.substring(0, Math.max(0, Math.min(charsToShow - tagInfo.length() - 3, detail.length()))) + "...";
        }
        canvas.drawText(fullInfo, textLeft, detailY, (Paint)this.eventListPaint);
        if (event.isImportant) {
            this.indicatorPaint.setColor(-26624);
            float starSize = this.dp2px(4.0f);
            float starX = textRight - (float)this.dp2px(4.0f);
            float starY = top + (float)this.dp2px(20.0f);
            canvas.drawCircle(starX, starY, starSize, this.indicatorPaint);
        }
    }

    private String getTagDisplayName(String tag) {
        Context ctx = this.getContext();
        switch (tag) {
            case "meeting": {
                return ctx.getString(R.string.calendar_tag_meeting);
            }
            case "review": {
                return ctx.getString(R.string.calendar_tag_review);
            }
            case "birthday": {
                return ctx.getString(R.string.calendar_tag_birthday);
            }
            case "travel": {
                return ctx.getString(R.string.calendar_tag_travel);
            }
            case "visit": {
                return ctx.getString(R.string.calendar_tag_visit);
            }
            case "important": {
                return ctx.getString(R.string.calendar_tag_important);
            }
            case "deadline": {
                return ctx.getString(R.string.calendar_tag_deadline);
            }
            case "team": {
                return ctx.getString(R.string.calendar_tag_team);
            }
            case "demo": {
                return ctx.getString(R.string.calendar_tag_demo);
            }
            case "family": {
                return ctx.getString(R.string.calendar_tag_family);
            }
            case "release": {
                return ctx.getString(R.string.calendar_tag_release);
            }
            case "design": {
                return ctx.getString(R.string.calendar_tag_design);
            }
            case "training": {
                return ctx.getString(R.string.calendar_tag_training);
            }
            case "planning": {
                return ctx.getString(R.string.calendar_tag_planning);
            }
            case "requirement": {
                return ctx.getString(R.string.calendar_tag_requirement);
            }
            case "test": {
                return ctx.getString(R.string.calendar_tag_test);
            }
            case "deploy": {
                return ctx.getString(R.string.calendar_tag_deploy);
            }
            case "summary": {
                return ctx.getString(R.string.calendar_tag_summary);
            }
        }
        return ctx.getString(R.string.calendar_tag_default);
    }

    /**
     * 解析 yyyy-MM-dd 字符串为 Calendar。
     *
     * @return 解析成功返回对应日期；格式非法或为空返回 null，
     *         调用方需跳过该条数据，不要回退到“今天”
     */
    private Calendar getCalendarFromString(String dateStr) {
        if (dateStr == null) {
            return null;
        }
        try {
            String[] parts = dateStr.split("-");
            if (parts.length != 3) {
                return null;
            }
            Calendar cal = Calendar.getInstance();
            cal.set(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) - 1, Integer.parseInt(parts[2]));
            return cal;
        }
        catch (Exception e) {
            return null;
        }
    }

    /** 事件标签 → 主题色；未知标签使用强调色，保证总能有可见颜色 */
    private int getEventColor(String tag) {
        switch (tag) {
            case "important": {
                return -49023;
            }
            case "meeting": {
                return -14575885;
            }
            case "review": {
                return -11751600;
            }
            case "birthday": {
                return -26624;
            }
            case "travel": 
            case "test": {
                return -6543440;
            }
            case "visit": {
                return -769226;
            }
            case "deadline": {
                return -43230;
            }
            case "team": {
                return -10011977;
            }
            case "demo": {
                return -16738393;
            }
            case "family": {
                return -1499549;
            }
            case "release": {
                return -8825528;
            }
            case "design": {
                return -12627531;
            }
            case "training": {
                return -16728876;
            }
            case "planning": {
                return -7617718;
            }
            case "requirement": {
                return -16121;
            }
            case "summary": {
                return -3285959;
            }
            case "reminder": {
                return -37997;
            }
            case "suggestion": {
                return -6521624;
            }
        }
        return -10453621;
    }

    /** 日历网格底边（= 日程列表顶边）的 y 坐标；触摸事件用它区分两个区域 */
    private float getCalendarBottom() {
        float startY = this.showNavigationBar ? this.navigationBarHeight : 0.0f;
        float weekBarHeight = this.weekTextSize * 2.8f;
        return startY + weekBarHeight + (float)this.rows * (this.daySize + this.daySpacing * 2.0f) + this.daySpacing;
    }

    /**
     * 计算日期格子的背景色，返回 0 表示不绘制背景（非本月日期）。
     * 优先级：阶段色 > 选中 > 今天 > 周末，保证“选中态”不会被周末底色盖住。
     */
    private int getDayBgColor(int periodColor, boolean isCurrentMonth, boolean isToday, boolean isWeekend, boolean isSelected) {
        if (!isCurrentMonth) {
            return 0;
        }
        if (periodColor != 0) {
            return periodColor;
        }
        if (isSelected) {
            return this.selectedDayColor;
        }
        if (isToday) {
            return this.todayBgColor;
        }
        if (isWeekend) {
            return this.weekendBgColor;
        }
        return 0xD000000;
    }

    private int getDayTextColor(boolean isCurrentMonth, boolean isSelected, boolean isToday, boolean isWeekend) {
        if (isToday) {
            return this.todayTextColor;
        }
        if (!isCurrentMonth) {
            return this.otherMonthTextColor;
        }
        if (isWeekend) {
            return this.weekendTextColor;
        }
        return this.normalTextColor;
    }

    /**
     * 根据背景色计算可读的文字颜色（黑或白）。
     * <p>
     * 背景可能是半透明阶段色，先把它与当前主题的“白/深色”基底做 alpha 混合，
     * 再按相对亮度选择对比度更高的一侧，避免浅粉底上出现白字看不清。
     */
    private int getTextColorForBackground(int bgColor) {
        int base = this.isNightMode() ? -15592942 : -1;
        float alpha = (float)Color.alpha((int)bgColor) / 255.0f;
        int red = (int)((float)Color.red((int)bgColor) * alpha + (float)Color.red((int)base) * (1.0f - alpha));
        int green = (int)((float)Color.green((int)bgColor) * alpha + (float)Color.green((int)base) * (1.0f - alpha));
        int blue = (int)((float)Color.blue((int)bgColor) * alpha + (float)Color.blue((int)base) * (1.0f - alpha));
        double luminance = (0.299 * (double)red + 0.587 * (double)green + 0.114 * (double)blue) / 255.0;
        double contrastWithWhite = 1.05 / (luminance + 0.05);
        double contrastWithDark = (luminance + 0.05) / 0.05;
        return contrastWithWhite >= contrastWithDark ? -1 : -14606047;
    }

    /**
     * 触摸事件总入口：按区域把事件分发给日程列表或日历网格。
     * <p>
     * 关键点：
     * <ul>
     *   <li>按下时先 requestDisallowInterceptTouchEvent(true)，防止外层 ViewPager 抢走横向手势；</li>
     *   <li>日程列表的滚动偏移在“内容坐标”中计算，命中条目时需把 y 加上 scrollY；</li>
     *   <li>抬手（ACTION_UP/CANCEL）必须恢复父容器的拦截能力，否则页面后续无法滑动。</li>
     * </ul>
     */
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        int action = event.getActionMasked();
        boolean inEventListArea = this.showEventList && y >= this.getCalendarBottom();
        this.gestureDetector.onTouchEvent(event);
        switch (action) {
            case 0: {
                this.lastTouchY = y;
                this.initialTouchY = y;
                this.touchStartedInEventList = inEventListArea;
                this.isScrollingEventList = false;
                this.isHandlingScroll = false;
                this.isFirstMoveAfterDown = true;
                this.pressedEventItem = null;
                if (inEventListArea) {
                    float adjustedY = y + this.eventListScrollY;
                    for (EventItemRect itemRect : this.eventItemReacts) {
                        if (!itemRect.rect.contains(x, adjustedY)) continue;
                        this.pressedEventItem = itemRect;
                        this.invalidate();
                        return true;
                    }
                    this.getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                this.getParent().requestDisallowInterceptTouchEvent(true);
                return this.handleCalendarAreaActionDown(x, y);
            }
            case 2: {
                if (this.touchStartedInEventList) {
                    float dy = y - this.lastTouchY;
                    if (this.isFirstMoveAfterDown) {
                        float distanceMoved = Math.abs(y - this.initialTouchY);
                        if (distanceMoved < this.touchSlop) {
                            this.lastTouchY = y;
                            return true;
                        }
                        this.isFirstMoveAfterDown = false;
                        this.lastTouchY = this.initialTouchY + (y > this.initialTouchY ? this.touchSlop : -this.touchSlop);
                        dy = y - this.lastTouchY;
                    }
                    this.isScrollingEventList = true;
                    this.isHandlingScroll = true;
                    float newScrollY = this.eventListScrollY - dy;
                    float maxScroll = Math.max(0.0f, this.eventListContentHeight - this.eventListHeight);
                    this.eventListScrollY = Math.max(0.0f, Math.min(newScrollY, maxScroll));
                    this.lastTouchY = y;
                    this.getParent().requestDisallowInterceptTouchEvent(true);
                    this.invalidate();
                    return true;
                }
                if (inEventListArea) break;
                return this.handleCalendarAreaActionMove(x, y);
            }
            case 1: 
            case 3: {
                boolean result = this.handleActionUp(x, y, this.touchStartedInEventList);
                this.touchStartedInEventList = false;
                this.isHandlingScroll = false;
                this.isFirstMoveAfterDown = true;
                this.getParent().requestDisallowInterceptTouchEvent(false);
                return result;
            }
        }
        return this.gestureDetector.onTouchEvent(event) || super.onTouchEvent(event);
    }

    /**
     * 处理日历区域的按下：识别年月文字双击、四个导航按钮与日期格子。
     *
     * @return 命中任何可交互元素返回 true，交给后续 MOVE/UP 继续处理
     */
    private boolean handleCalendarAreaActionDown(float x, float y) {
        if (this.isHandlingScroll) {
            return false;
        }
        if (this.showNavigationBar && this.monthYearTextRect.contains(x, y)) {
            long clickTime = System.currentTimeMillis();
            if (clickTime - this.lastMonthClickTime < 300L) {
                this.lastMonthClickTime = 0L;
                this.goToToday();
                this.performClick();
                return true;
            }
            this.lastMonthClickTime = clickTime;
            return true;
        }
        if (this.showNavigationBar) {
            if (this.toggleEventDisplayBtnRect.contains(x, y)) {
                this.toggleEventDisplayPressed = true;
                this.invalidate();
                return true;
            }
            if (this.prevMonthBtnRect.contains(x, y)) {
                this.prevMonthPressed = true;
                this.invalidate();
                return true;
            }
            if (this.nextMonthBtnRect.contains(x, y)) {
                this.nextMonthPressed = true;
                this.invalidate();
                return true;
            }
            if (this.togglePeriodBtnRect.contains(x, y)) {
                this.togglePeriodPressed = true;
                this.invalidate();
                return true;
            }
        }
        this.pressedDate = this.getDateAtPosition(x, y);
        return this.pressedDate != null;
    }

    /** 移动时更新按钮与日期的按下态（只改状态并重绘，不触发业务回调） */
    private boolean handleCalendarAreaActionMove(float x, float y) {
        Calendar newPressedDate;
        if (this.isHandlingScroll) {
            return false;
        }
        if (this.showNavigationBar) {
            boolean prevPressed = this.prevMonthBtnRect.contains(x, y);
            boolean nextPressed = this.nextMonthBtnRect.contains(x, y);
            boolean togglePressed = this.togglePeriodBtnRect.contains(x, y);
            boolean toggleEventDisplayPressedNew = this.toggleEventDisplayBtnRect.contains(x, y);
            if (this.prevMonthPressed != prevPressed || this.nextMonthPressed != nextPressed || this.togglePeriodPressed != togglePressed || this.toggleEventDisplayPressed != toggleEventDisplayPressedNew) {
                this.prevMonthPressed = prevPressed;
                this.nextMonthPressed = nextPressed;
                this.togglePeriodPressed = togglePressed;
                this.toggleEventDisplayPressed = toggleEventDisplayPressedNew;
                this.invalidate();
            }
        }
        if (!this.isSameDay(this.pressedDate, newPressedDate = this.getDateAtPosition(x, y))) {
            this.pressedDate = newPressedDate;
        }
        return true;
    }

    /**
     * 抬手时统一结算：仅当“按下与抬起都落在同一控件/日期上”才触发点击，
     * 滑动或长按过的这次手势不触发，避免误选日期。
     */
    private boolean handleActionUp(float x, float y, boolean touchStartedInEventListArea) {
        Calendar clickedDate;
        if (this.showNavigationBar && this.monthYearTextRect.contains(x, y) && this.calendarActionListener != null) {
            this.calendarActionListener.onMonthTextClick();
        }
        if (this.showNavigationBar) {
            if (this.prevMonthPressed && this.prevMonthBtnRect.contains(x, y)) {
                this.goToPreviousMonth();
                this.performClick();
            }
            if (this.nextMonthPressed && this.nextMonthBtnRect.contains(x, y)) {
                this.goToNextMonth();
                this.performClick();
            }
            if (this.togglePeriodPressed && this.togglePeriodBtnRect.contains(x, y)) {
                this.togglePeriodDisplay();
                this.performClick();
            }
            if (this.toggleEventDisplayPressed && this.toggleEventDisplayBtnRect.contains(x, y)) {
                this.toggleEventDisplayMode();
                this.performClick();
            }
        }
        if (touchStartedInEventListArea) {
            float adjustedY;
            if (this.pressedEventItem != null && !this.isScrollingEventList && !this.isHandlingScroll && this.pressedEventItem.rect.contains(x, adjustedY = y + this.eventListScrollY)) {
                this.handleEventItemClick(this.pressedEventItem.event, this.pressedEventItem.dateKey);
                this.performClick();
            }
            this.pressedEventItem = null;
        } else if (!this.isHandlingScroll && (clickedDate = this.getDateAtPosition(x, y)) != null && !this.isLongPressMode) {
            this.handleDateClick(clickedDate);
            this.performClick();
        }
        this.resetPressStates();
        return true;
    }

    /**
     * 清空所有“按下/长按”临时状态并重绘。
     * 每次手势结束（UP/CANCEL）和月份切换时都要调用，避免按钮停留在按下态。
     */
    private void resetPressStates() {
        this.prevMonthPressed = false;
        this.nextMonthPressed = false;
        this.togglePeriodPressed = false;
        this.toggleEventDisplayPressed = false;
        this.pressedDate = null;
        this.pressedEventItem = null;
        this.isLongPressMode = false;
        this.invalidate();
    }

    /**
     * 长按处理：只作用于日历网格区域。
     * 若该日已有事件，优先回调事件点击；否则回调日期长按（用于补记录等入口）。
     */
    private void handleLongPress(MotionEvent e) {
        Calendar date;
        if (this.isHandlingScroll) {
            return;
        }
        float x = e.getX();
        float y = e.getY();
        if (y < this.getCalendarBottom() && (date = this.getDateAtPosition(x, y)) != null) {
            String dateKey = this.formatDate(date);
            List<EventData> events = this.eventMap.get(dateKey);
            if (events != null && !events.isEmpty()) {
                if (this.calendarActionListener != null) {
                    this.calendarActionListener.onEventClicked(dateKey, events.get(0));
                }
            } else {
                this.notifyDateLongClicked(date);
            }
        }
    }

    /**
     * 自定义 View 必须重写 performClick 才能正确触发无障碍点击事件；
     * 这里同时播放点击音效并朗读当前选中日期。
     */
    public boolean performClick() {
        super.performClick();
        if (this.isClickable() && this.isEnabled()) {
            this.playSoundEffect(0);
            this.sendAccessibilityEvent(1);
            if (this.selectedDate != null) {
                String dateStr = this.formatDateForAccessibility(this.selectedDate);
                this.announceForAccessibility("\u9009\u4e2d" + dateStr);
            }
        }
        return true;
    }

    /**
     * 选中日期并联动切换展示月份，然后回调 OnDateClickListener。
     * 副作用：会修改 selectedDate/currentMonth 并重绘，同时可能触发月变化回调。
     */
    private void handleDateClick(Calendar date) {
        this.selectedDate.setTime(date.getTime());
        if (!this.isSameMonth(date, this.currentMonth)) {
            this.currentMonth.setTime(date.getTime());
            this.calculateRows();
            this.requestLayout();
            this.notifyMonthChanged();
        }
        this.notifyDateClicked(date);
        if (this.calendarActionListener != null) {
            this.calendarActionListener.onDateSelected(date);
        }
        this.invalidate();
    }

    private void handleEventItemClick(EventData event, String dateKey) {
        if (this.calendarActionListener != null) {
            this.calendarActionListener.onEventItemClick(event);
        }
    }

    /** 切换“圆点/文字”展示模式并通知外部（外部可持久化该偏好） */
    public void toggleEventDisplayMode() {
        this.showEventDots = !this.showEventDots;
        this.updateIconColors();
        this.invalidate();
        this.notifyEventDisplayModeChanged(this.showEventDots);
    }

    public void setShowEventDots(boolean showDots) {
        if (this.showEventDots != showDots) {
            this.showEventDots = showDots;
            this.updateIconColors();
            this.invalidate();
            this.notifyEventDisplayModeChanged(this.showEventDots);
        }
    }

    public boolean isShowEventDots() {
        return this.showEventDots;
    }

    private void notifyEventDisplayModeChanged(boolean showEventDots) {
        if (this.calendarActionListener != null) {
            this.calendarActionListener.onEventDisplayModeChanged(showEventDots);
        }
    }

    /** 把触摸坐标换算成日期：落在日历网格外（含日程列表区域）返回 null */
    private Calendar getDateAtPosition(float x, float y) {
        if (this.dayRect == null) {
            return null;
        }
        for (int row = 0; row < this.rows; ++row) {
            for (int col = 0; col < 7; ++col) {
                if (!this.dayRect[row][col].contains(x, y)) continue;
                return this.getDateAtPosition(row, col);
            }
        }
        return null;
    }

    private Calendar getDateAtPosition(int row, int col) {
        Calendar firstDay = this.getMonthFirstDay();
        int firstDayOfWeek = firstDay.get(7) - 1;
        int daysInMonth = this.currentMonth.getActualMaximum(5);
        return this.getDateAtCell(row, col, firstDay, firstDayOfWeek, daysInMonth);
    }

    /**
     * 设置分段周期数据：每段的起点与阶段天数，以及预测色/实际记录色数组。
     * <p>
     * 段与段之间的天数由相邻起点决定，中间段会按实际间隔拉伸阶段天数（间隔过短时只保留经期）。
     *
     * @param segments    周期分段列表
     * @param periodColors 四阶段预测色（半透明）
     * @param actualColors 四阶段实际记录色（实色）
     */
    public void setPeriodSegments(List<PeriodSegment> segments, int[] periodColors, int[] actualColors) {
        this.periodSegments = segments;
        this.periodColors = periodColors;
        this.actualPhaseColors = actualColors;
        this.invalidate();
    }

    /**
     * 设置是否显示阶段底色。
     * 与 {@link #togglePeriodDisplay()} 的区别：值未变化时不重绘、不回调。
     */
    public void setShowPeriodColors(boolean show) {
        if (this.showPeriodColors != show) {
            this.showPeriodColors = show;
            this.updateIconColors();
            this.invalidate();
            this.notifyPeriodToggle(show);
        }
    }

    /**
     * 切换阶段底色显示，并同步导航栏图标与状态。
     *
     * @return 切换后的显示状态
     */
    public boolean togglePeriodDisplay() {
        this.showPeriodColors = !this.showPeriodColors;
        this.updateIconColors();
        this.invalidate();
        this.notifyPeriodToggle(this.showPeriodColors);
        return this.showPeriodColors;
    }

    /**
     * 设置"实际记录日"的阶段映射（key 为 yyyyMMdd 日期 id）。
     * <p>
     * 这些日期用实色阶段底色绘制，与预测的半透明底色区分开。
     *
     * @param phaseMap 日期 id 到阶段下标的映射
     */
    public void setActualRecordPhases(Map<String, Integer> phaseMap) {
        this.actualPhaseMap = phaseMap;
        this.invalidate();
    }

    /**
     * 两个日期相差的自然日数（to - from）。
     * 与 {@link CycleCalculator#daysBetween(Date, Date)} 统一按 UTC 纪元日计算，
     * 避免直接除 86400000L 在跨夏令时时区少算/多算一天。
     */
    private int diffDays(Calendar from, Calendar to) {
        return CycleCalculator.daysBetween(from.getTime(), to.getTime());
    }

    /** 四个阶段天数求和，用于判断分段内是否还允许显示预测阶段 */
    private int sumDays(int[] phaseDays) {
        int sum = 0;
        for (int d : phaseDays) {
            sum += d;
        }
        return sum;
    }

    /**
     * 找到 date 所属的分段下标（分段按起点升序）。
     * 取“起点不晚于 date 的最后一个分段”，因此在第一个分段起点之前的日期返回 -1。
     */
    private int findSegmentIndex(Calendar date) {
        if (this.periodSegments == null || this.periodSegments.isEmpty()) {
            return -1;
        }
        int result = -1;
        int i = 0;
        while (i < this.periodSegments.size() && !date.before(this.periodSegments.get((int)i).start)) {
            result = i++;
        }
        return result;
    }

    /**
     * 取某一段实际生效的阶段天数。
     * <p>
     * 中间段（还有下一段）按"本段起点到下一段起点"的真实间隔拉伸：
     * 经期不变、黄体固定 14 天、卵泡吸收差值；间隔不足以放下
     * 经期 + 1 天卵泡 + 3 天排卵 + 14 天黄体时，只保留经期，其余日期按无预测处理。
     *
     * @param segIndex 分段下标
     * @return 四阶段天数（经期、卵泡、排卵、黄体）
     */
    private int[] effectivePhaseDays(int segIndex) {
        PeriodSegment seg = this.periodSegments.get(segIndex);
        if (segIndex + 1 >= this.periodSegments.size()) {
            return seg.phaseDays;
        }
        PeriodSegment next = this.periodSegments.get(segIndex + 1);
        int actualTotal = this.diffDays(seg.start, next.start);
        if (actualTotal <= 0 || actualTotal == seg.totalCycleDay) {
            return seg.phaseDays;
        }
        int menstrual = seg.phaseDays[0];
        if (actualTotal < menstrual + 18) {
            return new int[]{menstrual, 0, 0, 0};
        }
        return CycleCalculator.calculatePhaseDays(menstrual, actualTotal);
    }

    /**
     * 四个阶段天数按顺序累加，判断 seq（周期内第 0 起的天数）落在哪个阶段。
     * seq 超出四阶段总和时返回最后一个阶段下标，由调用方先行过滤越界情况。
     */
    private int phaseIndexBySequence(int seq, int[] phaseDays) {
        int accumulated = 0;
        for (int i = 0; i < phaseDays.length; ++i) {
            if (seq < accumulated + phaseDays[i]) {
                return i;
            }
            accumulated += phaseDays[i];
        }
        return phaseDays.length - 1;
    }

    /** 阶段下标之前的累计天数（即该阶段第 1 天在周期内的偏移，0 起） */
    private int accumulatedBefore(int phaseIndex, int[] phaseDays) {
        int sum = 0;
        for (int i = 0; i < phaseIndex && i < phaseDays.length; ++i) {
            sum += phaseDays[i];
        }
        return sum;
    }

    /**
     * 计算日期在“当前分段”内的序号（0 起），用于阶段推算。
     * <p>
     * 中间分段直接取与分段起点的天数差（该段长度由下一段起点决定，不做循环）；
     * 最后一段按周期总天数取模循环；存在阶段锚点且锚点不早于分段起点时，
     * 从锚点阶段起算（锚点当天为锚点阶段第 1 天）。
     * 取模使用 (seq % total + total) % total，兼容日期早于锚点导致的负序号。
     */
    private int getCycleSequenceMod(Calendar date, int segIndex) {
        PeriodSegment seg = this.periodSegments.get(segIndex);
        if (segIndex + 1 < this.periodSegments.size()) {
            return this.diffDays(seg.start, date);
        }
        int total = seg.totalCycleDay > 0 ? seg.totalCycleDay : this.sumDays(seg.phaseDays);
        int seq = this.diffDays(seg.start, date);
        return (seq % total + total) % total;
    }

    /**
     * 推算日期所属阶段下标。
     * <p>
     * 中间分段若序号已超出“四阶段总和”（短间隔分段只保留经期的情形），返回 -1 表示无预测；
     * 最后一段允许按周期循环，不额外裁剪。
     *
     * @return 0~3 的阶段下标；无法预测返回 -1
     */
    private int getPredictedPhaseIndex(Calendar date) {
        int segIndex = this.findSegmentIndex(date);
        if (segIndex < 0) {
            return -1;
        }
        int[] phaseDays = this.effectivePhaseDays(segIndex);
        int seq = this.getCycleSequenceMod(date, segIndex);
        if (segIndex + 1 < this.periodSegments.size() && seq >= this.sumDays(phaseDays)) {
            return -1;
        }
        return this.phaseIndexBySequence(seq, phaseDays);
    }

    /**
     * 取日期最终使用的阶段底色。
     * <p>
     * 实际记录日优先使用实色数组；其余日期用预测色（半透明）。
     * 关闭阶段底色或数组未设置时返回 0（不绘制）。
     */
    private int getPeriodColorForDate(Calendar date) {
        Integer actualPhase;
        if (!this.showPeriodColors || this.periodColors == null) {
            return 0;
        }
        String key = this.formatDate(date);
        Integer n = actualPhase = this.actualPhaseMap != null ? this.actualPhaseMap.get(key) : null;
        if (actualPhase != null && this.actualPhaseColors != null && actualPhase >= 0 && actualPhase < this.actualPhaseColors.length) {
            return this.actualPhaseColors[actualPhase];
        }
        int phaseIndex = this.getPredictedPhaseIndex(date);
        if (phaseIndex < 0) {
            return 0;
        }
        return phaseIndex < this.periodColors.length ? this.periodColors[phaseIndex] : 0;
    }

    /** 该日期是否存在用户实际记录（actualPhaseMap 有值），决定是否绘制“已记录”指示 */
    private boolean isActualRecord(String dateKey) {
        return this.actualPhaseMap != null && this.actualPhaseMap.containsKey(dateKey);
    }

    /**
     * 重新读取“今天”。
     * <p>应用跨零点仍在运行时，构造时缓存的 today 会停留在昨天，
     * 造成今日高亮消失、当天被误判为未来日期。绘制前对齐即可自动恢复。
     */
    private void refreshToday() {
        if (this.today == null) {
            this.today = Calendar.getInstance();
        } else {
            this.today.setTimeInMillis(System.currentTimeMillis());
        }
    }

    /** 切到上一月（不改变选中日期） */
    public void goToPreviousMonth() {
        this.currentMonth.add(2, -1);
        this.refreshCalendar();
    }

    /** 切到下一月（不改变选中日期） */
    public void goToNextMonth() {
        this.currentMonth.add(2, 1);
        this.refreshCalendar();
    }

    /** 回到今天：同时重置展示月份与选中日期，并通知月份变化 */
    public void goToToday() {
        this.currentMonth.setTime(new Date());
        this.selectedDate.setTime(new Date());
        this.refreshCalendar();
        if (this.calendarActionListener != null) {
            this.calendarActionListener.onMonthChanged((Calendar)this.currentMonth.clone());
        }
    }

    /** 跳转到指定日期（会同步切换月份并触发日期选择回调） */
    public void goToDate(Calendar date) {
        if (date != null) {
            this.setSelectedDate(date);
        }
    }

    /** 添加一条只有标题与标签的简单事件 */
    public void addEvent(Calendar date, String title, String tag) {
        this.addEvent(date, title, "", tag, 0, false, null, null);
    }

    /**
     * 添加一条完整事件。
     * <p>
     * 副作用：直接写入内存中的 eventMap（不落库，持久化由调用方负责），并按重要度重排当天的列表。
     * 日期或标题为空时静默忽略。
     *
     * @param color2      自定义颜色，0 表示按 tag 取默认色
     * @param isImportant 是否显示重要标记
     */
    public void addEvent(Calendar date, String title, String description, String tag, int color2, boolean isImportant, Calendar startTime, Calendar endTime) {
        if (date == null || title == null || title.isEmpty()) {
            return;
        }
        String dateKey = this.formatDate(date);
        List events = this.eventMap.computeIfAbsent(dateKey, k -> new ArrayList());
        EventData event = new EventData(System.currentTimeMillis() + "_" + events.size(), title, description != null ? description : "", tag, color2, isImportant, startTime, endTime);
        events.add(event);
        this.sortEvents(events);
        this.invalidate();
    }

    /**
     * 事件排序：重要度高的在前；重要度相同时用 id 兜底，保证每次绘制顺序稳定
     * （顺序不稳定会导致日程列表条目点击热区闪烁）。
     */
    private void sortEvents(List<EventData> events) {
        events.sort((e1, e2) -> {
            int importance2;
            int importance1 = this.getEventImportance(e1.tag);
            if (importance1 != (importance2 = this.getEventImportance(e2.tag))) {
                return Integer.compare(importance2, importance1);
            }
            if (e1.startTime != null && e2.startTime != null) {
                return e1.startTime.compareTo(e2.startTime);
            }
            if (e1.startTime != null) {
                return -1;
            }
            if (e2.startTime != null) {
                return 1;
            }
            return e1.title.compareTo(e2.title);
        });
    }

    /** 以 yyyy-MM-dd 字符串添加事件；格式不合法时静默忽略 */
    public void addEvent(String dateStr, String title, String tag) {
        try {
            String[] parts = dateStr.split("-");
            if (parts.length == 3) {
                Calendar cal = Calendar.getInstance();
                cal.set(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) - 1, Integer.parseInt(parts[2]));
                this.addEvent(cal, title, tag);
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** 删除指定日期下 id 匹配的事件；当天事件删空后顺带移除该日期的 key */
    public void removeEvent(Calendar date, String eventId) {
        if (date == null || eventId == null) {
            return;
        }
        String dateKey = this.formatDate(date);
        List<EventData> events = this.eventMap.get(dateKey);
        if (events != null) {
            events.removeIf(event -> event.id.equals(eventId));
            if (events.isEmpty()) {
                this.eventMap.remove(dateKey);
            }
            this.invalidate();
        }
    }

    /** 按标签批量删除事件（例如刷新预测记录前清掉旧的 suggestion 事件） */
    public void removeEventsByTag(String tag) {
        for (List<EventData> events : this.eventMap.values()) {
            events.removeIf(event -> event.tag.equals(tag));
        }
        this.eventMap.entrySet().removeIf(entry -> ((List)entry.getValue()).isEmpty());
        this.invalidate();
    }

    /** 清空全部事件（只影响内存） */
    public void clearAllEvents() {
        this.eventMap.clear();
        this.invalidate();
    }

    /**
     * 整体替换事件集合。
     * 注意：不拷贝每个事件对象，调用方后续修改 EventData 会直接反映到控件上。
     */
    public void setEvents(Map<String, List<EventData>> events) {
        this.eventMap.clear();
        if (events != null) {
            this.eventMap.putAll(events);
        }
        this.invalidate();
    }

    /** 获取某天的事件副本；date 为 null 或无事件时返回空列表（不会返回 null） */
    public List<EventData> getEventsForDate(Calendar date) {
        if (date == null) {
            return new ArrayList<EventData>();
        }
        String dateKey = this.formatDate(date);
        List<EventData> events = this.eventMap.get(dateKey);
        return events != null ? new ArrayList<EventData>(events) : new ArrayList();
    }

    /** 获取全部事件的浅拷贝（Map 与 List 为新容器，EventData 仍为原对象） */
    public Map<String, List<EventData>> getAllEvents() {
        return new HashMap<String, List<EventData>>(this.eventMap);
    }

    public void setShowEventList(boolean show) {
        if (this.showEventList != show) {
            this.showEventList = show;
            this.requestLayout();
            this.invalidate();
        }
    }

    public void setEventListHeight(float height) {
        if (this.eventListHeight != height) {
            this.eventListHeight = height;
            this.requestLayout();
            this.invalidate();
        }
    }

    /**
     * 查询日期所属的预测阶段。
     *
     * @return 0~3 的阶段下标；无法推算时返回 -1 表示“无阶段”，
     *         调用方应据此留白，不要当成经期（0）处理
     */
    public int getPeriodForDate(Calendar date) {
        return this.getPredictedPhaseIndex(date);
    }

    /**
     * 计算 date 是其所在阶段的第几天（从 1 开始）。
     * 无分段数据时返回 1，避免调用方拿到 0 后出现“第 0 天”。
     */
    public int getDaysInCurrentPeriod(Calendar date) {
        int segIndex = this.findSegmentIndex(date);
        if (segIndex < 0) {
            return 1;
        }
        int[] phaseDays = this.effectivePhaseDays(segIndex);
        int mod = this.getCycleSequenceMod(date, segIndex);
        int idx = this.phaseIndexBySequence(mod, phaseDays);
        return mod - this.accumulatedBefore(idx, phaseDays) + 1;
    }

    public Calendar getSelectedDate() {
        return (Calendar)this.selectedDate.clone();
    }

    public Calendar getCurrentMonth() {
        return (Calendar)this.currentMonth.clone();
    }

    public void setSelectedDate(Calendar date) {
        if (date == null) {
            return;
        }
        this.selectedDate.setTime(date.getTime());
        if (!this.isSameMonth(date, this.currentMonth)) {
            this.currentMonth.setTime(date.getTime());
            this.calculateRows();
            this.requestLayout();
            this.notifyMonthChanged();
        }
        this.invalidate();
    }

    public void setShowOtherMonthDays(boolean show) {
        if (this.showOtherMonthDays != show) {
            this.showOtherMonthDays = show;
            this.calculateRows();
            this.invalidate();
        }
    }

    public void setShowNavigationBar(boolean show) {
        if (this.showNavigationBar != show) {
            this.showNavigationBar = show;
            this.requestLayout();
            this.invalidate();
        }
    }

    public void setOnDateClickListener(OnDateClickListener listener) {
        this.dateClickListener = listener;
    }

    public void setOnDateLongClickListener(OnDateLongClickListener listener) {
        this.dateLongClickListener = listener;
    }

    public void setOnMonthChangeListener(OnMonthChangeListener listener) {
        this.monthChangeListener = listener;
    }

    public void setOnCalendarActionListener(OnCalendarActionListener listener) {
        this.calendarActionListener = listener;
    }

    private void refreshCalendar() {
        this.calculateRows();
        this.requestLayout();
        this.notifyMonthChanged();
        this.invalidate();
    }

    private Calendar getMonthFirstDay() {
        Calendar firstDay = (Calendar)this.currentMonth.clone();
        firstDay.set(5, 1);
        return firstDay;
    }

    private boolean isSameDay(Calendar cal1, Calendar cal2) {
        return cal1 != null && cal2 != null && cal1.get(1) == cal2.get(1) && cal1.get(2) == cal2.get(2) && cal1.get(5) == cal2.get(5);
    }

    private boolean isSameMonth(Calendar cal1, Calendar cal2) {
        return cal1 != null && cal2 != null && cal1.get(1) == cal2.get(1) && cal1.get(2) == cal2.get(2);
    }

    private String formatDate(Calendar calendar) {
        return String.format(Locale.getDefault(), "%04d-%02d-%02d", calendar.get(1), calendar.get(2) + 1, calendar.get(5));
    }

    private String formatDateShort(Calendar calendar) {
        return String.format(Locale.getDefault(), "%d\u6708%d\u65e5", calendar.get(2) + 1, calendar.get(5));
    }

    private String formatDateForAccessibility(Calendar calendar) {
        return String.format(Locale.getDefault(), "%d\u5e74%d\u6708%d\u65e5", calendar.get(1), calendar.get(2) + 1, calendar.get(5));
    }

    private String getMonthYearString(Calendar calendar) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy\u5e74MM\u6708", Locale.getDefault());
        return format.format(calendar.getTime());
    }

    private int dp2px(float dp) {
        return (int)TypedValue.applyDimension((int)1, (float)dp, (DisplayMetrics)this.getResources().getDisplayMetrics());
    }

    private int sp2px(float sp) {
        return (int)TypedValue.applyDimension((int)2, (float)sp, (DisplayMetrics)this.getResources().getDisplayMetrics());
    }

    private int darkenColor(int color2) {
        float[] hsv = new float[3];
        Color.colorToHSV((int)color2, (float[])hsv);
        hsv[2] = hsv[2] * 0.8f;
        return Color.HSVToColor((float[])hsv);
    }

    private void notifyMonthChanged() {
        if (this.monthChangeListener != null) {
            this.monthChangeListener.onMonthChanged((Calendar)this.currentMonth.clone());
        }
        if (this.calendarActionListener != null) {
            this.calendarActionListener.onMonthChanged((Calendar)this.currentMonth.clone());
        }
    }

    private void notifyDateClicked(Calendar date) {
        if (this.dateClickListener != null) {
            this.dateClickListener.onDateClick((Calendar)date.clone());
        }
    }

    private void notifyDateLongClicked(Calendar date) {
        if (this.dateLongClickListener != null) {
            this.dateLongClickListener.onDateLongClick((Calendar)date.clone());
        }
        if (this.calendarActionListener != null) {
            this.calendarActionListener.onDateLongClick((Calendar)date.clone());
        }
    }

    private void notifyPeriodToggle(boolean showPeriodColors) {
        if (this.calendarActionListener != null) {
            this.calendarActionListener.onPeriodToggle(showPeriodColors);
        }
    }

    /** 一条日历事件；tag 决定颜色与重要度，startTime/endTime 均为可空的时间点 */
    public static class EventData {
        public String id;
        public String title;
        public String description;
        public String tag;
        public int color;
        public boolean isImportant;
        public Calendar startTime;
        public Calendar endTime;
        public String date;

        public EventData() {
            this.id = System.currentTimeMillis() + "_event";
            this.title = "";
            this.description = "";
            this.tag = "";
            this.color = 0;
            this.isImportant = false;
        }

        public EventData(String title, String tag) {
            this(System.currentTimeMillis() + "_event", title, "", tag, 0, false, null, null);
        }

        public EventData(String id, String title, String description, String tag, int color2, boolean isImportant, Calendar startTime, Calendar endTime) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.tag = tag;
            this.color = color2;
            this.isImportant = isImportant;
            this.startTime = startTime != null ? (Calendar)startTime.clone() : null;
            this.endTime = endTime != null ? (Calendar)endTime.clone() : null;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public void setDate(String date) {
            this.date = date;
        }
    }

    /** 日程条目的触摸热区：绘制时记录，抬手时用于判断点中了哪条事件 */
    private static class EventItemRect {
        final RectF rect;
        final EventData event;
        final String dateKey;

        EventItemRect(RectF rect, EventData event, String dateKey) {
            this.rect = rect;
            this.event = event;
            this.dateKey = dateKey;
        }
    }

    /**
     * 日历交互动作的完整回调集合。
     * <p>
     * 与 OnDateClickListener 等单一回调并存：需要“日期选择 + 长按 + 月份切换 + 事件点击”
     * 全部能力的页面用本接口；只关心某一种交互时用对应的单一接口即可。
     */
    public static interface OnCalendarActionListener {
        public void onDateSelected(Calendar var1);

        public void onDateLongClick(Calendar var1);

        public void onMonthChanged(Calendar var1);

        public void onEventClicked(String var1, EventData var2);

        public void onPeriodToggle(boolean var1);

        public void onEventDisplayModeChanged(boolean var1);

        default public void onEventItemClick(EventData event) {
        }

        default public void onMonthTextClick() {
        }
    }

    /**
     * 周期分段：以某一天为起点的一段周期，记录该段的四阶段天数与周期总天数。
     */
    public static class PeriodSegment {
        public final Calendar start;
        public final int[] phaseDays;
        public final int totalCycleDay;

        public PeriodSegment(Calendar start, int[] phaseDays, int totalCycleDay) {
            this.start = (Calendar)start.clone();
            this.phaseDays = phaseDays;
            this.totalCycleDay = totalCycleDay;
        }
    }

    /** 日期点击回调 */
    public static interface OnDateClickListener {
        public void onDateClick(Calendar var1);
    }

    /** 日期长按回调 */
    public static interface OnDateLongClickListener {
        public void onDateLongClick(Calendar var1);
    }

    /** 月份切换回调（上一月/下一月/回到今天/跨月选日都会触发） */
    public static interface OnMonthChangeListener {
        public void onMonthChanged(Calendar var1);
    }
}
