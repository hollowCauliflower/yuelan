package work.huacai.yuelan.helper;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.util.Date;
import java.util.List;

import work.huacai.yuelan.model.MenstrualRecord;

/**
 * 每日建议的里程碑基准。
 * <p>
 * 记录“第一次使用/第一次有记录”的日期，用于计算使用天数与纪念节点。
 * 只存在 SharedPreferences：不是业务数据，不参与备份与数据库升级。
 */
public final class SuggestionMilestoneStore {

    private static final String PREFS_NAME = "suggestion_milestone";
    private static final String KEY_FIRST_USE = "first_use_date";

    private SuggestionMilestoneStore() {
    }

    /**
     * 计算指定日期的使用天数（首日算第 1 天）。
     * <p>首次调用时会把“最早一条实际记录”或“今天”落为基准日并持久化，
     * 之后保持不变，避免使用天数每天漂移。
     *
     * @param context       任意 Context
     * @param date          要计算的日期
     * @param actualRecords 实际记录，可为 null（无记录时以今天为基准）
     * @return 使用天数；日期早于基准日或参数非法时返回 0
     */
    public static int getUsageDays(Context context, @Nullable Date date,
                                   @Nullable List<MenstrualRecord> actualRecords) {
        if (date == null) {
            return 0;
        }
        Date firstUse = CycleCalculator.parseDateId(firstUseDateId(context, actualRecords));
        if (firstUse == null) {
            return 0;
        }
        int diff = CycleCalculator.daysBetween(firstUse, date);
        return diff >= 0 ? diff + 1 : 0;
    }

    /**
     * 读取或初始化基准日。
     */
    private static String firstUseDateId(Context context, @Nullable List<MenstrualRecord> actualRecords) {
        SharedPreferences prefs = prefs(context);
        String saved = prefs.getString(KEY_FIRST_USE, null);
        if (saved != null && !saved.isEmpty()) {
            return saved;
        }

        String todayId = CycleCalculator.formatDateId(new Date());
        String earliest = null;
        if (actualRecords != null) {
            for (MenstrualRecord record : actualRecords) {
                String id = record == null ? null : record.getId();
                // 只接受 yyyyMMdd 形式的有效日期，并忽略未来的预测/异常数据
                if (id == null || id.length() != 8 || CycleCalculator.parseDateId(id) == null) {
                    continue;
                }
                if (id.compareTo(todayId) > 0) {
                    continue;
                }
                if (earliest == null || id.compareTo(earliest) < 0) {
                    earliest = id;
                }
            }
        }

        String resolved = earliest != null ? earliest : todayId;
        prefs.edit().putString(KEY_FIRST_USE, resolved).apply();
        return resolved;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
