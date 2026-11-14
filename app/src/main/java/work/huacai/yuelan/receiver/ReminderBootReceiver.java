package work.huacai.yuelan.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import work.huacai.yuelan.helper.PeriodReminderScheduler;
import work.huacai.yuelan.helper.ReminderAlarmScheduler;

/**
 * 系统事件接收器：开机、应用更新后重建全部提醒闹钟。
 * <p>
 * AlarmManager 中的闹钟在设备重启与应用被覆盖安装后会被系统清除，
 * 若不重建，用户设置的提醒将永远不会触发。
 */
public class ReminderBootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }

        Context appContext = context.getApplicationContext();
        // 数据库读取放到子线程，goAsync 保证广播在重建完成前不被回收
        final PendingResult pendingResult = goAsync();
        new Thread(() -> {
            try {
                ReminderAlarmScheduler.rescheduleAll(appContext);
                // 经期提醒的闹钟也会在重启后被清除，这里一并重建
                PeriodReminderScheduler.apply(appContext);
            } finally {
                pendingResult.finish();
            }
        }).start();
    }
}
