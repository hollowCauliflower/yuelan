package work.huacai.yuelan.helper;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import work.huacai.yuelan.model.MenstrualCycle;
import work.huacai.yuelan.model.MenstrualRecord;

/**
 * 周期统计辅助类。
 * <p>
 * 统计页与后续的每日建议引擎共用同一套周期口径，避免两处各算一遍导致数字对不上：
 * <ul>
 *   <li>把用户实际记录按“相邻经期日期间隔 ≥ 10 天”切分成若干轮；</li>
 *   <li>只有“下一次经期已经开始”的轮次才算完整周期，
 *       因此完整周期数 = 轮次数 - 1，最后一段进行中的周期不计入；</li>
 *   <li>平均周期长度取各轮经期开始日之间的平均间隔。</li>
 * </ul>
 * 本类只做纯计算，不读写数据库、不依赖 Context，便于统计页与建议引擎复用同一口径。
 */
public final class CycleStatsHelper {

    /** 相邻两次经期记录间隔达到该天数即视为进入新周期 */
    public static final int NEW_CYCLE_GAP_DAYS = 10;

    private CycleStatsHelper() {
        // 工具类禁止实例化
    }

    /**
     * 按“经期开始 ~ 下一个经期开始前一天”切分周期：
     * 相邻经期日期间隔达到 10 天及以上视为进入新周期，非经期记录归入当前周期。
     * 第一个经期之前的非经期记录没有归属周期，直接丢弃。
     *
     * @param records 实际记录（顺序不限，调用方需已过滤预测记录）
     * @return 按时间升序排列的周期列表，每个周期内记录同样按日期升序
     */
    public static List<List<MenstrualRecord>> splitCycles(List<MenstrualRecord> records) {
        List<List<MenstrualRecord>> cycles = new ArrayList<>();
        if (records == null || records.isEmpty()) {
            return cycles;
        }

        List<MenstrualRecord> sorted = new ArrayList<>(records);
        Collections.sort(sorted, (r1, r2) -> {
            Date d1 = CycleCalculator.parseDateId(r1.getId());
            Date d2 = CycleCalculator.parseDateId(r2.getId());
            if (d1 == null || d2 == null) return 0;
            return d1.compareTo(d2);
        });

        List<MenstrualRecord> current = null;
        // 记录当前周期内“最后一条经期记录”的日期：
        // 不能拿周期内最后一条记录做间隔判断，否则经期末尾补录的非经期记录会把下一次经期误判为同一周期
        Date lastMenstrualDate = null;
        for (MenstrualRecord r : sorted) {
            if (r.getMenstrualCycle() == MenstrualCycle.MENSTRUAL_PERIOD) {
                Date cur = CycleCalculator.parseDateId(r.getId());
                boolean newCycle = current == null || lastMenstrualDate == null || cur == null
                        || CycleCalculator.daysBetween(lastMenstrualDate, cur) >= NEW_CYCLE_GAP_DAYS;
                if (newCycle) {
                    current = new ArrayList<>();
                    cycles.add(current);
                    lastMenstrualDate = null;
                }
                current.add(r);
                if (cur != null) {
                    lastMenstrualDate = cur;
                }
            } else if (current != null && !current.isEmpty()) {
                current.add(r);
            }
        }
        return cycles;
    }

    /**
     * 统计一个周期内的经期天数。
     * <p>按“首末经期记录的日期跨度”计算，而不是数记录条数：
     * 用户可能漏记中间某天，用条数会把 5 天经期算成 3 天。
     *
     * @param cycle 单个周期的记录列表
     * @return 经期自然日天数（含首尾）；没有经期记录时返回 0
     */
    public static int countPeriodDays(List<MenstrualRecord> cycle) {
        Date first = null;
        Date last = null;
        if (cycle != null) {
            for (MenstrualRecord r : cycle) {
                if (r.getMenstrualCycle() != MenstrualCycle.MENSTRUAL_PERIOD) {
                    continue;
                }
                Date date = CycleCalculator.parseDateId(r.getId());
                if (date == null) {
                    continue;
                }
                if (first == null) {
                    first = date;
                }
                last = date;
            }
        }
        if (first == null || last == null) {
            return 0;
        }
        // 自然日跨度 +1（含首尾），跨夏令时也不会算错
        return CycleCalculator.daysBetween(first, last) + 1;
    }

    /**
     * 完整周期数 = 轮次数 - 1。
     *
     * @param cycles 已切分的轮次列表
     * @return 完整周期数；轮次不足时返回 0
     */
    public static int completeCycleCount(List<List<MenstrualRecord>> cycles) {
        if (cycles == null || cycles.isEmpty()) {
            return 0;
        }
        return Math.max(0, cycles.size() - 1);
    }

