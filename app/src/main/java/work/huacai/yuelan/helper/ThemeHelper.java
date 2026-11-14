package work.huacai.yuelan.helper;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

/**
 * 主题模式（跟随系统 / 浅色 / 深色）的读取与切换。
 * <p>
 * 偏好保存在应用配置（menstruation_config）中；进程启动时由 Application 调用
 * {@link #applySavedMode(Context)} 恢复，设置页修改后调用 {@link #saveAndApply(Context, int)}
 * 立即生效，AppCompat 会自动重建 Activity。
 */
public final class ThemeHelper {

    /** 跟随系统（默认） */
    public static final int MODE_SYSTEM = 0;
    /** 强制浅色 */
    public static final int MODE_LIGHT = 1;
    /** 强制深色 */
    public static final int MODE_DARK = 2;

    /** 应用配置使用的 SharedPreferences 文件名（与 MainActivity 一致） */
    private static final String PREF_CONFIG = "menstruation_config";
    /** 主题模式偏好 key */
    private static final String KEY_THEME_MODE = "theme_mode";

    private ThemeHelper() {
    }

    /**
     * 读取已保存的主题模式。
     *
     * @param context 上下文
     * @return {@link #MODE_SYSTEM} / {@link #MODE_LIGHT} / {@link #MODE_DARK}
     */
    public static int getSavedMode(Context context) {
        return prefs(context).getInt(KEY_THEME_MODE, MODE_SYSTEM);
    }

    /**
     * 进程启动时按已保存的模式恢复夜间模式。
     *
     * @param context 上下文
     */
    public static void applySavedMode(Context context) {
        AppCompatDelegate.setDefaultNightMode(toNightMode(getSavedMode(context)));
    }

    /**
     * 保存并立即应用主题模式（AppCompat 会自动重建 Activity）。
     *
     * @param context 上下文
     * @param mode    {@link #MODE_SYSTEM} / {@link #MODE_LIGHT} / {@link #MODE_DARK}
     */
    public static void saveAndApply(Context context, int mode) {
        prefs(context).edit().putInt(KEY_THEME_MODE, mode).apply();
        AppCompatDelegate.setDefaultNightMode(toNightMode(mode));
    }

    /**
     * 把自定义模式映射为 AppCompat 夜间模式常量。
     */
    private static int toNightMode(int mode) {
        switch (mode) {
            case MODE_LIGHT:
                return AppCompatDelegate.MODE_NIGHT_NO;
            case MODE_DARK:
                return AppCompatDelegate.MODE_NIGHT_YES;
            default:
                return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREF_CONFIG, Context.MODE_PRIVATE);
    }
}
