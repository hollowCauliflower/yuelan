package work.huacai.yuelan.fragments;

import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.charts.BarLineChartBase;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter;
import com.github.mikephil.charting.formatter.ValueFormatter;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import work.huacai.yuelan.MainActivity;
import work.huacai.yuelan.R;
import work.huacai.yuelan.adapter.RecordAdapter;
import work.huacai.yuelan.dao.MenstrualRecordDao;
import work.huacai.yuelan.dao.impl.MenstrualRecordDaoImpl;
import work.huacai.yuelan.helper.CycleCalculator;
import work.huacai.yuelan.model.MenstrualCycle;
import work.huacai.yuelan.model.MenstrualRecord;
import work.huacai.yuelan.widget.AppDialogFactory;
import work.huacai.yuelan.widget.CycleRhythmView;
import work.huacai.yuelan.widget.DailySuggestionBottomSheet;

/**
 * 统计页 Fragment。
 * <p>
 * 统计口径（重要，避免"周期数"歧义）：
 * <ul>
 *   <li><b>记录天数 / 经期天数</b>：按用户实际记录过的日期统计；</li>
 *   <li><b>完整周期</b>：把经期按"相邻经期日期间隔 ≥ 10 天"切分成若干轮，
 *       只有"下一次经期已经开始"的轮次才算完整，因此完整周期数 = 轮次数 - 1，
 *       最后一段进行中的周期不计入；</li>
 *   <li><b>平均周期长度</b>：各轮经期开始日之间的平均间隔，至少需要 1 个完整周期；</li>
 * </ul>
 * 页面还包含：流量趋势折线图、痛经分布柱状图、周期节律条形图（直观展示每轮长短），
 * 以及"经期周期明细"和最近记录列表。
 * <p>
 * 注：原“阶段分布”卡片已移除——它按“记录条数”计算占比，而用户几乎只在经期
 * 录入流量/痛经，结果长期接近 100% 月经期，无法反映真实的周期结构。
 */
public class StatisticsFragment extends Fragment {

    private TextView tvTotalRecords;
    private TextView tvMenstrualDays;
    private TextView tvCycleCount;
    private TextView tvAvgCycle;
    private RecyclerView rvRecords;
    private TextView tvViewAllRecords;
    private LineChart chartFlow;
    private BarChart chartPain;
    private View cardCycleSummary;
    private LinearLayout layoutCycleSummaryList;
    /** 周期汇总为空时的占位文本，目前由整张卡片隐藏，此处保留 id 绑定 */
    private TextView tvNoCycleData;
    /** 周期节律卡片与自定义条形图 */
    private View cardCycleRhythm;
    private CycleRhythmView rhythmView;
    private TextView tvRhythmSummary;

    private MenstrualRecordDao recordDao;
    private RecordAdapter adapter;

    public StatisticsFragment() {
    }

