package work.huacai.yuelan.widget;

import android.app.Dialog;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.function.IntConsumer;

import work.huacai.yuelan.R;

/**
 * 品牌化弹窗工厂。
 * <p>
 * 统一提供确认、列表、单选、长文本与表单五类弹窗，布局全部基于
 * {@code dialog_background}（24dp 圆角 + 语义表面色），窗口统一为
 * 88% 屏宽、透明背景、0.5 遮罩，深浅色随语义色 token 自动切换。
 * <p>
 * 使用本工厂后不再需要直接构造 {@code AlertDialog}，避免系统默认样式混入。
 */
public final class AppDialogFactory {

    /** 弹窗宽度占屏幕宽度的比例，与每日建议居中弹窗保持一致 */
    private static final float WIDTH_RATIO = 0.88f;
    /** 长文本弹窗正文区域的最大高度占屏幕高度的比例 */
    private static final float LONG_TEXT_HEIGHT_RATIO = 0.45f;
    /** 表单弹窗内容区域的最大高度占屏幕高度的比例，超出后可滚动，保证底部按钮不被挤出屏幕 */
    private static final float FORM_HEIGHT_RATIO = 0.55f;
    /** positiveTextRes 传 0 表示不显示按钮 */
    private static final int NO_TEXT = 0;

    private AppDialogFactory() {
        // 工具类禁止实例化
    }

    /**
     * 表单提交回调：由调用方决定何时关闭弹窗，便于校验失败时保持弹窗打开。
     */
    public interface OnFormSubmitListener {
        /**
         * 点击主按钮时回调。
         *
         * @param dialog 当前弹窗，校验通过后由调用方调用 {@link Dialog#dismiss()}
         */
        void onSubmit(@NonNull Dialog dialog);
    }

    /**
     * 二次确认弹窗（标题 + 正文 + 主/次按钮）。
     *
     * @param context         上下文
     * @param title           标题
     * @param message         正文，可为空
     * @param positiveTextRes 主按钮文案，必填
     * @param negativeTextRes 次按钮文案，传 0 表示不显示次按钮
     * @param onConfirm       主按钮回调，弹窗会先关闭再回调
     * @return 已显示的弹窗
     */
    public static Dialog showConfirm(@NonNull Context context,
                                     @NonNull CharSequence title,
                                     @Nullable CharSequence message,
                                     int positiveTextRes,
                                     int negativeTextRes,
                                     @Nullable Runnable onConfirm) {
        Dialog dialog = createDialog(context, R.layout.dialog_brand_shell);
        bindTitle(dialog, title);
        bindMessage(dialog, message);
        dialog.findViewById(R.id.content_container).setVisibility(View.GONE);

        TextView btnPositive = dialog.findViewById(R.id.btn_positive);
        TextView btnNegative = dialog.findViewById(R.id.btn_negative);
        btnPositive.setText(positiveTextRes);

        if (negativeTextRes == NO_TEXT) {
            // 只有一个按钮时让主按钮占满整行
            btnNegative.setVisibility(View.GONE);
            LinearLayout.LayoutParams params =
                    (LinearLayout.LayoutParams) btnPositive.getLayoutParams();
            params.setMarginStart(0);
            btnPositive.setLayoutParams(params);
        } else {
            btnNegative.setText(negativeTextRes);
            btnNegative.setOnClickListener(v -> dialog.dismiss());
        }

        btnPositive.setOnClickListener(v -> {
            dialog.dismiss();
            if (onConfirm != null) {
                onConfirm.run();
            }
        });

        show(dialog, context);
        return dialog;
    }

    /**
     * 普通列表弹窗（点击某项即关闭，不显示单选圆点）。
     *
     * @param context        上下文
     * @param title          标题
     * @param items          列表项
     * @param onItemSelected 选中回调，参数为下标
     * @return 已显示的弹窗
     */
    public static Dialog showList(@NonNull Context context,
                                  @NonNull CharSequence title,
                                  @NonNull CharSequence[] items,
                                  @Nullable IntConsumer onItemSelected) {
        return showOptions(context, title, items, -1, false, onItemSelected, null);
    }

    /**
     * 单选列表弹窗（显示品牌粉单选圆点，点击某项即关闭）。
     *
     * @param context      上下文
     * @param title        标题
     * @param items        选项文案
     * @param checkedIndex 当前选中下标，传 -1 表示无选中
     * @param onSelected   选中回调，参数为下标
     * @return 已显示的弹窗
     */
    public static Dialog showSingleChoice(@NonNull Context context,
                                          @NonNull CharSequence title,
                                          @NonNull CharSequence[] items,
                                          int checkedIndex,
                                          @Nullable IntConsumer onSelected) {
        return showOptions(context, title, items, checkedIndex, true, onSelected, null);
    }

