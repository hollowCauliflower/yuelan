package work.huacai.yuelan.widget;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.checkbox.MaterialCheckBox;

import java.text.SimpleDateFormat;
import java.util.Calendar;
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
 * 每日建议居中弹窗。
 * <p>
 * 用于进入首页时的自动提醒：与底部弹窗（{@link DailySuggestionBottomSheet}，供悬浮按钮/日历使用）
 * 在视觉上明确区分开。支持前后一天切换，并保留“今日不再提醒”勾选项，
 * 与底部弹窗共用同一份 SharedPreferences 忽略日期。
 */
public class DailySuggestionDialog extends DialogFragment {

    /** 参数：要展示的日期（yyyyMMdd） */
    private static final String ARG_DATE_ID = "date_id";
    /** 参数：是否显示“今日不再提醒”勾选项（仅进入应用自动弹出时为 true） */
    private static final String ARG_SHOW_SNOOZE = "show_snooze";

    /** 弹窗宽度占屏幕宽度的比例 */
    private static final float WIDTH_RATIO = 0.92f;

    /** 弹窗标题日期格式（如“2024年5月20日”） */
    private static final SimpleDateFormat DATE_DISPLAY_FORMAT =
            new SimpleDateFormat("yyyy年M月d日", Locale.getDefault());

    private TextView tvDate;
    private TextView tvPhase;
    /** 天气行与暖心提醒行，由 {@link DailyWeatherSection} 统一加载 */
    private TextView tvWeather;
    private TextView tvWeatherTip;
    private DailyWeatherSection weatherSection;
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
    private TextView tvPrevDay;
    private TextView tvNextDay;
    private MaterialCheckBox cbNoRemindToday;

    /** 是否显示“今日不再提醒”：自动弹出时为 true，悬浮按钮手动打开时为 false */
    private boolean showSnooze;
    /** 当前展示的日期 */
    private Date currentDate;
    /** 当天天气，天气异步返回后用于重新生成建议正文 */
    private Weather currentWeather;
    /** 最近一次渲染使用的记录与阶段，供天气回调时复用，避免重复查库 */
    private MenstrualRecord lastRecord;
    private MenstrualCycle lastDisplayPhase;
    private MenstrualRecordDao recordDao;
    private MenstrualCycleRecordDao cycleRecordDao;
    private ReminderDao reminderDao;

