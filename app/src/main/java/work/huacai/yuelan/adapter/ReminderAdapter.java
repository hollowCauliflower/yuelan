package work.huacai.yuelan.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import work.huacai.yuelan.R;
import work.huacai.yuelan.helper.CycleCalculator;
import work.huacai.yuelan.helper.ReminderCycleHelper;
import work.huacai.yuelan.helper.ReminderRepeatHelper;
import work.huacai.yuelan.model.MenstrualCycle;
import work.huacai.yuelan.model.Reminder;

/**
 * 自定义提醒列表适配器。
 */
public class ReminderAdapter extends RecyclerView.Adapter<ReminderAdapter.ReminderViewHolder> {

    /** 日期展示格式 */
    private static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat("MM月dd日", Locale.getDefault());

    /** 列表数据 */
    private final List<Reminder> reminders = new ArrayList<>();
    private final Listener listener;

    /**
     * 列表交互回调。
     */
    public interface Listener {
        /** 点击条目：编辑 */
        void onEdit(Reminder reminder);

        /** 点击删除 */
        void onDelete(Reminder reminder);

        /** 切换启用状态 */
        void onToggle(Reminder reminder, boolean enabled);
    }

    public ReminderAdapter(Listener listener) {
        this.listener = listener;
    }

    /**
     * 替换列表数据并刷新。
     *
     * @param data 新的提醒列表，可为 null
     */
    public void submitList(List<Reminder> data) {
        reminders.clear();
        if (data != null) {
            reminders.addAll(data);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ReminderViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_reminder, parent, false);
        return new ReminderViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ReminderViewHolder holder, int position) {
        Reminder reminder = reminders.get(position);

        holder.tvTitle.setText(reminder.getTitle());

        String dateText;
        if (reminder.isSpanMode()) {
            // 阶段区间提醒：展示"每周期·黄体期 · 每天"和区间内下一次日期
            int phaseIndex = ReminderCycleHelper.spanPhaseIndex(reminder.getCycleNode());
            String phaseName = MenstrualCycle.getByIndex(Math.max(phaseIndex, 0))
                    .getDisplayName(holder.itemView.getContext());
            Date next = ReminderCycleHelper.nextSpanOccurrence(reminder,
                    System.currentTimeMillis());
            dateText = holder.itemView.getContext()
                    .getString(R.string.reminder_span_meta_format, phaseName)
                    + (next != null
                    ? " · " + holder.itemView.getContext()
                            .getString(R.string.reminder_next_prefix) + " " + DATE_FORMAT.format(next)
                    : "");
        } else if (reminder.isCycleMode()) {
            // 周期节点提醒：展示"每周期·节点名"和下一次节点日期
            String nodeName = nodeName(holder, reminder.getCycleNode());
            Date next = ReminderCycleHelper.nextNodeDate(reminder.getCycleNode());
            dateText = nodeName + (next != null
                    ? " · " + DATE_FORMAT.format(next)
                    : holder.itemView.getContext().getString(R.string.reminder_cycle_pending_setup));
        } else if (reminder.isRepeatMode()) {
            // 重复规则提醒：展示规则描述与下一次触发日期，避免出现一大串重复日期
            String rule = ReminderRepeatHelper.describe(holder.itemView.getContext(), reminder);
            Date next = ReminderRepeatHelper.nextOccurrenceAfter(reminder,
                    System.currentTimeMillis());
            dateText = rule + (next != null
                    ? " · " + holder.itemView.getContext()
                            .getString(R.string.reminder_next_prefix) + " " + DATE_FORMAT.format(next)
                    : "");
        } else {
            Date date = CycleCalculator.parseDateId(reminder.getDateId());
            dateText = date != null ? DATE_FORMAT.format(date) : reminder.getDateId();
        }
        String time = reminder.getAlarmTime() != null ? reminder.getAlarmTime() : "";
        holder.tvMeta.setText(dateText + "  " + time);

        String message = reminder.getMessage();
        if (message != null && !message.isEmpty()) {
            holder.tvMessage.setVisibility(View.VISIBLE);
            holder.tvMessage.setText(message);
        } else {
            holder.tvMessage.setVisibility(View.GONE);
        }

        // 绑定前先移除监听，避免复用时触发误回调
        holder.switchEnabled.setOnCheckedChangeListener(null);
        holder.switchEnabled.setChecked(reminder.isEnabled());
        holder.switchEnabled.setOnCheckedChangeListener(
                (buttonView, isChecked) -> listener.onToggle(reminder, isChecked));

        holder.itemView.setOnClickListener(v -> listener.onEdit(reminder));
        holder.tvDelete.setOnClickListener(v -> listener.onDelete(reminder));
    }

    @Override
    public int getItemCount() {
        return reminders.size();
    }

    /** 周期节点的展示名称（如"每周期·排卵期开始"） */
    private static String nodeName(ReminderViewHolder holder, int cycleNode) {
        int res;
        switch (cycleNode) {
            case Reminder.NODE_PERIOD_END:
                res = R.string.reminder_node_period_end;
                break;
            case Reminder.NODE_FOLLICLE_START:
                res = R.string.reminder_node_follicle;
                break;
            case Reminder.NODE_OVULATION_START:
                res = R.string.reminder_node_ovulation;
                break;
            case Reminder.NODE_LUTEAL_START:
                res = R.string.reminder_node_luteal;
                break;
            case Reminder.NODE_BEFORE_PERIOD:
                res = R.string.reminder_node_before_period;
                break;
            case Reminder.NODE_PERIOD_START:
            default:
                res = R.string.reminder_node_period_start;
                break;
        }
        return holder.itemView.getContext().getString(R.string.reminder_cycle_prefix)
                + holder.itemView.getContext().getString(res);
    }

    /** 列表条目控件 */
    static class ReminderViewHolder extends RecyclerView.ViewHolder {
        final TextView tvTitle;
        final TextView tvMeta;
        final TextView tvMessage;
        final TextView tvDelete;
        final SwitchCompat switchEnabled;

        ReminderViewHolder(@NonNull View itemView) {
            super(itemView);
            tvTitle = itemView.findViewById(R.id.tv_title);
            tvMeta = itemView.findViewById(R.id.tv_meta);
            tvMessage = itemView.findViewById(R.id.tv_message);
            tvDelete = itemView.findViewById(R.id.tv_delete);
            switchEnabled = itemView.findViewById(R.id.switch_enabled);
        }
    }
}
