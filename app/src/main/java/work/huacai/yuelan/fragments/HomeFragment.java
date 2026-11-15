package work.huacai.yuelan.fragments;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.datepicker.CalendarConstraints;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import work.huacai.yuelan.R;
import work.huacai.yuelan.dao.MenstrualCycleRecordDao;
import work.huacai.yuelan.dao.MenstrualRecordDao;
import work.huacai.yuelan.dao.ReminderDao;
import work.huacai.yuelan.dao.impl.MenstrualCycleRecordDaoImpl;
import work.huacai.yuelan.dao.impl.MenstrualRecordDaoImpl;
import work.huacai.yuelan.dao.impl.ReminderDaoImpl;
import work.huacai.yuelan.helper.CycleCalculator;
import work.huacai.yuelan.helper.DailyPromptHelper;
import work.huacai.yuelan.helper.PeriodReminderScheduler;
import work.huacai.yuelan.helper.GenerateSuggestionHelper;
import work.huacai.yuelan.helper.OpenMeteoHelper;
import work.huacai.yuelan.helper.ReminderAlarmScheduler;
import work.huacai.yuelan.helper.ReminderCycleHelper;
import work.huacai.yuelan.helper.WeatherCacheHelper;
import work.huacai.yuelan.helper.WeatherLocationHelper;
import work.huacai.yuelan.model.MenstrualCycle;
import work.huacai.yuelan.model.MenstrualCycleRecord;
import work.huacai.yuelan.model.MenstrualRecord;
import work.huacai.yuelan.model.Reminder;
import work.huacai.yuelan.widget.AppDialogFactory;
import work.huacai.yuelan.widget.CircularPeriodProgressBar;
import work.huacai.yuelan.widget.DailySuggestionBottomSheet;
import work.huacai.yuelan.widget.DailySuggestionDialog;
import work.huacai.yuelan.widget.RecordDetailBottomSheet;

/**
 * 首页（记录页）Fragment。
 * <p>
 * 页面职责：
 * 1. 展示当前周期的环形进度，以及“第几天 / 总天数 / 剩余天数 / 下次月经 / 下一阶段”信息；
 * 2. 四个阶段文本点击后，弹出该阶段的起止日期气泡；
 * 3. 右下角悬浮按钮：主按钮展开“流量 / 痛经 / 今日建议”三个子按钮，点击后打开对应弹窗；
 * 4. 进入页面时自动补全从最后一条记录到今天的预测记录，并异步拉取天气生成建议文案；
 * 5. 进入页面自动弹出一次“今日建议”（用户勾选当天不再提醒后不再弹）。
 */
public class HomeFragment extends Fragment {

    private static final String TAG = "HomeFragment";
    private static final int LOCATION_PERMISSION_REQUEST = 100;
    /** 与其他页面共用的应用配置（含定位权限是否已询问过的标记） */
    private static final String PREF_CONFIG = "menstruation_config";
    /** 定位权限是否已询问过：只问一次，用户拒绝后不再反复弹系统授权页 */
    private static final String KEY_LOCATION_PERMISSION_ASKED = "location_permission_asked";
    /** 缺少周期数据时显示的占位符 */
    private static final String PLACEHOLDER = "--";

    private CircularPeriodProgressBar circularProgressBar;
    private TextView currentDayView;
    private TextView totalDayView;
    private TextView remainingDayView;
    private TextView tvNextMenstrualTime;
    private TextView tvNextPhase;
    private View cardRecordDetail;
    private TextView tvFlowLevel;
    private TextView tvPainLevel;
    /** 今日自定义提醒卡片与其中的条目容器 */
    private View cardTodayReminders;
    private LinearLayout layoutTodayReminders;
    private View fabRecord;
    private View fabMenuContainer;
    private View fabFlow;
    private View fabPain;
    private View fabSuggestion;
    /** 三个子 FAB 的容器，统一做展开/收起动画 */
    private View[] fabItemContainers;
    private View view;

    private boolean isFabMenuOpen = false;
    private float fabRotation = 0f;
    /** 当前是否正显示每日引导弹窗，避免多次 onResume 导致弹窗堆叠 */
    private boolean dailyPromptShowing = false;
    /**
     * 本次进程内已经自动弹过今日建议的日期（yyyyMMdd）。
     * <p>切换主题等操作会重建 Activity 与 Fragment，实例字段会被重置，
     * 但用户并没有“重新进入”应用，因此需要进程级记录避免同一天反复弹出。
     */
    private static String lastAutoSuggestionDate;

    private MenstrualCycleRecord menstrualCycleRecord;
    /** 四个阶段的持续天数：[月经期, 卵泡期, 排卵期, 黄体期] */
    private int[] phaseArray;
    private Date startTime;
    private int totalCycleDay;
    private int menstrualPeriodDay;
    private Date currentCycleStart;

