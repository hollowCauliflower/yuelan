package work.huacai.yuelan.database;

import android.content.Context;
import android.content.res.XmlResourceParser;
import android.util.Log;
import androidx.annotation.NonNull;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据库配置解析器。
 * <p>
 * 负责把 res/xml 下的数据库配置（库名、版本号、各表的建表 SQL）解析成 {@link DatabaseConfig}，
 * 供 {@link DatabaseHelper} 建表与升级时使用。配置文件结构示例：
 * <pre>
 * &lt;database&gt;
 *   &lt;name&gt;xxx.db&lt;/name&gt;
 *   &lt;version&gt;1&lt;/version&gt;
 *   &lt;tables&gt;
 *     &lt;table&gt;&lt;name&gt;t&lt;/name&gt;&lt;sql&gt;&lt;![CDATA[CREATE TABLE ...]]&gt;&lt;/sql&gt;&lt;/table&gt;
 *   &lt;/tables&gt;
 * &lt;/database&gt;
 * </pre>
 * 解析使用 Pull 方式逐标签处理，SQL 一般位于 CDATA 中，因此文本和 CDATA 都会追加到同一个缓冲区。
 */
public class DatabaseConfigParser {

    private static final String TAG = "DatabaseConfigParser";

    /** 是否输出解析过程的调试日志，由调用方通过 {@link #setDebugMode(boolean)} 控制 */
    private static boolean DEBUG_MODE = false;

    /**
     * 单张表的配置。
     */
    public static class TableConfig {
        /** 表名 */
        public String name;
        /** 建表 SQL（已去除首尾空白） */
        public String sql;

        @NonNull
        @Override
        public String toString() {
            return "Table{name=" + name + ",sql=" + sql + "}";
        }
    }

    /**
     * 整个数据库的配置。
     */
    public static class DatabaseConfig {
        /** 数据库文件名，如 menstruation.db */
        public String name;
        /** 数据库版本号，默认 1 */
        public int version = 1;
        /** 该库包含的所有表配置 */
        public List<TableConfig> tables = new ArrayList<>();

        @NonNull
        @Override
        public String toString() {
            return "Database{name='" + name + "', version=" + version + ", tables=" + tables + "}";
        }
    }

    /**
     * 打开/关闭解析过程的调试日志。
     *
     * @param debug true 表示打印解析细节
     */
    public static void setDebugMode(boolean debug) {
        DEBUG_MODE = debug;
    }

    /**
     * 仅在调试模式下输出日志，避免发布版本刷屏。
     *
     * @param message 日志内容
     */
    private static void logDebug(String message) {
        if (DEBUG_MODE) {
            Log.d(TAG, message);
        }
    }

