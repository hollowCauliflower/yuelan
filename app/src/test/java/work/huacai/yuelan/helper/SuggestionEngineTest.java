package work.huacai.yuelan.helper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import work.huacai.yuelan.model.MenstrualCycle;
import work.huacai.yuelan.model.MenstrualRecord;
import work.huacai.yuelan.model.SuggestionTemplate;

/**
 * {@link SuggestionEngine} 的机制验证：
 * 同日稳定、跨日轮转、症状优先、占位符不残留、空模板降级。
 * <p>使用真实的 assets 模板文件，避免测试数据与线上内容脱节。
 */
public class SuggestionEngineTest {

    private static List<SuggestionTemplate> templates;

    @BeforeClass
    public static void loadTemplates() throws IOException {
        File file = new File("src/main/assets/suggestion_templates.json");
        assertTrue("模板文件不存在: " + file.getAbsolutePath(), file.exists());
        String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        templates = SuggestionTemplateParser.parse(json);
        assertFalse("模板解析结果为空", templates.isEmpty());
    }

    @Test
    public void parse_shouldLoadAllTemplates() {
        // 不锁死具体条数：P5 批量扩产时模板会持续增加，这里只校验种子规模达标
        assertTrue("模板数量不足，当前 " + templates.size(), templates.size() >= 50);
    }

    @Test
    public void generate_shouldBeStableWithinSameDay() {
        SuggestionContext context = lutealContext(20, 4);
        SuggestionContent first = SuggestionEngine.generate(context, templates);
        SuggestionContent second = SuggestionEngine.generate(context, templates);

        assertEquals(first.getOpening(), second.getOpening());
        assertEquals(first.getFocus(), second.getFocus());
        assertEquals(first.getTip(), second.getTip());
        assertEquals(first.getNudge(), second.getNudge());
    }

    @Test
    public void generate_shouldRotateAcrossConsecutiveDays() {
        Set<String> openings = new HashSet<>();
        for (int day = 1; day <= 7; day++) {
            SuggestionContext context = lutealContext(16 + day, day);
            String opening = SuggestionEngine.generate(context, templates).getOpening();
            assertNotNull("第 " + day + " 天没有生成开场句", opening);
            openings.add(opening);
        }
        assertEquals("连续 7 天的开场句不应重复", 7, openings.size());
    }

    @Test
    public void generate_shouldNotRepeatOpeningWithinTwelveDays() {
        // 开场模板种子共 12 条，连续 12 天应各取一条、互不重复
        Set<String> openings = new HashSet<>();
        for (int day = 1; day <= 12; day++) {
            SuggestionContext context = new SuggestionContext.Builder()
                    .dateId("202611" + String.format("%02d", day))
                    .phase(MenstrualCycle.LUTE_PHASE.name(), "黄体期")
                    .dayInCycle(day)
                    .dayInPhase(day)
                    .daysToNextPeriod(29 - day)
                    .record(false, 0, 0)
                    .season("autumn")
                    .build();
            String opening = SuggestionEngine.generate(context, templates).getOpening();
            assertNotNull("第 " + day + " 天没有生成开场句", opening);
            openings.add(opening);
        }
        assertEquals("连续 12 天的开场句不应重复", 12, openings.size());
    }

    @Test
    public void generate_shouldPrioritizeSymptomAdvice() {
        SuggestionContext context = new SuggestionContext.Builder()
                .dateId("20261117")
                .phase(MenstrualCycle.MENSTRUAL_PERIOD.name(), "月经期")
                .dayInCycle(2)
                .dayInPhase(2)
                .daysToNextPeriod(26)
                .record(true, 2, 1)
                .build();

        SuggestionContent content = SuggestionEngine.generate(context, templates);
        assertNotNull(content.getFocus());
        assertTrue("中度痛经应优先生成热敷建议，实际为: " + content.getFocus(),
                content.getFocus().contains("热敷"));
        assertTrue("痛经程度应渲染为“中度”", content.getFocus().contains("中度"));
    }

    @Test
    public void generate_shouldNotLeakPlaceholderWhenSignalsMissing() {
        SuggestionContext context = new SuggestionContext.Builder()
                .dateId("20261118")
                .phase(MenstrualCycle.FOLLICULAR_PHASE.name(), "卵泡期")
                .dayInCycle(8)
                .dayInPhase(3)
                .daysToNextPeriod(20)
                .record(false, 0, 0)
                .build();

        SuggestionContent content = SuggestionEngine.generate(context, templates);
        assertNoPlaceholder(content.getOpening());
        assertNoPlaceholder(content.getFocus());
        assertNoPlaceholder(content.getTip());
        assertNoPlaceholder(content.getNudge());
        assertNoPlaceholder(content.getInsight());
        assertNoPlaceholder(content.getYiji());
    }

