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
