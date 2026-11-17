package work.huacai.yuelan.helper;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import work.huacai.yuelan.model.MenstrualCycle;
import work.huacai.yuelan.model.MenstrualRecord;
import work.huacai.yuelan.model.SuggestionTemplate;

/**
 * 每日建议引擎。
 * <p>
 * 输入当天信号与模板池，输出按插槽组装的建议内容。核心约定：
 * <ul>
 *   <li>只做纯计算：不读写数据库、不发起网络请求、不依赖 Context；</li>
 *   <li>同一份上下文多次调用结果完全一致（以周期内天序号轮转，而非随机）；</li>
 *   <li>相邻日期轮转位置 +1，保证跨天不重复；</li>
 *   <li>同一屏内按 {@code mutexGroup} 互斥，避免开场和贴士都在说保暖。</li>
 * </ul>
 */
public final class SuggestionEngine {

    /** 占位符：阶段中文名 */
    private static final String PH_PHASE_NAME = "{{phaseName}}";
    /** 占位符：周期第几天 */
    private static final String PH_DAY_IN_CYCLE = "{{dayInCycle}}";
    /** 占位符：距下次经期天数 */
    private static final String PH_DAYS_TO_PERIOD = "{{daysToNextPeriod}}";
    /** 占位符：痛经等级文案 */
    private static final String PH_PAIN_TEXT = "{{painText}}";
    /** 占位符：流量等级文案 */
    private static final String PH_FLOW_TEXT = "{{flowText}}";
    /** 占位符：天气描述 */
    private static final String PH_WEATHER_TEXT = "{{weatherText}}";
    /** 占位符：气温 */
    private static final String PH_TEMP = "{{temp}}";
    /** 占位符：连续记录天数 */
    private static final String PH_STREAK = "{{streak}}";
    /** 占位符：当天提醒时间 */
    private static final String PH_REMINDER_TIME = "{{reminderTime}}";
    /** 占位符：当天提醒标题 */
    private static final String PH_REMINDER_TITLE = "{{reminderTitle}}";
    /** 占位符：使用天数（里程碑） */
    private static final String PH_USAGE_DAYS = "{{usageDays}}";
    /** 占位符：第几个记录周期（里程碑） */
    private static final String PH_CYCLE_NO = "{{cycleNo}}";
    /** 占位符：节气/节日名称 */
    private static final String PH_FESTIVAL_NAME = "{{festivalName}}";
    /** 占位符：周期长度变化天数 */
    private static final String PH_CYCLE_DIFF = "{{cycleDiff}}";

    /** 各插槽的轮转偏移，避免不同插槽总是选中同一位置 */
    private static final int OFFSET_OPENING = 0;
    private static final int OFFSET_FOCUS = 3;
    private static final int OFFSET_TIP = 5;
    private static final int OFFSET_NUDGE = 7;
    private static final int OFFSET_INSIGHT = 9;
    private static final int OFFSET_YIJI = 11;

    /** 默认语气 */
    private static final String DEFAULT_TONE = "gentle";

    private SuggestionEngine() {
        // 工具类禁止实例化
    }

    /**
     * 生成一天的建议内容。
     *
     * @param context   当天信号，不得为 null
     * @param templates 模板池，可为空
     * @return 生成结果；无模板或无法匹配时返回空内容
     */
    public static SuggestionContent generate(SuggestionContext context,
                                             List<SuggestionTemplate> templates) {
        return generate(context, templates, Collections.emptyMap(), Collections.emptySet());
    }

