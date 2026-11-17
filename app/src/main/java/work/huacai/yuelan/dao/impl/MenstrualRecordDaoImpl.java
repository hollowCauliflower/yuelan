package work.huacai.yuelan.dao.impl;

import android.content.ContentValues;
import android.database.Cursor;
import android.util.Log;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import work.huacai.yuelan.dao.MenstrualRecordDao;
import work.huacai.yuelan.database.DatabaseHelper;
import work.huacai.yuelan.model.MenstrualCycle;
import work.huacai.yuelan.model.MenstrualRecord;

/**
 * {@link MenstrualRecordDao} 的实现，直接读写 menstrual_record 表。
 * <p>
 * 表字段与实体字段的对应关系按固定列下标读取（见 {@link #parseRecordFromCursor}），
 * 因此列顺序必须与 database_config.xml 中 menstrual_record 的建表语句保持一致；
 * 新增列时要在语句末尾追加，读取侧用列数量判断是否存在。
 */
public class MenstrualRecordDaoImpl implements MenstrualRecordDao {

    private static final String TAG = "MenstrualRecordDaoImpl";

    /** 数据库中的时间字段统一使用该格式；SimpleDateFormat 非线程安全，使用处均加锁 */
    private static final SimpleDateFormat DATE_TIME_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

    private final DatabaseHelper databaseHelper = DatabaseHelper.getDatabaseHelper();

    /**
     * 查询全部记录，按日期倒序（最新在前）。
     *
     * @return 记录列表；无数据或查询失败返回空列表
     */
    @Override
    public List<MenstrualRecord> selectAll() {
        return queryRecords("SELECT * FROM menstrual_record ORDER BY id DESC", null, "selectAll");
    }

    /**
     * 只查询用户实际填写过的记录（is_prediction = 1），按日期倒序。
     * <p>
     * 注意库内约定：0 表示自动生成的预测记录，1 表示用户实际记录（与实体字段含义相反）。
     *
     * @return 实际记录列表；无数据或查询失败返回空列表
     */
    @Override
    public List<MenstrualRecord> selectActual() {
        return queryRecords("SELECT * FROM menstrual_record WHERE is_prediction = 1 ORDER BY id DESC", null, "selectActual");
    }

