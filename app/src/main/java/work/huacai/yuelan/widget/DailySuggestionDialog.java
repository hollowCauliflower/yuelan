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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.checkbox.MaterialCheckBox;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

import work.huacai.yuelan.R;
import work.huacai.yuelan.dao.MenstrualCycleRecordDao;
import work.huacai.yuelan.dao.MenstrualRecordDao;
import work.huacai.yuelan.dao.impl.MenstrualCycleRecordDaoImpl;
import work.huacai.yuelan.dao.impl.MenstrualRecordDaoImpl;
import work.huacai.yuelan.helper.CycleCalculator;
import work.huacai.yuelan.model.MenstrualCycle;
import work.huacai.yuelan.model.MenstrualCycleRecord;
import work.huacai.yuelan.model.MenstrualRecord;

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
    private static final float WIDTH_RATIO = 0.88f;

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
    private TextView tvPrevDay;
    private TextView tvNextDay;
    private MaterialCheckBox cbNoRemindToday;

    /** 是否显示“今日不再提醒”：自动弹出时为 true，悬浮按钮手动打开时为 false */
    private boolean showSnooze;
    /** 当前展示的日期 */
    private Date currentDate;
    private MenstrualRecordDao recordDao;
    private MenstrualCycleRecordDao cycleRecordDao;

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
        tvSuggestion = view.findViewById(R.id.tv_suggestion);
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
        MenstrualRecord record = recordDao.selectById(dateId);
        MenstrualCycle cycle = record != null ? record.getMenstrualCycle() : null;
        // 天气暖心提醒要结合当天阶段：没有记录时用推算出的预测阶段
        MenstrualCycle displayPhase = cycle;

        if (cycle != null) {
            tvPhase.setText(cycle.getDisplayName(requireContext()));
            tvPhase.setBackgroundTintList(
                    ContextCompat.getColorStateList(requireContext(), getPhaseColor(cycle)));
            tvSuggestion.setText(record.getSuggestion() != null
                    ? record.getSuggestion()
                    : getString(R.string.record_adapter_no_suggestion));
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
                tvSuggestion.setText(generateSuggestionForPhase(predictedCycle));
            } else {
                // 无法推算预测阶段：显示占位文案与灰色标签
                tvPhase.setText(getString(R.string.daily_suggestion_no_record));
                tvPhase.setBackgroundTintList(
                        ContextCompat.getColorStateList(requireContext(), R.color.gray));
                tvSuggestion.setText(getString(R.string.daily_suggestion_no_record_suggestion));
            }
        }

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