    /**
     * 生成一天的建议内容，并纳入用户反馈与临时跳过。
     *
     * @param context    当天信号，不得为 null
     * @param templates  模板池，可为空
     * @param feedback   模板 id → 反馈分（正数偏爱、负数不喜欢），可为 null
     * @param skippedIds 本次会话内要跳过的模板 id（“换一条”使用），可为 null
     * @return 生成结果；无模板或无法匹配时返回空内容
     */
    public static SuggestionContent generate(SuggestionContext context,
                                             List<SuggestionTemplate> templates,
                                             Map<String, Integer> feedback,
                                             Set<String> skippedIds) {
        if (context == null || templates == null || templates.isEmpty()) {
            return SuggestionContent.empty();
        }

        Map<String, Integer> scores = feedback == null ? Collections.emptyMap() : feedback;
        Set<String> skipped = skippedIds == null ? Collections.emptySet() : skippedIds;
        // 已占用的互斥分组：只对“关注 / 贴士 / 宜忌”生效，依据与引导不参与互斥
        Set<String> usedGroups = new HashSet<>();
        // 本次命中的模板 id，供界面层做“有用 / 换一条”反馈
        List<String> chosenIds = new ArrayList<>();

        String opening = pick(SuggestionTemplate.SLOT_OPENING, OFFSET_OPENING,
                context, templates, scores, skipped, usedGroups, chosenIds, false, toneOf(context));
        String focus = pick(SuggestionTemplate.SLOT_FOCUS, OFFSET_FOCUS,
                context, templates, scores, skipped, usedGroups, chosenIds, true, toneOf(context));
        String tip = pick(SuggestionTemplate.SLOT_TIP, OFFSET_TIP,
                context, templates, scores, skipped, usedGroups, chosenIds, true, toneOf(context));
        String yiji = pick(SuggestionTemplate.SLOT_YIJI, OFFSET_YIJI,
                context, templates, scores, skipped, usedGroups, chosenIds, true, toneOf(context));
        String nudge = pick(SuggestionTemplate.SLOT_NUDGE, OFFSET_NUDGE,
                context, templates, scores, skipped, usedGroups, chosenIds, false, toneOf(context));
        String insight = pick(SuggestionTemplate.SLOT_INSIGHT, OFFSET_INSIGHT,
                context, templates, scores, skipped, usedGroups, chosenIds, false, toneOf(context));

        return new SuggestionContent(opening, focus, tip, nudge, insight, yiji, chosenIds);
    }

    /**
     * 从指定插槽中选出一条最匹配的模板并渲染。
     *
     * @param slot        插槽类型
     * @param offset      轮转偏移
     * @param context     当天信号
     * @param templates   全部模板
     * @param usedGroups  已占用的互斥分组（会被写入）
     * @param useMutex    是否参与互斥
     * @return 渲染后的文案；没有可用模板时返回 null
     */
    @Nullable
    private static String pick(String slot, int offset, SuggestionContext context,
                               List<SuggestionTemplate> templates, Map<String, Integer> feedback,
                               Set<String> skippedIds, Set<String> usedGroups,
                               List<String> chosenIds, boolean useMutex, String tone) {
        // 先按用户选择的语气收集候选；语气下没有可用模板，
        // 或温柔语气能命中更具体的标签（痛经、天气、提醒等）时回退，
        // 保证“语气”只改变措辞，不会把真正相关的建议挤掉
        Pool pool = collect(slot, context, templates, feedback, skippedIds, tone);
        if (!DEFAULT_TONE.equals(tone)) {
            Pool gentle = collect(slot, context, templates, feedback, skippedIds, DEFAULT_TONE);
            if (pool.candidates.isEmpty() || gentle.bestScore > pool.bestScore) {
                pool = gentle;
            }
        }
        if (pool.candidates.isEmpty()) {
            return null;
        }

        // 只保留得分最高的一档：标签越具体越优先，同档内由用户反馈分决定偏爱
        Set<String> topIds = new HashSet<>();
        for (SuggestionTemplate template : pool.candidates) {
            if (scoreOf(template, feedback) == pool.bestScore) {
                topIds.add(template.getId());
            }
        }
        // 按 id 排序，保证同一份数据在不同设备上顺序一致
        Collections.sort(pool.canonical, (t1, t2) -> t1.getId().compareTo(t2.getId()));

        // 关键：轮转索引基于 canonical 的大小，而不是当天可选池的大小。
        // 历史日期没有倒计时、未记录症状时可选池会变窄，若按窄池取模，
        // 相邻两天容易算到同一个下标而重复；按固定基准取模再跳过不可用项可以避免。
        int dayKey = context.getDayInCycle() > 0
                ? context.getDayInCycle()
                : Math.abs(context.getDateId() == null ? 0 : context.getDateId().hashCode());
        int start = Math.floorMod(dayKey + offset, pool.canonical.size());

        for (int i = 0; i < pool.canonical.size(); i++) {
            SuggestionTemplate template = pool.canonical.get((start + i) % pool.canonical.size());
            if (!topIds.contains(template.getId())) {
                continue;
            }
            String group = template.getMutexGroup();
            boolean hasGroup = group != null && !group.isEmpty();
            if (useMutex && hasGroup && usedGroups.contains(group)) {
                continue;
            }
            if (useMutex && hasGroup) {
                usedGroups.add(group);
            }
            chosenIds.add(template.getId());
            return render(template.getTemplate(), context);
        }
        return null;
    }

