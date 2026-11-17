package work.huacai.yuelan.helper;

import android.content.Context;

import androidx.annotation.Nullable;

import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import work.huacai.yuelan.model.MenstrualCycle;
import work.huacai.yuelan.model.MenstrualCycleRecord;
import work.huacai.yuelan.model.MenstrualRecord;
import work.huacai.yuelan.model.Reminder;
import work.huacai.yuelan.model.Weather;

/**
 * 把界面层能拿到的数据组装成 {@link SuggestionContext}。
 * <p>
 * 只做“取值 + 换算”，不生成文案，也不读写数据库以外的东西：
 * 周期天数、阶段内天数、距下次经期天数统一走 {@link CycleCalculator} 与
 * {@link ReminderCycleHelper}，避免与日历/提醒形成第二套口径。
 */
public final class SuggestionContextHelper {

    private SuggestionContextHelper() {
    }

    /**
     * 构建建议上下文（语气取用户在设置页选择的语气）。
     */
    public static SuggestionContext build(Context context, Date date,
                                          @Nullable MenstrualRecord record,
                                          @Nullable MenstrualCycle phase,
                                          @Nullable Weather weather,
                                          @Nullable MenstrualCycleRecord cycleRecord,
                                          @Nullable List<MenstrualRecord> actualRecords,
                                          @Nullable String reminderTime,
                                          @Nullable String reminderTitle) {
        return build(context, date, record, phase, weather, cycleRecord, actualRecords,
                reminderTime, reminderTitle, SuggestionToneStore.getTone(context));
    }

