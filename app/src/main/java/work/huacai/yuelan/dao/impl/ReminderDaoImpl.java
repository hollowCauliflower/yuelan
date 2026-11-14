package work.huacai.yuelan.dao.impl;

import android.content.ContentValues;
import android.database.Cursor;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

import work.huacai.yuelan.dao.ReminderDao;
import work.huacai.yuelan.database.DatabaseHelper;
import work.huacai.yuelan.model.Reminder;

/**
 * {@link ReminderDao} 的实现，直接读写 reminder 表。
 */
public class ReminderDaoImpl implements ReminderDao {

    private static final String TAG = "ReminderDaoImpl";
    private static final String TABLE = "reminder";

    private final DatabaseHelper databaseHelper = DatabaseHelper.getDatabaseHelper();

    /**
     * 查询全部提醒。
     * <p>
     * 排序口径：target_day 倒序（近日期在前），同日再按 id 倒序，
     * 与提醒列表页“最新添加的在上面”的展示一致。
     *
     * @return 提醒列表；查询失败返回空列表
     */
    @Override
    public List<Reminder> selectAll() {
        return query("SELECT * FROM reminder ORDER BY target_day DESC, id DESC");
    }

    /**
     * 查询指定日期的提醒，供首页/日历当天日程使用。
     *
     * @param targetDay 日期整数（yyyyMMdd）
     * @return 当天的提醒列表，按时间升序；无数据返回空列表
     */
    @Override
    public List<Reminder> selectByDay(int targetDay) {
        return query("SELECT * FROM reminder WHERE target_day = " + targetDay
                + " ORDER BY alarm_time ASC, id ASC");
    }

    /**
     * 按主键查询单条提醒。
     *
     * @param id 提醒 id
     * @return 提醒实体；不存在或查询失败返回 null
     */
    @Override
    public Reminder selectById(long id) {
        Cursor cursor = null;
        try {
            cursor = databaseHelper.querySQL(
                    "SELECT * FROM reminder WHERE id = " + id, null);
            if (cursor != null && cursor.moveToFirst()) {
                return parse(cursor);
            }
        } catch (Exception e) {
            Log.e(TAG, "selectById error", e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return null;
    }

    /**
     * 新增一条提醒。
     * <p>
     * 返回值是新行的 rowId（自增主键），调用方需自行回填到实体 id 后再排闹钟。
     *
     * @param reminder 提醒实体
     * @return 新行的 rowId；参数为 null 或插入失败返回 -1
     */
    @Override
    public long insert(Reminder reminder) {
        if (reminder == null) return -1;
        return databaseHelper.insert(TABLE, toValues(reminder));
    }

    /**
     * 按 id 更新提醒的全部可写字段。
     *
     * @param reminder 提醒实体，必须带有效 id
     * @return 受影响行数；参数非法或更新失败返回 0
     */
    @Override
    public int update(Reminder reminder) {
        if (reminder == null || reminder.getId() <= 0) return 0;
        return databaseHelper.update(TABLE, toValues(reminder),
                "id = ?", new String[]{String.valueOf(reminder.getId())});
    }

    /**
     * 删除一条提醒。
     * <p>
     * 只删数据库记录，不会取消已排的闹钟与通知，调用方需自行调用
     * {@code ReminderAlarmScheduler.cancel}。
     *
     * @param id 提醒 id
     * @return 受影响行数
     */
    @Override
    public int delete(long id) {
        return databaseHelper.delete(TABLE, "id = ?", new String[]{String.valueOf(id)});
    }

    /**
     * 只更新启用开关，避免覆盖用户正在编辑的其他字段。
     *
     * @param id      提醒 id
     * @param enabled 是否启用
     * @return 受影响行数
     */
    @Override
    public int setEnabled(long id, boolean enabled) {
        ContentValues values = new ContentValues();
        values.put("is_enabled", enabled ? 1 : 0);
        return databaseHelper.update(TABLE, values,
                "id = ?", new String[]{String.valueOf(id)});
    }

    /** 执行查询并转换为列表 */
    private List<Reminder> query(String sql) {
        List<Reminder> list = new ArrayList<>();
        Cursor cursor = null;
        try {
            cursor = databaseHelper.querySQL(sql, null);
            while (cursor != null && cursor.moveToNext()) {
                list.add(parse(cursor));
            }
        } catch (Exception e) {
            Log.e(TAG, "query error: " + sql, e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return list;
    }

    /** 实体转 ContentValues；新增时不带 id，依赖自增主键 */
    private ContentValues toValues(Reminder reminder) {
        ContentValues values = new ContentValues();
        values.put("title", reminder.getTitle());
        values.put("message", reminder.getMessage());
        values.put("reminder_type", reminder.getReminderType());
        values.put("target_day", reminder.getTargetDay());
        values.put("alarm_time", reminder.getAlarmTime());
        values.put("is_enabled", reminder.isEnabled() ? 1 : 0);
        values.put("is_alarm", reminder.isAlarm() ? 1 : 0);
        values.put("cycle_node", reminder.getCycleNode());
        values.put("repeat_type", reminder.getRepeatType());
        values.put("repeat_config", reminder.getRepeatConfig());
        values.put("repeat_end_day", reminder.getRepeatEndDay());
        return values;
    }

    /** 游标转实体 */
    private Reminder parse(Cursor cursor) {
        Reminder reminder = new Reminder();
        reminder.setId(cursor.getLong(cursor.getColumnIndexOrThrow("id")));
        reminder.setTitle(cursor.getString(cursor.getColumnIndexOrThrow("title")));
        reminder.setMessage(cursor.getString(cursor.getColumnIndexOrThrow("message")));
        reminder.setReminderType(cursor.getString(cursor.getColumnIndexOrThrow("reminder_type")));
        reminder.setTargetDay(cursor.getInt(cursor.getColumnIndexOrThrow("target_day")));
        reminder.setAlarmTime(cursor.getString(cursor.getColumnIndexOrThrow("alarm_time")));
        reminder.setEnabled(cursor.getInt(cursor.getColumnIndexOrThrow("is_enabled")) == 1);
        reminder.setAlarm(cursor.getInt(cursor.getColumnIndexOrThrow("is_alarm")) == 1);
        // cycle_node 列是版本 5 新增；老库升级异常时容忍缺列，默认固定日期模式
        int nodeIndex = cursor.getColumnIndex("cycle_node");
        reminder.setCycleNode(nodeIndex >= 0 ? cursor.getInt(nodeIndex) : Reminder.NODE_NONE);
        // repeat_* 列是版本 6 新增；老库升级异常时容忍缺列，默认不重复
        int repeatTypeIndex = cursor.getColumnIndex("repeat_type");
        reminder.setRepeatType(repeatTypeIndex >= 0
                ? cursor.getInt(repeatTypeIndex) : Reminder.REPEAT_NONE);
        int repeatConfigIndex = cursor.getColumnIndex("repeat_config");
        reminder.setRepeatConfig(repeatConfigIndex >= 0
                ? cursor.getString(repeatConfigIndex) : null);
        int repeatEndIndex = cursor.getColumnIndex("repeat_end_day");
        reminder.setRepeatEndDay(repeatEndIndex >= 0 ? cursor.getInt(repeatEndIndex) : 0);
        reminder.setCreatedTime(cursor.getString(cursor.getColumnIndexOrThrow("created_time")));
        return reminder;
    }
}
