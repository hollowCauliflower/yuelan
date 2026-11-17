package work.huacai.yuelan.fragments;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.NumberPicker;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.datepicker.CalendarConstraints;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import work.huacai.yuelan.R;
import work.huacai.yuelan.activities.ReminderListActivity;
import work.huacai.yuelan.dao.impl.MenstrualCycleRecordDaoImpl;
import work.huacai.yuelan.database.DatabaseBackupHelper;
import work.huacai.yuelan.helper.AppConfig;
import work.huacai.yuelan.helper.AppIconHelper;
import work.huacai.yuelan.helper.CycleCalculator;
import work.huacai.yuelan.helper.PeriodReminderScheduler;
import work.huacai.yuelan.helper.ReminderNotificationHelper;
import work.huacai.yuelan.helper.SuggestionToneStore;
import work.huacai.yuelan.helper.ThemeHelper;
import work.huacai.yuelan.helper.UpdateChecker;
import work.huacai.yuelan.helper.WeatherCacheHelper;
import work.huacai.yuelan.helper.WeatherLocationHelper;
import work.huacai.yuelan.model.MenstrualCycleRecord;
import work.huacai.yuelan.widget.AppDialogFactory;

/**
 * 设置页。
 * <p>
 * 顶部"当前周期设置"卡片展示并支持直接编辑周期参数（上次月经开始日、周期长度、经期长度），
 * 点击任意一行即可弹出对应选择器修改，保存后立即生效。其余负责经期提醒开关/天数/时间等本地设置，
 * 以及数据导出、导入、备份、清空和隐私政策、用户协议入口。
 */
public class SettingsFragment extends Fragment {

    /** 提醒设置使用的 SharedPreferences 文件名（与调度器共用同一份配置） */
    private static final String PREF_REMINDER = PeriodReminderScheduler.PREF_NAME;
    /** 经期提醒总开关 */
    private static final String KEY_REMINDER_ENABLED = PeriodReminderScheduler.KEY_ENABLED;
    /** 提前提醒的天数 */
    private static final String KEY_REMINDER_DAYS = PeriodReminderScheduler.KEY_DAYS;
    /** 提醒时间的小时（24 小时制） */
    private static final String KEY_REMINDER_HOUR = PeriodReminderScheduler.KEY_HOUR;
    /** 提醒时间的分钟 */
    private static final String KEY_REMINDER_MINUTE = PeriodReminderScheduler.KEY_MINUTE;

    /** SAF “另存为”导出数据的请求码 */
    private static final int REQUEST_EXPORT = 2001;
    /** SAF “打开文件”导入数据的请求码 */
    private static final int REQUEST_IMPORT = 2002;
    /** 从相册选择自定义图标图片的请求码 */
    private static final int REQUEST_CUSTOM_ICON = 2003;
    /** 申请通知权限的请求码（Android 13+） */
    private static final int REQUEST_NOTIFICATION_PERMISSION = 2004;
    /** 应用配置使用的 SharedPreferences 文件名（与 MainActivity 一致） */
    private static final String PREF_CONFIG = "menstruation_config";
    /** 是否首次使用 App 的标记，清空数据后置回 true 让用户重新走引导页 */
    private static final String KEY_FIRST_ENTRY = "first_entry";

    private TextView tvReminderDays;
    private TextView tvReminderTime;
    private TextView tvVersion;
    /** 当前主题模式的展示文案 */
    private TextView tvThemeMode;
    /** 每日建议语气的展示文案 */
    private TextView tvSuggestionTone;
    /** 天气地点：跟随定位或用户指定的城市 */
    private TextView tvWeatherLocation;
    /** 设置页里当前图标风格的预览图标 */
    private ImageView ivAppIconPreview;