    /**
     * 支持长按的单选列表弹窗。
     *
     * @param onItemLongClick 长按回调，参数为下标；为 null 时不响应长按
     * @return 已显示的弹窗
     */
    public static Dialog showSingleChoice(@NonNull Context context,
                                          @NonNull CharSequence title,
                                          @NonNull CharSequence[] items,
                                          int checkedIndex,
                                          @Nullable IntConsumer onSelected,
                                          @Nullable IntConsumer onItemLongClick) {
        return showOptions(context, title, items, checkedIndex, true, onSelected, onItemLongClick);
    }

    /**
     * 长文本弹窗（隐私政策 / 用户协议），正文超过屏幕高度时可滚动。
     *
     * @param context         上下文
     * @param title           标题
     * @param content         正文
     * @param positiveTextRes 主按钮文案
     * @return 已显示的弹窗
     */
    public static Dialog showLongText(@NonNull Context context,
                                      @NonNull CharSequence title,
                                      @Nullable CharSequence content,
                                      int positiveTextRes) {
        Dialog dialog = createDialog(context, R.layout.dialog_brand_long_text);
        bindTitle(dialog, title);

        TextView tvContent = dialog.findViewById(R.id.tv_content);
        tvContent.setText(content != null ? content : "");

        TextView btnPositive = dialog.findViewById(R.id.btn_positive);
        btnPositive.setText(positiveTextRes);
        btnPositive.setOnClickListener(v -> dialog.dismiss());

        show(dialog, context);

        // 正文高度超过屏幕 45% 时固定高度，保证 ScrollView 真正可滚动而不是被窗口裁切
        ScrollView scroll = dialog.findViewById(R.id.scroll_content);
        int maxHeight = (int) (context.getResources().getDisplayMetrics().heightPixels
                * LONG_TEXT_HEIGHT_RATIO);
        scroll.post(() -> {
            if (scroll.getHeight() > maxHeight) {
                ViewGroup.LayoutParams params = scroll.getLayoutParams();
                params.height = maxHeight;
                scroll.setLayoutParams(params);
            }
        });
        return dialog;
    }