    /** 四个阶段文本共用的点击回调：按被点击的控件定位阶段序号，再查该阶段的日期范围 */
    private final View.OnClickListener phaseClickListener = v -> {
        if (currentCycleStart == null || phaseArray == null) {
            Log.w(TAG, "phaseClickListener: data not initialized");
            return;
        }

        int phase = 0;
        int id = v.getId();
        if (id == R.id.tv_phase1) phase = 1;
        else if (id == R.id.tv_phase2) phase = 2;
        else if (id == R.id.tv_phase3) phase = 3;
        else if (id == R.id.tv_phase4) phase = 4;

        Date[] dateRange = CycleCalculator.calculatePhaseDateRange(phase, currentCycleStart, phaseArray, totalCycleDay);
        if (dateRange != null) {
            showPopupWindowDateRange(v, dateRange);
        } else {
            Log.w(TAG, "dateRange is null");
        }
    };

    /**
     * 在指定控件下方弹出气泡，展示传入的起止日期
     *
     * @param anchorView 气泡的锚点控件（被点击的阶段文本）
     * @param dateRange  长度为 2 的数组：阶段开始、阶段结束
     */
    private void showPopupWindowDateRange(View anchorView, Date[] dateRange) {
        if (getView() == null) {
            Log.w(TAG, "parent view is null, can't show popup window");
            return;
        }

        View popupView = LayoutInflater.from(requireContext())
                .inflate(R.layout.popup_phase_info, (ViewGroup) getView(), false);
        TextView tvDate = popupView.findViewById(R.id.tv_date_range);

        String dateText = CycleCalculator.formatDateDisplay(dateRange[0]) + " - " + CycleCalculator.formatDateDisplay(dateRange[1]);
        tvDate.setText(dateText);

        PopupWindow popupWindow = new PopupWindow(
                popupView,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true
        );

        popupWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popupWindow.setAnimationStyle(R.style.PopupAnimation);
        popupWindow.setOutsideTouchable(true);
        // 向左偏移 100px、向下偏移 10px，让气泡大致居中于阶段文本
        popupWindow.showAsDropDown(anchorView, -100, 10);
    }

    public HomeFragment() {
    }

