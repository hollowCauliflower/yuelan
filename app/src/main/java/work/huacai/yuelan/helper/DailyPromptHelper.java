package work.huacai.yuelan.helper;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Calendar;
import java.util.Date;

import work.huacai.yuelan.dao.impl.MenstrualRecordDaoImpl;
import work.huacai.yuelan.model.MenstrualCycle;
import work.huacai.yuelan.model.MenstrualCycleRecord;
import work.huacai.yuelan.model.MenstrualRecord;

/**
 * 每日引导弹窗的判断与"今天不再提醒"标记管理。
 * <p>
 * 首页每次 {@code onResume} 时调用 {@link #getPromptType} 判断今天是否需要弹出引导，
 * 仅在三种"关键日期"弹出：预测月经期、预测排卵期、距下次月经 ≤ 3 天；
 * 其它阶段（卵泡期、黄体期中后段）不打扰用户。
 * <p>
 * 用户勾选"今天不再提醒"后，通过 {@link #markSkipToday} 写入当天日期，
 * 当天所有引导都不再弹出（第二天自动恢复）。
 */
public final class DailyPromptHelper {

    private static final String SP_NAME = "daily_prompt";
    /** 记录"今天不再提醒"的日期（yyyyMMdd），仅当天有效 */
    private static final String KEY_SKIP_DATE = "skip_date";
    /** 最近一次自动弹出引导的日期（yyyyMMdd） */
    private static final String KEY_LAST_SHOWN_DATE = "last_shown_date";
    /** 最近一次自动弹出引导的类型（{@link PromptType#name()}） */
    private static final String KEY_LAST_SHOWN_TYPE = "last_shown_type";

    /** 距下次月经多少天内弹出"临近经期"提醒 */
    private static final int APPROACHING_THRESHOLD_DAYS = 3;

    /** 引导类型 */
    public enum PromptType {
        /** 今天是预测月经期：引导用户记录流量/痛经，并提供纠错周期入口 */
        MENSTRUAL_PERIOD,
        /** 今天是预测排卵期：提示可以记录身体状况 */
        OVULATION,
        /** 距下次月经 ≤ 3 天：提醒准备卫生用品 */
        APPROACHING_PERIOD,
        /** 不需要弹出任何引导 */
        NONE
    }

    private DailyPromptHelper() {
        // 工具类禁止实例化
    }

    /**
     * 判断今天应该弹出哪种引导。
     *
     * @param context      上下文，用于读取 SP
     * @param cycleRecord  当前周期设置，用于推算下次经期
     * @return 引导类型；若用户已勾选"今天不再提醒"或不属于关键日期，返回 {@link PromptType#NONE}
     */
    @NonNull
    public static PromptType getPromptType(@NonNull Context context,
                                           @Nullable MenstrualCycleRecord cycleRecord) {
        if (shouldSkipToday(context)) {
            return PromptType.NONE;
        }

        String todayId = CycleCalculator.formatDateId(new Date());
        MenstrualRecord todayRecord = new MenstrualRecordDaoImpl().selectById(todayId);

        // 1) 当天有预测记录时，按阶段判断是否属于"关键日期"
        if (todayRecord != null && todayRecord.isPrediction()) {
            MenstrualCycle cycle = todayRecord.getMenstrualCycle();
            if (cycle == MenstrualCycle.MENSTRUAL_PERIOD) {
                return PromptType.MENSTRUAL_PERIOD;
            }
            if (cycle == MenstrualCycle.OVULATION_PERIOD) {
                return PromptType.OVULATION;
            }
            // 卵泡期、黄体期不主动引导
            return PromptType.NONE;
        }

        // 2) 没有预测记录或已是实际记录时，检查是否临近下次经期
        if (cycleRecord == null || cycleRecord.getStartTime() == null
                || cycleRecord.getTotalCycleDay() <= 0) {
            return PromptType.NONE;
        }

        Date currentCycleStart = CycleCalculator.getCurrentCycleStart(
                cycleRecord.getStartTime(), cycleRecord.getTotalCycleDay());
        if (currentCycleStart == null) {
            return PromptType.NONE;
        }

        Calendar nextPeriod = Calendar.getInstance();
        nextPeriod.setTime(currentCycleStart);
        nextPeriod.add(Calendar.DAY_OF_MONTH, cycleRecord.getTotalCycleDay());

        int daysUntil = CycleCalculator.daysBetween(CycleCalculator.normalizeDate(new Date()),
                nextPeriod.getTime());
        // 下次经期还没到且在阈值内：弹出临近提醒（已经到了的话上面月经期分支会先命中）
        if (daysUntil > 0 && daysUntil <= APPROACHING_THRESHOLD_DAYS) {
            return PromptType.APPROACHING_PERIOD;
        }

        return PromptType.NONE;
    }

    /**
     * 用户是否已勾选"今天不再提醒"。
     *
     * @return true 表示当天已标记跳过
     */
    public static boolean shouldSkipToday(@NonNull Context context) {
        SharedPreferences sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        String skipDate = sp.getString(KEY_SKIP_DATE, null);
        String today = CycleCalculator.formatDateId(new Date());
        return today.equals(skipDate);
    }

    /**
     * 标记"今天不再提醒"，写入当天日期。
     */
    public static void markSkipToday(@NonNull Context context) {
        SharedPreferences sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_SKIP_DATE, CycleCalculator.formatDateId(new Date())).apply();
    }

    /**
     * 判断今天是否已自动弹出过指定类型的引导。
     * <p>每天每类引导只自动弹一次，避免每次回到首页都打扰用户。
     *
     * @param context 上下文
     * @param type    引导类型
     * @return true 表示今天已弹过该类型
     */
    public static boolean wasShownToday(@NonNull Context context, @NonNull PromptType type) {
        SharedPreferences sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        String today = CycleCalculator.formatDateId(new Date());
        return today.equals(sp.getString(KEY_LAST_SHOWN_DATE, null))
                && type.name().equals(sp.getString(KEY_LAST_SHOWN_TYPE, null));
    }

    /**
     * 记录今天已自动弹出过指定类型的引导。
     *
     * @param context 上下文
     * @param type    引导类型
     */
    public static void markShownToday(@NonNull Context context, @NonNull PromptType type) {
        SharedPreferences sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        sp.edit()
                .putString(KEY_LAST_SHOWN_DATE, CycleCalculator.formatDateId(new Date()))
                .putString(KEY_LAST_SHOWN_TYPE, type.name())
                .apply();
    }
}