    @Test
    public void generate_shouldRenderWeatherPlaceholder() {
        SuggestionContext context = new SuggestionContext.Builder()
                .dateId("20261119")
                .phase(MenstrualCycle.LUTE_PHASE.name(), "黄体期")
                .dayInCycle(24)
                .dayInPhase(8)
                .daysToNextPeriod(4)
                .record(false, 0, 0)
                .weather("小雨", 6)
                .season("autumn")
                .build();

        SuggestionContent content = SuggestionEngine.generate(context, templates);
        String tip = content.getTip();
        assertNotNull(tip);
        assertFalse("天气提示不应残留占位符", tip.contains("{{"));
    }

    @Test
    public void generate_shouldReturnEmptyWhenNoTemplates() {
        SuggestionContent content = SuggestionEngine.generate(lutealContext(20, 4),
                new ArrayList<>());
        assertTrue(content.isEmpty());
        assertNull(content.getOpening());
    }

    @Test
    public void recordStreak_shouldCountConsecutiveDaysEndingToday() {
        List<MenstrualRecord> records = new ArrayList<>();
        Calendar calendar = Calendar.getInstance();
        for (int i = 0; i < 3; i++) {
            records.add(actualRecord(CycleCalculator.formatDateId(calendar.getTime())));
            calendar.add(Calendar.DAY_OF_MONTH, -1);
        }
        assertEquals(3, CycleStatsHelper.recordStreak(records, new java.util.Date()));
    }

    @Test
    public void recordStreak_shouldFallBackToYesterday() {
        List<MenstrualRecord> records = new ArrayList<>();
        Calendar calendar = Calendar.getInstance();
        // 今天未记录、昨天与前天有记录：仍应算作连续 2 天
        calendar.add(Calendar.DAY_OF_MONTH, -1);
        records.add(actualRecord(CycleCalculator.formatDateId(calendar.getTime())));
        calendar.add(Calendar.DAY_OF_MONTH, -1);
        records.add(actualRecord(CycleCalculator.formatDateId(calendar.getTime())));

        assertEquals(2, CycleStatsHelper.recordStreak(records, new java.util.Date()));
    }

    @Test
    public void generate_shouldUseStreakMilestoneTip() {
        SuggestionContext context = new SuggestionContext.Builder()
                .dateId("20261119")
                .phase(MenstrualCycle.LUTE_PHASE.name(), "黄体期")
                .dayInCycle(24)
                .dayInPhase(8)
                .daysToNextPeriod(4)
                .record(false, 0, 0)
                .season("autumn")
                .history(null, 7, false)
                .build();

        SuggestionContent content = SuggestionEngine.generate(context, templates);
        assertNotNull(content.getTip());
        assertTrue("纪念节点应展示连续记录天数，实际为: " + content.getTip(),
                content.getTip().contains("7"));
    }

    @Test
    public void generate_shouldSkipRequestedTemplates() {
        SuggestionContext context = lutealContext(24, 8);
        SuggestionContent first = SuggestionEngine.generate(context, templates);
        assertNotNull(first.getOpening());

        Set<String> skipped = new HashSet<>(first.getTemplateIds());
        SuggestionContent second = SuggestionEngine.generate(context, templates,
                Collections.emptyMap(), skipped);

        assertNotNull(second.getOpening());
        assertFalse("换一条后开场句不应与上一版相同",
                first.getOpening().equals(second.getOpening()));
    }

    @Test
    public void generate_shouldPreferLikedTemplate() {
        SuggestionContext context = lutealContext(24, 8);
        Map<String, Integer> feedback = new HashMap<>();
        // 给 opening_01 很高的正反馈，同标签档内它应被优先选中
        feedback.put("opening_01", 50);

        SuggestionContent content = SuggestionEngine.generate(context, templates,
                feedback, Collections.emptySet());

        assertTrue("应优先命中被点赞的开场模板，实际: " + content.getTemplateIds(),
                content.getTemplateIds().contains("opening_01"));
    }

    @Test
    public void generate_shouldLinkReminderTimeAndTitle() {
        SuggestionContext context = new SuggestionContext.Builder()
                .dateId("20261119")
                .phase(MenstrualCycle.LUTE_PHASE.name(), "黄体期")
                .dayInCycle(24)
                .dayInPhase(8)
                .daysToNextPeriod(4)
                .record(false, 0, 0)
                .season("autumn")
                .reminder("19:00", "黄体酮")
                .build();

        SuggestionContent content = SuggestionEngine.generate(context, templates);
        assertNotNull(content.getTip());
        assertTrue("提醒时间应渲染进贴士，实际: " + content.getTip(),
                content.getTip().contains("19:00"));
        assertTrue("提醒标题应渲染进贴士，实际: " + content.getTip(),
                content.getTip().contains("黄体酮"));
    }

