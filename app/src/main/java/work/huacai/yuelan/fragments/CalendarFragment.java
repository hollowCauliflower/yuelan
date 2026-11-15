package work.huacai.yuelan.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import work.huacai.yuelan.R;
import work.huacai.yuelan.dao.MenstrualRecordDao;
import work.huacai.yuelan.dao.ReminderDao;
import work.huacai.yuelan.dao.impl.MenstrualCycleRecordDaoImpl;
import work.huacai.yuelan.dao.impl.MenstrualRecordDaoImpl;
import work.huacai.yuelan.dao.impl.ReminderDaoImpl;
import work.huacai.yuelan.helper.CycleCalculator;
import work.huacai.yuelan.helper.PeriodReminderScheduler;
import work.huacai.yuelan.helper.PhaseContinuityHelper;
import work.huacai.yuelan.helper.ReminderAlarmScheduler;
import work.huacai.yuelan.helper.ReminderCycleHelper;
import work.huacai.yuelan.helper.ReminderRepeatHelper;
import work.huacai.yuelan.model.MenstrualCycle;
import work.huacai.yuelan.model.MenstrualCycleRecord;
import work.huacai.yuelan.model.MenstrualRecord;
import work.huacai.yuelan.model.Reminder;
import work.huacai.yuelan.widget.AppDialogFactory;
import work.huacai.yuelan.widget.CalendarView;
import work.huacai.yuelan.widget.DailySuggestionBottomSheet;
import work.huacai.yuelan.widget.MakeupRecordDialog;
import work.huacai.yuelan.widget.PhaseRangeEditor;
import work.huacai.yuelan.widget.RecordDetailBottomSheet;

/**
 * 日历页 Fragment。
 * <p>
 * 页面职责：
 * 1. 顶部展示下次排卵、上一周期、当前阶段、本周期已记录天数四项统计
 *    （与首页的当前第几天/周期总长/剩余天数/下次经期、统计页的平均周期错开，避免信息重复）；
 * 2. 月历按阶段着色显示经期/卵泡期/排卵期/黄体期，并只展示用户设置的提醒（自定义提醒与月经提醒）；
 * 3. 点击某一天：已有记录则打开建议弹窗；没有记录且是过去日期则弹出"补录阶段"选择框。
 * <p>
 * 周期参数（起始日/周期长度/经期长度）的调整统一在"设置"页的"当前周期设置"卡片中完成，
 * 日历页不再提供调整入口，避免两处修改造成混乱。
 */
public class CalendarFragment extends Fragment {

    /** 提醒事项向前展开的天数（约一年，避免翻到较早月份时提醒消失） */
    private static final int EVENT_RANGE_DAYS_BACK = 400;
    /** 提醒事项向后展开的天数（约一年，避免翻到未来较远月份时提醒消失） */
    private static final int EVENT_RANGE_DAYS_FORWARD = 400;

    private CalendarView calendarView;
    private TextView tvNextOvulation;
    private TextView tvPreviousCycle;
    private TextView tvCurrentPhase;
    private TextView tvRecordedDays;

    private MenstrualCycleRecordDaoImpl cycleRecordDao;
    private MenstrualRecordDao recordDao;
    private ReminderDao reminderDao;
    private MenstrualCycleRecord currentCycleRecord;

    public CalendarFragment() {
    }

