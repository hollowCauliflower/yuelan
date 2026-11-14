package work.huacai.yuelan.helper;

import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import work.huacai.yuelan.dao.MenstrualCycleRecordDao;
import work.huacai.yuelan.dao.MenstrualRecordDao;
import work.huacai.yuelan.dao.PhaseAnchorDao;
import work.huacai.yuelan.model.MenstrualCycle;
import work.huacai.yuelan.model.MenstrualCycleRecord;
import work.huacai.yuelan.model.MenstrualRecord;
import work.huacai.yuelan.model.PhaseAnchor;

/**
 * 阶段连贯性检查：在用户手动修改某天的非经期阶段前，
 * 判断该修改是否会与前后日期的有效阶段（实际记录优先，否则按分段+锚点推算的预测阶段）
 * 产生不连贯，并给出提示所需的上下文。
 * <p>
 * 目前识别三类不连贯：
 * <ul>
 *   <li><b>孤岛</b>：前一天与后一天是同一阶段，且所选阶段不同
 *       （如卵泡期中间夹一个排卵期）；</li>
 *   <li><b>倒置</b>：所选阶段在同周期顺序上早于前一天阶段
 *       （如前一天是排卵期却选择卵泡期），经期（0）视为新一轮开始、不算倒置；</li>
 *   <li><b>黄体期→经期</b>：上一轮经期还没结束（黄体期仍在进行）就直接把今天
 *       补签成月经期，会产生"上一轮还没结束就开始新一轮"的错乱。经期提前应先把
 *       黄体期的最后一天补签成月经期。</li>
 * </ul>
 */
public class PhaseContinuityHelper {

    /** 不连贯类型 */
    public enum Discontinuity {
        /** 连贯，可直接保存 */
        NONE,
        /** 孤岛：前后同一天阶段，所选不同 */
        ISLAND,
        /** 倒置：所选阶段早于前一天阶段（同周期顺序倒退） */
        BACKWARD,
        /** 前一天是黄体期时直接补签月经期：上一轮经期尚未走完，应先把前一天改成经期 */
        LUTEAL_TO_PERIOD
    }

    /** 检查结果：类型 + 前后日期的有效阶段（用于提示文案） */
    public static class CheckResult {
        public final Discontinuity type;
        public final MenstrualCycle prevPhase;
        public final MenstrualCycle nextPhase;

        CheckResult(Discontinuity type, MenstrualCycle prevPhase, MenstrualCycle nextPhase) {
            this.type = type;
            this.prevPhase = prevPhase;
            this.nextPhase = nextPhase;
        }
    }

    /**
     * 检查把 dateId 设为 newPhase 是否与前后日期的有效阶段不连贯。
     *
     * @param dateId         目标日期 id（yyyyMMdd）
     * @param newPhase       用户选择的阶段
     * @param recordDao      记录 DAO（读取实际记录）
     * @param cycleRecordDao 周期分段 DAO（推算预测阶段）
     * @param anchorDao      阶段锚点 DAO（推算预测阶段）
     * @return 检查结果；prevPhase / nextPhase 在无法推算时为 null
     */
    public static CheckResult check(String dateId, MenstrualCycle newPhase,
                                    MenstrualRecordDao recordDao,
                                    MenstrualCycleRecordDao cycleRecordDao,
                                    PhaseAnchorDao anchorDao) {
        Date date = CycleCalculator.parseDateId(dateId);
        if (date == null || newPhase == null) {
            return new CheckResult(Discontinuity.NONE, null, null);
        }

        List<MenstrualCycleRecord> segments = cycleRecordDao.selectAll();
        Map<String, MenstrualCycle> actualByDate = loadActualPhases(recordDao);
        PhaseAnchor anchor = anchorDao.selectLatest();

        Date prevDate = addDays(date, -1);
        Date nextDate = addDays(date, 1);

        MenstrualCycle prevPhase = effectivePhase(prevDate, segments, actualByDate, anchor);
        MenstrualCycle nextPhase = effectivePhase(nextDate, segments, actualByDate, anchor);

        // 经期修改走分段重锚（syncActualStartAndRegenerate），不存在孤岛问题；
        // 但"前一天是黄体期"时直接补签经期会让上一轮周期没有结束就进入新一轮，
        // 属于顺序错乱，拦截并提示先把前一天补签为经期
        if (newPhase == MenstrualCycle.MENSTRUAL_PERIOD) {
            if (prevPhase == MenstrualCycle.LUTE_PHASE) {
                return new CheckResult(Discontinuity.LUTEAL_TO_PERIOD, prevPhase, null);
            }
            return new CheckResult(Discontinuity.NONE, null, null);
        }

        // 孤岛：前后同一天阶段且与所选不同
        if (prevPhase != null && prevPhase == nextPhase && newPhase != prevPhase) {
            return new CheckResult(Discontinuity.ISLAND, prevPhase, nextPhase);
        }

        // 倒置：所选阶段在同周期顺序上早于前一天阶段（经期视为新一轮开始，不算倒置）
        if (prevPhase != null && newPhase.getIndex() < prevPhase.getIndex()) {
            return new CheckResult(Discontinuity.BACKWARD, prevPhase, nextPhase);
        }

        return new CheckResult(Discontinuity.NONE, prevPhase, nextPhase);
    }

