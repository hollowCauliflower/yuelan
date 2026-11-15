package work.huacai.yuelan.helper;

import android.content.Context;

import java.util.Random;

import work.huacai.yuelan.R;
import work.huacai.yuelan.model.MenstrualCycle;

/**
 * 建议生成辅助类。
 * <p>
 * 根据月经周期阶段（从预定义的多条建议中随机选一条）生成健康建议。
 * 天气不再参与建议文案生成，由每日建议界面单独展示天气行。
 */
public class GenerateSuggestionHelper {
    private static final Random RANDOM = new Random();

    private GenerateSuggestionHelper() {
        // 工具类禁止实例化
    }

    /**
     * 生成个性化建议。
     *
     * @param context 上下文（用于获取字符串资源）
     * @param cycle   当前周期阶段
     * @return 该阶段的一条随机建议文案
     */
    public static String generateSuggestion(Context context, MenstrualCycle cycle) {
        return getRandomPhaseAdvice(context, cycle);
    }

    /**
     * 从对应阶段的建议数组中随机取一条。
     * <p>
     * 若数组资源未定义或为空，降级使用 {@link MenstrualCycle#getTips(Context)}。
     */
    private static String getRandomPhaseAdvice(Context context, MenstrualCycle cycle) {
        int arrayResId = getSuggestionsArrayResId(cycle);
        if (arrayResId != 0) {
            String[] suggestions = context.getResources().getStringArray(arrayResId);
            if (suggestions.length > 0) {
                return suggestions[RANDOM.nextInt(suggestions.length)];
            }
        }
        // 降级：使用原有的单条建议
        return cycle.getTips(context);
    }

    /**
     * 返回阶段对应的建议数组资源 ID。
     * <p>
     * 需在 strings.xml 中定义以下数组：
     * menstrual_period_suggestions、follicular_phase_suggestions、
     * ovulation_period_suggestions、lute_phase_suggestions。
     *
     * @return 资源 ID；未知阶段返回 0
     */
    private static int getSuggestionsArrayResId(MenstrualCycle cycle) {
        switch (cycle) {
            case MENSTRUAL_PERIOD:
                return R.array.menstrual_period_suggestions;
            case FOLLICULAR_PHASE:
                return R.array.follicular_phase_suggestions;
            case OVULATION_PERIOD:
                return R.array.ovulation_period_suggestions;
            case LUTE_PHASE:
                return R.array.lute_phase_suggestions;
            default:
                return 0;
        }
    }
}
