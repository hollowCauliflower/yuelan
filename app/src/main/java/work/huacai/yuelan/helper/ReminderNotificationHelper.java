package work.huacai.yuelan.helper;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import work.huacai.yuelan.R;
import work.huacai.yuelan.activities.AlarmFullScreenActivity;
import work.huacai.yuelan.activities.ReminderListActivity;

/**
 * 自定义提醒事项的通知助手。
 * <p>
 * 通知渠道使用 {@link NotificationManager#IMPORTANCE_HIGH}，并显式设置
 * {@link Notification#VISIBILITY_PUBLIC}，保证到点通知能弹出横幅、响铃/震动，
 * 且在锁屏上显示完整内容，同时出现在下拉通知栏中。
 * <p>
 * 注意：通知渠道创建后，重要性、声音、锁屏可见性等属性无法再被应用修改。
 * 因此当已存在的渠道属性与期望值不一致时（典型是早期版本创建的渠道
 * lockscreenVisibility 仍为 NO_OVERRIDE），只能删除渠道后重新创建。
 */
public final class ReminderNotificationHelper {

    private static final String TAG = "ReminderChannel";

    /** 自定义提醒事项使用的通知渠道 id */
    public static final String CHANNEL_ID = "custom_reminder";
    /** 强提醒（全屏闹钟）使用的通知渠道 id，声音走闹钟通道 */
    public static final String ALARM_CHANNEL_ID = "custom_reminder_alarm";

    /** 通知 id 基数，避免与本应用其它通知冲突 */
    private static final int NOTIFICATION_ID_BASE = 20000;

    /** 渠道属性迁移标记：只做一次删除重建，避免每次启动都删渠道 */
    private static final String PREFS_NAME = "reminder_channel_prefs";
    private static final String KEY_PUBLIC_MIGRATED = "public_visibility_migrated_v1";

    private ReminderNotificationHelper() {
    }