    /**
     * 查询日期最大（即最近一天）的一条记录。
     *
     * @return 最新记录；表为空或查询失败返回 null
     */
    @Override
    public MenstrualRecord selectLastId() {
        String sql = "SELECT * FROM menstrual_record ORDER BY id DESC LIMIT 1";
        Cursor cursor = null;
        try {
            cursor = databaseHelper.querySQL(sql, null);
            if (cursor != null && cursor.moveToFirst()) {
                return parseRecordFromCursor(cursor);
            }
        } catch (Exception e) {
            Log.e(TAG, "selectLastId error", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }

    /**
     * 插入一条每日记录。
     *
     * @param menstrualRecord 待插入的记录
     * @return 新行的 rowId；参数为 null 或插入失败返回 -1
     */
    @Override
    public long insert(MenstrualRecord menstrualRecord) {
        if (menstrualRecord == null || menstrualRecord.getMenstrualCycle() == null) {
            // 阶段为空时无法写入 menstrual_cycle（读取侧会因枚举解析失败丢弃整行），直接拒绝
            Log.e(TAG, "insert: record or menstrualCycle is null");
            return -1;
        }
        
        Log.d(TAG, "insert: id=" + menstrualRecord.getId() 
                + ", cycle=" + menstrualRecord.getMenstrualCycle().name()
                + ", suggestion=" + menstrualRecord.getSuggestion());
        
        ContentValues values = recordToContentValues(menstrualRecord);
        long result = databaseHelper.insert("menstrual_record", values);
        Log.d(TAG, "insert result: " + result);
        return result;
    }

    /**
     * 更新指定记录的建议内容，同时刷新 updated_time。
     *
     * @param id         记录 id（yyyyMMdd）
     * @param suggestion 新的建议文本
     * @return 受影响行数；id 为空或更新失败返回 0
     */
    @Override
    public int updateSuggestion(String id, String suggestion) {
        if (id == null || id.isEmpty()) {
            Log.e(TAG, "updateSuggestion: id is null or empty");
            return 0;
        }
        
        Log.d(TAG, "updateSuggestion: id=" + id + ", suggestion=" + suggestion);
        
        ContentValues values = new ContentValues();
        values.put("suggestion", suggestion);
        values.put("updated_time", currentTimeString());
        
        int result = databaseHelper.update("menstrual_record", values, "id = ?", new String[]{id});
        Log.d(TAG, "updateSuggestion result: " + result + " rows updated");
        return result;
    }

    /**
     * 只更新指定日期的心情等级，其余字段保持不变。
     *
     * @param id   记录 id（yyyyMMdd）
     * @param mood 心情等级
     * @return 受影响行数；id 为空或更新失败返回 0
     */
    @Override
    public int updateMood(String id, int mood) {
        if (id == null || id.isEmpty()) {
            return 0;
        }
        ContentValues values = new ContentValues();
        values.put("mood", mood);
        values.put("updated_time", currentTimeString());
        return databaseHelper.update("menstrual_record", values, "id = ?", new String[]{id});
    }

    /**
     * 按 id（日期）查询单条记录。
     *
     * @param id 记录 id（yyyyMMdd）
     * @return 记录；不存在或 id 为空返回 null
     */
    @Override
    public MenstrualRecord selectById(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        
        String sql = "SELECT * FROM menstrual_record WHERE id = ?";
        Cursor cursor = null;
        try {
            cursor = databaseHelper.querySQL(sql, new String[]{id});
            if (cursor != null && cursor.moveToFirst()) {
                return parseRecordFromCursor(cursor);
            }
        } catch (Exception e) {
            Log.e(TAG, "selectById error: " + id, e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }

    /**
     * 查询闭区间 [startDate, endDate] 内的记录，按日期升序。
     * <p>
     * id 是 yyyyMMdd 文本，等长且左对齐，因此可以直接用字符串比较做范围过滤。
     *
     * @param startDate 起始日期 id
     * @param endDate   结束日期 id
     * @return 记录列表；参数为 null、无数据或查询失败返回空列表
     */
    @Override
    public List<MenstrualRecord> selectByDateRange(String startDate, String endDate) {
        if (startDate == null || endDate == null) {
            return new ArrayList<>();
        }

        return queryRecords(
                "SELECT * FROM menstrual_record WHERE id >= ? AND id <= ? ORDER BY id ASC",
                new String[]{startDate, endDate},
                "selectByDateRange");
    }

    /**
     * 用整条记录覆盖更新数据库中的对应行，并刷新 updated_time。
     * <p>
     * 按 id 定位；供记录详情页与补录流程复用。
     *
     * @param record 待更新的记录，按 id 定位
     * @return 受影响行数；record 或 id 为空、更新失败返回 0
     */
    @Override
    public int updateRecord(MenstrualRecord record) {
        if (record == null || record.getId() == null || record.getMenstrualCycle() == null) {
            Log.e(TAG, "updateRecord: record, id or menstrualCycle is null");
            return 0;
        }
        
        ContentValues values = recordToContentValues(record);
        values.put("updated_time", currentTimeString());
        
        return databaseHelper.update("menstrual_record", values, "id = ?", new String[]{record.getId()});
    }

    /**
     * 按日期 id 删除一条记录。
     * <p>
     * 记录以日期为主键，删除后该日期不再有数据；调用方随后应调用
     * {@code MenstrualCycleRecordDaoImpl#syncActualStartAndRegenerate} 重算预测基准日，
     * 让该日期按新周期重新生成预测占位记录。
     *
     * @param id 记录 id（yyyyMMdd）
     * @return 实际删除的行数；参数为空或删除失败返回 0
     */
    @Override
    public int deleteById(String id) {
        if (id == null || id.isEmpty()) {
            Log.e(TAG, "deleteById: id is null or empty");
            return 0;
        }
        return databaseHelper.delete("menstrual_record", "id = ?", new String[]{id});
    }

    /**
     * 执行查询并把每一行解析成实体。
     * <p>
     * 游标在本方法内关闭；单行解析失败只跳过该行，不影响其余数据。
     *
     * @param sql           查询语句
     * @param selectionArgs 查询参数，可为 null
     * @param methodName    调用方方法名，仅用于错误日志定位
     * @return 解析后的记录列表，可能为空
     */
    private List<MenstrualRecord> queryRecords(String sql, String[] selectionArgs, String methodName) {
        List<MenstrualRecord> records = new ArrayList<>();
        Cursor cursor = null;
        try {
            cursor = databaseHelper.querySQL(sql, selectionArgs);
            while (cursor != null && cursor.moveToNext()) {
                MenstrualRecord record = parseRecordFromCursor(cursor);
                if (record != null) {
                    records.add(record);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, methodName + " error", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return records;
    }

    /**
     * 把实体转换成 ContentValues。
     * <p>
     * 注意 is_prediction 列与实体字段是反的：数据库里 0 表示预测、1 表示实际，
     * 这与读取侧 {@link #parseRecordFromCursor} 中 getInt 是否为 0 的判断互为镜像，属于既有数据约定。
     *
     * @param record 待写入的实体
     * @return 可直接交给 insert/update 的列值
     */
    private ContentValues recordToContentValues(MenstrualRecord record) {
        ContentValues values = new ContentValues();
        values.put("id", record.getId());
        values.put("menstrual_cycle", record.getMenstrualCycle().name());
        values.put("is_prediction", record.isPrediction() ? 0 : 1);
        values.put("suggestion", record.getSuggestion());
        values.put("flow_level", record.getFlowLevel());
        values.put("flow_score", record.getFlowScore());
        values.put("pain_level", record.getPainLevel());
        values.put("pad_count", record.getPadCount());
        values.put("pad_light_count", record.getPadLightCount());
        values.put("pad_medium_count", record.getPadMediumCount());
        values.put("pad_heavy_count", record.getPadHeavyCount());
        values.put("clot_small_count", record.getClotSmallCount());
        values.put("clot_large_count", record.getClotLargeCount());
        values.put("notes", record.getNotes());
        values.put("flow_note", record.getFlowNote());
        values.put("pain_note", record.getPainNote());
        values.put("mood", record.getMood());
        return values;
    }

    /**
     * 从游标当前行解析出一条记录。
     * <p>
     * 前 4 列（id、menstrual_cycle、is_prediction、suggestion）是建表时就有的必填列，直接读取；
     * 其余列按“列数量是否足够”判断存在性，兼容旧版本数据库文件缺少新增列的情况。
     *
     * @param cursor 已定位到目标行的游标（索引 0 对应 id 列）
     * @return 解析出的记录；字段值非法（如 menstrual_cycle 无法枚举）时返回 null
     */
    private MenstrualRecord parseRecordFromCursor(Cursor cursor) {
        try {
            MenstrualRecord record = new MenstrualRecord();
            record.setId(cursor.getString(0));
            record.setMenstrualCycle(MenstrualCycle.valueOf(cursor.getString(1)));
            record.setPrediction(cursor.getInt(2) == 0);
            record.setSuggestion(cursor.getString(3));

            // 与建表语句中的列顺序一一对应
            int flowLevelIndex = 4;
            int flowScoreIndex = 5;
            int painLevelIndex = 6;
            int padCountIndex = 7;
            int padLightIndex = 8;
            int padMediumIndex = 9;
            int padHeavyIndex = 10;
            int clotSmallIndex = 11;
            int clotLargeIndex = 12;
            int notesIndex = 13;
            int createdTimeIndex = 14;
            int updatedTimeIndex = 15;
            // v3 新增：流量 / 痛经专属备注，列在表结构末尾
            int flowNoteIndex = 16;
            int painNoteIndex = 17;
            // v9 新增：心情等级，追加在表结构最后
            int moodIndex = 18;

            if (hasColumn(cursor, flowLevelIndex)) {
                record.setFlowLevel(cursor.getInt(flowLevelIndex));
            }
            if (hasColumn(cursor, flowScoreIndex)) {
                record.setFlowScore(cursor.getInt(flowScoreIndex));
            }
            if (hasColumn(cursor, painLevelIndex)) {
                record.setPainLevel(cursor.getInt(painLevelIndex));
            }
            if (hasColumn(cursor, padCountIndex)) {
                record.setPadCount(cursor.getInt(padCountIndex));
            }
            if (hasColumn(cursor, padLightIndex)) {
                record.setPadLightCount(cursor.getInt(padLightIndex));
            }
            if (hasColumn(cursor, padMediumIndex)) {
                record.setPadMediumCount(cursor.getInt(padMediumIndex));
            }
            if (hasColumn(cursor, padHeavyIndex)) {
                record.setPadHeavyCount(cursor.getInt(padHeavyIndex));
            }
            if (hasColumn(cursor, clotSmallIndex)) {
                record.setClotSmallCount(cursor.getInt(clotSmallIndex));
            }
            if (hasColumn(cursor, clotLargeIndex)) {
                record.setClotLargeCount(cursor.getInt(clotLargeIndex));
            }
            if (hasColumn(cursor, notesIndex) && !cursor.isNull(notesIndex)) {
                record.setNotes(cursor.getString(notesIndex));
            }
            if (hasColumn(cursor, flowNoteIndex) && !cursor.isNull(flowNoteIndex)) {
                record.setFlowNote(cursor.getString(flowNoteIndex));
            }
            if (hasColumn(cursor, painNoteIndex) && !cursor.isNull(painNoteIndex)) {
                record.setPainNote(cursor.getString(painNoteIndex));
            }
            if (hasColumn(cursor, moodIndex)) {
                record.setMood(cursor.getInt(moodIndex));
            }

            record.setCreated_time(parseDateTime(cursor, createdTimeIndex, "created_time"));
            record.setUpdated_time(parseDateTime(cursor, updatedTimeIndex, "updated_time"));

            return record;
        } catch (Exception e) {
            Log.e(TAG, "parseRecordFromCursor error", e);
            return null;
        }
    }

    /**
     * 判断游标是否包含指定下标的列。
     *
     * @param cursor 游标
     * @param index  列下标
     * @return 存在该列返回 true
     */
    private static boolean hasColumn(Cursor cursor, int index) {
        return cursor.getColumnCount() > index;
    }

    /**
     * 读取并解析 yyyy-MM-dd HH:mm:ss 格式的时间列。
     *
     * @param cursor 游标
     * @param index  时间列下标
     * @param field  字段名，仅用于错误日志
     * @return 解析后的时间；列不存在、值为 null 或格式错误返回 null
     */
    private static Date parseDateTime(Cursor cursor, int index, String field) {
        if (!hasColumn(cursor, index)) {
            return null;
        }
        String value = cursor.getString(index);
        if (value == null) {
            return null;
        }
        synchronized (DATE_TIME_FORMAT) {
            try {
                return DATE_TIME_FORMAT.parse(value);
            } catch (ParseException e) {
                Log.e(TAG, "Parse " + field + " error: " + value, e);
                return null;
            }
        }
    }

    /**
     * 生成当前时间的字符串形式，用于写入 updated_time。
     * <p>
     * SimpleDateFormat 不是线程安全的，因此加锁后再格式化。
     *
     * @return 当前时间（yyyy-MM-dd HH:mm:ss）
     */
    private static String currentTimeString() {
        synchronized (DATE_TIME_FORMAT) {
            return DATE_TIME_FORMAT.format(new Date());
        }
    }
}
