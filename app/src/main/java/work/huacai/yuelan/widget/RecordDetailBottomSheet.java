package work.huacai.yuelan.widget;

import android.app.Dialog;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import work.huacai.yuelan.R;
import work.huacai.yuelan.dao.impl.MenstrualRecordDaoImpl;
import work.huacai.yuelan.helper.CycleCalculator;
import work.huacai.yuelan.model.MenstrualCycle;
import work.huacai.yuelan.model.MenstrualRecord;

/**
 * 单日记录的流量 / 痛经录入弹窗。
 * <p>
 * 流量区用“加 / 减”按钮统计少量、适中、过量护垫及大/小血块数量，实时换算流量评分；
 * 痛经区用 5 个表情图标选择 0~4 级。仅月经期才显示这两块表单。
 * 保存时把数值写回 {@link MenstrualRecord} 并按 id 判断新增还是更新。
 */
public class RecordDetailBottomSheet extends BottomSheetDialogFragment {

    /** 参数：要编辑的日期（yyyyMMdd） */
    private static final String ARG_DATE_ID = "date_id";
    /** 参数：入口模式，见 {@code MODE_*} 常量 */
    private static final String ARG_MODE = "mode";

    /** 入口模式：只有“记录流量”入口，隐藏痛经表单 */
    public static final String MODE_FLOW = "flow";
    /** 入口模式：只有“记录痛经”入口，隐藏流量表单 */
    public static final String MODE_PAIN = "pain";
    /** 入口模式：两个表单都显示（日历等场景使用） */
    public static final String MODE_ALL = "all";
    /** 标题日期格式（如“2024年05月20日 周一”），只在主线程使用 */
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy年MM月dd日 E", Locale.getDefault());

    /** 流量计数的类型标识，用于复用同一套加减按钮逻辑 */
    private static final int KIND_PAD_LIGHT = 0;
    private static final int KIND_PAD_MEDIUM = 1;
    private static final int KIND_PAD_HEAVY = 2;
    private static final int KIND_CLOT_SMALL = 3;
    private static final int KIND_CLOT_LARGE = 4;

    private String dateId;
    /** 入口模式，默认两个表单都显示 */
    private String mode = MODE_ALL;
    private MenstrualRecord record;
    private MenstrualRecordDaoImpl recordDao;
    private OnRecordSavedListener onRecordSavedListener;

    private TextView tvDate;
    private TextView tvPhase;
    private View viewPhaseColor;
    private TextView tvFlowScore;

    private LinearLayout layoutFlowRecord;
    private LinearLayout layoutPainRecord;

    private TextView tvPadLightMinus;
    private TextView tvPadLightCount;
    private TextView tvPadLightPlus;
    private TextView tvPadMediumMinus;
    private TextView tvPadMediumCount;
    private TextView tvPadMediumPlus;
    private TextView tvPadHeavyMinus;
    private TextView tvPadHeavyCount;
    private TextView tvPadHeavyPlus;
    private TextView tvClotSmallMinus;
    private TextView tvClotSmallCount;
    private TextView tvClotSmallPlus;
    private TextView tvClotLargeMinus;
    private TextView tvClotLargeCount;
    private TextView tvClotLargePlus;

    private ImageView ivPain0;
    private ImageView ivPain1;
    private ImageView ivPain2;
    private ImageView ivPain3;
    private ImageView ivPain4;
    private TextView tvPainLevel;

    private EditText etNotes;
    /** 备注区标题，按入口模式切换为“备注 / 流量备注 / 痛经备注” */
    private TextView tvNotesTitle;

    private TextView tvSave;
    private TextView tvCancel;
    /** 删除记录入口，仅当天存在实际记录时显示 */
    private TextView tvDelete;
    /** 非经期提示区（提示文案 + “标记为经期”按钮） */
    private LinearLayout layoutNotMenstrual;
    /** 非经期时显示的提示文案 */
    private TextView tvNotMenstrualHint;
    /** 非经期时当场把本日标记为经期的按钮 */
    private TextView btnMarkMenstrual;

