package work.huacai.yuelan.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import work.huacai.yuelan.dao.impl.ReminderDaoImpl;
import work.huacai.yuelan.helper.ReminderAlarmScheduler;
import work.huacai.yuelan.helper.ReminderNotificationHelper;
import work.huacai.yuelan.model.Reminder;

/**
 * 提醒闹钟触发接收器：到点后发出高优先级、锁屏可见的通知。
 * <p>
 * 提醒内容随 Intent 一起传递，不依赖数据库查询，保证在进程被杀后拉起时也能正常显示。
 * <p>
 * 周期节点提醒是一次性闹钟（每次只排"下一节点"），触发后需要立即按最新周期数据
 * 重排下一次，保证每周期都能收到。
 */
public class ReminderAlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null
                || !ReminderAlarmScheduler.ACTION_REMINDER_ALARM.equals(intent.getAction())) {
            return;
        }

        final long reminderId = intent.getLongExtra(ReminderAlarmScheduler.EXTRA_ID, -1);
        String title = intent.getStringExtra(ReminderAlarmScheduler.EXTRA_TITLE);
        String message = intent.getStringExtra(ReminderAlarmScheduler.EXTRA_MESSAGE);
        // true = 强提醒：由通知助手附带全屏 intent，到点直接点亮屏幕进入闹钟页
        boolean fullScreen = intent.getBooleanExtra(ReminderAlarmScheduler.EXTRA_FULL_SCREEN, false);

        ReminderNotificationHelper.show(context, reminderId, title, message, fullScreen);

        // 周期节点 / 重复规则提醒触发后重排下一次（读库较耗时，放子线程）
        if (reminderId > 0) {
            final Context appContext = context.getApplicationContext();
            new Thread(() -> {
                Reminder reminder = new ReminderDaoImpl().selectById(reminderId);
                if (reminder != null && reminder.isEnabled()
                        && (reminder.isCycleMode() || reminder.isRepeatMode())) {
                    ReminderAlarmScheduler.schedule(appContext, reminder);
                }
            }).start();
        }
    }
}
