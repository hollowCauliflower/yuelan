package work.huacai.yuelan.widget;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.checkbox.MaterialCheckBox;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import work.huacai.yuelan.R;
import work.huacai.yuelan.dao.MenstrualCycleRecordDao;
import work.huacai.yuelan.dao.MenstrualRecordDao;
import work.huacai.yuelan.dao.PhaseAnchorDao;
import work.huacai.yuelan.dao.ReminderDao;
import work.huacai.yuelan.dao.impl.MenstrualCycleRecordDaoImpl;
import work.huacai.yuelan.dao.impl.MenstrualRecordDaoImpl;
import work.huacai.yuelan.dao.impl.PhaseAnchorDaoImpl;
import work.huacai.yuelan.dao.impl.ReminderDaoImpl;
import work.huacai.yuelan.helper.CycleCalculator;
import work.huacai.yuelan.helper.PhaseContinuityHelper;
import work.huacai.yuelan.model.MenstrualCycle;
import work.huacai.yuelan.model.MenstrualCycleRecord;
import work.huacai.yuelan.model.MenstrualRecord;
import work.huacai.yuelan.model.PhaseAnchor;
import work.huacai.yuelan.model.Reminder;

/**
 * 每日建议弹窗。
 * <p>
 * 两种使用方式：
 * 1. 首页自动弹出（{@link #newInstance(String, boolean)} 的 isToday = true），底部带“今日不再提醒”勾选项；
 * 2. 日历中查看某一天的记录（isToday = false），可前后切换日期。
 * <p>
 * 关闭时若勾选了“今日不再提醒”，会把当天的日期 ID 写入 SharedPreferences，
 * 首页下次通过 {@link #shouldRemindToday(Context)} 判断当天是否还要再弹。
 */
public class DailySuggestionBottomSheet extends BottomSheetDialogFragment {

    /** 参数：要展示的日期（yyyyMMdd） */
    private static final String ARG_DATE_ID = "date_id";
    /** 参数：是否为首页自动弹出的“今日建议”模式 */
    private static final String ARG_IS_TODAY = "is_today";

    /** 记录“今日不再提醒”的偏好文件与键名，首页与居中弹窗共用同一份配置 */
    static final String PREFS_NAME = "menstruation_config";
    static final String KEY_SNOOZE_DATE = "daily_suggestion_snooze_date";

    /** 弹窗标题日期格式（如“2024年5月20日”），只在主线程使用 */
    private static final SimpleDateFormat DATE_DISPLAY_FORMAT =
            new SimpleDateFormat("yyyy年M月d日", Locale.getDefault());

    private TextView tvDate;
    private TextView tvPhase;
    private TextView tvSuggestion;
    private TextView tvPrevDay;
    private TextView tvNextDay;
    private MaterialCheckBox cbNoRemindToday;
    /** 阶段标签所在的可点击容器，点击可修改当天所属阶段 */
    private View layoutPhase;
    /** 阶段标签右侧的编辑小图标：未来日期不可编辑时隐藏 */
    private View ivPhaseEdit;
    /** 未来日期提示文案：说明该天只是预测、不能记录 */
    private TextView tvFutureHint;
    /** 当日提醒区块与其条目容器 */
    private View dayRemindersSection;
    private LinearLayout layoutDayReminders;

    /** 当前展示的日期 */
    private Date currentDate;
    /** true = 首页自动弹出的今日建议模式，才显示“今日不再提醒” */
    private boolean isTodayMode;
    private MenstrualRecordDao recordDao;
    private ReminderDao reminderDao;
    private MenstrualCycleRecordDao cycleRecordDao;
    private PhaseAnchorDao phaseAnchorDao;

    /** 阶段修改回调：通知宿主刷新日历与统计 */
    public interface OnPhaseChangedListener {
        /** 单日阶段修改完成：只需刷新展示，不自动重排后续预测 */
        void onPhaseChanged();

        /**
         * 批量范围修改完成（用户在涂抹编辑器里显式保存）：
         * 默认复用 {@link #onPhaseChanged()}，需要重排预测的宿主可覆盖。
         */
        default void onPhaseRangeChanged() {
            onPhaseChanged();
        }
    }
    private OnPhaseChangedListener phaseChangedListener;