    /** 当前表单中的计数（尚未保存到数据库） */
    private int padLightCount = 0;
    private int padMediumCount = 0;
    private int padHeavyCount = 0;
    private int clotSmallCount = 0;
    private int clotLargeCount = 0;
    /** 当前选中的痛经等级 0~4 */
    private int painLevel = 0;

    /**
     * 按日期创建弹窗。
     *
     * @param dateId 日期 ID（yyyyMMdd）
     * @return 已带参数的弹窗实例
     */
    public static RecordDetailBottomSheet newInstance(String dateId) {
        return newInstance(dateId, MODE_ALL);
    }

    /**
     * 按日期与入口模式创建弹窗。
     *
     * @param dateId 日期 ID（yyyyMMdd）
     * @param mode   入口模式，见 {@code MODE_*}
     * @return 已带参数的弹窗实例
     */
    public static RecordDetailBottomSheet newInstance(String dateId, String mode) {
        RecordDetailBottomSheet fragment = new RecordDetailBottomSheet();
        Bundle args = new Bundle();
        args.putString(ARG_DATE_ID, dateId);
        args.putString(ARG_MODE, mode);
        fragment.setArguments(args);
        return fragment;
    }

    /**
     * 按已有记录创建弹窗（内部只取记录 id，具体数据仍从数据库读取）。
     *
     * @param record 记录对象
     * @return 已带参数的弹窗实例
     */
    public static RecordDetailBottomSheet newInstance(MenstrualRecord record) {
        return newInstance(record, MODE_ALL);
    }

    /**
     * 按已有记录与入口模式创建弹窗。
     *
     * @param record 记录对象
     * @param mode   入口模式，见 {@code MODE_*}
     * @return 已带参数的弹窗实例
     */
    public static RecordDetailBottomSheet newInstance(MenstrualRecord record, String mode) {
        return newInstance(record.getId(), mode);
    }

    /**
     * 设置保存成功后的回调。
     *
     * @param listener 回调，可为 null
     */
    public void setOnRecordSavedListener(OnRecordSavedListener listener) {
        this.onRecordSavedListener = listener;
    }

