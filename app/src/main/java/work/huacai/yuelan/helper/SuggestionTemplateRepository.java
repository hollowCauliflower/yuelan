package work.huacai.yuelan.helper;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import work.huacai.yuelan.model.SuggestionTemplate;

/**
 * 建议模板资源仓库。
 * <p>
 * 负责从 {@code assets/suggestion_templates.json} 读取并缓存模板。
 * 读取或解析失败时返回空列表，由界面层降级到内置兜底文案，保证不会空屏或崩溃。
 */
public final class SuggestionTemplateRepository {

    private static final String TAG = "SuggestionTemplateRepo";
    private static final String ASSET_NAME = "suggestion_templates.json";

    /** 进程内缓存；模板随 APK 发版，运行期不会变化 */
    private static volatile List<SuggestionTemplate> cached;

    private SuggestionTemplateRepository() {
    }

    /**
     * 获取全部模板（首次调用时读取 assets，之后走缓存）。
     *
     * @param context 任意 Context，内部会转成 ApplicationContext
     * @return 模板列表；读取失败时返回空列表
     */
    public static List<SuggestionTemplate> getTemplates(Context context) {
        List<SuggestionTemplate> local = cached;
        if (local != null) {
            return local;
        }
        synchronized (SuggestionTemplateRepository.class) {
            if (cached != null) {
                return cached;
            }
            List<SuggestionTemplate> parsed = readAsset(context);
            if (!parsed.isEmpty()) {
                cached = parsed;
            }
            return parsed;
        }
    }

    /**
     * 清空缓存，仅用于数据重置或测试。
     */
    public static void clearCache() {
        cached = null;
    }

    private static List<SuggestionTemplate> readAsset(Context context) {
        if (context == null) {
            return Collections.emptyList();
        }
        Context appContext = context.getApplicationContext();
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                appContext.getAssets().open(ASSET_NAME), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        } catch (IOException e) {
            Log.e(TAG, "readAsset: 读取模板资源失败", e);
            return Collections.emptyList();
        }
        List<SuggestionTemplate> templates = SuggestionTemplateParser.parse(builder.toString());
        if (templates.isEmpty()) {
            Log.w(TAG, "readAsset: 模板为空或解析失败");
        }
        return templates;
    }
}