    /**
     * 设置阶段修改监听器。
     *
     * @param listener 阶段被修改后回调
     */
    public void setOnPhaseChangedListener(OnPhaseChangedListener listener) {
        this.phaseChangedListener = listener;
    }

    /**
     * 创建弹窗实例。
     *
     * @param dateId  要展示的日期（yyyyMMdd）
     * @param isToday true 表示首页自动弹出的“今日建议”，会显示“今日不再提醒”勾选项
     * @return 已带参数的弹窗实例
     */
    public static DailySuggestionBottomSheet newInstance(String dateId, boolean isToday) {
        DailySuggestionBottomSheet fragment = new DailySuggestionBottomSheet();
        Bundle args = new Bundle();
        args.putString(ARG_DATE_ID, dateId);
        args.putBoolean(ARG_IS_TODAY, isToday);
        fragment.setArguments(args);
        return fragment;
    }

    /**
     * 今天是否还需要自动弹出每日建议（用户勾选“今日不再提醒”后当天不再弹）。
     *
     * @param context 任意 Context
     * @return 存储的“忽略日期”不是今天时返回 true
     */
    public static boolean shouldRemindToday(Context context) {
        String today = CycleCalculator.formatDateId(new Date());
        String snoozeDate = prefs(context).getString(KEY_SNOOZE_DATE, null);
        return !today.equals(snoozeDate);
    }

    /**
     * 获取存放用户配置的 SharedPreferences。
     */
    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        recordDao = new MenstrualRecordDaoImpl();
        reminderDao = new ReminderDaoImpl();
        cycleRecordDao = new MenstrualCycleRecordDaoImpl();
        phaseAnchorDao = new PhaseAnchorDaoImpl();

        // 解析参数；日期解析失败时在下面回退为今天
        if (getArguments() != null) {
            String dateId = getArguments().getString(ARG_DATE_ID);
            isTodayMode = getArguments().getBoolean(ARG_IS_TODAY, false);
            if (dateId != null) {
                currentDate = CycleCalculator.parseDateId(dateId);
            }
        }

        if (currentDate == null) {
            currentDate = new Date();
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_daily_suggestion, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        tvDate = view.findViewById(R.id.tv_date);
        tvPhase = view.findViewById(R.id.tv_phase);
        layoutPhase = view.findViewById(R.id.layout_phase);
        ivPhaseEdit = view.findViewById(R.id.iv_phase_edit);
        tvFutureHint = view.findViewById(R.id.tv_future_hint);
        tvSuggestion = view.findViewById(R.id.tv_suggestion);
        tvPrevDay = view.findViewById(R.id.tv_prev_day);
        tvNextDay = view.findViewById(R.id.tv_next_day);
        cbNoRemindToday = view.findViewById(R.id.cb_no_remind_today);
        dayRemindersSection = view.findViewById(R.id.layout_day_reminders_section);
        layoutDayReminders = view.findViewById(R.id.layout_day_reminders);

        // 只有首页自动弹出的“今日建议”才提供“今日不再提醒”
        cbNoRemindToday.setVisibility(isTodayMode ? View.VISIBLE : View.GONE);

        // 点击阶段标签可修改当天所属阶段（补签/修改预测阶段）；
        // 未来日期只允许查看预测，不能写成实际记录，因此不注册点击（视觉上也弱化）
        layoutPhase.setOnClickListener(v -> showPhaseEditDialog());

        updateDisplay();
        updateDayReminders();

        tvPrevDay.setOnClickListener(v -> navigateDay(-1));
        tvNextDay.setOnClickListener(v -> navigateDay(1));
    }

