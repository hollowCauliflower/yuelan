package work.huacai.yuelan.adapter;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import work.huacai.yuelan.R;
import work.huacai.yuelan.helper.CycleCalculator;
import work.huacai.yuelan.model.MenstrualCycle;
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

        // 建议预览最多展示 20 个字符，超出部分用省略号截断
        String suggestion = record.getSuggestion();
        if (suggestion != null && !suggestion.isEmpty()) {
            if (suggestion.length() > 20) {
                suggestion = suggestion.substring(0, 20) + "...";
            }
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
