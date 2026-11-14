package work.huacai.yuelan.model;


import android.content.Context;

import androidx.annotation.NonNull;

import java.util.Date;

import work.huacai.yuelan.R;

/**
 * 单日记录模型（对应数据库中的每日记录表）。
 * <p>
 * 一条记录 = 某一天（{@code id} 为 yyyyMMdd）+ 所属阶段 + 建议文案，
 * 以及可选的流量、痛经、备注等表单数据。流量数据用“护垫/血块数量”录入，
 * 再由 {@link #calculateFlowScore()} 换算成评分与等级。
 */
public class MenstrualRecord {

    /** 记录 ID，格式为 yyyyMMdd，同时也是日期主键 */
    private String id;
    /** 所属周期阶段，可能为 null（例如尚未初始化阶段数据） */
    private MenstrualCycle menstrualCycle;
    /** 是否为预测数据（true 表示由周期算法推测、而非用户实际录入） */
    private boolean isPrediction;
    /** 当天展示/保存的建议文案 */
    private String suggestion;
    /** 创建时间（字段名与数据库列一致，故用下划线命名） */
    private Date created_time;
    /** 更新时间 */
    private Date updated_time;

    /** 流量数据：按护垫数量 + 血块数量累计评分 */
    private int flowLevel;
    private int flowScore;
    /** 痛经程度：0~4 */
    private int painLevel;
    /** 护垫总数（各规格之和），由保存逻辑写入 */
    private int padCount;
    private int padLightCount;
    private int padMediumCount;
    private int padHeavyCount;
    private int clotSmallCount;
    private int clotLargeCount;
    /** 用户手写备注（通用备注，两个表单同屏展示时使用） */
    private String notes;
    /** 流量记录专属备注（仅“记录流量”入口使用） */
    private String flowNote;
    /** 痛经记录专属备注（仅“记录痛经”入口使用） */
    private String painNote;

    /** 流量等级：无 / 少量 / 适中 / 过量 */
    public static final int FLOW_LEVEL_NONE = 0;
    public static final int FLOW_LEVEL_LIGHT = 1;
    public static final int FLOW_LEVEL_MEDIUM = 2;
    public static final int FLOW_LEVEL_HEAVY = 3;

    /** 痛经等级：无 / 轻度 / 中度 / 严重 / 剧痛 */
    public static final int PAIN_LEVEL_NONE = 0;
    public static final int PAIN_LEVEL_MILD = 1;
    public static final int PAIN_LEVEL_MODERATE = 2;
    public static final int PAIN_LEVEL_SEVERE = 3;
    public static final int PAIN_LEVEL_EXTREME = 4;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public MenstrualCycle getMenstrualCycle() {
        return menstrualCycle;
    }

    public void setMenstrualCycle(MenstrualCycle menstrualCycle) {
        this.menstrualCycle = menstrualCycle;
    }

    public boolean isPrediction() {
        return isPrediction;
    }

    public void setPrediction(boolean prediction) {
        isPrediction = prediction;
    }

    /**
     * 以 SQLite 常用的 0/1 整数形式写入“是否预测”字段。
     */
    public void setIsPrediction(int isPrediction) {
        this.isPrediction = isPrediction != 0;
    }

    /**
     * 以 SQLite 常用的 0/1 整数形式读取“是否预测”字段。
     */
    public int getIsPrediction() {
        return isPrediction ? 1 : 0;
    }

    public String getSuggestion() {
        return suggestion;
    }

    public void setSuggestion(String suggestion) {
        this.suggestion = suggestion;
    }

    public Date getCreated_time() {
        return created_time;
    }

    public void setCreated_time(Date created_time) {
        this.created_time = created_time;
    }

    public Date getUpdated_time() {
        return updated_time;
    }

    public void setUpdated_time(Date updated_time) {
        this.updated_time = updated_time;
    }

    public int getFlowLevel() {
        return flowLevel;
    }

    public void setFlowLevel(int flowLevel) {
        this.flowLevel = flowLevel;
    }

    public int getFlowScore() {
        return flowScore;
    }

    public void setFlowScore(int flowScore) {
        this.flowScore = flowScore;
    }

    public int getPainLevel() {
        return painLevel;
    }

    public void setPainLevel(int painLevel) {
        this.painLevel = painLevel;
    }

    public int getPadCount() {
        return padCount;
    }

    public void setPadCount(int padCount) {
        this.padCount = padCount;
    }

    public int getPadLightCount() {
        return padLightCount;
    }

    public void setPadLightCount(int padLightCount) {
        this.padLightCount = padLightCount;
    }

    public int getPadMediumCount() {
        return padMediumCount;
    }

    public void setPadMediumCount(int padMediumCount) {
        this.padMediumCount = padMediumCount;
    }

    public int getPadHeavyCount() {
        return padHeavyCount;
    }

    public void setPadHeavyCount(int padHeavyCount) {
        this.padHeavyCount = padHeavyCount;
    }

    public int getClotSmallCount() {
        return clotSmallCount;
    }