    /**
     * 渲染当前日期的自定义提醒列表：按提醒时间排序，每项带启用开关。
     * 该日期没有提醒时隐藏整个区块。
     */
    private void updateDayReminders() {
        if (dayRemindersSection == null || layoutDayReminders == null || currentDate == null) return;

        String dateId = CycleCalculator.formatDateId(currentDate);
        int targetDay;
        try {
            targetDay = Integer.parseInt(dateId);
        } catch (NumberFormatException e) {
            dayRemindersSection.setVisibility(View.GONE);
            return;
        }

        List<Reminder> reminders = new ArrayList<>(reminderDao.selectByDay(targetDay));
        layoutDayReminders.removeAllViews();

        if (reminders.isEmpty()) {
            dayRemindersSection.setVisibility(View.GONE);
            return;
        }

        dayRemindersSection.setVisibility(View.VISIBLE);

        // 只有今天及之后的提醒才允许在弹窗里切换开关；过去的日期已经错过提醒时间，只做展示
        String todayId = CycleCalculator.formatDateId(new Date());
        boolean canToggle = dateId.compareTo(todayId) >= 0;

        // 按提醒时间升序展示
        Collections.sort(reminders, (r1, r2) ->
                compareTime(r1.getAlarmTime(), r2.getAlarmTime()));

        for (Reminder reminder : reminders) {
            View item = LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_today_reminder, layoutDayReminders, false);

            TextView tvTime = item.findViewById(R.id.tv_time);
            TextView tvTitle = item.findViewById(R.id.tv_title);
            TextView tvMessage = item.findViewById(R.id.tv_message);
            SwitchCompat switchEnabled = item.findViewById(R.id.switch_enabled);

            tvTime.setText(reminder.getAlarmTime() != null ? reminder.getAlarmTime() : "");
            tvTitle.setText(reminder.getTitle());

            String message = reminder.getMessage();
            if (message != null && !message.isEmpty()) {
                tvMessage.setVisibility(View.VISIBLE);
                tvMessage.setText(message);
            } else {
                tvMessage.setVisibility(View.GONE);
            }

            // 过去的日期不展示开关，只展示提醒内容
            switchEnabled.setVisibility(canToggle ? View.VISIBLE : View.GONE);

            // 开关反映该提醒的启用状态，切换后立即持久化（次日再打开弹窗仍生效）
            switchEnabled.setOnCheckedChangeListener(null);
            switchEnabled.setChecked(reminder.isEnabled());
            switchEnabled.setOnCheckedChangeListener((buttonView, isChecked) ->
                    reminderDao.setEnabled(reminder.getId(), isChecked));

            layoutDayReminders.addView(item);
        }
    }

    /** 比较 "HH:mm" 时间字符串；空值排最后 */
    private static int compareTime(String t1, String t2) {
        if (t1 == null && t2 == null) return 0;
        if (t1 == null) return 1;
        if (t2 == null) return -1;
        return t1.compareTo(t2);
    }

    @Override
    public void onDismiss(@NonNull android.content.DialogInterface dialog) {
        // 勾选后关闭弹窗即记录“今天不再提醒”（只写入日期，跨天自动失效）
        if (isTodayMode && cbNoRemindToday != null && cbNoRemindToday.isChecked()) {
            Context context = getContext();
            if (context != null) {
                prefs(context).edit()
                        .putString(KEY_SNOOZE_DATE, CycleCalculator.formatDateId(new Date()))
                        .apply();
            }
        }
        super.onDismiss(dialog);
    }

    /**
     * 刷新日期标题、阶段标签与建议内容。
     * <p>副作用：读取数据库当天记录，并更新日期切换按钮的可用状态。
     */
    private void updateDisplay() {
        if (currentDate == null) return;

        tvDate.setText(DATE_DISPLAY_FORMAT.format(currentDate));

        // 记录 id 就是 yyyyMMdd，直接按日期查当天的记录
        String dateId = CycleCalculator.formatDateId(currentDate);
        MenstrualRecord record = recordDao.selectById(dateId);
        MenstrualCycle cycle = record != null ? record.getMenstrualCycle() : null;

        if (cycle != null) {
            tvPhase.setText(cycle.getDisplayName(requireContext()));
            tvPhase.setBackgroundTintList(
                    ContextCompat.getColorStateList(requireContext(), getPhaseColor(cycle)));
            tvSuggestion.setText(record.getSuggestion() != null
                    ? record.getSuggestion()
                    : getString(R.string.record_adapter_no_suggestion));
        } else {
            // 当天没有实际记录：尝试按周期参数推算预测阶段，让用户看到预测结果后再决定是否覆盖
            MenstrualCycle predictedCycle = computePredictedPhase(currentDate);
            if (predictedCycle != null) {
                tvPhase.setText(getString(R.string.daily_suggestion_predicted_phase,
                        predictedCycle.getDisplayName(requireContext())));
                tvPhase.setBackgroundTintList(
                        ContextCompat.getColorStateList(requireContext(), getPhaseColor(predictedCycle)));
                tvSuggestion.setText(generateSuggestionForPhase(predictedCycle));
            } else {
                // 无法推算预测阶段：显示占位文案与灰色标签
                tvPhase.setText(getString(R.string.daily_suggestion_no_record));
                tvPhase.setBackgroundTintList(
                        ContextCompat.getColorStateList(requireContext(), R.color.gray));
                tvSuggestion.setText(getString(R.string.daily_suggestion_no_record_suggestion));
            }
        }

        // 不允许查看今天之后的日期：只有当前日期早于今天时“下一天”才可用
        Date today = CycleCalculator.normalizeDate(new Date());
        boolean canGoNext = CycleCalculator.normalizeDate(currentDate).before(today);
        tvNextDay.setEnabled(canGoNext);
        tvNextDay.setAlpha(canGoNext ? 1.0f : 0.5f);

        // 未来日期只是预测：阶段标签不可点击、隐藏编辑图标，并显示一行说明，
        // 从入口上杜绝"给未来日期补录实际记录"
        boolean future = isFutureDate();
        layoutPhase.setEnabled(!future);
        layoutPhase.setClickable(!future);
        layoutPhase.setAlpha(future ? 0.75f : 1.0f);
        ivPhaseEdit.setVisibility(future ? View.GONE : View.VISIBLE);
        tvFutureHint.setVisibility(future ? View.VISIBLE : View.GONE);
    }

    /**
     * 当前展示的日期是否晚于今天。
     * <p>未来日期只允许查看预测，任何"改成实际记录"的操作都应被拒绝。
     */
    private boolean isFutureDate() {
        if (currentDate == null) {
            return false;
        }
        return CycleCalculator.normalizeDate(currentDate)
                .after(CycleCalculator.normalizeDate(new Date()));
    }

    /**
     * 向前 / 向后切换一天。
     *
     * @param offset -1 表示前一天，1 表示后一天
     */
    private void navigateDay(int offset) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(currentDate);
        cal.add(Calendar.DAY_OF_MONTH, offset);

        Date today = CycleCalculator.normalizeDate(new Date());
        Date newDate = CycleCalculator.normalizeDate(cal.getTime());

        // 再次校验：向后切换时不允许越过今天
        if (offset > 0 && newDate.after(today)) {
            return;
        }

        currentDate = cal.getTime();
        updateDisplay();
        // 切换日期后同步刷新当天的提醒列表
        updateDayReminders();
    }

    /**
     * 阶段标签底色（与日历、统计页保持一致的阶段色）。
     *
     * @return 颜色资源 ID
     */
    private int getPhaseColor(MenstrualCycle cycle) {
        switch (cycle) {
            case MENSTRUAL_PERIOD:
                return R.color.phase_menstrual;
            case FOLLICULAR_PHASE:
                return R.color.phase_follicular;
            case OVULATION_PERIOD:
                return R.color.phase_ovulation;
            case LUTE_PHASE:
                return R.color.phase_luteal;
            default:
                return R.color.gray;
        }
    }

    /**
     * 弹出补签卡片弹窗，修改当天所属阶段。
     * <p>
     * 与日历页的补录弹窗共用 {@link MakeupRecordDialog}，保证两处入口体验一致；
     * 点选阶段卡片后进入连贯性检查与保存流程，"批量调整连续日期"打开涂抹式范围编辑器。
     * 无论当天原本是预测记录还是没有记录，确认后都会转为用户实际记录（isPrediction=0），
     * 并写入对应阶段的建议文案；保存后刷新本弹窗并通知宿主重算预测。
     */
    private void showPhaseEditDialog() {
        if (currentDate == null) return;
        // 双保险：即使标签被程序化点击，也不允许给未来日期写实际记录
        if (isFutureDate()) {
            Toast.makeText(requireContext(), R.string.calendar_record_future_not_allowed,
                    Toast.LENGTH_SHORT).show();
            return;
        }

        String dateId = CycleCalculator.formatDateId(currentDate);
        MakeupRecordDialog.show(requireContext(), dateId, new MakeupRecordDialog.Callback() {
            @Override
            public void onPhaseSelected(@NonNull MenstrualCycle cycle) {
                if (!isAdded()) return;
                savePhaseEdit(cycle);
            }

            @Override
            public void onBatchEdit() {
                if (!isAdded()) return;
                PhaseRangeEditor.show(requireContext(), dateId, () -> {
                    if (!isAdded()) return;
                    updateDisplay();
                    if (phaseChangedListener != null) {
                        // 批量涂抹是用户显式重排，走范围修改回调
                        phaseChangedListener.onPhaseRangeChanged();
                    }
                });
            }
        });
    }

    /**
     * 保存阶段修改：存在记录则更新，不存在则新建；统一标记为实际记录并写入建议文案。
     * <p>
     * 保存前先做连贯性检查：若与前后日期的有效阶段不连贯（孤岛/倒置），
     * 弹窗让用户选择"顺延后续预测"（写入阶段锚点，后续预测从该日起按所选阶段
     * 顺序重排）、"仅修改当天"或"取消"。
     */
    private void savePhaseEdit(MenstrualCycle cycle) {
        if (isFutureDate()) {
            Toast.makeText(requireContext(), R.string.calendar_record_future_not_allowed,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        String dateId = CycleCalculator.formatDateId(currentDate);
        PhaseContinuityHelper.CheckResult check = PhaseContinuityHelper.check(
                dateId, cycle, recordDao, cycleRecordDao, phaseAnchorDao);
        if (check.type != PhaseContinuityHelper.Discontinuity.NONE) {
            showContinuityDialog(cycle, check);
            return;
        }
        doSavePhaseEdit(cycle);
    }

    /**
     * 阶段不连贯时的选择弹窗：批量调整阶段 / 取消。
     * <p>黄体期→经期：提示后果（只落单日、不重排预测），用户确认后仍保存当天。
     */
    private void showContinuityDialog(MenstrualCycle cycle, PhaseContinuityHelper.CheckResult check) {
        // 黄体期还没结束就补签经期：不硬拦，提示后允许只保存当天；
        // DAO 的 reconcile 判定会因间隔不足而不新增分段，预测周期保持不动
        if (check.type == PhaseContinuityHelper.Discontinuity.LUTEAL_TO_PERIOD) {
            AppDialogFactory.showConfirm(requireContext(),
                    getString(R.string.phase_range_invalid_title),
                    getString(R.string.phase_edit_luteal_to_period_message),
                    R.string.phase_edit_save_single_day, R.string.dialog_cancel,
                    () -> {
                        if (!isAdded()) return;
                        doSavePhaseEdit(cycle);
                    });
            return;
        }

        String message;
        if (check.type == PhaseContinuityHelper.Discontinuity.ISLAND && check.prevPhase != null) {
            message = getString(R.string.phase_edit_island_message,
                    check.prevPhase.getDisplayName(requireContext()));
        } else if (check.prevPhase != null) {
            message = getString(R.string.phase_edit_backward_message,
                    check.prevPhase.getDisplayName(requireContext()));
        } else {
            message = getString(R.string.phase_edit_island_message,
                    cycle.getDisplayName(requireContext()));
        }

        // 不连贯时统一走批量涂抹编辑器（从该日期到今天），不再提供"仅修改当天"
        // ——单日覆盖会留下孤岛/倒置数据，批量涂抹的保存校验能从源头避免
        AppDialogFactory.showConfirm(requireContext(),
                getString(R.string.phase_range_invalid_title), message,
                R.string.phase_edit_option_resequence, R.string.cancel,
                () -> PhaseRangeEditor.show(requireContext(),
                        CycleCalculator.formatDateId(currentDate), () -> {
                            updateDisplay();
                            if (phaseChangedListener != null) {
                                phaseChangedListener.onPhaseChanged();
                            }
                        }));
    }

    /**
     * 真正落库阶段修改（只改当天）。
     * <p>需要"逐天调整并顺延后续预测"时走 {@link PhaseRangeEditor}，
     * 它会在保存范围内所有日期后写入新的阶段锚点。
     *
     * @param cycle 目标阶段
     */
    private void doSavePhaseEdit(MenstrualCycle cycle) {
        String dateId = CycleCalculator.formatDateId(currentDate);

        MenstrualRecord record = recordDao.selectById(dateId);
        boolean isNew = record == null;
        if (isNew) {
            record = new MenstrualRecord();
            record.setId(dateId);
        }
        record.setMenstrualCycle(cycle);
        // 0 表示用户实际记录
        record.setIsPrediction(0);
        record.setSuggestion(generateSuggestionForPhase(cycle));

        boolean saved = isNew ? recordDao.insert(record) > 0 : recordDao.updateRecord(record) > 0;
        if (saved) {
            updateDisplay();
            if (phaseChangedListener != null) {
                phaseChangedListener.onPhaseChanged();
            }
        }
    }

    /**
     * 取阶段对应的静态建议文案（与日历页补录逻辑保持一致）。
     */
    private String generateSuggestionForPhase(MenstrualCycle cycle) {
        switch (cycle) {
            case MENSTRUAL_PERIOD:
                return getString(R.string.cycle_menstrual_period_tips);
            case FOLLICULAR_PHASE:
                return getString(R.string.cycle_follicular_phase_tips);
            case OVULATION_PERIOD:
                return getString(R.string.cycle_ovulation_period_tips);
            case LUTE_PHASE:
                return getString(R.string.cycle_lute_phase_tips);
            default:
                return "";
        }
    }

    /**
     * 按当前周期参数推算指定日期的预测阶段。
     * <p>
     * 用于当天没有实际记录时，在弹窗中展示预测阶段，方便用户对照后决定是否手动覆盖。
     *
     * @param date 目标日期
     * @return 预测阶段；无法推算时返回 null
     */
    @Nullable
    private MenstrualCycle computePredictedPhase(Date date) {
        MenstrualCycleRecord cycleRecord = cycleRecordDao.selectLast();
        if (cycleRecord == null || cycleRecord.getStartTime() == null) {
            return null;
        }
        int[] phaseDays = CycleCalculator.calculatePhaseDays(
                cycleRecord.getMenstrualPeriodDay(),
                cycleRecord.getTotalCycleDay());
        // 叠加阶段锚点：用户选择"顺延后续预测"后，弹窗显示的预测阶段与日历保持一致
        PhaseAnchor anchor = phaseAnchorDao.selectLatest();
        Date anchorDate = anchor != null ? anchor.getAnchorDate() : null;
        int anchorIndex = anchor != null ? anchor.getPhaseIndex() : -1;
        return CycleCalculator.getMenstrualCycleForDate(
                date, cycleRecord.getStartTime(), phaseDays, cycleRecord.getTotalCycleDay(),
                anchorDate, anchorIndex);
    }

    /**
     * 当前是否处于深色模式，用于决定导航栏图标的明暗。
     */
    private boolean isNightMode() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog == null) return;
        applyWindowStyle(dialog);
    }

    /**
     * 统一设置弹窗的窗口样式：透明背景 + 导航栏配色 + 底部的系统栏内边距。
     * <p>
     * 之所以需要手动处理 WindowInsets：开启 edge-to-edge（{@code setDecorFitsSystemWindows(false)}）后，
     * 手势导航模式会把导航栏强制变透明，弹窗内容会一直绘制到屏幕最底部，
     * 需要额外给 design_bottom_sheet 补一段底部 padding，否则内容会被系统导航条遮挡。
     *
     * @param dialog 当前弹窗
     */
    private void applyWindowStyle(Dialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) return;

        // 去掉默认的白色圆角矩形背景，改由布局自身绘制圆角
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        // 导航栏颜色与弹窗背景保持一致，图标亮度跟随深浅色
        window.setNavigationBarColor(ContextCompat.getColor(requireContext(), R.color.card_surface));
        WindowCompat.getInsetsController(window, window.getDecorView())
                .setAppearanceLightNavigationBars(!isNightMode());

        // 手势导航模式下导航栏强制透明，让弹窗背景延伸到屏幕底部
        View bottomSheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (bottomSheet != null) {
            ViewCompat.setOnApplyWindowInsetsListener(bottomSheet, (v, insets) -> {
                int bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
                v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), bottom);
                return WindowInsetsCompat.CONSUMED;
            });
        }
        WindowCompat.setDecorFitsSystemWindows(window, false);
    }
}
