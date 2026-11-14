package work.huacai.yuelan.model;

/**
 * 自定义提醒事项。
 * <p>
 * 对应数据库 reminder 表：{@code targetDay} 存目标日期（yyyyMMdd 整数），
 * {@code alarmTime} 存时间文本（HH:mm），{@code enabled} 表示当天是否启用。
 * <p>
 * 提醒方式有三种：{@code cycleNode = 0 且 repeatType = 0} 表示"仅一次"（按 targetDay 触发）；
 * {@code repeatType > 0} 表示"重复规则"（每天 / 每周 / 每月 / 间隔，targetDay 作为系列开始日期）；
 * {@code cycleNode > 0} 表示"按周期节点"（每个周期的指定阶段节点触发，见 {@link #NODE_PERIOD_START}
 * 等常量），此时 targetDay 不使用。
 * <p>
 * 在周期节点基础上，{@code cycleNodeEnd > 0} 表示"按阶段区间"：从 {@code cycleNode}
 * 到 {@code cycleNodeEnd}（不含）之间的每一天都提醒一次，用于"整个黄体期每天吃药"这类场景。
 */
public class Reminder {

    /** 周期节点：固定日期（默认值，按 targetDay 触发） */
    public static final int NODE_NONE = 0;
    /** 响铃时长：一直响，直到用户点“关闭提醒” */
    public static final int RING_FOREVER = 0;
    /** 周期节点：经期开始（预测月经来潮当天） */
    public static final int NODE_PERIOD_START = 1;
    /** 周期节点：经期结束 */
    public static final int NODE_PERIOD_END = 2;
    /** 周期节点：卵泡期开始 */
    public static final int NODE_FOLLICLE_START = 3;
    /** 周期节点：排卵期开始（易孕期开始） */
    public static final int NODE_OVULATION_START = 4;
    /** 周期节点：黄体期开始 */
    public static final int NODE_LUTEAL_START = 5;
    /** 周期节点：下次月经前 3 天 */
    public static final int NODE_BEFORE_PERIOD = 6;

    /** 重复规则：不重复（仅 targetDay 指定的一天） */
    public static final int REPEAT_NONE = 0;
    /** 重复规则：每天 */
    public static final int REPEAT_DAILY = 1;
    /** 重复规则：每周的指定星期几 */
    public static final int REPEAT_WEEKLY = 2;
    /** 重复规则：每月的指定几天 */
    public static final int REPEAT_MONTHLY = 3;
    /** 重复规则：每隔 N 天 */
    public static final int REPEAT_INTERVAL = 4;
    /** 重复规则：指定多个日期（repeat_config 存逗号分隔的 yyyyMMdd，每个日期各触发一次） */
    public static final int REPEAT_MULTI_DATE = 5;

    /** 主键，新增前为 0 */
    private long id;
    /** 标题（必填） */
    private String title;
    /** 备注内容 */
    private String message;
    /** 提醒类型，默认 custom */
    private String reminderType = "custom";
    /** 目标日期，yyyyMMdd 整数（周期节点模式下不使用） */
    private int targetDay;
    /** 提醒时间，HH:mm */
    private String alarmTime;
    /** 是否启用 */
    private boolean enabled = true;
    /** 是否使用系统闹钟提醒 */
    private boolean alarm;
    /**
     * 强提醒的响铃时长（分钟）。
     * <p>{@link #RING_FOREVER} 表示一直响到用户点“关闭提醒”，>0 表示响满该分钟数后
     * 自动停止铃声与震动（全屏页面保留，仍需手动关闭）。
     */
    private int ringDuration = RING_FOREVER;
    /** 周期节点（0 = 固定日期提醒，>0 = 按周期节点提醒） */
    private int cycleNode = NODE_NONE;
    /**
     * 阶段区间的结束节点（0 = 单日节点提醒；>0 = 从 cycleNode 到该节点之前每天提醒）。
     * <p>例如黄体期区间为 {@code cycleNode = NODE_LUTEAL_START}、
     * {@code cycleNodeEnd = NODE_PERIOD_START}（下一周期起点，不含）。
     */
    private int cycleNodeEnd = NODE_NONE;
    /** 重复规则（0 = 不重复，见 REPEAT_* 常量） */
    private int repeatType = REPEAT_NONE;
    /**
     * 重复规则的参数：星期几（1-7，逗号分隔）、每月几号（逗号分隔）、间隔天数，
     * 或“指定多个日期”模式下逗号分隔的 yyyyMMdd。
     */
    private String repeatConfig;
    /** 重复结束日期 yyyyMMdd，0 表示长期有效 */
    private int repeatEndDay;
    /** 创建时间文本 */
    private String createdTime;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getReminderType() {
        return reminderType;
    }

    public void setReminderType(String reminderType) {
        this.reminderType = reminderType;
    }

    public int getTargetDay() {
        return targetDay;
    }

    public void setTargetDay(int targetDay) {
        this.targetDay = targetDay;
    }

    public String getAlarmTime() {
        return alarmTime;
    }

    public void setAlarmTime(String alarmTime) {
        this.alarmTime = alarmTime;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isAlarm() {
        return alarm;
    }

    public void setAlarm(boolean alarm) {
        this.alarm = alarm;
    }

    public int getRingDuration() {
        return ringDuration;
    }

    public void setRingDuration(int ringDuration) {
        this.ringDuration = ringDuration;
    }

    public int getCycleNode() {
        return cycleNode;
    }

    public void setCycleNode(int cycleNode) {
        this.cycleNode = cycleNode;
    }

    public int getCycleNodeEnd() {
        return cycleNodeEnd;
    }

    public void setCycleNodeEnd(int cycleNodeEnd) {
        this.cycleNodeEnd = cycleNodeEnd;
    }

    /**
     * 是否按阶段区间提醒（周期节点模式的扩展：区间内每天提醒一次）。
     *
     * @return 阶段区间模式返回 true
     */
    public boolean isSpanMode() {
        return cycleNode > NODE_NONE && cycleNodeEnd > NODE_NONE;
    }

    /**
     * 是否按周期节点提醒（否则按固定日期提醒）。
     *
     * @return 周期节点模式返回 true
     */
    public boolean isCycleMode() {
        return cycleNode > NODE_NONE;
    }

    public int getRepeatType() {
        return repeatType;
    }

    public void setRepeatType(int repeatType) {
        this.repeatType = repeatType;
    }

    public String getRepeatConfig() {
        return repeatConfig;
    }

    public void setRepeatConfig(String repeatConfig) {
        this.repeatConfig = repeatConfig;
    }

    public int getRepeatEndDay() {
        return repeatEndDay;
    }

    public void setRepeatEndDay(int repeatEndDay) {
        this.repeatEndDay = repeatEndDay;
    }

    /**
     * 是否为重复规则提醒（每天/每周/每月/间隔）。
     *
     * @return 重复规则模式返回 true
     */
    public boolean isRepeatMode() {
        return repeatType > REPEAT_NONE;
    }

    public String getCreatedTime() {
        return createdTime;
    }

    public void setCreatedTime(String createdTime) {
        this.createdTime = createdTime;
    }

    /**
     * 目标日期转为 yyyyMMdd 字符串，供与记录 id、日历日期比较使用。
     *
     * @return 形如 "20261101" 的日期字符串
     */
    public String getDateId() {
        return String.format(java.util.Locale.getDefault(), "%08d", targetDay);
    }
}
