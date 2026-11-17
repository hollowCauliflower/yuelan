package work.huacai.yuelan.model;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 每日建议片段模板。
 * <p>
 * 一条模板对应建议里的一个插槽（开场 / 关注 / 贴士 / 引导 / 依据 / 宜忌），
 * 由 {@code SuggestionEngine} 按阶段、标签、语气筛选后再用上下文变量渲染。
 * 模板内容来自 {@code assets/suggestion_templates.json}，本类只承载数据，不含业务逻辑。
 */
public class SuggestionTemplate {

    /** 插槽：开场 */
    public static final String SLOT_OPENING = "opening";
    /** 插槽：关注（针对当天身体状态的动作建议） */
    public static final String SLOT_FOCUS = "focus";
    /** 插槽：贴士（阶段 × 环境） */
    public static final String SLOT_TIP = "tip";
    /** 插槽：引导（记录 / 打卡） */
    public static final String SLOT_NUDGE = "nudge";
    /** 插槽：依据（“为什么给你这条建议”） */
    public static final String SLOT_INSIGHT = "insight";
    /** 插槽：今日宜忌 */
    public static final String SLOT_YIJI = "yiji";

    /** 适用于所有阶段的标记 */
    public static final String PHASE_ALL = "ALL";

    private final String id;
    private final String slot;
    private final List<String> phases;
    private final Set<String> tags;
    private final String topic;
    private final String tone;
    private final String mutexGroup;
    private final int weight;
    private final String template;
    private final String insight;

    /**
     * @param id         全局唯一标识
     * @param slot       插槽类型，取值见本类 SLOT_* 常量
     * @param phases     适用阶段枚举名列表，或 {@link #PHASE_ALL}
     * @param tags       命中条件，全部满足才会被选中
     * @param topic      主题，用于插槽间互斥
     * @param tone       语气，未启用语气人格时固定 gentle
     * @param mutexGroup 互斥分组，同一屏内同组只出现一次
     * @param weight     同条件候选的排序权重
     * @param template   文案模板，支持 {{placeholder}} 占位符
     * @param insight    可选的依据文案
     */
    public SuggestionTemplate(String id, String slot, List<String> phases, Set<String> tags,
                              String topic, String tone, String mutexGroup, int weight,
                              String template, String insight) {
        this.id = id;
        this.slot = slot;
        this.phases = phases == null ? Collections.emptyList() : Collections.unmodifiableList(phases);
        this.tags = tags == null ? Collections.emptySet() : Collections.unmodifiableSet(tags);
        this.topic = topic;
        this.tone = tone;
        this.mutexGroup = mutexGroup;
        this.weight = weight;
        this.template = template;
        this.insight = insight;
    }

    public String getId() {
        return id;
    }

    public String getSlot() {
        return slot;
    }

    public List<String> getPhases() {
        return phases;
    }

    public Set<String> getTags() {
        return tags;
    }

    public String getTopic() {
        return topic;
    }

    public String getTone() {
        return tone;
    }

    public String getMutexGroup() {
        return mutexGroup;
    }

    public int getWeight() {
        return weight;
    }

    public String getTemplate() {
        return template;
    }

    public String getInsight() {
        return insight;
    }

    /**
     * 该模板是否适用于指定阶段。
     *
     * @param phaseName 阶段枚举名，可为 null
     * @return 阶段匹配或模板声明适用所有阶段时返回 true
     */
    public boolean matchesPhase(String phaseName) {
        if (phases.contains(PHASE_ALL)) {
            return true;
        }
        return phaseName != null && phases.contains(phaseName);
    }
}
