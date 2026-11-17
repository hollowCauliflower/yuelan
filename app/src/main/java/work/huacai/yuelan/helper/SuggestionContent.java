package work.huacai.yuelan.helper;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;

/**
 * 每日建议的生成结果。
 * <p>
 * 每个字段对应一个插槽，没有合适内容时为 null，由界面层决定隐藏还是降级展示。
 * 同时记录本次命中的模板 id，供“有用 / 换一条”反馈持久化使用。
 */
public final class SuggestionContent {

    private final String opening;
    private final String focus;
    private final String tip;
    private final String nudge;
    private final String insight;
    private final String yiji;
    /** 本次实际命中的模板 id 集合 */
    private final List<String> templateIds;

    SuggestionContent(@Nullable String opening, @Nullable String focus, @Nullable String tip,
                      @Nullable String nudge, @Nullable String insight, @Nullable String yiji,
                      List<String> templateIds) {
        this.opening = opening;
        this.focus = focus;
        this.tip = tip;
        this.nudge = nudge;
        this.insight = insight;
        this.yiji = yiji;
        this.templateIds = templateIds == null
                ? Collections.emptyList() : Collections.unmodifiableList(templateIds);
    }

    /** 空结果：所有插槽都没有内容 */
    static SuggestionContent empty() {
        return new SuggestionContent(null, null, null, null, null, null, Collections.emptyList());
    }

    /**
     * 本次命中的模板 id，用于反馈打分与“换一条”跳过。
     *
     * @return 不可变列表；无内容时为空列表
     */
    public List<String> getTemplateIds() {
        return templateIds;
    }

    @Nullable
    public String getOpening() {
        return opening;
    }

    @Nullable
    public String getFocus() {
        return focus;
    }

    @Nullable
    public String getTip() {
        return tip;
    }

    @Nullable
    public String getNudge() {
        return nudge;
    }

    @Nullable
    public String getInsight() {
        return insight;
    }

    @Nullable
    public String getYiji() {
        return yiji;
    }

    /**
     * 是否所有插槽都为空。
     *
     * @return 无任何内容时返回 true
     */
    public boolean isEmpty() {
        return isEmpty(opening) && isEmpty(focus) && isEmpty(tip)
                && isEmpty(nudge) && isEmpty(insight) && isEmpty(yiji);
    }

    /**
     * 列表预览用的短句：优先开场，其次关注、贴士。
     * <p>记录列表每行空间有限，只取第一句，避免被系统硬截断得莫名其妙。
     *
     * @return 可展示的短句；全部为空时返回 null
     */
    @Nullable
    public String getHeadline() {
        if (!isEmpty(opening)) {
            return opening;
        }
        if (!isEmpty(focus)) {
            return focus;
        }
        if (!isEmpty(tip)) {
            return tip;
        }
        return null;
    }

    /**
     * 弹窗正文：按“开场 / 关注 / 贴士”三行拼接。
     * <p>遵循设计文档 12.4 的“首屏不超过三行”原则，依据与宜忌留给后续版本折叠展示。
     *
     * @return 可直接塞进 TextView 的多行文本；全部为空时返回 null
     */
    @Nullable
    public String toDisplayText() {
        StringBuilder builder = new StringBuilder();
        appendLine(builder, opening);
        appendLine(builder, focus);
        appendLine(builder, tip);
        return builder.length() == 0 ? null : builder.toString();
    }

    private static void appendLine(StringBuilder builder, @Nullable String line) {
        if (isEmpty(line)) {
            return;
        }
        if (builder.length() > 0) {
            builder.append('\n');
        }
        builder.append(line.trim());
    }

    private static boolean isEmpty(@Nullable String value) {
        return value == null || value.trim().isEmpty();
    }
}
