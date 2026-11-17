package work.huacai.yuelan.helper;

import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import work.huacai.yuelan.model.SuggestionTemplate;

/**
 * 建议模板 JSON 解析器。
 * <p>
 * 只做“字符串 → 模板对象”的转换，不依赖 Android 资源系统，
 * 因此界面层与单元测试可以共用同一份解析逻辑。
 */
public final class SuggestionTemplateParser {

    /** 模板缺省权重 */
    private static final int DEFAULT_WEIGHT = 10;

    private static final Gson GSON = new Gson();

    private SuggestionTemplateParser() {
    }

    /**
     * 解析模板文件内容。
     * <p>
     * 单条模板缺少 id / slot / template 时跳过该条并记录日志，不因一条脏数据导致整份内容不可用。
     *
     * @param json 文件内容，可为 null
     * @return 解析出的模板列表；解析失败或内容为空时返回空列表
     */
    public static List<SuggestionTemplate> parse(@Nullable String json) {
        List<SuggestionTemplate> result = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) {
            return result;
        }

        RawFile raw;
        try {
            raw = GSON.fromJson(json, RawFile.class);
        } catch (JsonSyntaxException e) {
            return result;
        }
        if (raw == null || raw.templates == null) {
            return result;
        }

        for (RawTemplate item : raw.templates) {
            if (item == null || isEmpty(item.id) || isEmpty(item.slot) || isEmpty(item.template)) {
                // 单条脏数据直接跳过：严格校验由 tools/suggestion_template_check.js 负责
                continue;
            }
            List<String> phases = item.phase != null ? item.phase : item.phases;
            result.add(new SuggestionTemplate(
                    item.id,
                    item.slot,
                    phases == null ? Collections.emptyList() : phases,
                    item.tags == null ? Collections.emptySet() : new HashSet<>(item.tags),
                    item.topic,
                    item.tone,
                    item.mutexGroup,
                    item.weight == null ? DEFAULT_WEIGHT : item.weight,
                    item.template,
                    item.insight));
        }
        return result;
    }

    private static boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** 模板文件根结构 */
    private static final class RawFile {
        int schemaVersion;
        int contentVersion;
        List<RawTemplate> templates;
    }

    /** 单条模板的原始 JSON 结构 */
    private static final class RawTemplate {
        String id;
        String slot;
        /** 兼容单数与复数两种写法 */
        List<String> phase;
        List<String> phases;
        List<String> tags;
        String topic;
        String tone;
        String mutexGroup;
        Integer weight;
        String template;
        String insight;
    }
}
