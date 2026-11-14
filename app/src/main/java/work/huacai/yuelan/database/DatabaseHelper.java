package work.huacai.yuelan.database;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据库访问入口（SQLiteOpenHelper 单例）。
 * <p>
 * 库名、版本号与建表 SQL 都来自 res/xml/database_config.xml，由 {@link DatabaseConfigParser} 解析：
 * <ul>
 *   <li>数据库文件首次创建时按配置执行建表语句（{@link #onCreate}）；</li>
 *   <li>版本号变化时先备份旧表、删除再重建（{@link #onUpgrade} / {@link #onDowngrade}）；</li>
 *   <li>对上层（DAO 层）提供通用的增删改查、SQL 执行以及整库备份/恢复能力。</li>
 * </ul>
 */
public class DatabaseHelper extends SQLiteOpenHelper {
    private static final String TAG = "DatabaseHelper";

    /** 单例引用。volatile 保证可见性，与 getInstance 上的 synchronized 配合实现线程安全的懒加载 */
    private static volatile DatabaseHelper databaseHelper;

    /** 保存 ApplicationContext，避免持有 Activity 导致内存泄漏 */
    private Context context;

    /** 解析得到的库配置（库名、版本、建表 SQL）。解析失败时为 null，此时使用默认库名 */
    private DatabaseConfigParser.DatabaseConfig databaseConfig;

    private DatabaseHelper(Context context, String dbName, int dbVersion) {
        super(context, dbName, null, dbVersion);
        this.context = context.getApplicationContext();
    }

    /**
     * 获取数据库单例，首次调用时解析 XML 配置并创建实例。
     * <p>
     * 若 XML 解析失败（文件损坏、格式错误等），会退化为默认配置（menstruation.db，版本 1），
     * 保证应用不会因配置问题而无法启动。
     *
     * @param context  任意 Context，内部会转换并保存 ApplicationContext
     * @param xmlResId 数据库配置 XML 的资源 id（如 R.xml.database_config）；仅首次创建实例时使用
     * @return 全局唯一的 DatabaseHelper 实例
     */
    public static synchronized DatabaseHelper getInstance(Context context, int xmlResId) {
        if (databaseHelper == null) {
            // 解析XML配置
            try {
                DatabaseConfigParser.setDebugMode(true);
                DatabaseConfigParser.DatabaseConfig config =
                        DatabaseConfigParser.parseFromResource(context, xmlResId);

                databaseHelper = new DatabaseHelper(context, config.name, config.version);
                databaseHelper.databaseConfig = config;

                Log.i(TAG, "数据库配置加载成功: name:" + config.name + ",version:" + config.version + "表数量:" + config.tables.size());

            } catch (Exception e) {
                Log.e(TAG, "解析数据库配置失败", e);
                // 使用默认配置
                databaseHelper = new DatabaseHelper(context, "menstruation.db", 1);
            }
        }
        return databaseHelper;
    }

    /**
     * 获取已初始化的 DatabaseHelper 实例。
     * <p>
     * 供 DAO 层使用：DAO 在构造时即依赖此方法，因此必须先由 Application 调用 {@link #getInstance}。
     *
     * @return 已初始化的单例
     * @throws IllegalStateException 尚未调用 getInstance() 时抛出
     */
    public static DatabaseHelper getDatabaseHelper() {
        if (databaseHelper == null) {
            throw new IllegalStateException("DatabaseHelper未初始化，请先调用getInstance()");
        }
        return databaseHelper;
    }

    /**
     * 数据库首次创建时的回调：逐条执行配置中的建表 SQL。
     * <p>
     * 单张表建表失败只记录日志并继续，避免一条错误 SQL 阻断其余表的创建。
     *
     * @param db 可写的数据库对象
     */
    @Override
    public void onCreate(SQLiteDatabase db) {
        if (databaseConfig != null && databaseConfig.tables != null) {
            for (DatabaseConfigParser.TableConfig table : databaseConfig.tables) {
                try {
                    db.execSQL(table.sql);
                    Log.d(TAG, "创建表"+ table.name + "成功,SQL:" + table.sql);
                } catch (Exception e) {
                    Log.e(TAG, "创建表失败:" + table.name, e);
                }
            }
        } else {
            Log.w(TAG, "数据库配置为空，无法创建表");
        }
    }

    /**
     * 数据库版本升高时的回调。
     * <p>
     * 所有历史版本升级都按版本差逐级补齐：仅执行 ADD COLUMN / CREATE TABLE，
     * 不删除、不重建已有表，确保存量用户数据完整保留。
     *
     * @param db         可写的数据库对象
     * @param oldVersion 升级前版本号
     * @param newVersion 升级后版本号
     */
    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        Log.i(TAG, "升级数据库从版本 " + oldVersion + " 到 " + newVersion);

        // 逐版本补齐新增列/表，全部使用 ALTER / CREATE，避免跨版本升级时丢数据
        if (oldVersion < 3) {
            // 2 → 3：menstrual_record 追加流量/痛经备注列，SQLite 的 ADD COLUMN 不影响存量数据
            try {
                db.execSQL("ALTER TABLE menstrual_record ADD COLUMN flow_note TEXT");
                db.execSQL("ALTER TABLE menstrual_record ADD COLUMN pain_note TEXT");
                Log.i(TAG, "menstrual_record 增加 flow_note / pain_note 列成功");
            } catch (Exception e) {
                Log.e(TAG, "menstrual_record 增加备注列失败", e);
            }
        }

        if (oldVersion < 4) {
            // 3 → 4：新增 phase_anchor 表（阶段锚点），纯新增表、无存量数据迁移
            try {
                db.execSQL("CREATE TABLE IF NOT EXISTS phase_anchor (" +
                        "anchor_date TEXT PRIMARY KEY," +
                        "phase_index INTEGER NOT NULL," +
                        "created_time TEXT DEFAULT (datetime('now', 'localtime'))" +
                        ")");
                Log.i(TAG, "phase_anchor 表创建成功");
            } catch (Exception e) {
                Log.e(TAG, "phase_anchor 表创建失败", e);
            }
        }

        if (oldVersion < 5) {
            // 4 → 5：reminder 追加 cycle_node 列（0=固定日期，>0=按周期节点提醒），
            // ALTER 不影响存量提醒，旧数据默认固定日期模式
            try {
                db.execSQL("ALTER TABLE reminder ADD COLUMN cycle_node INTEGER NOT NULL DEFAULT 0");
                Log.i(TAG, "reminder 增加 cycle_node 列成功");
            } catch (Exception e) {
                Log.e(TAG, "reminder 增加 cycle_node 列失败", e);
            }
        }

        if (oldVersion < 6) {
            // 5 → 6：reminder 追加重复规则列，旧提醒默认不重复
            try {
                db.execSQL("ALTER TABLE reminder ADD COLUMN repeat_type INTEGER NOT NULL DEFAULT 0");
                db.execSQL("ALTER TABLE reminder ADD COLUMN repeat_config TEXT");
                db.execSQL("ALTER TABLE reminder ADD COLUMN repeat_end_day INTEGER NOT NULL DEFAULT 0");
                Log.i(TAG, "reminder 增加重复规则列成功");
            } catch (Exception e) {
                Log.e(TAG, "reminder 增加重复规则列失败", e);
            }
        }

        if (oldVersion < 7) {
            // 6 → 7：重复规则新增“指定多个日期”（repeat_type = 5），
            // 日期复用 repeat_config 存储（逗号分隔的 yyyyMMdd），无需新增列。
            // 这里只做一次数据自检：显式日期规则不使用结束日期，统一归零。
            try {
                db.execSQL("UPDATE reminder SET repeat_end_day = 0 WHERE repeat_type = 5");
                Log.i(TAG, "reminder 支持指定多个日期规则（无表结构变更）");
            } catch (Exception e) {
                Log.e(TAG, "reminder 指定多个日期规则迁移失败", e);
            }
        }
    }

    /**
     * 数据库版本降低时的回调。
     * <p>
     * 降级场景很少见，这里直接复用升级逻辑（备份 + 重建）。
     *
     * @param db         可写的数据库对象
     * @param oldVersion 降级前版本号
     * @param newVersion 降级后版本号
     */
    @Override
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        Log.w(TAG, "数据库降级从版本 " + oldVersion + " 到 " + newVersion);
        // todo 这里还需要继续优化
        onUpgrade(db, oldVersion, newVersion);
    }

    /**
     * 执行非查询 SQL（INSERT/UPDATE/DELETE/DDL 等）。
     * <p>
     * 异常会被吞掉并记录日志，调用方无法感知失败（与既有 DAO 的错误处理约定保持一致）。
     *
     * @param sql      要执行的 SQL
     * @param bindArgs 绑定的参数，可为 null
     */
    public void executeSQL(String sql, Object[] bindArgs) {
        SQLiteDatabase db = getWritableDatabase();
        try {
            if (bindArgs != null && bindArgs.length > 0) {
                db.execSQL(sql, bindArgs);
            } else {
                db.execSQL(sql);
            }
        } catch (Exception e) {
            Log.e(TAG, "执行SQL失败: " + sql, e);
        }
    }

    /**
     * 执行查询 SQL。
     * <p>
     * 返回的 Cursor 由调用方负责关闭；查询失败时返回 null。
     *
     * @param sql           查询语句
     * @param selectionArgs 查询参数，可为 null
     * @return 查询结果游标，失败时返回 null
     */
    public Cursor querySQL(String sql, String[] selectionArgs) {
        SQLiteDatabase db = getReadableDatabase();
        try {
            return db.rawQuery(sql, selectionArgs);
        } catch (Exception e) {
            Log.e(TAG, "查询SQL失败: " + sql, e);
            return null;
        }
    }

    /**
     * 向指定表插入一行数据。
     *
     * @param tableName 表名
     * @param values    待插入的列值
     * @return 新行的 rowId；失败返回 -1
     */
    public long insert(String tableName, ContentValues values) {
        SQLiteDatabase db = getWritableDatabase();
        try {
            return db.insert(tableName, null, values);
        } catch (Exception e) {
            Log.e(TAG, "插入数据失败: " + tableName, e);
            return -1;
        }
    }

    /**
     * 更新指定表中满足条件的行。
     *
     * @param tableName   表名
     * @param values      需要更新的列值
     * @param whereClause WHERE 条件（如 "id = ?"），可为 null 表示更新全表
     * @param whereArgs   WHERE 条件中 ? 对应的参数
     * @return 受影响的行数；失败返回 0
     */
    public int update(String tableName, ContentValues values, String whereClause, String[] whereArgs) {
        SQLiteDatabase db = getWritableDatabase();
        try {
            return db.update(tableName, values, whereClause, whereArgs);
        } catch (Exception e) {
            Log.e(TAG, "更新数据失败: " + tableName, e);
            return 0;
        }
    }

    /**
     * 删除指定表中满足条件的行。
     *
     * @param tableName   表名
     * @param whereClause WHERE 条件，可为 null 表示清空整表
     * @param whereArgs   WHERE 条件中 ? 对应的参数
     * @return 受影响的行数；失败返回 0
     */
    public int delete(String tableName, String whereClause, String[] whereArgs) {
        SQLiteDatabase db = getWritableDatabase();
        try {
            return db.delete(tableName, whereClause, whereArgs);
        } catch (Exception e) {
            Log.e(TAG, "删除数据失败: " + tableName, e);
            return 0;
        }
    }

    /**
     * 把数据库文件整体复制到指定路径。
     * <p>
     * 副作用：复制前会关闭当前数据库连接（确保文件内容是完整的），后续访问会自动重新打开。
     *
     * @param backupPath 备份文件的目标绝对路径
     * @return 复制成功返回 true；源库文件不存在或 IO 失败返回 false
     */
    public boolean backupDatabase(String backupPath) {
        SQLiteDatabase db = getReadableDatabase();
        // 先关闭连接，避免复制到尚未落盘的脏数据
        db.close();

        File currentDB = context.getDatabasePath(getDatabaseName());
        File backupDB = new File(backupPath);

        try {
            if (currentDB.exists()) {
                copyFile(currentDB, backupDB);
                Log.i(TAG, "数据库备份成功: " + backupPath);
                return true;
            } else {
                Log.e(TAG, "原数据库文件不存在");
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "数据库备份失败", e);
            return false;
        }
    }

    /**
     * 用备份文件覆盖当前数据库文件。
     * <p>
     * 副作用：先关闭连接并删除现有数据库文件；无论成功与否，finally 中都会重新打开数据库，
     * 使后续调用返回一个新的连接（失败时相当于重新创建一个空库）。
     *
     * @param backupPath 备份文件的绝对路径
     * @return 恢复成功返回 true；删除旧文件失败或复制失败返回 false
     */
    public boolean restoreDatabase(String backupPath) {
        close();

        File backupDB = new File(backupPath);
        File currentDB = context.getDatabasePath(getDatabaseName());

        if (currentDB.exists() && !currentDB.delete()) {
            Log.e(TAG, "无法删除当前数据库文件");
            return false;
        }

        try {
            copyFile(backupDB, currentDB);
            Log.i(TAG, "数据库恢复成功: " + backupPath);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "数据库恢复失败", e);
            return false;
        } finally {
            // 重新建立连接，保证后续读写可用
            getWritableDatabase();
        }
    }

    /**
     * 复制单个文件（覆盖目标文件）。
     *
     * @param source 源文件
     * @param dest   目标文件
     * @throws IOException 读写失败时抛出；流由 try-with-resources 自动关闭
     */
    private void copyFile(File source, File dest) throws IOException {
        try (FileInputStream fis = new FileInputStream(source);
             FileOutputStream fos = new FileOutputStream(dest)) {

            FileChannel sourceChannel = fis.getChannel();
            FileChannel destChannel = fos.getChannel();
            destChannel.transferFrom(sourceChannel, 0, sourceChannel.size());
        }
    }

    /**
     * 获取当前数据库文件的绝对路径。
     *
     * @return 数据库文件路径字符串
     */
    public String getDatabasePath() {
        return context.getDatabasePath(getDatabaseName()).getAbsolutePath();
    }

    /**
     * 获取当前数据库文件的大小。
     *
     * @return 文件字节数；文件不存在时返回 0
     */
    public long getDatabaseSize() {
        File dbFile = context.getDatabasePath(getDatabaseName());
        return dbFile.exists() ? dbFile.length() : 0;
    }

    /**
     * 判断指定表是否存在。
     *
     * @param tableName 表名
     * @return 存在返回 true；不存在或查询失败返回 false
     */
    public boolean tableExists(String tableName) {
        SQLiteDatabase db = getReadableDatabase();

        try (Cursor cursor = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                new String[]{tableName})) {

            return cursor != null && cursor.getCount() > 0;
        } catch (Exception e) {
            Log.e(TAG, "检查表是否存在失败: " + tableName, e);
            return false;
        }
    }

    /**
     * 获取数据库中所有业务表名（排除 sqlite_ 开头的系统表）。
     *
     * @return 表名列表；查询失败时返回已收集到的空列表
     */
    public List<String> getAllTableNames() {
        List<String> tables = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();

        try (Cursor cursor = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'",
                null)) {

            while (cursor != null && cursor.moveToNext()) {
                tables.add(cursor.getString(0));
            }
        } catch (Exception e) {
            Log.e(TAG, "获取表名失败", e);
        }

        return tables;
    }

    /**
     * 从建表 SQL 中提取表名，供升级时构造备份表名使用。
     * <p>
     * 仅支持 "CREATE TABLE xxx (...)" 形式：按空白切分后取第 3 段并去掉引号/括号。
     * 注意返回值统一转为大写（SQL 标识符大小写不敏感，不影响 SQLite 行为）；
     * 若语句是 "CREATE TABLE IF NOT EXISTS xxx" 这种写法，本方法会错误地返回 "IF"。
     *
     * @param sql 建表语句
     * @return 表名；无法识别时返回空字符串
     */
    private String extractTableName(String sql) {
        if (sql == null) return "";
        sql = sql.trim().toUpperCase();
        if (sql.startsWith("CREATE TABLE")) {
            String[] parts = sql.split("\\s+");
            if (parts.length > 2) {
                return parts[2].replace("(", "").replace("`", "").replace("\"", "");
            }
        }
        return "";
    }
}
