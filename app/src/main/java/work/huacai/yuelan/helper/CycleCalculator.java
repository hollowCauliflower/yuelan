package work.huacai.yuelan.helper;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import work.huacai.yuelan.model.MenstrualCycle;
import work.huacai.yuelan.model.MenstrualRecord;

/**
 * 周期计算工具类。
 * <p>
 * 负责把“用户设置的月经期天数 + 周期总天数”换算成四个阶段的长度，
 * 再根据日期推算所属阶段、当前周期起始日、第几天等。
 * 全部为静态方法，不持有状态；日期一律按“自然日”对齐（时分秒清零）后再做差。
 */
public class CycleCalculator {

    private static final String TAG = "CycleCalculator";

    /**
     * 一个完整周期中除经期外必须保留的最少天数：1 天卵泡 + 3 天排卵 + 14 天黄体。
     * <p>
     * 两次经期起点之间的间隔若小于“经期天数 + 本值”，就放不下完整的四个阶段，
     * 此时不应触发整段预测重排，只把补签的那一天记为实际经期。
     */
    public static final int MIN_NON_MENSTRUAL_DAYS = 18;

    /** 日期 ID 格式（yyyyMMdd），同时用作数据库主键 */
    // 日期 ID 是写入数据库的主键格式，必须固定用公历/西方数字：
    // 若跟随系统 Locale（如泰国佛历），年份会变成 2567 导致记录 id 与日期错位
    private static final SimpleDateFormat DATE_FORMAT_ID = new SimpleDateFormat("yyyyMMdd", Locale.US);
    /** 展示格式（MM月dd日） */
    private static final SimpleDateFormat DATE_FORMAT_DISPLAY = new SimpleDateFormat("MM月dd日", Locale.getDefault());
    /** 短格式（MM-dd），用于日志与日历页"下次排卵"等紧凑日期展示 */
    private static final SimpleDateFormat DATE_FORMAT_SHORT = new SimpleDateFormat("MM-dd", Locale.getDefault());
    /** 中文短格式（M月d日），用于日历页"下次排卵"区间展示 */
    private static final SimpleDateFormat DATE_FORMAT_CN_SHORT = new SimpleDateFormat("M月d日", Locale.getDefault());

    private CycleCalculator() {
        // 工具类禁止实例化
    }

