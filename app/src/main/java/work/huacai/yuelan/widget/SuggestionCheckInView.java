package work.huacai.yuelan.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.checkbox.MaterialCheckBox;

import java.util.Date;

import work.huacai.yuelan.R;
import work.huacai.yuelan.dao.impl.MenstrualRecordDaoImpl;
import work.huacai.yuelan.helper.CycleCalculator;
import work.huacai.yuelan.helper.MicroHabitStore;
import work.huacai.yuelan.model.MenstrualCycle;
import work.huacai.yuelan.model.MenstrualRecord;

/**
 * 每日建议卡片底部的一键打卡区。
 * <p>
 * 包含心情（好 / 一般 / 低落）与睡眠、饮水两个微习惯，只在查看“今天”时展示：
 * <ul>
 *   <li>心情写入 {@code menstrual_record.mood}，不覆盖已有的流量、痛经与备注；</li>
 *   <li>微习惯按日期写入 SharedPreferences，不进入数据库与备份。</li>
 * </ul>
 * 数据变化后通过回调通知宿主重新生成建议，让“心情/打卡”标签的文案即时生效。
 */
public class SuggestionCheckInView extends LinearLayout {

    private final MenstrualRecordDaoImpl recordDao = new MenstrualRecordDaoImpl();

    private TextView tvMoodGood;
    private TextView tvMoodOkay;
    private TextView tvMoodLow;
    private MaterialCheckBox cbSleep;
    private MaterialCheckBox cbWater;

    /** 当前展示的日期与阶段，用于在无记录时创建一条最小实际记录 */
    private Date date;
    private MenstrualCycle phase;
    /** 数据变化后的回调，由宿主重新生成建议 */
    private Runnable onChanged;
    /** 程序化设置勾选状态时忽略监听，避免误判为用户操作 */
    private boolean binding;
    /** 当前心情等级 */
    private int currentMood = MenstrualRecord.MOOD_NONE;

    public SuggestionCheckInView(Context context) {
        super(context);
        init(context);
    }

    public SuggestionCheckInView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        setOrientation(VERTICAL);
        LayoutInflater.from(context).inflate(R.layout.view_suggestion_check_in, this, true);
        tvMoodGood = findViewById(R.id.tv_mood_good);
        tvMoodOkay = findViewById(R.id.tv_mood_okay);
        tvMoodLow = findViewById(R.id.tv_mood_low);
        cbSleep = findViewById(R.id.cb_habit_sleep);
        cbWater = findViewById(R.id.cb_habit_water);

        tvMoodGood.setOnClickListener(v -> onMoodClicked(MenstrualRecord.MOOD_GOOD));
        tvMoodOkay.setOnClickListener(v -> onMoodClicked(MenstrualRecord.MOOD_OKAY));
        tvMoodLow.setOnClickListener(v -> onMoodClicked(MenstrualRecord.MOOD_LOW));

        cbSleep.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (binding || date == null) {
                return;
            }
            MicroHabitStore.setSleepDone(getContext(),
                    CycleCalculator.formatDateId(date), isChecked);
            notifyChanged();
        });
        cbWater.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (binding || date == null) {
                return;
            }
            MicroHabitStore.setWaterDone(getContext(),
                    CycleCalculator.formatDateId(date), isChecked);
            notifyChanged();
        });
    }

    /**
     * 绑定当天数据。
     *
     * @param date      当前查看的日期
     * @param record    当天记录，可为 null
     * @param phase     当天展示阶段，无记录时用于创建最小实际记录
     * @param onChanged 数据变化后的回调，可为 null
     */
    public void bind(@Nullable Date date, @Nullable MenstrualRecord record,
                     @Nullable MenstrualCycle phase, @Nullable Runnable onChanged) {
        this.date = date;
        this.phase = phase;
        this.onChanged = onChanged;

        boolean today = date != null
                && CycleCalculator.formatDateId(date).equals(CycleCalculator.formatDateId(new Date()));
        setVisibility(today ? VISIBLE : GONE);
        if (!today) {
            return;
        }

        currentMood = record == null ? MenstrualRecord.MOOD_NONE : record.getMood();
        updateMoodSelection();

        String dateId = CycleCalculator.formatDateId(date);
        binding = true;
        cbSleep.setChecked(MicroHabitStore.isSleepDone(getContext(), dateId));
        cbWater.setChecked(MicroHabitStore.isWaterDone(getContext(), dateId));
        binding = false;
    }

    /**
     * 心情点击：已有记录只更新 mood 列；没有记录时用当天展示阶段创建一条实际记录。
     */
    private void onMoodClicked(int mood) {
        if (date == null) {
            return;
        }
        String dateId = CycleCalculator.formatDateId(date);
        MenstrualRecord existing = recordDao.selectById(dateId);
        if (existing != null) {
            if (existing.isPrediction()) {
                // 预测行不能只改 mood：它会在下次生成预测时被覆盖，
                // 因此把该行升级为实际记录，同时保留原阶段与其它字段
                existing.setPrediction(false);
                existing.setMood(mood);
                recordDao.updateRecord(existing);
            } else {
                recordDao.updateMood(dateId, mood);
            }
        } else {
            if (phase == null) {
                // 没有阶段就无法写入 menstrual_cycle，提示用户先记录阶段
                Toast.makeText(getContext(), R.string.daily_suggestion_mood_need_phase,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            MenstrualRecord record = new MenstrualRecord();
            record.setId(dateId);
            record.setMenstrualCycle(phase);
            record.setPrediction(false);
            record.setMood(mood);
            if (recordDao.insert(record) < 0) {
                // 并发场景下可能已被其它入口插入，改为只更新心情
                recordDao.updateMood(dateId, mood);
            }
        }

        currentMood = mood;
        updateMoodSelection();
        Toast.makeText(getContext(), R.string.daily_suggestion_mood_saved, Toast.LENGTH_SHORT).show();
        notifyChanged();
    }

    /**
     * 刷新三个心情按钮的选中态：选中项用强调色，其余保持正文色。
     */
    private void updateMoodSelection() {
        int accent = ContextCompat.getColor(getContext(), R.color.brand_primary);
        int normal = ContextCompat.getColor(getContext(), R.color.text_body);
        tvMoodGood.setTextColor(currentMood == MenstrualRecord.MOOD_GOOD ? accent : normal);
        tvMoodOkay.setTextColor(currentMood == MenstrualRecord.MOOD_OKAY ? accent : normal);
        tvMoodLow.setTextColor(currentMood == MenstrualRecord.MOOD_LOW ? accent : normal);
    }

    private void notifyChanged() {
        if (onChanged != null) {
            onChanged.run();
        }
    }
}