    /** 是否正在等待系统固定桌面快捷方式的结果 */
    private boolean shortcutRequestPending = false;
    /** 系统快捷方式确认框是否让页面暂停过（用于关闭后检查结果） */
    private boolean shortcutDialogPaused = false;
    /** 本次请求的快捷方式唯一 id（每次新建，避免“图标已存在”） */
    private String pendingShortcutId;
    /** 快捷方式创建成功后才保存为应用内自定义图标的图片 */
    private Bitmap pendingShortcutIcon;
    /** 创建前是否已经存在旧快捷方式，用于成功后的清理提示 */
    private boolean hadPreviousShortcut = false;
    /** 系统真正固定快捷方式后才会收到该广播 */
    private final BroadcastReceiver shortcutPinnedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            resolveShortcutResult(true);
        }
    };

    /** 当前周期设置：上次月经开始日 */
    private TextView tvLastPeriodStart;
    /** 当前周期设置：周期长度（天） */
    private TextView tvCycleLength;
    /** 当前周期设置：经期长度（天） */
    private TextView tvPeriodLength;
    /** 当前周期设置记录，编辑时直接修改此对象后回写数据库 */
    private MenstrualCycleRecord currentCycleRecord;

    private boolean periodReminderEnabled = true;
    private int reminderDays = 3;
    private int reminderHour = 9;
    private int reminderMinute = 0;

    public SettingsFragment() {
    }

    /**
     * 创建设置页实例。
     *
     * @return 新的 {@link SettingsFragment} 实例
     */
    public static SettingsFragment newInstance() {
        return new SettingsFragment();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    /**
     * 视图创建完成后：绑定控件、加载数据、注册点击监听。
     */
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        initViews(view);
        loadData();
        setupListeners();

        // 系统“添加到主屏幕”确认框关闭后窗口会重新获得焦点，
        // 此时检查快捷方式是否真正固定，用于识别用户取消/创建失败
        view.getViewTreeObserver().addOnWindowFocusChangeListener(hasFocus -> {
            if (hasFocus && shortcutRequestPending) {
                view.postDelayed(this::checkShortcutPinned, 600L);
            }
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        // 只接收系统固定快捷方式成功后的回调（应用内广播，不对外导出）
        ContextCompat.registerReceiver(requireContext(), shortcutPinnedReceiver,
                new IntentFilter(AppIconHelper.ACTION_SHORTCUT_PINNED),
                ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    public void onStop() {
        Context context = getContext();
        if (context != null) {
            try {
                context.unregisterReceiver(shortcutPinnedReceiver);
            } catch (IllegalArgumentException ignored) {
                // 未注册成功时忽略，避免退出页面崩溃
            }
        }
        super.onStop();
    }

    /**
     * 绑定布局控件，并展示当前 App 版本号。
     *
     * @param view 设置页根视图
     */
    private void initViews(View view) {
        tvReminderDays = view.findViewById(R.id.tv_reminder_days);
        tvReminderTime = view.findViewById(R.id.tv_reminder_time);
        tvVersion = view.findViewById(R.id.tv_version);
        tvThemeMode = view.findViewById(R.id.tv_theme_mode);
        tvThemeMode.setText(getThemeModeName(ThemeHelper.getSavedMode(requireContext())));
        tvSuggestionTone = view.findViewById(R.id.tv_suggestion_tone);
        tvSuggestionTone.setText(getToneName(SuggestionToneStore.getTone(requireContext())));
        tvWeatherLocation = view.findViewById(R.id.tv_weather_location);
        ivAppIconPreview = view.findViewById(R.id.iv_app_icon_preview);
        AppIconHelper.loadAppIcon(requireContext(), ivAppIconPreview);

        // 当前周期设置（只读展示）
        tvLastPeriodStart = view.findViewById(R.id.tv_last_period_start);
        tvCycleLength = view.findViewById(R.id.tv_cycle_length);
        tvPeriodLength = view.findViewById(R.id.tv_period_length);

        try {
            String versionName = requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0).versionName;
            tvVersion.setText("v" + versionName);
        } catch (PackageManager.NameNotFoundException e) {
            // 取不到版本号时使用兜底文案
            tvVersion.setText("v1.0.0");
        }
    }

    /**
     * 加载提醒相关设置与当前周期设置并刷新界面。
     */
    private void loadData() {
        loadReminderSettings();
        loadCycleSettings();
        // 语气可能在设置弹窗之外被修改，回到页面时统一刷新展示
        if (tvSuggestionTone != null) {
            tvSuggestionTone.setText(getToneName(SuggestionToneStore.getTone(requireContext())));
        }
        tvWeatherLocation.setText(WeatherLocationHelper.getDisplayName(requireContext()));
        // 跟随系统定位时异步反查城市名，把展示补全成"跟随系统定位（南昌）"
        if (WeatherLocationHelper.isDeviceMode(requireContext())) {
            WeatherLocationHelper.resolveDeviceCityName(requireContext(), cityName -> {
                if (!isAdded() || tvWeatherLocation == null) return;
                tvWeatherLocation.setText(WeatherLocationHelper.getDisplayName(requireContext()));
            });
        }
    }

    /**
     * 读取最近一次保存的周期设置，填充到顶部"当前周期设置"卡片，
     * 并缓存到 currentCycleRecord 供后续编辑使用。
     * <p>
     * 没有任何周期设置记录时展示"尚未设置"占位文案。
     */
    private void loadCycleSettings() {
        currentCycleRecord = new MenstrualCycleRecordDaoImpl().selectLast();
        if (currentCycleRecord == null) {
            String notSet = getString(R.string.settings_cycle_not_set);
            tvLastPeriodStart.setText(notSet);
            tvCycleLength.setText(notSet);
            tvPeriodLength.setText(notSet);
            return;
        }

        if (currentCycleRecord.getStartTime() != null) {
            // 用 yyyy-MM-dd 展示，比 MM月dd日 更利于识别完整日期
            tvLastPeriodStart.setText(CycleCalculator.formatDateId(currentCycleRecord.getStartTime())
                    .replaceFirst("(\\d{4})(\\d{2})(\\d{2})", "$1-$2-$3"));
        } else {
            tvLastPeriodStart.setText(R.string.settings_cycle_not_set);
        }
        tvCycleLength.setText(getString(R.string.settings_flow_label_with_unit, currentCycleRecord.getTotalCycleDay()));
        tvPeriodLength.setText(getString(R.string.settings_flow_label_with_unit, currentCycleRecord.getMenstrualPeriodDay()));
    }

    /**
     * 读取提醒相关设置并刷新界面。
     */
    private void loadReminderSettings() {
        SharedPreferences prefs = getReminderPrefs();
        periodReminderEnabled = prefs.getBoolean(KEY_REMINDER_ENABLED, true);
        reminderDays = prefs.getInt(KEY_REMINDER_DAYS, 3);
        reminderHour = prefs.getInt(KEY_REMINDER_HOUR, 9);
        reminderMinute = prefs.getInt(KEY_REMINDER_MINUTE, 0);

        tvReminderDays.setText(formatDays(reminderDays));
        tvReminderTime.setText(formatReminderTime(reminderHour, reminderMinute));
    }

    /**
     * 把当前提醒设置写回 SharedPreferences，并立即按新设置重排经期提醒。
     * <p>
     * 关闭开关时会取消闹钟；开启/调整天数或时间时会重新计算下一次提醒时间。
     */
    private void saveReminderSettings() {
        SharedPreferences.Editor editor = getReminderPrefs().edit();
        editor.putBoolean(KEY_REMINDER_ENABLED, periodReminderEnabled);
        editor.putInt(KEY_REMINDER_DAYS, reminderDays);
        editor.putInt(KEY_REMINDER_HOUR, reminderHour);
        editor.putInt(KEY_REMINDER_MINUTE, reminderMinute);
        editor.apply();

        // 设置变化后重排闹钟（内部会先取消旧闹钟，再异步计算下一次提醒）
        PeriodReminderScheduler.apply(requireContext());
    }

    /**
     * 获取提醒设置对应的 SharedPreferences。
     *
     * @return 提醒设置 SharedPreferences
     */
    private SharedPreferences getReminderPrefs() {
        return requireContext().getSharedPreferences(PREF_REMINDER, Context.MODE_PRIVATE);
    }

    /**
     * 为各个设置项与功能入口注册点击监听。
     */
    private void setupListeners() {
        View view = getView();
        View layoutReminderDays = view.findViewById(R.id.layout_reminder_days);
        View layoutReminderTime = view.findViewById(R.id.layout_reminder_time);
        View layoutCustomReminder = view.findViewById(R.id.layout_custom_reminder);
        View layoutExport = view.findViewById(R.id.layout_export);
        View layoutImport = view.findViewById(R.id.layout_import);
        View layoutClearData = view.findViewById(R.id.layout_clear_data);
        View layoutPrivacy = view.findViewById(R.id.layout_privacy);
        View layoutAgreement = view.findViewById(R.id.layout_agreement);
        View layoutAppIcon = view.findViewById(R.id.layout_app_icon);
        View layoutThemeMode = view.findViewById(R.id.layout_theme_mode);
        View layoutSuggestionTone = view.findViewById(R.id.layout_suggestion_tone);
        View layoutWeatherLocation = view.findViewById(R.id.layout_weather_location);
        View layoutCheckUpdate = view.findViewById(R.id.layout_check_update);
        SwitchCompat switchPeriodReminder = view.findViewById(R.id.switch_period_reminder);

        layoutReminderDays.setOnClickListener(v -> showReminderDaysDialog());
        layoutReminderTime.setOnClickListener(v -> showTimePicker());
        // 提前天数与提醒时间都是"月经提醒"的子项：总开关关闭时置灰且不可点击
        if (layoutCustomReminder != null) {
            // “自定义提醒”在部分布局版本中可能不存在
            layoutCustomReminder.setOnClickListener(v -> showCustomReminderDialog());
        }
        layoutExport.setOnClickListener(v -> exportData());
        layoutImport.setOnClickListener(v -> importData());
        layoutClearData.setOnClickListener(v -> showClearDataDialog());
        layoutPrivacy.setOnClickListener(v -> showPrivacyPolicy());
        layoutAgreement.setOnClickListener(v -> showUserAgreement());
        if (layoutAppIcon != null) {
            layoutAppIcon.setOnClickListener(v -> showAppIconDialog());
        }
        if (layoutThemeMode != null) {
            layoutThemeMode.setOnClickListener(v -> showThemeModeDialog());
        }
        if (layoutSuggestionTone != null) {
            layoutSuggestionTone.setOnClickListener(v -> showSuggestionToneDialog());
        }
        if (layoutWeatherLocation != null) {
            layoutWeatherLocation.setOnClickListener(v -> showWeatherLocationDialog());
        }
        if (layoutCheckUpdate != null) {
            // 手动检查更新：与启动时自动检查复用同一套逻辑
            layoutCheckUpdate.setOnClickListener(v -> UpdateChecker.checkManually(requireActivity()));
        }

        // 月经提醒总开关：切换后立即持久化，并按新状态重排/取消闹钟，同时联动子项可用状态
        switchPeriodReminder.setOnCheckedChangeListener((buttonView, isChecked) -> {
            periodReminderEnabled = isChecked;
            saveReminderSettings();
            applyPeriodReminderEnabledState(layoutReminderDays, layoutReminderTime, isChecked);

            // Android 13+ 未授予通知权限时，到点通知会被系统静默丢弃，开启时顺手申请
            if (isChecked && !ReminderNotificationHelper.canPostNotifications(requireContext())) {
                ActivityCompat.requestPermissions(requireActivity(),
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQUEST_NOTIFICATION_PERMISSION);
            }
        });
        switchPeriodReminder.setChecked(periodReminderEnabled);
        applyPeriodReminderEnabledState(layoutReminderDays, layoutReminderTime, periodReminderEnabled);

        // 当前周期设置：三项参数统一由卡片标题右侧的"编辑"入口一起编辑，
        // 单项修改容易让开始日期/周期长度/经期长度错位
        view.findViewById(R.id.layout_cycle_header).setOnClickListener(v -> showCycleSettingsDialog());
    }

    /**
     * 控制"月经提醒"子项（提前提醒天数、提醒时间）的可用状态：
     * 总开关关闭时置灰并取消点击，避免用户误以为单独修改仍会生效。
     *
     * @param reminderDaysRow 提前提醒天数行
     * @param reminderTimeRow 提醒时间行
     * @param enabled         月经提醒是否开启
     */
    private void applyPeriodReminderEnabledState(View reminderDaysRow, View reminderTimeRow,
                                                 boolean enabled) {
        float alpha = enabled ? 1f : 0.4f;
        if (reminderDaysRow != null) {
            reminderDaysRow.setAlpha(alpha);
            reminderDaysRow.setClickable(enabled);
        }
        if (reminderTimeRow != null) {
            reminderTimeRow.setAlpha(alpha);
            reminderTimeRow.setClickable(enabled);
        }
    }

    /**
     * 弹出合并的"更新本轮周期"弹窗：开始日期 / 周期长度 / 经期长度一起编辑，
     * 确认时一次性保存（工作副本语义，取消不落库）。
     */
    private void showCycleSettingsDialog() {
        if (currentCycleRecord == null) {
            showNoCycleRecordDialog();
            return;
        }

        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_cycle_settings, null);
        View rowStartDate = dialogView.findViewById(R.id.row_dialog_start_date);
        TextView tvStartDate = dialogView.findViewById(R.id.tv_dialog_start_date);
        NumberPicker npCycleLength = dialogView.findViewById(R.id.np_cycle_length);
        NumberPicker npPeriodLength = dialogView.findViewById(R.id.np_period_length);

        // 工作副本：取消时不写回 currentCycleRecord
        final Date[] startDateHolder = {currentCycleRecord.getStartTime()};
        tvStartDate.setText(formatCycleStartDate(startDateHolder[0]));

        rowStartDate.setOnClickListener(v -> pickStartDate(startDateHolder[0], picked -> {
            startDateHolder[0] = picked;
            tvStartDate.setText(formatCycleStartDate(picked));
        }));

        npCycleLength.setMinValue(21);
        npCycleLength.setMaxValue(45);
        npCycleLength.setValue(currentCycleRecord.getTotalCycleDay());
        npCycleLength.setWrapSelectorWheel(false);

        npPeriodLength.setMinValue(2);
        npPeriodLength.setMaxValue(10);
        npPeriodLength.setValue(currentCycleRecord.getMenstrualPeriodDay());
        npPeriodLength.setWrapSelectorWheel(false);

        AppDialogFactory.showForm(requireContext(),
                getString(R.string.settings_update_cycle_title), dialogView,
                R.string.dialog_confirm, R.string.dialog_cancel, dialog -> {
                    // 上次月经开始日不允许晚于今天：弹窗提示并让用户重新选择，
                    // 弹窗保持打开，避免把未来日期写进周期分段
                    if (startDateHolder[0] != null
                            && CycleCalculator.normalizeDate(startDateHolder[0]).after(
                            CycleCalculator.normalizeDate(new Date()))) {
                        AppDialogFactory.showConfirm(requireContext(),
                                getString(R.string.cycle_start_after_today_title),
                                getString(R.string.cycle_start_after_today_message),
                                R.string.cycle_start_after_today_reselect,
                                R.string.dialog_cancel,
                                () -> pickStartDate(startDateHolder[0], picked -> {
                                    startDateHolder[0] = picked;
                                    tvStartDate.setText(formatCycleStartDate(picked));
                                }));
                        return;
                    }

                    // 起始日是否被改动：改动时要把该日落为实际经期首日，
                    // 否则会被“按实际记录对齐基准日”回滚，用户看到的仍是旧周期的阶段
                    boolean startChanged = startDateHolder[0] != null
                            && (currentCycleRecord.getStartTime() == null
                            || !CycleCalculator.formatDateId(startDateHolder[0])
                            .equals(CycleCalculator.formatDateId(currentCycleRecord.getStartTime())));
                    currentCycleRecord.setStartTime(startDateHolder[0]);
                    currentCycleRecord.setTotalCycleDay(npCycleLength.getValue());
                    currentCycleRecord.setMenstrualPeriodDay(npPeriodLength.getValue());
                    saveCycleSettings(startChanged);
                    dialog.dismiss();
                });
    }

    /** 把周期开始日期格式化为 yyyy-MM-dd；为空时显示占位文案 */
    private String formatCycleStartDate(Date date) {
        if (date == null) {
            return getString(R.string.settings_cycle_not_set);
        }
        return CycleCalculator.formatDateId(date)
                .replaceFirst("(\\d{4})(\\d{2})(\\d{2})", "$1-$2-$3");
    }

    /**
     * 弹出"上次月经开始日"选择器（范围：今天往前 2 年到今天），选中后回调。
     *
     * @param initial   初始选中日期；null 时默认今天
     * @param onPicked  选中回调
     */
    private void pickStartDate(Date initial, Consumer<Date> onPicked) {
        Calendar calendar = Calendar.getInstance();
        if (initial != null) {
            calendar.setTime(initial);
        }

        Calendar start = Calendar.getInstance();
        start.add(Calendar.YEAR, -2);
        Calendar end = Calendar.getInstance();

        CalendarConstraints constraints = new CalendarConstraints.Builder()
                .setStart(CycleCalculator.toUtcMidnight(start))
                .setEnd(CycleCalculator.toUtcMidnight(end))
                .build();

        MaterialDatePicker<Long> picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText(R.string.settings_last_period_start)
                .setSelection(CycleCalculator.toUtcMidnight(calendar))
                .setCalendarConstraints(constraints)
                .build();

        picker.addOnPositiveButtonClickListener(selection -> {
            if (selection == null) return;
            onPicked.accept(CycleCalculator.localFromUtc(selection));
        });
        picker.show(getChildFragmentManager(), "start_date_picker");
    }

    /**
     * 弹出“自定义提醒”说明弹窗。
     */
    private void showCustomReminderDialog() {
        startActivity(new Intent(requireContext(), ReminderListActivity.class));
    }

    /**
     * 弹出提醒提前天数选择弹窗（1~7 天）。
     */
    private void showReminderDaysDialog() {
        showNumberPickerDialog(R.string.settings_reminder_days_title, 1, 7, reminderDays,
                value -> {
                    reminderDays = value;
                    tvReminderDays.setText(formatDays(reminderDays));
                    saveReminderSettings();
                });
    }

    /**
     * 通用的数字选择弹窗，三个设置项共用同一套弹窗逻辑。
     *
     * @param titleRes      弹窗标题资源 id
     * @param min           可选最小值
     * @param max           可选最大值
     * @param initialValue  初始值
     * @param onConfirmed   点击“确定”后的回调，参数为选中的值
     */
    private void showNumberPickerDialog(int titleRes, int min, int max, int initialValue,
                                        IntConsumer onConfirmed) {
        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_number_picker, null);
        NumberPicker np = dialogView.findViewById(R.id.number_picker);

        np.setMinValue(min);
        np.setMaxValue(max);
        np.setValue(initialValue);
        np.setWrapSelectorWheel(false);

        AppDialogFactory.showForm(requireContext(), getString(titleRes), dialogView,
                R.string.dialog_confirm, R.string.dialog_cancel, dialog -> {
                    onConfirmed.accept(np.getValue());
                    dialog.dismiss();
                });
    }

    /**
     * 保存当前周期设置到数据库，按新参数重新生成预测记录并重排经期提醒，
     * 最后刷新设置页展示。
     *
     * @param startDateChanged 用户是否修改了起始日；修改时需把该日落为实际经期首日，
     *                         否则基准日会被历史实际记录回滚
     */
    private void saveCycleSettings(boolean startDateChanged) {
        if (currentCycleRecord == null) return;

        MenstrualCycleRecordDaoImpl dao = new MenstrualCycleRecordDaoImpl();
        if (startDateChanged) {
            // 起始日按用户选择生效，并确保存在以该日开头的分段
            dao.applyManualCycleStart(currentCycleRecord.getStartTime(),
                    currentCycleRecord.getTotalCycleDay(),
                    currentCycleRecord.getMenstrualPeriodDay(), requireContext());
        } else {
            // 原地更新最近一条设置，避免频繁调整时表记录不断增长
            dao.updateLatest(currentCycleRecord);
        }
        // 用户重新定义周期参数后，按新参数重新生成到今天的预测记录，
        // 并把基准日对齐到最近的实际经期
        dao.syncActualStartAndRegenerate(requireContext());
        // 周期参数改变后重排经期提醒与周期节点提醒
        PeriodReminderScheduler.apply(requireContext());
        work.huacai.yuelan.helper.ReminderAlarmScheduler
                .rescheduleAll(requireContext());

        loadCycleSettings();
    }

    /**
     * 尚未设置过周期时的提示弹窗。
     */
    private void showNoCycleRecordDialog() {
        AppDialogFactory.showConfirm(requireContext(),
                getString(R.string.dialog_title_prompt),
                getString(R.string.calendar_no_cycle_record),
                R.string.dialog_confirm, 0, null);
    }

    /**
     * 弹出提醒时间选择器（Material 风格），跟随系统 12/24 小时制设置。
     */
    private void showTimePicker() {
        MaterialTimePicker picker = new MaterialTimePicker.Builder()
                .setTimeFormat(DateFormat.is24HourFormat(requireContext())
                        ? TimeFormat.CLOCK_24H : TimeFormat.CLOCK_12H)
                .setHour(reminderHour)
                .setMinute(reminderMinute)
                .setTitleText(R.string.settings_reminder_time)
                .build();

        picker.addOnPositiveButtonClickListener(v -> {
            reminderHour = picker.getHour();
            reminderMinute = picker.getMinute();
            tvReminderTime.setText(formatReminderTime(reminderHour, reminderMinute));
            saveReminderSettings();
        });
        picker.show(getChildFragmentManager(), "reminder_time_picker");
    }

    /**
     * 通过系统“另存为”选择器导出数据，用户可自由选择保存位置与文件名。
     */
    private void exportData() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, DatabaseBackupHelper.createExportFileName());
        try {
            startActivityForResult(intent, REQUEST_EXPORT);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(requireContext(), R.string.settings_no_file_picker, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 二次确认后通过系统“打开文件”选择器选中 JSON 导出文件进行导入。
     */
    private void importData() {
        AppDialogFactory.showConfirm(requireContext(),
                getString(R.string.settings_import_title),
                getString(R.string.settings_import_message),
                R.string.settings_import_button, R.string.dialog_cancel,
                () -> {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    // JSON 文件在系统中可能被识别为任意 MIME，放宽为全部类型再按扩展名过滤
                    intent.setType("*/*");
                    intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                            "application/json", "text/plain", "application/octet-stream"});
                    try {
                        startActivityForResult(intent, REQUEST_IMPORT);
                    } catch (ActivityNotFoundException e) {
                        Toast.makeText(requireContext(), R.string.settings_no_file_picker, Toast.LENGTH_SHORT).show();
                    }
                });
    }

    /**
     * 处理系统文件选择器的返回结果：导出时写入所选位置，导入时从事务恢复数据。
     */
    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();

        if (requestCode == REQUEST_EXPORT) {
            try {
                DatabaseBackupHelper.exportToUri(requireContext(), uri);
                Toast.makeText(requireContext(), R.string.settings_export_done, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(requireContext(),
                        getString(R.string.settings_export_failed, e.getMessage()), Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQUEST_IMPORT) {
            try {
                DatabaseBackupHelper.importFromUri(requireContext(), uri);
                Toast.makeText(requireContext(), R.string.settings_import_success, Toast.LENGTH_SHORT).show();
                // 导入数据后周期设置可能变化，需要按新数据重排经期提醒
                PeriodReminderScheduler.apply(requireContext());
                // 提醒表已被整体替换：按导入后的提醒重建闹钟，
                // 否则新导入的提醒不会响，旧提醒的闹钟也会残留在系统里
                work.huacai.yuelan.helper.ReminderAlarmScheduler
                        .rescheduleAll(requireContext());
                loadData();
            } catch (Exception e) {
                Toast.makeText(requireContext(),
                        getString(R.string.settings_import_failed, e.getMessage()), Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQUEST_CUSTOM_ICON) {
            applyCustomIcon(uri);
        }
    }

    /**
     * 用所选图片创建桌面快捷方式。
     * <p>该入口只创建快捷方式，不修改应用内图标；成功与否以系统是否真正
     * 固定快捷方式为准：用户取消或创建失败都会提示“操作未成功”。
     *
     * @param uri 图片选择器返回的 Uri
     */
    private void applyCustomIcon(Uri uri) {
        try {
            Bitmap icon = AppIconHelper.decodeShortcutIcon(requireContext(), uri);
            // 每次使用唯一 id，避免系统提示“图标已存在，创建失败”
            String shortcutId = "custom_app_icon_" + System.currentTimeMillis();
            // 记录创建前是否已有旧快捷方式，成功后提示用户手动清理
            hadPreviousShortcut = AppIconHelper.hasPinnedCustomShortcuts(requireContext());

            // 系统真正固定快捷方式后才会发送该广播
            Intent callbackIntent = new Intent(AppIconHelper.ACTION_SHORTCUT_PINNED);
            callbackIntent.setPackage(requireContext().getPackageName());
            PendingIntent callback = PendingIntent.getBroadcast(requireContext(), shortcutId.hashCode(),
                    callbackIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            pendingShortcutIcon = icon;
            pendingShortcutId = shortcutId;
            boolean requested = AppIconHelper.requestShortcut(requireActivity(), icon, callback, shortcutId);
            if (!requested) {
                pendingShortcutIcon = null;
                pendingShortcutId = null;
                Toast.makeText(requireContext(),
                        R.string.settings_app_icon_shortcut_unsupported, Toast.LENGTH_SHORT).show();
                return;
            }
            shortcutRequestPending = true;
        } catch (Exception e) {
            pendingShortcutIcon = null;
            pendingShortcutId = null;
            Toast.makeText(requireContext(),
                    getString(R.string.settings_app_icon_custom_failed, e.getMessage()),
                    Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 系统确认框关闭后检查快捷方式是否真的出现在桌面。
     */
    private void checkShortcutPinned() {
        if (!shortcutRequestPending || !isAdded()) {
            return;
        }
        resolveShortcutResult(AppIconHelper.isShortcutPinned(requireContext(), pendingShortcutId));
    }

    /**
     * 统一处理快捷方式创建结果，保证只提示一次。
     * <p>只有快捷方式真正创建成功时，才把所选图片保存为应用内自定义图标，
     * 让设置页预览同步显示用户选择的图片。
     *
     * @param success true 表示快捷方式已真正固定到桌面
     */
    private void resolveShortcutResult(boolean success) {
        if (!shortcutRequestPending || !isAdded()) {
            return;
        }
        shortcutRequestPending = false;
        shortcutDialogPaused = false;

        if (success && pendingShortcutIcon != null) {
            try {
                AppIconHelper.saveCustomIcon(requireContext(), pendingShortcutIcon);
                AppIconHelper.loadAppIcon(requireContext(), ivAppIconPreview);
            } catch (Exception ignored) {
                // 保存预览失败不影响“快捷方式已创建”的结论
            }
        }
        pendingShortcutIcon = null;
        pendingShortcutId = null;

        if (success) {
            // 已有旧快捷方式时提醒用户手动删除，Android 不允许应用自动移除
            String message = hadPreviousShortcut
                    ? getString(R.string.settings_app_icon_shortcut_old_message)
                    : getString(R.string.settings_app_icon_shortcut_success_message);
            AppDialogFactory.showConfirm(requireContext(),
                    getString(R.string.settings_app_icon_shortcut_created),
                    message, R.string.dialog_i_know, 0, null);
        } else {
            Toast.makeText(requireContext(),
                    R.string.settings_app_icon_shortcut_cancelled, Toast.LENGTH_SHORT).show();
        }
        hadPreviousShortcut = false;
    }

    /**
     * 说明桌面图标无法直接替换，并提供“创建桌面快捷方式”把自定义图片放到桌面。
     */
    private void showShortcutDialog() {
        AppDialogFactory.showConfirm(requireContext(),
                getString(R.string.settings_app_icon_shortcut_title),
                getString(R.string.settings_app_icon_shortcut_message),
                R.string.settings_app_icon_shortcut_create, R.string.dialog_cancel,
                this::createCustomIconShortcut);
    }

    /**
     * 在桌面创建使用自定义图片的快捷方式（不替换桌面图标，作为替代方案）。
     */
    private void createCustomIconShortcut() {
        boolean created = AppIconHelper.requestCustomIconShortcut(requireActivity());
        Toast.makeText(requireContext(),
                created ? R.string.settings_app_icon_shortcut_done
                        : R.string.settings_app_icon_shortcut_unsupported,
                Toast.LENGTH_SHORT).show();
    }

    /**
     * 弹出清空数据的二次确认弹窗。
     */
    private void showClearDataDialog() {
        AppDialogFactory.showConfirm(requireContext(),
                getString(R.string.settings_clear_data_title),
                getString(R.string.settings_clear_data_message),
                R.string.dialog_clear, R.string.dialog_cancel,
                this::clearAllData);
    }

    /**
     * 清空全部业务数据（清空前会自动生成一份备份），随后置回首次使用标记，
     * 重启应用让用户像刚安装一样重新走引导页。
     */
    private void clearAllData() {
        try {
            DatabaseBackupHelper.clearAllData(requireContext());

            // 数据已清空，取消经期提醒闹钟并撤销残留通知
            PeriodReminderScheduler.cancel(requireContext());

            // 数据清空后回到“首次使用”状态，重启后由 MainActivity 跳转引导页
            requireContext().getSharedPreferences(PREF_CONFIG, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_FIRST_ENTRY, true).apply();

            Toast.makeText(requireContext(), R.string.settings_data_cleared, Toast.LENGTH_SHORT).show();

            // 重启到当前图标风格对应的入口 Activity（该入口才是当前启用的 LAUNCHER 组件）
            Intent intent = new Intent(requireContext(), AppIconHelper.getEntryClass(requireContext()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(requireContext(),
                    getString(R.string.settings_clear_failed_with_msg, e.getMessage()),
                    Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 弹出图标选择弹窗：默认鲸尾月牙 + 蔚蓝月 / 薄荷星 / 紫罗兰三套预设
     * + “自定义桌面快捷方式（从相册选择）”。
     * <p>
     * 选择预设风格后桌面图标与启动页图标会同步切换；选择自定义桌面快捷方式
     * 则只把所选图片做成桌面快捷方式，不修改应用内图标。
     * <p>
     * 定制包（APK 内打包了用户图片集，assets/custom_icons/icons.json 存在）只展示
     * “默认图标 + 我的图片”，预设风格与相册入口不再出现，保证定制包内只含用户自己的图。
     */
    private void showAppIconDialog() {
        List<String> bundled = AppIconHelper.getBundledIconAssets(requireContext());
        if (!bundled.isEmpty()) {
            showBundledIconDialog(bundled);
            return;
        }

        int currentStyle = AppIconHelper.getCurrentStyle(requireContext());
        boolean hasCustomIcon = AppIconHelper.hasCustomIcon(requireContext());

        // 精简后的可选风格：默认 + 蓝/绿/紫三个区分度最高的
        final int[] visibleStyles = {
                AppIconHelper.STYLE_DEFAULT, AppIconHelper.STYLE_MOON,
                AppIconHelper.STYLE_STAR, AppIconHelper.STYLE_VIOLET
        };
        List<String> names = new ArrayList<>();
        for (int style : visibleStyles) {
            names.add(getString(AppIconHelper.getStyleNameRes(style)));
        }
        int customIndex = names.size();
        names.add(getString(R.string.icon_style_custom));

        final int removeIndex;
        if (hasCustomIcon) {
            removeIndex = names.size();
            names.add(getString(R.string.icon_style_custom_remove));
        } else {
            removeIndex = -1;
        }

        // 定制入口：告诉用户如何把图片发给我们打包专属图标（发邮箱/加微信）
        int guideIndex = names.size();
        names.add(getString(R.string.icon_style_custom_guide));

        // 当前风格不在可见列表中时（旧版本选过已精简的风格），勾选落在默认风格上
        int visibleCurrent = 0;
        for (int i = 0; i < visibleStyles.length; i++) {
            if (visibleStyles[i] == currentStyle) {
                visibleCurrent = i;
                break;
            }
        }
        int checkedIndex = hasCustomIcon ? customIndex : visibleCurrent;

        AppDialogFactory.showSingleChoice(requireContext(),
                getString(R.string.settings_app_icon_title),
                names.toArray(new String[0]), checkedIndex, which -> {
                    if (which == customIndex) {
                        pickCustomIconImage();
                        return;
                    }
                    if (which == guideIndex) {
                        showCustomIconGuide();
                        return;
                    }
                    if (which == removeIndex) {
                        AppIconHelper.clearCustomIcon(requireContext());
                        AppIconHelper.loadAppIcon(requireContext(), ivAppIconPreview);
                        Toast.makeText(requireContext(),
                                R.string.settings_app_icon_custom_removed, Toast.LENGTH_SHORT).show();
                        return;
                    }

                    int targetStyle = visibleStyles[which];
                    // 切换到预设风格：自定义图片同时失效，避免与预设图标混淆
                    AppIconHelper.clearCustomIcon(requireContext());
                    if (targetStyle == currentStyle) {
                        AppIconHelper.loadAppIcon(requireContext(), ivAppIconPreview);
                        return;
                    }
                    AppIconHelper.applyStyle(requireContext(), targetStyle);
                    AppIconHelper.loadAppIcon(requireContext(), ivAppIconPreview);
                    Toast.makeText(requireContext(),
                            R.string.settings_app_icon_applied, Toast.LENGTH_SHORT).show();
                });
    }

    /**
     * 弹出"定制专属图标"说明：引导用户把图片发到邮箱/微信，
     * 由我们手动打包成专属安装包后回发（图片仅用于生成定制 APK）。
     */
    private void showCustomIconGuide() {
        // 联系方式从 assets/app_config.json 读取，代码与资源里不再保存个人信息
        AppConfig config = AppConfig.get(requireContext());
        AppDialogFactory.showLongText(requireContext(),
                getString(R.string.custom_icon_guide_title),
                getString(R.string.custom_icon_guide_message,
                        config.getContactEmail(),
                        config.getContactWechat()),
                R.string.dialog_i_know);
    }

    /**
     * 定制包的图标选择弹窗：直接列出用户图片集（第一张即默认图标）。
     * <p>
     * 定制包打包时已把"默认应用图标"和"预设风格入口"的图标资源都换成用户图片，
     * 因此第一张图就是默认图标，不需要再单独列出"默认图标"；
     * 前几张图片直接切换对应入口即可真正替换桌面图标；图片数量超过入口数时，
     * 才退回"自定义图片 + 桌面快捷方式"方案。
     * <p>
     * 长按任意一项可重命名该图片。
     *
     * @param bundled 打包图标集的 asset 路径列表
     */
    private void showBundledIconDialog(List<String> bundled) {
        List<String> labels = AppIconHelper.getBundledIconLabels(requireContext());

        // 当前选中：相册自定义图片优先；否则看当前入口对应哪张打包图片；再否则第一张
        int bundledIndex = AppIconHelper.getBundledIconIndex(requireContext());
        int checkedIndex = 0;
        if (bundledIndex >= 0 && bundledIndex < labels.size()) {
            checkedIndex = bundledIndex;
        } else {
            int currentStyle = AppIconHelper.getCurrentStyle(requireContext());
            for (int i = 0; i < bundled.size(); i++) {
                if (AppIconHelper.bundledIndexToStyle(i) == currentStyle) {
                    checkedIndex = i;
                    break;
                }
            }
        }

        Dialog dialog = AppDialogFactory.showSingleChoice(requireContext(),
                getString(R.string.settings_app_icon_title),
                labels.toArray(new String[0]), checkedIndex,
                which -> {
                    int index = which;
                    int style = AppIconHelper.bundledIndexToStyle(index);
                    if (style >= 0) {
                        // 切换入口：桌面图标与启动页图标都会换成该图片
                        AppIconHelper.clearCustomIcon(requireContext());
                        AppIconHelper.applyStyle(requireContext(), style);
                        AppIconHelper.loadAppIcon(requireContext(), ivAppIconPreview);
                        Toast.makeText(requireContext(),
                                R.string.settings_app_icon_applied, Toast.LENGTH_SHORT).show();
                        return;
                    }

                    // 图片数量超过预设入口数量时，退回自定义图片 + 桌面快捷方式方案
                    try {
                        AppIconHelper.saveBundledIconAsCustom(requireContext(),
                                bundled.get(index), index);
                        AppIconHelper.loadAppIcon(requireContext(), ivAppIconPreview);
                        Toast.makeText(requireContext(),
                                R.string.settings_app_icon_custom_applied, Toast.LENGTH_SHORT).show();
                        showShortcutDialog();
                    } catch (Exception e) {
                        Toast.makeText(requireContext(),
                                getString(R.string.settings_app_icon_custom_failed, e.getMessage()),
                                Toast.LENGTH_SHORT).show();
                    }
                },
                // 长按重命名
                this::showBundledIconRenameInput);

        // 提示用户可以长按改名
        AppDialogFactory.setSubtitle(dialog, getString(R.string.icon_rename_longpress_hint));
    }

    /**
     * 输入新的图标名称并保存，保存后重新打开图标弹窗回显。
     *
     * @param index 图片下标
     */
    private void showBundledIconRenameInput(int index) {
        View content = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_text_input, null);
        EditText input = content.findViewById(R.id.et_input);
        List<String> labels = AppIconHelper.getBundledIconLabels(requireContext());
        if (index < labels.size()) {
            input.setText(labels.get(index));
            input.setSelection(input.getText().length());
        }

        AppDialogFactory.showForm(requireContext(),
                getString(R.string.icon_rename_title),
                content,
                R.string.dialog_save, R.string.dialog_cancel,
                dialog -> {
                    String label = input.getText().toString().trim();
                    if (label.isEmpty()) {
                        Toast.makeText(requireContext(),
                                R.string.icon_rename_empty, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    AppIconHelper.setBundledIconLabel(requireContext(), index, label);
                    Toast.makeText(requireContext(),
                            R.string.icon_rename_saved, Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                    showBundledIconDialog(AppIconHelper.getBundledIconAssets(requireContext()));
                });
    }

    /**
     * 打开系统图片选择器，供用户挑选自定义图标图片。
     */
    private void pickCustomIconImage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        try {
            startActivityForResult(intent, REQUEST_CUSTOM_ICON);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(requireContext(), R.string.settings_no_file_picker, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 选择天气地点：跟随系统定位，或手动指定城市。
     * <p>
     * 手动指定城市后，即使没有定位权限也能获取该城市的天气；
     * 切换后清空天气缓存，保证下次展示立刻使用新地点。
     */
    private void showWeatherLocationDialog() {
        String[] items = WeatherLocationHelper.getOptionNames(requireContext());
        int checked = WeatherLocationHelper.getOptionIndex(requireContext());
        AppDialogFactory.showSingleChoice(requireContext(),
                getString(R.string.settings_weather_location), items, checked, which -> {
                    if (which == 0) {
                        WeatherLocationHelper.setDeviceMode(requireContext());
                    } else {
                        WeatherLocationHelper.setCity(requireContext(),
                                WeatherLocationHelper.CITIES[which - 1]);
                    }
                    WeatherCacheHelper.clear(requireContext());
                    tvWeatherLocation.setText(
                            WeatherLocationHelper.getDisplayName(requireContext()));
                    // 切回跟随定位时重新解析城市名，补全"跟随系统定位（xx）"
                    if (which == 0) {
                        WeatherLocationHelper.resolveDeviceCityName(requireContext(), cityName -> {
                            if (!isAdded() || tvWeatherLocation == null) return;
                            tvWeatherLocation.setText(
                                    WeatherLocationHelper.getDisplayName(requireContext()));
                        });
                    }
                });
    }

    /**
     * 弹出主题模式选择弹窗：跟随系统 / 浅色模式 / 深色模式。
     * <p>
     * 选择后调用 {@link ThemeHelper#saveAndApply}，AppCompat 会自动重建 Activity，
     * 设置页重建后 {@link #initViews(View)} 会重新读取偏好并刷新展示。
     */
    private void showThemeModeDialog() {
        int current = ThemeHelper.getSavedMode(requireContext());
        String[] items = {
                getString(R.string.settings_theme_follow_system),
                getString(R.string.settings_theme_light),
                getString(R.string.settings_theme_dark)
        };
        AppDialogFactory.showSingleChoice(requireContext(),
                getString(R.string.settings_theme_mode_dialog_title), items, current, which -> {
                    if (which == current) {
                        // 与当前模式一致，无需触发重建
                        return;
                    }
                    ThemeHelper.saveAndApply(requireContext(), which);
                    // 从“跟随系统”切到“浅色/深色”时，若系统当前恰好就是目标明暗，
                    // AppCompat 不会重建 Activity，界面不会自动刷新，这里手动同步展示文案
                    tvThemeMode.setText(getThemeModeName(which));
                });
    }

    /**
     * 弹出建议语气选择弹窗：温柔 / 简洁 / 元气。
     * <p>语气只影响文案措辞，保存后下次打开每日建议立即生效，无需重启页面。
     */
    private void showSuggestionToneDialog() {
        final String[] tones = {
                SuggestionToneStore.TONE_GENTLE,
                SuggestionToneStore.TONE_BRIEF,
                SuggestionToneStore.TONE_CHEERFUL
        };
        String[] items = {
                getString(R.string.suggestion_tone_gentle),
                getString(R.string.suggestion_tone_brief),
                getString(R.string.suggestion_tone_cheerful)
        };
        String currentTone = SuggestionToneStore.getTone(requireContext());
        int checked = 0;
        for (int i = 0; i < tones.length; i++) {
            if (tones[i].equals(currentTone)) {
                checked = i;
                break;
            }
        }

        AppDialogFactory.showSingleChoice(requireContext(),
                getString(R.string.settings_suggestion_tone_title), items, checked, which -> {
                    SuggestionToneStore.setTone(requireContext(), tones[which]);
                    tvSuggestionTone.setText(getToneName(tones[which]));
                });
    }

    /**
     * 把语气值映射为展示文案。
     *
     * @param tone 语气值
     * @return 对应的中文名称
     */
    private String getToneName(String tone) {
        if (SuggestionToneStore.TONE_BRIEF.equals(tone)) {
            return getString(R.string.suggestion_tone_brief);
        }
        if (SuggestionToneStore.TONE_CHEERFUL.equals(tone)) {
            return getString(R.string.suggestion_tone_cheerful);
        }
        return getString(R.string.suggestion_tone_gentle);
    }

    /**
     * 把主题模式映射为展示文案。
     *
     * @param mode {@link ThemeHelper#MODE_SYSTEM} / {@link ThemeHelper#MODE_LIGHT} / {@link ThemeHelper#MODE_DARK}
     * @return 主题模式名称
     */
    private String getThemeModeName(int mode) {
        switch (mode) {
            case ThemeHelper.MODE_LIGHT:
                return getString(R.string.settings_theme_light);
            case ThemeHelper.MODE_DARK:
                return getString(R.string.settings_theme_dark);
            default:
                return getString(R.string.settings_theme_follow_system);
        }
    }

    /**
     * 展示隐私政策内容。
     */
    private void showPrivacyPolicy() {
        AppDialogFactory.showLongText(requireContext(),
                getString(R.string.settings_privacy),
                getString(R.string.settings_privacy_policy_content),
                R.string.dialog_i_know);
    }

    /**
     * 展示用户协议内容。
     */
    private void showUserAgreement() {
        AppDialogFactory.showLongText(requireContext(),
                getString(R.string.settings_agreement),
                getString(R.string.settings_user_agreement_content),
                R.string.dialog_i_know);
    }

    /**
     * 从其它页面返回设置页时重新加载数据，保证展示的是最新记录。
     */
    @Override
    public void onResume() {
        super.onResume();
        loadData();
        // 系统确认框关闭后回到页面：延迟检查快捷方式是否真正创建
        if (shortcutDialogPaused && shortcutRequestPending) {
            shortcutDialogPaused = false;
            requireView().postDelayed(this::checkShortcutPinned, 600L);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (shortcutRequestPending) {
            shortcutDialogPaused = true;
        }
    }

    /**
     * 格式化“天数 + 单位”的展示文案。
     *
     * @param days 天数
     * @return 形如“28 天”的文案
     */
    private String formatDays(int days) {
        return getString(R.string.settings_flow_label_with_unit, days);
    }

    /**
     * 把提醒时间格式化为 HH:mm。
     *
     * @param hour   小时（24 小时制）
     * @param minute 分钟
     * @return 形如“09:00”的文本
     */
    private String formatReminderTime(int hour, int minute) {
        return String.format(Locale.getDefault(), "%02d:%02d", hour, minute);
    }
}
