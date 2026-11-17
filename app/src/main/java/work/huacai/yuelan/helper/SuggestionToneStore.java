package work.huacai.yuelan.helper;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 每日建议的语气人格设置。
 * <p>
 * 语气只影响文案措辞，不影响任何周期数据，因此放在 SharedPreferences：
 * 无需数据库版本变更，也不进入备份文件，卸载即清空。
 */
public final class SuggestionToneStore {

    /** 温柔（默认）：平辈朋友口吻，句子完整 */
    public static final String TONE_GENTLE = "gentle";
    /** 简洁：短句直给，尽量减少修饰 */
    public static final String TONE_BRIEF = "brief";
    /** 元气：轻快有活力的鼓励口吻 */
    public static final String TONE_CHEERFUL = "cheerful";

    private static final String PREFS_NAME = "suggestion_tone";
    private static final String KEY_TONE = "tone";

    private SuggestionToneStore() {
    }

    /**
     * 读取用户选择的语气。
     *
     * @param context 任意 Context
     * @return 合法语气值；未设置或数据损坏时返回 {@link #TONE_GENTLE}
     */
    public static String getTone(Context context) {
        String tone = prefs(context).getString(KEY_TONE, TONE_GENTLE);
        return isValid(tone) ? tone : TONE_GENTLE;
    }

    /**
     * 保存语气选择。
     *
     * @param context 任意 Context
     * @param tone    语气值，非法时回退为温柔
     */
    public static void setTone(Context context, String tone) {
        prefs(context).edit().putString(KEY_TONE, isValid(tone) ? tone : TONE_GENTLE).apply();
    }

    /**
     * 是否为受支持的语气值。
     *
     * @param tone 待校验的语气
     * @return 合法返回 true
     */
    public static boolean isValid(String tone) {
        return TONE_GENTLE.equals(tone) || TONE_BRIEF.equals(tone) || TONE_CHEERFUL.equals(tone);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