    /**
     * 把本地日期换算为 UTC 当天零点的毫秒值。
     * <p>
     * MaterialDatePicker 的选区值按 UTC 计算，直接传本地毫秒值会导致日期偏移一天。
     *
     * @param calendar 本地日期
     * @return 对应 UTC 零点的毫秒值，供 MaterialDatePicker.setSelection 使用
     */
    public static long toUtcMidnight(Calendar calendar) {
        Calendar utc = (Calendar) calendar.clone();
        // 先清零本地时分秒，再按 UTC 重新解释同一“墙上时间”
        utc.clear(Calendar.HOUR_OF_DAY);
        utc.clear(Calendar.MINUTE);
        utc.clear(Calendar.SECOND);
        utc.clear(Calendar.MILLISECOND);
        Calendar result = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        result.set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), 0, 0, 0);
        result.set(Calendar.MILLISECOND, 0);
        return result.getTimeInMillis();
    }

    /**
     * 把 MaterialDatePicker 返回的 UTC 零点毫秒值转换回本地自然日。
     *
     * @param utcMillis UTC 零点毫秒值
     * @return 本地时区的对应日期
     */
    public static Date localFromUtc(long utcMillis) {
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        utc.setTimeInMillis(utcMillis);
        Calendar local = Calendar.getInstance();
        local.set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), 0, 0, 0);
        local.set(Calendar.MILLISECOND, 0);
        return local.getTime();
    }

    /**
     * 按总周期天数换算四个阶段的长度。
     * <p>
     * 规则：排卵期固定 3 天、黄体期优先 14 天（剩余不足 14 天时全部给黄体期，最多放宽到 18 天），
     * 其余天数归卵泡期。返回数组的四个元素之和<b>恒等于</b>{@code totalCycleDays}，
     * 以保证周期环形进度、阶段日期区间与日历取色使用同一套长度、不会互相越界。
     * <p>
     * 月经期天数会先被钳制到 {@link #getMaxMenstrualDays(int)}：
     * 至少为排卵期（3 天）和黄体期（10 天）留出空间，避免"经期把卵泡期挤成负数"。
     *
     * @param menstrualDays  用户设置的月经期天数
     * @param totalCycleDays 用户设置的周期总天数
     * @return 长度为 4 的数组，依次为 月经期、卵泡期、排卵期、黄体期 的天数，总和 == totalCycleDays
     */
    public static int[] calculatePhaseDays(int menstrualDays, int totalCycleDays) {
        int total = Math.max(totalCycleDays, 0);

        // 周期短到放不下四个阶段时，退化为"月经期 + 黄体期"，仍保证总和相等
        if (total < 4) {
            int menstrual = Math.min(Math.max(menstrualDays, 0), total);
            return new int[]{menstrual, 0, 0, total - menstrual};
        }

        int ovulation = 3;
        int actualMenstrual = Math.min(Math.max(menstrualDays, 1), getMaxMenstrualDays(total));

        // 扣掉月经期与排卵期后，剩余天数在卵泡期与黄体期之间分配
        int remaining = total - actualMenstrual - ovulation;
        int luteal;
        if (remaining <= 14) {
            // 剩余不足 14 天：全部给黄体期，卵泡期为 0（仍保证总和相等）
            luteal = Math.max(remaining, 0);
        } else if (remaining <= 44) {
            luteal = 14;
        } else {
            // 超长周期（仅可能来自导入数据）：黄体期放宽到 18 天，其余归卵泡期
            luteal = 18;
        }

        return new int[]{actualMenstrual, remaining - luteal, ovulation, luteal};
    }

    /**
     * 在给定周期总天数下，经期天数允许的最大值。
     * <p>
     * 需为排卵期（3 天）与黄体期（最少 10 天）留出空间，因此上限为 {@code totalCycleDays - 13}；
     * 同时不超过生理上常用的 20 天上限。
     *
     * @param totalCycleDays 周期总天数
     * @return 经期天数上限，至少为 1
     */
    public static int getMaxMenstrualDays(int totalCycleDays) {
        return Math.max(1, Math.min(20, totalCycleDays - 13));
    }

    /**
     * 判断两轮经期起点之间的实际间隔能否容纳一个完整周期。
     * <p>
     * 判定口径：间隔 ≥ 经期天数 + {@link #MIN_NON_MENSTRUAL_DAYS}（1 卵泡 + 3 排卵 + 14 黄体）。
     * 不满足时说明这段“周期”太短，强行按四阶段拆分会让卵泡期/黄体期被压成负数或畸变，
     * 因此只把补签日记为单日经期，不触发整段预测重排。
     *
     * @param actualIntervalDays 与上一轮经期起点相隔的自然日数
     * @param menstrualPeriodDays 经期天数
     * @return 可以重排返回 true；只应记录单日返回 false
     */
    public static boolean canResequenceCycle(int actualIntervalDays, int menstrualPeriodDays) {
        return actualIntervalDays >= Math.max(menstrualPeriodDays, 1) + MIN_NON_MENSTRUAL_DAYS;
    }

    /**
     * 从"用户实际记录"中找出最近一段连续经期的首日，作为预测的基准日。
     * <p>
     * 只考虑阶段为月经期的实际记录，从最新一条向前回退；
     * 允许用户漏记中间日期（间隔小于 10 天仍视为同一段经期），
     * 间隔达到 10 天及以上时停止回退。若没有任何实际经期记录，返回 null，由调用方回退到设置中的起始日。
     *
     * @param actualRecords 用户实际记录（可含非经期记录）
     * @return 最近一段连续实际经期的首日（自然日）；没有实际经期记录时返回 null
     */
    public static Date findLatestActualMenstrualStart(List<MenstrualRecord> actualRecords) {
        List<Date> anchors = findAllActualMenstrualAnchors(actualRecords);
        return anchors.isEmpty() ? null : anchors.get(anchors.size() - 1);
    }

    /**
     * 从实际记录中找出每一轮实际经期的开始锚点（升序）。
     * <p>
     * 切分规则与 {@link #findLatestActualMenstrualStart} 一致：按日期升序遍历经期记录，
     * 相邻两条经期记录间隔达到 10 天及以上时开启新一轮（容忍用户漏记经期中间的几天）。
     *
     * @param actualRecords 实际记录列表
     * @return 每轮实际经期的开始日期（升序）；无经期记录时返回空列表
     */
    public static List<Date> findAllActualMenstrualAnchors(List<MenstrualRecord> actualRecords) {
        List<Date> anchors = new ArrayList<>();
        if (actualRecords == null || actualRecords.isEmpty()) {
            return anchors;
        }

        List<MenstrualRecord> sorted = new ArrayList<>(actualRecords);
        Collections.sort(sorted, (r1, r2) -> {
            Date d1 = parseDateId(r1.getId());
            Date d2 = parseDateId(r2.getId());
            if (d1 == null || d2 == null) return 0;
            return d1.compareTo(d2);
        });

        Date prevMenstrualDate = null;
        // 今天之后不允许存在实际记录；若历史数据里残留了未来日期的"实际经期"，
        // 绝不能把它当成锚点，否则当前分段起点会跑到未来，首页天数会算出负数
        Date today = normalizeDate(new Date());
        for (MenstrualRecord record : sorted) {
            if (record.getMenstrualCycle() != MenstrualCycle.MENSTRUAL_PERIOD) {
                continue;
            }
            Date date = normalizeDate(parseDateId(record.getId()));
            if (date == null || date.after(today)) {
                continue;
            }
            // 用户经常漏记经期中间的某几天，不能要求严格相邻一天：
            // 与统计页的周期切分保持一致，间隔达到 10 天及以上才视为新一轮经期
            if (prevMenstrualDate == null || daysBetween(prevMenstrualDate, date) >= 10) {
                anchors.add(date);
            }
            prevMenstrualDate = date;
        }
        return anchors;
    }

    /**
     * 根据“周期内第几天”定位阶段序号。
     *
     * @param dayInCycle 周期内第几天，从 1 开始
     * @param phaseArray 长度必须为 4 的阶段天数数组
     * @return 1=月经期、2=卵泡期、3=排卵期、4=黄体期；入参非法或超出总天数时返回 -1
     */
    public static int getPhaseIndex(int dayInCycle, int[] phaseArray) {
        if (phaseArray == null || phaseArray.length != 4 || dayInCycle < 1) {
            return -1;
        }

        // 阶段区间为左闭右闭：第 1 阶段 [1, m]，第 2 阶段 [m+1, m+f] ...
        if (dayInCycle <= phaseArray[0]) {
            return 1;
        }
        if (dayInCycle <= phaseArray[0] + phaseArray[1]) {
            return 2;
        }
        if (dayInCycle <= phaseArray[0] + phaseArray[1] + phaseArray[2]) {
            return 3;
        }

        int totalDays = phaseArray[0] + phaseArray[1] + phaseArray[2] + phaseArray[3];
        if (dayInCycle <= totalDays) {
            return 4;
        }

        return -1;
    }

    /**
     * 阶段序号转枚举。
     *
     * @param phaseIndex 阶段序号（1~4）
     * @return 对应周期阶段；序号非法时返回 null
     */
    public static MenstrualCycle getMenstrualCycleForPhase(int phaseIndex) {
        switch (phaseIndex) {
            case 1:
                return MenstrualCycle.MENSTRUAL_PERIOD;
            case 2:
                return MenstrualCycle.FOLLICULAR_PHASE;
            case 3:
                return MenstrualCycle.OVULATION_PERIOD;
            case 4:
                return MenstrualCycle.LUTE_PHASE;
            default:
                return null;
        }
    }

    /**
     * 判断某个日期属于哪个周期阶段（支持跨多个周期的循环推算）。
     *
     * @param date          目标日期
     * @param startTime     最近一次月经开始日（周期基准日）
     * @param phaseArray    长度必须为 4 的阶段天数数组
     * @param totalCycleDay 周期总天数
     * @return 目标日期所属阶段；目标日期早于基准日或入参非法时返回 null
     */
    public static MenstrualCycle getMenstrualCycleForDate(Date date, Date startTime, int[] phaseArray, int totalCycleDay) {
        return getMenstrualCycleForDate(date, startTime, phaseArray, totalCycleDay, null, -1);
    }

    /**
     * 判断某个日期属于哪个周期阶段（支持阶段锚点重排）。
     * <p>
     * 无锚点（anchorDate 为 null 或 anchorPhaseIndex 不在 0~3）时与
     * {@link #getMenstrualCycleForDate(Date, Date, int[], int)} 完全一致；
     * 有锚点且不早于分段起点、目标日期不早于锚点时，从锚点阶段起按阶段长度顺序推进
     * （锚点日当天为锚点阶段第 1 天），可跨虚拟周期循环。
     *
     * @param date              目标日期
     * @param startTime         分段起点（最近一次月经开始日）
     * @param phaseArray        长度必须为 4 的阶段天数数组
     * @param totalCycleDay     周期总天数
     * @param anchorDate        阶段锚点日期；null 表示无锚点
     * @param anchorPhaseIndex  锚点阶段下标（0~3），不在范围内视为无锚点
     * @return 目标日期所属阶段；目标日期早于分段起点或入参非法时返回 null
     */
    public static MenstrualCycle getMenstrualCycleForDate(Date date, Date startTime, int[] phaseArray,
                                                          int totalCycleDay, Date anchorDate, int anchorPhaseIndex) {
        if (startTime == null || phaseArray == null || totalCycleDay <= 0 || date == null) {
            return null;
        }

        Date normalizedStart = normalizeDate(startTime);
        Date normalizedTarget = normalizeDate(date);

        if (normalizedTarget.before(normalizedStart)) {
            return null;
        }

        boolean hasAnchor = anchorDate != null && anchorPhaseIndex >= 0 && anchorPhaseIndex < phaseArray.length;
        if (hasAnchor) {
            Date normalizedAnchor = normalizeDate(anchorDate);
            if (!normalizedAnchor.before(normalizedStart) && !normalizedTarget.before(normalizedAnchor)) {
                // 锚点阶段之前的累计天数 = 锚点阶段第 1 天在周期序列中的序号（0 起）
                int before = 0;
                for (int i = 0; i < anchorPhaseIndex; i++) {
                    before += phaseArray[i];
                }
                // 按"自然日"计算间隔（跨夏令时时一天可能是 23/25 小时，不能用固定毫秒值整除）
                int seq = before + daysBetween(normalizedAnchor, normalizedTarget);
                int dayInCycle = seq % totalCycleDay + 1;
                return getMenstrualCycleForPhase(getPhaseIndex(dayInCycle, phaseArray));
            }
        }

        // 按"自然日"计算间隔（跨夏令时时一天可能是 23/25 小时，不能用固定毫秒值整除）
        int diffDays = daysBetween(normalizedStart, normalizedTarget);
        // 对周期取模即可得到当前周期内的第几天（从 1 开始）
        int dayInCycle = diffDays % totalCycleDay + 1;
        // 阶段天数总和小于周期总长时（如"仅经期"的短间隔分段），
        // 超出部分不属于任何阶段，返回 null 由调用方留空，避免循环出错误的阶段
        int phaseSum = 0;
        for (int days : phaseArray) {
            phaseSum += days;
        }
        if (dayInCycle > phaseSum) {
            return null;
        }
        int phaseIndex = getPhaseIndex(dayInCycle, phaseArray);

        return getMenstrualCycleForPhase(phaseIndex);
    }

    /**
     * 计算从基准日到今天已经过去多少天（含今天，即今天为第 1 天）。
     *
     * @param startTime 基准日（最近一次月经开始日）
     * @return 天数；startTime 为 null 时返回 0
     */
    public static int calculateCurrentDay(Date startTime) {
        if (startTime == null) {
            return 0;
        }

        Date today = normalizeDate(new Date());
        int diffDays = daysBetween(normalizeDate(startTime), today);
        // 含今天：起始日当天记为第 1 天
        return diffDays + 1;
    }

    /**
     * 计算两个日期之间相隔的自然日数（{@code to - from}）。
     * <p>
     * 先把两个本地日期换算成“UTC 纪元日”，再直接相减。UTC 没有夏令时，
     * 一天恒为 24 小时，因此不会出现跨夏令时切换时用固定毫秒整除而差一天的问题。
     * 结果与是否清零时分秒无关（只看年月日）。
     *
     * @param from 起始日期
     * @param to   结束日期
     * @return 相隔天数，可能为负；任一参数为 null 时返回 0
     */
    public static int daysBetween(Date from, Date to) {
        if (from == null || to == null) {
            return 0;
        }
        long fromDay = toUtcEpochDay(from);
        long toDay = toUtcEpochDay(to);
        return (int) (toDay - fromDay);
    }

    /**
     * 把日期换算成 UTC 纪元日（自 1970-01-01 起的自然日序号）。
     * <p>
     * 只取本地日期的年月日，再按 UTC 构造零点；UTC 无夏令时，毫秒值可安全整除一天的毫秒数。
     *
     * @param date 本地日期
     * @return 纪元日序号
     */
    private static long toUtcEpochDay(Date date) {
        Calendar local = Calendar.getInstance();
        local.setTime(date);
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        utc.clear();
        utc.set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH));
        return utc.getTimeInMillis() / (24L * 60 * 60 * 1000);
    }

    /**
     * 把日期的时间部分清零，得到当天 00:00:00.000。
     *
     * @param date 原始日期
     * @return 归一化后的新 Date；入参为 null 时返回 null
     */
    public static Date normalizeDate(Date date) {
        if (date == null) return null;
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        normalizeCalendar(cal);
        return cal.getTime();
    }

    /**
     * 原地把 Calendar 的时分秒毫秒清零。
     *
     * @param cal 待修改的 Calendar
     */
    public static void normalizeCalendar(Calendar cal) {
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
    }

    /**
     * 计算某个阶段在具体周期中的起止日期。
     * <p>
     * phase 取值 1~5，其中 5 表示“下一个周期的月经期”（用于首页的下一个阶段提示）：
     * 日期基准会先整体后移一个周期再按第 1 阶段计算。
     *
     * @param phase         阶段序号（1~5）
     * @param cycleStart    当前周期的第一天
     * @param phaseArray    长度必须为 4 的阶段天数数组
     * @param totalCycleDay 周期总天数（phase = 5 时用于跨周期偏移）
     * @return {开始日期, 结束日期}（均为闭区间，结束日期 = 开始日期 + 阶段天数 - 1）；入参非法时返回 null
     */
    public static Date[] calculatePhaseDateRange(int phase, Date cycleStart, int[] phaseArray, int totalCycleDay) {
        if (phase < 1 || phase > 5 || cycleStart == null || phaseArray == null || phaseArray.length != 4) {
            return null;
        }

        int adjustedPhase = phase;
        Date baseDate = cycleStart;

        if (phase == 5) {
            // 第 5 阶段 = 下一周期第 1 阶段，先把基准日向后推一个完整周期
            adjustedPhase = 1;
            Calendar cal = Calendar.getInstance();
            cal.setTime(cycleStart);
            cal.add(Calendar.DAY_OF_MONTH, totalCycleDay);
            baseDate = cal.getTime();
        }

        // 累加前面各阶段的天数，得到该阶段的起始偏移
        int startOffset = 0;
        for (int i = 0; i < adjustedPhase - 1; i++) {
            startOffset += phaseArray[i];
        }

        Calendar cal = Calendar.getInstance();
        cal.setTime(baseDate);
        cal.add(Calendar.DAY_OF_MONTH, startOffset);
        Date startDate = cal.getTime();

        // 结束日 = 起始日 + 阶段天数 - 1，保证区间闭合
        cal.add(Calendar.DAY_OF_MONTH, phaseArray[adjustedPhase - 1] - 1);
        Date endDate = cal.getTime();

        return new Date[]{startDate, endDate};
    }

    /**
     * 格式化为日期 ID（yyyyMMdd），与数据库中的记录 id 一致。
     *
     * @param date 目标日期
     * @return 格式化结果
     */
    public static String formatDateId(Date date) {
        // SimpleDateFormat 非线程安全，统一加锁调用
        synchronized (DATE_FORMAT_ID) {
            return DATE_FORMAT_ID.format(date);
        }
    }

    /**
     * 格式化为展示文本（MM月dd日）。
     *
     * @param date 目标日期
     * @return 格式化结果
     */
    public static String formatDateDisplay(Date date) {
        synchronized (DATE_FORMAT_DISPLAY) {
            return DATE_FORMAT_DISPLAY.format(date);
        }
    }

    /**
     * 格式化为短文本（MM-dd），用于日志与紧凑日期展示。
     *
     * @param date 目标日期
     * @return 格式化结果
     */
    public static String formatDateShort(Date date) {
        synchronized (DATE_FORMAT_SHORT) {
            return DATE_FORMAT_SHORT.format(date);
        }
    }

    /**
     * 格式化为中文短日期（M月d日），用于日历页"下次排卵"区间展示。
     *
     * @param date 目标日期
     * @return 形如 11月19日 的文本
     */
    public static String formatDateCnShort(Date date) {
        synchronized (DATE_FORMAT_CN_SHORT) {
            return DATE_FORMAT_CN_SHORT.format(date);
        }
    }

    /**
     * 解析日期 ID（yyyyMMdd）。
     *
     * @param dateStr 日期字符串，可为 null
     * @return 解析出的日期；空串或格式不合法时返回 null（不抛出异常）
     */
    public static Date parseDateId(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) {
            return null;
        }
        try {
            synchronized (DATE_FORMAT_ID) {
                return DATE_FORMAT_ID.parse(dateStr);
            }
        } catch (Exception e) {
            // 记录 id 不是日期格式时降级为 null，由调用方显示原始文本
            Log.e(TAG, "Failed to parse date: " + dateStr, e);
            return null;
        }
    }

    /**
     * 计算当前所在周期的起始日（最近一次月经开始日之后的第 N 个周期）。
     *
     * @param startTime     最近一次月经开始日
     * @param totalCycleDay 周期总天数
     * @return 当前周期起始日；入参非法时返回 null
     */
    public static Date getCurrentCycleStart(Date startTime, int totalCycleDay) {
        if (startTime == null || totalCycleDay <= 0) {
            return null;
        }

        // 绝对天数从 1 开始，因此用 (absDay - 1) 整除周期长度得到已过去的完整周期数；
        // 起始日被误设到今天之后时 absDay 会 <= 0，此时按"当前周期刚开始"处理，避免返回未来日期
        int absDay = Math.max(1, calculateCurrentDay(startTime));
        int cycleIndex = (absDay - 1) / totalCycleDay;

        Calendar cal = Calendar.getInstance();
        cal.setTime(startTime);
        cal.add(Calendar.DAY_OF_MONTH, cycleIndex * totalCycleDay);
        return cal.getTime();
    }

    /**
     * 计算今天在当前周期内的第几天（从 1 开始）。
     *
     * @param startTime     最近一次月经开始日
     * @param totalCycleDay 周期总天数
     * @return 周期内天数；入参非法时返回 0
     */
    public static int getDayInCurrentCycle(Date startTime, int totalCycleDay) {
        if (startTime == null || totalCycleDay <= 0) {
            return 0;
        }

        // 绝对天数取模周期长度后加 1，得到 1..totalCycleDay 的结果。
        // 用 Math.floorMod 而不是 %：起始日晚于今天时 absDay 可能为负，% 会得到负数（曾出现"第 -7 天"）
        int absDay = calculateCurrentDay(startTime);
        return Math.floorMod(absDay - 1, totalCycleDay) + 1;
    }
}
