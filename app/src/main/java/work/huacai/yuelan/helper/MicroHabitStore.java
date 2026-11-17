package work.huacai.yuelan.helper;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 睡眠 / 饮水微习惯打卡的本地存储。
 * <p>
 * 每天两个可勾选的小目标，按日期各存一条布尔值。属于轻量打卡数据，
 * 不进入数据库与备份文件，卸载即清空。
 */
public final class MicroHabitStore {

    private static final String PREFS_NAME = "micro_habit";
    private static final String KEY_SLEEP_PREFIX = "sleep_";
    private static final String KEY_WATER_PREFIX = "water_";

    private MicroHabitStore() {
    }

    /**
     * 指定日期是否已完成睡眠打卡。
     *
     * @param context 任意 Context
     * @param dateId  日期（yyyyMMdd）
     * @return 已打卡返回 true
     */
    public static boolean isSleepDone(Context context, String dateId) {
        return dateId != null && prefs(context).getBoolean(KEY_SLEEP_PREFIX + dateId, false);
    }

    /**
     * 指定日期是否已完成饮水打卡。
     *
     * @param context 任意 Context
     * @param dateId  日期（yyyyMMdd）
     * @return 已打卡返回 true
     */
    public static boolean isWaterDone(Context context, String dateId) {
        return dateId != null && prefs(context).getBoolean(KEY_WATER_PREFIX + dateId, false);
    }

    /**
     * 保存睡眠打卡状态。
     */
    public static void setSleepDone(Context context, String dateId, boolean done) {
        if (dateId == null) {
            return;
        }
        prefs(context).edit().putBoolean(KEY_SLEEP_PREFIX + dateId, done).apply();
    }

    /**
     * 保存饮水打卡状态。
     */
    public static void setWaterDone(Context context, String dateId, boolean done) {
        if (dateId == null) {
            return;
        }
        prefs(context).edit().putBoolean(KEY_WATER_PREFIX + dateId, done).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
