package work.huacai.yuelan.adapter;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import work.huacai.yuelan.R;
import work.huacai.yuelan.dao.MenstrualCycleRecordDao;
import work.huacai.yuelan.dao.MenstrualRecordDao;
import work.huacai.yuelan.dao.impl.MenstrualCycleRecordDaoImpl;
import work.huacai.yuelan.dao.impl.MenstrualRecordDaoImpl;
import work.huacai.yuelan.helper.CycleCalculator;
import work.huacai.yuelan.helper.SuggestionContent;
import work.huacai.yuelan.helper.SuggestionContext;
import work.huacai.yuelan.helper.SuggestionContextHelper;
import work.huacai.yuelan.helper.SuggestionEngine;
import work.huacai.yuelan.helper.SuggestionTemplateRepository;
import work.huacai.yuelan.model.MenstrualCycle;
import work.huacai.yuelan.model.MenstrualCycleRecord;
import work.huacai.yuelan.model.MenstrualRecord;

/**
 * 记录列表适配器。
 * <p>
 * 用于统计页等处展示“某一天”的记录摘要：日期、所属周期阶段、建议预览，
 * 以及该天是否录入了流量 / 痛经数据，条目布局为 {@code R.layout.item_record}。
 */
public class RecordAdapter extends RecyclerView.Adapter<RecordAdapter.RecordViewHolder> {

    /**
     * 日期展示格式（如“2024年5月20日”）。
     * SimpleDateFormat 非线程安全，这里只在主线程的 onBindViewHolder 中使用。
     */
    private static final SimpleDateFormat DATE_DISPLAY_FORMAT = new SimpleDateFormat("yyyy年M月d日", Locale.getDefault());

    private List<MenstrualRecord> records;
    private OnRecordClickListener listener;
    /** 周期参数用于给建议引擎推算周期第几天，与弹窗保持同一口径 */
    private final MenstrualCycleRecordDao cycleRecordDao = new MenstrualCycleRecordDaoImpl();
    /** 实际记录缓存：列表刷新时更新一次，避免每个条目都查一遍库 */
    private List<MenstrualRecord> actualRecords;

    /**
     * 条目点击回调
     */
    public interface OnRecordClickListener {
        /**
         * @param record 被点击条目对应的记录
         */
        void onRecordClick(MenstrualRecord record);
    }

    /**
     * @param records  记录列表（可为空集合，不应为 null）
     * @param listener 点击回调，可为 null（为 null 时点击不响应）
     */
    public RecordAdapter(List<MenstrualRecord> records, OnRecordClickListener listener) {
        this.records = records;
        this.listener = listener;
    }

    /**
     * 设置条目点击回调。
     *
     * @param listener 点击回调，可为 null
     */
    public void setOnRecordClickListener(OnRecordClickListener listener) {
        this.listener = listener;
    }

