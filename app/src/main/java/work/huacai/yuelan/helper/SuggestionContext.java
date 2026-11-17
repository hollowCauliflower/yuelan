package work.huacai.yuelan.helper;

/**
 * 每日建议的输入信号集合。
 * <p>
 * 由界面层负责从数据库、周期参数、天气与统计中收集数据，再交给
 * {@link SuggestionEngine} 生成文案。本类不依赖 Android，便于单元测试与后续复用。
 */
public final class SuggestionContext {

    /** 季节枚举名，对应模板标签 season_spring 等；未知时可为 null */
    private final String season;
    /** 语气，对应模板 tone 字段；默认 gentle */
    private final String tone;

    /** 日期 id（yyyyMMdd） */
    private final String dateId;
    /** 阶段枚举名（如 MENSTRUAL_PERIOD），未知时为 null */
    private final String phaseName;
    /** 阶段中文名（如“黄体期”），用于 {{phaseName}} */
    private final String phaseDisplayName;
    /** 周期内第几天，从 1 开始；推算失败时为 0 */
    private final int dayInCycle;
    /** 阶段内第几天，从 1 开始；未知时为 0 */
    private final int dayInPhase;
    /** 距离下次预计经期还有几天；未知时为 -1 */
    private final int daysToNextPeriod;

    /** 当天是否有用户实际记录 */
    private final boolean hasRecord;
    /** 痛经等级 0~4 */
    private final int painLevel;
    /** 流量等级 0~3 */
    private final int flowLevel;

    /** 天气描述，可为 null */
    private final String weatherText;
    /** 气温（整数），可为 null */
    private final Integer temperature;

    /** 周期规律性：stable / variable / irregular；样本不足时为 null */
    private final String regularity;
    /** 连续记录天数 */
    private final int recordStreak;
    /** 近期经期是否常伴中度以上痛经 */
    private final boolean painTrend;

    /** 当天最近一条提醒的时间（HH:mm），无提醒时为 null */
    private final String reminderTime;
    /** 当天最近一条提醒的标题，无提醒时为 null */
    private final String reminderTitle;

    /** 查看的是否为今天：里程碑类文案只在当天出现，历史日期不补发纪念 */
    private final boolean today;
    /** 使用天数（首日算第 1 天） */
    private final int usageDays;
    /** 当前是第几个记录周期（从 1 开始） */
    private final int cycleNo;
    /** 最近一个周期相对此前平均长度的变化天数：正数变长、负数变短、0 表示不足样本 */
    private final int cycleDiff;
    /** 心情等级：0 未记录 / 1 好 / 2 一般 / 3 低落 */
    private final int mood;
    /** 今天是否已完成睡眠打卡 */
    private final boolean sleepDone;
    /** 今天是否已完成饮水打卡 */
    private final boolean waterDone;
    /** 节日名称，如“元旦”；无节日时为 null */
    private final String festivalName;
    /** 今天是否为节气当天 */
    private final boolean solarTerm;
    /** 今天是否为公历节日 */
    private final boolean festival;
    /** 相对湿度（%），可为 null */
    private final Integer humidity;
    /** 紫外线指数，可为 null */
    private final Integer uvIndex;
    /** 空气质量指数，可为 null */
    private final Integer airQuality;
    /** 花粉指数，可为 null */
    private final Integer pollenLevel;

    private SuggestionContext(Builder builder) {
        this.season = builder.season;
        this.tone = builder.tone;
        this.dateId = builder.dateId;
        this.phaseName = builder.phaseName;
        this.phaseDisplayName = builder.phaseDisplayName;
        this.dayInCycle = builder.dayInCycle;
        this.dayInPhase = builder.dayInPhase;
        this.daysToNextPeriod = builder.daysToNextPeriod;
        this.hasRecord = builder.hasRecord;
        this.painLevel = builder.painLevel;
        this.flowLevel = builder.flowLevel;
        this.weatherText = builder.weatherText;
        this.temperature = builder.temperature;
        this.regularity = builder.regularity;
        this.recordStreak = builder.recordStreak;
        this.painTrend = builder.painTrend;
        this.reminderTime = builder.reminderTime;
        this.reminderTitle = builder.reminderTitle;
        this.today = builder.today;
        this.usageDays = builder.usageDays;
        this.cycleNo = builder.cycleNo;
        this.cycleDiff = builder.cycleDiff;
        this.mood = builder.mood;
        this.sleepDone = builder.sleepDone;
        this.waterDone = builder.waterDone;
        this.festivalName = builder.festivalName;
        this.solarTerm = builder.solarTerm;
        this.festival = builder.festival;
        this.humidity = builder.humidity;
        this.uvIndex = builder.uvIndex;
        this.airQuality = builder.airQuality;
        this.pollenLevel = builder.pollenLevel;
    }

    public String getSeason() {
        return season;
    }

    public String getTone() {
        return tone;
    }

    public String getDateId() {
        return dateId;
    }

    public String getPhaseName() {
        return phaseName;
    }

    public String getPhaseDisplayName() {
        return phaseDisplayName;
    }

    public int getDayInCycle() {
        return dayInCycle;
    }

    public int getDayInPhase() {
        return dayInPhase;
    }

    public int getDaysToNextPeriod() {
        return daysToNextPeriod;
    }

    public boolean hasRecord() {
        return hasRecord;
    }

    public int getPainLevel() {
        return painLevel;
    }

    public int getFlowLevel() {
        return flowLevel;
    }

    public String getWeatherText() {
        return weatherText;
    }

    public Integer getTemperature() {
        return temperature;
    }

    public String getRegularity() {
        return regularity;
    }

    public int getRecordStreak() {
        return recordStreak;
    }

    public boolean hasPainTrend() {
        return painTrend;
    }

