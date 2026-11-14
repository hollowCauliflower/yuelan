package work.huacai.yuelan.fragments;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import work.huacai.yuelan.R;
import work.huacai.yuelan.widget.LoadingProgressRingView;

/**
 * 引导流程的第三页（加载页）。
 * <p>
 * 展示进度环、百分比进度和轮播的加载文案；
 * 进度由 {@code OnboardingActivity} 通过 {@link #updateProgress(int)} 驱动。
 */
public class LoadingFragment extends Fragment {

    /** 每段加载文案的展示时长（毫秒） */
    private static final long TEXT_SWITCH_INTERVAL = 1000L;
    /** 中心 Logo 呼吸周期（毫秒） */
    private static final long LOGO_BREATH_DURATION = 3000L;

    private LoadingProgressRingView ringProgress;
    private ImageView ivLoadingLogo;
    private TextView tvProgress;
    private TextView tvProgressText;
    /** 主线程 Handler，用于延迟切换加载文案 */
    private final Handler handler = new Handler();
    private AnimatorSet logoBreathAnimator;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_loading, container, false);

        initViews(view);
        startLoadingAnimation();

        return view;
    }

    /**
     * 绑定控件。
     *
     * @param view 加载页根视图
     */
    private void initViews(View view) {
        ringProgress = view.findViewById(R.id.ringProgress);
        ivLoadingLogo = view.findViewById(R.id.ivLoadingLogo);
        tvProgress = view.findViewById(R.id.tvProgress);
        tvProgressText = view.findViewById(R.id.tvProgressText);
    }

    /**
     * 启动进度环光晕、Logo 呼吸以及加载文案轮播。
     */
    private void startLoadingAnimation() {
        ringProgress.startGlowAnimation();
        startLogoBreath();
        animateLoadingText();
    }

    /**
     * 中心 Logo 轻微缩放 + 透明度起伏，形成缓慢的呼吸感。
     */
    private void startLogoBreath() {
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(ivLoadingLogo, "scaleX", 0.96f, 1.04f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(ivLoadingLogo, "scaleY", 0.96f, 1.04f);
        ObjectAnimator alpha = ObjectAnimator.ofFloat(ivLoadingLogo, "alpha", 0.85f, 1f);
        ObjectAnimator floatY = ObjectAnimator.ofFloat(ivLoadingLogo, "translationY", 0f, -4f);

        for (ObjectAnimator animator : new ObjectAnimator[]{scaleX, scaleY, alpha, floatY}) {
            animator.setDuration(LOGO_BREATH_DURATION);
            animator.setRepeatCount(ObjectAnimator.INFINITE);
            animator.setRepeatMode(ObjectAnimator.REVERSE);
            animator.setInterpolator(new AccelerateDecelerateInterpolator());
        }

        logoBreathAnimator = new AnimatorSet();
        logoBreathAnimator.playTogether(scaleX, scaleY, alpha, floatY);
        logoBreathAnimator.start();
    }

    /**
     * 按固定间隔依次淡入展示资源数组中的加载文案，全部展示完后停止。
     */
    private void animateLoadingText() {
        final int[] currentIndex = {0};

        // 使用资源数组
        final String[] loadingTexts = getResources().getStringArray(R.array.loading_messages);

        Runnable textAnimator = new Runnable() {
            @Override
            public void run() {
                if (currentIndex[0] < loadingTexts.length) {
                    // 淡入新文字
                    tvProgressText.setAlpha(0f);
                    tvProgressText.setText(loadingTexts[currentIndex[0]]);
                    tvProgressText.animate()
                            .alpha(1f)
                            .setDuration(500)
                            .start();

                    currentIndex[0]++;

                    handler.postDelayed(this, TEXT_SWITCH_INTERVAL);
                }
            }
        };

        // 第一次延迟500ms开始
        handler.postDelayed(textAnimator, 500);
    }

    /**
     * 更新百分比进度；每走到 25% 的整数倍时播放一次放大回弹动画。
     *
     * @param progress 0~100 的进度值
     */
    public void updateProgress(int progress) {
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                // 进度环平滑过渡到新进度
                ringProgress.setProgress(progress);

                // 使用带占位符的资源字符串
                String progressText = getString(R.string.progress_percentage, progress);
                tvProgress.setText(progressText);

                // 进度文字缩放动画：每 25% 强调一次
                if (progress % 25 == 0) {
                    tvProgress.animate()
                            .scaleX(1.3f)
                            .scaleY(1.3f)
                            .setDuration(200)
                            .withEndAction(() -> tvProgress.animate()
                                    .scaleX(1f)
                                    .scaleY(1f)
                                    .setDuration(200)
                                    .start())
                            .start();
                }
            });
        }
    }

    /**
     * 页面回到前台时恢复呼吸动画。
     */
    @Override
    public void onResume() {
        super.onResume();
        ringProgress.resumeGlowAnimation();
        if (logoBreathAnimator != null && logoBreathAnimator.isPaused()) {
            logoBreathAnimator.resume();
        }
    }

    /**
     * 页面进入后台时暂停动画，节省资源。
     */
    @Override
    public void onPause() {
        super.onPause();
        ringProgress.pauseGlowAnimation();
        if (logoBreathAnimator != null && logoBreathAnimator.isStarted()) {
            logoBreathAnimator.pause();
        }
    }

    /**
     * 视图销毁时清理 Handler 任务并取消动画，避免内存泄漏。
     */
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // 清理Handler任务
        handler.removeCallbacksAndMessages(null);
        if (logoBreathAnimator != null) {
            logoBreathAnimator.cancel();
            logoBreathAnimator = null;
        }
        ivLoadingLogo.animate().cancel();
        ivLoadingLogo.setTranslationY(0f);
    }
}
