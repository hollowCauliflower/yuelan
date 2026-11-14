package work.huacai.yuelan.helper;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import java.util.Random;

import work.huacai.yuelan.R;
import work.huacai.yuelan.model.MenstrualCycle;
import work.huacai.yuelan.model.Weather;

/**
 * 建议生成辅助类。
 * <p>
 * 根据月经周期阶段（从预定义的多条建议中随机选一条）和当天的天气信息拼接出个性化健康建议。
 * 资源缺失或天气为空时会自动降级，保证始终能返回一段可用文案。
 */
public class GenerateSuggestionHelper {
    private static final String TAG = "GenerateSuggestionHelper";
    private static final Random RANDOM = new Random();

    private GenerateSuggestionHelper() {
        // 工具类禁止实例化
    }

    /**
     * 生成个性化建议。
     *
     * @param context 上下文（用于获取字符串资源）
     * @param cycle   当前周期阶段
     * @param weather 当前天气信息，可为 null（此时只返回阶段建议）
     * @return “阶段建议 + 空格 + 天气建议”；天气建议为空时只返回阶段建议
     */
    public static String generateSuggestion(Context context, MenstrualCycle cycle, Weather weather) {
        // 1. 随机取一条该阶段的建议
        String phaseAdvice = getRandomPhaseAdvice(context, cycle);

        // 2. 取天气相关的附加建议（可能为空串）
        String weatherAdvice = getWeatherAdvice(context, weather);
        Log.d(TAG, "generateSuggestion: " + weatherAdvice);

        // 3. 拼接：天气建议为空时避免留下多余空格
        if (!TextUtils.isEmpty(weatherAdvice)) {
            return phaseAdvice + " " + weatherAdvice;
        }
        return phaseAdvice;
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

    /**
     * 根据天气现象与温度生成附加建议。
     * <p>
     * 天气文案来自 {@code OpenMeteoHelper#decodeWeatherCode}，只会是以下固定词之一：
     * 晴朗、多云、阴天、阴、雾、毛毛雨、雨、雪、阵雨、雷雨、未知。
     *
     * @param weather 天气对象，可为 null
     * @return 附加建议文本；无匹配项或天气为空时返回空串
     */
    private static String getWeatherAdvice(Context context, Weather weather) {
        if (weather == null) return "";

        String weatherText = weather.getWeatherText();
        String tempStr = weather.getTemperature();
        // 无法解析温度时返回 Integer.MIN_VALUE，后续按“温度未知”处理
        int temperature = extractTemperature(tempStr);

        StringBuilder advice = new StringBuilder();

        if (weatherText != null) {
            // “雨”已包含“阵雨”“毛毛雨”等词，无需重复判断
            if (weatherText.contains("雨")) {
                advice.append(context.getString(R.string.weather_rainy));
            } else if (weatherText.contains("雪")) {
                advice.append(context.getString(R.string.weather_snowy));
            } else if (weatherText.contains("雾")) {
                advice.append(context.getString(R.string.weather_foggy));
            } else if (weatherText.contains("晴朗") && temperature < 30) {
                // 晴且不炎热时才提示防晒/补水类建议
                advice.append(context.getString(R.string.weather_sunny));
            }
        }

        if (temperature != Integer.MIN_VALUE) {
            if (temperature < 10) {
                advice.append(context.getString(R.string.weather_cold));
            } else if (temperature > 30) {
                advice.append(context.getString(R.string.weather_hot));
            }
        }

        return advice.toString();
    }

    /**
     * 从温度字符串中提取整数温度值（如 "25℃" → 25）。
     *
     * @param tempStr 形如 "23℃" 的字符串，可为 null
     * @return 温度整数；空串或无法解析时返回 {@link Integer#MIN_VALUE} 作为“未知”标记
     */
    private static int extractTemperature(String tempStr) {
        if (tempStr == null || tempStr.isEmpty()) {
            return Integer.MIN_VALUE;
        }
        // 接口返回的温度带单位，去掉单位后再转整数
        String numeric = tempStr.replace("℃", "").replace("°C", "").trim();
        try {
            return Integer.parseInt(numeric);
        } catch (NumberFormatException e) {
            return Integer.MIN_VALUE;
        }
    }
}
