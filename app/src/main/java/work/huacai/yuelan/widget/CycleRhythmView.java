package work.huacai.yuelan.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

import work.huacai.yuelan.R;

/**
 * 周期节律条形图。
 * <p>
 * 每一行代表一个"完整周期"：条形长度与该轮周期天数成正比（所有行共用同一比例尺），
 * 条形最左侧的粉色段表示经期天数。这样既能一眼看出周期长短的变化趋势，
 * 也能看出经期在整个周期中的占比，比单纯罗列日期更直观。
 * <p>
 * 仅做展示，无交互；数据由统计页按经期切分结果填充。
 */
public class CycleRhythmView extends View {

    /** 一行数据：标签（如"第3轮"）+ 本轮周期天数 + 经期天数 */
    public static class Item {
        public final String label;
        public final int cycleDays;
        public final int periodDays;

        public Item(String label, int cycleDays, int periodDays) {
            this.label = label;
            this.cycleDays = cycleDays;
            this.periodDays = periodDays;
        }
    }

    /** 每行高度（dp） */
    private static final float ROW_HEIGHT_DP = 38f;
    /** 条形高度（dp） */
    private static final float BAR_HEIGHT_DP = 16f;
    /** 左侧标签列宽（dp） */
    private static final float LABEL_WIDTH_DP = 52f;
    /** 右侧数值列宽（dp） */
    private static final float VALUE_WIDTH_DP = 60f;
    /** 最短条形长度（dp），避免周期很短时条形缩成一个点 */
    private static final float MIN_BAR_WIDTH_DP = 44f;

    private final List<Item> items = new ArrayList<>();
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    /** 周期部分（非经期）的浅蓝灰，保证在白色卡片上有足够对比度 */
    private int cycleColor;
    /** 经期部分的品牌粉 */
    private int periodColor;
    /** 标签与数值文字色 */
    private int textColor;

    public CycleRhythmView(Context context) {
        this(context, null);
    }

    public CycleRhythmView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CycleRhythmView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        cycleColor = Color.parseColor("#DCE8F5");
        periodColor = Color.parseColor("#FF6B93");
        textColor = ContextCompat.getColor(context, R.color.text_sub);
        textPaint.setTextSize(dp(12));
    }

    /**
     * 填充数据并重新测量高度。列表为空时高度为 0（卡片本身也应隐藏）。
     *
     * @param newItems 周期条目，按时间正序
     */
    public void setItems(@Nullable List<Item> newItems) {
        items.clear();
        if (newItems != null) {
            items.addAll(newItems);
        }
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int desiredHeight = (int) Math.ceil(items.size() * dp(ROW_HEIGHT_DP));
        setMeasuredDimension(width, resolveSize(desiredHeight, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (items.isEmpty()) {
            return;
        }

        float labelWidth = dp(LABEL_WIDTH_DP);
        float valueWidth = dp(VALUE_WIDTH_DP);
        float barHeight = dp(BAR_HEIGHT_DP);
        float rowHeight = dp(ROW_HEIGHT_DP);
        float trackLeft = labelWidth;
        float trackWidth = Math.max(dp(MIN_BAR_WIDTH_DP),
                getWidth() - labelWidth - valueWidth);

        // 所有行共用同一比例尺，条形越长代表周期越长
        int maxCycleDays = 1;
        for (Item item : items) {
            maxCycleDays = Math.max(maxCycleDays, item.cycleDays);
        }

        float radius = barHeight / 2f;
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            float centerY = rowHeight * i + rowHeight / 2f;

            // 左侧轮次标签
            textPaint.setColor(ContextCompat.getColor(getContext(), R.color.text_title));
            textPaint.setTextSize(dp(12));
            drawTextCentered(canvas, item.label, 0, labelWidth - dp(6), centerY);

            float barWidth = trackWidth * item.cycleDays / maxCycleDays;
            barWidth = Math.max(barWidth, dp(MIN_BAR_WIDTH_DP));

            // 整条周期背景
            rect.set(trackLeft, centerY - barHeight / 2f,
                    trackLeft + barWidth, centerY + barHeight / 2f);
            barPaint.setColor(cycleColor);
            canvas.drawRoundRect(rect, radius, radius, barPaint);

            // 经期段（粉色），按经期天数 / 周期天数取比例
            float periodRatio = item.cycleDays > 0
                    ? Math.min(1f, (float) item.periodDays / item.cycleDays)
                    : 0f;
            float periodWidth = Math.max(barHeight, barWidth * periodRatio);
            rect.set(trackLeft, centerY - barHeight / 2f,
                    trackLeft + periodWidth, centerY + barHeight / 2f);
            barPaint.setColor(periodColor);
            canvas.drawRoundRect(rect, radius, radius, barPaint);

            // 右侧"28 天"数值
            textPaint.setColor(textColor);
            textPaint.setTextSize(dp(12));
            String value = getContext().getString(R.string.statistics_day_count, item.cycleDays);
            float valueX = trackLeft + trackWidth + valueWidth - dp(4)
                    - textPaint.measureText(value);
            canvas.drawText(value, valueX, centerY - (textPaint.ascent() + textPaint.descent()) / 2f,
                    textPaint);
        }
    }

    /** 在指定水平区间内居中绘制单行文字（按垂直中心对齐） */
    private void drawTextCentered(Canvas canvas, String text, float left, float right,
                                  float centerY) {
        float textWidth = textPaint.measureText(text);
        float x = left + (right - left - textWidth) / 2f;
        canvas.drawText(text, x, centerY - (textPaint.ascent() + textPaint.descent()) / 2f,
                textPaint);
    }

    /** dp → px */
    private float dp(float dp) {
        return getResources().getDisplayMetrics().density * dp;
    }
}
