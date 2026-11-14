package work.huacai.yuelan.helper;

import android.content.Context;

import java.text.DateFormatSymbols;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import work.huacai.yuelan.R;
import work.huacai.yuelan.model.Reminder;

/**
 * 提醒重复规则的计算与文案工具。
 * <p>
 * 重复规则存于 reminder 表：{@code repeat_type} 表示规则类型，{@code repeat_config} 存参数
 * （每周模式为 1-7 的星期几、每月模式为 1-31 的日期，均用逗号分隔；间隔模式为天数；
 * 指定多个日期模式为逗号分隔的 yyyyMMdd），{@code repeat_end_day} 为重复结束日期
 * （0 表示长期有效，指定多个日期模式不使用该字段）。
 * <p>
 * 这里只做纯日期计算，不依赖通知/闹钟，供调度器、日历与列表共用。
 */
public final class ReminderRepeatHelper {

    /** 单次查找下一次触发日的最大搜索天数，避免异常配置导致死循环 */
    private static final int MAX_SEARCH_DAYS = 400;

    private ReminderRepeatHelper() {
    }

    /**
     * 判断某一天是否命中该重复规则（忽略提醒时间，只看日期）。
     *
     * @param reminder 重复提醒
     * @param day      待判断的日期
     * @return 命中返回 true
     */
    public static boolean matches(Reminder reminder, Date day) {
        if (reminder == null || !reminder.isRepeatMode() || day == null) {
            return false;
        }
        Calendar target = Calendar.getInstance();
        target.setTime(day);
        normalize(target);
        // 指定多个日期：每个选中日期各触发一次，与开始/结束日期无关
        if (reminder.getRepeatType() == Reminder.REPEAT_MULTI_DATE) {
            return containsInt(reminder.getRepeatConfig(), toDayId(target));
        }
        Calendar start = parseStart(reminder);
        if (start == null) {
            return false;
        }
        if (target.before(start)) {
            return false;
        }
        if (reminder.getRepeatEndDay() > 0) {
            Calendar end = parseDay(reminder.getRepeatEndDay());
            if (end == null || target.after(end)) {
                return false;
            }
        }

        switch (reminder.getRepeatType()) {
            case Reminder.REPEAT_DAILY:
                return true;
            case Reminder.REPEAT_WEEKLY:
                return containsInt(reminder.getRepeatConfig(), target.get(Calendar.DAY_OF_WEEK));
            case Reminder.REPEAT_MONTHLY:
                return containsInt(reminder.getRepeatConfig(), target.get(Calendar.DAY_OF_MONTH));
            case Reminder.REPEAT_INTERVAL:
                int interval = parseInterval(reminder.getRepeatConfig());
                if (interval <= 0) {
                    return false;
                }
                return daysBetween(start, target) % interval == 0;
            default:
                return false;
        }
    }

