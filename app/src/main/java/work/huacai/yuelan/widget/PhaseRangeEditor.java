package work.huacai.yuelan.widget;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import work.huacai.yuelan.R;
import work.huacai.yuelan.dao.MenstrualRecordDao;
import work.huacai.yuelan.dao.PhaseAnchorDao;
import work.huacai.yuelan.dao.impl.MenstrualCycleRecordDaoImpl;
import work.huacai.yuelan.dao.impl.MenstrualRecordDaoImpl;
import work.huacai.yuelan.dao.impl.PhaseAnchorDaoImpl;
import work.huacai.yuelan.helper.CycleCalculator;
import work.huacai.yuelan.helper.PhaseContinuityHelper;
import work.huacai.yuelan.model.MenstrualCycle;
import work.huacai.yuelan.model.MenstrualRecord;
import work.huacai.yuelan.model.PhaseAnchor;

/**
 * 阶段范围编辑器（批量涂抹版）。
 * <p>
 * 用于替代"顺延后续预测"的一键重排。弹窗把<b>被修改日 → 今天</b>的日期按自然月铺成
 * 一张小日历：顶部是四个阶段"画笔"，点选画笔后<b>点按或滑过日期</b>即可批量涂色，
 * 比逐天点选快得多，也更直观。
 * <p>
 * 保存前仍做<b>顺序校验</b>：相邻两天的阶段必须是"同阶段"或"顺序推进一格"
 * （月经期 → 卵泡期 → 排卵期 → 黄体期 → 月经期）；出现孤岛 / 倒序 / 跳阶段时
 * 弹窗提示并且不允许保存，避免写入不连贯的数据。
 * <p>
 * 保存成功后会：把范围内每一天写成"用户实际记录"，并清除旧锚点；
 * 若最后一天不是经期，则在最后一天写入新的阶段锚点，让今天之后预测仍按用户选择的顺序顺延。
 */
public final class PhaseRangeEditor {

    /** 一次最多可编辑的天数，避免误操作一次写入过多记录 */
    private static final int MAX_RANGE_DAYS = 366;
    /** 日期格子高度（dp） */
    private static final int CELL_HEIGHT_DP = 38;

    /** 保存完成回调：由调用方负责重算预测并刷新界面 */
    public interface OnSavedListener {
        void onSaved();
    }

    private PhaseRangeEditor() {
        // 工具类禁止实例化
    }

    /** 日历格子里的一天：日期 id + 当前选择的阶段 */
    private static class DayEntry {
        final Date date;
        final String dateId;
        MenstrualCycle phase;

        DayEntry(Date date, MenstrualCycle phase) {
            this.date = date;
            this.dateId = CycleCalculator.formatDateId(date);
            this.phase = phase;
        }
    }

    /**
     * 弹出阶段范围编辑器。
     *
     * @param context    上下文（建议传 Activity）
     * @param fromDateId 起始日期 id（yyyyMMdd），通常是用户刚修改的那一天
     * @param listener   保存成功后的回调，可为 null
     */
    public static void show(@NonNull Context context, @NonNull String fromDateId,
                            OnSavedListener listener) {
        Date from = CycleCalculator.parseDateId(fromDateId);
        if (from == null) {
            return;
        }
        from = CycleCalculator.normalizeDate(from);

        // 未来日期不允许编辑；范围始终是 [起始日, 今天]
        Date today = CycleCalculator.normalizeDate(new Date());
        Date start = from.after(today) ? today : from;
        Date end = today;

        // 范围过长时只保留从起始日往后的 MAX_RANGE_DAYS 天，避免一次写入几年记录
        Calendar capCal = Calendar.getInstance();
        capCal.setTime(start);
        capCal.add(Calendar.DAY_OF_MONTH, MAX_RANGE_DAYS - 1);
        Date capped = CycleCalculator.normalizeDate(capCal.getTime());
        if (capped.before(end)) {
            end = capped;
        }

        List<DayEntry> days = buildDays(context, start, end);
        if (days.isEmpty()) {
            return;
        }

        MenstrualRecordDao recordDao = new MenstrualRecordDaoImpl();
        PhaseAnchorDao anchorDao = new PhaseAnchorDaoImpl();

        // ---------- 弹窗内容：范围标签 + 提示 + 调色板 + 统计 + 月历 ----------
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);

