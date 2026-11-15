package work.huacai.yuelan.helper;

import android.content.Context;

import androidx.annotation.Nullable;

import work.huacai.yuelan.R;
import work.huacai.yuelan.model.MenstrualCycle;
import work.huacai.yuelan.model.Weather;

/**
 * 天气 × 周期阶段的暖心提醒。
 * <p>
 * 只做展示，不写入数据库：把当天（以及明天）的天气与阶段结合，
 * 在容易受影响的组合上给一句提醒（例如"经期 + 降温"、"黄体期 + 阴雨"），
 * 没有明显影响时返回 null，由界面隐藏这一行。
 */
public final class WeatherSuggestionHelper {

    /** 温度无法解析时的标记值 */
    private static final int UNKNOWN_TEMPERATURE = Integer.MIN_VALUE;
    /** 明日降温达到该温差才提醒 */
    private static final int TEMPERATURE_DROP_THRESHOLD = 5;

    private WeatherSuggestionHelper() {
    }

    /**
     * 生成天气相关的暖心提醒。
     *
     * @param context   上下文（用于取文案）
     * @param phase     当天所属阶段
     * @param today     当天天气，可为 null
     * @param tomorrow  明天天气，可为 null（仅用于"明日降温"提醒）
     * @return 提醒文案；没有需要提醒的组合时返回 null
     */
    @Nullable
    public static String buildTip(Context context, MenstrualCycle phase,
                                  @Nullable Weather today, @Nullable Weather tomorrow) {
        if (context == null || phase == null || today == null) {
            return null;
        }

        int todayTemp = extractTemperature(today.getTemperature());
        int tomorrowTemp = extractTemperature(tomorrow != null ? tomorrow.getTemperature() : null);

        // 明日明显降温：比当天的常规建议更值得提前提醒
        if (todayTemp != UNKNOWN_TEMPERATURE && tomorrowTemp != UNKNOWN_TEMPERATURE
                && todayTemp - tomorrowTemp >= TEMPERATURE_DROP_THRESHOLD) {
            int drop = todayTemp - tomorrowTemp;
            if (phase == MenstrualCycle.MENSTRUAL_PERIOD) {
                return context.getString(R.string.weather_tip_tomorrow_drop_period, drop);
            }
            return context.getString(R.string.weather_tip_tomorrow_drop, drop);
        }

        String weatherText = today.getWeatherText();
        if (weatherText == null) {
            return null;
        }
        boolean rainy = weatherText.contains("雨");
        boolean snowy = weatherText.contains("雪");
        boolean foggy = weatherText.contains("雾");
        boolean sunny = weatherText.contains("晴朗");
        boolean cold = todayTemp != UNKNOWN_TEMPERATURE && todayTemp < 10;
        boolean hot = todayTemp != UNKNOWN_TEMPERATURE && todayTemp > 30;

        switch (phase) {
            case MENSTRUAL_PERIOD:
                if (cold) {
                    return context.getString(R.string.weather_tip_period_cold);
                }
                if (rainy || snowy) {
                    return context.getString(R.string.weather_tip_period_rain);
                }
                if (hot) {
                    return context.getString(R.string.weather_tip_period_hot);
                }
                return null;
            case LUTE_PHASE:
                if (cold) {
                    return context.getString(R.string.weather_tip_luteal_cold);
                }
                if (rainy || foggy) {
                    return context.getString(R.string.weather_tip_luteal_rain);
                }
                return null;
            case OVULATION_PERIOD:
                if (hot) {
                    return context.getString(R.string.weather_tip_ovulation_hot);
                }
                return null;
            case FOLLICULAR_PHASE:
                if (rainy || snowy) {
                    return context.getString(R.string.weather_tip_follicular_rain);
                }
                if (sunny && todayTemp != UNKNOWN_TEMPERATURE
                        && todayTemp >= 15 && todayTemp <= 28) {
                    return context.getString(R.string.weather_tip_follicular_sunny);
                }
                return null;
            default:
                return null;
        }
    }

    /**
     * 从 "24.3℃" 这类字符串中取出整数温度。
     *
     * @param temperature 带单位的温度文本，可为 null
     * @return 温度整数；无法解析时返回 {@link #UNKNOWN_TEMPERATURE}
     */
    private static int extractTemperature(@Nullable String temperature) {
        if (temperature == null || temperature.isEmpty()) {
            return UNKNOWN_TEMPERATURE;
        }
        String numeric = temperature.replace("℃", "").replace("°C", "").trim();
        try {
            // 接口返回的是 double，四舍五入到整数便于展示和比较
            return (int) Math.round(Double.parseDouble(numeric));
        } catch (NumberFormatException e) {
            return UNKNOWN_TEMPERATURE;
        }
    }
}