    /**
     * 收集指定插槽 + 阶段 + 语气下的全部模板与当天可用候选。
     *
     * @param tone 目标语气
     * @return 轮转基准与候选集合
     */
    private static Pool collect(String slot, SuggestionContext context,
                                List<SuggestionTemplate> templates,
                                Map<String, Integer> feedback, Set<String> skippedIds,
                                String tone) {
        Pool pool = new Pool();
        for (SuggestionTemplate template : templates) {
            if (!slot.equals(template.getSlot())) {
                continue;
            }
            if (!template.matchesPhase(context.getPhaseName())) {
                continue;
            }
            if (!matchesTone(template, tone)) {
                continue;
            }
            // “换一条”跳过的模板不再参与本轮候选，也不再占用轮转位置；
            // 但带 has_reminder 的提醒文案属于强时效信息（用药/复查），不允许被换掉
            if (skippedIds.contains(template.getId())
                    && !template.getTags().contains("has_reminder")) {
                continue;
            }
            pool.canonical.add(template);
            if (!matchesTags(template, context)) {
                continue;
            }
            if (!canRender(template.getTemplate(), context)) {
                continue;
            }
            int score = scoreOf(template, feedback);
            if (score > pool.bestScore) {
                pool.bestScore = score;
            }
            pool.candidates.add(template);
        }
        return pool;
    }

    /**
     * 单个插槽、单个语气下的模板集合。
     */
    private static final class Pool {
        /** 轮转基准：该语气下全部模板（与当天是否可渲染无关） */
        private final List<SuggestionTemplate> canonical = new ArrayList<>();
        /** 当天真正可用（标签命中、占位符可渲染）的模板 */
        private final List<SuggestionTemplate> candidates = new ArrayList<>();
        /** 候选中的最高得分 */
        private int bestScore;
    }

    /**
     * 候选得分 = 标签具体度 × 100 + 模板权重 + 反馈分。
     * <p>标签每多命中一个就领先 100 分，保证“症状优先”不被个别反馈压过；
     * 权重用于让“有明确时间点的提醒”这类强时效内容优先于泛化贴士；
     * 反馈分限制在 ±50，避免某条文案因为连续点赞而永远霸屏。
     */
    private static int scoreOf(SuggestionTemplate template, Map<String, Integer> feedback) {
        int raw = feedback.getOrDefault(template.getId(), 0);
        int bounded = Math.max(-50, Math.min(50, raw));
        return template.getTags().size() * 100 + template.getWeight() + bounded;
    }

    /**
     * 语气匹配：模板未指定语气时视为通用；上下文未指定或非法时按 gentle 处理。
     */
    private static boolean matchesTone(SuggestionTemplate template, String tone) {
        String templateTone = template.getTone();
        if (templateTone == null || templateTone.isEmpty()) {
            return true;
        }
        return templateTone.equals(tone);
    }

    /**
     * 取有效语气：非法值一律回退为温柔。
     */
    private static String toneOf(SuggestionContext context) {
        String tone = context.getTone();
        return SuggestionToneStore.isValid(tone) ? tone : DEFAULT_TONE;
    }

