package work.huacai.yuelan.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import work.huacai.yuelan.helper.PeriodReminderScheduler;

/**
 * 经期提醒闹钟触发接收器。
 * <p>
 * 到点后由 {@link PeriodReminderScheduler#onTriggered(Context)} 在后台线程
 * 发出“经期即将来潮”的高优先级通知，并安排下一个周期的提醒：
 * 通知文案要按实际剩余天数计算、下一次排程要读数据库，都不适合放在广播主线程。
 */
public class PeriodReminderReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null
                || !PeriodReminderScheduler.ACTION_PERIOD_REMINDER.equals(intent.getAction())) {
            return;
        }

        PeriodReminderScheduler.onTriggered(context);
    }
}
