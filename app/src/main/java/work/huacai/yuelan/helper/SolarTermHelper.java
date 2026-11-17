package work.huacai.yuelan.helper;

import androidx.annotation.Nullable;

import java.util.Calendar;
import java.util.Date;

/**
 * 节气与节日计算。
 * <p>
 * 节气使用“21 世纪通用公式”推算：{@code [Y×0.2422 + C] - int(Y/4)}，
 * 其中 Y 为年份后两位、C 为每个节气的常量。该公式在 2000-2099 年内误差极小，
 * 用于每日建议的“今天是小寒”这类轻量提示已经足够。
 * <p>
 * 节日只覆盖公历固定节日与按星期推算的母亲节/父亲节；春节、中秋等农历节日不在此处计算，
 * 避免引入农历转换表。
 */
public final class SolarTermHelper {

    /** 二十四节气名称，与 {@link #CONSTANTS} / {@link #MONTHS} 一一对应（从小寒开始） */
    private static final String[] NAMES = {
            "小寒", "大寒", "立春", "雨水", "惊蛰", "春分",
            "清明", "谷雨", "立夏", "小满", "芒种", "夏至",
            "小暑", "大暑", "立秋", "处暑", "白露", "秋分",
            "寒露", "霜降", "立冬", "小雪", "大雪", "冬至"
    };

    /** 各节气对应的公式常量（21 世纪） */
    private static final double[] CONSTANTS = {
            5.4055, 20.12, 3.87, 18.73, 5.63, 20.646,
            4.81, 20.1, 5.52, 21.04, 5.678, 21.37,
            7.108, 22.83, 7.5, 23.13, 7.646, 23.042,
            8.318, 23.438, 7.438, 22.36, 7.18, 21.94
    };

    /** 各节气所在月份 */
    private static final int[] MONTHS = {
            1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6,
            7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12
    };

    private SolarTermHelper() {
    }

    /**
     * 判断指定日期是否是某个节气当天。
     *
     * @param date 目标日期，可为 null
     * @return 节气名称（如“立春”）；不是节气日或参数为空时返回 null
     */
    @Nullable
    public static String solarTermOf(@Nullable Date date) {
        if (date == null) {
            return null;
        }
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        int year = calendar.get(Calendar.YEAR);
        int month = calendar.get(Calendar.MONTH) + 1;
        int day = calendar.get(Calendar.DAY_OF_MONTH);
        int y = year % 100;

        for (int i = 0; i < NAMES.length; i++) {
            if (MONTHS[i] != month) {
                continue;
            }
            int termDay = (int) (y * 0.2422 + CONSTANTS[i]) - y / 4;
            if (termDay == day) {
                return NAMES[i];
            }
        }
        return null;
    }

    /**
     * 判断指定日期是否是公历节日。
     *
     * @param date 目标日期，可为 null
     * @return 节日名称（如“元旦”）；不是节日或参数为空时返回 null
     */
    @Nullable
    public static String festivalOf(@Nullable Date date) {
        if (date == null) {
            return null;
        }
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        int month = calendar.get(Calendar.MONTH) + 1;
        int day = calendar.get(Calendar.DAY_OF_MONTH);

        switch (month) {
            case 1:
                return day == 1 ? "元旦" : null;
            case 2:
                return day == 14 ? "情人节" : null;
            case 3:
                return day == 8 ? "妇女节" : null;
            case 5:
                if (day == 1) {
                    return "劳动节";
                }
                // 母亲节：五月第二个星期日
                return isNthWeekday(calendar, Calendar.SUNDAY, 2) ? "母亲节" : null;
            case 6:
                if (day == 1) {
                    return "儿童节";
                }
                // 父亲节：六月第三个星期日
                return isNthWeekday(calendar, Calendar.SUNDAY, 3) ? "父亲节" : null;
            case 10:
                return day == 1 ? "国庆节" : null;
            case 12:
                if (day == 24) {
                    return "平安夜";
                }
                return day == 25 ? "圣诞节" : null;
            default:
                return null;
        }
    }

    /**
     * 判断日期是否落在当月的第 n 个指定星期几。
     *
     * @param calendar 已设置为目标日期的 Calendar
     * @param weekday  星期常量（Calendar.SUNDAY 等）
     * @param n        第几个，从 1 开始
     * @return 命中返回 true
     */
    private static boolean isNthWeekday(Calendar calendar, int weekday, int n) {
        if (calendar.get(Calendar.DAY_OF_WEEK) != weekday) {
            return false;
        }
        int ordinal = (calendar.get(Calendar.DAY_OF_MONTH) - 1) / 7 + 1;
        return ordinal == n;
    }
}