    /**
     * 平均周期长度：各轮经期开始日之间的自然日跨度 / 完整周期数。
     *
     * @param cycles 已切分的轮次列表
     * @return 平均天数；完整周期不足 1 个或日期解析失败时返回 -1
     */
    public static int averageCycleDays(List<List<MenstrualRecord>> cycles) {
        int completeCycles = completeCycleCount(cycles);
        if (completeCycles < 1) {
            return -1;
        }
        Date firstStart = cycleStartDate(cycles.get(0));
        Date lastStart = cycleStartDate(cycles.get(cycles.size() - 1));
        if (firstStart == null || lastStart == null) {
            return -1;
        }
        // 按自然日计算跨度，避免夏令时导致某一天只有 23 小时而少算一天
        int spanDays = CycleCalculator.daysBetween(firstStart, lastStart);
        return Math.round(spanDays / (float) completeCycles);
    }

    /**
     * 周期起始日：轮次内第一条记录的日期（切分逻辑保证首条一定是经期记录）。
     *
     * @param cycle 单个周期
     * @return 起始日期；参数为空或日期非法时返回 null
     */
    public static Date cycleStartDate(List<MenstrualRecord> cycle) {
        if (cycle == null || cycle.isEmpty()) {
            return null;
        }
        return CycleCalculator.parseDateId(cycle.get(0).getId());
    }

    /**
     * 周期内平均流量分值。
     * <p>只统计真正录入过流量的经期记录；没有录入时返回 -1，与“0 分”区分开。
     *
     * @param cycle 单个周期
     * @return 平均流量分值；无数据时返回 -1
     */
    public static int averageFlowScore(List<MenstrualRecord> cycle) {
        int sum = 0;
        int count = 0;
        if (cycle != null) {
            for (MenstrualRecord record : cycle) {
                if (record.getMenstrualCycle() == MenstrualCycle.MENSTRUAL_PERIOD
                        && record.hasFlowData()) {
                    sum += record.getFlowScore();
                    count++;
                }
            }
        }
        return count > 0 ? sum / count : -1;
    }

    /**
     * 周期内平均痛经等级。
     * <p>0 级也计入：有录入即为“无痛经”，而不是“未记录”；没有录入时返回 -1。
     *
     * @param cycle 单个周期
     * @return 平均痛经等级（0~4）；无数据时返回 -1
     */
    public static int averagePainLevel(List<MenstrualRecord> cycle) {
        int sum = 0;
        int count = 0;
        if (cycle != null) {
            for (MenstrualRecord record : cycle) {
                if (record.getMenstrualCycle() == MenstrualCycle.MENSTRUAL_PERIOD
                        && (record.hasFlowData() || record.hasPainData())) {
                    sum += record.getPainLevel();
                    count++;
                }
            }
        }
        return count > 0 ? sum / count : -1;
    }

    /**
     * 最近若干个完整周期的长度统计，用于周期节律图与“规律性”判断。
     * <p>只统计完整周期（不含最后一段进行中的周期），最多取最近 {@code maxCount} 个。
     *
     * @param cycles   已切分的轮次列表
     * @param maxCount 最多统计的周期数，小于 1 时按 1 处理
     * @return 长度统计结果；没有完整周期时返回空结果
     */
    public static CycleLengthStats cycleLengthStats(List<List<MenstrualRecord>> cycles, int maxCount) {
        List<CycleLength> items = new ArrayList<>();
        int completeCycles = completeCycleCount(cycles);
        if (completeCycles <= 0) {
            return new CycleLengthStats(items);
        }

        int limit = Math.max(1, maxCount);
        int from = Math.max(0, completeCycles - limit);
        for (int i = from; i < completeCycles; i++) {
            Date start = cycleStartDate(cycles.get(i));
            Date nextStart = cycleStartDate(cycles.get(i + 1));
            if (start == null || nextStart == null) {
                continue;
            }
            int cycleDays = CycleCalculator.daysBetween(start, nextStart);
            int periodDays = countPeriodDays(cycles.get(i));
            items.add(new CycleLength(i, cycleDays, periodDays));
        }
        return new CycleLengthStats(items);
    }

    /**
     * 周期规律性评分。
     * <p>取最近若干个完整周期的长度极差：≤3 天为很规律，≤7 天为较规律，
     * 更大则判定为波动较大。只有一个完整周期时无法评估，返回 null。
     *
     * @param cycles 已切分的轮次列表
     * @return {@code stable} / {@code variable} / {@code irregular}；样本不足返回 null
     */
    public static String regularityOf(List<List<MenstrualRecord>> cycles) {
        CycleLengthStats stats = cycleLengthStats(cycles, 6);
        if (stats.getItems().size() < 2) {
            return null;
        }
        int spread = stats.getMaxDays() - stats.getMinDays();
        if (spread <= 3) {
            return "stable";
        }
        if (spread <= 7) {
            return "variable";
        }
        return "irregular";
    }