    public static CalendarFragment newInstance() {
        return new CalendarFragment();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_calendar, container, false);
    }

    /**
     * 视图创建完成：初始化 DAO、绑定控件并加载一次数据
     */
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        cycleRecordDao = new MenstrualCycleRecordDaoImpl();
        recordDao = new MenstrualRecordDaoImpl();
        reminderDao = new ReminderDaoImpl();

        initViews(view);
        loadData();
    }

    // 日历页自己的偏好文件：记录一次性引导是否已展示
    private static final String PREFS_CALENDAR = "calendar_prefs";
    private static final String KEY_LEGEND_GUIDE_SHOWN = "legend_guide_shown";

    /**
     * 首次进入日历页时弹出一次图例说明（四阶段颜色 + 实心=已记录/浅色=预测）。
     * <p>用 SharedPreferences 标记只弹一次；常驻图例只保留四阶段色块，
     * 避免"已记录/预测"只用月经期粉演示造成歧义。
     */
    private void showLegendGuideIfFirstTime(@NonNull View view) {
        android.content.SharedPreferences prefs = requireContext()
                .getSharedPreferences(PREFS_CALENDAR, android.content.Context.MODE_PRIVATE);
        if (prefs.getBoolean(KEY_LEGEND_GUIDE_SHOWN, false)) {
            return;
        }
        prefs.edit().putBoolean(KEY_LEGEND_GUIDE_SHOWN, true).apply();
        // 等首帧渲染完再弹，避免与每日建议弹窗等启动弹窗叠在一起
        view.post(() -> {
            if (!isAdded()) return;
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.calendar_legend_guide_title)
                    .setView(R.layout.dialog_calendar_legend)
                    .setPositiveButton(R.string.dialog_i_know, null)
                    .show();
        });
    }

    /**
     * 每次回到日历页重新加载数据，保证补录/调整周期后展示同步更新
     */
    @Override
    public void onResume() {
        super.onResume();
        loadData();
        // ViewPager2 下只有当前页才是 RESUMED，因此只有用户真正切到日历页才会弹引导
        showLegendGuideIfFirstTime(requireView());
    }

    /**
     * 绑定控件，并注册日历日期选中回调
     */
    private void initViews(View view) {
        calendarView = view.findViewById(R.id.calendar_view);
        tvNextOvulation = view.findViewById(R.id.tv_next_ovulation);
        tvPreviousCycle = view.findViewById(R.id.tv_previous_cycle);
        tvCurrentPhase = view.findViewById(R.id.tv_current_phase);
        tvRecordedDays = view.findViewById(R.id.tv_recorded_days);

        calendarView.setOnCalendarActionListener(new CalendarView.OnCalendarActionListener() {
            /** 选中日期：打开当天建议或补录弹窗 */
            @Override
            public void onDateSelected(Calendar date) {
                // 延后一帧再打开弹窗：点击回调发生在 onTouchEvent 的抬手阶段，
                // 若此时同步弹出 BottomSheet，会插入新的窗口层级并打断本次绘制的收尾，
                // 造成按压态与选中态之间的偶发闪烁。post 到下一帧可保证按压态先复位再弹窗。
                calendarView.post(() -> {
                    if (!isAdded()) return;
                    showDailySuggestion(date);
                });
            }

            /** 长按日期：直接打开流量 / 痛经录入弹窗，方便补登任意历史日期 */
            @Override
            public void onDateLongClick(Calendar date) {
                openRecordEditor(CycleCalculator.formatDateId(date.getTime()));
            }

            @Override
            public void onMonthChanged(Calendar month) {
            }

            @Override
            public void onEventClicked(String date, CalendarView.EventData event) {
                // 长按到带有提醒事件的日期时，同样打开记录弹窗（日期格式为 yyyy-MM-dd）
                openRecordEditor(date.replace("-", ""));
            }

            @Override
            public void onPeriodToggle(boolean showPeriodColors) {
            }

            @Override
            public void onEventDisplayModeChanged(boolean showEventDots) {
            }
        });
    }

    /**
     * 加载周期记录并刷新四项统计文本、日历配色和每日建议摘要。
     * 没有周期设置时各项显示 "--"。
     */
    private void loadData() {
        currentCycleRecord = cycleRecordDao.selectLast();

        if (currentCycleRecord == null) {
            tvNextOvulation.setText("--");
            tvPreviousCycle.setText("--");
            tvCurrentPhase.setText("--");
            tvRecordedDays.setText("--");
            // 没设置周期时仍要把提醒挂到日历上
            loadCalendarEvents();
            return;
        }

        int totalCycleDay = currentCycleRecord.getTotalCycleDay();

        int previousCycle = calculatePreviousCycle();
        tvPreviousCycle.setText(previousCycle > 0 ? String.valueOf(previousCycle) : "--");

        String nextOvulation = calculateNextOvulation();
        tvNextOvulation.setText(nextOvulation != null ? nextOvulation : "--");

        // 当前阶段：与日历格子、当日建议同源（叠加阶段锚点与实际记录），
        // 不能再用“第几天 + 固定阶段长度”推算，否则阶段被调整后两处会不一致
        MenstrualCycle phase = ReminderCycleHelper.phaseOn(new Date());
        if (phase != null) {
            tvCurrentPhase.setText(phase.getDisplayName(requireContext()));
            tvCurrentPhase.setTextColor(ContextCompat.getColor(requireContext(),
                    getPhaseColorRes(phase)));
        } else {
            tvCurrentPhase.setText("--");
            tvCurrentPhase.setTextColor(ContextCompat.getColor(requireContext(),
                    R.color.text_title));
        }

        tvRecordedDays.setText(String.valueOf(countRecordedDaysInCurrentCycle(totalCycleDay)));

        setupCalendarPeriodColors();
        loadCalendarEvents();
    }

    /**
     * 计算下次排卵期并格式化为"开始-结束"区间文本。
     * <p>
     * 按当前阶段模型（含阶段锚点与实际记录）找今天之后最近的一段排卵期，
     * 保证与日历格子的阶段配色、首页“下个阶段”一致。
     * 与首页"下次经期"、统计页"平均周期"均不重复，作为日历页特有的补充信息。
     *
     * @return 形如 11月19日-11月21日 的区间文本；无法推算时返回 null
     */
    @Nullable
    private String calculateNextOvulation() {
        // 与日历阶段配色同源：按阶段锚点重排后的顺序找下一次排卵期区间
        Date[] range = ReminderCycleHelper.nextPhaseRange(
                MenstrualCycle.OVULATION_PERIOD, new Date());
        if (range == null) {
            return null;
        }
        return CycleCalculator.formatDateCnShort(range[0])
                + "-" + CycleCalculator.formatDateCnShort(range[1]);
    }

    /**
     * 本周期已记录天数：当前周期起点（含）至今天（含）之间的实际记录条数。
     */
    private int countRecordedDaysInCurrentCycle(int totalCycleDay) {
        Date cycleStart = ReminderCycleHelper.currentPeriodStart();
        if (cycleStart == null) {
            cycleStart = CycleCalculator.getCurrentCycleStart(
                    currentCycleRecord.getStartTime(), totalCycleDay);
        }
        if (cycleStart == null) {
            return 0;
        }
        Date today = CycleCalculator.normalizeDate(new Date());
        List<MenstrualRecord> actual = recordDao.selectActual();
        int count = 0;
        if (actual != null) {
            for (MenstrualRecord record : actual) {
                Date date = CycleCalculator.parseDateId(record.getId());
                if (date != null && !date.before(cycleStart) && !date.after(today)) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * 阶段标签配色，与日历、建议弹窗保持一致的四阶段色系。
     */
    private int getPhaseColorRes(MenstrualCycle cycle) {
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
     * 计算上一周期天数：取最近两次“经期第一天”的间隔天数。
     * <p>
     * 只统计用户实际记录，避免自动生成的预测占位记录把周期长度算错；
     * 记录不足两次经期起点时返回 0（界面显示 "--"）。
     * <p>
     * {@code selectActual()} 按日期倒序返回，这里从后往前遍历，得到“按时间先后”的顺序。
     * 每次遇到与上一条经期记录间隔达到 10 天的日期，就视为新一段经期的<b>首日</b>
     * （漏记经期中间几天时，间隔仍小于 10 天，不会被误判成新一周期）。
     * 不能反向（从新到旧）收集起点：那样记下的会是每段经期的最后一天，
     * 上一周期会被少算好几天（甚至跨段算成几十天）。
     */
    private int calculatePreviousCycle() {
        List<MenstrualRecord> records = recordDao.selectActual();
        if (records == null || records.isEmpty()) {
            return 0;
        }

        List<Date> periodStarts = new ArrayList<>();
        Date previousMenstrualDate = null;
        for (int i = records.size() - 1; i >= 0; i--) {
            MenstrualRecord record = records.get(i);
            if (record.getMenstrualCycle() != MenstrualCycle.MENSTRUAL_PERIOD) {
                continue;
            }
            Date date = CycleCalculator.parseDateId(record.getId());
            if (date == null) {
                continue;
            }

            if (previousMenstrualDate == null
                    || CycleCalculator.daysBetween(previousMenstrualDate, date) >= 10) {
                periodStarts.add(date);
            }
            previousMenstrualDate = date;
        }

        if (periodStarts.size() < 2) {
            return 0;
        }

        // 最近两次经期首日相隔的自然日数即上一周期长度（用 daysBetween 避免夏令时误差）
        int size = periodStarts.size();
        return CycleCalculator.daysBetween(periodStarts.get(size - 2), periodStarts.get(size - 1));
    }

    /**
     * 把四个阶段的持续天数与配色下发给日历控件。
     * <p>
     * 配色策略：实际记录用实色、预测经期用极淡色、预测非经期不着色，
     * 避免整月铺满色块对用户造成误导。
     */
    private void setupCalendarPeriodColors() {
        // 预测色：四个阶段都用半透明色（约 25% 不透明度），明显比实色的实际记录更淡，
        // 让用户能看出周期阶段分布，同时一眼区分预测与实际
        int[] phaseColors = new int[]{
                0x40FF6B93,   // 预测经期：半透明粉色
                0x404CAF50,   // 预测卵泡期：半透明绿色
                0x40FF9800,   // 预测排卵期：半透明橙色
                0x402196F3    // 预测黄体期：半透明蓝色
        };

        // 实际记录色：四个阶段均使用实色，与半透明的预测日形成强烈对比
        int[] phaseActualColors = new int[]{
                0xFFFF6B93,   // 月经期：实粉
                0xFF4CAF50,   // 卵泡期：实绿
                0xFFFF9800,   // 排卵期：实橙
                0xFF2196F3    // 黄体期：实蓝
        };

        // 周期分段：每轮实际周期一行，历史分段按当时参数渲染，不随最新设置变化
        List<MenstrualCycleRecord> segmentRecords = cycleRecordDao.selectAll();
        List<CalendarView.PeriodSegment> segments = new ArrayList<>();
        for (MenstrualCycleRecord seg : segmentRecords) {
            if (seg.getStartTime() == null) {
                continue;
            }
            int[] phaseDays = CycleCalculator.calculatePhaseDays(
                    seg.getMenstrualPeriodDay(), seg.getTotalCycleDay());
            Calendar start = Calendar.getInstance();
            start.setTime(seg.getStartTime());
            segments.add(new CalendarView.PeriodSegment(start, phaseDays, seg.getTotalCycleDay()));
        }
        calendarView.setPeriodSegments(segments, phaseColors, phaseActualColors);
        calendarView.setShowPeriodColors(true);

        // 收集所有实际记录（is_prediction=1）的日期→阶段下标，
        // 日历据此用实色绘制实际记录日，与预测日明确区分
        Map<String, Integer> actualPhaseMap = new HashMap<>();
        List<MenstrualRecord> actualRecords = recordDao.selectActual();
        if (actualRecords != null) {
            for (MenstrualRecord record : actualRecords) {
                if (record.getMenstrualCycle() != null) {
                    actualPhaseMap.put(toCalendarKey(record.getId()),
                            record.getMenstrualCycle().getIndex());
                }
            }
        }
        calendarView.setActualRecordPhases(actualPhaseMap);
    }

    /**
     * 把自定义提醒与月经提醒挂到日历对应日期上。
     * <p>只展示用户真正设置过的提醒：自定义提醒（含周期节点/重复规则）用粉色点，
     * 月经提醒开启时在下次预计经期首日挂一条提醒。系统生成的每日建议不再计入日历事项。
     * 有实际记录的日期另由日历控件在首位画一个白点，两类指示合并成一排展示。
     * <p>注意：{@link CalendarView} 内部以 yyyy-MM-dd 作为日期 key，
     * 而记录与提醒的日期是 yyyyMMdd，需要先转换格式，否则事件点不会显示。
     */
    private void loadCalendarEvents() {
        Map<String, List<CalendarView.EventData>> eventMap = new HashMap<>();

        // 提醒展开范围：前后各一年，避免翻到较远的月份时提醒圆点突然消失
        Calendar rangeStart = Calendar.getInstance();
        rangeStart.add(Calendar.DAY_OF_MONTH, -EVENT_RANGE_DAYS_BACK);
        Calendar rangeEnd = Calendar.getInstance();
        rangeEnd.add(Calendar.DAY_OF_MONTH, EVENT_RANGE_DAYS_FORWARD);

        // 只有启用状态的提醒才显示在日历上
        for (Reminder reminder : reminderDao.selectAll()) {
            if (!reminder.isEnabled()) continue;

            if (reminder.isSpanMode()) {
                // 阶段区间提醒：阶段内每一天都挂一个粉色点
                for (Date spanDate : ReminderCycleHelper.spanDatesBetween(reminder,
                        rangeStart.getTime(), rangeEnd.getTime())) {
                    CalendarView.EventData event = new CalendarView.EventData();
                    event.setTitle(reminder.getTitle());
                    event.description = reminder.getMessage() != null ? reminder.getMessage() : "";
                    event.tag = "reminder";
                    event.setDate(toCalendarKey(CycleCalculator.formatDateId(spanDate)));
                    addEvent(eventMap, event.date, event);
                }
                continue;
            }

            if (reminder.isCycleMode()) {
                // 周期节点提醒：节点当天挂粉色点
                for (Date nodeDate : ReminderCycleHelper.nodeDatesBetween(reminder.getCycleNode(),
                        rangeStart.getTime(), rangeEnd.getTime())) {
                    CalendarView.EventData event = new CalendarView.EventData();
                    event.setTitle(reminder.getTitle());
                    event.description = reminder.getMessage() != null ? reminder.getMessage() : "";
                    event.tag = "reminder";
                    event.setDate(toCalendarKey(CycleCalculator.formatDateId(nodeDate)));
                    addEvent(eventMap, event.date, event);
                }
                continue;
            }

            if (reminder.isRepeatMode()) {
                // 重复规则提醒：把规则展开成区间内的具体日期（粉色点）
                for (Date repeatDate : ReminderRepeatHelper.occurrencesBetween(reminder,
                        rangeStart.getTime(), rangeEnd.getTime())) {
                    CalendarView.EventData event = new CalendarView.EventData();
                    event.setTitle(reminder.getTitle());
                    event.description = reminder.getMessage() != null ? reminder.getMessage() : "";
                    event.tag = "reminder";
                    event.setDate(toCalendarKey(CycleCalculator.formatDateId(repeatDate)));
                    addEvent(eventMap, event.date, event);
                }
                continue;
            }

            CalendarView.EventData event = new CalendarView.EventData();
            event.setTitle(reminder.getTitle());
            event.description = reminder.getMessage() != null ? reminder.getMessage() : "";
            event.tag = "reminder";
            event.setDate(toCalendarKey(reminder.getDateId()));
            addEvent(eventMap, toCalendarKey(reminder.getDateId()), event);
        }

        // 月经提醒（设置页开启时）：在“预计经期首日 - 提前提醒天数”那天挂一条提醒事项，
        // 与实际通知日期保持一致。经期首日按当前阶段模型推算（叠加阶段锚点），
        // 不能再用“周期起点 + 周期天数”，否则阶段被调整后提醒会早/晚好几天。
        if (PeriodReminderScheduler.isEnabled(requireContext())) {
            int daysBefore = PeriodReminderScheduler.getReminderDays(requireContext());
            for (Date periodStart : ReminderCycleHelper.nodeDatesBetween(
                    Reminder.NODE_PERIOD_START, rangeStart.getTime(), rangeEnd.getTime())) {
                Date reminderDay = shiftDays(periodStart, -daysBefore);
                CalendarView.EventData event = new CalendarView.EventData();
                event.setTitle(getString(R.string.period_reminder_notification_title));
                event.description = "";
                event.tag = "reminder";
                event.setDate(toCalendarKey(CycleCalculator.formatDateId(reminderDay)));
                addEvent(eventMap, event.date, event);
            }
        }

        calendarView.setEvents(eventMap);
    }

    /**
     * 合并同一天的事件，避免后写入的事件覆盖先写入的。
     */
    private void addEvent(Map<String, List<CalendarView.EventData>> eventMap,
                          String key, CalendarView.EventData event) {
        List<CalendarView.EventData> events = eventMap.get(key);
        if (events == null) {
            events = new ArrayList<>();
            eventMap.put(key, events);
        }
        events.add(event);
    }

    /**
     * 日期按自然日平移（返回当天 0 点）。
     *
     * @param day  基准日期
     * @param days 偏移天数，可为负
     * @return 平移后的日期
     */
    private Date shiftDays(Date day, int days) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(day);
        calendar.add(Calendar.DAY_OF_MONTH, days);
        return CycleCalculator.normalizeDate(calendar.getTime());
    }

    /**
     * 把 yyyyMMdd 转换为日历控件使用的 yyyy-MM-dd。
     *
     * @param dateId 形如 20261101 的日期
     * @return 形如 2026-11-01 的日期；格式不合法时原样返回
     */
    private String toCalendarKey(String dateId) {
        if (dateId == null || dateId.length() != 8) {
            return dateId;
        }
        return dateId.substring(0, 4) + "-" + dateId.substring(4, 6) + "-" + dateId.substring(6, 8);
    }

    /**
     * 点击日期：已有记录直接打开建议弹窗；没有记录但当天有启用的自定义提醒时也打开
     * 建议弹窗（其中会展示当日提醒）；否则若不是未来日期则弹出补录框
     */
    private void showDailySuggestion(Calendar date) {
        String dateId = CycleCalculator.formatDateId(date.getTime());
        MenstrualRecord record = recordDao.selectById(dateId);

        boolean hasDayReminder = false;
        Date day = CycleCalculator.parseDateId(dateId);
        if (day != null) {
            // 与日历圆点、当日提醒列表同口径：周期节点/阶段区间/重复规则展开后的当天都算
            for (Reminder reminder : reminderDao.selectAll()) {
                if (ReminderCycleHelper.occursOn(reminder, day)) {
                    hasDayReminder = true;
                    break;
                }
            }
        }

        if (record != null || hasDayReminder) {
            openDailySuggestion(dateId);
        } else {
            Calendar today = Calendar.getInstance();
            if (!date.after(today)) {
                // 过去/今天无记录：走补录弹窗（选阶段后写入记录，经期还会继续补录流量/痛经）
                showMakeupRecordDialog(dateId);
            } else {
                // 未来日期只展示预测，不允许写入实际记录；
                // 打开每日建议弹窗仅供查看（弹窗内部会禁用阶段编辑）
                openDailySuggestion(dateId);
            }
        }
    }

    /**
     * 打开每日建议弹窗，并在阶段被修改后重算预测、刷新日历。
     */
    private void openDailySuggestion(String dateId) {
        DailySuggestionBottomSheet bottomSheet = DailySuggestionBottomSheet.newInstance(dateId, false);
        bottomSheet.setOnPhaseChangedListener(new DailySuggestionBottomSheet.OnPhaseChangedListener() {
            /** 单日修改阶段：只刷新展示，不自动重排后续预测 */
            @Override
            public void onPhaseChanged() {
                loadData();
            }

            /** 批量范围修改（用户显式操作）：保留重排预测 */
            @Override
            public void onPhaseRangeChanged() {
                regenerateRecords();
                loadData();
            }
        });
        bottomSheet.show(getChildFragmentManager(), "DailySuggestionBottomSheet");
    }

    /**
     * 补录弹窗：四阶段卡片式选择 + 批量调整入口（统一由 {@link MakeupRecordDialog} 提供）。
     *
     * @param dateId 日期 id（yyyyMMdd）
     */
    private void showMakeupRecordDialog(String dateId) {
        MakeupRecordDialog.show(requireContext(), dateId, new MakeupRecordDialog.Callback() {
            /** 点选阶段卡片：写入对应阶段（经期还会继续补录流量/痛经） */
            @Override
            public void onPhaseSelected(@NonNull MenstrualCycle cycle) {
                if (!isAdded()) return;
                createMakeupRecord(dateId, cycle);
            }

            /** 批量调整：进入涂抹式范围编辑器（范围从该日期到今天） */
            @Override
            public void onBatchEdit() {
                if (!isAdded()) return;
                PhaseRangeEditor.show(requireContext(), dateId, () -> {
                    if (!isAdded()) return;
                    regenerateRecords();
                    loadData();
                });
            }
        });
    }

    /**
     * 写入补录记录并刷新页面。
     * <p>
     * 该日期通常已经有自动生成的预测记录，直接 insert 会撞主键；
     * 因此改为"存在则覆盖更新、不存在才插入"，写入成功后同步基准日并重算预测。
     */
    private void createMakeupRecord(String dateId, MenstrualCycle cycle) {
        // 非经期阶段修改先做连贯性检查：与前后有效阶段不连贯（孤岛/倒置）时，
        // 让用户选择"逐天调整阶段范围"（弹出范围编辑器）、"仅修改当天"或取消
        PhaseContinuityHelper.CheckResult check = PhaseContinuityHelper.check(
                dateId, cycle, recordDao, cycleRecordDao);
        if (check.type != PhaseContinuityHelper.Discontinuity.NONE) {
            showContinuityDialog(dateId, cycle, check);
            return;
        }
        doCreateMakeupRecord(dateId, cycle);
    }

    /**
     * 阶段不连贯时的选择弹窗：批量调整阶段 / 取消。
     * <p>黄体期→经期：提示后果（只落单日、不重排预测），用户确认后仍保存当天。
     */
    private void showContinuityDialog(String dateId, MenstrualCycle cycle,
                                      PhaseContinuityHelper.CheckResult check) {
        // 黄体期还没结束就补签经期：不硬拦，提示后允许只保存当天；
        // DAO 的 reconcile 判定会因间隔不足而不新增分段，预测周期保持不动
        if (check.type == PhaseContinuityHelper.Discontinuity.LUTEAL_TO_PERIOD) {
            AppDialogFactory.showConfirm(requireContext(),
                    getString(R.string.phase_range_invalid_title),
                    getString(R.string.phase_edit_luteal_to_period_message),
                    R.string.phase_edit_save_single_day, R.string.dialog_cancel,
                    () -> {
                        if (!isAdded()) return;
                        doCreateMakeupRecord(dateId, cycle);
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
                () -> {
                    if (!isAdded()) return;
                    PhaseRangeEditor.show(requireContext(), dateId, () -> {
                        if (!isAdded()) return;
                        regenerateRecords();
                        loadData();
                    });
                });
    }

    /**
     * 真正落库补录记录（只改当天）。
     * <p>需要"逐天调整并顺延后续预测"时走 {@link PhaseRangeEditor}，
     * 它会在保存范围内所有日期后写入新的阶段锚点。
     *
     * @param dateId 日期 id（yyyyMMdd）
     * @param cycle  目标阶段
     */
    private void doCreateMakeupRecord(String dateId, MenstrualCycle cycle) {
        MenstrualRecord record = recordDao.selectById(dateId);
        boolean isNew = record == null;
        if (isNew) {
            record = new MenstrualRecord();
            record.setId(dateId);
        }
        record.setMenstrualCycle(cycle);
        // 0 表示用户实际记录（实体含义为 false），与库里 is_prediction=1 对应
        record.setIsPrediction(0);
        record.setSuggestion(generateSuggestionForPhase(cycle));

        boolean saved = isNew ? recordDao.insert(record) > 0 : recordDao.updateRecord(record) > 0;
        if (saved) {
            // 单日补签只落这一天，不自动重排后续预测：
            // 重排会把整段阶段顺延，用户看到的预测反而更乱。
            // 需要重排时请走显式的“批量调整阶段”或设置页的周期设置。
            loadData();
            // 补录为经期时，继续打开流量 / 痛经录入弹窗，让用户补全当天的经期数据
            if (cycle == MenstrualCycle.MENSTRUAL_PERIOD) {
                openRecordEditor(dateId);
            }
        }
    }

    /**
     * 打开指定日期的流量 / 痛经录入弹窗。
     * <p>
     * 未来日期不允许录入；保存后同步预测基准日并刷新日历。
     *
     * @param dateId 日期 id（yyyyMMdd）
     */
    private void openRecordEditor(String dateId) {
        Date date = CycleCalculator.parseDateId(dateId);
        if (date == null) {
            return;
        }
        // parseDateId 解析结果为当天 00:00，只要不晚于当前时刻即为今天或过去
        if (date.after(new Date())) {
            Toast.makeText(requireContext(), R.string.calendar_record_future_not_allowed,
                    Toast.LENGTH_SHORT).show();
            return;
        }

        // 没有记录时无法确定该日期属于哪个阶段，先让用户补签阶段，避免写入空阶段导致数据异常
        if (recordDao.selectById(dateId) == null) {
            showMakeupRecordDialog(dateId);
            return;
        }

        RecordDetailBottomSheet bottomSheet =
                RecordDetailBottomSheet.newInstance(dateId, RecordDetailBottomSheet.MODE_ALL);
        bottomSheet.setOnRecordSavedListener(() -> {
            // 流量/痛经只影响当天记录本身，不自动重排后续预测，刷新日历即可
            loadData();
        });
        bottomSheet.show(getChildFragmentManager(), "RecordDetailBottomSheet");
    }

    /**
     * 取阶段对应的静态建议文案（补录记录时使用）
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
     * 按最新的周期设置重新生成到今天为止的每日记录，并把基准日对齐到最近的实际经期。
     * 在用户补录/修改经期记录后调用，保证预测与实际记录保持一致。
     */
    private void regenerateRecords() {
        if (currentCycleRecord == null || getActivity() == null) {
            return;
        }

        cycleRecordDao.syncActualStartAndRegenerate(requireContext());
        // 基准日/周期参数可能已改变，按最新数据重排经期提醒与周期节点提醒
        PeriodReminderScheduler.apply(requireContext());
        ReminderAlarmScheduler.rescheduleAll(requireContext());
    }
}
