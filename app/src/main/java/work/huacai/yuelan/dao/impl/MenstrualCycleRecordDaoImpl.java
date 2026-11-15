package work.huacai.yuelan.dao.impl;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import work.huacai.yuelan.dao.MenstrualCycleRecordDao;
import work.huacai.yuelan.dao.MenstrualRecordDao;
import work.huacai.yuelan.database.DatabaseHelper;
import work.huacai.yuelan.helper.CycleCalculator;
import work.huacai.yuelan.helper.GenerateSuggestionHelper;
import work.huacai.yuelan.model.MenstrualCycle;
import work.huacai.yuelan.model.MenstrualCycleRecord;
import work.huacai.yuelan.model.MenstrualRecord;

/**
 * {@link MenstrualCycleRecordDao} 的实现，管理 menstrual_cycle_record 表。
 * <p>
 * 除了读写用户设置的周期参数外，还负责根据周期推算每天的阶段，
 * 批量生成 menstrual_record 中的预测记录及其阶段建议文案。
 */
public class MenstrualCycleRecordDaoImpl implements MenstrualCycleRecordDao {

    private static final String TAG = "MenstrualCycleRecordDaoImpl";

    /** created_time 列的格式；SimpleDateFormat 非线程安全，使用处均加锁 */
    private static final SimpleDateFormat DATE_FORMAT_CREATED = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

    private final DatabaseHelper databaseHelper = DatabaseHelper.getDatabaseHelper();

    /**
     * 插入一条周期设置记录。
     * <p>
     * created_time 不在此处赋值，由建表语句中的默认值（本地当前时间）填充。
     *
     * @param menstrualCycleRecord 周期设置
     * @return 新行的 rowId；参数为 null 或插入失败返回 -1
     */
    @Override
    public long insert(MenstrualCycleRecord menstrualCycleRecord) {
        if (menstrualCycleRecord == null) {
            Log.e(TAG, "insert: menstrualCycleRecord is null");
            return -1;
        }
        
        ContentValues values = new ContentValues();
        String startTime = CycleCalculator.formatDateId(menstrualCycleRecord.getStartTime());
        values.put("start_time", startTime);
        values.put("total_cycle_day", menstrualCycleRecord.getTotalCycleDay());
        values.put("menstrual_period_day", menstrualCycleRecord.getMenstrualPeriodDay());
        return databaseHelper.insert("menstrual_cycle_record", values);
    }

    /**
     * 更新最近一条周期设置（按 id 定位）；记录没有 id（首次保存）时新增一行。
     * <p>
     * 旧实现每次修改设置都调用 {@link #insert}，会不断往 menstrual_cycle_record
     * 表里追加新行，记录数随修改次数膨胀。改为原地更新后，表里只保留最新一份设置。
     *
     * @param menstrualCycleRecord 周期设置
     * @return 更新/插入成功返回行 id；失败返回 -1
     */
    @Override
    public long updateLatest(MenstrualCycleRecord menstrualCycleRecord) {
        if (menstrualCycleRecord == null) {
            Log.e(TAG, "updateLatest: menstrualCycleRecord is null");
            return -1;
        }
        // 没有 id 说明这一行还不存在（例如首次设置），退回插入逻辑
        if (menstrualCycleRecord.getId() <= 0) {
            return insert(menstrualCycleRecord);
        }

        ContentValues values = new ContentValues();
        values.put("start_time", CycleCalculator.formatDateId(menstrualCycleRecord.getStartTime()));
        values.put("total_cycle_day", menstrualCycleRecord.getTotalCycleDay());
        values.put("menstrual_period_day", menstrualCycleRecord.getMenstrualPeriodDay());

        int updated = databaseHelper.update("menstrual_cycle_record", values,
                "id = ?", new String[]{String.valueOf(menstrualCycleRecord.getId())});
        if (updated <= 0) {
            // 记录可能已被外部删除（如导入/清空数据），此时按插入兜底
            Log.w(TAG, "updateLatest: 目标记录不存在，改为插入, id=" + menstrualCycleRecord.getId());
            return insert(menstrualCycleRecord);
        }
        return menstrualCycleRecord.getId();
    }

