package work.huacai.yuelan.helper;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import work.huacai.yuelan.dao.impl.MenstrualCycleRecordDaoImpl;
import work.huacai.yuelan.dao.impl.MenstrualRecordDaoImpl;
import work.huacai.yuelan.model.MenstrualCycle;
import work.huacai.yuelan.model.MenstrualCycleRecord;
import work.huacai.yuelan.model.MenstrualRecord;
import work.huacai.yuelan.model.Reminder;

/**
 * 周期节点日期计算器。
 * <p>
 * 把"按周期节点提醒"的节点（经期开始、排卵期开始等）换算成具体日期。
 * <p>
 * 关键口径：提醒命中的日期必须与日历/每日建议里<b>实际显示的阶段</b>完全一致。
 * 因此这里不复用"周期起点 + 固定偏移"的老算法，而是逐日取该日期的阶段：
 * <ul>
 *   <li>用户实际记录过阶段的日期，以记录为准；</li>
 *   <li>其余日期按最新周期设置推算（每个周期分段使用自己的起始日与长度）；</li>
 *   <li>周期长度变化由“实际经期记录形成的新分段”处理，不做阶段重排。</li>
 * </ul>
 * 没有任何周期设置记录时返回 null / 空列表，调用方按"无法计算"处理
 * （周期节点提醒将不会被安排，列表中提示"完成周期设置后生效"）。
 */
public final class ReminderCycleHelper {

    /** 向前搜索节点/区间的最大周期数，避免异常数据导致死循环 */
    private static final int MAX_CYCLE_LOOKAHEAD = 4;

    private ReminderCycleHelper() {
    }

    /**
     * 计算某个周期节点下一次发生的日期。
     *
     * @param cycleNode 节点类型，见 {@link Reminder#NODE_PERIOD_START} 等常量
     * @return 下一次节点日期（当天 0 点）；无周期设置或节点非法时返回 null
     */
    public static Date nextNodeDate(int cycleNode) {
        PhaseModel model = loadModel();
        if (model == null) {
            return null;
        }
        return nextNodeDateFrom(model, cycleNode,
                CycleCalculator.normalizeDate(new Date()), true);
    }

    /**
     * 计算某个周期节点严格晚于指定日期的下一次发生日期。
     *
     * @param cycleNode 节点类型
     * @param after     起始日期（不含）
     * @return 下一次节点日期（当天 0 点）；无周期设置或节点非法时返回 null
     */
    public static Date nextNodeDateAfter(int cycleNode, Date after) {
        PhaseModel model = loadModel();
        if (model == null || after == null) {
            return null;
        }
        return nextNodeDateFrom(model, cycleNode,
                CycleCalculator.normalizeDate(after), false);
    }

    /** 从指定日期开始（含/不含）向前找第一个命中的节点日期 */
    private static Date nextNodeDateFrom(PhaseModel model, int cycleNode,
                                         Date from, boolean inclusive) {
        Calendar cursor = Calendar.getInstance();
        cursor.setTime(from);
        if (!inclusive) {
            cursor.add(Calendar.DAY_OF_MONTH, 1);
        }
        int limit = latestTotal(model) * MAX_CYCLE_LOOKAHEAD + 7;
        for (int i = 0; i <= limit; i++) {
            Date day = CycleCalculator.normalizeDate(cursor.getTime());
            if (matchesNode(model, day, cycleNode)) {
                return day;
            }
            cursor.add(Calendar.DAY_OF_MONTH, 1);
        }
        return null;
    }

    /**
     * 某一天在界面上显示的阶段。
     * <p>实际记录优先，其次按周期设置推算；无法推算（如早于当前分段起点）返回 null。
     *
     * @param day 目标日期
     * @return 阶段；无法推算时返回 null
     */
    public static MenstrualCycle phaseOn(Date day) {
        PhaseModel model = loadModel();
        if (model == null || day == null) {
            return null;
        }
        int index = phaseIndexOn(model, CycleCalculator.normalizeDate(day));
        if (index < 0 || index >= MenstrualCycle.values().length) {
            return null;
        }
        return MenstrualCycle.getByIndex(index);
    }