    /**
     * 标签匹配：模板上的标签必须全部满足。
     */
    private static boolean matchesTags(SuggestionTemplate template, SuggestionContext context) {
        for (String tag : template.getTags()) {
            if (!hasTag(tag, context)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断单个标签是否命中当天信号。
     */
    private static boolean hasTag(String tag, SuggestionContext context) {
        if (tag == null) {
            return false;
        }
        String phase = context.getPhaseName();
        int dayInPhase = context.getDayInPhase();
        boolean menstrual = MenstrualCycle.MENSTRUAL_PERIOD.name().equals(phase);
        boolean follicular = MenstrualCycle.FOLLICULAR_PHASE.name().equals(phase);
        boolean ovulation = MenstrualCycle.OVULATION_PERIOD.name().equals(phase);
        boolean luteal = MenstrualCycle.LUTE_PHASE.name().equals(phase);

        switch (tag) {
            case "day_1":
                return menstrual && dayInPhase == 1;
            case "day_2_3":
                return menstrual && dayInPhase >= 2 && dayInPhase <= 3;
            case "day_4_plus":
                return menstrual && dayInPhase >= 4;
            case "follicular_early":
                return follicular && dayInPhase >= 1 && dayInPhase <= 4;
            case "follicular_near_ovulation":
                return follicular && dayInPhase >= 5;
            case "ovulation_day":
                return ovulation;
            case "luteal_early":
                return luteal && dayInPhase >= 1 && dayInPhase <= 5;
            case "luteal_mid":
                return luteal && dayInPhase >= 6 && dayInPhase <= 10;
            case "pre_period_3d":
                return context.getDaysToNextPeriod() >= 0 && context.getDaysToNextPeriod() <= 3;
            case "pain_mild":
                return context.hasRecord() && context.getPainLevel() == 1;
            case "pain_moderate":
                return context.hasRecord() && context.getPainLevel() == 2;
            case "pain_severe_plus":
                return context.hasRecord() && context.getPainLevel() >= 3;
            case "flow_heavy":
                return context.hasRecord() && context.getFlowLevel() >= 3;
            case "has_record":
                return context.hasRecord();
            case "no_record":
                return !context.hasRecord();
            case "cold_weather":
                return context.getTemperature() != null && context.getTemperature() < 10;
            case "hot_weather":
                return context.getTemperature() != null && context.getTemperature() > 30;
            case "rainy":
                return containsWeather(context, "雨") || containsWeather(context, "雪");
            case "sunny":
                return containsWeather(context, "晴");
            case "season_spring":
                return "spring".equals(context.getSeason());
            case "season_summer":
                return "summer".equals(context.getSeason());
            case "season_autumn":
                return "autumn".equals(context.getSeason());
            case "season_winter":
                return "winter".equals(context.getSeason());
            case "regularity_stable":
                return "stable".equals(context.getRegularity());
            case "regularity_variable":
                return "variable".equals(context.getRegularity());
            case "regularity_irregular":
                return "irregular".equals(context.getRegularity());
            case "streak_3":
                return context.getRecordStreak() >= 3;
            case "streak_7":
                return context.getRecordStreak() >= 7;
            case "streak_milestone":
                // 只在连续记录的纪念节点出现，避免每天都用打卡文案说教
                return isStreakMilestone(context.getRecordStreak());
            case "pain_trend":
                return context.hasPainTrend();
            case "has_reminder":
                // 提醒联动：当天有启用中的提醒时，优先把时间点提醒出来
                return !isEmpty(context.getReminderTime()) && !isEmpty(context.getReminderTitle());
            case "mood_good":
                return context.getMood() == MenstrualRecord.MOOD_GOOD;
            case "mood_ok":
                return context.getMood() == MenstrualRecord.MOOD_OKAY;
            case "mood_low":
                return context.getMood() == MenstrualRecord.MOOD_LOW;
            case "sleep_done":
                return context.isSleepDone();
            case "water_done":
                return context.isWaterDone();
            case "solar_term":
                return context.isSolarTerm();
            case "festival":
                return context.isFestival();
            case "milestone_usage":
                // 使用天数纪念：只在当天出现，避免历史日期补发“纪念”
                return context.isToday() && isUsageMilestone(context.getUsageDays());
            case "milestone_cycle":
                // 周期纪念：在当前周期的前三天展示，给用户留出看到的窗口
                return context.isToday() && isCycleMilestone(context.getCycleNo())
                        && context.getDayInCycle() >= 1 && context.getDayInCycle() <= 3;
            case "high_humidity":
                return context.getHumidity() != null && context.getHumidity() >= 80;
            case "dry_air":
                return context.getHumidity() != null && context.getHumidity() <= 30;
            case "high_uv":
                return context.getUvIndex() != null && context.getUvIndex() >= 7;
            case "poor_air":
                return context.getAirQuality() != null && context.getAirQuality() >= 100;
            case "high_pollen":
                return context.getPollenLevel() != null && context.getPollenLevel() > 0;
            case "cycle_shorter":
                return context.getCycleDiff() <= -2;
            case "cycle_longer":
                return context.getCycleDiff() >= 2;
            default:
                // 未知标签一律视为不命中：宁可退回通用文案，也不展示未经校验的内容
                return false;
        }
    }

    /**
     * 连续记录纪念节点：只有这些天数才展示打卡文案，避免每天重复说教。
     *
     * @param streak 连续记录天数
     * @return 命中节点返回 true
     */
    private static boolean isStreakMilestone(int streak) {
        return streak == 3 || streak == 7 || streak == 14 || streak == 30
                || streak == 60 || streak == 100 || streak == 180 || streak == 365;
    }

    /**
     * 使用天数纪念节点：使用满这些天数时追加一句。
     */
    private static boolean isUsageMilestone(int usageDays) {
        return usageDays == 7 || usageDays == 30 || usageDays == 100
                || usageDays == 180 || usageDays == 365;
    }

    /**
     * 周期序号纪念节点：第 3 / 6 / 12 / 24 个周期。
     */
    private static boolean isCycleMilestone(int cycleNo) {
        return cycleNo == 3 || cycleNo == 6 || cycleNo == 12 || cycleNo == 24;
    }

    private static boolean containsWeather(SuggestionContext context, String keyword) {
        String text = context.getWeatherText();
        return text != null && text.contains(keyword);
    }

    /**
     * 模板中的所有占位符是否都能用当天信号填充。
     */
    private static boolean canRender(String template, SuggestionContext context) {
        if (template == null) {
            return false;
        }
        if (template.contains(PH_PHASE_NAME) && isEmpty(context.getPhaseDisplayName())) {
            return false;
        }
        if (template.contains(PH_DAY_IN_CYCLE) && context.getDayInCycle() <= 0) {
            return false;
        }
        if (template.contains(PH_DAYS_TO_PERIOD) && context.getDaysToNextPeriod() < 0) {
            return false;
        }
        if (template.contains(PH_PAIN_TEXT) && painText(context) == null) {
            return false;
        }
        if (template.contains(PH_FLOW_TEXT) && flowText(context) == null) {
            return false;
        }
        if (template.contains(PH_WEATHER_TEXT) && isEmpty(context.getWeatherText())) {
            return false;
        }
        if (template.contains(PH_TEMP) && context.getTemperature() == null) {
            return false;
        }
        if (template.contains(PH_STREAK) && context.getRecordStreak() <= 0) {
            return false;
        }
        if (template.contains(PH_REMINDER_TIME) && isEmpty(context.getReminderTime())) {
            return false;
        }
        if (template.contains(PH_REMINDER_TITLE) && isEmpty(context.getReminderTitle())) {
            return false;
        }
        if (template.contains(PH_USAGE_DAYS) && context.getUsageDays() <= 0) {
            return false;
        }
        if (template.contains(PH_CYCLE_NO) && context.getCycleNo() <= 0) {
            return false;
        }
        if (template.contains(PH_FESTIVAL_NAME) && isEmpty(context.getFestivalName())) {
            return false;
        }
        if (template.contains(PH_CYCLE_DIFF) && context.getCycleDiff() == 0) {
            return false;
        }
        return true;
    }

    /**
     * 用当天信号渲染模板；存在未知占位符时返回 null，避免把 “{{xxx}}” 直接展示给用户。
     */
    @Nullable
    private static String render(String template, SuggestionContext context) {
        if (!canRender(template, context)) {
            return null;
        }
        String result = template;
        if (result.contains(PH_PHASE_NAME)) {
            result = result.replace(PH_PHASE_NAME, context.getPhaseDisplayName());
        }
        if (result.contains(PH_DAY_IN_CYCLE)) {
            result = result.replace(PH_DAY_IN_CYCLE, String.valueOf(context.getDayInCycle()));
        }
        if (result.contains(PH_DAYS_TO_PERIOD)) {
            result = result.replace(PH_DAYS_TO_PERIOD,
                    String.valueOf(context.getDaysToNextPeriod()));
        }
        if (result.contains(PH_PAIN_TEXT)) {
            result = result.replace(PH_PAIN_TEXT, painText(context));
        }
        if (result.contains(PH_FLOW_TEXT)) {
            result = result.replace(PH_FLOW_TEXT, flowText(context));
        }
        if (result.contains(PH_WEATHER_TEXT)) {
            result = result.replace(PH_WEATHER_TEXT, context.getWeatherText());
        }
        if (result.contains(PH_TEMP)) {
            result = result.replace(PH_TEMP, String.valueOf(context.getTemperature()));
        }
        if (result.contains(PH_STREAK)) {
            result = result.replace(PH_STREAK, String.valueOf(context.getRecordStreak()));
        }
        if (result.contains(PH_REMINDER_TIME)) {
            result = result.replace(PH_REMINDER_TIME, context.getReminderTime());
        }
        if (result.contains(PH_REMINDER_TITLE)) {
            result = result.replace(PH_REMINDER_TITLE, context.getReminderTitle());
        }
        if (result.contains(PH_USAGE_DAYS)) {
            result = result.replace(PH_USAGE_DAYS, String.valueOf(context.getUsageDays()));
        }
        if (result.contains(PH_CYCLE_NO)) {
            result = result.replace(PH_CYCLE_NO, String.valueOf(context.getCycleNo()));
        }
        if (result.contains(PH_FESTIVAL_NAME)) {
            result = result.replace(PH_FESTIVAL_NAME, context.getFestivalName());
        }
        if (result.contains(PH_CYCLE_DIFF)) {
            // 文案里写的是“短了/长了 N 天”，这里统一取绝对值
            result = result.replace(PH_CYCLE_DIFF, String.valueOf(Math.abs(context.getCycleDiff())));
        }
        // 渲染后仍残留占位符说明模板里写了未知变量：判为不可用，交由上层跳过
        if (result.contains("{{")) {
            return null;
        }
        return result;
    }

    /**
     * 痛经等级文案；当天没有录入或等级为 0 时返回 null（表示该占位符不可用）。
     */
    @Nullable
    private static String painText(SuggestionContext context) {
        if (!context.hasRecord()) {
            return null;
        }
        switch (context.getPainLevel()) {
            case 1:
                return "轻度";
            case 2:
                return "中度";
            case 3:
                return "严重";
            case 4:
                return "剧痛";
            default:
                return null;
        }
    }

    /**
     * 流量等级文案；当天没有录入或等级为 0 时返回 null。
     */
    @Nullable
    private static String flowText(SuggestionContext context) {
        if (!context.hasRecord()) {
            return null;
        }
        switch (context.getFlowLevel()) {
            case 1:
                return "少量";
            case 2:
                return "适中";
            case 3:
                return "过量";
            default:
                return null;
        }
    }

    private static boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * 生成季节标记（spring / summer / autumn / winter），供界面层填充上下文。
     *
     * @param month 月份（1~12）
     * @return 季节枚举名；月份非法时返回 null
     */
    @Nullable
    public static String seasonOf(int month) {
        if (month < 1 || month > 12) {
            return null;
        }
        if (month >= 3 && month <= 5) {
            return "spring";
        }
        if (month >= 6 && month <= 8) {
            return "summer";
        }
        if (month >= 9 && month <= 11) {
            return "autumn";
        }
        return "winter";
    }

    /**
     * 便于日志与测试输出的描述。
     */
    static String describe(SuggestionContent content) {
        return String.format(Locale.US, "SuggestionContent{opening=%s, focus=%s, tip=%s}",
                content.getOpening(), content.getFocus(), content.getTip());
    }
}
