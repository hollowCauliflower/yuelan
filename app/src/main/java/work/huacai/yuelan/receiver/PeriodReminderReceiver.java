package work.huacai.yuelan.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import work.huacai.yuelan.helper.PeriodReminderScheduler;
import work.huacai.yuelan.helper.ReminderNotificationHelper;

/**
 * 经期提醒闹钟触发接收器。
 * <p>
 * 到点后发出“经期即将来潮”的高优先级通知，并立刻安排下一个周期的提醒。
 * 因为 AlarmManager 中的闹钟是一次性的，不重排的话只会提醒一次。
 */
public class PeriodReminderReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null
                || !PeriodReminderScheduler.ACTION_PERIOD_REMINDER.equals(intent.getAction())) {
            return;
        }

        // 通知文案使用用户当前设置的提前天数
        int daysAhead = PeriodReminderScheduler.getReminderDays(context);
        ReminderNotificationHelper.showPeriodReminder(context, daysAhead);

        // 安排下一次（下个月经周期）的提醒；需要读数据库，内部已放到子线程
        PeriodReminderScheduler.scheduleNextAfterFired(context);
    }
}
