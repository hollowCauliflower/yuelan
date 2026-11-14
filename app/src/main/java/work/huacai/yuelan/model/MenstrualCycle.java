package work.huacai.yuelan.model;

import android.content.Context;

import androidx.annotation.ColorInt;

import work.huacai.yuelan.R;

/**
 * 月经周期的四个阶段。
 * <p>
 * 每个枚举都绑定了名称、描述、健康提示和建议色，便于在 UI 中统一取用；
 * 枚举声明顺序同时就是阶段顺序（月经期 → 卵泡期 → 排卵期 → 黄体期），
 * 因此 {@link #getIndex()} 与 {@link #getByIndex(int)} 可直接用 ordinal 互转。
 */
public enum MenstrualCycle {
    /**
     * 月经期（周期开头）
     */
    MENSTRUAL_PERIOD(
            R.string.cycle_menstrual_period_name,
            R.string.cycle_menstrual_period_description,
            R.string.cycle_menstrual_period_tips,
            R.color.menstrual_period_color,
            R.color.menstrual_period_color_dark
    ),
    /**
     * 卵泡期
     */
    FOLLICULAR_PHASE(
            R.string.cycle_follicular_phase_name,
            R.string.cycle_follicular_phase_description,
            R.string.cycle_follicular_phase_tips,
            R.color.follicular_phase_color,
            R.color.follicular_phase_color_dark
    ),
    /**
     * 排卵期
     */
    OVULATION_PERIOD(
            R.string.cycle_ovulation_period_name,
            R.string.cycle_ovulation_period_description,
            R.string.cycle_ovulation_period_tips,
            R.color.ovulation_period_color,
            R.color.ovulation_period_color_dark
    ),
    /**
     * 黄体期
     */
    LUTE_PHASE(
            R.string.cycle_lute_phase_name,
            R.string.cycle_lute_phase_description,
            R.string.cycle_lute_phase_tips,
            R.color.lute_phase_color,
            R.color.lute_phase_color_dark
    );

    /** 阶段名称 */
    private final int nameResId;
    /** 阶段描述 */
    private final int descriptionResId;
    /** 阶段健康提示（当建议数组资源缺失时的降级文案） */
    private final int tipsResId;
    /** 浅色模式下的阶段主色 */
    private final int colorResId;
    /** 深色模式下的阶段主色 */
    private final int colorDarkResId;

    MenstrualCycle(int nameResId, int descriptionResId, int tipsResId,
                   int colorResId, int colorDarkResId) {
        this.nameResId = nameResId;
        this.descriptionResId = descriptionResId;
        this.tipsResId = tipsResId;
        this.colorResId = colorResId;
        this.colorDarkResId = colorDarkResId;
    }

    /**
     * 获取本地化的阶段名称。
     */
    public String getDisplayName(Context context) {
        return context.getString(nameResId);
    }

    /**
     * 获取本地化的阶段描述。
     */
    public String getDescription(Context context) {
        return context.getString(descriptionResId);
    }

    /**
     * 获取本地化的阶段提示。
     */
    public String getTips(Context context) {
        return context.getString(tipsResId);
    }

    /**
     * 获取阶段颜色资源 ID。
     *
     * @param isDarkMode 是否使用深色模式配色
     * @return 颜色资源 ID（不是颜色值）
     */
    public int getColorResId(boolean isDarkMode) {
        return isDarkMode ? colorDarkResId : colorResId;
    }

    /**
     * 获取阶段颜色值。
     *
     * @param isDarkMode 是否使用深色模式配色
     */
    @ColorInt
    public int getColor(Context context, boolean isDarkMode) {
        return context.getResources().getColor(getColorResId(isDarkMode));
    }

    /**
     * 获取阶段颜色值，深浅色由当前系统配置自动判断。
     */
    @ColorInt
    public int getColor(Context context) {
        return getColor(context, isDarkMode(context));
    }

    /**
     * 阶段下标（0~3），与枚举声明顺序一致。
     */
    public int getIndex() {
        return ordinal();
    }

    /**
     * 按下标获取阶段。
     *
     * @param index 0~3
     * @return 对应阶段；下标越界时返回 {@link #MENSTRUAL_PERIOD}
     */
    public static MenstrualCycle getByIndex(int index) {
        MenstrualCycle[] cycles = values();
        if (index >= 0 && index < cycles.length) {
            return cycles[index];
        }
        return MENSTRUAL_PERIOD;
    }

    /**
     * 一次性获取所有阶段颜色值，供自定义 View 绘制时使用。
     */
    @ColorInt
    public static int[] getColors(Context context, boolean isDarkMode) {
        MenstrualCycle[] cycles = values();
        int[] colors = new int[cycles.length];
        for (int i = 0; i < cycles.length; i++) {
            colors[i] = cycles[i].getColor(context, isDarkMode);
        }
        return colors;
    }

    /**
     * 一次性获取所有阶段名称。
     */
    public static String[] getDisplayNames(Context context) {
        MenstrualCycle[] cycles = values();
        String[] names = new String[cycles.length];
        for (int i = 0; i < cycles.length; i++) {
            names[i] = cycles[i].getDisplayName(context);
        }
        return names;
    }

    /**
     * 读取系统配置判断当前是否为深色模式。
     */
    private static boolean isDarkMode(Context context) {
        int nightModeFlags = context.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }
}
