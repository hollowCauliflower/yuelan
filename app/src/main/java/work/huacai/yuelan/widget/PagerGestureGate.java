package work.huacai.yuelan.widget;

import android.app.Activity;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

/**
 * ViewPager2 手势方向仲裁器（触摸代理）。
 * <p>
 * 解决的问题：页面内存在可纵向滚动的控件（ScrollView、图表、列表等），
 * 手指起点落在这些控件上做大幅纵向滑动时，ViewPager2 会先按自身触摸阈值
 * 把对角位移判定为横向翻页，导致“想上下滚动却翻了页”。
 * <p>
 * 处理思路：在 Activity 的 {@code dispatchTouchEvent} 中先于 ViewPager2 看到事件，
 * 每个手势只在首次超过触摸阈值时按 dx/dy 判定一次主方向：
 * <ul>
 *     <li>纵向占优：对 ViewPager2 内部的 RecyclerView 调用
 *     {@code requestDisallowInterceptTouchEvent(true)}，把事件完整留给页面内的滚动控件；</li>
 *     <li>横向占优：不做任何拦截，ViewPager2 按原有逻辑正常翻页。</li>
 * </ul>
 * 手势结束（抬手、取消、主指针抬起）后统一复位拦截标记，避免状态遗留到下一次手势。
 * <p>
 * 统一在 Activity 层接入，四个主页面（首页/日历/统计/设置）与引导页共用同一份逻辑，
 * 无需在每个页面布局里重复包裹容器。
 */
public final class PagerGestureGate {

    /** 触发方向判定的最小位移（系统触摸阈值，单位 px） */
    private final int touchSlop;

    /** 需要设置/清除拦截标记的目标，即 ViewPager2 内部的 RecyclerView */
    private final ViewGroup interceptTarget;

    /** 本次手势的按下点是否落在 ViewPager2 区域内 */
    private boolean gestureInPager;

    /** 本次手势的主方向是否已经判定完成（一个手势只判定一次） */
    private boolean directionDecided;

    /** 主指针 id，用于多指手势时跟踪第一根手指 */
    private int activePointerId = MotionEvent.INVALID_POINTER_ID;

    /** 按下点的坐标，用于计算 dx/dy */
    private float initialX;
    private float initialY;

    private PagerGestureGate(ViewPager2 viewPager, int touchSlop) {
        this.touchSlop = touchSlop;
        this.interceptTarget = resolveInterceptTarget(viewPager);
    }

    /**
     * 创建仲裁器，供 Activity 在 {@code dispatchTouchEvent} 中调用 {@link #onTouchEvent(MotionEvent)}。
     *
     * @param activity 使用该 ViewPager2 的 Activity，用于读取系统触摸阈值
     * @param viewPager 需要参与方向仲裁的 ViewPager2
     * @return 已绑定该 ViewPager2 的仲裁器实例
     */
    @NonNull
    public static PagerGestureGate attach(@NonNull Activity activity, @NonNull ViewPager2 viewPager) {
        return new PagerGestureGate(viewPager, ViewConfiguration.get(activity).getScaledTouchSlop());
    }

    /**
     * 在 Activity 的 {@code dispatchTouchEvent} 中调用，负责判定方向并设置/清除拦截标记。
     * 该方法只观察事件，不消费事件。
     *
     * @param event 当前触摸事件
     */
    public void onTouchEvent(@NonNull MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // 新手势开始：记录按下点，并先复位上一次手势可能遗留的拦截状态
                gestureInPager = isDownInsidePager(event);
                directionDecided = false;
                activePointerId = event.getPointerId(0);
                initialX = event.getX();
                initialY = event.getY();
                releasePagerIntercept();
                break;
            case MotionEvent.ACTION_MOVE:
                judgeDirection(event);
                break;
            case MotionEvent.ACTION_POINTER_UP:
                // 主指针抬起（例如双指缩放结束）：立即结束本次方向判定并复位
                if (event.getPointerId(event.getActionIndex()) == activePointerId) {
                    finishGesture();
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                finishGesture();
                break;
            default:
                break;
        }
    }

    /**
     * 根据位移判定主方向：纵向占优时阻断 ViewPager2 横向拦截，横向占优时保持翻页能力。
     * 同一手势只判定一次，避免手指抖动导致拦截状态来回切换。
     *
     * @param event 当前 MOVE 事件
     */
    private void judgeDirection(MotionEvent event) {
        if (!gestureInPager || directionDecided) {
            return;
        }
        int pointerIndex = event.findPointerIndex(activePointerId);
        if (pointerIndex < 0) {
            return;
        }
        float dx = Math.abs(event.getX(pointerIndex) - initialX);
        float dy = Math.abs(event.getY(pointerIndex) - initialY);
        if (dx < touchSlop && dy < touchSlop) {
            // 位移尚未超过阈值，方向不明确，继续观察
            return;
        }
        if (dy > dx) {
            // 纵向占优：拦截标记打在 ViewPager2 内部的 RecyclerView 上，
            // 该标记会沿父链向上传播，ViewPager2 本次手势内不再拦截事件
            interceptTarget.requestDisallowInterceptTouchEvent(true);
        }
        // 横向占优或位移刚好相等时不做拦截，事件继续交给 ViewPager2 正常处理
        directionDecided = true;
    }

    /**
     * 结束当前手势：复位方向判定状态和主指针，并清除 ViewPager2 的拦截标记。
     */
    private void finishGesture() {
        gestureInPager = false;
        directionDecided = false;
        activePointerId = MotionEvent.INVALID_POINTER_ID;
        releasePagerIntercept();
    }

    /**
     * 清除 ViewPager2 的事件拦截限制，恢复其横向翻页能力。
     */
    private void releasePagerIntercept() {
        interceptTarget.requestDisallowInterceptTouchEvent(false);
    }

    /**
     * 判断按下点是否落在 ViewPager2 的可见区域内。
     * 落在底部导航栏等区域的手势不参与方向判定。
     *
     * @param event ACTION_DOWN 事件
     * @return true 表示按下点在 ViewPager2 内
     */
    private boolean isDownInsidePager(MotionEvent event) {
        // interceptTarget 是 ViewPager2 的子 View，其区域与页面区域一致
        if (!interceptTarget.isShown()) {
            return false;
        }
        int[] location = new int[2];
        interceptTarget.getLocationOnScreen(location);
        Rect pagerRect = new Rect(
                location[0],
                location[1],
                location[0] + interceptTarget.getWidth(),
                location[1] + interceptTarget.getHeight());
        return pagerRect.contains((int) event.getRawX(), (int) event.getRawY());
    }

    /**
     * 取得拦截标记作用的目标：ViewPager2 内部承载页面滑动与拦截的 RecyclerView。
     * 直接在子 View 上设置标记会沿父链向上传播，才能阻止 ViewPager2 自身拦截；
     * 若结构异常则退化为 ViewPager2 本身。
     *
     * @param viewPager 目标 ViewPager2
     * @return 用于设置拦截标记的 View
     */
    private static ViewGroup resolveInterceptTarget(ViewPager2 viewPager) {
        for (int i = 0; i < viewPager.getChildCount(); i++) {
            View child = viewPager.getChildAt(i);
            if (child instanceof RecyclerView) {
                return (ViewGroup) child;
            }
        }
        return viewPager;
    }
}
