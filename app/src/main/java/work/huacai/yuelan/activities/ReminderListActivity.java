package work.huacai.yuelan.activities;

import android.Manifest;
import android.app.AlarmManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.slider.Slider;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

import work.huacai.yuelan.R;
import work.huacai.yuelan.adapter.ReminderAdapter;
import work.huacai.yuelan.dao.ReminderDao;
import work.huacai.yuelan.dao.impl.MenstrualCycleRecordDaoImpl;
import work.huacai.yuelan.dao.impl.ReminderDaoImpl;
import work.huacai.yuelan.helper.CycleCalculator;
import work.huacai.yuelan.helper.ReminderAlarmScheduler;
import work.huacai.yuelan.helper.ReminderCycleHelper;
import work.huacai.yuelan.helper.ReminderNotificationHelper;
import work.huacai.yuelan.model.MenstrualCycle;
import work.huacai.yuelan.model.MenstrualCycleRecord;
import work.huacai.yuelan.model.Reminder;
import work.huacai.yuelan.widget.AppDialogFactory;

/**
 * 自定义提醒管理页。
 * <p>
 * 列表展示全部提醒，支持新增、编辑、删除与启用/停用；
 * 每条提醒包含标题、备注、日期（yyyyMMdd）与时间（HH:mm），
 * 数据写入 reminder 表，首页与日历会读取同一份数据展示。
 */
public class ReminderListActivity extends AppCompatActivity implements ReminderAdapter.Listener {

    /** 日期展示格式 */
    private static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat("yyyy年M月d日", Locale.getDefault());
    /** “指定多个日期”标签的短日期格式 */
    private static final SimpleDateFormat MULTI_DATE_CHIP_FORMAT =
            new SimpleDateFormat("MM月dd日", Locale.getDefault());

    /** 请求 Android 13+ 通知权限 */
    private static final int REQUEST_NOTIFICATION_PERMISSION = 2101;
    /** 提醒设置偏好文件（与设置页共用） */
    private static final String PREF_REMINDER = "reminder_settings";
    /** 是否已展示过首次开启提醒的系统权限引导 */
    private static final String KEY_REMINDER_SETUP_GUIDED = "reminder_setup_guided";

