package work.huacai.yuelan.database;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 数据库备份 / 数据导出导入的统一入口。
 * <p>
 * 设计约定：
 * <ul>
 *   <li>所有备份文件统一放在应用私有目录的 {@code auto_backup/} 下，自动备份前缀为
 *       {@code auto_backup_}，手动备份前缀为 {@code manual_backup_}，两者互不覆盖；</li>
 *   <li>自动备份每天最多一份，启动时只判断“今天是否已有自动备份”，避免重复写盘；</li>
 *   <li>自动备份与手动备份各自最多保留 {@link #MAX_KEEP} 份，超出后按时间从旧到新删除；</li>
 *   <li>导出 / 导入覆盖月经周期、每日记录、提醒三张业务表，导入过程放在同一事务中，
 *       失败会整体回滚，不会出现“数据被清空但没导入成功”的情况。</li>
 * </ul>
 */
public class DatabaseBackupHelper {

    private static final String TAG = "DatabaseBackupHelper";

    /** 备份目录名（位于应用私有 files 目录下，无需存储权限） */
    public static final String BACKUP_DIR_NAME = "auto_backup";
    /** 自动备份文件名前缀 */
    private static final String AUTO_PREFIX = "auto_backup_";
    /** 手动备份文件名前缀 */
    private static final String MANUAL_PREFIX = "manual_backup_";
    /** 每类备份最多保留的份数 */
    private static final int MAX_KEEP = 5;

    /** 文件名中的时间戳格式 */
    private static final SimpleDateFormat FILE_TIME_FORMAT =
            new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
    /** 判断“今天是否已备份”用的日期格式 */
    private static final SimpleDateFormat DAY_FORMAT =
            new SimpleDateFormat("yyyyMMdd", Locale.getDefault());

    /** 工具类不允许实例化 */
    private DatabaseBackupHelper() {
    }

    // ==================== 备份 ====================

    /**
     * 今天是否还需要自动备份。
     *
     * @param context 任意 Context
     * @return 今天还没有自动备份文件时返回 true
     */
    public static boolean shouldAutoBackup(Context context) {
        String todayPrefix = AUTO_PREFIX + DAY_FORMAT.format(new Date());
        File[] files = getBackupDir(context).listFiles((dir, name) ->
                name.startsWith(todayPrefix) && name.endsWith(".db"));
        return files == null || files.length == 0;
    }

    /**
     * 需要时执行一次自动备份（在后台线程调用）。
     *
     * @param context 任意 Context
     * @return 新生成的备份文件；今天已备份或失败时返回 null
     */
    public static File performAutoBackupIfNeeded(Context context) {
        if (!shouldAutoBackup(context)) {
            return null;
        }
        try {
            File backup = createBackupFile(context, AUTO_PREFIX);
            cleanupOldBackups(getBackupDir(context), AUTO_PREFIX);
            Log.i(TAG, "自动备份完成: " + backup.getAbsolutePath());
            return backup;
        } catch (Exception e) {
            Log.e(TAG, "自动备份失败", e);
            return null;
        }
    }

    /**
     * 创建一个手动备份文件。
     *
     * @param context 任意 Context
     * @return 新生成的备份文件
     * @throws Exception 备份目录不可用或复制失败
     */
    public static File createManualBackup(Context context) throws Exception {
        File backup = createBackupFile(context, MANUAL_PREFIX);
        cleanupOldBackups(getBackupDir(context), MANUAL_PREFIX);
        Log.i(TAG, "手动备份完成: " + backup.getAbsolutePath());
        return backup;
    }

    /**
     * 列出全部备份文件（自动 + 手动），按修改时间从新到旧排序。
     *
     * @param context 任意 Context
     * @return 备份文件列表，永不为 null
     */
    public static List<File> listBackups(Context context) {
        File[] files = getBackupDir(context).listFiles((dir, name) ->
                (name.startsWith(AUTO_PREFIX) || name.startsWith(MANUAL_PREFIX))
                        && name.endsWith(".db"));
        if (files == null) {
            return new ArrayList<>();
        }
        List<File> list = new ArrayList<>(Arrays.asList(files));
        list.sort((f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));
        return list;
    }

    /**
     * 获取最近一次备份文件。
     *
     * @param context 任意 Context
     * @return 最新的备份文件；没有任何备份时返回 null
     */
    public static File getLatestBackup(Context context) {
        List<File> backups = listBackups(context);
        return backups.isEmpty() ? null : backups.get(0);
    }

    /**
     * 获取备份目录，不存在时创建。
     */
    private static File getBackupDir(Context context) {
        File dir = new File(context.getApplicationContext().getFilesDir(), BACKUP_DIR_NAME);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /**
     * 把当前数据库文件复制为一个新的备份文件。
     *
     * @param context 任意 Context
     * @param prefix  文件名前缀（自动 / 手动）
     * @return 备份文件
     * @throws Exception 复制失败
     */
    private static File createBackupFile(Context context, String prefix) throws Exception {
        File backupDir = getBackupDir(context);
        File backupFile = new File(backupDir,
                prefix + FILE_TIME_FORMAT.format(new Date()) + ".db");
        boolean success = DatabaseHelper.getDatabaseHelper()
                .backupDatabase(backupFile.getAbsolutePath());
        if (!success) {
            throw new Exception("数据库复制失败");
        }
        return backupFile;
    }

    /**
     * 清理指定前缀的历史备份，只保留最新的 {@link #MAX_KEEP} 份。
     *
     * @param backupDir 备份目录
     * @param prefix    文件名前缀
     */
    private static void cleanupOldBackups(File backupDir, String prefix) {
        File[] files = backupDir.listFiles((dir, name) ->
                name.startsWith(prefix) && name.endsWith(".db"));
        if (files == null || files.length <= MAX_KEEP) {
            return;
        }
        List<File> list = new ArrayList<>(Arrays.asList(files));
        list.sort((f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));
        for (int i = MAX_KEEP; i < list.size(); i++) {
            if (!list.get(i).delete()) {
                Log.w(TAG, "清理旧备份失败: " + list.get(i).getName());
            }
        }
    }

    // ==================== 导出 ====================

    /**
     * 生成导出文件的建议文件名（供系统“另存为”选择器预填）。
     *
     * @return 形如 menstruation_export_20260101_120000.json 的文件名
     */
    public static String createExportFileName() {
        return "menstruation_export_" + FILE_TIME_FORMAT.format(new Date()) + ".json";
    }

    /**
     * 把业务数据序列化为 JSON（周期、每日记录、提醒三张表）。
     *
     * @param context 任意 Context（用于读取版本号）
     * @return 组装好的 JSON 对象
     * @throws Exception 查询或序列化失败
     */
    private static JSONObject buildExportData(Context context) throws Exception {
        DatabaseHelper dbHelper = DatabaseHelper.getDatabaseHelper();

        JSONObject exportData = new JSONObject();
        exportData.put("export_time", System.currentTimeMillis());
        exportData.put("app_version", context.getPackageManager()
                .getPackageInfo(context.getPackageName(), 0).versionName);

        exportData.put("menstrual_cycle_records", exportCycles(dbHelper));
        exportData.put("menstrual_records", exportRecords(dbHelper));
        exportData.put("reminders", exportReminders(dbHelper));
        return exportData;
    }

    /**
     * 导出业务数据到用户通过系统文件选择器指定的位置。
     *
     * @param context 任意 Context
     * @param uri     “另存为”选择器返回的目标 Uri
     * @throws Exception 无法写入所选文件或序列化失败
     */
    public static void exportToUri(Context context, Uri uri) throws Exception {
        JSONObject exportData = buildExportData(context);

        try (OutputStream os = context.getContentResolver().openOutputStream(uri)) {
            if (os == null) {
                throw new Exception("无法写入所选文件");
            }
            // 缩进 2 个空格，便于人工查看导出结果
            os.write(exportData.toString(2).getBytes(StandardCharsets.UTF_8));
        }

        Log.i(TAG, "数据导出成功: " + uri);
    }

    /** 导出月经周期表：按列名读取，避免依赖建表列顺序 */
    private static JSONArray exportCycles(DatabaseHelper dbHelper) throws JSONException {
        JSONArray array = new JSONArray();
        try (Cursor cursor = dbHelper.querySQL(
                "SELECT * FROM menstrual_cycle_record ORDER BY id ASC", null)) {
            if (cursor == null) return array;
            while (cursor.moveToNext()) {
                JSONObject item = new JSONObject();
                item.put("id", cursor.getInt(cursor.getColumnIndexOrThrow("id")));
                item.put("start_time", getString(cursor, "start_time"));
                item.put("total_cycle_day", getInt(cursor, "total_cycle_day"));
                item.put("menstrual_period_day", getInt(cursor, "menstrual_period_day"));
                item.put("created_time", getString(cursor, "created_time"));
                array.put(item);
            }
        }
        return array;
    }

    /** 导出每日记录表 */
    private static JSONArray exportRecords(DatabaseHelper dbHelper) throws JSONException {
        JSONArray array = new JSONArray();
        try (Cursor cursor = dbHelper.querySQL(
                "SELECT * FROM menstrual_record ORDER BY id ASC", null)) {
            if (cursor == null) return array;
            while (cursor.moveToNext()) {
                JSONObject item = new JSONObject();
                item.put("id", getString(cursor, "id"));
                item.put("menstrual_cycle", getString(cursor, "menstrual_cycle"));
                item.put("is_prediction", getInt(cursor, "is_prediction"));
                item.put("suggestion", getString(cursor, "suggestion"));
                item.put("flow_level", getInt(cursor, "flow_level"));
                item.put("flow_score", getInt(cursor, "flow_score"));
                item.put("pain_level", getInt(cursor, "pain_level"));
                item.put("pad_count", getInt(cursor, "pad_count"));
                item.put("pad_light_count", getInt(cursor, "pad_light_count"));
                item.put("pad_medium_count", getInt(cursor, "pad_medium_count"));
                item.put("pad_heavy_count", getInt(cursor, "pad_heavy_count"));
                item.put("clot_small_count", getInt(cursor, "clot_small_count"));
                item.put("clot_large_count", getInt(cursor, "clot_large_count"));
                item.put("notes", getString(cursor, "notes"));
                item.put("created_time", getString(cursor, "created_time"));
                item.put("updated_time", getString(cursor, "updated_time"));
                array.put(item);
            }
        }
        return array;
    }

    /** 导出自定义提醒表 */
    private static JSONArray exportReminders(DatabaseHelper dbHelper) throws JSONException {
        JSONArray array = new JSONArray();
        try (Cursor cursor = dbHelper.querySQL(
                "SELECT * FROM reminder ORDER BY id ASC", null)) {
            if (cursor == null) return array;
            while (cursor.moveToNext()) {
                JSONObject item = new JSONObject();
                item.put("id", getInt(cursor, "id"));
                item.put("title", getString(cursor, "title"));
                item.put("message", getString(cursor, "message"));
                item.put("reminder_type", getString(cursor, "reminder_type"));
                item.put("target_day", getInt(cursor, "target_day"));
                item.put("alarm_time", getString(cursor, "alarm_time"));
                item.put("is_enabled", getInt(cursor, "is_enabled"));
                item.put("is_alarm", getInt(cursor, "is_alarm"));
                // 周期节点与重复规则：保证备份恢复后提醒行为与导出前一致
                item.put("cycle_node", getInt(cursor, "cycle_node"));
                item.put("repeat_type", getInt(cursor, "repeat_type"));
                item.put("repeat_config", getString(cursor, "repeat_config"));
                item.put("repeat_end_day", getInt(cursor, "repeat_end_day"));
                item.put("created_time", getString(cursor, "created_time"));
                array.put(item);
            }
        }
        return array;
    }

    // ==================== 导入 ====================

    /**
     * 从用户通过系统文件选择器选中的文件中恢复数据。
     * <p>
     * 三张业务表的清空与重新写入在同一个事务内执行：任何一条插入失败都会整体回滚，
     * 原有数据保持不变。
     *
     * @param context 任意 Context
     * @param uri     “打开文件”选择器返回的源 Uri
     * @throws Exception 无法读取所选文件、解析 JSON 或数据库写入失败
     */
    public static void importFromUri(Context context, Uri uri) throws Exception {
        String jsonContent;
        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            if (is == null) {
                throw new Exception("无法读取所选文件");
            }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) != -1) {
                bos.write(buffer, 0, read);
            }
            jsonContent = bos.toString("UTF-8");
        }

        JSONObject importData = new JSONObject(jsonContent);
        // 先校验备份结构再清库：传入任意 JSON（如 "{}"）时若不校验，
        // DELETE 会成功、后续插入因缺少数组而跳过，最终把所有数据清空且无法回滚
        if (!importData.has("menstrual_records") || !importData.has("menstrual_cycle_records")) {
            throw new Exception("文件内容不是有效的备份数据");
        }
        if (!importData.isNull("menstrual_records")
                && !(importData.opt("menstrual_records") instanceof JSONArray)) {
            throw new Exception("备份数据格式错误：menstrual_records 不是数组");
        }
        SQLiteDatabase db = DatabaseHelper.getDatabaseHelper().getWritableDatabase();

        db.beginTransaction();
        try {
            db.execSQL("DELETE FROM menstrual_record");
            db.execSQL("DELETE FROM menstrual_cycle_record");
            db.execSQL("DELETE FROM reminder");

            insertCycles(db, importData.optJSONArray("menstrual_cycle_records"));
            insertRecords(db, importData.optJSONArray("menstrual_records"));
            insertReminders(db, importData.optJSONArray("reminders"));

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        Log.i(TAG, "数据导入成功: " + uri);
    }

    /** 写入月经周期表；旧版本导出文件缺少字段时使用默认值 */
    private static void insertCycles(SQLiteDatabase db, JSONArray array) throws JSONException {
        if (array == null) return;
        String sql = "INSERT INTO menstrual_cycle_record "
                + "(start_time, total_cycle_day, menstrual_period_day, created_time) "
                + "VALUES (?, ?, ?, ?)";
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i);
            db.execSQL(sql, new Object[]{
                    item.getString("start_time"),
                    item.optInt("total_cycle_day", 28),
                    item.optInt("menstrual_period_day", 5),
                    item.optString("created_time", null)
            });
        }
    }

    /** 写入每日记录表 */
    private static void insertRecords(SQLiteDatabase db, JSONArray array) throws JSONException {
        if (array == null) return;
        String sql = "INSERT INTO menstrual_record (id, menstrual_cycle, is_prediction, suggestion, "
                + "flow_level, flow_score, pain_level, pad_count, pad_light_count, pad_medium_count, "
                + "pad_heavy_count, clot_small_count, clot_large_count, notes, created_time, updated_time) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i);
            db.execSQL(sql, new Object[]{
                    item.getString("id"),
                    item.getString("menstrual_cycle"),
                    item.optInt("is_prediction", 0),
                    item.optString("suggestion", null),
                    item.optInt("flow_level", 0),
                    item.optInt("flow_score", 0),
                    item.optInt("pain_level", 0),
                    item.optInt("pad_count", 0),
                    item.optInt("pad_light_count", 0),
                    item.optInt("pad_medium_count", 0),
                    item.optInt("pad_heavy_count", 0),
                    item.optInt("clot_small_count", 0),
                    item.optInt("clot_large_count", 0),
                    item.optString("notes", null),
                    item.optString("created_time", null),
                    item.optString("updated_time", null)
            });
        }
    }

    /** 写入提醒表 */
    private static void insertReminders(SQLiteDatabase db, JSONArray array) throws JSONException {
        if (array == null) return;
        String sql = "INSERT INTO reminder (title, message, reminder_type, target_day, alarm_time, "
                + "is_enabled, is_alarm, cycle_node, repeat_type, repeat_config, repeat_end_day, "
                + "created_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i);
            db.execSQL(sql, new Object[]{
                    item.getString("title"),
                    item.optString("message", null),
                    item.optString("reminder_type", "custom"),
                    item.optInt("target_day", 0),
                    item.optString("alarm_time", null),
                    item.optInt("is_enabled", 1),
                    item.optInt("is_alarm", 0),
                    // 旧备份没有这些字段时回退为“固定日期、不重复”
                    item.optInt("cycle_node", 0),
                    item.optInt("repeat_type", 0),
                    item.optString("repeat_config", null),
                    item.optInt("repeat_end_day", 0),
                    item.optString("created_time", null)
            });
        }
    }

    // ==================== 清空 ====================

    /**
     * 清空全部业务数据（清空前自动生成一份备份，避免误操作无法恢复）。
     *
     * @param context 任意 Context
     * @throws Exception 备份或删除失败
     */
    public static void clearAllData(Context context) throws Exception {
        // 先备份再清空，清空后用户仍可通过备份文件恢复
        createManualBackup(context);

        SQLiteDatabase db = DatabaseHelper.getDatabaseHelper().getWritableDatabase();
        db.beginTransaction();
        try {
            db.execSQL("DELETE FROM menstrual_record");
            db.execSQL("DELETE FROM menstrual_cycle_record");
            db.execSQL("DELETE FROM reminder");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        Log.i(TAG, "业务数据已清空");
    }

    // ==================== 游标读取辅助 ====================

    /** 按列名读取字符串，列不存在或值为 NULL 时返回 null */
    private static String getString(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        return index == -1 || cursor.isNull(index) ? null : cursor.getString(index);
    }

    /** 按列名读取整数，列不存在时返回 0 */
    private static int getInt(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        return index == -1 || cursor.isNull(index) ? 0 : cursor.getInt(index);
    }
}