    /**
     * 构建建议上下文。
     *
     * @param context     用于取阶段中文名
     * @param date        要展示的日期
     * @param record      当天记录，可为 null（此时按预测阶段展示）
     * @param phase       当天阶段（实际记录优先，其次预测），可为 null
     * @param weather     当天天气，可为 null（未加载或不可用）
     * @param cycleRecord 最近一次周期参数，可为 null
     * @param actualRecords 全部实际记录，用于规律性/连续打卡/痛经趋势；可为 null
     * @param reminderTime  当天最近一条提醒的时间（HH:mm），无提醒时传 null
     * @param reminderTitle 当天最近一条提醒的标题，无提醒时传 null
     * @param tone          语气人格（gentle / brief / cheerful），非法值按温柔处理
     * @return 建议上下文；日期非法时仍会返回一个最小可用上下文
     */
    public static SuggestionContext build(Context context, Date date,
                                          @Nullable MenstrualRecord record,
                                          @Nullable MenstrualCycle phase,
                                          @Nullable Weather weather,
                                          @Nullable MenstrualCycleRecord cycleRecord,
                                          @Nullable List<MenstrualRecord> actualRecords,
                                          @Nullable String reminderTime,
                                          @Nullable String reminderTitle,
                                          @Nullable String tone) {
        String dateId = date == null ? null : CycleCalculator.formatDateId(date);
        String todayId = CycleCalculator.formatDateId(new Date());
        boolean isToday = dateId != null && dateId.equals(todayId);

        String phaseName = phase == null ? null : phase.name();
        String phaseDisplayName = phase == null ? null : phase.getDisplayName(context);

        int dayInCycle = 0;
        int dayInPhase = 0;
        int totalCycleDay = 0;
        if (cycleRecord != null && cycleRecord.getStartTime() != null
                && cycleRecord.getTotalCycleDay() > 0 && date != null) {
            totalCycleDay = cycleRecord.getTotalCycleDay();
            dayInCycle = dayInCycleOf(date, cycleRecord);
            dayInPhase = dayInPhaseOf(dayInCycle, phase, cycleRecord);
        }

        // 距下次经期天数：
        // - 今天优先用 ReminderCycleHelper，与提醒功能保持同一口径；
        // - 历史日期不能再用“相对今天”的接口，按周期参数直接推算，
        //   这样所有日期都能拿到倒计时，开场模板的可选池不会因日期而缩水
        int daysToNextPeriod = -1;
        if (dayInCycle > 0) {
            if (isToday) {
                Date nextPeriod = ReminderCycleHelper.nextPeriodStart();
                if (nextPeriod != null) {
                    daysToNextPeriod = CycleCalculator.daysBetween(
                            CycleCalculator.normalizeDate(new Date()), nextPeriod);
                }
            } else {
                daysToNextPeriod = totalCycleDay - dayInCycle + 1;
            }
        }

        boolean hasRecord = record != null && !record.isPrediction();
        int painLevel = record == null ? 0 : record.getPainLevel();
        int flowLevel = record == null ? 0 : record.getFlowLevel();

        String weatherText = weather == null ? null : weather.getWeatherText();
        Integer temperature = weather == null ? null : parseTemperature(weather.getTemperature());

        // 历史信号：规律性、连续记录天数、近期痛经趋势。
        // 三者都基于实际记录计算，与统计页共用 CycleStatsHelper 的切分口径。
        List<List<MenstrualRecord>> cycles = actualRecords == null
                ? Collections.emptyList()
                : CycleStatsHelper.splitCycles(actualRecords);
        String regularity = CycleStatsHelper.regularityOf(cycles);
        int streak = CycleStatsHelper.recordStreak(actualRecords, new Date());
        boolean painTrend = CycleStatsHelper.recentPeriodPainTrend(actualRecords);
        // 周期序号：切分出的轮次数即“当前是第几个记录周期”
        int cycleNo = cycles.size();
        // 周期趋势：最近一个完整周期相对此前平均长度的变化天数
        int cycleDiff = CycleStatsHelper.recentCycleTrend(cycles);
        // 里程碑：首日算第 1 天，首次读取时以最早的实际记录（或今天）为基准
        int usageDays = SuggestionMilestoneStore.getUsageDays(context, date, actualRecords);
        // 微习惯只对“今天”有意义，历史/未来日期不读取当天打卡
        boolean sleepDone = isToday && MicroHabitStore.isSleepDone(context, dateId);
        boolean waterDone = isToday && MicroHabitStore.isWaterDone(context, dateId);
        // 节气与节日：同一天最多命中一个名称，节日优先于节气
        String solarTermName = SolarTermHelper.solarTermOf(date);
        String holidayName = SolarTermHelper.festivalOf(date);
        String festivalName = holidayName != null ? holidayName : solarTermName;
        // 扩展天气维度，缺项时保持 null，由模板自行跳过
        Integer humidity = weather == null ? null : weather.getHumidityPercent();
        Integer uvIndex = weather == null || weather.getUvIndex() == null
                ? null : (int) Math.round(weather.getUvIndex());
        Integer airQuality = weather == null ? null : weather.getAirQualityIndex();
        Integer pollen = weather == null ? null : weather.getPollenIndex();
        int mood = record == null ? MenstrualRecord.MOOD_NONE : record.getMood();

        return new SuggestionContext.Builder()
                .dateId(dateId)
                .phase(phaseName, phaseDisplayName)
                .dayInCycle(dayInCycle)
                .dayInPhase(dayInPhase)
                .daysToNextPeriod(daysToNextPeriod)
                .record(hasRecord, painLevel, flowLevel)
                .weather(weatherText, temperature)
                .season(seasonOf(date))
                .history(regularity, streak, painTrend)
                .reminder(reminderTime, reminderTitle)
                .tone(tone)
                .today(isToday)
                .milestone(usageDays, cycleNo)
                .cycleDiff(cycleDiff)
                .mood(mood)
                .habits(sleepDone, waterDone)
                .festival(festivalName, solarTermName != null, holidayName != null)
                .air(humidity, uvIndex, airQuality, pollen)
                .build();
    }