    /**
     * 批量解析时的只读快照：把分段、实际记录、锚点一次性读入内存，
     * 之后逐日推算阶段不再访问数据库，避免范围编辑器等场景出现 N 次重复查询。
     */
    public static class PhaseSnapshot {
        private final List<MenstrualCycleRecord> segments;
        private final Map<String, MenstrualCycle> actualByDate;
        private final PhaseAnchor anchor;

        PhaseSnapshot(List<MenstrualCycleRecord> segments,
                      Map<String, MenstrualCycle> actualByDate,
                      PhaseAnchor anchor) {
            this.segments = segments;
            this.actualByDate = actualByDate;
            this.anchor = anchor;
        }

        /** 查询某天的有效阶段（实际记录优先，否则按分段+锚点推算） */
        public MenstrualCycle phaseOf(Date date) {
            return effectivePhase(CycleCalculator.normalizeDate(date), segments, actualByDate, anchor);
        }
    }

    /**
     * 加载一次相位数据快照。
     *
     * @param recordDao      记录 DAO
     * @param cycleRecordDao 周期分段 DAO
     * @param anchorDao      阶段锚点 DAO
     * @return 只读快照
     */
    public static PhaseSnapshot loadSnapshot(MenstrualRecordDao recordDao,
                                             MenstrualCycleRecordDao cycleRecordDao,
                                             PhaseAnchorDao anchorDao) {
        return new PhaseSnapshot(cycleRecordDao.selectAll(), loadActualPhases(recordDao),
                anchorDao.selectLatest());
    }

    /**
     * 对外的单日有效阶段查询（实际记录优先，否则分段+锚点推算）。
     * <p>供阶段范围编辑器初始化每天的默认阶段使用。
     *
     * @param date           目标日期
     * @param recordDao      记录 DAO
     * @param cycleRecordDao 周期分段 DAO
     * @param anchorDao      阶段锚点 DAO
     * @return 有效阶段；无法推算时返回 null
     */
    public static MenstrualCycle resolveEffectivePhase(Date date,
                                                       MenstrualRecordDao recordDao,
                                                       MenstrualCycleRecordDao cycleRecordDao,
                                                       PhaseAnchorDao anchorDao) {
        return loadSnapshot(recordDao, cycleRecordDao, anchorDao).phaseOf(date);
    }

    /**
     * 计算某天的有效阶段：有实际记录用实际阶段，否则按所属分段参数
     * （叠加阶段锚点重排）推算预测阶段；无法推算返回 null。
     */
    private static MenstrualCycle effectivePhase(Date date,
                                                 List<MenstrualCycleRecord> segments,
                                                 Map<String, MenstrualCycle> actualByDate,
                                                 PhaseAnchor anchor) {
        MenstrualCycle actual = actualByDate.get(CycleCalculator.formatDateId(date));
        if (actual != null) {
            return actual;
        }

        MenstrualCycleRecord covering = null;
        for (MenstrualCycleRecord seg : segments) {
            if (seg.getStartTime() != null && !seg.getStartTime().after(date)) {
                covering = seg;
            } else {
                break;
            }
        }
        if (covering == null) {
            return null;
        }

        int[] phaseArray = CycleCalculator.calculatePhaseDays(
                covering.getMenstrualPeriodDay(), covering.getTotalCycleDay());
        Date anchorDate = anchor != null ? anchor.getAnchorDate() : null;
        int anchorIndex = anchor != null ? anchor.getPhaseIndex() : -1;
        return CycleCalculator.getMenstrualCycleForDate(
                date, covering.getStartTime(), phaseArray, covering.getTotalCycleDay(),
                anchorDate, anchorIndex);
    }

    /** 读取全部实际记录，构建 dateId → 实际阶段 映射 */
    private static Map<String, MenstrualCycle> loadActualPhases(MenstrualRecordDao recordDao) {
        Map<String, MenstrualCycle> map = new HashMap<>();
        List<MenstrualRecord> actual = recordDao.selectActual();
        if (actual != null) {
            for (MenstrualRecord record : actual) {
                if (record.getMenstrualCycle() != null) {
                    map.put(record.getId(), record.getMenstrualCycle());
                }
            }
        }
        return map;
    }

    /** 日期加减天数（按 0 点归一） */
    private static Date addDays(Date date, int days) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        cal.add(Calendar.DAY_OF_MONTH, days);
        return cal.getTime();
    }
}