    private final ReminderDao reminderDao = new ReminderDaoImpl();
    private ReminderAdapter adapter;
    private TextView tvEmpty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reminder_list);

        tvEmpty = findViewById(R.id.tv_empty);
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.fab_add).setOnClickListener(v -> showEditor(null));

        RecyclerView recyclerView = findViewById(R.id.rv_reminders);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ReminderAdapter(this);
        recyclerView.setAdapter(adapter);

        // 通知渠道需在发通知前创建；Android 13+ 还需拿到通知权限
        ReminderNotificationHelper.ensureChannel(this);
        requestNotificationPermissionIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadData();
        refreshExactAlarmBanner();
    }

    /**
     * 刷新「精确闹钟未授权」提示。
     * <p>未获得「闹钟与提醒」权限时，系统会把闹钟放进最长一小时的窗口里延后送达，
     * 用户会感觉“到点没提醒”，因此常驻一条可点击的提示直到授权。
     */
    private void refreshExactAlarmBanner() {
        TextView warning = findViewById(R.id.tv_exact_alarm_warning);
        AlarmManager alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
        boolean exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || (alarmManager != null && alarmManager.canScheduleExactAlarms());
        warning.setVisibility(exact ? View.GONE : View.VISIBLE);
        warning.setOnClickListener(v -> openReminderPermissionSettings());
    }

    /** 重新读取提醒列表并刷新空状态 */
    private void loadData() {
        List<Reminder> list = reminderDao.selectAll();
        adapter.submitList(list);
        boolean empty = list.isEmpty();
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onEdit(Reminder reminder) {
        showEditor(reminder);
    }

    @Override
    public void onDelete(Reminder reminder) {
        AppDialogFactory.showConfirm(this,
                getString(R.string.reminder_delete_title),
                getString(R.string.reminder_delete_message),
                R.string.dialog_confirm, R.string.dialog_cancel,
                () -> {
                    reminderDao.delete(reminder.getId());
                    // 同步取消闹钟与已发出的通知，避免删除后仍被提醒
                    ReminderAlarmScheduler.cancel(this, reminder.getId());
                    loadData();
                });
    }

    @Override
    public void onToggle(Reminder reminder, boolean enabled) {
        reminderDao.setEnabled(reminder.getId(), enabled);
        reminder.setEnabled(enabled);
        // 停用即取消闹钟，启用则按当前日期时间重新排一次
        if (enabled) {
            boolean scheduled = ReminderAlarmScheduler.schedule(this, reminder);
            if (!scheduled) {
                Toast.makeText(this, R.string.reminder_time_passed, Toast.LENGTH_SHORT).show();
            }
        } else {
            ReminderAlarmScheduler.cancel(this, reminder.getId());
        }
    }

    /**
     * 打开新增 / 编辑弹窗。
     * <p>
     * 提醒方式分三类：「自选日期」支持仅一次 / 每天 / 每周 / 每月 / 间隔五种重复规则；
     * 「周期节点」在每个周期的指定节点触发一次；「阶段区间」在某一个阶段内的每一天
     * 同一时间提醒（如整个黄体期每天吃药）。
     * <p>
     * 顶部摘要卡随选择实时刷新，表单分区只展开当前方式需要的设置，避免弹窗过长。
     *
     * @param reminder 为 null 表示新增，否则编辑该提醒
     */
    private void showEditor(Reminder reminder) {
        View content = LayoutInflater.from(this)
                .inflate(R.layout.dialog_edit_reminder, null);
        EditText etTitle = content.findViewById(R.id.et_title);
        EditText etMessage = content.findViewById(R.id.et_message);
        TextView tvDateLabel = content.findViewById(R.id.tv_date_label);
        TextView tvDate = content.findViewById(R.id.tv_date);
        TextView tvTime = content.findViewById(R.id.tv_time);
        SwitchCompat switchEnabled = content.findViewById(R.id.switch_enabled);
        SwitchCompat switchFullScreen = content.findViewById(R.id.switch_full_screen);
        TextView tvFullScreenWarning = content.findViewById(R.id.tv_full_screen_warning);
        View layoutRingDuration = content.findViewById(R.id.layout_ring_duration);
        ViewGroup layoutRingDurations = content.findViewById(R.id.layout_ring_durations);
        TextView tvModeDate = content.findViewById(R.id.tv_mode_date);
        TextView tvModeCycle = content.findViewById(R.id.tv_mode_cycle);
        TextView tvModePhase = content.findViewById(R.id.tv_mode_phase);
        View layoutDateMode = content.findViewById(R.id.layout_date_mode);
        View layoutCycleMode = content.findViewById(R.id.layout_cycle_mode);
        View layoutPhaseMode = content.findViewById(R.id.layout_phase_mode);
        View layoutRepeatEnd = content.findViewById(R.id.layout_repeat_end);
        View layoutStartDate = content.findViewById(R.id.layout_start_date);
        View layoutMultiDate = content.findViewById(R.id.layout_multi_date);
        View layoutWeekdays = content.findViewById(R.id.layout_weekdays);
        View layoutMonthDay = content.findViewById(R.id.layout_month_day);
        View layoutInterval = content.findViewById(R.id.layout_interval);
        ViewGroup layoutRepeatRules = content.findViewById(R.id.layout_repeat_rules);
        ViewGroup layoutEndRules = content.findViewById(R.id.layout_end_rules);
        ViewGroup layoutWeekdayChips = content.findViewById(R.id.layout_weekday_chips);
        ViewGroup layoutMultiDateChips = content.findViewById(R.id.layout_multi_date_chips);
        TextView tvIntervalValue = content.findViewById(R.id.tv_interval_value);
        TextView tvMonthDay = content.findViewById(R.id.tv_month_day);
        TextView tvSummaryTime = content.findViewById(R.id.tv_summary_time);
        TextView tvSummaryBadge = content.findViewById(R.id.tv_summary_badge);
        TextView tvSummaryTitle = content.findViewById(R.id.tv_summary_title);
        TextView tvSummarySub = content.findViewById(R.id.tv_summary_sub);
        TextView[] phaseViews = {
                content.findViewById(R.id.tv_phase_0),
                content.findViewById(R.id.tv_phase_1),
                content.findViewById(R.id.tv_phase_2),
                content.findViewById(R.id.tv_phase_3)
        };
        Slider sliderInterval = content.findViewById(R.id.slider_interval);
        TextView[] nodeViews = {
                content.findViewById(R.id.tv_node_period_start),
                content.findViewById(R.id.tv_node_period_end),
                content.findViewById(R.id.tv_node_follicle),
                content.findViewById(R.id.tv_node_ovulation),
                content.findViewById(R.id.tv_node_luteal),
                content.findViewById(R.id.tv_node_before_period)
        };
        final int[] nodeValues = {
                Reminder.NODE_PERIOD_START, Reminder.NODE_PERIOD_END,
                Reminder.NODE_FOLLICLE_START, Reminder.NODE_OVULATION_START,
                Reminder.NODE_LUTEAL_START, Reminder.NODE_BEFORE_PERIOD
        };

        // ===== 表单状态 =====
        final int modeDate = 0;
        final int modeCycle = 1;
        final int modePhase = 2;
        Calendar startDate = Calendar.getInstance();
        final int[] timeValue = {9, 0};
        final int[] mode = {modeDate};
        final int[] cycleNode = {Reminder.NODE_PERIOD_START};
        final int[] phaseIndex = {3};
        final int[] repeatType = {Reminder.REPEAT_NONE};
        final int[] endMonths = {0};
        final boolean[] weekdays = new boolean[8];
        final int[] monthDay = {startDate.get(Calendar.DAY_OF_MONTH)};
        final int[] interval = {2};
        /** 强提醒响铃时长（分钟，0 = 一直响）；新建默认 3 分钟 */
        final int[] ringDuration = {3};
        /** “指定多个日期”模式下的已选日期（yyyyMMdd，升序） */
        final List<Integer> multiDates = new ArrayList<>();

        // 各阶段实际天数，用于在阶段卡片上展示“黄体期 · 12天”
        final int[] phaseDays = loadPhaseDays();

        if (reminder != null) {
            etTitle.setText(reminder.getTitle());
            etMessage.setText(reminder.getMessage());
            switchEnabled.setChecked(reminder.isEnabled());
            switchFullScreen.setChecked(reminder.isAlarm());
            ringDuration[0] = reminder.getRingDuration();
            if (reminder.getAlarmTime() != null) {
                String[] parts = reminder.getAlarmTime().split(":");
                if (parts.length == 2) {
                    try {
                        timeValue[0] = Integer.parseInt(parts[0]);
                        timeValue[1] = Integer.parseInt(parts[1]);
                    } catch (NumberFormatException ignored) {
                        // 时间格式异常时保留默认值
                    }
                }
            }
            if (reminder.isSpanMode()) {
                mode[0] = modePhase;
                int index = ReminderCycleHelper.spanPhaseIndex(reminder.getCycleNode());
                if (index >= 0) {
                    phaseIndex[0] = index;
                }
            } else if (reminder.isCycleMode()) {
                mode[0] = modeCycle;
                cycleNode[0] = reminder.getCycleNode();
            } else {
                Date parsed = CycleCalculator.parseDateId(reminder.getDateId());
                if (parsed != null) {
                    startDate.setTime(parsed);
                }
                repeatType[0] = reminder.getRepeatType();
                if (repeatType[0] == Reminder.REPEAT_WEEKLY) {
                    for (int day : parseInts(reminder.getRepeatConfig())) {
                        if (day >= Calendar.SUNDAY && day <= Calendar.SATURDAY) {
                            weekdays[day] = true;
                        }
                    }
                } else if (repeatType[0] == Reminder.REPEAT_MONTHLY) {
                    List<Integer> days = parseInts(reminder.getRepeatConfig());
                    if (!days.isEmpty()) {
                        monthDay[0] = days.get(0);
                    }
                } else if (repeatType[0] == Reminder.REPEAT_INTERVAL) {
                    int value = parseInterval(reminder.getRepeatConfig());
                    if (value >= 2) {
                        interval[0] = Math.min(value, 30);
                    }
                } else if (repeatType[0] == Reminder.REPEAT_MULTI_DATE) {
                    multiDates.addAll(parseInts(reminder.getRepeatConfig()));
                    Collections.sort(multiDates);
                }
                if (repeatType[0] != Reminder.REPEAT_NONE && reminder.getRepeatEndDay() > 0) {
                    endMonths[0] = nearestEndMonths(startDate, reminder.getRepeatEndDay());
                }
            }
        } else {
            switchEnabled.setChecked(true);
            // 新建提醒默认按强提醒（全屏闹钟）处理，用户可随时关闭
            switchFullScreen.setChecked(true);
            weekdays[startDate.get(Calendar.DAY_OF_WEEK)] = true;
        }
        tvTime.setText(formatTime(timeValue));
        etTitle.setSelection(etTitle.getText().length());

        final Runnable[] refresh = new Runnable[1];

        // ===== 动态标签：重复规则 / 结束条件 / 星期 / 每月日期 =====
        final int chipPadH = dpToPx(14);
        final int chipPadV = dpToPx(7);
        final int[] repeatValues = {Reminder.REPEAT_NONE, Reminder.REPEAT_DAILY,
                Reminder.REPEAT_WEEKLY, Reminder.REPEAT_MONTHLY, Reminder.REPEAT_INTERVAL,
                Reminder.REPEAT_MULTI_DATE};
        final int[] repeatLabels = {R.string.reminder_repeat_once, R.string.reminder_repeat_daily,
                R.string.reminder_repeat_weekly, R.string.reminder_repeat_monthly,
                R.string.reminder_repeat_interval, R.string.reminder_repeat_multi_date};
        final List<TextView> repeatChips = new ArrayList<>();
        for (int i = 0; i < repeatValues.length; i++) {
            final int index = i;
            TextView chip = createChip(getString(repeatLabels[i]), chipPadH, chipPadV);
            chip.setOnClickListener(v -> {
                repeatType[0] = repeatValues[index];
                refresh[0].run();
            });
            layoutRepeatRules.addView(chip);
            repeatChips.add(chip);
        }

        final int[] endOptions = {0, 1, 3, 6, 12};
        final int[] endLabels = {R.string.reminder_repeat_forever, R.string.reminder_repeat_months_1,
                R.string.reminder_repeat_months_3, R.string.reminder_repeat_months_6,
                R.string.reminder_repeat_months_12};
        final List<TextView> endChips = new ArrayList<>();
        for (int i = 0; i < endOptions.length; i++) {
            final int index = i;
            TextView chip = createChip(getString(endLabels[i]), chipPadH, chipPadV);
            chip.setOnClickListener(v -> {
                endMonths[0] = endOptions[index];
                refresh[0].run();
            });
            layoutEndRules.addView(chip);
            endChips.add(chip);
        }

        // 强提醒响铃时长：1 / 3 / 5 / 10 分钟，或一直响到用户关闭
        final int[] ringOptions = {1, 3, 5, 10, Reminder.RING_FOREVER};
        final int[] ringLabels = {R.string.reminder_ring_1, R.string.reminder_ring_3,
                R.string.reminder_ring_5, R.string.reminder_ring_10,
                R.string.reminder_ring_forever};
        final List<TextView> ringChips = new ArrayList<>();
        for (int i = 0; i < ringOptions.length; i++) {
            final int index = i;
            TextView chip = createChip(getString(ringLabels[i]), dpToPx(10), chipPadV);
            chip.setOnClickListener(v -> {
                ringDuration[0] = ringOptions[index];
                refresh[0].run();
            });
            layoutRingDurations.addView(chip);
            ringChips.add(chip);
        }

        final int[] weekdayOrder = {Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
                Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY};
        final int[] weekdayLabels = {R.string.reminder_weekday_mon, R.string.reminder_weekday_tue,
                R.string.reminder_weekday_wed, R.string.reminder_weekday_thu,
                R.string.reminder_weekday_fri, R.string.reminder_weekday_sat,
                R.string.reminder_weekday_sun};
        final List<TextView> weekdayChips = new ArrayList<>();
        for (int i = 0; i < weekdayOrder.length; i++) {
            final int day = weekdayOrder[i];
            TextView chip = createChip(getString(weekdayLabels[i]), dpToPx(9), chipPadV);
            chip.setMinWidth(dpToPx(38));
            chip.setOnClickListener(v -> {
                weekdays[day] = !weekdays[day];
                refresh[0].run();
            });
            layoutWeekdayChips.addView(chip);
            weekdayChips.add(chip);
        }

        // 每月只保留一个可点击的日期值，不再铺 31 个标签
        tvMonthDay.setOnClickListener(v -> {
            MaterialDatePicker<Long> picker = MaterialDatePicker.Builder.datePicker()
                    .setTitleText(R.string.reminder_month_day_pick_title)
                    .setSelection(MaterialDatePicker.todayInUtcMilliseconds())
                    .build();
            picker.addOnPositiveButtonClickListener(selection -> {
                if (selection == null) {
                    return;
                }
                Calendar picked = Calendar.getInstance();
                picked.setTime(CycleCalculator.localFromUtc(selection));
                monthDay[0] = picked.get(Calendar.DAY_OF_MONTH);
                refresh[0].run();
            });
            picker.show(getSupportFragmentManager(), "reminder_month_day_picker");
        });

        sliderInterval.setValue(interval[0]);
        sliderInterval.addOnChangeListener((slider, value, fromUser) -> {
            interval[0] = (int) value;
            refresh[0].run();
        });

        // 所有状态变化统一在这里刷新：选中态、分区显隐、顶部摘要
        refresh[0] = () -> {
            applyChipStyle(tvModeDate, mode[0] == modeDate);
            applyChipStyle(tvModeCycle, mode[0] == modeCycle);
            applyChipStyle(tvModePhase, mode[0] == modePhase);
            layoutCycleMode.setVisibility(mode[0] == modeCycle ? View.VISIBLE : View.GONE);
            layoutPhaseMode.setVisibility(mode[0] == modePhase ? View.VISIBLE : View.GONE);
            layoutDateMode.setVisibility(mode[0] == modeDate ? View.VISIBLE : View.GONE);
            for (int i = 0; i < nodeViews.length; i++) {
                applyChipStyle(nodeViews[i], nodeValues[i] == cycleNode[0]);
            }
            for (int i = 0; i < phaseViews.length; i++) {
                String name = MenstrualCycle.getByIndex(i).getDisplayName(this);
                phaseViews[i].setText(phaseDays != null && phaseDays[i] > 0
                        ? getString(R.string.reminder_phase_card_format, name, phaseDays[i])
                        : name);
                applyChipStyle(phaseViews[i], i == phaseIndex[0]);
            }
            for (int i = 0; i < repeatChips.size(); i++) {
                applyChipStyle(repeatChips.get(i), repeatValues[i] == repeatType[0]);
            }
            for (int i = 0; i < endChips.size(); i++) {
                applyChipStyle(endChips.get(i), endOptions[i] == endMonths[0]);
            }
            for (int i = 0; i < weekdayChips.size(); i++) {
                applyChipStyle(weekdayChips.get(i), weekdays[weekdayOrder[i]]);
            }
            for (int i = 0; i < ringChips.size(); i++) {
                applyChipStyle(ringChips.get(i), ringOptions[i] == ringDuration[0]);
            }
            // 响铃时长只对强提醒有意义，关闭强提醒时整行隐藏
            layoutRingDuration.setVisibility(switchFullScreen.isChecked()
                    ? View.VISIBLE : View.GONE);

            boolean repeat = repeatType[0] != Reminder.REPEAT_NONE;
            boolean showDateOptions = mode[0] == modeDate;
            boolean multiDate = showDateOptions && repeatType[0] == Reminder.REPEAT_MULTI_DATE;
            // 指定多个日期时开始日期由已选日期决定，隐藏开始日期与结束条件
            layoutStartDate.setVisibility(multiDate ? View.GONE : View.VISIBLE);
            layoutMultiDate.setVisibility(multiDate ? View.VISIBLE : View.GONE);
            layoutRepeatEnd.setVisibility(showDateOptions && repeat && !multiDate
                    ? View.VISIBLE : View.GONE);
            if (multiDate) {
                rebuildMultiDateChips(layoutMultiDateChips, multiDates,
                        chipPadH, chipPadV, refresh[0]);
            }
            layoutWeekdays.setVisibility(showDateOptions
                    && repeatType[0] == Reminder.REPEAT_WEEKLY ? View.VISIBLE : View.GONE);
            layoutMonthDay.setVisibility(showDateOptions
                    && repeatType[0] == Reminder.REPEAT_MONTHLY ? View.VISIBLE : View.GONE);
            layoutInterval.setVisibility(showDateOptions
                    && repeatType[0] == Reminder.REPEAT_INTERVAL ? View.VISIBLE : View.GONE);

            tvDateLabel.setText(repeat
                    ? R.string.reminder_start_date_label : R.string.reminder_date_label);
            tvDate.setText(DATE_FORMAT.format(startDate.getTime()));
            tvIntervalValue.setText(getString(R.string.reminder_interval_value, interval[0]));
            tvMonthDay.setText(getString(R.string.reminder_month_day_value, monthDay[0]));

            // 强提醒开启但系统权限不足时，直接把原因与修复入口放在开关下面
            boolean needFullScreen = !ReminderNotificationHelper.canUseFullScreenIntent(this);
            boolean needOverlay = !ReminderNotificationHelper.canDrawOverlays(this);
            if (switchFullScreen.isChecked() && (needFullScreen || needOverlay)) {
                tvFullScreenWarning.setVisibility(View.VISIBLE);
                tvFullScreenWarning.setText(needFullScreen
                        ? R.string.reminder_full_screen_warning_notification
                        : R.string.reminder_full_screen_warning_overlay);
            } else {
                tvFullScreenWarning.setVisibility(View.GONE);
            }

            bindSummary(tvSummaryTime, tvSummaryBadge, tvSummaryTitle, tvSummarySub,
                    mode[0], modePhase, modeCycle, phaseIndex[0], cycleNode[0], repeatType[0],
                    startDate, endMonths[0], multiDates, weekdays, monthDay[0], interval[0],
                    timeValue);
        };
        refresh[0].run();

        tvModeDate.setOnClickListener(v -> {
            mode[0] = modeDate;
            refresh[0].run();
        });
        tvModeCycle.setOnClickListener(v -> {
            mode[0] = modeCycle;
            refresh[0].run();
        });
        tvModePhase.setOnClickListener(v -> {
            mode[0] = modePhase;
            refresh[0].run();
        });
        for (int i = 0; i < nodeViews.length; i++) {
            final int index = i;
            nodeViews[i].setOnClickListener(v -> {
                cycleNode[0] = nodeValues[index];
                refresh[0].run();
            });
        }
        for (int i = 0; i < phaseViews.length; i++) {
            final int index = i;
            phaseViews[i].setOnClickListener(v -> {
                mode[0] = modePhase;
                phaseIndex[0] = index;
                refresh[0].run();
            });
        }

        // 强提醒开关变化时刷新权限提示；点击提示直达对应系统设置
        switchFullScreen.setOnCheckedChangeListener((buttonView, isChecked) -> refresh[0].run());
        tvFullScreenWarning.setOnClickListener(v -> {
            if (!ReminderNotificationHelper.canUseFullScreenIntent(this)) {
                openFullScreenSettings();
            } else {
                openOverlaySettings();
            }
        });

        // 常用提醒快捷项：一键填充标题 / 备注 / 推荐时间（如黄体酮按医嘱晚上服用）；
        // 命中当前阶段的预设会排在前面并高亮，选中后填充品牌粉选中态。
        // 带阶段归属的预设（如黄体酮 → 黄体期）会顺带切到“阶段区间”，整个阶段每天提醒。
        bindPresets(content.findViewById(R.id.layout_presets), etTitle, etMessage, tvTime,
                timeValue, content.findViewById(R.id.tv_presets_hint), () -> refresh[0].run(),
                index -> {
                    mode[0] = modePhase;
                    phaseIndex[0] = index;
                    refresh[0].run();
                });

        tvDate.setOnClickListener(v -> {
            MaterialDatePicker<Long> picker = MaterialDatePicker.Builder.datePicker()
                    .setTitleText(R.string.fragment_setup_select_date_text)
                    .setSelection(CycleCalculator.toUtcMidnight(startDate))
                    .build();
            picker.addOnPositiveButtonClickListener(selection -> {
                if (selection == null) {
                    return;
                }
                startDate.setTime(CycleCalculator.localFromUtc(selection));
                refresh[0].run();
            });
            picker.show(getSupportFragmentManager(), "reminder_date_picker");
        });

        tvTime.setOnClickListener(v -> {
            MaterialTimePicker picker = new MaterialTimePicker.Builder()
                    .setTimeFormat(DateFormat.is24HourFormat(this)
                            ? TimeFormat.CLOCK_24H : TimeFormat.CLOCK_12H)
                    .setHour(timeValue[0])
                    .setMinute(timeValue[1])
                    .setTitleText(R.string.settings_reminder_time)
                    .build();
            picker.addOnPositiveButtonClickListener(dialog -> {
                timeValue[0] = picker.getHour();
                timeValue[1] = picker.getMinute();
                tvTime.setText(formatTime(timeValue));
                refresh[0].run();
            });
            picker.show(getSupportFragmentManager(), "reminder_time_picker");
        });

        refresh[0].run();

        AppDialogFactory.showForm(this,
                getString(reminder == null
                        ? R.string.reminder_new_title : R.string.reminder_edit_title),
                content, R.string.dialog_save, R.string.dialog_cancel, dialog -> {
                    // 校验失败时直接 return，弹窗保持打开
                    String title = etTitle.getText().toString().trim();
                    if (title.isEmpty()) {
                        Toast.makeText(this, R.string.reminder_title_required, Toast.LENGTH_SHORT).show();
                        return;
                    }

                    String message = etMessage.getText().toString().trim();
                    String timeText = formatTime(timeValue);
                    boolean enabled = switchEnabled.isChecked();

                    if (mode[0] == modePhase) {
                        // 阶段区间：从阶段起点到下一阶段起点（不含）每天提醒
                        int[] nodes = ReminderCycleHelper.phaseSpanNodes(phaseIndex[0]);
                        if (nodes == null) {
                            return;
                        }
                        Reminder toSave = reminder != null ? reminder : new Reminder();
                        toSave.setTitle(title);
                        toSave.setMessage(message);
                        toSave.setReminderType("custom");
                        toSave.setTargetDay(0);
                        toSave.setCycleNode(nodes[0]);
                        toSave.setCycleNodeEnd(nodes[1]);
                        toSave.setRepeatType(Reminder.REPEAT_NONE);
                        toSave.setRepeatConfig(null);
                        toSave.setRepeatEndDay(0);
                        toSave.setAlarmTime(timeText);
                        toSave.setEnabled(enabled);
                        toSave.setAlarm(switchFullScreen.isChecked());
                        // 响铃时长只在强提醒下生效；关闭强提醒时保留设置，便于再次打开
                        toSave.setRingDuration(ringDuration[0]);
                        persistReminder(toSave);
                    } else if (mode[0] == modeCycle) {
                        // 按周期节点：单条记录，targetDay 不使用
                        Reminder toSave = reminder != null ? reminder : new Reminder();
                        toSave.setTitle(title);
                        toSave.setMessage(message);
                        toSave.setReminderType("custom");
                        toSave.setTargetDay(0);
                        toSave.setCycleNode(cycleNode[0]);
                        toSave.setCycleNodeEnd(Reminder.NODE_NONE);
                        toSave.setRepeatType(Reminder.REPEAT_NONE);
                        toSave.setRepeatConfig(null);
                        toSave.setRepeatEndDay(0);
                        toSave.setAlarmTime(timeText);
                        toSave.setEnabled(enabled);
                        toSave.setAlarm(switchFullScreen.isChecked());
                        // 响铃时长只在强提醒下生效；关闭强提醒时保留设置，便于再次打开
                        toSave.setRingDuration(ringDuration[0]);
                        persistReminder(toSave);
                    } else {
                        // 自选时间：一条记录保存重复规则，由调度器计算每次触发时间
                        int type = repeatType[0];
                        String config = null;
                        if (type == Reminder.REPEAT_WEEKLY) {
                            config = buildWeekdayConfig(weekdays);
                            if (config.isEmpty()) {
                                Toast.makeText(this, R.string.reminder_repeat_empty_weekdays,
                                        Toast.LENGTH_SHORT).show();
                                return;
                            }
                        } else if (type == Reminder.REPEAT_MONTHLY) {
                            config = String.valueOf(monthDay[0]);
                        } else if (type == Reminder.REPEAT_INTERVAL) {
                            config = String.valueOf(interval[0]);
                        } else if (type == Reminder.REPEAT_MULTI_DATE) {
                            if (multiDates.isEmpty()) {
                                Toast.makeText(this, R.string.reminder_repeat_empty_days,
                                        Toast.LENGTH_SHORT).show();
                                return;
                            }
                            config = buildMultiDateConfig(multiDates);
                        }

                        Reminder toSave = reminder != null ? reminder : new Reminder();
                        toSave.setTitle(title);
                        toSave.setMessage(message);
                        toSave.setReminderType("custom");
                        // 指定多个日期以最早日期作为系列起点，其余日期由 repeat_config 命中
                        toSave.setTargetDay(type == Reminder.REPEAT_MULTI_DATE
                                ? multiDates.get(0) : toDayId(startDate));
                        toSave.setCycleNode(Reminder.NODE_NONE);
                        toSave.setCycleNodeEnd(Reminder.NODE_NONE);
                        toSave.setRepeatType(type);
                        toSave.setRepeatConfig(config);
                        toSave.setRepeatEndDay(type == Reminder.REPEAT_NONE
                                || type == Reminder.REPEAT_MULTI_DATE
                                ? 0 : endDayId(startDate, endMonths[0]));
                        toSave.setAlarmTime(timeText);
                        toSave.setEnabled(enabled);
                        toSave.setAlarm(switchFullScreen.isChecked());
                        // 响铃时长只在强提醒下生效；关闭强提醒时保留设置，便于再次打开
                        toSave.setRingDuration(ringDuration[0]);
                        persistReminder(toSave);
                    }

                    Toast.makeText(this, R.string.reminder_saved, Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                    loadData();
                });
    }

    /**
     * 按选中状态刷新标签样式：选中为品牌粉底白字，未选中为描边文字色。
     * <p>同时清空可能残留的 backgroundTint，避免“推荐”样式覆盖选中态。
     *
     * @param chip     标签视图
     * @param selected 是否选中
     */
    private void applyChipStyle(TextView chip, boolean selected) {
        chip.setBackgroundResource(selected
                ? R.drawable.chip_selected_background : R.drawable.chip_unselected_background);
        chip.setBackgroundTintList(null);
        chip.setTextColor(ContextCompat.getColor(this,
                selected ? R.color.white : R.color.text_body));
    }

    /**
     * 创建一个可点击的品牌标签。
     *
     * @param text  标签文字
     * @param padH  左右内边距（px）
     * @param padV  上下内边距（px）
     * @return 已设置好基础样式的 TextView
     */
    private TextView createChip(String text, int padH, int padV) {
        TextView chip = new TextView(this);
        chip.setText(text);
        chip.setTextSize(13f);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(padH, padV, padH, padV);
        applyChipStyle(chip, false);
        // 间距交给 ChipGroup 的 chipSpacing，自己不再加 margin，避免叠加后间距过大
        return chip;
    }

    /**
     * 刷新顶部摘要卡：把当前选择翻译成「时间 + 节奏 + 补充说明」。
     * <p>这是弹窗里唯一一处“结果预览”，替代了原来底部单独的一块预览文本。
     */
    private void bindSummary(TextView tvSummaryTime, TextView tvSummaryBadge,
                             TextView tvSummaryTitle, TextView tvSummarySub,
                             int mode, int modePhase, int modeCycle, int phaseIndex, int cycleNode,
                             int repeatType, Calendar startDate, int endMonths,
                             List<Integer> multiDates, boolean[] weekdays,
                             int monthDay, int interval, int[] timeValue) {
        tvSummaryTime.setText(formatTime(timeValue));

        if (mode == modePhase) {
            int[] days = loadPhaseDays();
            String phaseName = MenstrualCycle.getByIndex(phaseIndex).getDisplayName(this);
            tvSummaryBadge.setText(R.string.reminder_summary_badge_daily);
            tvSummaryTitle.setText(getString(R.string.reminder_summary_phase_title, phaseName));
            tvSummarySub.setText(days != null && days[phaseIndex] > 0
                    ? getString(R.string.reminder_summary_phase_sub_days, days[phaseIndex])
                    : getString(R.string.reminder_summary_phase_sub));
            return;
        }

        if (mode == modeCycle) {
            tvSummaryBadge.setText(R.string.reminder_summary_badge_cycle);
            tvSummaryTitle.setText(nodeLabel(cycleNode));
            Date next = ReminderCycleHelper.nextNodeDate(cycleNode);
            tvSummarySub.setText(next != null
                    ? getString(R.string.reminder_summary_next_format, DATE_FORMAT.format(next))
                    : getString(R.string.reminder_cycle_pending_setup));
            return;
        }

        String startText = DATE_FORMAT.format(startDate.getTime());
        String untilText = endMonths > 0
                ? getString(R.string.reminder_summary_until_format,
                        DATE_FORMAT.format(endDate(startDate, endMonths).getTime()))
                : getString(R.string.reminder_summary_forever);
        switch (repeatType) {
            case Reminder.REPEAT_DAILY:
                tvSummaryBadge.setText(R.string.reminder_repeat_daily);
                tvSummaryTitle.setText(getString(R.string.reminder_summary_start_format, startText));
                tvSummarySub.setText(untilText);
                break;
            case Reminder.REPEAT_WEEKLY:
                tvSummaryBadge.setText(R.string.reminder_repeat_weekly);
                tvSummaryTitle.setText(joinWeekdayNames(weekdays));
                tvSummarySub.setText(untilText);
                break;
            case Reminder.REPEAT_MONTHLY:
                tvSummaryBadge.setText(R.string.reminder_repeat_monthly);
                tvSummaryTitle.setText(getString(R.string.reminder_summary_month_day_title, monthDay));
                tvSummarySub.setText(untilText);
                break;
            case Reminder.REPEAT_INTERVAL:
                tvSummaryBadge.setText(
                        getString(R.string.reminder_summary_badge_interval_format, interval));
                tvSummaryTitle.setText(getString(R.string.reminder_summary_start_format, startText));
                tvSummarySub.setText(untilText);
                break;
            case Reminder.REPEAT_MULTI_DATE:
                tvSummaryBadge.setText(R.string.reminder_repeat_multi_date);
                tvSummaryTitle.setText(getString(R.string.reminder_summary_multi_title,
                        multiDates.size()));
                tvSummarySub.setText("");
                break;
            default:
                tvSummaryBadge.setText(R.string.reminder_summary_badge_once);
                tvSummaryTitle.setText(startText);
                tvSummarySub.setText(R.string.reminder_summary_once_sub);
                break;
        }
    }

    /** 读取四个阶段的天数；没有周期设置时返回 null */
    private int[] loadPhaseDays() {
        MenstrualCycleRecord record = new MenstrualCycleRecordDaoImpl().selectLast();
        if (record == null || record.getStartTime() == null || record.getTotalCycleDay() <= 0) {
            return null;
        }
        return CycleCalculator.calculatePhaseDays(
                record.getMenstrualPeriodDay(), record.getTotalCycleDay());
    }

    /** 周期节点的展示名称 */
    private String nodeLabel(int cycleNode) {
        int res;
        switch (cycleNode) {
            case Reminder.NODE_PERIOD_END:
                res = R.string.reminder_node_period_end;
                break;
            case Reminder.NODE_FOLLICLE_START:
                res = R.string.reminder_node_follicle;
                break;
            case Reminder.NODE_OVULATION_START:
                res = R.string.reminder_node_ovulation;
                break;
            case Reminder.NODE_LUTEAL_START:
                res = R.string.reminder_node_luteal;
                break;
            case Reminder.NODE_BEFORE_PERIOD:
                res = R.string.reminder_node_before_period;
                break;
            case Reminder.NODE_PERIOD_START:
            default:
                res = R.string.reminder_node_period_start;
                break;
        }
        return getString(res);
    }

    /** 把选中的星期拼成“一、三、五” */
    private String joinWeekdayNames(boolean[] weekdays) {
        int[] order = {Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
                Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY};
        int[] labels = {R.string.reminder_weekday_mon, R.string.reminder_weekday_tue,
                R.string.reminder_weekday_wed, R.string.reminder_weekday_thu,
                R.string.reminder_weekday_fri, R.string.reminder_weekday_sat,
                R.string.reminder_weekday_sun};
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < order.length; i++) {
            if (!weekdays[order[i]]) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("、");
            }
            builder.append(getString(labels[i]));
        }
        return builder.toString();
    }

    /** 星期多选转 repeat_config（1=周日 … 7=周六，逗号分隔） */
    private String buildWeekdayConfig(boolean[] weekdays) {
        StringBuilder builder = new StringBuilder();
        for (int day = Calendar.SUNDAY; day <= Calendar.SATURDAY; day++) {
            if (!weekdays[day]) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(",");
            }
            builder.append(day);
        }
        return builder.toString();
    }

    /** Calendar 转 yyyyMMdd 整数 */
    private int toDayId(Calendar calendar) {
        return calendar.get(Calendar.YEAR) * 10000
                + (calendar.get(Calendar.MONTH) + 1) * 100
                + calendar.get(Calendar.DAY_OF_MONTH);
    }

    /** yyyyMMdd 整数转短日期文案（MM月dd日） */
    private String formatMultiDate(int dayId) {
        Calendar calendar = Calendar.getInstance();
        calendar.set(dayId / 10000, (dayId / 100) % 100 - 1, dayId % 100);
        return MULTI_DATE_CHIP_FORMAT.format(calendar.getTime());
    }

    /** 已选日期转 repeat_config（逗号分隔的 yyyyMMdd） */
    private String buildMultiDateConfig(List<Integer> dates) {
        StringBuilder builder = new StringBuilder();
        for (int dayId : dates) {
            if (builder.length() > 0) {
                builder.append(",");
            }
            builder.append(dayId);
        }
        return builder.toString();
    }

    /**
     * 重建“指定多个日期”的标签区：每个已选日期一个标签，点击即删除；
     * 末尾追加“添加日期”入口。ChipGroup 自动换行，避免标签被裁切。
     */
    private void rebuildMultiDateChips(ViewGroup container, List<Integer> dates,
                                       int padH, int padV, Runnable onChanged) {
        container.removeAllViews();
        for (int dayId : dates) {
            TextView chip = createChip(formatMultiDate(dayId) + " ×", padH, padV);
            chip.setOnClickListener(v -> {
                dates.remove(Integer.valueOf(dayId));
                onChanged.run();
            });
            container.addView(chip);
        }
        TextView add = createChip(getString(R.string.reminder_multi_date_add), padH, padV);
        add.setBackgroundTintList(null);
        add.setTextColor(ContextCompat.getColor(this, R.color.brand_primary));
        add.setOnClickListener(v -> openMultiDatePicker(dates, onChanged));
        container.addView(add);
    }

    /** 打开日期选择器，选中后加入已选日期列表（去重后升序） */
    private void openMultiDatePicker(List<Integer> dates, Runnable onChanged) {
        MaterialDatePicker<Long> picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText(R.string.reminder_multi_date_pick_title)
                .setSelection(MaterialDatePicker.todayInUtcMilliseconds())
                .build();
        picker.addOnPositiveButtonClickListener(selection -> {
            if (selection == null) {
                return;
            }
            Calendar picked = Calendar.getInstance();
            picked.setTime(CycleCalculator.localFromUtc(selection));
            int dayId = toDayId(picked);
            if (!dates.contains(dayId)) {
                dates.add(dayId);
                Collections.sort(dates);
            }
            onChanged.run();
        });
        picker.show(getSupportFragmentManager(), "reminder_multi_date_picker");
    }

    /** 按开始日期 + 月数计算重复结束日期；0 表示长期有效 */
    private int endDayId(Calendar startDate, int months) {
        if (months <= 0) {
            return 0;
        }
        return toDayId(endDate(startDate, months));
    }

    /** 开始日期加 N 个月后的 Calendar（保留日） */
    private Calendar endDate(Calendar startDate, int months) {
        Calendar end = (Calendar) startDate.clone();
        end.add(Calendar.MONTH, months);
        return end;
    }

    /** 把已有结束日期换算成最接近的快捷选项（长期/1/3/6/12 个月） */
    private int nearestEndMonths(Calendar startDate, int endDay) {
        Calendar end = Calendar.getInstance();
        end.set(endDay / 10000, (endDay / 100) % 100 - 1, endDay % 100, 0, 0, 0);
        int[] options = {0, 1, 3, 6, 12};
        int best = 0;
        long bestDiff = Long.MAX_VALUE;
        for (int option : options) {
            long diff = Math.abs(endDate(startDate, option).getTimeInMillis()
                    - end.getTimeInMillis());
            if (diff < bestDiff) {
                bestDiff = diff;
                best = option;
            }
        }
        return best;
    }

    /** 解析逗号分隔的整数配置，非法项忽略 */
    private List<Integer> parseInts(String config) {
        List<Integer> result = new ArrayList<>();
        if (config == null || config.trim().isEmpty()) {
            return result;
        }
        for (String part : config.split(",")) {
            try {
                result.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException ignored) {
                // 忽略单个非法值
            }
        }
        return result;
    }

    /** 解析间隔天数 */
    private int parseInterval(String config) {
        if (config == null) {
            return 0;
        }
        try {
            return Integer.parseInt(config.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 统一格式化 HH:mm */
    private String formatTime(int[] timeValue) {
        return String.format(Locale.getDefault(), "%02d:%02d", timeValue[0], timeValue[1]);
    }

    /**
     * 落库一条提醒并按开关状态安排/取消闹钟，附带通知权限与精确闹钟提示。
     */
    private void persistReminder(Reminder toSave) {
        if (toSave.getId() > 0) {
            reminderDao.update(toSave);
        } else {
            // insert 返回自增 id，必须回填，否则无法给这条提醒安排闹钟
            long newId = reminderDao.insert(toSave);
            if (newId <= 0) {
                // 落库失败：不排闹钟、不提示“已保存”，直接告知用户失败原因
                AppDialogFactory.showConfirm(this,
                        getString(R.string.reminder_save_failed_title),
                        getString(R.string.reminder_save_failed_message),
                        R.string.dialog_i_know,
                        0,
                        null);
                return;
            }
            toSave.setId(newId);
        }

        if (toSave.isEnabled()) {
            if (!ReminderAlarmScheduler.schedule(this, toSave)) {
                Toast.makeText(this, R.string.reminder_time_passed, Toast.LENGTH_SHORT).show();
            }
            if (!ReminderNotificationHelper.canPostNotifications(this)) {
                Toast.makeText(this, R.string.reminder_notification_disabled,
                        Toast.LENGTH_LONG).show();
                requestNotificationPermissionIfNeeded();
            }
            boolean guided = maybeShowReminderSetupGuide();
            // 强提醒还需系统允许“全屏通知”，与上面的权限引导错开，避免两个弹窗叠在一起
            if (toSave.isAlarm() && !guided) {
                maybeShowFullScreenGuide();
            }
        } else {
            ReminderAlarmScheduler.cancel(this, toSave.getId());
        }
    }

    /**
     * 常用提醒预设。
     * <p>标题、备注使用字符串资源以支持多语言；时间给出常见的服药/记录时间。
     * {@code phase} 标注该预设常用于哪个周期阶段（null 表示全周期通用），
     * 用于编辑弹窗中按当前阶段排序推荐——只是展示顺序，提醒仍按固定日期触发。
     */
    private static final class Preset {
        final int titleRes;
        final int messageRes;
        final int hour;
        final int minute;
        /** 常用于的阶段；null 表示全周期通用 */
        final MenstrualCycle phase;

        Preset(int titleRes, int messageRes, int hour, int minute, MenstrualCycle phase) {
            this.titleRes = titleRes;
            this.messageRes = messageRes;
            this.hour = hour;
            this.minute = minute;
            this.phase = phase;
        }

        /** 是否命中指定阶段 */
        boolean matches(MenstrualCycle current) {
            return phase != null && phase == current;
        }
    }

    /** 预置的常用提醒项：覆盖备孕/经期常见场景，phase 仅供按当前阶段排序推荐 */
    private static final Preset[] PRESETS = {
            new Preset(R.string.reminder_preset_progesterone_title,
                    R.string.reminder_preset_progesterone_message, 21, 0, MenstrualCycle.LUTE_PHASE),
            new Preset(R.string.reminder_preset_folic_acid_title,
                    R.string.reminder_preset_folic_acid_message, 9, 0, null),
            new Preset(R.string.reminder_preset_bbt_title,
                    R.string.reminder_preset_bbt_message, 7, 0, null),
            new Preset(R.string.reminder_preset_ginger_tea_title,
                    R.string.reminder_preset_ginger_tea_message, 10, 0,
                    MenstrualCycle.MENSTRUAL_PERIOD),
            new Preset(R.string.reminder_preset_weight_title,
                    R.string.reminder_preset_weight_message, 7, 30, null)
    };

    /**
     * 计算当前所处周期阶段；没有周期设置时返回 null。
     * <p>与首页/日历同源：按实际记录 + 周期设置 + 阶段锚点取今天的阶段，
     * 避免常用提醒的排序和“当前处于某阶段”的提示与实际显示的阶段不一致。
     */
    private MenstrualCycle detectCurrentPhase() {
        return ReminderCycleHelper.phaseOn(new Date());
    }

    /**
     * 在编辑弹窗顶部渲染"常用提醒"横向快捷标签，点击后填充表单。
     * <p>与当前阶段匹配的预设排在前面并高亮（仅排序提示，不改变提醒的固定日期触发方式）；
     * 当前标题与某个预设一致时，该标签显示品牌粉选中态，做到“选中有选中效果”。
     *
     * @param container 标签容器
     * @param etTitle   标题输入框
     * @param etMessage 备注输入框
     * @param tvTime    时间展示文本
     * @param timeValue 时间工作副本 [hour, minute]，与保存逻辑共用
     * @param tvHint    预设说明文本，可为 null
     * @param onChanged 预设改变时间后的刷新回调，可为 null
     * @param onPhaseSelected 预设带阶段归属时回调阶段下标，把提醒方式切到“阶段区间”
     */
    private void bindPresets(ViewGroup container, EditText etTitle, EditText etMessage,
                             TextView tvTime, int[] timeValue, TextView tvHint,
                             Runnable onChanged, IntConsumer onPhaseSelected) {
        if (container == null) {
            return;
        }
        container.removeAllViews();
        int padH = dpToPx(12);
        int padV = dpToPx(7);
        int marginEnd = dpToPx(8);

        MenstrualCycle currentPhase = detectCurrentPhase();
        List<Preset> ordered = new ArrayList<>(Arrays.asList(PRESETS));
        if (currentPhase != null) {
            ordered.sort((a, b) -> Boolean.compare(b.matches(currentPhase), a.matches(currentPhase)));
        }

        final List<TextView> presetChips = new ArrayList<>();
        for (Preset preset : ordered) {
            TextView chip = new TextView(this);
            chip.setText(preset.titleRes);
            chip.setTextSize(12);
            chip.setPadding(padH, padV, padH, padV);

            // 横向排列，间距由 marginEnd 控制
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMarginEnd(marginEnd);
            container.addView(chip, params);
            presetChips.add(chip);

            chip.setOnClickListener(v -> {
                etTitle.setText(preset.titleRes);
                etTitle.setSelection(etTitle.getText().length());
                etMessage.setText(preset.messageRes);
                timeValue[0] = preset.hour;
                timeValue[1] = preset.minute;
                tvTime.setText(String.format(Locale.getDefault(), "%02d:%02d",
                        preset.hour, preset.minute));
                // 吃药这类预设天然覆盖整个阶段，顺手切到“阶段区间”省去用户再选一次
                if (preset.phase != null && onPhaseSelected != null) {
                    onPhaseSelected.accept(preset.phase.getIndex());
                }
                if (onChanged != null) {
                    onChanged.run();
                }
            });
        }

        // 标题变化时同步预设的选中/推荐样式：选中 = 品牌粉底白字；推荐但未选中 = 浅粉底品牌字
        Runnable syncSelection = () -> {
            String currentTitle = etTitle.getText().toString().trim();
            for (int i = 0; i < ordered.size(); i++) {
                Preset preset = ordered.get(i);
                TextView chip = presetChips.get(i);
                boolean recommended = currentPhase != null && preset.matches(currentPhase);
                boolean selected = getString(preset.titleRes).equals(currentTitle);
                if (selected) {
                    chip.setText(preset.titleRes);
                    applyChipStyle(chip, true);
                } else if (recommended) {
                    chip.setText(getString(preset.titleRes) + " · "
                            + getString(R.string.reminder_preset_recommended_suffix));
                    chip.setBackgroundResource(R.drawable.chip_unselected_background);
                    chip.setBackgroundTintList(ColorStateList.valueOf(
                            ContextCompat.getColor(this, R.color.tile_pink)));
                    chip.setTextColor(ContextCompat.getColor(this, R.color.brand_primary));
                } else {
                    chip.setText(preset.titleRes);
                    applyChipStyle(chip, false);
                }
            }
        };
        etTitle.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                syncSelection.run();
            }
        });
        syncSelection.run();

        if (tvHint != null) {
            tvHint.setText(currentPhase != null
                    ? getString(R.string.reminder_presets_hint_phase,
                            currentPhase.getDisplayName(this))
                    : getString(R.string.reminder_presets_hint_fixed));
        }
    }

    /**
     * dp 转像素，用于代码创建的快捷标签。
     */
    private int dpToPx(int dp) {
        return (int) (getResources().getDisplayMetrics().density * dp);
    }

    /**
     * Android 13+ 请求通知权限，否则到点通知会被系统静默丢弃。
     */
    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                REQUEST_NOTIFICATION_PERMISSION);
    }

    /**
     * 首次开启提醒时的系统权限引导（只弹一次）。
     * <p>
     * 合并了原先单独的「精准闹钟」弹窗：一次讲清「闹钟和提醒」权限与自启动/后台运行限制，
     * 避免连续弹两个框。用户拒绝或关闭后不再打扰。
     */
    private boolean maybeShowReminderSetupGuide() {
        SharedPreferences prefs = getSharedPreferences(PREF_REMINDER, MODE_PRIVATE);
        if (prefs.getBoolean(KEY_REMINDER_SETUP_GUIDED, false)) {
            return false;
        }
        prefs.edit().putBoolean(KEY_REMINDER_SETUP_GUIDED, true).apply();

        AppDialogFactory.showConfirm(this,
                getString(R.string.reminder_guide_title),
                getString(R.string.reminder_guide_message),
                R.string.reminder_guide_settings,
                R.string.reminder_guide_dismiss,
                this::openReminderPermissionSettings);
        return true;
    }

    /**
     * 强提醒首次使用引导：全屏通知（Android 14+）或“显示在其他应用上层”未授权时，
     * 到点只能看到横幅而不会全屏点亮，这里提示用户去系统设置开启。
     * <p>每次保存强提醒时都会检查权限，已授权则不再打扰。
     */
    private void maybeShowFullScreenGuide() {
        boolean needFullScreen = !ReminderNotificationHelper.canUseFullScreenIntent(this);
        boolean needOverlay = !ReminderNotificationHelper.canDrawOverlays(this);
        if (!needFullScreen && !needOverlay) {
            return;
        }
        AppDialogFactory.showConfirm(this,
                getString(R.string.reminder_full_screen_permission_title),
                getString(needFullScreen
                        ? R.string.reminder_full_screen_permission_message
                        : R.string.reminder_full_screen_overlay_message),
                R.string.reminder_guide_settings,
                R.string.reminder_guide_dismiss,
                needFullScreen ? this::openFullScreenSettings : this::openOverlaySettings);
    }

    /**
     * 跳转系统「显示在其他应用上层」授权页；不可用时退回应用详情页。
     */
    private void openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && openSystemIntent(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())))) {
            return;
        }
        if (openSystemIntent(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())))) {
            return;
        }
        Toast.makeText(this, R.string.reminder_exact_alarm_open_failed,
                Toast.LENGTH_LONG).show();
    }

    /**
     * 跳转系统“全屏通知”授权页；不可用时退回应用详情页，避免按钮点击无响应。
     */
    private void openFullScreenSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                && openSystemIntent(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                Uri.parse("package:" + getPackageName())))) {
            return;
        }
        if (openSystemIntent(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())))) {
            return;
        }
        Toast.makeText(this, R.string.reminder_exact_alarm_open_failed,
                Toast.LENGTH_LONG).show();
    }

    /**
     * 跳转系统「闹钟与提醒」授权页；部分系统没有该页面时退到应用详情页，
     * 都打不开则提示用户手动查找，避免按钮点了没反应。
     */
    private void openReminderPermissionSettings() {
        if (openSystemIntent(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:" + getPackageName())))) {
            return;
        }
        if (openSystemIntent(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())))) {
            return;
        }
        Toast.makeText(this, R.string.reminder_exact_alarm_open_failed,
                Toast.LENGTH_LONG).show();
    }

    /** 尝试打开系统页面，返回是否成功跳转。 */
    private boolean openSystemIntent(Intent intent) {
        try {
            startActivity(intent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
