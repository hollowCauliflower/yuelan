package work.huacai.yuelan.fragments;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.Fragment;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.datepicker.CalendarConstraints;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.slider.Slider;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

import work.huacai.yuelan.R;
import work.huacai.yuelan.dao.MenstrualCycleRecordDao;
import work.huacai.yuelan.dao.impl.MenstrualCycleRecordDaoImpl;
import work.huacai.yuelan.helper.CycleCalculator;
import work.huacai.yuelan.model.MenstrualCycleRecord;
import work.huacai.yuelan.widget.AppDialogFactory;

/**
 * 引导流程的第二页（基础设置页）。
 * <p>
 * 让用户在首次使用时选择“上次月经开始日期”“月经周期长度”“经期天数”，
 * 用户翻到加载页后由 {@code OnboardingActivity} 调用
 * {@link #saveUserSettingsAndInitData()} 把设置写入数据库并初始化数据。
 */
public class SetupFragment extends Fragment {
    private static final String TAG = "SetupFragment";

    private TextView tvLastPeriodDate;
    private TextView tvCycleDays;
    private TextView tvPeriodDays;
    private Slider sliderCycle;
    private Slider sliderPeriod;
    private ImageView btnTips;
    private MaterialCardView cardDatePicker;

    /** 记录用户选择的开始日期，默认今天 */
    private Calendar calendar;
    private SimpleDateFormat dateFormat;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_setup, container, false);

        initViews(view);
        setupDatePicker();
        setupSliders();
        setupTipsButton();

        return view;
    }

    /**
     * 绑定控件并初始化默认展示（开始日期默认今天，天数取滑块的默认值）。
     *
     * @param view 设置页根视图
     */
    private void initViews(View view) {
        tvLastPeriodDate = view.findViewById(R.id.tvLastPeriodDate);
        tvCycleDays = view.findViewById(R.id.tvCycleDays);
        tvPeriodDays = view.findViewById(R.id.tvPeriodDays);
        sliderCycle = view.findViewById(R.id.sliderCycle);
        sliderPeriod = view.findViewById(R.id.sliderPeriod);
        btnTips = view.findViewById(R.id.btnTips);
        cardDatePicker = view.findViewById(R.id.card_date_picker);

        calendar = Calendar.getInstance();
        dateFormat = new SimpleDateFormat("yyyy年MM月dd日", Locale.getDefault());

        // 默认设置为今天
        tvLastPeriodDate.setText(dateFormat.format(calendar.getTime()));
        tvCycleDays.setText(getString(R.string.fragment_setup_cycle_default_length_text,
                (int) sliderCycle.getValue()));
        tvPeriodDays.setText(getString(R.string.fragment_setup_menstrual_default_length_text,
                (int) sliderPeriod.getValue()));
    }

    /**
     * 为日期卡片注册点击事件。
     */
    private void setupDatePicker() {
        cardDatePicker.setOnClickListener(v -> showDatePickerDialog());
    }

    /**
     * 弹出日期选择器（Material 风格）：范围限制在一年前到今天，选定后更新展示并播放点击反馈动画。
     */
    private void showDatePickerDialog() {
        Calendar minDate = Calendar.getInstance();
        // 设置最小日期为一年前
        minDate.add(Calendar.YEAR, -1);

        CalendarConstraints constraints = new CalendarConstraints.Builder()
                .setStart(CycleCalculator.toUtcMidnight(minDate))
                // 最大可选日期为今天（用“今天 0 点”而不是 now()：凌晨 0~8 点时
                // now() 的 UTC 时间还停在前一天，会把今天误判为未来而禁选）
                .setEnd(CycleCalculator.toUtcMidnight(Calendar.getInstance()))
                .build();

        MaterialDatePicker<Long> picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText(R.string.fragment_setup_select_date_text)
                .setSelection(CycleCalculator.toUtcMidnight(calendar))
                .setCalendarConstraints(constraints)
                .build();

        picker.addOnPositiveButtonClickListener(selection -> {
            if (selection == null) {
                return;
            }
            Date picked = CycleCalculator.localFromUtc(selection);
            // 双保险：即使日期控件被绕过，也不允许把开始日设到今天之后
            if (CycleCalculator.normalizeDate(picked).after(
                    CycleCalculator.normalizeDate(new Date()))) {
                AppDialogFactory.showConfirm(requireContext(),
                        getString(R.string.cycle_start_after_today_title),
                        getString(R.string.cycle_start_after_today_message),
                        R.string.cycle_start_after_today_reselect,
                        R.string.dialog_cancel,
                        this::showDatePickerDialog);
                return;
            }
            calendar.setTime(picked);
            tvLastPeriodDate.setText(dateFormat.format(calendar.getTime()));

            // 卡片轻微缩放，作为点击反馈
            playPulseAnimation(cardDatePicker, 0.95f, 100);
        });
        picker.show(getChildFragmentManager(), "setup_date_picker");
    }

    /**
     * 配置两个滑块的数值展示、点击反馈动画和标签文案。
     */
    private void setupSliders() {
        // 周期长度滑块
        sliderCycle.addOnChangeListener((slider, value, fromUser) -> {
            int intValue = (int) value;
            tvCycleDays.setText(getString(R.string.fragment_setup_cycle_default_length_text, intValue));

            // 周期缩短后经期可能放不下，立即把经期滑块收回到允许上限
            limitPeriodSlider(intValue);

            // 数值变化时放大再回弹，突出数值已变化
            playPulseAnimation(tvCycleDays, 1.2f, 150);
        });

        // 经期天数滑块
        sliderPeriod.addOnChangeListener((slider, value, fromUser) -> {
            int maxPeriod = getMaxPeriodForCurrentCycle();
            if (value > maxPeriod) {
                // 超过上限时拒绝本次取值并提示，避免阶段天数被挤爆
                slider.setValue(maxPeriod);
                Toast.makeText(requireContext(),
                        getString(R.string.settings_menstrual_days_too_long,
                                (int) sliderCycle.getValue(), maxPeriod),
                        Toast.LENGTH_SHORT).show();
                return;
            }

            int intValue = (int) value;
            tvPeriodDays.setText(getString(R.string.fragment_setup_menstrual_default_length_text, intValue));

            playPulseAnimation(tvPeriodDays, 1.2f, 150);
        });

        // 设置提示标签：根据数值区间给出周期长短的描述
        sliderCycle.setLabelFormatter(value -> {
            int intValue = (int) value;
            if (intValue < 25) return "较短周期";
            else if (intValue <= 35) return "正常周期";
            else return "较长周期";
        });

        // 经期天数对应的提示标签
        sliderPeriod.setLabelFormatter(value -> {
            int intValue = (int) value;
            if (intValue < 4) return "较短经期";
            else if (intValue <= 7) return "正常经期";
            else return "较长经期";
        });
    }

    /**
     * 周期变化时，把经期滑块收回到当前周期允许的上限，并提示用户。
     *
     * @param cycleDays 当前选中的周期天数
     */
    private void limitPeriodSlider(int cycleDays) {
        int maxPeriod = Math.min((int) sliderPeriod.getValueTo(),
                CycleCalculator.getMaxMenstrualDays(cycleDays));
        if ((int) sliderPeriod.getValue() > maxPeriod) {
            sliderPeriod.setValue(maxPeriod);
            Toast.makeText(requireContext(),
                    getString(R.string.settings_menstrual_days_too_long, cycleDays, maxPeriod),
                    Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * @return 当前周期天数下经期滑块的允许上限（同时不超出滑块自身的上限）
     */
    private int getMaxPeriodForCurrentCycle() {
        return Math.min((int) sliderPeriod.getValueTo(),
                CycleCalculator.getMaxMenstrualDays((int) sliderCycle.getValue()));
    }

    /**
     * 配置提示按钮：点击展示说明弹窗，并在延迟 1 秒后播放一次轻微放大动画引导点击。
     */
    private void setupTipsButton() {
        btnTips.setOnClickListener(v -> showTipsBottomSheet());

        // 添加按钮闪烁动画
        btnTips.postDelayed(() -> playPulseAnimation(btnTips, 1.05f, 500), 1000);
    }

    /**
     * 展示底部说明弹窗，弹窗背景透明以保留布局中定义的圆角。
     */
    private void showTipsBottomSheet() {
        BottomSheetDialog bottomSheetDialog = new BottomSheetDialog(requireContext());
        bottomSheetDialog.setContentView(R.layout.bottom_sheet_tips);
        // 统一底部弹窗规范：透明背景、导航栏配色与底部系统栏内边距
        AppDialogFactory.applyBottomSheetWindowStyle(bottomSheetDialog);
        bottomSheetDialog.show();

        // 绑定关闭弹窗事件
        MaterialButton btnClose = bottomSheetDialog.findViewById(R.id.btnCloseTips);
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> bottomSheetDialog.dismiss());
        }

        // 按钮点击动画
        playPulseAnimation(btnTips, 0.9f, 100);
    }

    /**
     * 执行“放大（或缩小）后回弹”的反馈动画。
     *
     * @param target   执行动画的视图
     * @param scale    目标缩放比例
     * @param duration 单程动画时长（毫秒），回弹使用相同时长
     */
    private void playPulseAnimation(View target, float scale, long duration) {
        target.animate()
                .scaleX(scale)
                .scaleY(scale)
                .setDuration(duration)
                .withEndAction(() -> target.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(duration)
                        .start())
                .start();
    }

    /**
     * 保存用户设置并初始化数据库数据（由引导页在后台线程调用）。
     *
     * @return 保存成功返回 true，失败返回 false
     */
    public boolean saveUserSettingsAndInitData() {
        int cycleDays = (int) sliderCycle.getValue();
        int periodDays = Math.min((int) sliderPeriod.getValue(),
                CycleCalculator.getMaxMenstrualDays(cycleDays));

        MenstrualCycleRecord menstrualCycleRecord = new MenstrualCycleRecord();
        menstrualCycleRecord.setStartTime(calendar.getTime());
        menstrualCycleRecord.setTotalCycleDay(cycleDays);
        menstrualCycleRecord.setMenstrualPeriodDay(periodDays);
        MenstrualCycleRecordDao menstrualCycleRecordDao = new MenstrualCycleRecordDaoImpl();
        boolean flag = menstrualCycleRecordDao.saveUserSettingsAndInitData(menstrualCycleRecord, getContext());
        if (flag) {
            Log.d(TAG, "设置已保存:上次月经开始时间 " + menstrualCycleRecord.getStartTime()
                    + ",月经周期 " + menstrualCycleRecord.getTotalCycleDay()
                    + "天,经期 " + menstrualCycleRecord.getMenstrualPeriodDay() + "天");
            return true;
        } else {
            Log.e(TAG, "数据保存失败");
            return false;
        }
    }
}
