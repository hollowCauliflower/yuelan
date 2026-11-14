package work.huacai.yuelan.helper;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import work.huacai.yuelan.dao.impl.MenstrualCycleRecordDaoImpl;
import work.huacai.yuelan.model.MenstrualCycleRecord;
import work.huacai.yuelan.model.Reminder;

/**
 * 周期节点日期计算器。
 * <p>
 * 把"按周期节点提醒"的节点（经期开始、排卵期开始等）换算成具体日期：
 * 以最近一条周期设置为准，当前周期起点由
 * {@link CycleCalculator#getCurrentCycleStart(Date, int)} 推算（与日历预测同源），
 * 节点在当前周期内的偏移由阶段天数数组决定；节点日期早于今天时顺延到下一周期。
 * <p>
 * 没有任何周期设置记录时返回 null / 空列表，调用方按"无法计算"处理
 * （周期节点提醒将不会被安排，列表中提示"完成周期设置后生效"）。
 */
public final class ReminderCycleHelper {

    private ReminderCycleHelper() {
    }

    /**
     * 计算某个周期节点下一次发生的日期。
     *
     * @param cycleNode 节点类型，见 {@link Reminder#NODE_PERIOD_START} 等常量
     * @return 下一次节点日期（当天 0 点）；无周期设置或节点非法时返回 null
     */
    public static Date nextNodeDate(int cycleNode) {
        MenstrualCycleRecord record = new MenstrualCycleRecordDaoImpl().selectLast();
        if (record == null || record.getStartTime() == null || record.getTotalCycleDay() <= 0) {
            return null;
        }
        int total = record.getTotalCycleDay();
        int[] phases = CycleCalculator.calculatePhaseDays(record.getMenstrualPeriodDay(), total);
        int offset = nodeOffset(cycleNode, phases, total);
        if (offset < 0) {
            return null;
        }

        Date cycleStart = CycleCalculator.getCurrentCycleStart(record.getStartTime(), total);
        if (cycleStart == null) {
            return null;
        }

        Date today = CycleCalculator.normalizeDate(new Date());
        Calendar cal = Calendar.getInstance();
        cal.setTime(cycleStart);
        cal.add(Calendar.DAY_OF_MONTH, offset);
        Date nodeDate = CycleCalculator.normalizeDate(cal.getTime());
        // 本周期的节点已过 → 顺延一个周期
        while (nodeDate.before(today)) {
            cal.add(Calendar.DAY_OF_MONTH, total);
            nodeDate = CycleCalculator.normalizeDate(cal.getTime());
        }
        return nodeDate;
    }

    /**
     * 计算某个周期节点在指定日期区间内的所有发生日期（按周期循环展开）。
     *
     * @param cycleNode 节点类型
     * @param start     区间起点（含）
     * @param end       区间终点（含）
     * @return 区间内的节点日期列表；无周期设置时返回空列表
     */
    public static List<Date> nodeDatesBetween(int cycleNode, Date start, Date end) {
        List<Date> result = new ArrayList<>();
        if (start == null || end == null || start.after(end)) {
            return result;
        }
        Date first = nextNodeDate(cycleNode);
        if (first == null) {
            return result;
        }
        MenstrualCycleRecord record = new MenstrualCycleRecordDaoImpl().selectLast();
        if (record == null || record.getTotalCycleDay() <= 0) {
            return result;
        }
        int total = record.getTotalCycleDay();

        // 从 nextNodeDate 向前回退到 start 之前的最近一个节点，再逐个向后展开
        Calendar cal = Calendar.getInstance();
        cal.setTime(first);
        while (cal.getTime().after(start)) {
            cal.add(Calendar.DAY_OF_MONTH, -total);
        }
        // 现在 cal <= start，逐个向后收集区间内节点
        Date cursor = CycleCalculator.normalizeDate(cal.getTime());
        while (!cursor.after(end)) {
            if (!cursor.before(start)) {
                result.add(cursor);
            }
            cal.add(Calendar.DAY_OF_MONTH, total);
            cursor = CycleCalculator.normalizeDate(cal.getTime());
        }
        return result;
    }