    /**
     * 记录变更回调（保存或删除成功后触发）。
     */
    public interface OnRecordSavedListener {
        /**
         * 记录已成功写入或删除，调用方可在此重算预测、刷新列表并重排提醒
         */
        void onRecordSaved();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            dateId = getArguments().getString(ARG_DATE_ID);
            String argMode = getArguments().getString(ARG_MODE);
            if (argMode != null) {
                mode = argMode;
            }
        }
        recordDao = new MenstrualRecordDaoImpl();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_record_detail, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        initViews(view);
        loadData();
        setupListeners();
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog != null) {
            applyWindowStyle(dialog);
        }
    }

    /**
     * 统一设置弹窗的窗口样式：导航栏配色 + 底部的系统栏内边距。
     * <p>
     * 开启 edge-to-edge（{@code setDecorFitsSystemWindows(false)}）后，内容会绘制到系统栏区域，
     * 必须手动给 design_bottom_sheet 补底部 padding；这里取“系统栏 / 软键盘”两者的较大值，
     * 保证键盘弹出时输入框不会被遮挡。
     *
     * @param dialog 当前弹窗
     */
    private void applyWindowStyle(Dialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) return;

        // 导航栏颜色与弹窗背景保持一致，图标亮度跟随深浅色
        window.setNavigationBarColor(ContextCompat.getColor(requireContext(), R.color.card_surface));
        WindowCompat.getInsetsController(window, window.getDecorView())
                .setAppearanceLightNavigationBars(!isNightMode());

        // 手势导航模式下导航栏强制透明，让弹窗背景延伸到屏幕底部
        View bottomSheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (bottomSheet != null) {
            ViewCompat.setOnApplyWindowInsetsListener(bottomSheet, (v, insets) -> {
                int systemBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
                int imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
                v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                        Math.max(systemBottom, imeBottom));
                return WindowInsetsCompat.CONSUMED;
            });
        }
        WindowCompat.setDecorFitsSystemWindows(window, false);
    }

    /**
     * 当前是否处于深色模式，用于决定导航栏图标的明暗。
     */
    private boolean isNightMode() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * 绑定所有控件引用。
     */
    private void initViews(View view) {
        tvDate = view.findViewById(R.id.tv_date);
        tvPhase = view.findViewById(R.id.tv_phase);
        viewPhaseColor = view.findViewById(R.id.view_phase_color);
        tvFlowScore = view.findViewById(R.id.tv_flow_score);

        layoutFlowRecord = view.findViewById(R.id.layout_flow_record);
        layoutPainRecord = view.findViewById(R.id.layout_pain_record);

        tvPadLightMinus = view.findViewById(R.id.tv_pad_light_minus);
        tvPadLightCount = view.findViewById(R.id.tv_pad_light_count);
        tvPadLightPlus = view.findViewById(R.id.tv_pad_light_plus);
        tvPadMediumMinus = view.findViewById(R.id.tv_pad_medium_minus);
        tvPadMediumCount = view.findViewById(R.id.tv_pad_medium_count_label);
        tvPadMediumPlus = view.findViewById(R.id.tv_pad_medium_plus);
        tvPadHeavyMinus = view.findViewById(R.id.tv_pad_heavy_minus);
        tvPadHeavyCount = view.findViewById(R.id.tv_pad_heavy_count);
        tvPadHeavyPlus = view.findViewById(R.id.tv_pad_heavy_plus);

        tvClotSmallMinus = view.findViewById(R.id.tv_clot_small_minus);
        tvClotSmallCount = view.findViewById(R.id.tv_clot_small_count);
        tvClotSmallPlus = view.findViewById(R.id.tv_clot_small_plus);
        tvClotLargeMinus = view.findViewById(R.id.tv_clot_large_minus);
        tvClotLargeCount = view.findViewById(R.id.tv_clot_large_count);
        tvClotLargePlus = view.findViewById(R.id.tv_clot_large_plus);

        ivPain0 = view.findViewById(R.id.iv_pain_0);
        ivPain1 = view.findViewById(R.id.iv_pain_1);
        ivPain2 = view.findViewById(R.id.iv_pain_2);
        ivPain3 = view.findViewById(R.id.iv_pain_3);
        ivPain4 = view.findViewById(R.id.iv_pain_4);
        tvPainLevel = view.findViewById(R.id.tv_pain_level);

        etNotes = view.findViewById(R.id.et_notes);
        tvNotesTitle = view.findViewById(R.id.tv_notes_title);

        tvSave = view.findViewById(R.id.tv_save);
        tvCancel = view.findViewById(R.id.tv_cancel);
        tvDelete = view.findViewById(R.id.tv_delete);
        layoutNotMenstrual = view.findViewById(R.id.layout_not_menstrual);
        tvNotMenstrualHint = view.findViewById(R.id.tv_not_menstrual_hint);
        btnMarkMenstrual = view.findViewById(R.id.btn_mark_menstrual);
    }

    /**
     * 读取当天记录并回填表单。
     * <p>若当天还没有记录，则新建一条只带 id 的空记录，保存时走 insert。
     */
    private void loadData() {
        if (dateId == null) return;

        record = recordDao.selectById(dateId);
        // 只有“用户实际记录”才允许删除；预测占位记录删除后会被重新生成，没有意义
        boolean hasActualRecord = record != null && !record.isPrediction();
        if (tvDelete != null) {
            tvDelete.setVisibility(hasActualRecord ? View.VISIBLE : View.GONE);
        }
        if (record == null) {
            record = new MenstrualRecord();
            record.setId(dateId);
            // 用户手动录入的记录不是预测数据
            record.setIsPrediction(0);
        }

        Date date = CycleCalculator.parseDateId(dateId);
        if (date != null) {
            tvDate.setText(DATE_FORMAT.format(date));
        }

        if (record.getMenstrualCycle() != null) {
            tvPhase.setText(record.getMenstrualCycle().getDisplayName(requireContext()));
            setPhaseColor(record.getMenstrualCycle());
        }

        // 流量 / 痛经表单只在月经期展示；同时按入口模式收起另一个表单
        updateFormVisibility();

        padLightCount = record.getPadLightCount();
        padMediumCount = record.getPadMediumCount();
        padHeavyCount = record.getPadHeavyCount();
        clotSmallCount = record.getClotSmallCount();
        clotLargeCount = record.getClotLargeCount();
        painLevel = record.getPainLevel();

        updateCountDisplays();
        updateFlowScore();
        updatePainDisplay();

        // 备注按入口模式读写独立字段，避免流量备注与痛经备注互相覆盖
        if (MODE_FLOW.equals(mode)) {
            tvNotesTitle.setText(R.string.record_detail_notes_flow);
            if (record.getFlowNote() != null) {
                etNotes.setText(record.getFlowNote());
            }
        } else if (MODE_PAIN.equals(mode)) {
            tvNotesTitle.setText(R.string.record_detail_notes_pain);
            if (record.getPainNote() != null) {
                etNotes.setText(record.getPainNote());
            }
        } else {
            if (record.getNotes() != null) {
                etNotes.setText(record.getNotes());
            }
        }
    }

    /**
     * 按当前阶段与入口模式刷新表单显隐。
     * <p>流量 / 痛经表单只在月经期展示；非经期显示提示区。
     * “标记今天为经期”按钮只在编辑今天时出现——历史日期的阶段应通过补签流程修改，
     * 在录入弹窗里直接改阶段会让“今天”这个词名不副实。
     */
    private void updateFormVisibility() {
        boolean isMenstrual = record.getMenstrualCycle() == MenstrualCycle.MENSTRUAL_PERIOD;
        boolean showFlow = isMenstrual && !MODE_PAIN.equals(mode);
        boolean showPain = isMenstrual && !MODE_FLOW.equals(mode);
        // 只有今天才提供“标记为经期”的快捷入口
        boolean isToday = dateId != null && dateId.equals(CycleCalculator.formatDateId(new Date()));

        layoutFlowRecord.setVisibility(showFlow ? View.VISIBLE : View.GONE);
        layoutPainRecord.setVisibility(showPain ? View.VISIBLE : View.GONE);
        tvFlowScore.setVisibility(showFlow ? View.VISIBLE : View.GONE);
        layoutNotMenstrual.setVisibility(isMenstrual ? View.GONE : View.VISIBLE);
        btnMarkMenstrual.setVisibility(isToday ? View.VISIBLE : View.GONE);
    }

    /**
     * 非经期时当场把本日标记为经期：只改内存中的阶段并展开表单，
     * 点击“保存”后才真正落库（由保存逻辑统一 insert/update）。
     */
    private void markAsMenstrual() {
        record.setMenstrualCycle(MenstrualCycle.MENSTRUAL_PERIOD);
        tvPhase.setText(MenstrualCycle.MENSTRUAL_PERIOD.getDisplayName(requireContext()));
        setPhaseColor(MenstrualCycle.MENSTRUAL_PERIOD);
        updateFormVisibility();
    }

    /**
     * 设置阶段圆点颜色（与列表、日历保持同一套色值）。
     * <p>颜色硬编码，切换深色模式时保持不变。
     */
    private void setPhaseColor(MenstrualCycle cycle) {
        int color;
        switch (cycle) {
            case MENSTRUAL_PERIOD:
                color = 0xFFFF6B93;
                break;
            case FOLLICULAR_PHASE:
                color = 0xFF4CAF50;
                break;
            case OVULATION_PERIOD:
                color = 0xFFFF9800;
                break;
            case LUTE_PHASE:
                color = 0xFF2196F3;
                break;
            default:
                color = 0xFF999999;
        }
        viewPhaseColor.setBackgroundColor(color);
    }

    /**
     * 注册所有交互事件：流量加减、痛经图标、保存与取消。
     */
    private void setupListeners() {
        bindCounter(tvPadLightMinus, tvPadLightPlus, KIND_PAD_LIGHT);
        bindCounter(tvPadMediumMinus, tvPadMediumPlus, KIND_PAD_MEDIUM);
        bindCounter(tvPadHeavyMinus, tvPadHeavyPlus, KIND_PAD_HEAVY);
        bindCounter(tvClotSmallMinus, tvClotSmallPlus, KIND_CLOT_SMALL);
        bindCounter(tvClotLargeMinus, tvClotLargePlus, KIND_CLOT_LARGE);

        View.OnClickListener painClickListener = v -> {
            int id = v.getId();
            if (id == R.id.iv_pain_0) painLevel = 0;
            else if (id == R.id.iv_pain_1) painLevel = 1;
            else if (id == R.id.iv_pain_2) painLevel = 2;
            else if (id == R.id.iv_pain_3) painLevel = 3;
            else if (id == R.id.iv_pain_4) painLevel = 4;
            updatePainDisplay();
        };

        ivPain0.setOnClickListener(painClickListener);
        ivPain1.setOnClickListener(painClickListener);
        ivPain2.setOnClickListener(painClickListener);
        ivPain3.setOnClickListener(painClickListener);
        ivPain4.setOnClickListener(painClickListener);

        tvSave.setOnClickListener(v -> saveRecord());
        tvCancel.setOnClickListener(v -> dismiss());
        tvDelete.setOnClickListener(v -> showDeleteConfirmDialog());
        btnMarkMenstrual.setOnClickListener(v -> markAsMenstrual());
    }

    /**
     * 删除前二次确认：误删会移动预测基准日，必须让用户明确确认。
     */
    private void showDeleteConfirmDialog() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.record_detail_delete_confirm_title)
                .setMessage(R.string.record_detail_delete_confirm_message)
                .setNegativeButton(R.string.record_detail_cancel, null)
                .setPositiveButton(R.string.record_detail_delete_confirm,
                        (dialog, which) -> deleteRecord())
                .show();
    }

    /**
     * 删除当天的实际记录，并通知调用方刷新预测记录与经期提醒。
     * <p>
     * 删除成功后复用保存回调：日历/首页收到通知后会重算预测基准日、刷新列表并重排提醒。
     */
    private void deleteRecord() {
        int deleted = recordDao.deleteById(dateId);
        if (deleted > 0) {
            if (onRecordSavedListener != null) {
                onRecordSavedListener.onRecordSaved();
            }
            dismiss();
        } else {
            Toast.makeText(requireContext(), R.string.record_detail_delete_failed,
                    Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 给一组“减 / 加”按钮绑定计数逻辑。
     *
     * @param minus 减号按钮
     * @param plus  加号按钮
     * @param kind  计数类型，见 {@code KIND_*} 常量
     */
    private void bindCounter(TextView minus, TextView plus, int kind) {
        minus.setOnClickListener(v -> changeFlowCount(kind, -1));
        plus.setOnClickListener(v -> changeFlowCount(kind, 1));
    }

    /**
     * 修改指定类型的计数并刷新界面。
     *
     * @param kind  计数类型
     * @param delta +1 或 -1
     */
    private void changeFlowCount(int kind, int delta) {
        // 计数不允许为负：减到 0 后继续点击保持 0（与逐个按钮的原有判断等价）
        switch (kind) {
            case KIND_PAD_LIGHT:
                padLightCount = Math.max(0, padLightCount + delta);
                break;
            case KIND_PAD_MEDIUM:
                padMediumCount = Math.max(0, padMediumCount + delta);
                break;
            case KIND_PAD_HEAVY:
                padHeavyCount = Math.max(0, padHeavyCount + delta);
                break;
            case KIND_CLOT_SMALL:
                clotSmallCount = Math.max(0, clotSmallCount + delta);
                break;
            case KIND_CLOT_LARGE:
                clotLargeCount = Math.max(0, clotLargeCount + delta);
                break;
            default:
                return;
        }
        refreshFlowViews();
    }

    /**
     * 刷新计数文本与流量评分。
     */
    private void refreshFlowViews() {
        updateCountDisplays();
        updateFlowScore();
    }

    /**
     * 把当前计数写入对应的文本控件。
     */
    private void updateCountDisplays() {
        tvPadLightCount.setText(String.valueOf(padLightCount));
        tvPadMediumCount.setText(String.valueOf(padMediumCount));
        tvPadHeavyCount.setText(String.valueOf(padHeavyCount));
        tvClotSmallCount.setText(String.valueOf(clotSmallCount));
        tvClotLargeCount.setText(String.valueOf(clotLargeCount));
    }

    /**
     * 实时计算并展示流量评分与等级。
     * <p>评分权重与等级阈值与 {@link MenstrualRecord#calculateFlowScore()} 保持一致。
     */
    private void updateFlowScore() {
        int score = padLightCount * 1 + padMediumCount * 5 + padHeavyCount * 20
                + clotSmallCount * 1 + clotLargeCount * 5;
        tvFlowScore.setText(getString(R.string.record_detail_flow_score_label, score));

        String level;
        if (score <= 0) {
            // 未填写任何护垫/血块数量：显示“未记录”，与保存后的等级和统计数据保持一致
            level = getString(R.string.record_detail_flow_no_data);
        } else if (score < 25) {
            level = getString(R.string.statistics_flow_light);
        } else if (score <= 100) {
            level = getString(R.string.statistics_flow_medium);
        } else {
            level = getString(R.string.statistics_flow_heavy);
        }
        tvFlowScore.append(" (" + level + ")");
    }

    /**
     * 按痛经等级点亮前 N 个表情图标，并更新等级文案。
     * <p>等级 0 仍点亮第 1 个图标，用于表示“无痛经”档位。
     */
    private void updatePainDisplay() {
        ImageView[] painIcons = {ivPain0, ivPain1, ivPain2, ivPain3, ivPain4};
        // 选中状态为“该图标下标 <= 当前等级”，等级越高点亮越多
        for (int i = 0; i < painIcons.length; i++) {
            painIcons[i].setSelected(i <= painLevel);
        }

        String[] painTexts = {
                getString(R.string.record_detail_pain_0),
                getString(R.string.record_detail_pain_1),
                getString(R.string.record_detail_pain_2),
                getString(R.string.record_detail_pain_3),
                getString(R.string.record_detail_pain_4)
        };
        tvPainLevel.setText(painTexts[painLevel]);
    }

    /**
     * 把表单数据写回记录并落库：不存在则 insert，存在则 update。
     * <p>仅当数据库操作成功（返回值 &gt; 0）时触发回调和关闭弹窗。
     */
    private void saveRecord() {
        if (record == null) return;

        record.setPadLightCount(padLightCount);
        record.setPadMediumCount(padMediumCount);
        record.setPadHeavyCount(padHeavyCount);
        record.setClotSmallCount(clotSmallCount);
        record.setClotLargeCount(clotLargeCount);
        record.setPainLevel(painLevel);
        record.setPadCount(padLightCount + padMediumCount + padHeavyCount);
        record.calculateFlowScore();
        // 备注按入口模式写入独立字段：流量 / 痛经各自保存，互不覆盖
        String noteText = etNotes.getText().toString().trim();
        if (MODE_FLOW.equals(mode)) {
            record.setFlowNote(noteText);
        } else if (MODE_PAIN.equals(mode)) {
            record.setPainNote(noteText);
        } else {
            record.setNotes(noteText);
        }
        // 用户在本弹窗保存即视为实际记录：把预测占位记录转为实际记录，
        // 否则统计、基准日与图表仍会把这条数据当成预测而过滤掉
        record.setIsPrediction(0);

        int result;
        MenstrualRecord existingRecord = recordDao.selectById(record.getId());
        if (existingRecord == null) {
            result = (int) recordDao.insert(record);
        } else {
            result = recordDao.updateRecord(record);
        }

        if (result > 0) {
            if (onRecordSavedListener != null) {
                onRecordSavedListener.onRecordSaved();
            }
            dismiss();
        }
    }
}
