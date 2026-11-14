package work.huacai.yuelan;

import android.app.Application;

import work.huacai.yuelan.database.DatabaseBackupHelper;
import work.huacai.yuelan.database.DatabaseHelper;
import work.huacai.yuelan.helper.PeriodReminderScheduler;
import work.huacai.yuelan.helper.ReminderAlarmScheduler;
import work.huacai.yuelan.helper.ReminderNotificationHelper;
import work.huacai.yuelan.helper.ThemeHelper;

/**
 * 应用全局 Application。
 * <p>
 * 进程启动时初始化数据库单例，并在后台线程按需执行一次自动备份。
 * 备份逻辑统一交给 {@link DatabaseBackupHelper}，此处只负责调度，不直接操作文件。
 */
public class YuelanApplication extends Application {

    /**
     * 应用启动入口：初始化数据库，并按需触发自动备份。
     */
    @Override
    public void onCreate() {
        super.onCreate();

        // 先恢复主题模式（浅色/深色/跟随系统），保证首个 Activity 就按用户偏好渲染
        ThemeHelper.applySavedMode(this);

        // 初始化数据库单例（此处调用会让数据库完成建表/升级）
        DatabaseHelper.getInstance(this, R.xml.database_config);

        // 提醒通知渠道必须在发通知前创建；渠道创建是幂等的
        ReminderNotificationHelper.ensureChannel(this);
        // 强提醒渠道提前建好：顺手删除旧的静音渠道，保证升级后第一次提醒就是响亮闹钟
        ReminderNotificationHelper.ensureAlarmChannel(this);

        // 进程启动时重建一次提醒闹钟，作为开机/更新广播之外的兜底（数据库读取较重，放子线程）
        new Thread(() -> {
            ReminderAlarmScheduler.rescheduleAll(this);
            // 经期提醒同样依赖 AlarmManager，进程启动时一并重建
            PeriodReminderScheduler.apply(this);
        }).start();

        // 每天最多备份一次；文件 IO 放到子线程，避免阻塞主线程。
        // 注意：后台线程不能再弹 Toast（无 Looper 会直接崩溃），失败只记录日志。
        if (DatabaseBackupHelper.shouldAutoBackup(this)) {
            new Thread(() -> DatabaseBackupHelper.performAutoBackupIfNeeded(this)).start();
        }
    }
}