    /**
     * 今天所在周期的起点（最近一次经期第一天）。
     *
     * @return 当前周期起点；今天之前没有可推算的周期时返回 null
     */
    public static Date currentPeriodStart() {
        PhaseModel model = loadModel();
        if (model == null) {
            return null;
        }
        Date today = CycleCalculator.normalizeDate(new Date());
        // 从今天开始逐日向回找“经期第一天”（阶段序列不会跨周期重排，
        // 周期长度变化由实际经期记录形成的新分段处理）
        Calendar cursor = Calendar.getInstance();
        cursor.setTime(today);
        int limit = latestTotal(model) * 2 + 7;
        for (int i = 0; i <= limit; i++) {
            Date day = CycleCalculator.normalizeDate(cursor.getTime());
            if (isPhaseStart(model, day, MenstrualCycle.MENSTRUAL_PERIOD)) {
                return day;
            }
            cursor.add(Calendar.DAY_OF_MONTH, -1);
        }
        return null;
    }

    /**
     * 下一次经期第一天（严格晚于今天）。
     *
     * @return 下次经期开始日；无周期设置时返回 null
     */
    public static Date nextPeriodStart() {
        PhaseModel model = loadModel();
        if (model == null) {
            return null;
        }
        return nextNodeDateFrom(model, Reminder.NODE_PERIOD_START,
                CycleCalculator.normalizeDate(new Date()), false);
    }

    /**
     * 下一次指定阶段的日期区间（闭区间）。
     *
     * @param phase 目标阶段
     * @param after 起始日期（不含）
     * @return {开始日期, 结束日期}；无法推算时返回 null
     */
    public static Date[] nextPhaseRange(MenstrualCycle phase, Date after) {
        PhaseModel model = loadModel();
        if (model == null || phase == null || after == null) {
            return null;
        }
        int target = phase.getIndex();
        Calendar cursor = Calendar.getInstance();
        cursor.setTime(CycleCalculator.normalizeDate(after));
        cursor.add(Calendar.DAY_OF_MONTH, 1);
        int limit = latestTotal(model) * 2 + 7;
        Date start = null;
        for (int i = 0; i <= limit; i++) {
            Date day = CycleCalculator.normalizeDate(cursor.getTime());
            if (phaseIndexOn(model, day) == target) {
                if (start == null) {
                    start = day;
                }
                // 找到该阶段的最后一天后返回
                if (phaseIndexOn(model, shiftDays(day, 1)) != target) {
                    return new Date[]{start, day};
                }
            } else if (start != null) {
                // 理论上不会走到这里（start 之后连续同阶段），兜底返回
                return new Date[]{start, shiftDays(day, -1)};
            }
            cursor.add(Calendar.DAY_OF_MONTH, 1);
        }
        return null;
    }