    /**
     * 表单弹窗（自定义内容 + 主/次按钮），保存校验失败时可保持弹窗打开。
     *
     * @param context         上下文
     * @param title           标题
     * @param content         自定义内容视图（不能已有父容器）
     * @param positiveTextRes 主按钮文案
     * @param negativeTextRes 次按钮文案
     * @param listener        主按钮回调
     * @return 已显示的弹窗
     */
    public static Dialog showForm(@NonNull Context context,
                                  @NonNull CharSequence title,
                                  @NonNull View content,
                                  int positiveTextRes,
                                  int negativeTextRes,
                                  @NonNull OnFormSubmitListener listener) {
        Dialog dialog = createDialog(context, R.layout.dialog_brand_shell);
        bindTitle(dialog, title);
        dialog.findViewById(R.id.tv_message).setVisibility(View.GONE);

        FrameLayout container = dialog.findViewById(R.id.content_container);
        container.setVisibility(View.VISIBLE);
        // 内容可能很高（如提醒编辑的周期节点选项），包一层 ScrollView 保证超高时可滚动
        ScrollView scrollView = new ScrollView(context);
        scrollView.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        container.addView(scrollView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView btnPositive = dialog.findViewById(R.id.btn_positive);
        TextView btnNegative = dialog.findViewById(R.id.btn_negative);
        btnPositive.setText(positiveTextRes);
        btnNegative.setText(negativeTextRes);
        btnPositive.setOnClickListener(v -> listener.onSubmit(dialog));
        btnNegative.setOnClickListener(v -> dialog.dismiss());

        show(dialog, context);

        // 内容区超过屏幕 55% 时固定高度，让 ScrollView 真正滚动而不是把按钮挤出屏幕
        int maxHeight = (int) (context.getResources().getDisplayMetrics().heightPixels
                * FORM_HEIGHT_RATIO);
        scrollView.post(() -> {
            if (scrollView.getHeight() > maxHeight) {
                ViewGroup.LayoutParams params = scrollView.getLayoutParams();
                params.height = maxHeight;
                scrollView.setLayoutParams(params);
            }
        });
        return dialog;
    }

    /**
     * 为底部弹窗补齐品牌规范：透明窗口背景、导航栏配色与底部系统栏内边距。
     * <p>
     * 适用于直接 new 出来的 {@code BottomSheetDialog}，逻辑与两个 BottomSheetDialogFragment 保持一致。
     *
     * @param dialog 底部弹窗
     */
    public static void applyBottomSheetWindowStyle(@NonNull Dialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) {
            return;
        }

        Context context = dialog.getContext();
        // 去掉默认白色圆角背景，改由布局自身绘制圆角
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setNavigationBarColor(ContextCompat.getColor(context, R.color.card_surface));
        WindowCompat.getInsetsController(window, window.getDecorView())
                .setAppearanceLightNavigationBars(!isNightMode(context));

        // 手势导航模式下导航栏强制透明，让弹窗背景延伸到屏幕底部
        View bottomSheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (bottomSheet != null) {
            ViewCompat.setOnApplyWindowInsetsListener(bottomSheet, (v, insets) -> {
                int bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
                v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), bottom);
                return WindowInsetsCompat.CONSUMED;
            });
        }
        WindowCompat.setDecorFitsSystemWindows(window, false);
    }

    /**
     * 生成统一窗口配置的弹窗实例。
     */
    private static Dialog createDialog(@NonNull Context context, int layoutRes) {
        Dialog dialog = new Dialog(context, R.style.Theme_Yuelan_Dialog);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(layoutRes);
        return dialog;
    }

    /**
     * 列表 / 单选弹窗的共用实现。
     *
     * @param checkedIndex    单选时的选中下标；showRadio 为 false 时忽略
     * @param showRadio       是否显示单选圆点
     * @param onItemLongClick 长按回调，为 null 时不响应长按
     */
    private static Dialog showOptions(@NonNull Context context,
                                      @NonNull CharSequence title,
                                      @NonNull CharSequence[] items,
                                      int checkedIndex,
                                      boolean showRadio,
                                      @Nullable IntConsumer onSelected,
                                      @Nullable IntConsumer onItemLongClick) {
        Dialog dialog = createDialog(context, R.layout.dialog_brand_list);
        bindTitle(dialog, title);

        LinearLayout container = dialog.findViewById(R.id.list_container);
        LayoutInflater inflater = LayoutInflater.from(context);
        for (int i = 0; i < items.length; i++) {
            View row = inflater.inflate(R.layout.item_brand_dialog_option, container, false);
            RadioButton radio = row.findViewById(R.id.rb_option);
            TextView tvOption = row.findViewById(R.id.tv_option);
            tvOption.setText(items[i]);

            if (showRadio) {
                radio.setChecked(i == checkedIndex);
            } else {
                radio.setVisibility(View.GONE);
            }

            final int index = i;
            row.setOnClickListener(v -> {
                dialog.dismiss();
                if (onSelected != null) {
                    onSelected.accept(index);
                }
            });
            if (onItemLongClick != null) {
                row.setOnLongClickListener(v -> {
                    onItemLongClick.accept(index);
                    return true;
                });
            }
            container.addView(row);
        }

        TextView btnCancel = dialog.findViewById(R.id.btn_cancel);
        btnCancel.setText(R.string.dialog_cancel);
        btnCancel.setOnClickListener(v -> dialog.dismiss());

        show(dialog, context);
        return dialog;
    }

    /**
     * 绑定标题，标题为空时隐藏整行。
     */
    private static void bindTitle(@NonNull Dialog dialog, @NonNull CharSequence title) {
        TextView tvTitle = dialog.findViewById(R.id.tv_title);
        tvTitle.setText(title);
        tvTitle.setVisibility(TextUtils.isEmpty(title) ? View.GONE : View.VISIBLE);
    }

    /**
     * 设置列表弹窗的副标题（如操作提示），为空时隐藏。
     * <p>
     * 需在弹窗 {@code show} 之后调用，适用于 {@link #showSingleChoice} 等返回的列表弹窗。
     */
    public static void setSubtitle(@NonNull Dialog dialog, @Nullable CharSequence subtitle) {
        TextView tvSubtitle = dialog.findViewById(R.id.tv_subtitle);
        if (tvSubtitle == null) {
            return;
        }
        if (TextUtils.isEmpty(subtitle)) {
            tvSubtitle.setVisibility(View.GONE);
        } else {
            tvSubtitle.setText(subtitle);
            tvSubtitle.setVisibility(View.VISIBLE);
        }
    }

    /**
     * 绑定正文，正文为空时隐藏。
     */
    private static void bindMessage(@NonNull Dialog dialog, @Nullable CharSequence message) {
        TextView tvMessage = dialog.findViewById(R.id.tv_message);
        if (TextUtils.isEmpty(message)) {
            tvMessage.setVisibility(View.GONE);
        } else {
            tvMessage.setVisibility(View.VISIBLE);
            tvMessage.setText(message);
        }
    }

    /**
     * 统一显示弹窗：透明窗口背景 + 88% 屏宽 + 0.5 遮罩。
     */
    private static void show(@NonNull Dialog dialog, @NonNull Context context) {
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setDimAmount(0.5f);
        }
        dialog.show();
        if (window != null) {
            int width = (int) (context.getResources().getDisplayMetrics().widthPixels * WIDTH_RATIO);
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    /**
     * 当前是否深色模式，用于决定导航栏图标明暗。
     */
    private static boolean isNightMode(@NonNull Context context) {
        return (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }
}