    /**
     * 创建居中弹窗实例。
     *
     * @param dateId     要展示的日期（yyyyMMdd）
     * @param showSnooze true 时显示“今日不再提醒”勾选项
     * @return 已带参数的弹窗实例
     */
    public static DailySuggestionDialog newInstance(String dateId, boolean showSnooze) {
        DailySuggestionDialog dialog = new DailySuggestionDialog();
        Bundle args = new Bundle();
        args.putString(ARG_DATE_ID, dateId);
        args.putBoolean(ARG_SHOW_SNOOZE, showSnooze);
        dialog.setArguments(args);
        return dialog;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        recordDao = new MenstrualRecordDaoImpl();
        cycleRecordDao = new MenstrualCycleRecordDaoImpl();
        reminderDao = new ReminderDaoImpl();

        if (getArguments() != null) {
            String dateId = getArguments().getString(ARG_DATE_ID);
            showSnooze = getArguments().getBoolean(ARG_SHOW_SNOOZE, false);
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
        return inflater.inflate(R.layout.dialog_daily_suggestion, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        tvDate = view.findViewById(R.id.tv_date);
        tvPhase = view.findViewById(R.id.tv_phase);
        tvWeather = view.findViewById(R.id.tv_weather);
        tvWeatherTip = view.findViewById(R.id.tv_weather_tip);
        weatherSection = new DailyWeatherSection(requireContext(), tvWeather, tvWeatherTip);
        // 天气异步返回后重新生成建议正文：首屏先出不含天气的版本，天气到达后原地替换
        weatherSection.setOnWeatherLoadedListener((today, tomorrow) -> {
            if (!isAdded() || currentDate == null) {
                return;
            }
            currentWeather = today;
            renderSuggestion(lastRecord, lastDisplayPhase);
        });
        tvSuggestion = view.findViewById(R.id.tv_suggestion);
        // 长建议默认折叠：正文超过 3 行时显示“展开全文”，避免弹窗被撑长、内容看不全
        suggestionCollapsible = new CollapsibleText(tvSuggestion,
                view.findViewById(R.id.tv_suggestion_toggle));
        tvYiji = view.findViewById(R.id.tv_yiji);
        tvInsightToggle = view.findViewById(R.id.tv_insight_toggle);
        tvInsight = view.findViewById(R.id.tv_insight);
        // 依据默认收起：首屏只保留三行建议，想看原因时再展开
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

        // 仅进入应用自动弹出时提供“今日不再提醒”（用户显式关闭时才写入忽略日期）；
        // 悬浮按钮手动打开属于主动查看，不需要该选项
        cbNoRemindToday.setVisibility(showSnooze ? View.VISIBLE : View.GONE);

        updateDisplay();

        tvPrevDay.setOnClickListener(v -> navigateDay(-1));
        tvNextDay.setOnClickListener(v -> navigateDay(1));
        view.findViewById(R.id.tv_close).setOnClickListener(v -> dismiss());
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog == null) return;

        Window window = dialog.getWindow();
        if (window == null) return;

        // 透明背景，圆角由布局自身绘制；宽度固定为屏幕的 88%，高度自适应
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setLayout((int) (getResources().getDisplayMetrics().widthPixels * WIDTH_RATIO),
                WindowManager.LayoutParams.WRAP_CONTENT);
        window.setDimAmount(0.5f);
    }

    /**
     * 关闭弹窗时若勾选了“今日不再提醒”，记录当天日期，首页下次便不再自动弹出。
     */
    @Override
    public void onDismiss(@NonNull android.content.DialogInterface dialog) {
        if (showSnooze && cbNoRemindToday != null && cbNoRemindToday.isChecked()) {
            Context context = getContext();
            if (context != null) {
                context.getSharedPreferences(DailySuggestionBottomSheet.PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putString(DailySuggestionBottomSheet.KEY_SNOOZE_DATE,
                                CycleCalculator.formatDateId(new Date()))
                        .apply();
            }
        }
        super.onDismiss(dialog);
    }

    /**
     * 刷新日期标题、阶段标签与建议内容。
     */
    private void updateDisplay() {
        if (currentDate == null) return;

        tvDate.setText(DATE_DISPLAY_FORMAT.format(currentDate));

        // 记录 id 就是 yyyyMMdd，直接按日期查当天的记录
        String dateId = CycleCalculator.formatDateId(currentDate);
        // 切换日期时清空上一天的天气，避免旧天气参与新日期的建议生成
        currentWeather = null;

        MenstrualRecord record = recordDao.selectById(dateId);
        MenstrualCycle cycle = record != null ? record.getMenstrualCycle() : null;
        // 天气暖心提醒要结合当天阶段：没有记录时用推算出的预测阶段
        MenstrualCycle displayPhase = cycle;

        if (cycle != null) {
            tvPhase.setText(cycle.getDisplayName(requireContext()));
            tvPhase.setBackgroundTintList(
                    ContextCompat.getColorStateList(requireContext(), getPhaseColor(cycle)));
        } else {
            // 当天还没有落库记录（典型场景：跨零点后进入应用，新一天的预测记录尚未生成）：
            // 先按当前周期参数推算阶段，避免把“有预测的今天”误显示成“无记录”
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

        // 建议正文由引擎按当天信号实时生成；记录与阶段暂存，供天气回调时复用
        lastRecord = record;
        lastDisplayPhase = displayPhase;
        // 切换日期后依据回到收起状态，避免把上一天的说明带到新日期
        insightExpanded = false;
        // 换过的文案只对当天生效，切换日期后重新开放候选
        skippedTemplateIds.clear();
        renderSuggestion(record, displayPhase);

        // 不允许查看今天之后的日期
        Date today = CycleCalculator.normalizeDate(new Date());
        boolean canGoNext = CycleCalculator.normalizeDate(currentDate).before(today);
        tvNextDay.setEnabled(canGoNext);
        tvNextDay.setAlpha(canGoNext ? 1.0f : 0.5f);

        // 天气按当前查看的日期展示；今天的天气额外结合阶段生成暖心提醒
        if (weatherSection != null) {
            weatherSection.show(currentDate, displayPhase);
        }
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

        if (offset > 0 && newDate.after(today)) {
            return;
        }

        currentDate = cal.getTime();
        updateDisplay();
    }

    /**
     * 用建议引擎实时生成正文。
     * <p>与底部弹窗共用同一套引擎与模板：同一天结果稳定，跨天轮转不重复；
     * 数据库里的 {@code suggestion} 仅作为引擎无内容时的兜底。
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
        // 实际记录用于规律性、连续打卡与痛经趋势
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
     * 引擎不可用时的兜底文案：优先用记录里已写入的建议，其次用阶段静态建议。
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
     * 按当前周期参数推算指定日期的预测阶段。
     * <p>用于当天没有落库记录时仍能展示预测结果，与底部弹窗
     * {@link DailySuggestionBottomSheet} 的口径保持一致。
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
     * 取阶段对应的静态建议文案（与日历页/底部弹窗保持一致）。
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
     * 阶段标签底色，与日历、统计页保持一致的阶段配色。
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
}
