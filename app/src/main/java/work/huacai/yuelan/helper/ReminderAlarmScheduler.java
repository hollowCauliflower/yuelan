package work.huacai.yuelan.helper;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import java.util.Calendar;
import java.util.Date;
import java.util.List;

import work.huacai.yuelan.dao.impl.ReminderDaoImpl;
import work.huacai.yuelan.model.Reminder;
import work.huacai.yuelan.receiver.ReminderAlarmReceiver;

/**
 * 提醒事项的闹钟调度器。
 * <p>
 * 每条启用的提醒按「日期 + 时间」排一个闹钟，统一使用
 * {@link AlarmManager#setAlarmClock(AlarmManager.AlarmClockInfo, PendingIntent)}：
 * <ul>
 *   <li>它是系统认定的“用户可见闹钟”，会显示状态栏闹钟图标，并且不做批量对齐；</li>
 *   <li>普通精确闹钟在 Android 14+ 缺少「闹钟与提醒」权限时会被放进最长一小时的窗口，
 *       ColorOS 等 ROM 即使已授权也可能继续延后，实测只有 setAlarmClock 能准点；</li>
 *   <li>排程失败（极少数 ROM 限制）时退回
 *       {@link AlarmManager#setExactAndAllowWhileIdle}（无权限时再退
 *       {@link AlarmManager#setAndAllowWhileIdle}，系统可能延后送达）。</li>
 * </ul>
 * <p>
 * 闹钟在重启、应用更新后会被系统清除，需由 {@link work.huacai.yuelan.receiver.ReminderBootReceiver}
 * 与 {@link work.huacai.yuelan.YuelanApplication} 调用
 * {@link #rescheduleAll(Context)} 重建。
 */
public final class ReminderAlarmScheduler {

    private static final String TAG = "ReminderAlarmScheduler";

    /** 闹钟触发的广播 action */
    public static final String ACTION_REMINDER_ALARM =
            "work.huacai.yuelan.action.REMINDER_ALARM";

    /** 提醒 id */
    public static final String EXTRA_ID = "extra_reminder_id";
    /** 提醒标题 */
    public static final String EXTRA_TITLE = "extra_reminder_title";
    /** 提醒备注 */
    public static final String EXTRA_MESSAGE = "extra_reminder_message";
    /** 是否使用强提醒（全屏闹钟） */
    public static final String EXTRA_FULL_SCREEN = "extra_reminder_full_screen";
    /** 强提醒响铃时长（分钟，0 = 一直响） */
    public static final String EXTRA_RING_DURATION = "extra_reminder_ring_duration";

    private ReminderAlarmScheduler() {
    }

    /**
     * 为一条提醒安排闹钟；时间已过或未启用时不安排。
     *
     * @param context  任意 Context
     * @param reminder 提醒实体
     * @return 已安排返回 true；未启用、时间已过或数据非法返回 false
     */
    public static boolean schedule(Context context, Reminder reminder) {
        if (reminder == null || reminder.getId() <= 0) {
            return false;
        }
        if (!reminder.isEnabled()) {
            cancel(context, reminder.getId());
            return false;
        }

        long triggerAt = computeTriggerAt(reminder);
        if (triggerAt <= 0) {
            // 设定时间已经过去：取消可能存在的旧闹钟，避免误触发
            cancel(context, reminder.getId());
            return false;
        }

        AlarmManager alarmManager =
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return false;
        }