    /**
     * 从 XML 资源文件解析数据库配置。
     * <p>
     * 解析完成后会补全缺失的默认值（库名、版本号、表名），并打印解析结果。
     *
     * @param context  用于获取 Resources
     * @param xmlResId 配置 XML 的资源 id
     * @return 解析后的数据库配置，不会返回 null
     * @throws XmlPullParserException XML 格式错误时抛出
     * @throws IOException            读取资源失败时抛出
     */
    public static DatabaseConfig parseFromResource(Context context, int xmlResId)
            throws XmlPullParserException, IOException {

        DatabaseConfig config = new DatabaseConfig();
        TableConfig currentTable = null;
        StringBuilder sqlBuilder = null;

        // 跟踪当前标签：TEXT 事件本身不带标签名，需要靠它判断文本属于 name 还是 version
        String currentTag = null;

        logDebug("开始解析XML资源，ID: " + xmlResId);

        XmlResourceParser parser = context.getResources().getXml(xmlResId);

        int eventType = parser.getEventType();

        while (eventType != XmlPullParser.END_DOCUMENT) {
            String tagName = parser.getName();

            switch (eventType) {
                case XmlPullParser.START_TAG:
                    currentTag = tagName;
                    if ("table".equals(tagName)) {
                        currentTable = new TableConfig();
                        logDebug("开始解析新表");
                    } else if ("sql".equals(tagName)) {
                        sqlBuilder = new StringBuilder();
                        logDebug("开始解析SQL语句");
                    } else if ("name".equals(tagName)) {
                        logDebug("开始解析名称");
                    } else if ("version".equals(tagName)) {
                        logDebug("开始解析版本号");
                    }
                    break;

                case XmlPullParser.TEXT:
                    String text = parser.getText();
                    if (text != null && !text.trim().isEmpty()) {
                        text = text.trim();
                        logDebug("读取文本: [" + text + "] 当前标签: " + currentTag);

                        if (sqlBuilder != null) {
                            // 处于 sql 标签内：文本片段依次拼接成完整 SQL
                            sqlBuilder.append(text);
                        } else if (currentTable == null) {
                            // 还没有进入 table 标签，属于数据库级别的配置
                            if ("name".equals(currentTag)) {
                                config.name = text;
                                logDebug("设置数据库名称: " + text);
                            } else if ("version".equals(currentTag)) {
                                try {
                                    config.version = Integer.parseInt(text);
                                    logDebug("设置数据库版本: " + text);
                                } catch (NumberFormatException e) {
                                    // 版本号非法时回退到 1，避免整份配置解析失败
                                    Log.w(TAG, "版本号解析失败: " + text);
                                    config.version = 1;
                                }
                            }
                        } else {
                            // 已进入 table 标签，属于表级别的配置
                            if ("name".equals(currentTag)) {
                                currentTable.name = text;
                                logDebug("设置表名: " + text);
                            }
                        }
                    }
                    break;

                case XmlPullParser.CDSECT:
                    // 建表 SQL 通常放在 CDATA 中，同样追加到 sql 缓冲区
                    String cdataText = parser.getText();
                    if (cdataText != null && sqlBuilder != null) {
                        sqlBuilder.append(cdataText);
                        logDebug("读取到CDATA内容，长度: " + cdataText.length());
                    }
                    break;

                case XmlPullParser.END_TAG:
                    if ("sql".equals(tagName)) {
                        if (currentTable != null && sqlBuilder != null) {
                            currentTable.sql = sqlBuilder.toString().trim();
                            logDebug("SQL语句解析完成，长度: " + currentTable.sql.length());
                        }
                        sqlBuilder = null;
                    } else if ("table".equals(tagName)) {
                        if (currentTable != null) {
                            config.tables.add(currentTable);
                            logDebug("表解析完成: " + (currentTable.name != null ? currentTable.name : "未命名"));
                        }
                        currentTable = null;
                    }
                    currentTag = null;
                    break;
            }

            eventType = parser.next();
        }

        parser.close();

        // 验证配置
        validateConfig(config);

        // 打印结果
        printParsingResult(config);

        return config;
    }

    /**
     * 校验并补全配置：库名、版本号、表名缺失或非法时给出警告并使用默认值。
     *
     * @param config 待校验的配置（会被就地修改）
     */
    private static void validateConfig(DatabaseConfig config) {
        if (config.name == null || config.name.trim().isEmpty()) {
            Log.w(TAG, "警告: 数据库名称未设置");
            config.name = "app_database.db";
        }

        if (config.version < 1) {
            Log.w(TAG, "警告: 数据库版本号无效(" + config.version + ")");
            config.version = 1;
        }

        for (int i = 0; i < config.tables.size(); i++) {
            TableConfig table = config.tables.get(i);
            if (table.name == null || table.name.trim().isEmpty()) {
                Log.w(TAG, "警告: 第" + (i + 1) + "个表的名称未设置");
                table.name = "table_" + (i + 1);
            }

            // SQL 为空只告警不中断：建表失败会在 DatabaseHelper.onCreate 中单独记录
            if (table.sql == null || table.sql.trim().isEmpty()) {
                Log.w(TAG, "警告: 表 '" + table.name + "' 的SQL语句为空");
            }
        }
    }

    /**
     * 打印解析结果，SQL 只展示前 5 行，避免日志过长。
     *
     * @param config 已解析完成的配置
     */
    private static void printParsingResult(DatabaseConfig config) {
        Log.i(TAG, "========== 解析结果 ==========");
        Log.i(TAG, "数据库名称: " + config.name);
        Log.i(TAG, "数据库版本: " + config.version);
        Log.i(TAG, "表数量: " + config.tables.size());

        for (int i = 0; i < config.tables.size(); i++) {
            TableConfig table = config.tables.get(i);
            Log.i(TAG, "\n--- 表 " + (i + 1) + " ---");
            Log.i(TAG, "表名: " + table.name);
            Log.i(TAG, "SQL语句长度: " + (table.sql != null ? table.sql.length() : 0));

            if (table.sql != null && !table.sql.isEmpty()) {
                // 显示SQL前几行
                String[] lines = table.sql.split("\n");
                int maxLines = Math.min(5, lines.length);
                for (int j = 0; j < maxLines; j++) {
                    Log.i(TAG, "  " + lines[j].trim());
                }
                if (lines.length > 5) {
                    Log.i(TAG, "  ... 还有" + (lines.length - 5) + "行");
                }
            }
        }
        Log.i(TAG, "==============================");
    }
}