    public static StatisticsFragment newInstance() {
        return new StatisticsFragment();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_statistics, container, false);
    }

    /**
     * 视图创建完成：初始化 DAO、绑定控件并首次加载统计数据
     */
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        recordDao = new MenstrualRecordDaoImpl();

        initViews(view);
        loadData();
    }

    /**
     * 每次回到统计页都重新读取数据，保证记录变化后统计结果即时刷新
     */
    @Override
    public void onResume() {
        super.onResume();
        loadData();
    }

    /**
     * 绑定布局控件并初始化最近记录列表的适配器
     */
    private void initViews(View view) {
        tvTotalRecords = view.findViewById(R.id.tv_total_records);
        tvMenstrualDays = view.findViewById(R.id.tv_menstrual_days);
        tvCycleCount = view.findViewById(R.id.tv_cycle_count);
        tvAvgCycle = view.findViewById(R.id.tv_avg_cycle);
        rvRecords = view.findViewById(R.id.rv_records);
        tvViewAllRecords = view.findViewById(R.id.tv_view_all_records);
        // “查看全部”跳转日历 tab，日历页是完整历史的主视图
        tvViewAllRecords.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).setCurrentItem(1);
            }
        });
        chartFlow = view.findViewById(R.id.chart_flow);
        chartPain = view.findViewById(R.id.chart_pain);
        cardCycleSummary = view.findViewById(R.id.card_cycle_summary);
        layoutCycleSummaryList = view.findViewById(R.id.layout_cycle_summary_list);
        tvNoCycleData = view.findViewById(R.id.tv_no_cycle_data);
        cardCycleRhythm = view.findViewById(R.id.card_cycle_rhythm);
        rhythmView = view.findViewById(R.id.rhythm_view);
        tvRhythmSummary = view.findViewById(R.id.tv_rhythm_summary);

        // "完整周期"旁的 ⓘ：点击弹出统计口径说明，卡片内不再常驻解释文案
        view.findViewById(R.id.tv_cycle_count_info).setOnClickListener(v ->
                AppDialogFactory.showConfirm(requireContext(),
                        getString(R.string.statistics_cycle_count),
                        getString(R.string.statistics_cycle_logic_hint),
                        R.string.dialog_i_know, 0, null));

        // 图表空数据时显示中文占位，而不是 MPAndroidChart 默认的英文 “No chart data available”
        initChartEmptyState(chartFlow);
        initChartEmptyState(chartPain);

        rvRecords.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new RecordAdapter(new ArrayList<>(), this::showRecordDetail);
        rvRecords.setAdapter(adapter);
    }

    /**
     * 读取全部记录并刷新统计数值、两个图表、周期汇总卡片和底部记录列表。
     * 无数据时各数值归零展示并直接返回。
     */
    private void loadData() {
        // 只统计用户实际记录过的日期；自动生成的预测占位记录不计入任何指标
        List<MenstrualRecord> records = recordDao.selectActual();

        if (records == null || records.isEmpty()) {
            tvTotalRecords.setText("0");
            tvMenstrualDays.setText("0");
            tvCycleCount.setText("0");
            tvAvgCycle.setText("--");
            // 清空图表，避免复用 Fragment 时残留上一次的数据
            clearChart(chartFlow);
            clearChart(chartPain);
            cardCycleSummary.setVisibility(View.GONE);
            cardCycleRhythm.setVisibility(View.GONE);
            rhythmView.setItems(null);
            adapter.updateRecords(new ArrayList<>());
            return;
        }

        int totalDays = records.size();
        int menstrualDays = 0;
        int totalPainLevel = 0;
        int painRecordCount = 0;

        for (MenstrualRecord record : records) {
            if (record.getMenstrualCycle() == MenstrualCycle.MENSTRUAL_PERIOD) {
                menstrualDays++;
            }

            // 痛经 0 级表示“无痛经”，与“未录入”无法从数值上区分：
            // 只有填写过流量或痛经表单的记录才视为已录入，避免把补录的非经期记录算成“无痛经”
            if (record.hasFlowData() || record.hasPainData()) {
                totalPainLevel += record.getPainLevel();
                painRecordCount++;
            }
        }

        // 大数字加千分位，几千天时也不会挤成一团难以辨认
        tvTotalRecords.setText(formatCount(totalDays));
        tvMenstrualDays.setText(formatCount(menstrualDays));

        // 周期相关统计一律用经期切分的实测值。
        // 完整周期 = 轮次数 - 1：只有"下一次经期已经开始"的轮次才算一个完整周期，
        // 最后一段仍进行中的周期不计入，避免把"正在来的这一轮"也算成已完成。
        List<List<MenstrualRecord>> cycles = splitCycles(records);
        int completeCycles = Math.max(0, cycles.size() - 1);
        tvCycleCount.setText(formatCount(completeCycles));

        if (completeCycles >= 1) {
            Date firstStart = CycleCalculator.parseDateId(cycles.get(0).get(0).getId());
            Date lastStart = CycleCalculator.parseDateId(cycles.get(cycles.size() - 1).get(0).getId());
            if (firstStart != null && lastStart != null) {
                // 按自然日计算跨度，避免夏令时导致某一天只有 23 小时而少算一天
                int spanDays = CycleCalculator.daysBetween(firstStart, lastStart);
                int avgCycle = Math.round(spanDays / (float) completeCycles);
                tvAvgCycle.setText(avgCycle + getString(R.string.statistics_days));
            } else {
                tvAvgCycle.setText("--");
            }
        } else {
            tvAvgCycle.setText("--");
        }

        // 各阶段占比已移除：占比按记录条数计算会被“只在经期录入”的习惯严重带偏

        setupFlowChart(records);
        setupPainChart(records);
        setupCycleRhythm(cycles);
        setupCycleSummary(cycles);

        // 列表只展示最近 10 条（selectAll 已按 id 倒序，即日期从新到旧），
        // 完整历史通过“查看全部”去日历页看
        List<MenstrualRecord> recentRecords = records.size() > 10 ? records.subList(0, 10) : records;
        adapter.updateRecords(recentRecords);
    }

    /**
     * 绘制流量折线图：按日期升序取最近 30 条“有流量录入”的记录，Y 轴为流量分值。
     * <p>没有流量数据的记录（如补录的非经期日期）不参与绘制，否则会把折线拉到 0。
     */
    private void setupFlowChart(List<MenstrualRecord> records) {
        // 记录 id 本身就是 yyyyMMdd 格式（见 CycleCalculator.formatDateId），据此解析排序
        SimpleDateFormat idFormat = new SimpleDateFormat("yyyyMMdd", Locale.getDefault());
        SimpleDateFormat xLabelFormat = new SimpleDateFormat("MM/dd", Locale.getDefault());

        List<MenstrualRecord> sortedRecords = new ArrayList<>();
        for (MenstrualRecord record : records) {
            if (record.hasFlowData()) {
                sortedRecords.add(record);
            }
        }
        Collections.sort(sortedRecords, (r1, r2) -> {
            try {
                Date d1 = idFormat.parse(r1.getId());
                Date d2 = idFormat.parse(r2.getId());
                return d1 != null && d2 != null ? d1.compareTo(d2) : 0;
            } catch (Exception e) {
                return 0;
            }
        });

        // 只保留最近 30 条，避免折线过密
        List<MenstrualRecord> recentRecords = sortedRecords.size() > 30
                ? sortedRecords.subList(sortedRecords.size() - 30, sortedRecords.size())
                : sortedRecords;

        // 没有任何流量录入时显示空状态，而不是 “No chart data available”
        if (recentRecords.isEmpty()) {
            clearChart(chartFlow);
            return;
        }

        List<Entry> entries = new ArrayList<>();
        List<String> xLabels = new ArrayList<>();
        for (int i = 0; i < recentRecords.size(); i++) {
            MenstrualRecord record = recentRecords.get(i);
            entries.add(new Entry(i, record.getFlowScore()));
            try {
                Date date = idFormat.parse(record.getId());
                xLabels.add(date != null ? xLabelFormat.format(date) : "");
            } catch (Exception e) {
                xLabels.add("");
            }
        }

        LineDataSet dataSet = new LineDataSet(entries, getString(R.string.statistics_flow_level));
        dataSet.setColor(Color.parseColor("#FF6B93"));
        dataSet.setCircleColor(Color.parseColor("#FF6B93"));
        dataSet.setCircleRadius(4f);
        dataSet.setLineWidth(2f);
        dataSet.setMode(LineDataSet.Mode.CUBIC_BEZIER);
        dataSet.setDrawValues(false);
        dataSet.setFillColor(Color.parseColor("#33FF6B93"));
        dataSet.setFillAlpha(100);
        dataSet.setDrawFilled(true);

        chartFlow.setData(new LineData(dataSet));
        applyCommonChartStyle(chartFlow, new IndexAxisValueFormatter(xLabels), null);
        // 日期标签较长，倾斜 -45° 避免相互重叠
        chartFlow.getXAxis().setLabelRotationAngle(-45f);
        chartFlow.setTouchEnabled(true);
        chartFlow.setPinchZoom(true);
        chartFlow.invalidate();
    }

    /**
     * 绘制痛经分布柱状图：统计痛经等级 0~4 各出现多少次。
     * <p>只统计真正录入过流量/痛经的记录，避免补录的非经期记录全部落到“无痛经”一档。
     */
    private void setupPainChart(List<MenstrualRecord> records) {
        // 下标即痛经等级，值即该等级的记录条数
        int[] painCounts = new int[5];

        for (MenstrualRecord record : records) {
            if (!record.hasFlowData() && !record.hasPainData()) {
                continue;
            }
            int painLevel = record.getPainLevel();
            if (painLevel >= 0 && painLevel < 5) {
                painCounts[painLevel]++;
            }
        }

        // 没有任何痛经数据时显示空状态，而不是画一排 0 的坐标轴
        int painTotal = 0;
        for (int count : painCounts) {
            painTotal += count;
        }
        if (painTotal == 0) {
            clearChart(chartPain);
            return;
        }

        List<BarEntry> entries = new ArrayList<>();
        String[] labels = {
                getString(R.string.statistics_pain_none),
                getString(R.string.statistics_pain_mild),
                getString(R.string.statistics_pain_moderate),
                getString(R.string.statistics_pain_severe),
                getString(R.string.statistics_pain_extreme)
        };
        int[] colors = {
                Color.parseColor("#4CAF50"),
                Color.parseColor("#8BC34A"),
                Color.parseColor("#FF9800"),
                Color.parseColor("#FF5722"),
                Color.parseColor("#F44336")
        };

        for (int i = 0; i < 5; i++) {
            entries.add(new BarEntry(i, painCounts[i]));
        }

        BarDataSet dataSet = new BarDataSet(entries, getString(R.string.statistics_pain_level));
        dataSet.setColors(colors);
        dataSet.setValueTextSize(12f);
        dataSet.setValueTextColor(ContextCompat.getColor(requireContext(), R.color.text_body));

        BarData barData = new BarData(dataSet);
        barData.setBarWidth(0.6f);
        chartPain.setData(barData);

        // 柱状图计数为整数，Y 轴去掉小数部分
        ValueFormatter intFormatter = new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return String.valueOf((int) value);
            }
        };
        applyCommonChartStyle(chartPain, new IndexAxisValueFormatter(labels), intFormatter);
        chartPain.setTouchEnabled(true);
        chartPain.setFitBars(true);
        chartPain.invalidate();
    }

    /**
     * 配置图表的空数据占位文案与样式：统一用中文、主题文字色，居中显示。
     *
     * @param chart 目标图表
     */
    private void initChartEmptyState(BarLineChartBase<?> chart) {
        chart.setNoDataText(getString(R.string.statistics_no_records));
        chart.setNoDataTextColor(ContextCompat.getColor(requireContext(), R.color.text_sub));
        Paint noDataPaint = chart.getPaint(BarLineChartBase.PAINT_INFO);
        if (noDataPaint != null) {
            noDataPaint.setTextSize(42f);
        }
        chart.invalidate();
    }

    /**
     * 清空图表数据并触发重绘，使空状态文案显示出来。
     *
     * @param chart 目标图表
     */
    private void clearChart(BarLineChartBase<?> chart) {
        chart.clear();
        chart.invalidate();
    }

    /**
     * 折线图与柱状图共用的坐标轴/图例样式，避免两处重复配置。
     *
     * @param chart      目标图表（折线图或柱状图）
     * @param xFormatter X 轴标签格式化器
     * @param yFormatter 左侧 Y 轴数值格式化器，传 null 表示使用默认格式化
     */
    private void applyCommonChartStyle(BarLineChartBase<?> chart,
                                       ValueFormatter xFormatter,
                                       ValueFormatter yFormatter) {
        int axisTextColor = ContextCompat.getColor(requireContext(), R.color.text_sub);
        int gridColor = ContextCompat.getColor(requireContext(), R.color.divider);

        XAxis xAxis = chart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setDrawGridLines(false);
        xAxis.setGranularity(1f);
        xAxis.setValueFormatter(xFormatter);
        xAxis.setTextColor(axisTextColor);

        YAxis leftAxis = chart.getAxisLeft();
        leftAxis.setDrawGridLines(true);
        leftAxis.setAxisMinimum(0f);
        leftAxis.setTextColor(axisTextColor);
        leftAxis.setGridColor(gridColor);
        if (yFormatter != null) {
            leftAxis.setValueFormatter(yFormatter);
        }

        // 右侧 Y 轴、图表描述和图例在两个图表中都不展示
        chart.getAxisRight().setEnabled(false);
        chart.getDescription().setEnabled(false);
        chart.getLegend().setEnabled(false);
    }

    /**
     * 按“经期开始~下一个经期开始前一天”切分周期：
     * 相邻经期日期间隔达到 10 天及以上视为进入新周期，非经期记录归入当前周期。
     * 第一个经期之前的非经期记录没有归属周期，直接丢弃。
     *
     * @param records 实际记录（顺序不限，调用方已过滤预测记录）
     * @return 按时间升序排列的周期列表，每个周期内记录同样按日期升序
     */
    private List<List<MenstrualRecord>> splitCycles(List<MenstrualRecord> records) {
        List<MenstrualRecord> sorted = new ArrayList<>(records);
        Collections.sort(sorted, (r1, r2) -> {
            Date d1 = CycleCalculator.parseDateId(r1.getId());
            Date d2 = CycleCalculator.parseDateId(r2.getId());
            if (d1 == null || d2 == null) return 0;
            return d1.compareTo(d2);
        });

        List<List<MenstrualRecord>> cycles = new ArrayList<>();
        List<MenstrualRecord> current = null;
        // 记录当前周期内“最后一条经期记录”的日期：
        // 不能拿周期内最后一条记录做间隔判断，否则经期末尾补录的非经期记录会把下一次经期误判为同一周期
        Date lastMenstrualDate = null;
        for (MenstrualRecord r : sorted) {
            if (r.getMenstrualCycle() == MenstrualCycle.MENSTRUAL_PERIOD) {
                Date cur = CycleCalculator.parseDateId(r.getId());
                boolean newCycle = current == null || lastMenstrualDate == null || cur == null
                        || CycleCalculator.daysBetween(lastMenstrualDate, cur) >= 10;
                if (newCycle) {
                    current = new ArrayList<>();
                    cycles.add(current);
                    lastMenstrualDate = null;
                }
                current.add(r);
                if (cur != null) {
                    lastMenstrualDate = cur;
                }
            } else if (current != null && !current.isEmpty()) {
                current.add(r);
            }
        }
        return cycles;
    }

    /**
     * 统计一个周期内的经期天数。
     * <p>按“首末经期记录的日期跨度”计算，而不是数记录条数：
     * 用户可能漏记中间某天，用条数会把 5 天经期算成 3 天。
     */
    private int countPeriodDays(List<MenstrualRecord> cycle) {
        Date first = null;
        Date last = null;
        for (MenstrualRecord r : cycle) {
            if (r.getMenstrualCycle() != MenstrualCycle.MENSTRUAL_PERIOD) {
                continue;
            }
            Date date = CycleCalculator.parseDateId(r.getId());
            if (date == null) {
                continue;
            }
            if (first == null) {
                first = date;
            }
            last = date;
        }
        if (first == null || last == null) {
            return 0;
        }
        // 自然日跨度 +1（含首尾），跨夏令时也不会算错
        return CycleCalculator.daysBetween(first, last) + 1;
    }

    /**
     * 构建"周期节律"条形图：展示最近 6 个完整周期的长度与经期占比。
     * <p>条形长度按"最长周期"等比缩放（所有行同一比例尺），
     * 因此能直观看出这一轮比上一轮长了几天；粉色段表示经期部分。
     * 没有任何完整周期时整张卡片隐藏。
     *
     * @param cycles 已按经期切分的轮次列表（升序）
     */
    private void setupCycleRhythm(List<List<MenstrualRecord>> cycles) {
        int completeCycles = cycles.size() - 1;
        if (completeCycles <= 0) {
            cardCycleRhythm.setVisibility(View.GONE);
            rhythmView.setItems(null);
            return;
        }

        // 最新周期排在最下面，最多展示 6 轮
        int from = Math.max(0, completeCycles - 6);
        List<CycleRhythmView.Item> items = new ArrayList<>();
        int sum = 0;
        int min = Integer.MAX_VALUE;
        int max = 0;
        for (int i = from; i < completeCycles; i++) {
            Date start = CycleCalculator.parseDateId(cycles.get(i).get(0).getId());
            Date nextStart = CycleCalculator.parseDateId(cycles.get(i + 1).get(0).getId());
            if (start == null || nextStart == null) {
                continue;
            }
            int cycleDays = CycleCalculator.daysBetween(start, nextStart);
            int periodDays = countPeriodDays(cycles.get(i));
            items.add(new CycleRhythmView.Item(
                    getString(R.string.statistics_rhythm_cycle_label, i + 1),
                    cycleDays, periodDays));
            sum += cycleDays;
            min = Math.min(min, cycleDays);
            max = Math.max(max, cycleDays);
        }

        if (items.isEmpty()) {
            cardCycleRhythm.setVisibility(View.GONE);
            rhythmView.setItems(null);
            return;
        }

        cardCycleRhythm.setVisibility(View.VISIBLE);
        rhythmView.setItems(items);
        tvRhythmSummary.setText(getString(R.string.statistics_rhythm_summary,
                Math.round(sum / (float) items.size()), min, max));
    }

    /**
     * 构建周期汇总卡片：展示每个周期的开始/结束/经期天数/平均流量/平均痛经。
     * 周期切分在 loadData 中统一完成，避免与顶部统计重复计算。
     */
    private void setupCycleSummary(List<List<MenstrualRecord>> cycles) {
        if (layoutCycleSummaryList == null) return;

        layoutCycleSummaryList.removeAllViews();

        if (cycles.isEmpty()) {
            cardCycleSummary.setVisibility(View.GONE);
            return;
        }

        cardCycleSummary.setVisibility(View.VISIBLE);

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        // 最多展示最近 6 个周期
        int limit = Math.min(cycles.size(), 6);
        // 倒序遍历：最新的周期排在最上面
        for (int i = limit - 1; i >= 0; i--) {
            List<MenstrualRecord> oneCycle = cycles.get(i);

            // 明细展示的是"经期"本身：开始/结束都取经期记录的首末日，
            // 不用周期内最后一条记录（它可能只是补录的普通日期，会造成误解）
            Date start = CycleCalculator.parseDateId(oneCycle.get(0).getId());
            Date end = start;
            for (MenstrualRecord r : oneCycle) {
                if (r.getMenstrualCycle() == MenstrualCycle.MENSTRUAL_PERIOD) {
                    Date date = CycleCalculator.parseDateId(r.getId());
                    if (date != null) {
                        end = date;
                    }
                }
            }

            int periodDays = countPeriodDays(oneCycle);
            int flowSum = 0;
            int flowCount = 0;
            int painSum = 0;
            int painCount = 0;
            for (MenstrualRecord r : oneCycle) {
                if (r.getMenstrualCycle() == MenstrualCycle.MENSTRUAL_PERIOD) {
                    if (r.hasFlowData()) {
                        flowSum += r.getFlowScore();
                        flowCount++;
                    }
                    // 0 级也计入平均值：有录入即为“无痛经”，而不是“未记录”
                    if (r.hasFlowData() || r.hasPainData()) {
                        painSum += r.getPainLevel();
                        painCount++;
                    }
                }
            }

            // 周期内平均流量/痛经；没有填写时显示“未记录”
            String flowText = flowCount > 0
                    ? getFlowLevelText(flowSum / flowCount)
                    : getString(R.string.statistics_not_recorded);
            String painText = painCount > 0
                    ? getPainLevelText(painSum / painCount)
                    : getString(R.string.statistics_not_recorded);

            View row = LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_cycle_summary, layoutCycleSummaryList, false);

            ((TextView) row.findViewById(R.id.tv_summary_start))
                    .setText(start != null ? sdf.format(start) : "--");
            ((TextView) row.findViewById(R.id.tv_summary_end))
                    .setText(end != null ? sdf.format(end) : "--");
            ((TextView) row.findViewById(R.id.tv_summary_period_days))
                    .setText(String.valueOf(periodDays));
            ((TextView) row.findViewById(R.id.tv_summary_flow))
                    .setText(flowText);
            ((TextView) row.findViewById(R.id.tv_summary_pain))
                    .setText(painText);

            layoutCycleSummaryList.addView(row);
        }
    }

    /**
     * 天数格式化：超过一千时加千分位分隔符，避免"3650"这类长数字难以辨识。
     *
     * @param value 天数
     * @return 展示文本
     */
    private String formatCount(int value) {
        return String.format(Locale.getDefault(), "%,d", value);
    }

    /**
     * 平均流量分值转文案：小于 25 为少量，25~100 为中等，其余为大量
     */
    private String getFlowLevelText(int avgScore) {
        if (avgScore < 25) return getString(R.string.statistics_flow_light);
        if (avgScore <= 100) return getString(R.string.statistics_flow_medium);
        return getString(R.string.statistics_flow_heavy);
    }

    /**
     * 平均痛经等级（0~4）转文案
     */
    private String getPainLevelText(int avgPain) {
        switch (avgPain) {
            case 0: return getString(R.string.statistics_pain_none);
            case 1: return getString(R.string.statistics_pain_mild);
            case 2: return getString(R.string.statistics_pain_moderate);
            case 3: return getString(R.string.statistics_pain_severe);
            default: return getString(R.string.statistics_pain_extreme);
        }
    }

    /**
     * 记录条目点击：打开对应日期的建议/详情弹窗
     */
    private void showRecordDetail(MenstrualRecord record) {
        if (record != null && record.getId() != null) {
            DailySuggestionBottomSheet bottomSheet =
                    DailySuggestionBottomSheet.newInstance(record.getId(), false);
            bottomSheet.show(getChildFragmentManager(), "DailySuggestionBottomSheet");
        }
    }
}