    @Test
    public void generate_shouldKeepReminderEvenWhenSwapped() {
        SuggestionContext context = new SuggestionContext.Builder()
                .dateId("20261119")
                .phase(MenstrualCycle.LUTE_PHASE.name(), "黄体期")
                .dayInCycle(24)
                .dayInPhase(8)
                .daysToNextPeriod(4)
                .record(false, 0, 0)
                .season("autumn")
                .reminder("21:00", "吃黄体酮")
                .build();

        SuggestionContent first = SuggestionEngine.generate(context, templates);
        assertNotNull(first.getTip());
        assertTrue(first.getTip().contains("21:00"));

        // 模拟“换一条”：跳过当前所有模板后，提醒仍应保留
        Set<String> skipped = new HashSet<>(first.getTemplateIds());
        SuggestionContent swapped = SuggestionEngine.generate(context, templates,
                Collections.emptyMap(), skipped);
        assertNotNull(swapped.getTip());
        assertTrue("换一条后提醒不应被换掉，实际: " + swapped.getTip(),
                swapped.getTip().contains("21:00"));
    }

    @Test
    public void generate_shouldFallBackToGentleForSymptomAdvice() {
        // 简洁语气下只有通用文案；当天中度痛经时，应回退温柔语气以保留症状相关建议
        SuggestionContext context = new SuggestionContext.Builder()
                .dateId("20261117")
                .phase(MenstrualCycle.MENSTRUAL_PERIOD.name(), "月经期")
                .dayInCycle(2)
                .dayInPhase(2)
                .daysToNextPeriod(26)
                .record(true, 2, 1)
                .tone(SuggestionToneStore.TONE_BRIEF)
                .build();

        SuggestionContent content = SuggestionEngine.generate(context, templates);
        assertNotNull(content.getFocus());
        assertTrue("语气回退后仍应保留症状建议，实际为: " + content.getFocus(),
                content.getFocus().contains("热敷"));
    }

    @Test
    public void generate_shouldUseSelectedToneWhenSignalsEqual() {
        // 没有症状/天气等强信号时，应优先使用所选语气（元气开场）
        SuggestionContext context = lutealContextWithTone(24, 8, SuggestionToneStore.TONE_CHEERFUL);
        SuggestionContent content = SuggestionEngine.generate(context, templates);

        assertNotNull(content.getOpening());
        assertTrue("应命中元气语气开场，实际: " + content.getTemplateIds(),
                content.getTemplateIds().contains("opening_33")
                        || content.getTemplateIds().contains("opening_34"));
    }

    @Test
    public void solarTermAndFestival_shouldMatchKnownDates() {
        assertEquals("立春", SolarTermHelper.solarTermOf(CycleCalculator.parseDateId("20260204")));
        assertEquals("春分", SolarTermHelper.solarTermOf(CycleCalculator.parseDateId("20260320")));
        assertEquals("元旦", SolarTermHelper.festivalOf(CycleCalculator.parseDateId("20260101")));
        assertEquals("母亲节", SolarTermHelper.festivalOf(CycleCalculator.parseDateId("20260510")));
    }

    private static MenstrualRecord actualRecord(String dateId) {
        MenstrualRecord record = new MenstrualRecord();
        record.setId(dateId);
        record.setMenstrualCycle(MenstrualCycle.FOLLICULAR_PHASE);
        // 0 = 用户实际记录
        record.setIsPrediction(0);
        return record;
    }

    /** 构造黄体期上下文：dayInCycle 与 dayInPhase 由调用方给出，距经期天数按 28 天周期反推 */
    private static SuggestionContext lutealContext(int dayInCycle, int dayInPhase) {
        return lutealContextWithTone(dayInCycle, dayInPhase, SuggestionToneStore.TONE_GENTLE);
    }

    /** 构造带指定语气的黄体期上下文 */
    private static SuggestionContext lutealContextWithTone(int dayInCycle, int dayInPhase,
                                                           String tone) {
        return new SuggestionContext.Builder()
                .dateId("202611" + String.format("%02d", dayInCycle))
                .phase(MenstrualCycle.LUTE_PHASE.name(), "黄体期")
                .dayInCycle(dayInCycle)
                .dayInPhase(dayInPhase)
                .daysToNextPeriod(Math.max(0, 28 - dayInCycle))
                .record(false, 0, 0)
                .season("autumn")
                .tone(tone)
                .build();
    }

    private static void assertNoPlaceholder(String value) {
        if (value != null) {
            assertFalse("文案残留未渲染的占位符: " + value, value.contains("{{"));
        }
    }
}
