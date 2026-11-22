package work.huacai.yuelan.widget;

import android.text.Layout;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import work.huacai.yuelan.R;

/**
 * 长文本折叠控制器。
 * <p>
 * 默认只展示前 {@code collapsedLines} 行，超长时在正文下方显示“展开全文”，
 * 展开后显示“收起”。用于每日建议这类可能超过三行、又不想让弹窗被撑得过长的正文。
 * <p>
 * 判定是否超长用的是 TextView 实际排版后的省略号：只有最后一行真的被省略时才显示按钮，
 * 因此短建议不会出现多余的“展开全文”。
 */
public final class CollapsibleText {

    /** 默认收起行数：建议卡片首屏只保留 3 行正文，避免长建议把弹窗撑长、盖住下面的宜忌与打卡区 */
    public static final int DEFAULT_COLLAPSED_LINES = 3;

    /** 正文（被折叠的 TextView） */
    private final TextView content;
    /** 展开/收起按钮 */
    private final TextView toggle;
    /** 收起状态最多显示的行数 */
    private final int collapsedLines;

    /** 当前是否已展开 */
    private boolean expanded;

    /**
     * 使用默认收起行数绑定折叠行为。
     *
     * @param content 正文 TextView
     * @param toggle  展开/收起按钮
     */
    public CollapsibleText(@NonNull TextView content, @NonNull TextView toggle) {
        this(content, toggle, DEFAULT_COLLAPSED_LINES);
    }

    /**
     * 绑定折叠行为。
     *
     * @param content        正文 TextView
     * @param toggle         展开/收起按钮，点击后切换状态
     * @param collapsedLines 收起时最多显示的行数（至少 1）
     */
    public CollapsibleText(@NonNull TextView content, @NonNull TextView toggle, int collapsedLines) {
        this.content = content;
        this.toggle = toggle;
        this.collapsedLines = Math.max(1, collapsedLines);

        this.toggle.setText(R.string.daily_suggestion_expand);
        this.toggle.setOnClickListener(v -> setExpanded(!expanded));
        this.toggle.setVisibility(View.GONE);

        this.content.setMaxLines(this.collapsedLines);
        this.content.setEllipsize(TextUtils.TruncateAt.END);
        // 宽度/行数确定后才能判断是否被省略，布局变化时重新评估
        this.content.addOnLayoutChangeListener(
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                        updateToggle());

        applyCollapsedState();
    }

    /**
     * 设置正文。内容变化时自动回到收起状态；内容没变（如天气到达后重渲染同一段文字）则保留展开状态。
     *
     * @param text 正文，可为空
     */
    public void setText(@Nullable CharSequence text) {
        boolean sameText = TextUtils.equals(content.getText(), text);
        content.setText(text);
        if (!(sameText && expanded)) {
            expanded = false;
            applyCollapsedState();
        }
        // setText 会触发重新布局，布局完成后再判断按钮是否出现
        checkAfterLayout();
    }

    /** 布局宽度变化后重新判断是否需要展示“展开全文”。 */
    public void refresh() {
        checkAfterLayout();
    }

    /**
     * 切换展开 / 收起。
     *
     * @param expand true 展开，false 收起
     */
    private void setExpanded(boolean expand) {
        expanded = expand;
        if (expand) {
            content.setMaxLines(Integer.MAX_VALUE);
            content.setEllipsize(null);
            toggle.setText(R.string.daily_suggestion_collapse);
            toggle.setVisibility(View.VISIBLE);
        } else {
            applyCollapsedState();
        }
        checkAfterLayout();
    }

    /** 应用“收起”状态的排版参数与按钮文案。 */
    private void applyCollapsedState() {
        content.setMaxLines(collapsedLines);
        content.setEllipsize(TextUtils.TruncateAt.END);
        toggle.setText(R.string.daily_suggestion_expand);
    }

    /**
     * 等下一次排版完成后再判断按钮显隐。
     * <p>
     * 不能只依赖 {@code post} 或布局变化回调：{@code maxLines} 固定了 TextView 高度，
     * 文字变长时控件本身尺寸可能不变，布局回调不会触发；而 {@code post} 又可能早于排版。
     * 这里用一次性的 pre-draw 回调，保证拿到的是最新一次排版后的 Layout。
     */
    private void checkAfterLayout() {
        content.post(() -> {
            ViewTreeObserver observer = content.getViewTreeObserver();
            if (!observer.isAlive()) {
                updateToggle();
                return;
            }
            observer.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    ViewTreeObserver current = content.getViewTreeObserver();
                    if (current.isAlive()) {
                        current.removeOnPreDrawListener(this);
                    }
                    updateToggle();
                    return true;
                }
            });
        });
    }

    /** 根据实际排版结果决定按钮显示 / 隐藏。 */
    private void updateToggle() {
        if (expanded) {
            toggle.setVisibility(View.VISIBLE);
            return;
        }
        Layout layout = content.getLayout();
        if (layout == null) {
            return;
        }
        int lastVisibleLine = Math.min(collapsedLines - 1, layout.getLineCount() - 1);
        boolean truncated = lastVisibleLine >= 0
                && layout.getEllipsisCount(lastVisibleLine) > 0;
        toggle.setVisibility(truncated ? View.VISIBLE : View.GONE);
    }
}