    /**
     * 创建视图：加载周期数据后先刷新一次界面，再执行数据补全
     */
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        view = inflater.inflate(R.layout.fragment_home, container, false);
        initViews();
        displayCycleInfo();
        initData();
        return view;
    }

    /**
     * 每次回到首页都刷新周期信息、今日记录卡片，并在满足条件时自动弹出今日建议
     */
    @Override
    public void onResume() {
        super.onResume();
        displayCycleInfo();
        requestLocationPermission();
        // 提前预热今天/明天的天气缓存：自动弹出的今日建议通常能直接读到，避免天气行迟到导致的弹窗跳动
        OpenMeteoHelper.prefetchDailyWeather(requireContext());
        updateTodayRecord();
        updateTodayReminders();
        // 每日引导（经期/排卵期/临近经期）优先于每日建议，避免两个弹窗同时弹出
        boolean promptShown = maybeShowDailyPrompt();
        if (!promptShown) {
            maybeShowDailySuggestion();
        }
    }

    /**
     * 定位权限结果：授权后天气来源会从默认城市变成真实定位，
     * 需要清掉默认城市缓存并重新预热，避免当天继续显示兜底城市的天气。
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != LOCATION_PERMISSION_REQUEST) {
            return;
        }
        if (!WeatherLocationHelper.isDeviceMode(requireContext())) {
            return;
        }
        WeatherCacheHelper.clear(requireContext());
        OpenMeteoHelper.prefetchDailyWeather(requireContext());
    }

    /**
     * 绑定控件、设置阶段点击与 FAB 交互，并初始化子 FAB 的收起状态
     */
    private void initViews() {
        circularProgressBar = view.findViewById(R.id.circular_progress_bar);
        currentDayView = view.findViewById(R.id.tv_current_day);
        totalDayView = view.findViewById(R.id.tv_total_days);
        remainingDayView = view.findViewById(R.id.tv_remaining_days);
        tvNextMenstrualTime = view.findViewById(R.id.tv_next_period);
        tvNextPhase = view.findViewById(R.id.tv_next_phase);
        cardRecordDetail = view.findViewById(R.id.card_record_detail);
        tvFlowLevel = view.findViewById(R.id.tv_flow_level);
        tvPainLevel = view.findViewById(R.id.tv_pain_level);
        cardTodayReminders = view.findViewById(R.id.card_today_reminders);
        layoutTodayReminders = view.findViewById(R.id.layout_today_reminders);
        fabRecord = view.findViewById(R.id.fab_record);
        fabMenuContainer = view.findViewById(R.id.fab_menu_container);
        fabFlow = view.findViewById(R.id.fab_flow);
        fabPain = view.findViewById(R.id.fab_pain);
        fabSuggestion = view.findViewById(R.id.fab_suggestion);

        fabItemContainers = new View[]{
                view.findViewById(R.id.fab_flow_container),
                view.findViewById(R.id.fab_pain_container),
                view.findViewById(R.id.fab_suggestion_container)
        };

        // 子 FAB 初始状态（隐藏并下移，展开时做上移动画）
        for (View container : fabItemContainers) {
            container.setAlpha(0f);
            container.setTranslationY(50f);
        }

        TextView tvPhase1 = view.findViewById(R.id.tv_phase1);
        TextView tvPhase2 = view.findViewById(R.id.tv_phase2);
        TextView tvPhase3 = view.findViewById(R.id.tv_phase3);
        TextView tvPhase4 = view.findViewById(R.id.tv_phase4);

        tvPhase1.setOnClickListener(phaseClickListener);
        tvPhase2.setOnClickListener(phaseClickListener);
        tvPhase3.setOnClickListener(phaseClickListener);
        tvPhase4.setOnClickListener(phaseClickListener);

        fabRecord.setOnClickListener(v -> toggleFabMenu());
        // 流量与痛经两个子按钮分别打开对应模式的记录表单（只展示各自的表单区）
        fabFlow.setOnClickListener(v -> {
            closeFabMenu();
            showRecordDialog(RecordDetailBottomSheet.MODE_FLOW);
        });
        fabPain.setOnClickListener(v -> {
            closeFabMenu();
            showRecordDialog(RecordDetailBottomSheet.MODE_PAIN);
        });
        fabSuggestion.setOnClickListener(v -> {
            closeFabMenu();
            showDailySuggestionBottomSheet();
        });

        // 点击其他区域关闭菜单
        view.setOnClickListener(v -> {
            if (isFabMenuOpen) {
                closeFabMenu();
            }
        });
    }

    /**
     * 展开/收起悬浮菜单
     */
    private void toggleFabMenu() {
        if (isFabMenuOpen) {
            closeFabMenu();
        } else {
            openFabMenu();
        }
    }

    /**
     * 展开菜单：主 FAB 旋转成叉号，子 FAB 依次上移淡入（每个延迟 50ms 形成错落效果）。
     * <p>
     * “记录流量 / 记录疼痛”只在今天处于月经期时展示；每次展开都实时判断，
     * 因此用户在记录弹窗里把今天改为经期后，重新打开菜单即可看到入口。
     */
    private void openFabMenu() {
        isFabMenuOpen = true;

        // 主 FAB 旋转 45°（加号变叉号）
        rotateFab(45f);

        boolean todayMenstrual = isTodayMenstrual();
        // 显示菜单容器，子 FAB 依次上移淡入
        fabMenuContainer.setVisibility(View.VISIBLE);
        for (int i = 0; i < fabItemContainers.length; i++) {
            View container = fabItemContainers[i];
            // 前两个是流量/疼痛入口，仅月经期展示；建议入口始终展示
            boolean show = i >= 2 || todayMenstrual;
            container.setVisibility(show ? View.VISIBLE : View.GONE);
            if (!show) {
                continue;
            }
            // 重置到收起态再做展开动画，避免上次动画残留的透明度/位移
            container.setAlpha(0f);
            container.setTranslationY(50f);
            container.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(300)
                    .setStartDelay(50L * i)
                    .setInterpolator(new OvershootInterpolator(1.2f))
                    .start();
        }
    }

    /**
     * 判断今天是否处于月经期。
     * <p>优先读今日已保存记录的阶段；没有记录时按当前周期参数推算。
     *
     * @return true 表示今天是月经期
     */
    private boolean isTodayMenstrual() {
        String todayId = CycleCalculator.formatDateId(new Date());
        MenstrualRecord record = new MenstrualRecordDaoImpl().selectById(todayId);
        if (record != null && record.getMenstrualCycle() != null) {
            return record.getMenstrualCycle() == MenstrualCycle.MENSTRUAL_PERIOD;
        }
        if (menstrualCycleRecord == null) {
            return false;
        }
        // 与首页/日历同源：按实际记录 + 周期设置 + 阶段锚点取今天的阶段
        MenstrualCycle cycle = ReminderCycleHelper.phaseOn(new Date());
        return cycle == MenstrualCycle.MENSTRUAL_PERIOD;
    }

    /**
     * 收起菜单：子 FAB 按相反顺序下移淡出，全部动画结束后隐藏菜单容器
     */
    private void closeFabMenu() {
        isFabMenuOpen = false;

        // 主 FAB 转回原位
        rotateFab(-45f);

        // 子 FAB 依次下移淡出，全部结束后隐藏容器
        for (int i = 0; i < fabItemContainers.length; i++) {
            View container = fabItemContainers[i];
            container.animate()
                    .alpha(0f)
                    .translationY(50f)
                    .setDuration(200)
                    .setStartDelay(50L * (fabItemContainers.length - 1 - i))
                    .setInterpolator(new AccelerateDecelerateInterpolator())
                    .withEndAction(() -> {
                        // 动画结束时若菜单又被打开，则不要隐藏容器
                        if (!isFabMenuOpen) {
                            fabMenuContainer.setVisibility(View.GONE);
                        }
                    })
                    .start();
        }
    }

    /** 主 FAB 旋转动画，angle 为本次增量角度 */
    private void rotateFab(float angle) {
        ObjectAnimator animator = ObjectAnimator.ofFloat(fabRecord, "rotation", fabRotation, fabRotation + angle);
        animator.setDuration(200);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        animator.start();
        fabRotation += angle;
    }

    /**
     * 打开今日记录弹窗。
     *
     * @param mode 入口模式，决定只展示流量还是只展示痛经表单
     */
    private void showRecordDialog(String mode) {
        String todayId = CycleCalculator.formatDateId(new Date());
        MenstrualRecord record = new MenstrualRecordDaoImpl().selectById(todayId);
        if (record == null) {
            // 今天还没有记录：创建一条空白记录，并带上当天所属阶段
            record = new MenstrualRecord();
            record.setId(todayId);
            if (menstrualCycleRecord != null) {
                // 新记录的默认阶段同样要按阶段锚点推算，避免写入与界面显示不一致的阶段
                record.setMenstrualCycle(ReminderCycleHelper.phaseOn(new Date()));
            }
            record.setIsPrediction(0);
        }
        RecordDetailBottomSheet bottomSheet = RecordDetailBottomSheet.newInstance(record, mode);
        // 保存后刷新首页今日记录卡片，并让基准日/预测按最新实际记录重算
        bottomSheet.setOnRecordSavedListener(() -> {
            updateTodayRecord();
            displayCycleInfo();
            // 实际记录会移动预测基准日（displayCycleInfo 已同步），随后重排经期提醒
            PeriodReminderScheduler.apply(requireContext());
        });
        bottomSheet.show(getChildFragmentManager(), "RecordDetailBottomSheet");
    }

    /** FAB 手动打开“今日建议”：使用底部弹窗，与进入首页的居中弹窗区分开 */
    private void showDailySuggestionBottomSheet() {
        String todayId = CycleCalculator.formatDateId(new Date());
        // 悬浮按钮属于主动查看，不显示“今日不再提醒”选项
        DailySuggestionBottomSheet bottomSheet =
                DailySuggestionBottomSheet.newInstance(todayId, false);
        bottomSheet.show(getChildFragmentManager(), "DailySuggestionBottomSheet");
    }

    /**
     * 弹出每日建议居中弹窗。
     *
     * @param fromAuto true 表示进入应用自动弹出（带“今日不再提醒”）；
     *                 false 表示悬浮按钮手动打开（不带该选项）
     */
    private void showDailySuggestionDialog(boolean fromAuto) {
        String todayId = CycleCalculator.formatDateId(new Date());
        DailySuggestionDialog dialog = DailySuggestionDialog.newInstance(todayId, fromAuto);
        dialog.show(getChildFragmentManager(), "DailySuggestionDialog");
    }

    /**
     * 进入首页时自动弹出每日建议：每次页面生命周期只弹一次，
     * 用户勾选“今日不再提醒”后当天不再弹出。
     */
    private void maybeShowDailySuggestion() {
        if (!DailySuggestionBottomSheet.shouldRemindToday(requireContext())) return;

        // 同一天在进程内只自动弹一次；日期变化后（含跨零点）允许新的一天再弹
        String todayId = CycleCalculator.formatDateId(new Date());
        if (todayId.equals(lastAutoSuggestionDate)) return;

        lastAutoSuggestionDate = todayId;
        // 延迟一点，避免和页面切换动画叠在一起
        view.postDelayed(() -> showDailySuggestionDialog(true), 400);
    }

    /**
     * 进入首页时判断是否需要弹出每日引导（经期/排卵期/临近经期）。
     * <p>
     * 当天属于关键日期、用户没勾选"今天不再提醒"、且该类型今天还没自动弹过时才会弹出；
     * 每天每类引导最多自动弹一次，避免反复回首页被连续打扰。
     *
     * @return true 表示本次已弹出引导（调用方可据此跳过每日建议，避免双弹窗）
     */
    private boolean maybeShowDailyPrompt() {
        if (dailyPromptShowing) return false;
        if (menstrualCycleRecord == null) return false;

        DailyPromptHelper.PromptType type =
                DailyPromptHelper.getPromptType(requireContext());
        if (type == DailyPromptHelper.PromptType.NONE) {
            return false;
        }
        // 每天每类只自动弹一次：今天已弹过同类型就不再弹
        if (DailyPromptHelper.wasShownToday(requireContext(), type)) {
            return false;
        }

        dailyPromptShowing = true;
        DailyPromptHelper.markShownToday(requireContext(), type);
        // 延迟一点，避免和页面切换动画叠在一起
        view.postDelayed(() -> showDailyPrompt(type), 400);
        return true;
    }

    /**
     * 按类型弹出对应的每日引导弹窗。
     */
    private void showDailyPrompt(DailyPromptHelper.PromptType type) {
        switch (type) {
            case MENSTRUAL_PERIOD:
                showMenstrualPrompt();
                break;
            case OVULATION:
                showInfoPrompt(R.string.daily_prompt_ovulation_title,
                        R.string.daily_prompt_ovulation_message);
                break;
            case APPROACHING_PERIOD:
                showInfoPrompt(R.string.daily_prompt_approaching_title,
                        R.string.daily_prompt_approaching_message);
                break;
            default:
                dailyPromptShowing = false;
                break;
        }
    }

    /**
     * 月经期引导弹窗：带"记录一下"与"周期不准？"两个入口，以及"今天不再提醒"勾选框。
     * <p>
     * 使用品牌化布局 {@code dialog_menstrual_prompt}，勾选框位于两个按钮下方，
     * 与每日建议弹窗的脚注位置保持一致。
     * 只要勾选了"今天不再提醒"，无论用户点哪个按钮还是直接关闭弹窗，
     * 当天都不再弹出引导（在 onDismiss 中统一落库，避免只在按钮回调里保存导致漏存）。
     */
    private void showMenstrualPrompt() {
        android.app.Dialog dialog = new android.app.Dialog(requireContext(), R.style.Theme_Yuelan_Dialog);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_menstrual_prompt);

        android.widget.CheckBox checkBox = dialog.findViewById(R.id.cb_no_remind_today);

        dialog.findViewById(R.id.btn_record).setOnClickListener(v -> {
            dialog.dismiss();
            showRecordDialog(RecordDetailBottomSheet.MODE_ALL);
        });
        dialog.findViewById(R.id.btn_cycle_wrong).setOnClickListener(v -> {
            dialog.dismiss();
            showCycleCorrectionDialog();
        });

        dialog.setOnDismissListener(d -> {
            // 关闭时统一处理：勾选了就标记今天不再提醒
            if (checkBox.isChecked()) {
                DailyPromptHelper.markSkipToday(requireContext());
            }
            dailyPromptShowing = false;
        });

        dialog.show();
        // 与其他品牌弹窗一致：透明窗口背景 + 88% 屏宽 + 0.5 遮罩
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                    android.graphics.Color.TRANSPARENT));
            window.setDimAmount(0.5f);
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.88f);
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    /**
     * 排卵期 / 临近经期的纯提示弹窗，只有一个"知道了"按钮。
     * <p>无论点按钮还是点外部关闭，都要重置 {@code dailyPromptShowing}，否则会阻塞后续引导。
     */
    private void showInfoPrompt(int titleRes, int messageRes) {
        android.app.Dialog dialog = AppDialogFactory.showConfirm(requireContext(),
                getString(titleRes),
                getString(messageRes),
                R.string.daily_prompt_got_it, 0, null);
        dialog.setOnDismissListener(d -> dailyPromptShowing = false);
    }

    /**
     * 纠错周期弹窗：可调整周期天数、经期天数，并重设本次经期起始日。
     * 复用 {@code dialog_adjust_cycle} 布局，与日历页的"调整周期"保持一致。
     */
    private void showCycleCorrectionDialog() {
        if (menstrualCycleRecord == null) {
            dailyPromptShowing = false;
            return;
        }

        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_adjust_cycle, null);

        NumberPicker npCycleDays = dialogView.findViewById(R.id.np_cycle_days);
        NumberPicker npMenstrualDays = dialogView.findViewById(R.id.np_menstrual_days);
        TextView tvStartDate = dialogView.findViewById(R.id.tv_start_date);

        npCycleDays.setMinValue(21);
        npCycleDays.setMaxValue(45);
        npCycleDays.setValue(menstrualCycleRecord.getTotalCycleDay());
        npCycleDays.setWrapSelectorWheel(false);

        npMenstrualDays.setMinValue(2);
        npMenstrualDays.setMaxValue(10);
        npMenstrualDays.setValue(menstrualCycleRecord.getMenstrualPeriodDay());
        npMenstrualDays.setWrapSelectorWheel(false);

        final Date[] selectedStartDate = {menstrualCycleRecord.getStartTime()};
        updateStartDateText(tvStartDate, selectedStartDate[0]);
        tvStartDate.setOnClickListener(v -> showStartDatePicker(selectedStartDate, tvStartDate));

        AppDialogFactory.showForm(requireContext(),
                getString(R.string.cycle_correction_title), dialogView,
                R.string.dialog_save, R.string.dialog_cancel, dialog -> {
                    // 上次月经开始日不允许晚于今天：提示后让用户重新选择，
                    // 调整弹窗保持打开，不写入未来日期
                    if (selectedStartDate[0] != null
                            && CycleCalculator.normalizeDate(selectedStartDate[0]).after(
                            CycleCalculator.normalizeDate(new Date()))) {
                        AppDialogFactory.showConfirm(requireContext(),
                                getString(R.string.cycle_start_after_today_title),
                                getString(R.string.cycle_start_after_today_message),
                                R.string.cycle_start_after_today_reselect,
                                R.string.dialog_cancel,
                                () -> showStartDatePicker(selectedStartDate, tvStartDate));
                        return;
                    }
                    int newCycleDays = npCycleDays.getValue();
                    int newMenstrualDays = npMenstrualDays.getValue();
                    applyCycleCorrection(newCycleDays, newMenstrualDays, selectedStartDate[0]);
                    dialog.dismiss();
                });
    }

    /**
     * 刷新起始日文本控件；日期为空时显示"选择日期"占位。
     */
    private void updateStartDateText(TextView tvStartDate, Date date) {
        if (date == null) {
            tvStartDate.setText(R.string.cycle_correction_select_date);
        } else {
            tvStartDate.setText(CycleCalculator.formatDateId(date));
        }
    }

    /**
     * 弹出 Material 日期选择器（范围：今天往前 2 年到今天），选完回写数组并刷新文本。
     */
    private void showStartDatePicker(final Date[] selectedStartDate, final TextView tvStartDate) {
        Calendar calendar = Calendar.getInstance();
        if (selectedStartDate[0] != null) {
            calendar.setTime(selectedStartDate[0]);
        }

        Calendar start = Calendar.getInstance();
        start.add(Calendar.YEAR, -2);
        Calendar end = Calendar.getInstance();

        CalendarConstraints constraints = new CalendarConstraints.Builder()
                .setStart(CycleCalculator.toUtcMidnight(start))
                .setEnd(CycleCalculator.toUtcMidnight(end))
                .build();

        MaterialDatePicker<Long> picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText(R.string.cycle_correction_start_date)
                .setSelection(CycleCalculator.toUtcMidnight(calendar))
                .setCalendarConstraints(constraints)
                .build();

        picker.addOnPositiveButtonClickListener(selection -> {
            if (selection == null) {
                return;
            }
            selectedStartDate[0] = CycleCalculator.localFromUtc(selection);
            updateStartDateText(tvStartDate, selectedStartDate[0]);
        });
        picker.show(getChildFragmentManager(), "start_date_picker");
    }

    /**
     * 应用周期纠错：回写周期设置、重新生成预测记录、刷新首页并重排提醒。
     */
    private void applyCycleCorrection(int newCycleDays, int newMenstrualDays, Date newStartDate) {
        // 先记录原起始日，用于判断用户是否真的改了日期：
        // 只改周期/经期长度时不应把基准日“变成”一条实际经期记录
        Date previousStartDate = menstrualCycleRecord.getStartTime();
        boolean startDateChanged = newStartDate != null
                && (previousStartDate == null
                || !CycleCalculator.formatDateId(newStartDate)
                .equals(CycleCalculator.formatDateId(previousStartDate)));

        menstrualCycleRecord.setTotalCycleDay(newCycleDays);
        menstrualCycleRecord.setMenstrualPeriodDay(newMenstrualDays);
        if (newStartDate != null) {
            menstrualCycleRecord.setStartTime(newStartDate);
        }

        MenstrualCycleRecordDaoImpl dao = new MenstrualCycleRecordDaoImpl();
        if (startDateChanged) {
            // 手动指定起始日：先落为实际经期首日并确保存在对应分段，
            // 否则随后的基准日对齐会按历史实际记录把本次选择回滚（表现为被预测成排卵期）
            dao.applyManualCycleStart(menstrualCycleRecord.getStartTime(),
                    newCycleDays, newMenstrualDays, requireContext());
        } else {
            dao.updateLatest(menstrualCycleRecord);
        }
        // 按新参数重算预测记录，并把基准日对齐到刚指定的起始日
        dao.syncActualStartAndRegenerate(requireContext());
        PeriodReminderScheduler.apply(requireContext());
        // 周期参数/基准日改变后，周期节点与阶段区间提醒的日期也会变，需要重排闹钟
        ReminderAlarmScheduler.rescheduleAll(requireContext());

        // 刷新首页展示
        startTime = menstrualCycleRecord.getStartTime();
        totalCycleDay = newCycleDays;
        menstrualPeriodDay = newMenstrualDays;
        phaseArray = CycleCalculator.calculatePhaseDays(menstrualPeriodDay, totalCycleDay);
        displayCycleInfo();
        updateTodayRecord();
    }

    /**
     * 申请定位权限（天气建议需要定位，拒绝授权只影响建议生成）。
     * <p>
     * 只在首次进入首页时申请一次：每次 onResume 都调用 requestPermissions 会让
     * ColorOS 反复拉起系统授权页（表现为屏幕一直闪），用户拒绝后系统也可能反复
     * 重弹，因此用 SharedPreferences 标记是否已经问过，问过就不再打扰。
     */
    private void requestLocationPermission() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        SharedPreferences prefs = requireContext()
                .getSharedPreferences(PREF_CONFIG, Context.MODE_PRIVATE);
        if (prefs.getBoolean(KEY_LOCATION_PERMISSION_ASKED, false)) {
            return;
        }
        // 先落标记再请求：无论用户允许还是拒绝，后续 onResume 都不会重复申请
        prefs.edit().putBoolean(KEY_LOCATION_PERMISSION_ASKED, true).apply();
        ActivityCompat.requestPermissions(requireActivity(),
                new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                LOCATION_PERMISSION_REQUEST);
    }

    /**
     * 读取周期数据并刷新环形进度、天数文本、下次月经与下一阶段
     */
    private void displayCycleInfo() {
        loadMenstrualCycleRecordData();

        if (menstrualCycleRecord == null) {
            Log.e(TAG, "displayCycleInfo menstrualCycleRecord data is null");
            // 尚无周期设置时，用占位符覆盖布局里的带格式字符串，避免直接显示"%d天"之类的字面量
            currentDayView.setText(PLACEHOLDER);
            totalDayView.setText(PLACEHOLDER);
            remainingDayView.setText(PLACEHOLDER);
            tvNextMenstrualTime.setText(getString(R.string.settings_not_set));
            tvNextPhase.setText(getString(R.string.settings_not_set));
            return;
        }

        // 阶段锚点会把阶段顺序整体重排，因此“第几天/当前阶段”必须以重排后的周期起点为准，
        // 否则会出现首页显示黄体期、当日建议却显示卵泡期的情况
        Date periodStart = ReminderCycleHelper.currentPeriodStart();
        int cycleDay;
        if (periodStart != null) {
            currentCycleStart = periodStart;
            cycleDay = CycleCalculator.daysBetween(periodStart,
                    CycleCalculator.normalizeDate(new Date())) + 1;
        } else {
            cycleDay = CycleCalculator.getDayInCurrentCycle(startTime, totalCycleDay);
            currentCycleStart = CycleCalculator.getCurrentCycleStart(startTime, totalCycleDay);
        }

        Log.d(TAG, "cycleDay=" + cycleDay + ", currentCycleStart=" + CycleCalculator.formatDateShort(currentCycleStart));

        circularProgressBar.setPhaseDays(phaseArray);
        circularProgressBar.setCurrentDay(cycleDay);
        circularProgressBar.setDarkMode(isDarkModeEnabled());
        circularProgressBar.setAnimationEnabled(true);

        currentDayView.setText(String.valueOf(cycleDay));
        totalDayView.setText(String.valueOf(totalCycleDay));
        remainingDayView.setText(String.valueOf(totalCycleDay - cycleDay));

        updateNextMenstrualTime();
        updateNextPhase(cycleDay);
    }

    /**
     * 从数据库读取最近一条周期设置，并据此计算四个阶段的持续天数
     */
    private void loadMenstrualCycleRecordData() {
        MenstrualCycleRecordDao dao = new MenstrualCycleRecordDaoImpl();
        // 先把预测基准日对齐到"最近一段连续实际经期的首日"，并按最新设置重算预测记录
        dao.syncActualStartAndRegenerate(requireContext());
        menstrualCycleRecord = dao.selectLast();

        if (menstrualCycleRecord != null) {
            totalCycleDay = menstrualCycleRecord.getTotalCycleDay();
            menstrualPeriodDay = menstrualCycleRecord.getMenstrualPeriodDay();
            phaseArray = CycleCalculator.calculatePhaseDays(menstrualPeriodDay, totalCycleDay);
            startTime = menstrualCycleRecord.getStartTime();
        } else {
            Log.w(TAG, "menstrual_cycle_record table data is null");
        }
    }

    /** 下次月经日 = 下一个经期第一天（与日历阶段显示同源，已叠加阶段锚点） */
    private void updateNextMenstrualTime() {
        Date nextPeriod = ReminderCycleHelper.nextPeriodStart();
        if (nextPeriod != null) {
            tvNextMenstrualTime.setText(CycleCalculator.formatDateDisplay(nextPeriod));
            return;
        }
        if (currentCycleStart == null) return;

        Calendar nextMenstrualCal = Calendar.getInstance();
        nextMenstrualCal.setTime(currentCycleStart);
        nextMenstrualCal.add(Calendar.DAY_OF_MONTH, totalCycleDay);

        String nextPeriodText = CycleCalculator.formatDateDisplay(nextMenstrualCal.getTime());
        Log.d(TAG, "nextPeriod = " + nextPeriodText);
        tvNextMenstrualTime.setText(nextPeriodText);
    }

    /**
     * 计算并展示下一阶段的日期范围
     *
     * @param cycleDay 当前周期内的第几天
     */
    private void updateNextPhase(int cycleDay) {
        if (phaseArray == null) return;

        // 与日历阶段显示同源：按阶段锚点重排后的顺序找今天之后的下一个阶段，
        // 不再用“第几天 + 固定阶段长度”推算（阶段被调整过时会错位）
        Date[] nextPhaseDateArray = ReminderCycleHelper.nextPhaseRangeAfterToday();
        if (nextPhaseDateArray == null) {
            // 兜底：无锚点或推算失败时仍按周期设置计算
            int currentPhase = CycleCalculator.getPhaseIndex(cycleDay, phaseArray);
            nextPhaseDateArray = CycleCalculator.calculatePhaseDateRange(
                    currentPhase + 1, currentCycleStart, phaseArray, totalCycleDay);
        }

        if (nextPhaseDateArray != null) {
            String nextPhaseText = CycleCalculator.formatDateDisplay(nextPhaseDateArray[0])
                    + " - " + CycleCalculator.formatDateDisplay(nextPhaseDateArray[1]);
            Log.d(TAG, "nextPhaseText = " + nextPhaseText);
            tvNextPhase.setText(nextPhaseText);
        } else {
            Log.w(TAG, "nextPhaseDateArray is null");
        }
    }

    /** 是否处于深色模式（环形进度条需要区分配色） */
    private boolean isDarkModeEnabled() {
        return (requireContext().getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * 补全数据：从最后一条记录的下一天开始，逐天生成到今天的预测记录。
     * 每条新记录先写入本地建议，再异步用“天气 + 阶段”重新生成建议并回写。
     */
    private void initData() {
        if (startTime == null || phaseArray == null || totalCycleDay <= 0) {
            Log.e(TAG, "initData: required data not initialized");
            return;
        }

        MenstrualRecordDao dao = new MenstrualRecordDaoImpl();
        MenstrualRecord lastRecord = dao.selectLastId();

        if (lastRecord == null) {
            Log.e(TAG, "last menstrual record is null");
            return;
        }

        Date today = CycleCalculator.normalizeDate(new Date());
        Date lastDate = CycleCalculator.parseDateId(lastRecord.getId());

        if (lastDate == null) {
            Log.e(TAG, "Failed to parse last record id");
            return;
        }

        lastDate = CycleCalculator.normalizeDate(lastDate);

        if (!lastDate.before(today)) {
            Log.d(TAG, "initData: data is up to date");
            return;
        }

        Log.i(TAG, "========== 补充记录 ==========");

        Calendar nextCal = Calendar.getInstance();
        nextCal.setTime(lastDate);
        nextCal.add(Calendar.DAY_OF_MONTH, 1);
        Date nextDate = nextCal.getTime();

        int insertCount = 0;

        while (!nextDate.after(today)) {
            // 补全历史预测记录时也要带上阶段锚点，保证和首页/日历显示的阶段一致
            MenstrualCycle cycle = ReminderCycleHelper.phaseOn(nextDate);

            if (cycle != null) {
                MenstrualRecord record = new MenstrualRecord();
                String recordId = CycleCalculator.formatDateId(nextDate);
                record.setId(recordId);
                record.setMenstrualCycle(cycle);
                record.setPrediction(true);

                String localSuggestion = GenerateSuggestionHelper.generateSuggestion(requireContext(), cycle);
                record.setSuggestion(localSuggestion);

                Log.d(TAG, "补充记录: " + recordId + ", 阶段: " + cycle.name() + ", 建议: " + localSuggestion);

                long result = dao.insert(record);
                if (result == -1) {
                    Log.e(TAG, "补充记录失败: " + recordId);
                } else {
                    insertCount++;
                }
            }
            nextCal.add(Calendar.DAY_OF_MONTH, 1);
            nextDate = nextCal.getTime();
        }

        Log.i(TAG, "补充完成，共插入 " + insertCount + " 条记录");
    }

    /**
     * 刷新“今日记录”卡片：当天填写过流量或痛经才显示，否则隐藏。
     * <p>
     * 判定必须与统计图表使用同一口径（护垫/血块数量、痛经等级），
     * 不能只看 flowLevel：空表单保存会算出“0 分”，旧逻辑把它当少量，
     * 导致首页显示“今日记录”而统计图表却因无数量数据呈现空态。
     */
    private void updateTodayRecord() {
        if (cardRecordDetail == null || tvFlowLevel == null || tvPainLevel == null) return;

        String todayId = CycleCalculator.formatDateId(new Date());
        MenstrualRecordDao dao = new MenstrualRecordDaoImpl();
        MenstrualRecord record = dao.selectById(todayId);

        if (record != null && (record.hasFlowData() || record.hasPainData())) {
            cardRecordDetail.setVisibility(View.VISIBLE);

            tvFlowLevel.setText(record.getFlowLevelText(requireContext()));
            tvPainLevel.setText(record.getPainLevelText(requireContext()));
        } else {
            cardRecordDetail.setVisibility(View.GONE);
        }
    }

    /**
     * 刷新首页“今日提醒”列表：展示当天全部自定义提醒，并提供开关。
     * <p>当天没有提醒时隐藏整个卡片。
     */
    private void updateTodayReminders() {
        if (cardTodayReminders == null || layoutTodayReminders == null) return;

        Date todayDate = CycleCalculator.normalizeDate(new Date());
        ReminderDao reminderDao = new ReminderDaoImpl();
        // 与日历/当日提醒同口径：周期节点、阶段区间、重复规则展开到今天的都算今日提醒
        List<Reminder> reminders = new ArrayList<>();
        for (Reminder reminder : reminderDao.selectAll()) {
            if (reminder != null && ReminderCycleHelper.occursOn(reminder, todayDate)) {
                reminders.add(reminder);
            }
        }
        reminders.sort((r1, r2) -> {
            String t1 = r1.getAlarmTime() == null ? "" : r1.getAlarmTime();
            String t2 = r2.getAlarmTime() == null ? "" : r2.getAlarmTime();
            return t1.compareTo(t2);
        });

        layoutTodayReminders.removeAllViews();

        if (reminders.isEmpty()) {
            cardTodayReminders.setVisibility(View.GONE);
            return;
        }

        cardTodayReminders.setVisibility(View.VISIBLE);

        for (Reminder reminder : reminders) {
            View item = LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_today_reminder, layoutTodayReminders, false);

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

            // 绑定前先移除监听，避免复用时触发误回调
            switchEnabled.setOnCheckedChangeListener(null);
            switchEnabled.setChecked(reminder.isEnabled());
            switchEnabled.setOnCheckedChangeListener((buttonView, isChecked) ->
                    reminderDao.setEnabled(reminder.getId(), isChecked));

            layoutTodayReminders.addView(item);
        }
    }
}