    /**
     * 用户手动把某天指定为经期起始日（设置页“当前周期设置” / 首页“周期不准？”）时调用。
     * <p>
     * 处理两步：
     * <ol>
     *   <li>把该日期落成“实际经期”记录（库内 is_prediction = 1），使它成为实际锚点；</li>
     *   <li>确保存在一条以该日开头的周期分段，参数使用本次设置。</li>
     * </ol>
     * 缺少任何一步，随后的 {@link #syncActualStartAndRegenerate} 都会按“最近一段实际经期”
     * 重算基准日并把用户的选择回滚。典型表现：上一轮实际经期在 10-01，用户把 11-09 设为起点后，
     * 11-09 因不是锚点被丢弃，基准日退回 10-01，11-09 正好落在第 12 天而被预测成排卵期，
     * 首页/日历都显示排卵期并弹出排卵提示。
     *
     * @param startDate          用户选择的经期起始日
     * @param totalCycleDay      周期总天数
     * @param menstrualPeriodDay 经期天数
     * @param context            用于生成建议文案
     */
    public void applyManualCycleStart(Date startDate, int totalCycleDay,
                                      int menstrualPeriodDay, Context context) {
        if (startDate == null) {
            return;
        }
        Date normalized = CycleCalculator.normalizeDate(startDate);
        String dateId = CycleCalculator.formatDateId(normalized);

        // 1) 起始日必须是实际经期锚点，否则会被基准日对齐逻辑回滚。
        //    只更新阶段与“实际”标记，保留该日已填写的流量/痛经与建议文案
        ContentValues recordValues = new ContentValues();
        recordValues.put("is_prediction", 1);
        recordValues.put("menstrual_cycle", MenstrualCycle.MENSTRUAL_PERIOD.name());
        recordValues.put("updated_time", currentTimeString());
        int updated = databaseHelper.update("menstrual_record", recordValues,
                "id = ?", new String[]{dateId});
        if (updated <= 0) {
            MenstrualRecord record = new MenstrualRecord();
            record.setId(dateId);
            record.setMenstrualCycle(MenstrualCycle.MENSTRUAL_PERIOD);
            record.setPrediction(false);
            record.setSuggestion(MenstrualCycle.MENSTRUAL_PERIOD.getTips(context));
            new MenstrualRecordDaoImpl().insert(record);
        }

        // 2) 同一日期只保留一条分段；参数按本次设置覆盖
        MenstrualCycleRecord existing = null;
        for (MenstrualCycleRecord seg : selectAll()) {
            if (seg.getStartTime() != null && isSameDay(seg.getStartTime(), normalized)) {
                existing = seg;
                break;
            }
        }
        if (existing != null) {
            existing.setTotalCycleDay(totalCycleDay);
            existing.setMenstrualPeriodDay(menstrualPeriodDay);
            updateLatest(existing);
        } else {
            MenstrualCycleRecord seg = new MenstrualCycleRecord();
            seg.setStartTime(normalized);
            seg.setTotalCycleDay(totalCycleDay);
            seg.setMenstrualPeriodDay(menstrualPeriodDay);
            insert(seg);
        }
        Log.i(TAG, "applyManualCycleStart: 起始日=" + dateId
                + ", totalCycleDay=" + totalCycleDay
                + ", menstrualPeriodDay=" + menstrualPeriodDay);
    }

