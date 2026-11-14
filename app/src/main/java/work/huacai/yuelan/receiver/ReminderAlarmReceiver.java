package work.huacai.yuelan.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;

import work.huacai.yuelan.activities.AlarmFullScreenActivity;
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
        // 强提醒响铃时长（分钟，0 = 一直响到用户关闭）
        int ringDuration = intent.getIntExtra(ReminderAlarmScheduler.EXTRA_RING_DURATION, 0);

        ReminderNotificationHelper.show(context, reminderId, title, message, fullScreen, ringDuration);

        // 亮屏使用中系统不会主动拉起 full-screen intent（只会显示横幅）。
        // 若用户已允许“显示在其他应用上层”，这里直接启动全屏闹钟页，保证亮屏也能强提醒。
        if (fullScreen) {
            PowerManager powerManager =
                    (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            boolean interactive = powerManager != null && powerManager.isInteractive();
            if (interactive && ReminderNotificationHelper.canDrawOverlays(context)) {
                Intent page = new Intent(context, AlarmFullScreenActivity.class);
                page.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                page.putExtra(AlarmFullScreenActivity.EXTRA_ID, reminderId);
                page.putExtra(AlarmFullScreenActivity.EXTRA_TITLE, title);
                page.putExtra(AlarmFullScreenActivity.EXTRA_MESSAGE, message);
                page.putExtra(AlarmFullScreenActivity.EXTRA_RING_MINUTES, ringDuration);
                try {
                    context.startActivity(page);
                } catch (Exception e) {
                    // 系统仍拦截后台启动时，保留通知横幅提醒
                    Log.w("ReminderAlarmReceiver", "start alarm page failed", e);
                }
            }
        }

        // 周期节点 / 阶段区间 / 重复规则提醒触发后重排下一次（读库较耗时，放子线程）
        // 阶段区间属于 isCycleMode()，每触发一天都会自动排到区间内的下一天
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
