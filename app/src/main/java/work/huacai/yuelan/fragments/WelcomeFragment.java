package work.huacai.yuelan.fragments;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Outline;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.BounceInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import java.util.ArrayList;
import java.util.List;

import work.huacai.yuelan.R;
import work.huacai.yuelan.helper.AppIconHelper;
import work.huacai.yuelan.helper.ReminderNotificationHelper;

/**
 * 引导流程的第一页（欢迎页）。
 * <p>
 * 展示 Logo、标题、副标题和描述文字，并串联播放一串入场动画：
 * Logo 弹跳 -> 光环呼吸/涟漪扩散 + Logo 浮动 -> 标题淡入 -> 副标题打字机 -> 描述滑入。
 * 另外在页面展示时申请定位权限（用于天气/周边服务）。
 */
public class WelcomeFragment extends Fragment {
    /** 权限请求码（定位 + 通知） */
    private static final int PERMISSIONS_REQUEST = 100;
    /** 与其他页面共用的应用配置（含权限是否已申请过的标记） */
    private static final String PREF_CONFIG = "menstruation_config";
    /** 定位权限是否已申请过：与首页共用同一个标记，避免引导页问完首页又问一次 */
    private static final String KEY_ONBOARDING_PERMISSION_ASKED = "location_permission_asked";

