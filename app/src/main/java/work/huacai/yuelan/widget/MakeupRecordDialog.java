package work.huacai.yuelan.widget;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import java.util.Date;

import work.huacai.yuelan.R;
import work.huacai.yuelan.helper.CycleCalculator;
import work.huacai.yuelan.model.MenstrualCycle;

/**
 * 补签记录弹窗：四阶段卡片式选择 + 批量调整入口。
 * <p>
 * 日历页点击无记录日期、每日建议弹窗点击阶段标签时都使用本弹窗，
 * 保证两处入口的补签体验一致。
 * 卡片底色按阶段色动态生成（低透明度圆角块），点击卡片回调对应阶段；
 * 底部"批量调整连续日期"入口由调用方决定跳转到涂抹式范围编辑器。
 */
public final class MakeupRecordDialog {

    /** 补签选择回调 */
    public interface Callback {
        /**
         * 点击某个阶段卡片。
         *
         * @param cycle 选中的阶段
         */
        void onPhaseSelected(@NonNull MenstrualCycle cycle);

        /** 点击"批量调整连续日期"入口 */
        void onBatchEdit();
    }

    private MakeupRecordDialog() {
        // 工具类禁止实例化
    }

    /**
     * 弹出补签记录弹窗。
     *
     * @param context 上下文
     * @param dateId  补签日期（yyyyMMdd）
     * @param callback 选择回调（弹窗在回调前自动关闭）
     * @return 已显示的弹窗
     */
    public static Dialog show(@NonNull Context context, @NonNull String dateId,
                              @NonNull Callback callback) {
        Dialog dialog = new Dialog(context, R.style.Theme_Yuelan_Dialog);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_makeup_record);

        Date date = CycleCalculator.parseDateId(dateId);
        if (date != null) {
            ((TextView) dialog.findViewById(R.id.tv_makeup_date))
                    .setText(CycleCalculator.formatDateDisplay(date));
        }

        bindPhaseCard(context, dialog, R.id.card_menstrual, R.id.tv_menstrual_name,
                R.color.phase_menstrual, MenstrualCycle.MENSTRUAL_PERIOD, callback);
        bindPhaseCard(context, dialog, R.id.card_follicular, R.id.tv_follicular_name,
                R.color.phase_follicular, MenstrualCycle.FOLLICULAR_PHASE, callback);
        bindPhaseCard(context, dialog, R.id.card_ovulation, R.id.tv_ovulation_name,
                R.color.phase_ovulation, MenstrualCycle.OVULATION_PERIOD, callback);
        bindPhaseCard(context, dialog, R.id.card_luteal, R.id.tv_luteal_name,
                R.color.phase_luteal, MenstrualCycle.LUTE_PHASE, callback);

        dialog.findViewById(R.id.layout_batch_edit).setOnClickListener(v -> {
            dialog.dismiss();
            callback.onBatchEdit();
        });
        dialog.findViewById(R.id.btn_cancel).setOnClickListener(v -> dialog.dismiss());

        dialog.show();
        // 与其他品牌弹窗一致：透明窗口背景 + 88% 屏宽 + 0.5 遮罩
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setDimAmount(0.5f);
            int width = (int) (context.getResources().getDisplayMetrics().widthPixels * 0.88f);
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        return dialog;
    }

    /**
     * 绑定一张阶段卡片：阶段色标题 + 低透明阶段色圆角底，点击后回调并关闭弹窗。
     */
    private static void bindPhaseCard(Context context, Dialog dialog, int cardId, int nameId,
                                      int colorRes, MenstrualCycle cycle, Callback callback) {
        int color = ContextCompat.getColor(context, colorRes);
        float density = context.getResources().getDisplayMetrics().density;

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(density * 14);
        bg.setColor((color & 0x00FFFFFF) | 0x1F000000);
        bg.setStroke(Math.max(1, (int) density), (color & 0x00FFFFFF) | 0x4D000000);

        View card = dialog.findViewById(cardId);
        card.setBackground(bg);
        ((TextView) dialog.findViewById(nameId)).setTextColor(color);
        card.setOnClickListener(v -> {
            dialog.dismiss();
            callback.onPhaseSelected(cycle);
        });
    }
}