    /**
     * 计算下一次触发时间（日期 + 提醒时间）。
     *
     * @param reminder 重复提醒
     * @param nowMillis 当前时间戳，早于该时间的候选会被跳过
     * @return 下一次触发时间；规则已结束或数据非法时返回 null
     */
    public static Date nextOccurrenceAfter(Reminder reminder, long nowMillis) {
        if (reminder == null || !reminder.isRepeatMode()) {
            return null;
        }
        int hour = 0;
        int minute = 0;
        String time = reminder.getAlarmTime();
        if (time != null) {
            String[] parts = time.split(":");
            if (parts.length == 2) {
                try {
                    hour = Integer.parseInt(parts[0].trim());
                    minute = Integer.parseInt(parts[1].trim());
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
            return null;
        }

        // 指定多个日期：直接在所有选中日期里找最早的未来触发时间，
        // 不受开始日期与最大搜索天数限制
        if (reminder.getRepeatType() == Reminder.REPEAT_MULTI_DATE) {
            Calendar best = null;
            for (int dayId : parseInts(reminder.getRepeatConfig())) {
                Calendar occurrence = parseDay(dayId);
                if (occurrence == null) {
                    continue;
                }
                occurrence.set(Calendar.HOUR_OF_DAY, hour);
                occurrence.set(Calendar.MINUTE, minute);
                if (occurrence.getTimeInMillis() > nowMillis
                        && (best == null || occurrence.before(best))) {
                    best = occurrence;
                }
            }
            return best != null ? best.getTime() : null;
        }

        Calendar start = parseStart(reminder);
        if (start == null) {
            return null;
        }
        Calendar cursor = Calendar.getInstance();
        cursor.setTimeInMillis(nowMillis);
        normalize(cursor);
        if (cursor.before(start)) {
            cursor.setTime(start.getTime());
        }

        Calendar candidate = Calendar.getInstance();
        for (int i = 0; i < MAX_SEARCH_DAYS; i++) {
            if (matches(reminder, cursor.getTime())) {
                candidate.setTime(cursor.getTime());
                candidate.set(Calendar.HOUR_OF_DAY, hour);
                candidate.set(Calendar.MINUTE, minute);
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
     * 列出日期区间内所有命中的重复日期（用于日历展示）。
     *
     * @param reminder 重复提醒
     * @param from     区间开始（含）
     * @param to       区间结束（含）
     * @return 命中的日期列表，按时间升序
     */
    public static List<Date> occurrencesBetween(Reminder reminder, Date from, Date to) {
        List<Date> result = new ArrayList<>();
        if (reminder == null || !reminder.isRepeatMode() || from == null || to == null) {
            return result;
        }
        Calendar cursor = Calendar.getInstance();
        cursor.setTime(from);
        normalize(cursor);
        Calendar end = Calendar.getInstance();
        end.setTime(to);
        normalize(end);
        while (!cursor.after(end)) {
            if (matches(reminder, cursor.getTime())) {
                result.add(cursor.getTime());
            }
            cursor.add(Calendar.DAY_OF_MONTH, 1);
        }
        return result;
    }

    /**
     * 生成重复规则的中文/本地化描述，如“每天”“每周一、三”“每月15日”“每3天”。
     *
     * @param context  任意 Context
     * @param reminder 重复提醒
     * @return 规则描述；非重复提醒返回空串
     */
    public static String describe(Context context, Reminder reminder) {
        if (context == null || reminder == null || !reminder.isRepeatMode()) {
            return "";
        }
        switch (reminder.getRepeatType()) {
            case Reminder.REPEAT_DAILY:
                return context.getString(R.string.reminder_repeat_daily);
            case Reminder.REPEAT_WEEKLY:
                return context.getString(R.string.reminder_repeat_weekly_format,
                        joinWeekdays(reminder.getRepeatConfig()));
            case Reminder.REPEAT_MONTHLY:
                return context.getString(R.string.reminder_repeat_monthly_format,
                        joinInts(reminder.getRepeatConfig(), "、"));
            case Reminder.REPEAT_INTERVAL:
                return context.getString(R.string.reminder_repeat_interval_format,
                        parseInterval(reminder.getRepeatConfig()));
            case Reminder.REPEAT_MULTI_DATE:
                return context.getString(R.string.reminder_repeat_multi_date_format,
                        parseInts(reminder.getRepeatConfig()).size());
            default:
                return "";
        }
    }

    /** 星期数字（1=周日 … 7=周六）按当前语言拼成“一、三、五”样式的短名 */
    private static String joinWeekdays(String config) {
        DateFormatSymbols symbols = DateFormatSymbols.getInstance(Locale.getDefault());
        String[] names = symbols.getShortWeekdays();
        // 展示顺序固定为周一到周日，与编辑弹窗中的选择顺序保持一致
        int[] order = {Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
                Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY};
        StringBuilder builder = new StringBuilder();
        for (int day : order) {
            if (!containsInt(config, day)) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("、");
            }
            builder.append(names[day]);
        }
        return builder.toString();
    }

    /** 把逗号分隔的整数转成展示文本 */
    private static String joinInts(String config, String separator) {
        StringBuilder builder = new StringBuilder();
        for (int value : parseInts(config)) {
            if (builder.length() > 0) {
                builder.append(separator);
            }
            builder.append(value);
        }
        return builder.toString();
    }

    /** 解析 start 日期（targetDay）并归零到当天 00:00 */
    private static Calendar parseStart(Reminder reminder) {
        Calendar start = parseDay(reminder.getTargetDay());
        return start;
    }

    /** yyyyMMdd 整数转 Calendar，非法返回 null */
    private static Calendar parseDay(int day) {
        if (day <= 0) {
            return null;
        }
        int year = day / 10000;
        int month = (day / 100) % 100;
        int date = day % 100;
        if (year <= 0 || month <= 0 || month > 12 || date <= 0 || date > 31) {
            return null;
        }
        Calendar calendar = Calendar.getInstance();
        calendar.set(year, month - 1, date, 0, 0, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar;
    }

    /** Calendar 转 yyyyMMdd 整数，与提醒表的 target_day / repeat_config 保持同一格式 */
    private static int toDayId(Calendar calendar) {
        return calendar.get(Calendar.YEAR) * 10000
                + (calendar.get(Calendar.MONTH) + 1) * 100
                + calendar.get(Calendar.DAY_OF_MONTH);
    }

    /** 归零时分秒，只保留日期部分 */
    private static void normalize(Calendar calendar) {
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
    }

    /** 两个日期相差的天数（忽略时分秒） */
    private static long daysBetween(Calendar start, Calendar end) {
        long diff = end.getTimeInMillis() - start.getTimeInMillis();
        return Math.round(diff / (24f * 60 * 60 * 1000));
    }

    /** 解析间隔天数 */
    private static int parseInterval(String config) {
        if (config == null) {
            return 0;
        }
        try {
            return Integer.parseInt(config.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 解析逗号分隔的整数集合 */
    private static List<Integer> parseInts(String config) {
        List<Integer> result = new ArrayList<>();
        if (config == null || config.trim().isEmpty()) {
            return result;
        }
        for (String part : config.split(",")) {
            try {
                result.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException ignored) {
                // 忽略非法项，避免一条脏数据导致整个提醒失效
            }
        }
        return result;
    }

    /** 判断逗号分隔集合中是否包含指定值 */
    private static boolean containsInt(String config, int value) {
        return parseInts(config).contains(value);
    }
}