        PendingIntent pendingIntent = buildPendingIntent(context, reminder);
        // exact 只用于“setAlarmClock 不可用”时的降级选择；正常路径不依赖该权限
        boolean exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || alarmManager.canScheduleExactAlarms();
        Log.i(TAG, "schedule reminder=" + reminder.getId() + " exact=" + exact
                + " triggerAt=" + triggerAt);
        try {
            // 用“闹钟”方式排程：系统会把 setAlarmClock 当作面向用户的闹钟，
            // 不会像普通精确闹钟那样被 ROM（如 ColorOS）放进最长一小时的批量窗口里延后。
            // 这同样是唯一在“闹钟与提醒”权限缺失时仍能准点送达的方式。
            alarmManager.setAlarmClock(
                    new AlarmManager.AlarmClockInfo(triggerAt,
                            buildShowIntent(context, reminder)),
                    pendingIntent);
        } catch (SecurityException e) {
            Log.w(TAG, "setAlarmClock 不可用，退回普通闹钟", e);
            if (exact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
            } else {
                // 无「闹钟与提醒」权限时降级：仍会被唤醒，但系统可能合并/延后
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
            }
        }
        return true;
    }

    /**
     * 取消一条提醒对应的闹钟与通知。
     * <p>setAlarmClock 与普通闹钟共用同一 PendingIntent，cancel 会一并清除；
     * 通知也在这里撤掉，避免用户关掉提醒后仍看到历史通知。
     *
     * @param context    任意 Context
     * @param reminderId 提醒 id
     */
    public static void cancel(Context context, long reminderId) {
        if (reminderId <= 0) {
            return;
        }
        AlarmManager alarmManager =
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) {
            PendingIntent pendingIntent = PendingIntent.getBroadcast(context,
                    requestCode(reminderId), buildIntent(context, reminderId, null, null, false, 0),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            alarmManager.cancel(pendingIntent);
            pendingIntent.cancel();
        }
        ReminderNotificationHelper.cancel(context, reminderId);
    }

    /**
     * 重建全部提醒的闹钟。
     * <p>
     * 用于开机、应用更新后以及每次进程启动时的兜底；数据库访问较重，请在子线程调用。
     *
     * @param context 任意 Context
     */
    public static void rescheduleAll(Context context) {
        List<Reminder> list;
        try {
            list = new ReminderDaoImpl().selectAll();
        } catch (Exception e) {
            Log.e(TAG, "读取提醒列表失败", e);
            return;
        }
        for (Reminder reminder : list) {
            if (reminder != null && reminder.isEnabled()) {
                schedule(context, reminder);
            }
        }
    }

    /**
     * 计算提醒的触发时间戳。
     * <p>
     * 固定日期提醒按 targetDay（yyyyMMdd）计算；周期节点提醒（cycleNode>0）
     * 先由 {@link ReminderCycleHelper#nextNodeDate(int)} 算出下一次节点日期再叠加提醒时间；
     * 重复规则提醒（repeatType>0）由 {@link ReminderRepeatHelper} 直接推算下一次触发时间。
     *
     * @param reminder 提醒实体（targetDay 为 yyyyMMdd，alarmTime 为 HH:mm）
     * @return 未来触发时间毫秒值；数据非法或已过去返回 -1
     */
    private static long computeTriggerAt(Reminder reminder) {
        Calendar calendar = Calendar.getInstance();

        if (reminder.isSpanMode()) {
            // 阶段区间模式：区间内每天一次，直接算出下一次区间内的触发时间
            Date next = ReminderCycleHelper.nextSpanOccurrence(reminder,
                    System.currentTimeMillis());
            return next != null ? next.getTime() : -1;
        }

        if (reminder.isCycleMode()) {
            // 周期节点模式：目标日期 = 下一次该节点发生的日期
            Date nodeDate = ReminderCycleHelper.nextNodeDate(reminder.getCycleNode());
            if (nodeDate == null) {
                return -1;
            }
            calendar.setTime(nodeDate);
        } else if (reminder.isRepeatMode()) {
            // 重复规则模式：直接由规则算出下一次触发时间（已包含提醒时间）
            Date next = ReminderRepeatHelper.nextOccurrenceAfter(
                    reminder, System.currentTimeMillis());
            return next != null ? next.getTime() : -1;
        } else {
            int day = reminder.getTargetDay();
            if (day <= 0) {
                return -1;
            }
            int year = day / 10000;
            int month = (day / 100) % 100;
            int date = day % 100;
            if (year <= 0 || month <= 0 || month > 12 || date <= 0 || date > 31) {
                return -1;
            }
            calendar.set(year, month - 1, date, 0, 0, 0);
        }

        int hour = 0;
        int minute = 0;
        String time = reminder.getAlarmTime();
        if (time != null) {
            String[] parts = time.split(":");
            if (parts.length == 2) {
                try {
                    hour = Integer.parseInt(parts[0].trim());
                    minute = Integer.parseInt(parts[1].trim());
                } catch (NumberFormatException ignored) {
                    return -1;
                }
            }
        }
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
            return -1;
        }

        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, minute);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        long triggerAt = calendar.getTimeInMillis();
        return triggerAt > System.currentTimeMillis() ? triggerAt : -1;
    }

    /** 构造携带提醒内容的闹钟 PendingIntent */
    private static PendingIntent buildPendingIntent(Context context, Reminder reminder) {
        return PendingIntent.getBroadcast(context,
                requestCode(reminder.getId()),
                buildIntent(context, reminder.getId(), reminder.getTitle(), reminder.getMessage(),
                        reminder.isAlarm(), reminder.getRingDuration()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * setAlarmClock 要求一个“点击闹钟图标”的展示 Intent，这里通过稳定的中转页打开应用
     * （图标风格切换会禁用 MainActivity，不能直接用 MainActivity）。
     */
    private static PendingIntent buildShowIntent(Context context, Reminder reminder) {
        Intent intent = new Intent(context, work.huacai.yuelan.ShortcutEntryActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(context, requestCode(reminder.getId()), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * 构造闹钟广播 Intent。
     *
     * @param reminderId   提醒 id，用于触发后重排下一次
     * @param title        提醒标题，随 Intent 传递避免触发时再查库
     * @param message      提醒备注，可为空
     * @param fullScreen   true 表示强提醒（全屏闹钟），触发后由通知附带全屏 intent
     * @param ringDuration 强提醒响铃时长（分钟，0 = 一直响）
     */
    private static Intent buildIntent(Context context, long reminderId, String title, String message,
                                      boolean fullScreen, int ringDuration) {
        Intent intent = new Intent(context, ReminderAlarmReceiver.class);
        intent.setAction(ACTION_REMINDER_ALARM);
        intent.putExtra(EXTRA_ID, reminderId);
        intent.putExtra(EXTRA_TITLE, title);
        intent.putExtra(EXTRA_MESSAGE, message);
        intent.putExtra(EXTRA_FULL_SCREEN, fullScreen);
        intent.putExtra(EXTRA_RING_DURATION, ringDuration);
        return intent;
    }

    /** 提醒 id 转 PendingIntent 请求码（int 范围） */
    private static int requestCode(long reminderId) {
        return (int) (reminderId & 0x7FFFFFFF);
    }
}