    /**
     * 替换整个数据集并刷新列表。
     * <p>副作用：调用 {@link #notifyDataSetChanged()} 触发全量重绘。
     *
     * @param newRecords 新的记录列表
     */
    public void updateRecords(List<MenstrualRecord> newRecords) {
        this.records = newRecords;
        // 历史信号（规律性/连续打卡/痛经趋势）按实际记录计算，整批刷新一次即可
        this.actualRecords = new MenstrualRecordDaoImpl().selectActual();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public RecordViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_record, parent, false);
        return new RecordViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecordViewHolder holder, int position) {
        MenstrualRecord record = records.get(position);
        Context context = holder.itemView.getContext();

        // 记录 id 即 yyyyMMdd 形式的日期串，解析失败时直接显示原始 id
        Date date = CycleCalculator.parseDateId(record.getId());
        if (date != null) {
            holder.tvDate.setText(DATE_DISPLAY_FORMAT.format(date));
        } else {
            holder.tvDate.setText(record.getId());
        }

        holder.tvPhase.setText(getPhaseDisplayName(context, record.getMenstrualCycle()));
        holder.viewPhaseColor.setBackgroundColor(getPhaseColor(record.getMenstrualCycle()));

        // 建议预览改为引擎生成的短句：数据库里的 suggestion 是写库时随机冻结的旧文案，
        // 直接展示会和每日建议弹窗的正文不一致
        String suggestion = buildSuggestionPreview(context, date, record);
        if (suggestion != null && !suggestion.isEmpty()) {
            holder.tvSuggestionPreview.setText(suggestion);
        } else {
            holder.tvSuggestionPreview.setText(R.string.record_adapter_no_suggestion);
        }

        // 流量 / 痛经标签：两者都没有时整行隐藏，避免出现空白占位
        boolean hasFlow = record.hasFlowData();
        boolean hasPain = record.hasPainData();
        if (hasFlow || hasPain) {
            holder.layoutIndicators.setVisibility(View.VISIBLE);

            if (hasFlow) {
                holder.tvFlowIndicator.setVisibility(View.VISIBLE);
                holder.tvFlowIndicator.setText(
                        context.getString(R.string.record_adapter_flow_label,
                                record.getFlowLevelText(context), record.getFlowScore()));
            } else {
                holder.tvFlowIndicator.setVisibility(View.GONE);
            }

            if (hasPain) {
                holder.tvPainIndicator.setVisibility(View.VISIBLE);
                holder.tvPainIndicator.setText(
                        context.getString(R.string.record_adapter_pain_label,
                                record.getPainLevelText(context)));
            } else {
                holder.tvPainIndicator.setVisibility(View.GONE);
            }
        } else {
            holder.layoutIndicators.setVisibility(View.GONE);
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onRecordClick(record);
            }
        });
    }

    @Override
    public int getItemCount() {
        return records != null ? records.size() : 0;
    }

    /**
     * 生成列表用的建议预览。
     * <p>与每日建议弹窗共用同一引擎与模板，只取第一句（开场优先），
     * 超过 20 个字符时截断。引擎无内容时回退数据库里的旧文案。
     *
     * @param context 用于取阶段名与模板
     * @param date    记录日期，解析失败时为 null
     * @param record  记录本身
     * @return 预览文案；无任何可用内容时返回 null
     */
    @Nullable
    private String buildSuggestionPreview(Context context, @Nullable Date date,
                                          MenstrualRecord record) {
        if (date == null) {
            return record.getSuggestion();
        }
        MenstrualCycleRecord cycleRecord = cycleRecordDao.selectLast();
        if (actualRecords == null) {
            actualRecords = new MenstrualRecordDaoImpl().selectActual();
        }
        SuggestionContext suggestionContext = SuggestionContextHelper.build(context, date, record,
                record.getMenstrualCycle(), null, cycleRecord, actualRecords, null, null);
        SuggestionContent content = SuggestionEngine.generate(suggestionContext,
                SuggestionTemplateRepository.getTemplates(context));
        String headline = content.getHeadline();
        if (headline == null || headline.isEmpty()) {
            return record.getSuggestion();
        }
        // 列表行宽有限，超出部分统一截断，避免不同条目高度参差
        return headline.length() > 20 ? headline.substring(0, 20) + "..." : headline;
    }

    /**
     * 周期阶段的本地化名称；未知阶段时退回枚举名，便于排查数据问题。
     */
    private String getPhaseDisplayName(Context context, MenstrualCycle cycle) {
        switch (cycle) {
            case MENSTRUAL_PERIOD:
                return context.getString(R.string.cycle_menstrual_period_name);
            case FOLLICULAR_PHASE:
                return context.getString(R.string.cycle_follicular_phase_name);
            case OVULATION_PERIOD:
                return context.getString(R.string.cycle_ovulation_period_name);
            case LUTE_PHASE:
                return context.getString(R.string.cycle_lute_phase_name);
            default:
                return cycle.name();
        }
    }

    /**
     * 阶段圆点颜色（与日历、详情弹窗保持同一套色值）。
     * <p>注意：这里使用硬编码的十进制颜色而非资源色，切换深色模式时颜色不变。
     */
    private int getPhaseColor(MenstrualCycle cycle) {
        switch (cycle) {
            case MENSTRUAL_PERIOD:
                return Color.parseColor("#FF6B93");
            case FOLLICULAR_PHASE:
                return Color.parseColor("#4CAF50");
            case OVULATION_PERIOD:
                return Color.parseColor("#FF9800");
            case LUTE_PHASE:
                return Color.parseColor("#2196F3");
            default:
                return Color.parseColor("#9E9E9E");
        }
    }

    /**
     * 条目视图缓存，避免 onBindViewHolder 中重复 findViewById。
     */
    static class RecordViewHolder extends RecyclerView.ViewHolder {
        View viewPhaseColor;
        TextView tvDate;
        TextView tvPhase;
        TextView tvSuggestionPreview;
        LinearLayout layoutIndicators;
        TextView tvFlowIndicator;
        TextView tvPainIndicator;

        RecordViewHolder(@NonNull View itemView) {
            super(itemView);
            viewPhaseColor = itemView.findViewById(R.id.view_phase_color);
            tvDate = itemView.findViewById(R.id.tv_date);
            tvPhase = itemView.findViewById(R.id.tv_phase);
            tvSuggestionPreview = itemView.findViewById(R.id.tv_suggestion_preview);
            layoutIndicators = itemView.findViewById(R.id.layout_indicators);
            tvFlowIndicator = itemView.findViewById(R.id.tv_flow_indicator);
            tvPainIndicator = itemView.findViewById(R.id.tv_pain_indicator);
        }
    }
}