    /**
     * 找出指定日期最早的一条启用提醒。
     * <p>与日历圆点、弹窗提醒列表使用同一个 {@link ReminderCycleHelper#occursOn} 口径，
     * 避免“有提醒的日子建议里却不提醒”。
     *
     * @param reminders 全部提醒，可为 null
     * @param day       目标日期，可为 null
     * @return 最早的一条提醒；没有命中时返回 null
     */
    @Nullable
    public static Reminder firstReminderOn(@Nullable List<Reminder> reminders, @Nullable Date day) {
        if (reminders == null || reminders.isEmpty() || day == null) {
            return null;
        }
        Reminder earliest = null;
        for (Reminder reminder : reminders) {
            if (reminder == null || !reminder.isEnabled()
                    || !ReminderCycleHelper.occursOn(reminder, day)) {
                continue;
            }
            if (earliest == null
                    || compareTime(reminder.getAlarmTime(), earliest.getAlarmTime()) < 0) {
                earliest = reminder;
            }
        }
        return earliest;
    }

    /** 比较 "HH:mm" 时间；空值排最后 */
    private static int compareTime(@Nullable String first, @Nullable String second) {
        if (first == null && second == null) {
            return 0;
        }
        if (first == null) {
            return 1;
        }
        if (second == null) {
            return -1;
        }
        return first.compareTo(second);
    }

    /**
     * 指定日期在周期内是第几天（从 1 开始）。
     * <p>与 {@link CycleCalculator#getDayInCurrentCycle(Date, int)} 使用同一算法，
     * 差别只是把“今天”换成任意日期，便于查看历史记录时也能得到正确天数。
     */
    private static int dayInCycleOf(Date date, MenstrualCycleRecord cycleRecord) {
        int total = cycleRecord.getTotalCycleDay();
        if (total <= 0) {
            return 0;
        }
        int diffDays = CycleCalculator.daysBetween(cycleRecord.getStartTime(), date);
        return Math.floorMod(diffDays, total) + 1;
    }

    /**
     * 计算日期在所属阶段内的第几天。
     * <p>阶段起始日由 {@link CycleCalculator#calculatePhaseDays(int, int)} 的分段长度累加得出。
     * 若换算结果落在该阶段区间之外（例如用户手动补签了与预测不一致的阶段），
     * 返回 0 表示未知，避免让“第 1 天”“经期尾声”这类标签错误命中。
     */
    private static int dayInPhaseOf(int dayInCycle, @Nullable MenstrualCycle phase,
                                    MenstrualCycleRecord cycleRecord) {
        if (dayInCycle <= 0 || phase == null) {
            return 0;
        }
        int[] phaseDays = CycleCalculator.calculatePhaseDays(
                cycleRecord.getMenstrualPeriodDay(), cycleRecord.getTotalCycleDay());
        int index = phase.getIndex();
        if (index < 0 || index >= phaseDays.length) {
            return 0;
        }

        int startDayInCycle = 1;
        for (int i = 0; i < index; i++) {
            startDayInCycle += phaseDays[i];
        }
        int dayInPhase = dayInCycle - startDayInCycle + 1;
        if (dayInPhase < 1 || dayInPhase > phaseDays[index]) {
            return 0;
        }
        return dayInPhase;
    }

    /**
     * 从 “24.3℃” 这类文本中取整数值；解析失败返回 null，模板会自动跳过依赖气温的片段。
     */
    @Nullable
    private static Integer parseTemperature(@Nullable String temperature) {
        if (temperature == null || temperature.trim().isEmpty()) {
            return null;
        }
        String numeric = temperature.replace("℃", "").replace("°C", "").trim();
        try {
            return (int) Math.round(Double.parseDouble(numeric));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 日期所属季节标记；日期为空时返回 null。
     */
    @Nullable
    private static String seasonOf(@Nullable Date date) {
        if (date == null) {
            return null;
        }
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        return SuggestionEngine.seasonOf(calendar.get(Calendar.MONTH) + 1);
    }
}