    public String getReminderTime() {
        return reminderTime;
    }

    public String getReminderTitle() {
        return reminderTitle;
    }

    public boolean isToday() {
        return today;
    }

    public int getUsageDays() {
        return usageDays;
    }

    public int getCycleNo() {
        return cycleNo;
    }

    public int getCycleDiff() {
        return cycleDiff;
    }

    public int getMood() {
        return mood;
    }

    public boolean isSleepDone() {
        return sleepDone;
    }

    public boolean isWaterDone() {
        return waterDone;
    }

    public String getFestivalName() {
        return festivalName;
    }

    public boolean isSolarTerm() {
        return solarTerm;
    }

    public boolean isFestival() {
        return festival;
    }

    public Integer getHumidity() {
        return humidity;
    }

    public Integer getUvIndex() {
        return uvIndex;
    }

    public Integer getAirQuality() {
        return airQuality;
    }

    public Integer getPollenLevel() {
        return pollenLevel;
    }

    /**
     * 上下文构建器。字段较多，用构建器避免构造参数顺序出错。
     */
    public static final class Builder {
        private String season;
        private String tone = "gentle";
        private String dateId;
        private String phaseName;
        private String phaseDisplayName;
        private int dayInCycle;
        private int dayInPhase;
        private int daysToNextPeriod = -1;
        private boolean hasRecord;
        private int painLevel;
        private int flowLevel;
        private String weatherText;
        private Integer temperature;
        private String regularity;
        private int recordStreak;
        private boolean painTrend;
        private String reminderTime;
        private String reminderTitle;
        private boolean today;
        private int usageDays;
        private int cycleNo;
        private int cycleDiff;
        private int mood;
        private boolean sleepDone;
        private boolean waterDone;
        private String festivalName;
        private boolean solarTerm;
        private boolean festival;
        private Integer humidity;
        private Integer uvIndex;
        private Integer airQuality;
        private Integer pollenLevel;

        public Builder dateId(String dateId) {
            this.dateId = dateId;
            return this;
        }

        public Builder phase(String phaseName, String phaseDisplayName) {
            this.phaseName = phaseName;
            this.phaseDisplayName = phaseDisplayName;
            return this;
        }

        public Builder dayInCycle(int dayInCycle) {
            this.dayInCycle = dayInCycle;
            return this;
        }

        public Builder dayInPhase(int dayInPhase) {
            this.dayInPhase = dayInPhase;
            return this;
        }

        public Builder daysToNextPeriod(int daysToNextPeriod) {
            this.daysToNextPeriod = daysToNextPeriod;
            return this;
        }

        public Builder record(boolean hasRecord, int painLevel, int flowLevel) {
            this.hasRecord = hasRecord;
            this.painLevel = painLevel;
            this.flowLevel = flowLevel;
            return this;
        }

        public Builder weather(String weatherText, Integer temperature) {
            this.weatherText = weatherText;
            this.temperature = temperature;
            return this;
        }

        public Builder season(String season) {
            this.season = season;
            return this;
        }

        /**
         * 历史与打卡信号。
         *
         * @param regularity  周期规律性：stable / variable / irregular，可为 null
         * @param recordStreak 连续记录天数
         * @param painTrend   近期经期是否常伴中度以上痛经
         */
        public Builder history(String regularity, int recordStreak, boolean painTrend) {
            this.regularity = regularity;
            this.recordStreak = recordStreak;
            this.painTrend = painTrend;
            return this;
        }

        /**
         * 当天最近一条提醒；没有提醒时都传 null。
         *
         * @param time  提醒时间（HH:mm）
         * @param title 提醒标题
         */
        public Builder reminder(String time, String title) {
            this.reminderTime = time;
            this.reminderTitle = title;
            return this;
        }

        public Builder tone(String tone) {
            this.tone = tone;
            return this;
        }

        /**
         * 是否为“今天”，用于控制里程碑文案只在当天出现。
         */
        public Builder today(boolean today) {
            this.today = today;
            return this;
        }

        /**
         * 里程碑信号。
         *
         * @param usageDays 使用天数（首日算第 1 天）
         * @param cycleNo   当前是第几个记录周期
         */
        public Builder milestone(int usageDays, int cycleNo) {
            this.usageDays = usageDays;
            this.cycleNo = cycleNo;
            return this;
        }

        /**
         * 周期趋势：最近一个周期相对此前平均长度的变化天数。
         */
        public Builder cycleDiff(int cycleDiff) {
            this.cycleDiff = cycleDiff;
            return this;
        }

        /**
         * 心情等级：0 未记录 / 1 好 / 2 一般 / 3 低落。
         */
        public Builder mood(int mood) {
            this.mood = mood;
            return this;
        }

        /**
         * 微习惯打卡状态。
         */
        public Builder habits(boolean sleepDone, boolean waterDone) {
            this.sleepDone = sleepDone;
            this.waterDone = waterDone;
            return this;
        }

        /**
         * 节气与节日信号。
         *
         * @param name      节日/节气名称，可为 null
         * @param solarTerm 是否为节气当天
         * @param festival  是否为公历节日
         */
        public Builder festival(String name, boolean solarTerm, boolean festival) {
            this.festivalName = name;
            this.solarTerm = solarTerm;
            this.festival = festival;
            return this;
        }

        /**
         * 扩展天气信号；任一项无数据时传 null，对应模板会自动跳过。
         */
        public Builder air(Integer humidity, Integer uvIndex, Integer airQuality, Integer pollenLevel) {
            this.humidity = humidity;
            this.uvIndex = uvIndex;
            this.airQuality = airQuality;
            this.pollenLevel = pollenLevel;
            return this;
        }

        public SuggestionContext build() {
            return new SuggestionContext(this);
        }
    }
}