    public void setClotSmallCount(int clotSmallCount) {
        this.clotSmallCount = clotSmallCount;
    }

    public int getClotLargeCount() {
        return clotLargeCount;
    }

    public void setClotLargeCount(int clotLargeCount) {
        this.clotLargeCount = clotLargeCount;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public String getFlowNote() {
        return flowNote;
    }

    public void setFlowNote(String flowNote) {
        this.flowNote = flowNote;
    }

    public String getPainNote() {
        return painNote;
    }

    public void setPainNote(String painNote) {
        this.painNote = painNote;
    }

    /**
     * 根据护垫和血块数量计算流量评分与等级。
     * <p>
     * 评分权重：少量护垫 1 分、适中护垫 5 分、过量护垫 20 分、小血块 1 分、大血块 5 分；
     * 等级划分：0 分无数据，1~24 少量，25~100 适中，&gt;100 过量。
     * <p>副作用：同时更新 {@link #flowScore} 与 {@link #flowLevel}。
     * 数量全为 0 时必须记为 {@link #FLOW_LEVEL_NONE}：否则“空表单保存”会被当成少量，
     * 首页据此显示“今日记录”，而统计图表按护垫/血块数量过滤后却是空态。
     */
    public void calculateFlowScore() {
        int score = 0;
        score += padLightCount * 1;
        score += padMediumCount * 5;
        score += padHeavyCount * 20;
        score += clotSmallCount * 1;
        score += clotLargeCount * 5;
        this.flowScore = score;

        if (score <= 0) {
            this.flowLevel = FLOW_LEVEL_NONE;
        } else if (score < 25) {
            this.flowLevel = FLOW_LEVEL_LIGHT;
        } else if (score <= 100) {
            this.flowLevel = FLOW_LEVEL_MEDIUM;
        } else {
            this.flowLevel = FLOW_LEVEL_HEAVY;
        }
    }

    /**
     * 流量等级的中文名称（硬编码，不依赖资源）。
     */
    public String getFlowLevelText() {
        switch (flowLevel) {
            case FLOW_LEVEL_LIGHT:
                return "少量";
            case FLOW_LEVEL_MEDIUM:
                return "适中";
            case FLOW_LEVEL_HEAVY:
                return "过量";
            default:
                return "无";
        }
    }

    /**
     * 流量等级的本地化名称。
     *
     * @param context 上下文
     */
    public String getFlowLevelText(Context context) {
        switch (flowLevel) {
            case FLOW_LEVEL_LIGHT:
                return context.getString(R.string.statistics_flow_light);
            case FLOW_LEVEL_MEDIUM:
                return context.getString(R.string.statistics_flow_medium);
            case FLOW_LEVEL_HEAVY:
                return context.getString(R.string.statistics_flow_heavy);
            default:
                return context.getString(R.string.record_detail_flow_no_data);
        }
    }

    /**
     * 痛经等级的中文名称（硬编码，不依赖资源）。
     */
    public String getPainLevelText() {
        switch (painLevel) {
            case PAIN_LEVEL_MILD:
                return "轻度";
            case PAIN_LEVEL_MODERATE:
                return "中度";
            case PAIN_LEVEL_SEVERE:
                return "严重";
            case PAIN_LEVEL_EXTREME:
                return "剧痛";
            default:
                return "无";
        }
    }

    /**
     * 痛经等级的本地化名称。
     *
     * @param context 上下文
     */
    public String getPainLevelText(Context context) {
        switch (painLevel) {
            case PAIN_LEVEL_MILD:
                return context.getString(R.string.record_detail_pain_1);
            case PAIN_LEVEL_MODERATE:
                return context.getString(R.string.record_detail_pain_2);
            case PAIN_LEVEL_SEVERE:
                return context.getString(R.string.record_detail_pain_3);
            case PAIN_LEVEL_EXTREME:
                return context.getString(R.string.record_detail_pain_4);
            default:
                return context.getString(R.string.record_detail_pain_no_data);
        }
    }

    /**
     * 是否录入了流量数据（任一护垫或血块数量大于 0）。
     */
    public boolean hasFlowData() {
        return padLightCount > 0 || padMediumCount > 0 || padHeavyCount > 0
                || clotSmallCount > 0 || clotLargeCount > 0;
    }

    /**
     * 是否录入了痛经数据（等级大于 0）。
     */
    public boolean hasPainData() {
        return painLevel > PAIN_LEVEL_NONE;
    }

    @NonNull
    @Override
    public String toString() {
        return "MenstrualRecord{" +
                "id='" + id + '\'' +
                ", menstrualCycle=" + menstrualCycle +
                ", isPrediction=" + isPrediction +
                ", suggestion='" + suggestion + '\'' +
                ", flowLevel=" + flowLevel +
                ", flowScore=" + flowScore +
                ", painLevel=" + painLevel +
                ", notes='" + notes + '\'' +
                ", created_time=" + created_time +
                ", updated_time=" + updated_time +
                '}';
    }
}