    /**
     * 连续记录天数：从今天（或昨天）向前数，连续有实际记录的天数。
     * <p>允许从昨天开始起算，是因为用户通常在晚上才记录当天，
     * 白天打开应用时不应该显示“连续 0 天”而打断正反馈。
     *
     * @param actualRecords 实际记录（可含任意顺序）
     * @param today         参照日期
     * @return 连续天数；没有记录时返回 0
     */
    public static int recordStreak(List<MenstrualRecord> actualRecords, Date today) {
        if (actualRecords == null || actualRecords.isEmpty() || today == null) {
            return 0;
        }
        Set<String> recordedIds = new HashSet<>();
        for (MenstrualRecord record : actualRecords) {
            if (record != null && record.getId() != null) {
                recordedIds.add(record.getId());
            }
        }

        Calendar cursor = Calendar.getInstance();
        cursor.setTime(CycleCalculator.normalizeDate(today));
        // 今天还没记录时从昨天起算；两天都空则连续记录为 0
        if (!recordedIds.contains(CycleCalculator.formatDateId(cursor.getTime()))) {
            cursor.add(Calendar.DAY_OF_MONTH, -1);
            if (!recordedIds.contains(CycleCalculator.formatDateId(cursor.getTime()))) {
                return 0;
            }
        }

        int streak = 0;
        while (recordedIds.contains(CycleCalculator.formatDateId(cursor.getTime()))) {
            streak++;
            cursor.add(Calendar.DAY_OF_MONTH, -1);
        }
        return streak;
    }

    /**
     * 近期经期是否常伴中度以上痛经。
     * <p>只看最近 3 个周期内经期日期的记录，样本少于 3 条时不判定，
     * 避免仅凭一两天就下“你常痛经”的结论。
     *
     * @param actualRecords 实际记录
     * @return 中度以上痛经占比过半时返回 true
     */
    public static boolean recentPeriodPainTrend(List<MenstrualRecord> actualRecords) {
        if (actualRecords == null || actualRecords.isEmpty()) {
            return false;
        }
        List<List<MenstrualRecord>> cycles = splitCycles(actualRecords);
        if (cycles.isEmpty()) {
            return false;
        }

        int from = Math.max(0, cycles.size() - 3);
        int recordedDays = 0;
        int painfulDays = 0;
        for (int i = from; i < cycles.size(); i++) {
            for (MenstrualRecord record : cycles.get(i)) {
                if (record.getMenstrualCycle() != MenstrualCycle.MENSTRUAL_PERIOD) {
                    continue;
                }
                // 与统计页口径一致：填过流量或痛经才算“已录入”
                if (!record.hasFlowData() && !record.hasPainData()) {
                    continue;
                }
                recordedDays++;
                if (record.getPainLevel() >= 2) {
                    painfulDays++;
                }
            }
        }
        return recordedDays >= 3 && painfulDays * 2 >= recordedDays;
    }

    /**
     * 最近一个完整周期相对此前平均长度的变化天数。
     * <p>用于“你的周期比之前短了 2 天”这类趋势解读：正数表示变长，负数表示变短。
     * 至少需要 3 个完整周期才判断，避免样本太少时给出误导性的结论。
     *
     * @param cycles 已切分的轮次列表
     * @return 变化天数；样本不足时返回 0
     */
    public static int recentCycleTrend(List<List<MenstrualRecord>> cycles) {
        CycleLengthStats stats = cycleLengthStats(cycles, 6);
        List<CycleLength> items = stats.getItems();
        if (items.size() < 3) {
            return 0;
        }
        int lastDays = items.get(items.size() - 1).getCycleDays();
        int sum = 0;
        for (int i = 0; i < items.size() - 1; i++) {
            sum += items.get(i).getCycleDays();
        }
        int previousAverage = Math.round(sum / (items.size() - 1f));
        return lastDays - previousAverage;
    }

    /**
     * 单个完整周期的长度信息。
     */
    public static final class CycleLength {
        /** 在全部轮次中的下标（从 0 开始），用于展示“第 N 个周期” */
        private final int index;
        /** 周期总长度（自然日） */
        private final int cycleDays;
        /** 该周期内的经期天数 */
        private final int periodDays;

        CycleLength(int index, int cycleDays, int periodDays) {
            this.index = index;
            this.cycleDays = cycleDays;
            this.periodDays = periodDays;
        }

        public int getIndex() {
            return index;
        }

        public int getCycleDays() {
            return cycleDays;
        }

        public int getPeriodDays() {
            return periodDays;
        }
    }

    /**
     * 周期长度统计结果：明细 + 平均 / 最短 / 最长。
     */
    public static final class CycleLengthStats {
        private final List<CycleLength> items;
        private final int averageDays;
        private final int minDays;
        private final int maxDays;

        CycleLengthStats(List<CycleLength> items) {
            this.items = items;
            if (items.isEmpty()) {
                this.averageDays = 0;
                this.minDays = 0;
                this.maxDays = 0;
                return;
            }
            int sum = 0;
            int min = Integer.MAX_VALUE;
            int max = 0;
            for (CycleLength item : items) {
                sum += item.getCycleDays();
                min = Math.min(min, item.getCycleDays());
                max = Math.max(max, item.getCycleDays());
            }
            this.averageDays = Math.round(sum / (float) items.size());
            this.minDays = min;
            this.maxDays = max;
        }

        public List<CycleLength> getItems() {
            return items;
        }

        public int getAverageDays() {
            return averageDays;
        }

        public int getMinDays() {
            return minDays;
        }

        public int getMaxDays() {
            return maxDays;
        }

        public boolean isEmpty() {
            return items.isEmpty();
        }
    }
}
