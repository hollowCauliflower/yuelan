package work.huacai.yuelan.helper;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 建议反馈的本地存储。
 * <p>
 * “有用 / 换一条”只影响文案排序，不影响周期数据，因此放在 SharedPreferences：
 * 无需数据库版本变更，也不进入备份文件，卸载即清空。
 */
public final class SuggestionFeedbackStore {

    private static final String PREFS_NAME = "suggestion_feedback";
    private static final String KEY_PREFIX = "score_";
    /** 与引擎的反馈上限保持一致 */
    private static final int MAX_SCORE = 50;
    private static final int MIN_SCORE = -50;
    /** 单次“有用”加分 */
    private static final int LIKE_DELTA = 5;
    /** 单次“换一条”减分 */
    private static final int DISLIKE_DELTA = -5;

    private SuggestionFeedbackStore() {
    }

    /**
     * 读取全部反馈分。
     *
     * @param context 任意 Context
     * @return 模板 id → 反馈分；无数据时返回空 Map
     */
    public static Map<String, Integer> loadScores(Context context) {
        Map<String, Integer> scores = new HashMap<>();
        SharedPreferences prefs = prefs(context);
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(KEY_PREFIX) || !(entry.getValue() instanceof Integer)) {
                continue;
            }
            scores.put(key.substring(KEY_PREFIX.length()), (Integer) entry.getValue());
        }
        return scores;
    }

    /**
     * 标记“有用”：命中的模板加分，后续会更靠前。
     *
     * @param context 任意 Context
     * @param ids     本次展示的模板 id
     */
    public static void like(Context context, List<String> ids) {
        applyDelta(context, ids, LIKE_DELTA);
    }

    /**
     * 标记“换一条”：命中的模板减分，后续会尽量避开。
     *
     * @param context 任意 Context
     * @param ids     本次展示的模板 id
     */
    public static void dislike(Context context, List<String> ids) {
        applyDelta(context, ids, DISLIKE_DELTA);
    }

    /**
     * 批量累加反馈分，并限制在 [-50, 50] 内，避免无限累加导致排序失衡。
     */
    private static void applyDelta(Context context, List<String> ids, int delta) {
        if (context == null || ids == null || ids.isEmpty()) {
            return;
        }
        SharedPreferences prefs = prefs(context);
        SharedPreferences.Editor editor = prefs.edit();
        for (String id : ids) {
            if (id == null || id.isEmpty()) {
                continue;
            }
            String key = KEY_PREFIX + id;
            int current = prefs.getInt(key, 0);
            int updated = Math.max(MIN_SCORE, Math.min(MAX_SCORE, current + delta));
            editor.putInt(key, updated);
        }
        editor.apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
