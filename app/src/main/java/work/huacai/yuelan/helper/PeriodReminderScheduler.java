package work.huacai.yuelan.helper;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import java.util.Calendar;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import work.huacai.yuelan.dao.impl.MenstrualCycleRecordDaoImpl;
import work.huacai.yuelan.model.MenstrualCycleRecord;
import work.huacai.yuelan.receiver.PeriodReminderReceiver;

/**
 * 经期提醒的闹钟调度器。
 * <p>
 * 与“自定义提醒”（{@link ReminderAlarmScheduler}）不同，经期提醒不是用户指定的固定日期，
 * 而是根据最近一次周期记录推算：
 * <pre>
 * 下次月经开始日 = 当前周期起始日 + 周期天数
 * 提醒时间       = 下次月经开始日 - 提前天数，再套用用户设定的时/分
 * </pre>
 * 由于周期会不断滚动，这里只安排“下一次”提醒；闹钟触发后会由
 * {@link PeriodReminderReceiver} 重新计算并安排再下一次。
 * <p>
 * 设置页修改周期参数、提醒开关或提醒时间后都应调用 {@link #apply(Context)} 重排；
 * 开机与进程启动时也需重建（系统会清除 AlarmManager 中的闹钟）。
 */
public final class PeriodReminderScheduler {

    private static final String TAG = "PeriodReminderScheduler";

    /** 经期提醒设置所在的 SharedPreferences 文件名（与设置页共用） */
    public static final String PREF_NAME = "reminder_settings";
    /** 经期提醒总开关 */
    public static final String KEY_ENABLED = "period_reminder_enabled";
    /** 提前提醒天数 */
    public static final String KEY_DAYS = "reminder_days";
    /** 提醒小时（24 小时制） */
    public static final String KEY_HOUR = "reminder_hour";
    /** 提醒分钟 */
    public static final String KEY_MINUTE = "reminder_minute";

    /** 经期提醒闹钟触发的广播 action */
    public static final String ACTION_PERIOD_REMINDER =
            "work.huacai.yuelan.action.PERIOD_REMINDER";

    /** 经期提醒的 PendingIntent 请求码（与自定义提醒的区间区分开） */
    private static final int REQUEST_CODE = 31001;
    /** 最多向后推算多少个周期，避免数据异常时死循环（24 个周期约两年） */
    private static final int MAX_CYCLE_LOOKAHEAD = 24;
    /** 闹钟刚触发后重排时的最小间隔：1 天，防止同一周期内重复提醒 */
    private static final long RESCHEDULE_MIN_GAP_MILLIS = 24L * 60 * 60 * 1000;
    /** “即将来潮但已过提醒日”时，立即提醒的延迟（1 分钟） */
    private static final long IMMEDIATE_DELAY_MILLIS = 60L * 1000;

    /** 单线程池：计算下次提醒需要读数据库，避免在主线程做磁盘 IO */
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    private PeriodReminderScheduler() {
    }

    /**
     * 读取经期提醒总开关。
     *
     * @param context 任意 Context
     * @return 开关已打开返回 true（默认打开）
     */
    public static boolean isEnabled(Context context) {
        return getPrefs(context).getBoolean(KEY_ENABLED, true);
    }

    /**
     * 读取用户设置的提前提醒天数。
     *
     * @param context 任意 Context
     * @return 提前天数（默认 3 天）
     */
    public static int getReminderDays(Context context) {
        return getPrefs(context).getInt(KEY_DAYS, 3);
    }

    /**
     * 按当前设置重排经期提醒：先取消旧闹钟，开关打开时再异步安排下一次。
     * <p>
     * 适合在设置项变化、导入/清空数据、开机或进程启动时调用；取消操作很轻，直接在主线程完成。
     *
     * @param context 任意 Context
     */
    public static void apply(Context context) {
        Context appContext = context.getApplicationContext();
        cancel(appContext);
        if (!isEnabled(appContext)) {
            return;
        }
        scheduleAsync(appContext);
    }

    /**
     * 异步安排下一次经期提醒（读取数据库较重，不能在主线程执行）。
     *
     * @param context 任意 Context
     */
    public static void scheduleAsync(Context context) {
        Context appContext = context.getApplicationContext();
        executor.execute(() -> schedule(appContext, 0));
    }

    /**
     * 闹钟触发后安排“下一次”提醒（下一个月经周期）。
     * <p>
     * 本次提醒时间已经过去，若仍按普通逻辑计算会命中“立即提醒”分支造成重复通知，
     * 因此把最早触发时间推迟 1 天，要求调度器跳过本周期。
     *
     * @param context 任意 Context
     */
    public static void scheduleNextAfterFired(Context context) {
        Context appContext = context.getApplicationContext();
        long minTriggerAt = System.currentTimeMillis() + RESCHEDULE_MIN_GAP_MILLIS;
        executor.execute(() -> schedule(appContext, minTriggerAt));
    }

    /**
     * 安排下一次经期提醒。
     *
     * @param context      任意 Context
     * @param minTriggerAt 允许的最早触发时间；传 0 表示不限制
     */
    private static void schedule(Context context, long minTriggerAt) {
        if (!isEnabled(context)) {
            return;
        }

        long triggerAt = computeNextTrigger(context, minTriggerAt);
        if (triggerAt <= 0) {
            // 没有周期记录或无法推算：取消旧闹钟，等待用户完善设置后重排
            cancel(context);
            return;
        }

        AlarmManager alarmManager =
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }

        PendingIntent pendingIntent = buildPendingIntent(context);
        boolean exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || alarmManager.canScheduleExactAlarms();
        try {
            if (exact) {
                alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
            } else {
                // 未授予「闹钟与提醒」权限时降级：系统可能合并/延后送达
                alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
            }
            Log.i(TAG, "经期提醒已安排: " + new Date(triggerAt) + ", exact=" + exact);
        } catch (SecurityException e) {
            Log.w(TAG, "精确闹钟无权限，降级为非精确闹钟", e);
            alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
        }
    }

    /**
     * 计算下一次经期提醒的触发时间戳。
     * <p>
     * 从“当前周期起始日”开始逐周期向后推：提醒日 = 下次月经开始日 - 提前天数。
     * <ul>
     *   <li>提醒日尚未到达 → 直接用该时间；</li>
     *   <li>提醒日已过但月经还没开始（例如今天刚打开开关）→ 1 分钟后立即提醒一次；</li>
     *   <li>整个周期都已过去 → 继续看下一个周期。</li>
     * </ul>
     * 闹钟刚触发后重排时传入 {@code minTriggerAt = 当前时间 + 1 天}，从而跳过本周期，安排到下个周期。
     *
     * @param context      任意 Context
     * @param minTriggerAt 允许的最早触发时间；0 表示不限制
     * @return 触发时间毫秒值；无法推算时返回 -1
     */
    private static long computeNextTrigger(Context context, long minTriggerAt) {
        MenstrualCycleRecord record;
        try {
            record = new MenstrualCycleRecordDaoImpl().selectLast();
        } catch (Exception e) {
            Log.e(TAG, "读取周期记录失败", e);
            return -1;
        }
        if (record == null || record.getStartTime() == null) {
            return -1;
        }

        int cycleDays = record.getTotalCycleDay();
        if (cycleDays <= 0) {
            return -1;
        }

        SharedPreferences prefs = getPrefs(context);
        int daysBefore = prefs.getInt(KEY_DAYS, 3);
        int hour = prefs.getInt(KEY_HOUR, 9);
        int minute = prefs.getInt(KEY_MINUTE, 0);

        long now = System.currentTimeMillis();
        // 允许的最早触发时间：正常排程为“现在”，重排时为“现在 + 1 天”
        long threshold = Math.max(now, minTriggerAt);

        Date firstCycleStart = CycleCalculator.getCurrentCycleStart(record.getStartTime(), cycleDays);
        if (firstCycleStart == null) {
            return -1;
        }

        Calendar cycleStart = Calendar.getInstance();
        cycleStart.setTime(firstCycleStart);

        for (int i = 0; i <= MAX_CYCLE_LOOKAHEAD; i++) {
            // 预测的月经开始日 = 本周期起始日 + 一个完整周期
            Calendar predictedStart = (Calendar) cycleStart.clone();
            predictedStart.add(Calendar.DAY_OF_MONTH, cycleDays);

            // 提醒日 = 预测开始日 - 提前提醒天数，并套用用户设定的时分
            Calendar reminderAt = (Calendar) predictedStart.clone();
            reminderAt.add(Calendar.DAY_OF_MONTH, -daysBefore);
            reminderAt.set(Calendar.HOUR_OF_DAY, hour);
            reminderAt.set(Calendar.MINUTE, minute);
            reminderAt.set(Calendar.SECOND, 0);
            reminderAt.set(Calendar.MILLISECOND, 0);

            long reminderMillis = reminderAt.getTimeInMillis();
            long predictedStartMillis = predictedStart.getTimeInMillis();

            if (reminderMillis >= threshold) {
                return reminderMillis;
            }
            if (predictedStartMillis > threshold) {
                // 提醒日已过、但月经还没来：本次不限制最早时间时立即提醒一次
                if (minTriggerAt <= now) {
                    return now + IMMEDIATE_DELAY_MILLIS;
                }
            }

            // 整个周期都已过去，继续推下一个周期
            cycleStart.add(Calendar.DAY_OF_MONTH, cycleDays);
        }
        return -1;
    }

    /**
     * 取消经期提醒闹钟，并撤销可能仍然停留在通知栏的提醒通知。
     *
     * @param context 任意 Context
     */
    public static void cancel(Context context) {
        AlarmManager alarmManager =
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) {
            PendingIntent pendingIntent = buildPendingIntent(context);
            alarmManager.cancel(pendingIntent);
            pendingIntent.cancel();
        }
        ReminderNotificationHelper.cancelPeriodReminder(context);
    }

    /**
     * 构造经期提醒的闹钟 PendingIntent。
     *
     * @param context 任意 Context
     * @return 指向 {@link PeriodReminderReceiver} 的广播 PendingIntent
     */
    private static PendingIntent buildPendingIntent(Context context) {
        Intent intent = new Intent(context, PeriodReminderReceiver.class);
        intent.setAction(ACTION_PERIOD_REMINDER);
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * 获取经期提醒设置。
     *
     * @param context 任意 Context
     * @return 对应的 SharedPreferences
     */
    private static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }
}