    /**
     * 确保提醒通知渠道已创建（API 26+）。
     * <p>
     * 渠道一旦创建就不能修改重要性与锁屏可见性，所以必须第一次就用 HIGH + PUBLIC。
     *
     * @param context 任意 Context
     */
    public static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }

        NotificationChannel existing = manager.getNotificationChannel(CHANNEL_ID);
        boolean migrated = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_PUBLIC_MIGRATED, false);
        if (existing != null) {
            // 已经做过一次重建迁移就直接复用，避免覆盖用户后来在系统里改的渠道设置
            if (migrated) {
                return;
            }
            // 早期渠道可能是低重要性或 NO_OVERRIDE（渠道属性创建后不可修改），删除后重建一次
            boolean importanceOk = existing.getImportance() == NotificationManager.IMPORTANCE_HIGH;
            boolean visibilityOk = existing.getLockscreenVisibility() == Notification.VISIBILITY_PUBLIC;
            if (importanceOk && visibilityOk) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putBoolean(KEY_PUBLIC_MIGRATED, true).apply();
                return;
            }
            Log.i(TAG, "recreate channel, old importance=" + existing.getImportance()
                    + " old lockscreenVisibility=" + existing.getLockscreenVisibility());
            manager.deleteNotificationChannel(CHANNEL_ID);
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_PUBLIC_MIGRATED, true).apply();

        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.reminder_channel_name),
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(context.getString(R.string.reminder_channel_description));
        // 锁屏显示完整内容（不显示“内容已隐藏”）
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        channel.enableVibration(true);
        channel.setVibrationPattern(new long[]{0, 300, 200, 300});
        Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        channel.setSound(sound, new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .build());
        manager.createNotificationChannel(channel);
        NotificationChannel created = manager.getNotificationChannel(CHANNEL_ID);
        Log.i(TAG, "channel created, importance=" + (created == null ? -1 : created.getImportance())
                + " lockscreenVisibility=" + (created == null ? -1 : created.getLockscreenVisibility()));
    }

    /**
     * 确保强提醒（全屏闹钟）通知渠道已创建（API 26+）。
     * <p>与普通提醒分开建渠道，使用闹钟音频类型，方便用户在系统里单独控制音量与优先级。
     *
     * @param context 任意 Context
     */
    public static void ensureAlarmChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || manager.getNotificationChannel(ALARM_CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(ALARM_CHANNEL_ID,
                context.getString(R.string.reminder_alarm_channel_name),
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(context.getString(R.string.reminder_alarm_channel_description));
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        channel.enableVibration(true);
        channel.setVibrationPattern(new long[]{0, 500, 500});
        // 全屏闹钟页面自己循环播放铃声，渠道不再出声，避免“通知响一次 + 页面再响”叠音；
        // 若系统未允许全屏通知，show() 会退回普通提醒渠道（带声音）
        channel.setSound(null, null);
        manager.createNotificationChannel(channel);
    }

    /**
     * 当前是否可以弹出全屏提醒（Android 14+ 需要系统授权）。
     *
     * @param context 任意 Context
     * @return 可以弹出全屏页面返回 true
     */
    public static boolean canUseFullScreenIntent(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return true;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        return manager != null && manager.canUseFullScreenIntent();
    }

    /**
     * 是否已获得通知权限。Android 13 起需要运行时授权，低版本默认允许。
     *
     * @param context 任意 Context
     * @return 可以发通知返回 true
     */
    public static boolean canPostNotifications(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true;
        }
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 发出到点提醒通知。
     *
     * @param context    任意 Context
     * @param reminderId 提醒 id（用于通知 id 与点击跳转区分）
     * @param title      提醒标题
     * @param message    提醒备注，可为空
     */
    public static void show(Context context, long reminderId, String title, String message) {
        show(context, reminderId, title, message, false);
    }

    /**
     * 发出到点提醒通知；强提醒会附带全屏 intent，
     * 由 {@link work.huacai.yuelan.activities.AlarmFullScreenActivity} 全屏点亮并响铃。
     *
     * @param fullScreen true 表示使用强提醒（全屏闹钟）
     */
    public static void show(Context context, long reminderId, String title, String message,
                            boolean fullScreen) {
        ensureChannel(context);
        if (fullScreen) {
            ensureAlarmChannel(context);
        }
        if (!canPostNotifications(context)) {
            // 没有通知权限时不发，避免静默失败造成用户以为功能失效
            return;
        }

        String text = (message == null || message.isEmpty())
                ? context.getString(R.string.reminder_notification_default_text)
                : message;

        // 强提醒：全屏页面 PendingIntent；系统未授予全屏权限时仍保留高优先级通知
        PendingIntent fullScreenIntent = null;
        if (fullScreen && canUseFullScreenIntent(context)) {
            Intent alarmIntent = new Intent(context, AlarmFullScreenActivity.class);
            alarmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            alarmIntent.putExtra(AlarmFullScreenActivity.EXTRA_ID, reminderId);
            alarmIntent.putExtra(AlarmFullScreenActivity.EXTRA_TITLE, title);
            alarmIntent.putExtra(AlarmFullScreenActivity.EXTRA_MESSAGE, text);
            fullScreenIntent = PendingIntent.getActivity(context, notificationId(reminderId),
                    alarmIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }

        PendingIntent contentIntent;
        if (fullScreenIntent != null) {
            // 点击通知也进入全屏闹钟页，保证用户能在这里关闭响铃
            contentIntent = fullScreenIntent;
        } else {
            Intent intent = new Intent(context, ReminderListActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            contentIntent = PendingIntent.getActivity(context,
                    notificationId(reminderId), intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context,
                fullScreenIntent != null ? ALARM_CHANNEL_ID : CHANNEL_ID)
                .setSmallIcon(AppIconHelper.getNotificationIconRes(context))
                .setContentTitle(title == null ? context.getString(R.string.reminder_manage_title) : title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setCategory(fullScreen ? NotificationCompat.CATEGORY_ALARM
                        : NotificationCompat.CATEGORY_REMINDER)
                // 高优先级 + 公共可见性：横幅弹出、锁屏可见、下拉栏可见
                .setPriority(fullScreen ? NotificationCompat.PRIORITY_MAX
                        : NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                // 全屏页面已负责响铃/震动，通知本身静音，避免重复提醒
                .setSilent(fullScreenIntent != null)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .setContentIntent(contentIntent);

        if (fullScreenIntent != null) {
            builder.setFullScreenIntent(fullScreenIntent, true);
        }

        NotificationManagerCompat.from(context).notify(notificationId(reminderId), builder.build());
    }

    /**
     * 取消某条提醒对应的高优先级通知。
     *
     * @param context    任意 Context
     * @param reminderId 提醒 id
     */
    public static void cancel(Context context, long reminderId) {
        NotificationManagerCompat.from(context).cancel(notificationId(reminderId));
    }

    /**
     * 提醒 id 转通知 id（int 范围）。
     */
    private static int notificationId(long reminderId) {
        return NOTIFICATION_ID_BASE + (int) (reminderId % 1000);
    }

    /**
     * 经期提醒的通知 id（固定值，同一时间只会有一条经期提醒通知）
     */
    private static final int PERIOD_NOTIFICATION_ID = 30001;

    /**
     * 发出“经期即将来潮”提示通知。
     * <p>
     * 复用自定义提醒的通知渠道（高优先级、锁屏可见），点击通知直接回到首页。
     * 没有通知权限时静默返回，避免出现“到了时间却什么都没发生”的误解。
     *
     * @param context   任意 Context
     * @param daysAhead 距离预计来潮还有多少天（用于文案）
     */
    public static void showPeriodReminder(Context context, int daysAhead) {
        ensureChannel(context);
        if (!canPostNotifications(context)) {
            return;
        }

        // 必须打开“当前启用中的入口”：用户切换桌面图标风格后，MainActivity 会被禁用，
        // 直接指向 MainActivity 会导致点击提醒通知没有任何反应。
        Intent intent = new Intent(context, AppIconHelper.getEntryClass(context));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(context,
                PERIOD_NOTIFICATION_ID, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String text = context.getString(R.string.period_reminder_notification_text, daysAhead);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(AppIconHelper.getNotificationIconRes(context))
                .setContentTitle(context.getString(R.string.period_reminder_notification_title))
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .setContentIntent(contentIntent);

        NotificationManagerCompat.from(context).notify(PERIOD_NOTIFICATION_ID, builder.build());
    }

    /**
     * 取消经期提醒通知（关闭提醒开关或清除数据时调用）。
     *
     * @param context 任意 Context
     */
    public static void cancelPeriodReminder(Context context) {
        NotificationManagerCompat.from(context).cancel(PERIOD_NOTIFICATION_ID);
    }
}