    private ImageView ivLogo;
    private View outerRing;
    /** 向外扩散的涟漪光圈 */
    private View rippleRing;
    private TextView tvTitle, tvSubtitle, tvDescription;
    /** 主线程 Handler，用于串联各段动画的延迟执行 */
    private final Handler handler = new Handler();


    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_welcome, container, false);

        initViews(view);

        // 延迟一点开始动画，确保视图已经加载
        handler.postDelayed(this::startSequentialAnimations, 500);

        return view;
    }

    /**
     * 绑定控件并设置动画前的初始状态（隐藏 + Logo 圆形裁剪）。
     *
     * @param view 欢迎页根视图
     */
    private void initViews(View view) {
        ivLogo = view.findViewById(R.id.ivLogo);
        outerRing = view.findViewById(R.id.outerRing);
        rippleRing = view.findViewById(R.id.rippleRing);
        tvTitle = view.findViewById(R.id.tvTitle);
        tvSubtitle = view.findViewById(R.id.tvSubtitle);
        tvDescription = view.findViewById(R.id.tvDescription);

        // 启动页图标跟随用户在设置页选择的图标（自定义图片优先，否则为预设风格）
        AppIconHelper.loadAppIcon(requireContext(), ivLogo);

        // 初始状态：隐藏所有需要动画的元素
        ivLogo.setAlpha(0f);
        ivLogo.setScaleX(0f);
        ivLogo.setScaleY(0f);
        tvTitle.setAlpha(0f);
        tvSubtitle.setAlpha(0f);
        tvDescription.setAlpha(0f);

        // 设置圆形裁剪
        setCircleClip(ivLogo);
    }

    /**
     * 把视图裁剪成圆形（宽度/高度取较小值的一半作为圆角半径）。
     *
     * @param view 需要圆形裁剪的视图
     */
    private void setCircleClip(View view) {
        view.setClipToOutline(true);
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                int size = Math.min(view.getWidth(), view.getHeight());
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), size / 2f);
            }
        });
    }

    /**
     * 页面可见时：补设圆形裁剪（此时视图尺寸才确定），并在未授权时申请定位权限。
     */
    @Override
    public void onResume() {
        super.onResume();
        // 确保视图尺寸确定后设置圆形
        if (ivLogo.getWidth() > 0 && ivLogo.getHeight() > 0) {
            setCircleClip(ivLogo);
        }

        // 在引导页一次性申请所需权限（结果由系统回调处理，此处不处理拒绝逻辑）：
        // 1) 定位权限：用于结合天气生成每日建议；
        // 2) 通知权限（Android 13+）：经期提醒默认开启，若拿不到通知权限，到点通知会被系统静默丢弃。
        List<String> missingPermissions = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            missingPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && !ReminderNotificationHelper.canPostNotifications(requireContext())) {
            missingPermissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!missingPermissions.isEmpty()) {
            // 只申请一次：权限被永久拒绝时系统会立即回调，onResume 再次申请就会和
            // 授权页形成死循环（页面不停闪烁，最终整个引导页被系统销毁，看起来像闪退）
            SharedPreferences prefs = requireContext()
                    .getSharedPreferences(PREF_CONFIG, Context.MODE_PRIVATE);
            if (!prefs.getBoolean(KEY_ONBOARDING_PERMISSION_ASKED, false)) {
                prefs.edit().putBoolean(KEY_ONBOARDING_PERMISSION_ASKED, true).apply();
                ActivityCompat.requestPermissions(requireActivity(),
                        missingPermissions.toArray(new String[0]),
                        PERMISSIONS_REQUEST);
            }
        }
    }

    /**
     * 视图销毁时清理 Handler 中排队与延迟的任务，避免内存泄漏。
     */
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // 清理Handler，避免内存泄漏
        handler.removeCallbacksAndMessages(null);
    }

    /**
     * 动画序列入口：从 Logo 动画开始，后续动画由上一段动画结束时逐个触发。
     */
    private void startSequentialAnimations() {
        // 第一步：Logo动画（最顶部）
        startLogoAnimation();
    }

    /**
     * 第一步：Logo 弹跳 + 淡入 + 轻微旋转，结束后触发光环动画。
     */
    private void startLogoAnimation() {
        // 1. Logo弹跳动画
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(ivLogo, "scaleX", 0f, 1.2f, 1.0f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(ivLogo, "scaleY", 0f, 1.2f, 1.0f);
        ObjectAnimator alpha = ObjectAnimator.ofFloat(ivLogo, "alpha", 0f, 1f);
        ObjectAnimator rotation = ObjectAnimator.ofFloat(ivLogo, "rotation", -15f, 0f);

        AnimatorSet logoAnimator = new AnimatorSet();
        logoAnimator.playTogether(scaleX, scaleY, alpha, rotation);
        logoAnimator.setDuration(800);
        logoAnimator.setInterpolator(new BounceInterpolator());
        logoAnimator.start();

        // Logo动画完成后开始光环旋转
        logoAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                startRingAnimation();
            }
        });
    }

    /**
     * 第二步：光环呼吸 + 涟漪扩散 + Logo 轻微浮动，并在稍后触发标题动画。
     * <p>
     * 用"由内向外散开并淡出"的涟漪代替原来机械的匀速旋转，节奏更柔和。
     */
    private void startRingAnimation() {
        // 呼吸：光环缓慢地放大缩小，透明度同步起伏
        ObjectAnimator pulseScaleX = ObjectAnimator.ofFloat(outerRing, "scaleX", 0.97f, 1.03f);
        ObjectAnimator pulseScaleY = ObjectAnimator.ofFloat(outerRing, "scaleY", 0.97f, 1.03f);
        ObjectAnimator pulseAlpha = ObjectAnimator.ofFloat(outerRing, "alpha", 0.5f, 0.85f);
        setBreathing(pulseScaleX, 2800);
        setBreathing(pulseScaleY, 2800);
        setBreathing(pulseAlpha, 2800);

        AnimatorSet pulseAnimator = new AnimatorSet();
        pulseAnimator.playTogether(pulseScaleX, pulseScaleY, pulseAlpha);
        pulseAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        pulseAnimator.start();

        // 涟漪：光圈向外扩散，同时透明由浅入深再散尽，循环时首尾都是全透明，不会跳帧
        ObjectAnimator rippleScaleX = ObjectAnimator.ofFloat(rippleRing, "scaleX", 0.85f, 1.5f);
        ObjectAnimator rippleScaleY = ObjectAnimator.ofFloat(rippleRing, "scaleY", 0.85f, 1.5f);
        ObjectAnimator rippleAlpha = ObjectAnimator.ofFloat(rippleRing, "alpha", 0f, 0.45f, 0f);
        rippleScaleX.setDuration(2800);
        rippleScaleY.setDuration(2800);
        rippleAlpha.setDuration(2800);
        rippleScaleX.setRepeatCount(ObjectAnimator.INFINITE);
        rippleScaleY.setRepeatCount(ObjectAnimator.INFINITE);
        rippleAlpha.setRepeatCount(ObjectAnimator.INFINITE);
        rippleScaleX.setInterpolator(new DecelerateInterpolator());
        rippleScaleY.setInterpolator(new DecelerateInterpolator());
        rippleAlpha.setInterpolator(new AccelerateDecelerateInterpolator());

        AnimatorSet rippleAnimator = new AnimatorSet();
        rippleAnimator.playTogether(rippleScaleX, rippleScaleY, rippleAlpha);
        rippleAnimator.start();

        // Logo 上下轻微浮动，让静态图标有"呼吸感"
        ObjectAnimator logoFloat = ObjectAnimator.ofFloat(ivLogo, "translationY", 0f, -8f);
        setBreathing(logoFloat, 3200);
        logoFloat.setInterpolator(new AccelerateDecelerateInterpolator());
        logoFloat.start();

        // 光环开始后，延迟一点开始标题动画
        handler.postDelayed(this::startTitleAnimation, 300);
    }

    /**
     * 把一个动画配置成"无限来回"的呼吸节奏。
     *
     * @param animator 需要循环的动画
     * @param duration 单程时长（毫秒）
     */
    private void setBreathing(ObjectAnimator animator, long duration) {
        animator.setDuration(duration);
        animator.setRepeatCount(ObjectAnimator.INFINITE);
        animator.setRepeatMode(ObjectAnimator.REVERSE);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
    }

    /**
     * 第三步：标题淡入、放大并轻微上移，结束后延迟触发副标题动画。
     */
    private void startTitleAnimation() {
        // 3. 标题动画（Logo下方）
        ObjectAnimator titleAlpha = ObjectAnimator.ofFloat(tvTitle, "alpha", 0f, 1f);
        ObjectAnimator titleScaleX = ObjectAnimator.ofFloat(tvTitle, "scaleX", 0.7f, 1.0f);
        ObjectAnimator titleScaleY = ObjectAnimator.ofFloat(tvTitle, "scaleY", 0.7f, 1.0f);
        ObjectAnimator titleY = ObjectAnimator.ofFloat(tvTitle, "translationY", 20f, 0f);

        AnimatorSet titleAnimator = new AnimatorSet();
        titleAnimator.playTogether(titleAlpha, titleScaleX, titleScaleY, titleY);
        titleAnimator.setDuration(600);
        titleAnimator.start();

        // 标题动画完成后开始副标题动画
        titleAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                handler.postDelayed(WelcomeFragment.this::startSubtitleAnimation, 200);
            }
        });
    }

    /**
     * 第四步：清空副标题文本，先隐藏，再交给打字机动画逐字显示。
     */
    private void startSubtitleAnimation() {
        // 4. 副标题动画
        // 保存原始文本
        final String originalText = tvSubtitle.getText().toString();
        // 清空文本
        tvSubtitle.setText("");
        // 显示副标题（透明）
        tvSubtitle.setAlpha(1f);
        tvSubtitle.setTranslationX(0f);
        tvSubtitle.setScaleX(1f);

        // 创建打字机动画
        startTypewriterAnimation(tvSubtitle, originalText);
    }

    /**
     * 打字机效果：逐字把文本追加到 TextView 上，标点符号后停顿更久。
     * <p>
     * 使用独立的 Handler，文本播放完毕后自然结束。
     *
     * @param textView 展示文本的控件
     * @param text     需要逐字展示的完整文本
     */
    private void startTypewriterAnimation(final TextView textView, final String text) {
        // 使用StringBuilder逐步构建文本
        final StringBuilder stringBuilder = new StringBuilder();
        final int textLength = text.length();

        // 创建一个Handler来逐步添加字符
        final Handler typewriterHandler = new Handler();

        // Runnable来添加下一个字符
        final Runnable characterAdder = new Runnable() {
            int charIndex = 0;

            @Override
            public void run() {
                if (charIndex < textLength) {
                    // 添加下一个字符
                    stringBuilder.append(text.charAt(charIndex));
                    textView.setText(stringBuilder.toString());

                    // 添加一点随机延迟，让打字效果更自然
                    long delay = 80;
                    // 随机+-20ms
                    delay += (Math.random() * 400 - 20);

                    // 如果是标点符号，稍微停顿一下
                    char currentChar = text.charAt(charIndex);
                    if (currentChar == ',' || currentChar == '.' || currentChar == '!' || currentChar == '?') {
                        delay += 100; // 标点符号多停顿100ms
                    }

                    charIndex++;
                    typewriterHandler.postDelayed(this, delay);
                } else {
                    // 打字完成后的回调
                    onTypewriterComplete();
                }
            }
        };

        // 延迟一点开始打字
        typewriterHandler.postDelayed(characterAdder, 200);
    }

    /**
     * 打字完成后延迟 500ms 触发描述文字动画。
     */
    private void onTypewriterComplete() {
        // 打字完成后，延迟一点开始描述动画
        handler.postDelayed(this::startDescriptionAnimation, 500);
    }

    /**
     * 第五步：描述文字从下方滑入并淡入，结束后触发循环呼吸效果。
     */
    private void startDescriptionAnimation() {
        // 5. 描述文字动画
        // 从下方滑入并淡入
        ObjectAnimator descAlpha = ObjectAnimator.ofFloat(tvDescription, "alpha", 0f, 1f);
        ObjectAnimator descY = ObjectAnimator.ofFloat(tvDescription, "translationY", 30f, 0f);
        ObjectAnimator descScaleX = ObjectAnimator.ofFloat(tvDescription, "scaleX", 0.9f, 1.0f);
        ObjectAnimator descScaleY = ObjectAnimator.ofFloat(tvDescription, "scaleY", 0.9f, 1.0f);

        AnimatorSet descAnimator = new AnimatorSet();
        descAnimator.playTogether(descAlpha, descY, descScaleX, descScaleY);
        descAnimator.setDuration(600);
        descAnimator.start();

        // 描述动画完成后，开始轻微的呼吸效果
        descAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                handler.postDelayed(() -> startBreathingAnimation(tvDescription), 500);
            }
        });
    }

    /**
     * 第六步：呼吸效果（放大 -> 缩小），结束后间隔 2 秒无限循环。
     *
     * @param view 需要执行呼吸动画的视图
     */
    private void startBreathingAnimation(View view) {
        // 6. 轻微的呼吸效果
        ObjectAnimator scaleUpX = ObjectAnimator.ofFloat(view, "scaleX", 1.0f, 1.01f);
        ObjectAnimator scaleUpY = ObjectAnimator.ofFloat(view, "scaleY", 1.0f, 1.01f);

        AnimatorSet breathIn = new AnimatorSet();
        breathIn.playTogether(scaleUpX, scaleUpY);
        breathIn.setDuration(1200);

        ObjectAnimator scaleDownX = ObjectAnimator.ofFloat(view, "scaleX", 1.01f, 1.0f);
        ObjectAnimator scaleDownY = ObjectAnimator.ofFloat(view, "scaleY", 1.01f, 1.0f);

        AnimatorSet breathOut = new AnimatorSet();
        breathOut.playTogether(scaleDownX, scaleDownY);
        breathOut.setDuration(1200);

        AnimatorSet breathAnimator = new AnimatorSet();
        breathAnimator.playSequentially(breathIn, breathOut);

        // 循环执行
        breathAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                // 延迟2秒后再次开始呼吸动画
                handler.postDelayed(() -> startBreathingAnimation(view), 2000);
            }
        });

        breathAnimator.start();
    }
}