        boolean toToday = CycleCalculator.formatDateId(end)
                .equals(CycleCalculator.formatDateId(today));
        TextView rangeLabel = new TextView(context);
        rangeLabel.setText(toToday
                ? context.getString(R.string.phase_range_range_label,
                        CycleCalculator.formatDateDisplay(start))
                : context.getString(R.string.phase_range_range_label_range,
                        CycleCalculator.formatDateDisplay(start),
                        CycleCalculator.formatDateDisplay(end)));
        rangeLabel.setTextSize(13);
        rangeLabel.setTextColor(Color.parseColor("#FF6B93"));
        rangeLabel.setPadding(0, 0, 0, dp(context, 8));
        content.addView(rangeLabel);

        TextView hint = new TextView(context);
        hint.setText(R.string.phase_range_hint);
        hint.setTextSize(12);
        hint.setTextColor(ContextCompat.getColor(context, R.color.text_hint));
        hint.setLineSpacing(0, 1.2f);
        hint.setPadding(0, 0, 0, dp(context, 10));
        content.addView(hint);

        // 画笔：四个阶段色块，选中后整块实色 + 白色描边
        MenstrualCycle[] brushes = phaseValues();
        int initialSelected = 0;
        for (int i = 0; i < brushes.length; i++) {
            if (brushes[i] == days.get(0).phase) {
                initialSelected = i;
                break;
            }
        }
        final int[] selectedIndex = {initialSelected};
        LinearLayout palette = new LinearLayout(context);
        palette.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < brushes.length; i++) {
            MenstrualCycle brush = brushes[i];
            TextView chip = new TextView(context);
            chip.setTextSize(12);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(0, dp(context, 9), 0, dp(context, 9));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) {
                lp.setMarginStart(dp(context, 6));
            }
            final int index = i;
            chip.setOnClickListener(v -> {
                selectedIndex[0] = index;
                for (int child = 0; child < palette.getChildCount(); child++) {
                    bindBrush((TextView) palette.getChildAt(child), brushes[child],
                            child == index, context);
                }
            });
            palette.addView(chip, lp);
        }
        for (int i = 0; i < brushes.length; i++) {
            bindBrush((TextView) palette.getChildAt(i), brushes[i], i == initialSelected, context);
        }
        content.addView(palette);

        // 实时统计：每天涂色后立即更新各阶段天数，帮助用户判断比例是否合理
        TextView summary = new TextView(context);
        summary.setTextSize(12);
        summary.setTextColor(ContextCompat.getColor(context, R.color.text_secondary));
        summary.setGravity(Gravity.CENTER);
        summary.setPadding(0, dp(context, 10), 0, dp(context, 6));
        content.addView(summary);

        // ---------- 月历：箭头翻月 + 星期表头 + 日期网格 ----------
        LinearLayout monthHeader = new LinearLayout(context);
        monthHeader.setOrientation(LinearLayout.HORIZONTAL);
        monthHeader.setGravity(Gravity.CENTER_VERTICAL);

        TextView prevMonth = createNavButton(context);
        prevMonth.setText("‹");
        TextView nextMonth = createNavButton(context);
        nextMonth.setText("›");
        TextView monthTitle = new TextView(context);
        monthTitle.setTextSize(14);
        monthTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        monthTitle.setTextColor(ContextCompat.getColor(context, R.color.text_title));
        monthTitle.setGravity(Gravity.CENTER);

        monthHeader.addView(prevMonth, new LinearLayout.LayoutParams(dp(context, 40),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        monthHeader.addView(monthTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        monthHeader.addView(nextMonth, new LinearLayout.LayoutParams(dp(context, 40),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(monthHeader, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        content.addView(createWeekdayHeader(context), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        PhaseGrid grid = new PhaseGrid(context);
        content.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Map<String, DayEntry> entryByDate = new HashMap<>();
        for (DayEntry entry : days) {
            entryByDate.put(entry.dateId, entry);
        }

        Calendar monthCursor = firstDayOfMonth(start);
        Calendar lastMonth = firstDayOfMonth(end);

        final Runnable[] renderMonth = new Runnable[1];
        renderMonth[0] = () -> {
            monthTitle.setText(context.getString(R.string.phase_range_month_title,
                    monthCursor.get(Calendar.YEAR), monthCursor.get(Calendar.MONTH) + 1));
            prevMonth.setEnabled(!sameMonth(monthCursor, firstDayOfMonth(start)));
            prevMonth.setAlpha(prevMonth.isEnabled() ? 1.0f : 0.35f);
            nextMonth.setEnabled(!sameMonth(monthCursor, lastMonth));
            nextMonth.setAlpha(nextMonth.isEnabled() ? 1.0f : 0.35f);

            grid.renderMonth(monthCursor, entryByDate, today, context);
            updateSummary(context, summary, days);
        };

        prevMonth.setOnClickListener(v -> {
            monthCursor.add(Calendar.MONTH, -1);
            renderMonth[0].run();
        });
        nextMonth.setOnClickListener(v -> {
            monthCursor.add(Calendar.MONTH, 1);
            renderMonth[0].run();
        });

        grid.setOnPaintListener(entry -> {
            entry.phase = brushes[selectedIndex[0]];
            grid.refresh(entry);
            updateSummary(context, summary, days);
        });

        renderMonth[0].run();

        // 结束日为今天时标题用"至今天"，否则展示明确的起止日期；
        // 日期范围放在正文首行，标题保持短小，避免长标题在弹窗里折行
        AppDialogFactory.showForm(context, context.getString(R.string.phase_range_title), content,
                R.string.dialog_save, R.string.dialog_cancel,
                dialog -> saveRange(context, fromDateId, days, recordDao, anchorDao, dialog, listener));
    }

    /**
     * 构建范围内每一天的默认阶段：有实际记录用实际阶段，
     * 否则按"分段 + 锚点"推算预测阶段；无法推算时默认卵泡期。
     * <p>通过 {@link PhaseContinuityHelper#loadSnapshot} 一次性读库，避免逐日查询。
     */
    private static List<DayEntry> buildDays(Context context, Date from, Date end) {
        List<DayEntry> days = new ArrayList<>();
        MenstrualRecordDao recordDao = new MenstrualRecordDaoImpl();
        PhaseContinuityHelper.PhaseSnapshot snapshot = PhaseContinuityHelper.loadSnapshot(
                recordDao, new MenstrualCycleRecordDaoImpl(), new PhaseAnchorDaoImpl());

        Calendar cal = Calendar.getInstance();
        cal.setTime(from);
        int count = 0;
        while (!cal.getTime().after(end) && count < MAX_RANGE_DAYS) {
            Date date = CycleCalculator.normalizeDate(cal.getTime());
            MenstrualCycle phase = snapshot.phaseOf(date);
            if (phase == null) {
                // 起始日早于首个周期分段时没有预测可参考，用卵泡期做中性默认值
                phase = MenstrualCycle.FOLLICULAR_PHASE;
            }
            days.add(new DayEntry(date, phase));
            cal.add(Calendar.DAY_OF_MONTH, 1);
            count++;
        }
        return days;
    }

    /**
     * 校验并保存范围。
     * <p>
     * 校验失败时保持弹窗打开，只弹一条提示，避免用户丢失已做的涂抹结果；
     * 若起始补签日被涂成月经期，则提示后果并允许"只保存这一天"，
     * 不写分段、不动锚点，预测周期保持原样。
     *
     * @param fromDateId 进入编辑器时的补签日期（yyyyMMdd）
     */
    private static void saveRange(Context context, String fromDateId, List<DayEntry> days,
                                  MenstrualRecordDao recordDao, PhaseAnchorDao anchorDao,
                                  Dialog dialog, OnSavedListener listener) {
        String invalid = validate(context, days);
        if (invalid != null) {
            DayEntry fromEntry = findEntry(days, fromDateId);
            if (fromEntry != null && fromEntry.phase == MenstrualCycle.MENSTRUAL_PERIOD) {
                // 补签日为月经期但不连贯：提示后允许只保存该日
                AppDialogFactory.showConfirm(context,
                        context.getString(R.string.phase_range_invalid_title),
                        context.getString(R.string.phase_edit_luteal_to_period_message),
                        R.string.phase_edit_save_single_day, R.string.dialog_cancel,
                        () -> {
                            saveSingleDay(context, fromEntry, recordDao);
                            dialog.dismiss();
                            notifySaved(context, listener);
                        });
                return;
            }
            AppDialogFactory.showConfirm(context, context.getString(R.string.phase_range_invalid_title),
                    invalid, R.string.dialog_i_know, 0, null);
            return;
        }

        for (DayEntry entry : days) {
            MenstrualRecord record = recordDao.selectById(entry.dateId);
            boolean isNew = record == null;
            if (isNew) {
                record = new MenstrualRecord();
                record.setId(entry.dateId);
            }
            record.setMenstrualCycle(entry.phase);
            // 0 = 用户实际记录，重算预测时不会被覆盖
            record.setIsPrediction(0);
            record.setSuggestion(entry.phase.getTips(context));
            if (isNew) {
                recordDao.insert(record);
            } else {
                recordDao.updateRecord(record);
            }
        }

        // 重写锚点：让今天之后的预测继续按用户最后一次选择的阶段顺延，
        // 避免保存范围后明天又跳回旧的预测顺序
        anchorDao.clear();
        DayEntry last = days.get(days.size() - 1);
        if (last.phase != MenstrualCycle.MENSTRUAL_PERIOD) {
            anchorDao.upsert(new PhaseAnchor(last.date, last.phase.getIndex()));
        }

        dialog.dismiss();
        notifySaved(context, listener);
    }

    /** 在已构建的日期列表中查找指定日期。 */
    private static DayEntry findEntry(List<DayEntry> days, String dateId) {
        if (dateId == null) {
            return null;
        }
        for (DayEntry entry : days) {
            if (dateId.equals(entry.dateId)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * 只保存补签当天：写入/更新一条实际阶段记录，不清理也不写锚点，
     * 因此预测分段和基准日保持不动。
     */
    private static void saveSingleDay(Context context, DayEntry entry, MenstrualRecordDao recordDao) {
        MenstrualRecord record = recordDao.selectById(entry.dateId);
        boolean isNew = record == null;
        if (isNew) {
            record = new MenstrualRecord();
            record.setId(entry.dateId);
        }
        record.setMenstrualCycle(entry.phase);
        record.setIsPrediction(0);
        record.setSuggestion(entry.phase.getTips(context));
        if (isNew) {
            recordDao.insert(record);
        } else {
            recordDao.updateRecord(record);
        }
    }

    /** 保存完成后的回调：优先交给调用方刷新，否则用 Toast 提示。 */
    private static void notifySaved(Context context, OnSavedListener listener) {
        if (listener != null) {
            listener.onSaved();
        } else {
            Toast.makeText(context, R.string.phase_range_saved, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 校验范围内阶段顺序。
     *
     * @return 不连贯时返回给用户看的完整提示文案；全部合法返回 null
     */
    private static String validate(Context context, List<DayEntry> days) {
        for (int i = 1; i < days.size(); i++) {
            DayEntry prev = days.get(i - 1);
            DayEntry current = days.get(i);
            if (!isSequenceValid(prev.phase, current.phase)) {
                return context.getString(R.string.phase_range_invalid_message,
                        CycleCalculator.formatDateDisplay(prev.date),
                        prev.phase.getDisplayName(context),
                        CycleCalculator.formatDateDisplay(current.date),
                        current.phase.getDisplayName(context));
            }
        }
        return null;
    }

    /**
     * 判断"前一天阶段 → 后一天阶段"是否合法：只能保持不变或顺序推进一个阶段
     * （黄体期之后的下一个阶段是新一轮的月经期）。
     */
    private static boolean isSequenceValid(MenstrualCycle prev, MenstrualCycle next) {
        if (prev == null || next == null) {
            return true;
        }
        return next == prev || next.getIndex() == (prev.getIndex() + 1) % 4;
    }

    // ==================== 视图构建 ====================

    /** 设置画笔样式：选中为实色 + 白色描边 + 勾选前缀，未选中整体半透明 */
    private static void bindBrush(TextView chip, MenstrualCycle phase, boolean selected,
                                  Context context) {
        chip.setText(selected
                ? "✓ " + phase.getDisplayName(context)
                : phase.getDisplayName(context));
        chip.setTextColor(Color.WHITE);
        chip.setTypeface(null, selected ? android.graphics.Typeface.BOLD
                : android.graphics.Typeface.NORMAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ContextCompat.getColor(context, phaseColorRes(phase)));
        bg.setCornerRadius(dp(context, 12));
        if (selected) {
            bg.setStroke(dp(context, 2), Color.WHITE);
        }
        chip.setBackground(bg);
        chip.setAlpha(selected ? 1.0f : 0.5f);
    }

    /** 创建一个翻月用的箭头按钮 */
    private static TextView createNavButton(Context context) {
        TextView button = new TextView(context);
        button.setTextSize(20);
        button.setGravity(Gravity.CENTER);
        button.setTextColor(ContextCompat.getColor(context, R.color.primary_color));
        button.setPadding(0, dp(context, 4), 0, dp(context, 4));
        return button;
    }

    /** 星期表头（周一 → 周日），与下面对齐 7 列 */
    private static LinearLayout createWeekdayHeader(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(context, 4), 0, dp(context, 4));

        SimpleDateFormat weekdayFormat = new SimpleDateFormat("E", Locale.getDefault());
        Calendar cal = Calendar.getInstance();
        // 2024-01-01 是星期一，用它作为 7 个表头标签的基准
        cal.set(2024, Calendar.JANUARY, 1);
        for (int i = 0; i < 7; i++) {
            TextView tv = new TextView(context);
            tv.setText(weekdayFormat.format(cal.getTime()));
            tv.setTextSize(10);
            tv.setGravity(Gravity.CENTER);
            tv.setTextColor(ContextCompat.getColor(context, R.color.text_hint));
            row.addView(tv, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            cal.add(Calendar.DAY_OF_MONTH, 1);
        }
        return row;
    }

    /**
     * 实时统计各阶段天数：每行两项、共两行，避免在窄弹窗里出现"天"字单独折行。
     */
    private static void updateSummary(Context context, TextView summary, List<DayEntry> days) {
        int menstrual = 0, follicular = 0, ovulation = 0, luteal = 0;
        for (DayEntry entry : days) {
            switch (entry.phase) {
                case MENSTRUAL_PERIOD:
                    menstrual++;
                    break;
                case FOLLICULAR_PHASE:
                    follicular++;
                    break;
                case OVULATION_PERIOD:
                    ovulation++;
                    break;
                case LUTE_PHASE:
                    luteal++;
                    break;
                default:
                    break;
            }
        }
        String line1 = context.getString(R.string.phase_range_day_count,
                MenstrualCycle.MENSTRUAL_PERIOD.getDisplayName(context), menstrual)
                + " · " + context.getString(R.string.phase_range_day_count,
                MenstrualCycle.FOLLICULAR_PHASE.getDisplayName(context), follicular);
        String line2 = context.getString(R.string.phase_range_day_count,
                MenstrualCycle.OVULATION_PERIOD.getDisplayName(context), ovulation)
                + " · " + context.getString(R.string.phase_range_day_count,
                MenstrualCycle.LUTE_PHASE.getDisplayName(context), luteal);
        summary.setText(line1 + "\n" + line2);
    }

    /** 按需重绘单个格子（涂抹时调用） */
    static void styleCell(Context context, TextView cell, DayEntry entry, boolean isToday) {
        cell.setText(String.valueOf(entry.date.getDate()));
        cell.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ContextCompat.getColor(context, phaseColorRes(entry.phase)));
        bg.setCornerRadius(dp(context, 8));
        if (isToday) {
            bg.setStroke(dp(context, 2), ContextCompat.getColor(context, R.color.calendar_selection));
        }
        cell.setBackground(bg);
        cell.setAlpha(1.0f);
    }

    /** 范围外（该月内但不可编辑）的日期：浅灰文字、无底色，只作为日历上下文 */
    static void styleDisabledCell(Context context, TextView cell, int dayOfMonth,
                                  boolean isToday) {
        cell.setText(String.valueOf(dayOfMonth));
        cell.setTextColor(ContextCompat.getColor(context, R.color.text_disabled));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.TRANSPARENT);
        if (isToday) {
            bg.setStroke(dp(context, 1), ContextCompat.getColor(context, R.color.calendar_selection));
        }
        cell.setBackground(bg);
        cell.setAlpha(1.0f);
    }

    /** 四个阶段枚举，顺序与 getIndex() 一致 */
    private static MenstrualCycle[] phaseValues() {
        return new MenstrualCycle[]{
                MenstrualCycle.MENSTRUAL_PERIOD,
                MenstrualCycle.FOLLICULAR_PHASE,
                MenstrualCycle.OVULATION_PERIOD,
                MenstrualCycle.LUTE_PHASE
        };
    }

    /** 阶段对应的颜色资源 */
    private static int phaseColorRes(MenstrualCycle cycle) {
        switch (cycle) {
            case MENSTRUAL_PERIOD:
                return R.color.phase_menstrual;
            case FOLLICULAR_PHASE:
                return R.color.phase_follicular;
            case OVULATION_PERIOD:
                return R.color.phase_ovulation;
            case LUTE_PHASE:
                return R.color.phase_luteal;
            default:
                return R.color.gray;
        }
    }

    /** 取某日期所在月的 1 号（0 点） */
    private static Calendar firstDayOfMonth(Date date) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        cal.set(Calendar.DAY_OF_MONTH, 1);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal;
    }

    private static boolean sameMonth(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.MONTH) == b.get(Calendar.MONTH);
    }

    /** dp → px */
    private static int dp(Context context, float dp) {
        return (int) (context.getResources().getDisplayMetrics().density * dp);
    }

    /**
     * 可涂抹的月历网格。
     * <p>
     * 为了支持"按住滑过多个日期连续涂色"，触摸事件统一由网格本身处理：
     * 按下时命中某个可编辑日期即开始一次涂抹，移动过程中命中新格子就继续涂，
     * 抬手结束。这样不必给每个格子单独处理点击，也不会误触翻月。
     */
    private static final class PhaseGrid extends LinearLayout {

        interface OnPaintListener {
            void onPaint(DayEntry entry);
        }

        /** 已绑定日期 → 格子视图，用于涂抹后局部刷新 */
        private final Map<DayEntry, TextView> cellViews = new LinkedHashMap<>();
        private OnPaintListener paintListener;
        private boolean painting;
        /** 一次涂抹中最后涂过的日期，避免同一格子重复回调 */
        private String lastPaintedId;

        PhaseGrid(Context context) {
            super(context);
            setOrientation(VERTICAL);
        }

        void setOnPaintListener(OnPaintListener listener) {
            this.paintListener = listener;
        }

        /** 渲染某个月：7 列网格，范围外的日期灰显且不可涂 */
        void renderMonth(Calendar month, Map<String, DayEntry> entryByDate,
                         Date today, Context context) {
            removeAllViews();
            cellViews.clear();
            painting = false;
            lastPaintedId = null;

            Calendar day = (Calendar) month.clone();
            int offset = (day.get(Calendar.DAY_OF_WEEK) + 5) % 7; // 周一为第 0 列
            int daysInMonth = day.getActualMaximum(Calendar.DAY_OF_MONTH);

            LinearLayout row = null;
            int column = 0;
            for (int i = 0; i < offset; i++) {
                row = ensureRow(row);
                addCell(row, createEmptyCell(context));
                column++;
            }
            for (int d = 1; d <= daysInMonth; d++) {
                row = ensureRow(row);
                day.set(Calendar.DAY_OF_MONTH, d);
                Date date = CycleCalculator.normalizeDate(day.getTime());
                String dateId = CycleCalculator.formatDateId(date);
                boolean isToday = dateId.equals(CycleCalculator.formatDateId(today));
                DayEntry entry = entryByDate.get(dateId);

                TextView cell = createBaseCell(context);
                if (entry != null) {
                    styleCell(context, cell, entry, isToday);
                    cell.setTag(entry);
                    cellViews.put(entry, cell);
                } else {
                    styleDisabledCell(context, cell, d, isToday);
                }
                addCell(row, cell);
                column++;
                if (column % 7 == 0) {
                    row = null;
                }
            }

            // 补满最后一行，保证每行都有 7 个等宽格子
            if (row != null) {
                while (row.getChildCount() < 7) {
                    addCell(row, createEmptyCell(context));
                }
            }
        }

        private LinearLayout ensureRow(LinearLayout row) {
            if (row == null) {
                row = new LinearLayout(getContext());
                row.setOrientation(HORIZONTAL);
                row.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(getContext(), CELL_HEIGHT_DP)));
                addView(row);
            }
            return row;
        }

        private void addCell(LinearLayout row, TextView cell) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            lp.setMargins(dp(getContext(), 2), dp(getContext(), 2),
                    dp(getContext(), 2), dp(getContext(), 2));
            row.addView(cell, lp);
        }

        private TextView createBaseCell(Context context) {
            TextView cell = new TextView(context);
            cell.setTextSize(13);
            cell.setGravity(Gravity.CENTER);
            return cell;
        }

        private TextView createEmptyCell(Context context) {
            return createBaseCell(context);
        }

        /** 涂抹后局部刷新一个格子 */
        void refresh(DayEntry entry) {
            TextView cell = cellViews.get(entry);
            if (cell != null) {
                boolean isToday = entry.dateId.equals(CycleCalculator.formatDateId(
                        CycleCalculator.normalizeDate(new Date())));
                styleCell(getContext(), cell, entry, isToday);
            }
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                return findEntry(ev.getX(), ev.getY()) != null;
            }
            return painting;
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    DayEntry entry = findEntry(ev.getX(), ev.getY());
                    if (entry == null) {
                        return false;
                    }
                    painting = true;
                    lastPaintedId = null;
                    paint(entry);
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (!painting) {
                        return false;
                    }
                    DayEntry entry = findEntry(ev.getX(), ev.getY());
                    if (entry != null) {
                        paint(entry);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    painting = false;
                    lastPaintedId = null;
                    return true;
                default:
                    return super.onTouchEvent(ev);
            }
        }

        private void paint(DayEntry entry) {
            if (entry.dateId.equals(lastPaintedId)) {
                return;
            }
            lastPaintedId = entry.dateId;
            if (paintListener != null) {
                paintListener.onPaint(entry);
            }
        }

        /** 根据坐标找到命中的可编辑日期 */
        private DayEntry findEntry(float x, float y) {
            for (int i = 0; i < getChildCount(); i++) {
                View row = getChildAt(i);
                if (y < row.getTop() || y > row.getBottom()) {
                    continue;
                }
                ViewGroup rowGroup = (ViewGroup) row;
                for (int j = 0; j < rowGroup.getChildCount(); j++) {
                    View cell = rowGroup.getChildAt(j);
                    if (x >= cell.getLeft() && x <= cell.getRight()
                            && cell.getTag() instanceof DayEntry) {
                        return (DayEntry) cell.getTag();
                    }
                }
            }
            return null;
        }
    }
}