    /**
     * 查询当前周期的分段设置（按分段起始日倒序取第一条）。
     * <p>
     * 表内每轮实际周期一行；start_time 为 yyyyMMdd 文本，字典序即时间序。
     *
     * @return 最新周期设置；表为空或读取失败返回 null
     */
    @Override
    public MenstrualCycleRecord selectLast() {
        String sql = "SELECT * FROM menstrual_cycle_record ORDER BY start_time DESC, id DESC LIMIT 1";
        Cursor cursor = null;
        try {
            cursor = databaseHelper.querySQL(sql, null);
            if (cursor != null && cursor.moveToFirst()) {
                return parseRecord(cursor);
            }
        } catch (Exception e) {
            Log.e(TAG, "selectLast error", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }

    /**
     * 查询全部周期分段（按分段起始日升序），用于日历按分段渲染与历史周期展示。
     *
     * @return 分段列表；表为空或读取失败返回空列表
     */
    @Override
    public List<MenstrualCycleRecord> selectAll() {
        List<MenstrualCycleRecord> records = new ArrayList<>();
        String sql = "SELECT * FROM menstrual_cycle_record ORDER BY start_time ASC, id ASC";
        Cursor cursor = null;
        try {
            cursor = databaseHelper.querySQL(sql, null);
            while (cursor != null && cursor.moveToNext()) {
                MenstrualCycleRecord record = parseRecord(cursor);
                if (record != null) {
                    records.add(record);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "selectAll error", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return records;
    }

    /**
     * 按 id 删除一条周期分段（用于锚点回退时清理多余分段）。
     *
     * @param id 分段行 id
     */
    private void deleteById(int id) {
        databaseHelper.delete("menstrual_cycle_record", "id = ?",
                new String[]{String.valueOf(id)});
    }

    /** 从游标当前行解析一条周期分段记录 */
    private MenstrualCycleRecord parseRecord(Cursor cursor) {
        int id = cursor.getInt(0);
        String startTimeStr = cursor.getString(1);
        int totalCycleDay = cursor.getInt(2);
        int menstrualPeriodDay = cursor.getInt(3);
        String createdTimeStr = cursor.getString(4);

        MenstrualCycleRecord record = new MenstrualCycleRecord();
        record.setId(id);
        record.setTotalCycleDay(totalCycleDay);
        record.setMenstrualPeriodDay(menstrualPeriodDay);
        record.setStartTime(CycleCalculator.parseDateId(startTimeStr));

        if (createdTimeStr != null) {
            synchronized (DATE_FORMAT_CREATED) {
                try {
                    record.setCreatedTime(DATE_FORMAT_CREATED.parse(createdTimeStr));
                } catch (ParseException e) {
                    Log.e(TAG, "Parse created_time error", e);
                }
            }
        }
        return record;
    }

    /**
     * 从周期起始日逐日生成/刷新每日记录，直到今天。
     * <p>
     * 采用 UPSERT 语义：先一次性读出区间内已存在的记录状态，
     * <ul>
     *   <li>用户实际记录（实体 isPrediction=false，对应库内 is_prediction = 1）原样保留，绝不覆盖；</li>
     *   <li>已存在的预测记录（库内 is_prediction = 0）若阶段变化或建议为空则更新阶段与本地建议；</li>
     *   <li>缺失日期才插入，并异步请求天气补充建议。</li>
     * </ul>
     * 这样既避免了旧实现“insert 撞主键直接失败、预测永远不更新”的问题，
     * 也不会把用户手填的数据冲掉。
     *
     * @param menstrualCycleRecord 周期设置
     * @param context              用于生成建议文案与定位/天气
     */
    @Override
    public void insertToToday(MenstrualCycleRecord menstrualCycleRecord, Context context) {
        if (menstrualCycleRecord == null || context == null || menstrualCycleRecord.getStartTime() == null) {
            Log.e(TAG, "insertToToday: invalid parameters");
            return;
        }

        Date today = CycleCalculator.normalizeDate(new Date());
        Date currentDate = CycleCalculator.normalizeDate(menstrualCycleRecord.getStartTime());

        if (currentDate == null) {
            Log.e(TAG, "insertToToday: startTime is null");
            return;
        }
        if (currentDate.after(today)) {
            Log.i(TAG, "insertToToday: 起始日在未来，无需生成");
            return;
        }

        int[] phaseArray = CycleCalculator.calculatePhaseDays(
                menstrualCycleRecord.getMenstrualPeriodDay(),
                menstrualCycleRecord.getTotalCycleDay());

        // 阶段锚点机制已移除：直接用当前周期参数生成预测，
        // 周期长度变化由实际经期记录形成的新分段处理
        generateRangeRecords(currentDate, today, menstrualCycleRecord.getStartTime(),
                phaseArray, menstrualCycleRecord.getTotalCycleDay(), null, -1, context);
    }

    /**
     * 重算中间分段（已结束的真实周期）的预测记录。
     * <p>
     * 中间分段的长度以"到下一分段起点的实际间隔"为准：若仍按分段保存时的预设长度
     * 取模循环，陈旧预测会在新锚点之前再冒出一轮月经期（日历上看着像一个月来了两次）。
     * 这里按实际间隔重新分配阶段（经期天数不变，黄体期固定贴近下次经期，
     * 卵泡期吸收长短差值），刷新区间内的预测记录；用户实际记录原样保留。
     * <p>
     * 例外：当实际间隔不足以支撑完整周期（放不下"经期 + 1 天卵泡 + 3 天排卵 +
     * 14 天黄体"）时，强行压缩阶段会严重失真——此时该分段只保留经期标记，
     * 其余日期不生成任何阶段预测（并清掉之前错误生成的预测记录）。
     * <p>
     * 中间分段不套用阶段锚点：早于最新分段起点的锚点已在
     * {@link #syncActualStartAndRegenerate} 中清理。
     *
     * @param context 用于生成建议文案
     */
    private void regenerateMidSegments(Context context) {
        List<MenstrualCycleRecord> segments = selectAll();
        if (segments.size() < 2) {
            return;
        }
        for (int i = 0; i + 1 < segments.size(); i++) {
            Date segStart = segments.get(i).getStartTime();
            Date nextStart = segments.get(i + 1).getStartTime();
            if (segStart == null || nextStart == null) {
                continue;
            }
            int actualTotal = CycleCalculator.daysBetween(segStart, nextStart);
            if (actualTotal <= 0) {
                continue;
            }
            // 区间结束日 = 下一分段起点的前一天
            Calendar endCal = Calendar.getInstance();
            endCal.setTime(nextStart);
            endCal.add(Calendar.DAY_OF_MONTH, -1);

            int menstrual = segments.get(i).getMenstrualPeriodDay();
            int[] stretched;
            if (!CycleCalculator.canResequenceCycle(actualTotal, menstrual)) {
                // 间隔太短放不下完整四阶段：只保留经期标记，避免压缩出畸形周期
                stretched = new int[]{menstrual, 0, 0, 0};
            } else {
                stretched = CycleCalculator.calculatePhaseDays(menstrual, actualTotal);
            }
            generateRangeRecords(CycleCalculator.normalizeDate(segStart), endCal.getTime(),
                    segStart, stretched, actualTotal, null, -1, context);
        }
    }

    /**
     * 逐日生成/刷新 [rangeStart, rangeEnd] 区间内的每日记录（UPSERT 语义）。
     * <ul>
     *   <li>用户实际记录（实体 isPrediction=false，对应库内 is_prediction = 1）原样保留，绝不覆盖；</li>
     *   <li>已存在的预测记录（库内 is_prediction = 0）若阶段变化或建议为空则更新阶段与本地建议；</li>
     *   <li>缺失日期才插入，并异步请求天气补充建议。</li>
     * </ul>
     *
     * @param rangeStart    区间起始日（含，自然日）
     * @param rangeEnd      区间结束日（含，自然日）
     * @param cycleBase     周期推算基准日（通常为该分段起点）
     * @param phaseArray    阶段天数数组（中间分段传按实际长度拉伸后的数组）
     * @param totalCycleDay 有效周期总天数（中间分段传实际间隔）
     * @param anchorDate    阶段锚点日期；null 表示无锚点
     * @param anchorIndex   锚点阶段下标（0~3），-1 表示无锚点
     * @param context       用于生成建议文案
     */
    private void generateRangeRecords(Date rangeStart, Date rangeEnd, Date cycleBase,
                                      int[] phaseArray, int totalCycleDay,
                                      Date anchorDate, int anchorIndex, Context context) {
        // 预取区间内已有记录：id -> {是否预测, 阶段名}
        Map<String, ExistingRecordState> existing = loadExistingStates(
                CycleCalculator.formatDateId(rangeStart),
                CycleCalculator.formatDateId(rangeEnd));
        Log.d(TAG, "generateRangeRecords: start=" + CycleCalculator.formatDateId(rangeStart)
                + ", end=" + CycleCalculator.formatDateId(rangeEnd)
                + ", totalCycleDay=" + totalCycleDay
                + ", existCount=" + existing.size());

        MenstrualRecordDao menstrualRecordDao = new MenstrualRecordDaoImpl();

        Calendar currentCal = Calendar.getInstance();
        currentCal.setTime(rangeStart);
        Date currentDate = currentCal.getTime();

        int insertCount = 0;
        int updateCount = 0;
        int skippedActualCount = 0;

        while (!currentDate.after(rangeEnd)) {
            MenstrualCycle cycle = CycleCalculator.getMenstrualCycleForDate(
                    currentDate,
                    cycleBase,
                    phaseArray,
                    totalCycleDay,
                    anchorDate,
                    anchorIndex);

            if (cycle != null) {
                // 记录 id 用日期串（yyyyMMdd），天然充当主键并保证同日只生成一条
                String recordId = CycleCalculator.formatDateId(currentDate);
                String localSuggestion = GenerateSuggestionHelper.generateSuggestion(context, cycle);
                ExistingRecordState state = existing.get(recordId);

                if (state == null) {
                    MenstrualRecord record = new MenstrualRecord();
                    record.setId(recordId);
                    record.setMenstrualCycle(cycle);
                    record.setPrediction(true);
                    record.setSuggestion(localSuggestion);

                    long result = menstrualRecordDao.insert(record);
                    if (result == -1) {
                        Log.e(TAG, "插入失败: " + recordId);
                    } else {
                        insertCount++;
                    }
                } else if (state.prediction) {
                    // 预测记录：阶段变化或建议缺失时刷新，避免基准日变化后仍展示旧阶段/旧建议
                    boolean phaseChanged = !cycle.name().equals(state.cycleName);
                    boolean missingSuggestion = state.suggestion == null || state.suggestion.trim().isEmpty();

                    if (phaseChanged || missingSuggestion) {
                        ContentValues values = new ContentValues();
                        values.put("menstrual_cycle", cycle.name());
                        values.put("suggestion", localSuggestion);
                        values.put("updated_time", currentTimeString());
                        int updated = databaseHelper.update(
                                "menstrual_record",
                                values,
                                // 只更新预测行（库内 0 = 预测），绝不覆盖用户实际记录
                                "id = ? AND is_prediction = 0",
                                new String[]{recordId});
                        if (updated > 0) {
                            updateCount++;
                        }
                    }
                } else {
                    // 用户实际记录，保持原样
                    skippedActualCount++;
                }
            } else {
                // 该日期不属于任何阶段（短间隔分段只保留经期标记，其余日期无预测）：
                // 清掉之前版本错误生成的预测记录；用户实际记录保留
                String recordId = CycleCalculator.formatDateId(currentDate);
                ExistingRecordState state = existing.get(recordId);
                if (state != null && state.prediction) {
                    int deleted = menstrualRecordDao.deleteById(recordId);
                    if (deleted > 0) {
                        updateCount++;
                    }
                }
            }

            // currentCal 负责累加日期，currentDate 只作为循环判断的快照
            currentCal.add(Calendar.DAY_OF_MONTH, 1);
            currentDate = currentCal.getTime();
        }

        Log.i(TAG, "========== 区间记录写入完成 ==========");
        Log.i(TAG, "新增 " + insertCount + " 条，刷新预测 " + updateCount
                + " 条，保留实际记录 " + skippedActualCount + " 条");
    }

    /**
     * 预取日期区间内已存在的记录状态，避免逐日查询数据库。
     *
     * @param startId 起始日期 id（yyyyMMdd）
     * @param endId   结束日期 id（yyyyMMdd）
     * @return id 到记录状态的映射；查询失败时返回空映射（退化为全部插入，主键冲突会被忽略）
     */
    private Map<String, ExistingRecordState> loadExistingStates(String startId, String endId) {
        Map<String, ExistingRecordState> states = new HashMap<>();
        Cursor cursor = null;
        try {
            cursor = databaseHelper.querySQL(
                    "SELECT id, is_prediction, menstrual_cycle, suggestion FROM menstrual_record WHERE id >= ? AND id <= ?",
                    new String[]{startId, endId});
            while (cursor != null && cursor.moveToNext()) {
                // 库内约定：0 = 预测，1 = 实际；suggestion 用于判断建议是否缺失
                states.put(cursor.getString(0),
                        new ExistingRecordState(cursor.getInt(1) == 0,
                                cursor.getString(2), cursor.getString(3)));
            }
        } catch (Exception e) {
            Log.e(TAG, "loadExistingStates error", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return states;
    }

    /**
     * 已存在记录的轻量状态：是否预测 + 当前阶段名 + 当前建议文案。
     */
    private static class ExistingRecordState {
        /** true = 预测记录，false = 用户实际记录 */
        final boolean prediction;
        /** 当前阶段名，用于判断阶段是否发生变化 */
        final String cycleName;
        /** 当前建议文案，为空表示需要补全 */
        final String suggestion;

        ExistingRecordState(boolean prediction, String cycleName, String suggestion) {
            this.prediction = prediction;
            this.cycleName = cycleName;
            this.suggestion = suggestion;
        }
    }

    /**
     * 把预测基准对齐到实际经期记录，并按最新分段重算当前周期的预测记录。
     * <p>
     * 行为要点：
     * <ul>
     *   <li>没有任何实际经期记录时，保留设置中的起始日，直接按它生成预测；</li>
     *   <li>新一轮实际经期开始时<b>追加新分段</b>（沿用当前设置参数），
     *       历史分段保持原参数，实现"上个周期的设置不随这个周期变化"；</li>
     *   <li>用户删除经期记录导致锚点回退时，清理锚点之后多余的分段；</li>
     *   <li>最后按最新分段（叠加阶段锚点重排）重算当前周期的预测记录。</li>
     * </ul>
     *
     * @param context 用于生成建议文案
     */
    @Override
    public void syncActualStartAndRegenerate(Context context) {
        if (context == null) {
            return;
        }

        // 0) 清理历史上被误写入"未来日期"的实际记录：
        // 未来日期只应有预测记录（库内 is_prediction = 0），实际记录（=1）会让
        // 分段起点跑到今天之后，导致首页/日历算出"第 -7 天"这类异常天数
        String todayId = CycleCalculator.formatDateId(CycleCalculator.normalizeDate(new Date()));
        int cleaned = databaseHelper.delete("menstrual_record",
                "is_prediction = 1 AND id > ?", new String[]{todayId});
        if (cleaned > 0) {
            Log.i(TAG, "syncActualStartAndRegenerate: 清理未来日期的实际记录 " + cleaned + " 条");
        }

        // 1) 当前分段设置（没有就视为尚未初始化）
        MenstrualCycleRecord settings = selectLast();
        if (settings == null || settings.getStartTime() == null) {
            return;
        }

        // 2) 找出所有实际经期锚点并对分段表做 reconcile
        List<MenstrualRecord> actualRecords = new MenstrualRecordDaoImpl().selectActual();
        List<Date> anchors = CycleCalculator.findAllActualMenstrualAnchors(actualRecords);

        if (!anchors.isEmpty()) {
            reconcileSegments(anchors);

            // 3) 以最新分段为准重新读取；reconcile 可能插入了新分段
            settings = selectLast();
            if (settings == null || settings.getStartTime() == null) {
                return;
            }
        }

        // 5) 中间分段（已结束的真实周期）按实际周期长度重算预测，
        //    避免陈旧预测在新周期开始前再冒出一轮月经期
        regenerateMidSegments(context);

        // 6) 按最新分段参数重算当前周期的预测记录
        insertToToday(settings, context);
    }

    /**
     * 让周期分段表与实际经期锚点保持一致：
     * <ul>
     *   <li>删除晚于最新锚点、且自身不对应任何锚点的分段（锚点因删除记录而回退）；</li>
     *   <li>为每个尚无分段的锚点补建分段，参数沿用"包含该锚点的既有分段"，
     *       找不到时用当前最新设置（历史锚点尽力而为）。</li>
     * </ul>
     *
     * @param anchors 全部实际经期开始锚点（升序）
     */
    private void reconcileSegments(List<Date> anchors) {
        Date latestAnchor = anchors.get(anchors.size() - 1);
        MenstrualCycleRecord latestSettings = selectLast();
        if (latestSettings == null) {
            return;
        }

        // 1) 清理多余分段：start_time 晚于最新锚点且不命中任何锚点
        List<MenstrualCycleRecord> segments = selectAll();
        for (MenstrualCycleRecord seg : segments) {
            if (seg.getStartTime() != null && seg.getStartTime().after(latestAnchor)
                    && !containsSameDay(anchors, seg.getStartTime())) {
                deleteById(seg.getId());
                Log.i(TAG, "reconcileSegments: 删除多余分段 " + CycleCalculator.formatDateId(seg.getStartTime()));
            }
        }

        // 2) 为每个锚点确保存在分段
        segments = selectAll();
        // 补签单日是否值得触发整段重排：只有与上一轮经期起点的间隔能放下一个完整周期
        // （经期 + 1 卵泡 + 3 排卵 + 14 黄体）时才自动补建分段并重算后续预测；
        // 否则只保留这一天的实际经期记录，既有预测保持不变。
        // 已有分段的锚点（用户显式指定的起始日）不走该判定，保证用户选择始终生效。
        for (Date anchor : anchors) {
            if (findSegmentAt(segments, anchor) != null) {
                continue;
            }
            MenstrualCycleRecord paramsSource = findSegmentCovering(segments, anchor);
            if (paramsSource == null) {
                paramsSource = latestSettings;
            }
            // 上一段起点可确定且早于锚点时才判定；锚点早于所有分段（历史回填）保持原行为
            Date previousStart = paramsSource == null ? null : paramsSource.getStartTime();
            if (previousStart != null && !previousStart.after(anchor)
                    && !CycleCalculator.canResequenceCycle(
                            CycleCalculator.daysBetween(previousStart, anchor),
                            paramsSource.getMenstrualPeriodDay())) {
                Log.i(TAG, "reconcileSegments: 与上一轮经期间隔不足以容纳完整周期，仅记录单日 "
                        + CycleCalculator.formatDateId(anchor));
                continue;
            }
            MenstrualCycleRecord seg = new MenstrualCycleRecord();
            seg.setStartTime(anchor);
            seg.setTotalCycleDay(paramsSource.getTotalCycleDay());
            seg.setMenstrualPeriodDay(paramsSource.getMenstrualPeriodDay());
            insert(seg);
            Log.i(TAG, "reconcileSegments: 新增分段 " + CycleCalculator.formatDateId(anchor));
            segments = selectAll();
        }
    }

    /** 判断日期列表中是否存在与 target 同一天（忽略时分秒） */
    private boolean containsSameDay(List<Date> dates, Date target) {
        for (Date d : dates) {
            if (isSameDay(d, target)) {
                return true;
            }
        }
        return false;
    }

    /** 找 startTime 与 anchor 同一天的分段；找不到返回 null */
    private MenstrualCycleRecord findSegmentAt(List<MenstrualCycleRecord> segments, Date anchor) {
        for (MenstrualCycleRecord seg : segments) {
            if (seg.getStartTime() != null && isSameDay(seg.getStartTime(), anchor)) {
                return seg;
            }
        }
        return null;
    }

    /** 找"包含"该日期的分段：startTime 不晚于日期的最后一个分段；找不到返回 null */
    private MenstrualCycleRecord findSegmentCovering(List<MenstrualCycleRecord> segments, Date date) {
        MenstrualCycleRecord result = null;
        for (MenstrualCycleRecord seg : segments) {
            if (seg.getStartTime() != null && !seg.getStartTime().after(date)) {
                result = seg;
            } else {
                break;
            }
        }
        return result;
    }

    /** 判断两个日期是否同一天（忽略时分秒） */
    private boolean isSameDay(Date a, Date b) {
        if (a == null || b == null) {
            return false;
        }
        Calendar ca = Calendar.getInstance();
        Calendar cb = Calendar.getInstance();
        ca.setTime(a);
        cb.setTime(b);
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR)
                && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR);
    }

    /**
     * 保存周期设置，并在同一个事务中初始化到今天的每日记录。
     * <p>
     * 事务语义：{@link #insert} 失败会抛出异常并回滚，不会留下半套数据；
     * 注意 {@link #insertToToday} 内部使用的仍是同一个数据库连接，因此其写入也在该事务内。
     *
     * @param menstrualCycleRecord 周期设置
     * @param context              用于生成建议文案
     * @return 事务提交成功返回 true；抛出异常回滚后返回 false
     */
    @Override
    public boolean saveUserSettingsAndInitData(MenstrualCycleRecord menstrualCycleRecord, Context context) {
        Log.i(TAG, "========== 开始保存用户设置 ==========");
        SQLiteDatabase db = databaseHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            long rowId = insert(menstrualCycleRecord);
            if (rowId == -1) {
                throw new RuntimeException("insert menstrual_cycle_record data fail");
            }
            Log.d(TAG, "周期记录插入成功, rowId: " + rowId);

            insertToToday(menstrualCycleRecord, context);

            db.setTransactionSuccessful();
            Log.i(TAG, "========== 保存成功 ==========");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "事务执行失败，数据已回滚", e);
            return false;
        } finally {
            db.endTransaction();
        }
    }

    /**
     * 生成当前时间的字符串形式，用于写入 updated_time。
     *
     * @return 当前时间（yyyy-MM-dd HH:mm:ss）
     */
    private static String currentTimeString() {
        synchronized (DATE_FORMAT_CREATED) {
            return DATE_FORMAT_CREATED.format(new Date());
        }
    }
}