    /**
     * 阶段区间对应的起止节点（结束节点不含在区间内）。
     *
     * @param phaseIndex 阶段下标（0 经期、1 卵泡期、2 排卵期、3 黄体期）
     * @return {起始节点, 结束节点}；下标非法时返回 null
     */
    public static int[] phaseSpanNodes(int phaseIndex) {
        switch (phaseIndex) {
            case 0:
                return new int[]{Reminder.NODE_PERIOD_START, Reminder.NODE_FOLLICLE_START};
            case 1:
                return new int[]{Reminder.NODE_FOLLICLE_START, Reminder.NODE_OVULATION_START};
            case 2:
                return new int[]{Reminder.NODE_OVULATION_START, Reminder.NODE_LUTEAL_START};
            case 3:
                return new int[]{Reminder.NODE_LUTEAL_START, Reminder.NODE_PERIOD_START};
            default:
                return null;
        }
    }

    /**
     * 由区间起点节点反推阶段下标，用于编辑已保存的阶段区间提醒。
     *
     * @param startNode 区间起点节点
     * @return 阶段下标（0~3）；无法匹配时返回 -1
     */
    public static int spanPhaseIndex(int startNode) {
        switch (startNode) {
            case Reminder.NODE_PERIOD_START:
                return 0;
            case Reminder.NODE_FOLLICLE_START:
                return 1;
            case Reminder.NODE_OVULATION_START:
                return 2;
            case Reminder.NODE_LUTEAL_START:
                return 3;
            default:
                return -1;
        }
    }

    /**
     * 阶段区间模式：计算下一次区间内的提醒时间。
     * <p>
     * 从今天开始逐日检查是否落在区间内（相对周期起点的偏移），
     * 命中且「日期 + 提醒时间」还没过去就返回；今天已过则顺延到区间内的下一天。
     *
     * @param reminder  阶段区间提醒
     * @param nowMillis 当前时间戳
     * @return 下一次触发时间；无周期设置、区间非法或时间格式异常时返回 null
     */
    public static Date nextSpanOccurrence(Reminder reminder, long nowMillis) {
        if (reminder == null || !reminder.isSpanMode()) {
            return null;
        }
        int[] offsets = spanOffsets(reminder);
        int[] time = parseTime(reminder.getAlarmTime());
        Date cycleStart = currentCycleStart();
        if (offsets == null || time == null || cycleStart == null) {
            return null;
        }
        int total = offsets[0];
        int startOffset = offsets[1];
        int endOffset = offsets[2];

        Calendar base = Calendar.getInstance();
        base.setTime(cycleStart);
        Calendar cursor = Calendar.getInstance();
        cursor.setTimeInMillis(nowMillis);
        normalize(cursor);
        if (cursor.before(base)) {
            cursor.setTime(base.getTime());
        }

        for (int i = 0; i <= total * 2 + 7; i++) {
            int offset = (int) Math.floorMod(daysBetween(base, cursor), (long) total);
            if (offset >= startOffset && offset < endOffset) {
                Calendar candidate = Calendar.getInstance();
                candidate.setTime(cursor.getTime());
                candidate.set(Calendar.HOUR_OF_DAY, time[0]);
                candidate.set(Calendar.MINUTE, time[1]);
                candidate.set(Calendar.SECOND, 0);
                candidate.set(Calendar.MILLISECOND, 0);
                if (candidate.getTimeInMillis() > nowMillis) {
                    return candidate.getTime();
                }
            }
            cursor.add(Calendar.DAY_OF_MONTH, 1);
        }
        return null;
    }

    /**
     * 阶段区间模式：列出日期区间内所有落在提醒区间中的日期（用于日历标记）。
     *
     * @param reminder 阶段区间提醒
     * @param start    区间起点（含）
     * @param end      区间终点（含）
     * @return 命中的日期列表，按时间升序
     */
    public static List<Date> spanDatesBetween(Reminder reminder, Date start, Date end) {
        List<Date> result = new ArrayList<>();
        if (reminder == null || !reminder.isSpanMode()
                || start == null || end == null || start.after(end)) {
            return result;
        }
        int[] offsets = spanOffsets(reminder);
        Date cycleStart = currentCycleStart();
        if (offsets == null || cycleStart == null) {
            return result;
        }
        int total = offsets[0];
        int startOffset = offsets[1];
        int endOffset = offsets[2];

        Calendar base = Calendar.getInstance();
        base.setTime(cycleStart);
        Calendar cursor = Calendar.getInstance();
        cursor.setTime(start);
        normalize(cursor);
        Calendar last = Calendar.getInstance();
        last.setTime(end);
        normalize(last);

        while (!cursor.after(last)) {
            int offset = (int) Math.floorMod(daysBetween(base, cursor), (long) total);
            if (offset >= startOffset && offset < endOffset) {
                result.add(CycleCalculator.normalizeDate(cursor.getTime()));
            }
            cursor.add(Calendar.DAY_OF_MONTH, 1);
        }
        return result;
    }

