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

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.checkbox.MaterialCheckBox;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import work.huacai.yuelan.R;
import work.huacai.yuelan.dao.MenstrualCycleRecordDao;
import work.huacai.yuelan.dao.MenstrualRecordDao;
import work.huacai.yuelan.dao.ReminderDao;
import work.huacai.yuelan.dao.impl.MenstrualCycleRecordDaoImpl;
import work.huacai.yuelan.dao.impl.MenstrualRecordDaoImpl;
import work.huacai.yuelan.dao.impl.ReminderDaoImpl;
import work.huacai.yuelan.helper.CycleCalculator;
import work.huacai.yuelan.helper.PeriodReminderScheduler;
import work.huacai.yuelan.helper.PhaseContinuityHelper;
import work.huacai.yuelan.helper.ReminderCycleHelper;
import work.huacai.yuelan.helper.SuggestionContent;
import work.huacai.yuelan.helper.SuggestionContext;
import work.huacai.yuelan.helper.SuggestionContextHelper;
import work.huacai.yuelan.helper.SuggestionEngine;
import work.huacai.yuelan.helper.SuggestionFeedbackStore;
import work.huacai.yuelan.helper.SuggestionTemplateRepository;
import work.huacai.yuelan.model.MenstrualCycle;
import work.huacai.yuelan.model.MenstrualCycleRecord;
import work.huacai.yuelan.model.MenstrualRecord;
import work.huacai.yuelan.model.Reminder;
import work.huacai.yuelan.model.Weather;

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
    /** 天气行与暖心提醒行，由 {@link DailyWeatherSection} 统一加载 */
    private TextView tvWeather;
    private TextView tvWeatherTip;
    private DailyWeatherSection weatherSection;
    /** 建议区块标题：今天显示"今日建议"，其他日期显示"当日建议" */
    private TextView tvSuggestionTitle;
    private TextView tvSuggestion;
    /** 建议正文折叠控制：默认只展示前几行，超长时提供“展开全文 / 收起” */
    private CollapsibleText suggestionCollapsible;
    /** 今日宜忌：引擎输出为空时隐藏 */
    private TextView tvYiji;
    /** “为什么会有这条建议”开关与依据正文 */
    private TextView tvInsightToggle;
    private TextView tvInsight;
    /** 依据是否展开；切换日期时自动收起 */
    private boolean insightExpanded;
    /** 当前是否有依据内容，避免空点击 */
    private boolean hasInsight;
    /** 反馈入口容器与按钮：有用 / 换一条 */
    private View layoutFeedback;
    private TextView tvUseful;
    private TextView tvSwap;
    /** 心情与微习惯一键打卡 */
    private SuggestionCheckInView checkInView;
    /** 本次查看内“换一条”跳过的模板，切换日期后清空 */
    private final Set<String> skippedTemplateIds = new HashSet<>();
    /** 最近一次渲染结果，供反馈按钮取模板 id */
    private SuggestionContent lastContent;
    /** 当日记录摘要：对应日历格子底部的"记"标记，点击打开记录详情 */
    private View layoutRecordSummary;
    private TextView tvRecordSummary;
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
    /** 当天天气，天气异步返回后用于重新生成建议正文 */
    private Weather currentWeather;
    /** 最近一次渲染使用的记录与阶段，供天气回调时复用，避免重复查库 */
    private MenstrualRecord lastRecord;
    private MenstrualCycle lastDisplayPhase;
    private MenstrualRecordDao recordDao;
    private ReminderDao reminderDao;
    private MenstrualCycleRecordDao cycleRecordDao;

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
        tvWeather = view.findViewById(R.id.tv_weather);
        tvWeatherTip = view.findViewById(R.id.tv_weather_tip);
        weatherSection = new DailyWeatherSection(requireContext(), tvWeather, tvWeatherTip);
        // 天气异步返回后再重新生成一次建议正文：天气类片段依赖气温/天气描述，
        // 但首屏不能等网络，因此先渲染不含天气的版本，天气到达后原地替换
        weatherSection.setOnWeatherLoadedListener((today, tomorrow) -> {
            if (!isAdded() || currentDate == null) {
                return;
            }
            currentWeather = today;
            renderSuggestion(lastRecord, lastDisplayPhase);
        });
        tvSuggestionTitle = view.findViewById(R.id.tv_suggestion_title);
        layoutRecordSummary = view.findViewById(R.id.layout_record_summary);
        tvRecordSummary = view.findViewById(R.id.tv_record_summary);
        layoutPhase = view.findViewById(R.id.layout_phase);
        ivPhaseEdit = view.findViewById(R.id.iv_phase_edit);
        tvFutureHint = view.findViewById(R.id.tv_future_hint);
        tvSuggestion = view.findViewById(R.id.tv_suggestion);
        // 长建议默认折叠：正文超过 3 行时显示“展开全文”，避免底部弹窗内容看不全
        suggestionCollapsible = new CollapsibleText(tvSuggestion,
                view.findViewById(R.id.tv_suggestion_toggle));
        tvYiji = view.findViewById(R.id.tv_yiji);
        tvInsightToggle = view.findViewById(R.id.tv_insight_toggle);
        tvInsight = view.findViewById(R.id.tv_insight);
        // 依据默认收起：首屏只保留三行建议，用户想深究时再展开
        tvInsightToggle.setOnClickListener(v -> {
            insightExpanded = !insightExpanded;
            updateInsightState();
        });
        layoutFeedback = view.findViewById(R.id.layout_feedback);
        tvUseful = view.findViewById(R.id.tv_useful);
        tvSwap = view.findViewById(R.id.tv_swap);
        tvUseful.setOnClickListener(v -> onUsefulClicked());
        tvSwap.setOnClickListener(v -> onSwapClicked());
        checkInView = view.findViewById(R.id.check_in);
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
        // 记录摘要对应日历格子里的"记"标记，点击查看/编辑当天的流量、痛经与备注
        layoutRecordSummary.setOnClickListener(v -> openRecordDetail());

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
        List<Reminder> reminders = remindersForDay(dateId);
        // 月经提醒不是数据库里的提醒行，而是按周期推算的"下次预计经期首日"，
        // 日历上同样会画一个提醒圆点，这里需要补一条只读事项，否则点击该日同样看不到它
        boolean periodReminderDay = isPeriodReminderDay(dateId);
        layoutDayReminders.removeAllViews();

        if (reminders.isEmpty() && !periodReminderDay) {
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

        if (periodReminderDay) {
            addPeriodReminderItem();
        }
    }

    /** 追加一条只读的“月经提醒”事项（没有数据库提醒行，因此不提供开关） */
    private void addPeriodReminderItem() {
        View item = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_today_reminder, layoutDayReminders, false);
        TextView tvTime = item.findViewById(R.id.tv_time);
        TextView tvTitle = item.findViewById(R.id.tv_title);
        TextView tvMessage = item.findViewById(R.id.tv_message);
        SwitchCompat switchEnabled = item.findViewById(R.id.switch_enabled);

        // 与自定义提醒保持同样的信息密度：时间 + 提前天数说明，
        // 而不是只显示一个“月经提醒”标题
        tvTime.setVisibility(View.VISIBLE);
        tvTime.setText(PeriodReminderScheduler.getReminderTimeText(requireContext()));
        tvTitle.setText(R.string.period_reminder_notification_title);
        tvMessage.setVisibility(View.VISIBLE);
        tvMessage.setText(getString(R.string.period_reminder_notification_text,
                PeriodReminderScheduler.getReminderDays(requireContext())));
        switchEnabled.setVisibility(View.GONE);

        layoutDayReminders.addView(item);
    }

    /**
     * 指定日期是否为“下一次月经提醒”的提醒日（预计经期首日 - 提前提醒天数）。
     * <p>与 {@code CalendarFragment#loadCalendarEvents()} 的口径保持一致：
     * 只认当前这一次即将到来的经期，历史周期不再补挂只读事项。
     *
     * @param dateId 日期 id（yyyyMMdd）
     * @return 是月经提醒日返回 true
     */
    private boolean isPeriodReminderDay(String dateId) {
        if (!PeriodReminderScheduler.isEnabled(requireContext())) {
            return false;
        }
        Date periodStart = ReminderCycleHelper.nextPeriodStart();
        if (periodStart == null) {
            return false;
        }
        int daysBefore = PeriodReminderScheduler.getReminderDays(requireContext());
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(periodStart);
        calendar.add(Calendar.DAY_OF_MONTH, -daysBefore);
        Date reminderDay = CycleCalculator.normalizeDate(calendar.getTime());
        return dateId.equals(CycleCalculator.formatDateId(reminderDay));
    }

    /**
     * 收集指定日期真正命中的提醒。
     * <p>
     * 不能只按 target_day 精确匹配：阶段区间、周期节点、重复规则三种提醒
     * 在日历上会把区间内的每一天都画成提醒圆点，若这里只查固定日期，
     * 点击带圆点的当天就会出现"日历有提醒、弹窗里却什么都没有"的情况。
     * 因此这里与 {@code CalendarFragment#loadCalendarEvents()} 使用同一套展开规则。
     *
     * @param dateId 日期 id（yyyyMMdd）
     * @return 当天命中的提醒列表；日期非法时返回空列表
     */
    private List<Reminder> remindersForDay(String dateId) {
        List<Reminder> reminders = new ArrayList<>();
        Date day = CycleCalculator.parseDateId(dateId);
        if (day == null) {
            return reminders;
        }
        for (Reminder reminder : reminderDao.selectAll()) {
            if (ReminderCycleHelper.occursOn(reminder, day)) {
                reminders.add(reminder);
            }
        }
        return reminders;
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
        // 查看非今天的日期时不能写"今日建议"，否则容易让人以为日历上的那一项就是今日建议
        boolean isToday = dateId.equals(CycleCalculator.formatDateId(new Date()));
        tvSuggestionTitle.setText(isToday
                ? R.string.daily_suggestion_title : R.string.daily_suggestion_title_day);

        // 切换日期时清空上一天的天气，避免旧天气参与新日期的建议生成
        currentWeather = null;

        MenstrualRecord record = recordDao.selectById(dateId);
        updateRecordSummary(record);
        MenstrualCycle cycle = record != null ? record.getMenstrualCycle() : null;
        // 天气暖心提醒要结合当天阶段：没有记录时用推算出的预测阶段
        MenstrualCycle displayPhase = cycle;

        if (cycle != null) {
            tvPhase.setText(cycle.getDisplayName(requireContext()));
            tvPhase.setBackgroundTintList(
                    ContextCompat.getColorStateList(requireContext(), getPhaseColor(cycle)));
        } else {
            // 当天没有实际记录：尝试按周期参数推算预测阶段，让用户看到预测结果后再决定是否覆盖
            MenstrualCycle predictedCycle = computePredictedPhase(currentDate);
            displayPhase = predictedCycle;
            if (predictedCycle != null) {
                tvPhase.setText(getString(R.string.daily_suggestion_predicted_phase,
                        predictedCycle.getDisplayName(requireContext())));
                tvPhase.setBackgroundTintList(
                        ContextCompat.getColorStateList(requireContext(), getPhaseColor(predictedCycle)));
            } else {
                // 无法推算预测阶段：显示占位文案与灰色标签
                tvPhase.setText(getString(R.string.daily_suggestion_no_record));
                tvPhase.setBackgroundTintList(
                        ContextCompat.getColorStateList(requireContext(), R.color.gray));
            }
        }

        // 建议正文改为由引擎按当天信号实时生成；记录与阶段暂存，供天气回调时复用
        lastRecord = record;
        lastDisplayPhase = displayPhase;
        // 切换日期后依据回到收起状态，避免把上一天的说明带到新日期
        insightExpanded = false;
        // 换过的文案只对当天生效，切换日期后重新开放候选
        skippedTemplateIds.clear();
        renderSuggestion(record, displayPhase);

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

        // 天气按当前查看的日期展示；今天的天气额外结合阶段生成暖心提醒
        if (weatherSection != null) {
            weatherSection.show(currentDate, displayPhase);
        }
    }

    /**
     * 用建议引擎实时生成正文。
     * <p>生成时机由“写库时随机一次”改为“展示时按当天信号计算”：
     * 同一天反复打开结果一致，不同日期/不同症状给出不同内容。
     * 数据库里的 {@code suggestion} 字段降级为兜底，仅在引擎无可用模板时回退。
     *
     * @param record 当天记录，可为 null
     * @param phase  当天展示阶段，可为 null
     */
    private void renderSuggestion(@Nullable MenstrualRecord record, @Nullable MenstrualCycle phase) {
        if (tvSuggestion == null || currentDate == null) {
            return;
        }
        // 周期参数用于推算周期第几天与阶段内第几天，口径与日历、提醒保持一致
        MenstrualCycleRecord cycleRecord = cycleRecordDao.selectLast();
        // 实际记录用于规律性、连续打卡与痛经趋势；一次查询即可，数据量很小
        SuggestionContext context = SuggestionContextHelper.build(requireContext(), currentDate,
                record, phase, currentWeather, cycleRecord, recordDao.selectActual(),
                reminderTimeOfToday(), reminderTitleOfToday());
        // 反馈分与本次会话的跳过集合一起参与排序
        Map<String, Integer> feedback = SuggestionFeedbackStore.loadScores(requireContext());
        SuggestionContent content = SuggestionEngine.generate(context,
                SuggestionTemplateRepository.getTemplates(requireContext()),
                feedback, skippedTemplateIds);
        String text = content.toDisplayText();
        // 引擎无内容（模板损坏/未命中）时回退旧文案，保证正文永不空白；
        // 文本变化后折叠回首屏，用户点“展开全文”再看完整内容
        suggestionCollapsible.setText(text != null ? text : fallbackSuggestion(record, phase));
        // 宜忌与依据独立于正文：有就展示，没有就隐藏
        lastContent = content;
        renderExtras(content);
        updateFeedbackRow(content);
        // 打卡区只在今天出现；数据变化后由 onCheckInChanged 重新生成建议
        if (checkInView != null) {
            checkInView.bind(currentDate, record, phase, this::onCheckInChanged);
        }
    }

    /**
     * 心情/微习惯变化后重新读取当天记录并刷新建议，让对应标签即时生效。
     */
    private void onCheckInChanged() {
        if (currentDate == null) {
            return;
        }
        MenstrualRecord record = recordDao.selectById(CycleCalculator.formatDateId(currentDate));
        lastRecord = record;
        renderSuggestion(record, lastDisplayPhase);
    }

    /**
     * 当天最近一条提醒的时间；只有查看“今天”时才返回。
     * <p>历史日期显示“今天 19:00 有提醒”会造成误解，因此非今天一律返回 null。
     *
     * @return HH:mm；没有提醒或非今天返回 null
     */
    @Nullable
    private String reminderTimeOfToday() {
        Reminder reminder = todayReminder();
        return reminder == null ? null : reminder.getAlarmTime();
    }

    /**
     * 当天最近一条提醒的标题；只有查看“今天”时才返回。
     *
     * @return 提醒标题；没有提醒或非今天返回 null
     */
    @Nullable
    private String reminderTitleOfToday() {
        Reminder reminder = todayReminder();
        return reminder == null ? null : reminder.getTitle();
    }

    /**
     * 取“今天”命中的最早一条启用提醒。
     *
     * @return 提醒；非今天或没有提醒时返回 null
     */
    @Nullable
    private Reminder todayReminder() {
        if (currentDate == null) {
            return null;
        }
        String todayId = CycleCalculator.formatDateId(new Date());
        if (!todayId.equals(CycleCalculator.formatDateId(currentDate))) {
            return null;
        }
        return SuggestionContextHelper.firstReminderOn(reminderDao.selectAll(), currentDate);
    }

    /**
     * 反馈入口是否展示：有实际建议内容时才出现，避免空点击。
     *
     * @param content 当前引擎输出
     */
    private void updateFeedbackRow(SuggestionContent content) {
        if (layoutFeedback == null) {
            return;
        }
        boolean visible = content != null && !content.getTemplateIds().isEmpty();
        layoutFeedback.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (tvUseful != null) {
            // 每次重新渲染后恢复可点，便于对新内容再次反馈
            tvUseful.setEnabled(true);
        }
    }

    /**
     * “有用”：给本次命中的模板加分，后续排序会更偏向它们。
     */
    private void onUsefulClicked() {
        if (lastContent == null || lastContent.getTemplateIds().isEmpty()) {
            return;
        }
        SuggestionFeedbackStore.like(requireContext(), lastContent.getTemplateIds());
        if (tvUseful != null) {
            tvUseful.setEnabled(false);
        }
        Toast.makeText(requireContext(), R.string.daily_suggestion_feedback_thanks,
                Toast.LENGTH_SHORT).show();
    }

    /**
     * “换一条”：给当前模板减分并临时跳过，立即重新生成一版。
     * <p>若候选已用尽、内容没有变化，用 Toast 明确告知，而不是静默无响应。
     */
    private void onSwapClicked() {
        if (lastContent == null || lastContent.getTemplateIds().isEmpty()) {
            return;
        }
        List<String> ids = lastContent.getTemplateIds();
        SuggestionFeedbackStore.dislike(requireContext(), ids);
        skippedTemplateIds.addAll(ids);
        String before = lastContent.toDisplayText();
        renderSuggestion(lastRecord, lastDisplayPhase);
        String after = lastContent == null ? null : lastContent.toDisplayText();
        if (before != null && before.equals(after)) {
            Toast.makeText(requireContext(), R.string.daily_suggestion_no_more,
                    Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 渲染今日宜忌与“为什么”依据。
     * <p>两者都可能为空：宜忌只在模板命中时出现，依据只在 insight 插槽有内容时出现。
     *
     * @param content 引擎输出
     */
    private void renderExtras(SuggestionContent content) {
        if (tvYiji == null || tvInsight == null || tvInsightToggle == null) {
            return;
        }
        String yiji = content.getYiji();
        boolean hasYiji = yiji != null && !yiji.trim().isEmpty();
        tvYiji.setText(hasYiji ? yiji : null);
        tvYiji.setVisibility(hasYiji ? View.VISIBLE : View.GONE);

        String insight = content.getInsight();
        hasInsight = insight != null && !insight.trim().isEmpty();
        tvInsight.setText(hasInsight ? insight : null);
        tvInsightToggle.setVisibility(hasInsight ? View.VISIBLE : View.GONE);
        updateInsightState();
    }

    /**
     * 根据展开状态刷新依据正文与按钮文案；没有依据时保持隐藏。
     */
    private void updateInsightState() {
        if (tvInsight == null || tvInsightToggle == null) {
            return;
        }
        tvInsight.setVisibility(hasInsight && insightExpanded ? View.VISIBLE : View.GONE);
        tvInsightToggle.setText(insightExpanded
                ? R.string.daily_suggestion_why_hide : R.string.daily_suggestion_why);
    }

    /**
     * 引擎不可用时的兜底文案：优先用记录里已写入的建议，其次用阶段静态建议，
     * 最后才是“暂无记录”占位。
     *
     * @param record 当天记录，可为 null
     * @param phase  当天阶段，可为 null
     * @return 兜底文案
     */
    private String fallbackSuggestion(@Nullable MenstrualRecord record,
                                      @Nullable MenstrualCycle phase) {
        if (record != null && record.getSuggestion() != null
                && !record.getSuggestion().trim().isEmpty()) {
            return record.getSuggestion();
        }
        if (phase != null) {
            return generateSuggestionForPhase(phase);
        }
        return getString(R.string.daily_suggestion_no_record_suggestion);
    }

    /**
     * 刷新"已记录"摘要：只有用户实际记录（非预测）才展示。
     * <p>这条摘要就是日历格子底部"记"标记的说明，避免用户点开后不知道这一项是什么；
     * 显示流量等级 / 痛经等级，只有阶段没有任何流量痛经数据时提示"仅标记阶段"。
     *
     * @param record 当天记录，可为 null
     */
    private void updateRecordSummary(@Nullable MenstrualRecord record) {
        if (layoutRecordSummary == null || tvRecordSummary == null) return;
        if (record == null || record.isPrediction() || record.getMenstrualCycle() == null) {
            layoutRecordSummary.setVisibility(View.GONE);
            return;
        }

        boolean hasFlow = record.hasFlowData();
        boolean hasPain = record.hasPainData();
        StringBuilder summary = new StringBuilder(getString(R.string.daily_suggestion_recorded));
        if (hasFlow) {
            summary.append(" · ").append(getString(R.string.daily_suggestion_record_flow,
                    record.getFlowLevelText(requireContext())));
        }
        if (hasPain) {
            summary.append(" · ").append(getString(R.string.daily_suggestion_record_pain,
                    record.getPainLevelText(requireContext())));
        }
        if (!hasFlow && !hasPain) {
            summary.append(" · ").append(getString(R.string.daily_suggestion_record_phase_only));
        }

        tvRecordSummary.setText(summary);
        layoutRecordSummary.setVisibility(View.VISIBLE);
    }

    /**
     * 打开当天的记录详情（流量 / 痛经 / 备注），保存后刷新本弹窗并通知宿主日历。
     */
    private void openRecordDetail() {
        if (currentDate == null || isFutureDate()) {
            return;
        }
        String dateId = CycleCalculator.formatDateId(currentDate);
        RecordDetailBottomSheet detail = RecordDetailBottomSheet.newInstance(
                dateId, RecordDetailBottomSheet.MODE_ALL);
        detail.setOnRecordSavedListener(() -> {
            updateDisplay();
            updateDayReminders();
            if (phaseChangedListener != null) {
                phaseChangedListener.onPhaseChanged();
            }
        });
        detail.show(getChildFragmentManager(), "RecordDetailBottomSheet");
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
                dateId, cycle, recordDao, cycleRecordDao);
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
        return CycleCalculator.getMenstrualCycleForDate(
                date, cycleRecord.getStartTime(), phaseDays, cycleRecord.getTotalCycleDay());
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
        // 内容包含建议、打卡与当日提醒，默认直接展开，保证内部 ScrollView 有足够高度滚动
        expandSheet(dialog);
    }

    /**
     * 将底部弹窗展开到全屏高度，避免内容较多时只展示一部分。
     *
     * @param dialog 当前弹窗
     */
    private void expandSheet(Dialog dialog) {
        View bottomSheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (bottomSheet == null) {
            return;
        }
        BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(bottomSheet);
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
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
