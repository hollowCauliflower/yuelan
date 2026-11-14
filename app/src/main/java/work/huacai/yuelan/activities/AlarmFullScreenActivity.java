package work.huacai.yuelan.activities;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import work.huacai.yuelan.R;
import work.huacai.yuelan.helper.ReminderNotificationHelper;

/**
 * 强提醒（全屏闹钟）页面。
 * <p>
 * 由提醒通知的 full-screen intent 拉起：到点后点亮屏幕、越过锁屏全屏展示，
 * 并循环播放闹钟铃声与震动，直到用户点击“关闭提醒”。
 * <p>
 * 兼容性说明：Android 14 起 {@code USE_FULL_SCREEN_INTENT} 对非闹钟类应用
 * 可能被系统拒绝，此时系统会降级为高优先级横幅通知，本页面不会被拉起。
 */
public class AlarmFullScreenActivity extends AppCompatActivity {

    /** 提醒 id */
    public static final String EXTRA_ID = "alarm_reminder_id";
    /** 提醒标题 */
    public static final String EXTRA_TITLE = "alarm_reminder_title";
    /** 提醒备注 */
    public static final String EXTRA_MESSAGE = "alarm_reminder_message";
    /** 响铃时长（分钟，0 = 一直响到用户关闭） */
    public static final String EXTRA_RING_MINUTES = "alarm_ring_minutes";

    /** 到点后自动静音的延时任务（响铃时长 > 0 时启用） */
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable autoSilenceRunnable = () -> {
        stopAlarmSoundAndVibration();
        // 静音后不再强制亮屏，节省电量；页面保留，仍需用户确认关闭
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        TextView hint = findViewById(R.id.tv_alarm_ring_hint);
        if (hint != null) {
            hint.setText(R.string.alarm_full_screen_silenced);
        }
    };

    /** 循环播放的闹钟铃声；创建失败时为 null */
    @Nullable
    private MediaPlayer mediaPlayer;
    /** 震动器；设备无震动能力时为 null */
    @Nullable
    private Vibrator vibrator;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_alarm_fullscreen);

        // 全屏页面：锁屏上直接显示，并让屏幕保持常亮
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        KeyguardManager keyguardManager =
                (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (keyguardManager != null) {
            keyguardManager.requestDismissKeyguard(this, null);
        }

        bindContent();
        // 通知渠道自带闹钟铃声；页面接手后先撤掉通知，避免“通知响一次 + 页面循环响”叠音
        long reminderId = getIntent().getLongExtra(EXTRA_ID, -1);
        if (reminderId > 0) {
            ReminderNotificationHelper.cancel(this, reminderId);
        }
        startAlarmSoundAndVibration();
        scheduleAutoSilence();
    }

    /**
     * 单实例模式下又收到新的强提醒：刷新内容并继续响铃。
     */
    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        bindContent();
        long reminderId = intent.getLongExtra(EXTRA_ID, -1);
        if (reminderId > 0) {
            ReminderNotificationHelper.cancel(this, reminderId);
        }
        stopAlarmSoundAndVibration();
        startAlarmSoundAndVibration();
        scheduleAutoSilence();
    }

    /**
     * 填充提醒内容：大号当前时间 + 标题 + 备注。
     */
    private void bindContent() {
        TextView tvTime = findViewById(R.id.tv_alarm_time);
        TextView tvTitle = findViewById(R.id.tv_alarm_title);
        TextView tvMessage = findViewById(R.id.tv_alarm_message);
        TextView btnDismiss = findViewById(R.id.btn_alarm_dismiss);

        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
        tvTime.setText(timeFormat.format(new Date()));

        String title = getIntent().getStringExtra(EXTRA_TITLE);
        String message = getIntent().getStringExtra(EXTRA_MESSAGE);
        tvTitle.setText(title == null || title.isEmpty()
                ? getString(R.string.reminder_manage_title) : title);
        tvMessage.setText(message == null || message.isEmpty()
                ? getString(R.string.reminder_notification_default_text) : message);

        // 响铃时长提示：0 表示一直响，不展示提示
        TextView tvRingHint = findViewById(R.id.tv_alarm_ring_hint);
        int ringMinutes = getIntent().getIntExtra(EXTRA_RING_MINUTES, 0);
        if (tvRingHint != null) {
            if (ringMinutes > 0) {
                tvRingHint.setVisibility(View.VISIBLE);
                tvRingHint.setText(getString(R.string.alarm_full_screen_ring_hint, ringMinutes));
            } else {
                tvRingHint.setVisibility(View.GONE);
            }
        }

        btnDismiss.setOnClickListener(v -> dismissAlarm());
    }

    /**
     * 按设置的响铃时长安排自动静音；0 表示不自动停。
     */
    private void scheduleAutoSilence() {
        handler.removeCallbacks(autoSilenceRunnable);
        int minutes = getIntent().getIntExtra(EXTRA_RING_MINUTES, 0);
        if (minutes > 0) {
            handler.postDelayed(autoSilenceRunnable, minutes * 60L * 1000L);
        }
    }

    /**
     * 开始循环播放闹钟铃声并持续震动。
     */
    private void startAlarmSoundAndVibration() {
        try {
            Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (sound == null) {
                sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            }
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            mediaPlayer.setDataSource(this, sound);
            mediaPlayer.setLooping(true);
            mediaPlayer.prepare();
            mediaPlayer.start();
        } catch (Exception ignored) {
            // 铃声资源异常时只保留震动，不影响页面使用
            releasePlayer();
        }

        try {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                long[] pattern = {0, 500, 500};
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
                } else {
                    vibrator.vibrate(pattern, 0);
                }
            }
        } catch (Exception ignored) {
            vibrator = null;
        }
    }

    /**
     * 关闭本次提醒：停止铃声/震动并退出页面。
     */
    private void dismissAlarm() {
        stopAlarmSoundAndVibration();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask();
        } else {
            finish();
        }
    }

    /**
     * 为避免误触返回键把闹钟静音，返回键不做处理，必须点击“关闭提醒”。
     */
    @Override
    public void onBackPressed() {
        // 故意不调用 super：全屏闹钟只能通过按钮关闭
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(autoSilenceRunnable);
        stopAlarmSoundAndVibration();
        super.onDestroy();
    }

    /** 停止并释放铃声与震动资源 */
    private void stopAlarmSoundAndVibration() {
        releasePlayer();
        if (vibrator != null) {
            try {
                vibrator.cancel();
            } catch (Exception ignored) {
                // 部分设备在震动服务异常时会抛异常，忽略即可
            }
            vibrator = null;
        }
    }

    /** 释放 MediaPlayer，可重复调用 */
    private void releasePlayer() {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
            } catch (Exception ignored) {
                // 停止失败时仍然继续释放
            }
            try {
                mediaPlayer.release();
            } catch (Exception ignored) {
                // 忽略重复释放
            }
            mediaPlayer = null;
        }
    }
}