    /** 当前周期起点（无周期设置时返回 null） */
    private static Date currentCycleStart() {
        MenstrualCycleRecord record = new MenstrualCycleRecordDaoImpl().selectLast();
        if (record == null || record.getStartTime() == null || record.getTotalCycleDay() <= 0) {
            return null;
        }
        return CycleCalculator.getCurrentCycleStart(record.getStartTime(), record.getTotalCycleDay());
    }

    /**
     * 计算阶段区间的偏移范围。
     *
     * @return {周期总天数, 起始偏移, 结束偏移（不含）}；无周期设置或区间非法时返回 null
     */
    private static int[] spanOffsets(Reminder reminder) {
        MenstrualCycleRecord record = new MenstrualCycleRecordDaoImpl().selectLast();
        if (record == null || record.getStartTime() == null || record.getTotalCycleDay() <= 0) {
            return null;
        }
        int total = record.getTotalCycleDay();
        int[] phases = CycleCalculator.calculatePhaseDays(record.getMenstrualPeriodDay(), total);
        int start = nodeOffset(reminder.getCycleNode(), phases, total);
        int end = spanEndOffset(reminder.getCycleNodeEnd(), phases, total);
        if (start < 0 || end <= start) {
            return null;
        }
        return new int[]{total, start, Math.min(end, total)};
    }

    /**
     * 区间结束节点对应的偏移；{@link Reminder#NODE_PERIOD_START} 作为结束节点时表示下一周期起点，
     * 即整个周期的末尾。
     */
    private static int spanEndOffset(int endNode, int[] phases, int total) {
        if (endNode == Reminder.NODE_PERIOD_START) {
            return total;
        }
        return nodeOffset(endNode, phases, total);
    }

    /** 解析 HH:mm，返回 {hour, minute}；格式非法返回 null */
    private static int[] parseTime(String time) {
        if (time == null) {
            return null;
        }
        String[] parts = time.split(":");
        if (parts.length != 2) {
            return null;
        }
        try {
            int hour = Integer.parseInt(parts[0].trim());
            int minute = Integer.parseInt(parts[1].trim());
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
                return null;
            }
            return new int[]{hour, minute};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 两个日期相差的天数（忽略时分秒） */
    private static long daysBetween(Calendar start, Calendar end) {
        long diff = end.getTimeInMillis() - start.getTimeInMillis();
        return Math.round(diff / (24f * 60 * 60 * 1000));
    }

    /** 归零时分秒，只保留日期部分 */
    private static void normalize(Calendar calendar) {
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
    }

    /**
     * 节点在当前周期内的偏移天数（相对周期起点，0 起）。
     *
     * @param cycleNode 节点类型
     * @param phases    阶段天数数组（经期/卵泡/排卵/黄体）
     * @param total     周期总天数
     * @return 偏移天数；未知节点返回 -1
     */
    private static int nodeOffset(int cycleNode, int[] phases, int total) {
        switch (cycleNode) {
            case Reminder.NODE_PERIOD_START:
                return 0;
            case Reminder.NODE_PERIOD_END:
                return Math.max(phases[0] - 1, 0);
            case Reminder.NODE_FOLLICLE_START:
                return phases[0];
            case Reminder.NODE_OVULATION_START:
                return phases[0] + phases[1];
            case Reminder.NODE_LUTEAL_START:
                return phases[0] + phases[1] + phases[2];
            case Reminder.NODE_BEFORE_PERIOD:
                return Math.max(total - 3, 0);
            default:
                return -1;
        }
    }
}