    /**
     * 今天之后的下一个阶段（与今天阶段不同）的日期区间（闭区间）。
     *
     * @return {开始日期, 结束日期}；无法推算时返回 null
     */
    public static Date[] nextPhaseRangeAfterToday() {
        PhaseModel model = loadModel();
        if (model == null) {
            return null;
        }
        Date today = CycleCalculator.normalizeDate(new Date());
        int currentIndex = phaseIndexOn(model, today);
        Calendar cursor = Calendar.getInstance();
        cursor.setTime(today);
        cursor.add(Calendar.DAY_OF_MONTH, 1);
        int limit = latestTotal(model) * 2 + 7;
        Date start = null;
        for (int i = 0; i <= limit; i++) {
            Date day = CycleCalculator.normalizeDate(cursor.getTime());
            int index = phaseIndexOn(model, day);
            if (index >= 0 && index != currentIndex) {
                if (start == null) {
                    start = day;
                }
                if (phaseIndexOn(model, shiftDays(day, 1)) != index) {
                    return new Date[]{start, day};
                }
            } else if (start != null) {
                return new Date[]{start, shiftDays(day, -1)};
            }
            cursor.add(Calendar.DAY_OF_MONTH, 1);
        }
        return null;
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
        PhaseModel model = loadModel();
        if (model == null || start == null || end == null || start.after(end)) {
            return result;
        }
        Date last = CycleCalculator.normalizeDate(end);
        Calendar cursor = Calendar.getInstance();
        cursor.setTime(CycleCalculator.normalizeDate(start));
        while (!cursor.getTime().after(last)) {
            Date day = CycleCalculator.normalizeDate(cursor.getTime());
            if (matchesNode(model, day, cycleNode)) {
                result.add(day);
            }
            cursor.add(Calendar.DAY_OF_MONTH, 1);
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
     * 逐日判断该日期显示的阶段是否落在区间内，命中且「日期 + 提醒时间」还没过去就返回；
     * 今天已过则顺延到区间内的下一天。
     *
     * @param reminder  阶段区间提醒
     * @param nowMillis 当前时间戳
     * @return 下一次触发时间；无周期设置、区间非法或时间格式异常时返回 null
     */
    public static Date nextSpanOccurrence(Reminder reminder, long nowMillis) {
        PhaseModel model = loadModel();
        if (reminder == null || !reminder.isSpanMode() || model == null) {
            return null;
        }
        int[] range = spanIndexRange(reminder);
        int[] time = parseTime(reminder.getAlarmTime());
        if (range == null || time == null) {
            return null;
        }
        Calendar cursor = Calendar.getInstance();
        cursor.setTimeInMillis(nowMillis);
        normalize(cursor);
        int limit = latestTotal(model) * MAX_CYCLE_LOOKAHEAD + 7;
        for (int i = 0; i <= limit; i++) {
            Date day = CycleCalculator.normalizeDate(cursor.getTime());
            if (inSpan(model, day, range[0], range[1])) {
                Calendar candidate = Calendar.getInstance();
                candidate.setTime(day);
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
        PhaseModel model = loadModel();
        if (reminder == null || !reminder.isSpanMode() || model == null
                || start == null || end == null || start.after(end)) {
            return result;
        }
        int[] range = spanIndexRange(reminder);
        if (range == null) {
            return result;
        }
        Date last = CycleCalculator.normalizeDate(end);
        Calendar cursor = Calendar.getInstance();
        cursor.setTime(CycleCalculator.normalizeDate(start));
        while (!cursor.getTime().after(last)) {
            Date day = CycleCalculator.normalizeDate(cursor.getTime());
            if (inSpan(model, day, range[0], range[1])) {
                result.add(day);
            }
            cursor.add(Calendar.DAY_OF_MONTH, 1);
        }
        return result;
    }

    /**
     * 判断某条提醒是否在指定日期触发（只比较日期，忽略提醒时间）。
     * <p>
     * 日历圆点、点击日期后的当日提醒、首页"今日提醒"统一使用这一个口径，
     * 避免不同页面各算一套、出现"日历有圆点但点开没有"的情况。
     *
     * @param reminder 提醒
     * @param day      待判断的日期（可带时分秒，内部按自然日比较）
     * @return 当天触发返回 true
     */
    public static boolean occursOn(Reminder reminder, Date day) {
        if (reminder == null || day == null) {
            return false;
        }
        Date normalized = CycleCalculator.normalizeDate(day);
        if (reminder.isSpanMode()) {
            return !spanDatesBetween(reminder, normalized, normalized).isEmpty();
        }
        if (reminder.isCycleMode()) {
            return !nodeDatesBetween(reminder.getCycleNode(), normalized, normalized).isEmpty();
        }
        if (reminder.isRepeatMode()) {
            return ReminderRepeatHelper.matches(reminder, normalized);
        }
        return CycleCalculator.formatDateId(normalized).equals(reminder.getDateId());
    }

    // ==================== 内部实现 ====================

    /**
     * 当前生效的阶段模型：每个周期分段各自的参数 + 用户实际记录。
     * <p>一次计算内复用，避免逐日查询数据库。
     */
    private static final class PhaseModel {
        /** 周期分段（按起始日升序） */
        final List<Segment> segments;
        /** 用户实际记录的日期 → 阶段（yyyyMMdd → 阶段） */
        final Map<String, MenstrualCycle> actualPhases;

        PhaseModel(List<Segment> segments, Map<String, MenstrualCycle> actualPhases) {
            this.segments = segments;
            this.actualPhases = actualPhases;
        }

        /** 找到覆盖指定日期的分段：起点不晚于该日期的最后一个分段 */
        Segment coveringSegment(Date day) {
            Segment covering = null;
            for (Segment segment : segments) {
                if (segment.start == null) {
                    continue;
                }
                if (!segment.start.after(day)) {
                    covering = segment;
                } else {
                    break;
                }
            }
            return covering;
        }
    }

    /**
     * 单个周期分段：起始日 + 生效阶段天数与总长度。
     * <p>
     * 中间分段（已经开始的真实周期）按“到下一段起点的真实间隔”拉伸阶段，
     * 只有最后一段（当前周期）才按总天数取模循环，保证提醒日期与日历配色同源：
     * 若中间分段仍按保存时的预设长度取模，会在下一段开始前再冒出一轮经期，
     * 提醒圆点就会落在日历上并不属于经期的空白日期里。
     */
    private static final class Segment {
        final Date start;
        final int[] phases;
        final int total;
        /** true = 最后一段，按 total 取模循环；false = 中间分段，只铺满到下一段起点之前 */
        final boolean cycles;

        Segment(Date start, int[] phases, int total, boolean cycles) {
            this.start = start;
            this.phases = phases;
            this.total = total;
            this.cycles = cycles;
        }
    }

    /** 读取当前阶段模型；没有任何周期分段时返回 null */
    private static PhaseModel loadModel() {
        List<Date> starts = new ArrayList<>();
        List<int[]> rawPhases = new ArrayList<>();
        List<Integer> rawTotals = new ArrayList<>();
        List<MenstrualCycleRecord> records = new MenstrualCycleRecordDaoImpl().selectAll();
        if (records != null) {
            for (MenstrualCycleRecord record : records) {
                if (record == null || record.getStartTime() == null
                        || record.getTotalCycleDay() <= 0) {
                    continue;
                }
                int total = record.getTotalCycleDay();
                starts.add(record.getStartTime());
                rawPhases.add(CycleCalculator.calculatePhaseDays(
                        record.getMenstrualPeriodDay(), total));
                rawTotals.add(total);
            }
        }
        if (starts.isEmpty()) {
            return null;
        }

        List<Segment> segments = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            Date start = starts.get(i);
            int[] phases = rawPhases.get(i);
            int total = rawTotals.get(i);
            if (i + 1 >= starts.size()) {
                // 最后一段：当前周期还没结束，按设置的总天数循环
                segments.add(new Segment(start, phases, total, true));
                continue;
            }
            // 中间分段：用「本段起点 → 下一段起点」的真实间隔重排阶段，
            // 与 CalendarView/预测记录生成保持同一口径
            int actualTotal = CycleCalculator.daysBetween(start, starts.get(i + 1));
            int[] effective;
            if (actualTotal <= 0 || actualTotal == total) {
                effective = phases;
            } else if (actualTotal < phases[0] + 18) {
                // 间隔太短放不下完整四阶段：只保留经期标记，其余日期不预测
                effective = new int[]{phases[0], 0, 0, 0};
            } else {
                effective = CycleCalculator.calculatePhaseDays(phases[0], actualTotal);
            }
            segments.add(new Segment(start, effective,
                    actualTotal > 0 ? actualTotal : total, false));
        }

        Map<String, MenstrualCycle> actualPhases = new HashMap<>();
        List<MenstrualRecord> actualRecords = new MenstrualRecordDaoImpl().selectActual();
        if (actualRecords != null) {
            for (MenstrualRecord actual : actualRecords) {
                if (actual != null && actual.getId() != null && actual.getMenstrualCycle() != null) {
                    actualPhases.put(actual.getId(), actual.getMenstrualCycle());
                }
            }
        }
        return new PhaseModel(segments, actualPhases);
    }

    /**
     * 某一天显示的阶段下标（0 经期 … 3 黄体）。
     * <p>实际记录优先；否则用覆盖该日期的周期分段推算。无法推算（早于首个分段）返回 -1。
     */
    private static int phaseIndexOn(PhaseModel model, Date day) {
        MenstrualCycle actual = model.actualPhases.get(CycleCalculator.formatDateId(day));
        if (actual != null) {
            return actual.getIndex();
        }
        Segment segment = model.coveringSegment(day);
        if (segment == null) {
            return -1;
        }
        int diff = CycleCalculator.daysBetween(segment.start, day);
        int seq;
        if (segment.cycles) {
            // 最后一段：跨周期取模，兼容日期早于分段起点导致的负天数
            seq = Math.floorMod(diff, segment.total);
        } else {
            // 中间分段：超出四阶段总和（短间隔分段只保留经期）时不预测
            if (diff >= sumDays(segment.phases)) {
                return -1;
            }
            seq = diff;
        }
        return phaseIndexBySequence(seq, segment.phases);
    }

    /** 四个阶段天数求和 */
    private static int sumDays(int[] phaseDays) {
        int sum = 0;
        for (int days : phaseDays) {
            sum += days;
        }
        return sum;
    }

    /**
     * 按顺序累加阶段天数，判断 seq（周期内第 0 起的天数）落在哪个阶段。
     * 超出四阶段总和时返回最后一个阶段下标，由调用方先行过滤越界情况。
     */
    private static int phaseIndexBySequence(int seq, int[] phaseDays) {
        int accumulated = 0;
        for (int i = 0; i < phaseDays.length; i++) {
            if (seq < accumulated + phaseDays[i]) {
                return i;
            }
            accumulated += phaseDays[i];
        }
        return phaseDays.length - 1;
    }

    /** 当前生效的周期总天数（取最新分段；无分段时返回 0） */
    private static int latestTotal(PhaseModel model) {
        if (model.segments.isEmpty()) {
            return 0;
        }
        return model.segments.get(model.segments.size() - 1).total;
    }

    /** 日期是否命中某个周期节点 */
    private static boolean matchesNode(PhaseModel model, Date day, int cycleNode) {
        switch (cycleNode) {
            case Reminder.NODE_PERIOD_START:
                return isPhaseStart(model, day, MenstrualCycle.MENSTRUAL_PERIOD);
            case Reminder.NODE_PERIOD_END:
                return isPhaseEnd(model, day, MenstrualCycle.MENSTRUAL_PERIOD);
            case Reminder.NODE_FOLLICLE_START:
                return isPhaseStart(model, day, MenstrualCycle.FOLLICULAR_PHASE);
            case Reminder.NODE_OVULATION_START:
                return isPhaseStart(model, day, MenstrualCycle.OVULATION_PERIOD);
            case Reminder.NODE_LUTEAL_START:
                return isPhaseStart(model, day, MenstrualCycle.LUTE_PHASE);
            case Reminder.NODE_BEFORE_PERIOD:
                // 下次经期开始日往前推 3 天
                return isPhaseStart(model, shiftDays(day, 3), MenstrualCycle.MENSTRUAL_PERIOD);
            default:
                return false;
        }
    }

    /** 是否为某阶段的第一天（前一天不是同一阶段） */
    private static boolean isPhaseStart(PhaseModel model, Date day, MenstrualCycle phase) {
        int index = phase.getIndex();
        if (phaseIndexOn(model, day) != index) {
            return false;
        }
        return phaseIndexOn(model, shiftDays(day, -1)) != index;
    }

    /** 是否为某阶段的最后一天（后一天不是同一阶段） */
    private static boolean isPhaseEnd(PhaseModel model, Date day, MenstrualCycle phase) {
        int index = phase.getIndex();
        if (phaseIndexOn(model, day) != index) {
            return false;
        }
        return phaseIndexOn(model, shiftDays(day, 1)) != index;
    }

    /**
     * 阶段区间的下标范围 [起始, 结束)：按阶段顺序比较，
     * 结束节点为"经期开始"时表示一直到本周期结束（下一次经期前）。
     */
    private static int[] spanIndexRange(Reminder reminder) {
        int startIndex = spanPhaseIndex(reminder.getCycleNode());
        int endIndex = spanEndPhaseIndex(reminder.getCycleNodeEnd());
        if (startIndex < 0 || endIndex <= startIndex) {
            return null;
        }
        return new int[]{startIndex, endIndex};
    }

    /** 区间结束节点对应的阶段下标；4 表示"本周期末尾（下一次经期开始前）" */
    private static int spanEndPhaseIndex(int endNode) {
        switch (endNode) {
            case Reminder.NODE_PERIOD_START:
                return 4;
            case Reminder.NODE_PERIOD_END:
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

    /** 日期是否落在阶段区间内 */
    private static boolean inSpan(PhaseModel model, Date day, int startIndex, int endIndex) {
        int index = phaseIndexOn(model, day);
        return index >= startIndex && index < endIndex;
    }

    /** 日期按自然日平移（返回当天 0 点） */
    private static Date shiftDays(Date day, int days) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(day);
        calendar.add(Calendar.DAY_OF_MONTH, days);
        return CycleCalculator.normalizeDate(calendar.getTime());
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

    /** 归零时分秒，只保留日期部分 */
    private static void normalize(Calendar calendar) {
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
    }
}
