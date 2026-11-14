package work.huacai.yuelan.activities;

import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;

import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.button.MaterialButton;

import work.huacai.yuelan.R;
import work.huacai.yuelan.adapters.OnboardingPagerAdapter;
import work.huacai.yuelan.fragments.LoadingFragment;
import work.huacai.yuelan.fragments.SetupFragment;
import work.huacai.yuelan.helper.AppIconHelper;
import work.huacai.yuelan.helper.PeriodReminderScheduler;
import work.huacai.yuelan.widget.PagerGestureGate;

/**
 * 首次启动引导页。
 * <p>
 * 共三页：欢迎页（0） -> 基础设置页（1） -> 加载页（2）。
 * 用户翻到最后一页时禁用滑动，播放进度动画，同时在后台线程保存用户设置并初始化数据，
 * 完成后进入当前启用的主界面入口（图标风格不同，入口 Activity 也不同），
 * 并把“首次使用”标记置为 false。
 */
public class OnboardingActivity extends AppCompatActivity {

    /** SharedPreferences 文件名（与 MainActivity 共用同一份配置） */
    private static final String PREF_CONFIG = "menstruation_config";
    /** 首次使用标记，引导完成后置为 false */
    private static final String KEY_FIRST_ENTRY = "first_entry";

    /** 引导页中“基础设置”页的位置 */
    private static final int PAGE_SETUP = 1;
    /** 引导页中“加载中”页的位置 */
    private static final int PAGE_LOADING = 2;
    /** 模拟进度动画时长（毫秒） */
    private static final long MOCK_PROGRESS_DURATION = 3000L;

    private ViewPager2 viewPager;
    private OnboardingPagerAdapter adapter;
    private LinearLayout indicatorContainer;
    private MaterialButton btnSkip;
    private ProgressBar progressBar;
    /** ViewPager2 与“基础设置”页纵向滚动表单的手势方向仲裁器 */
    private PagerGestureGate pagerGestureGate;

    /** 页面指示器圆点，下标与页面位置对应 */
    private ImageView[] indicators;
    private int currentPage = 0;

    private boolean isNavigating = false;     // 防止多次跳转
    private int currentProgress = 0;           // 当前进度值

    /**
     * 界面入口：初始化控件、ViewPager、指示器和“下一步”按钮。
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_onboarding);

        initViews();
        setupViewPager();
        setupIndicators();
        setupButton();
    }

    /**
     * 绑定布局中的控件。
     */
    private void initViews() {
        viewPager = findViewById(R.id.viewPager);
        indicatorContainer = findViewById(R.id.indicatorContainer);
        btnSkip = findViewById(R.id.btnNext);
        progressBar = findViewById(R.id.progressBar);
        // 与主界面共用同一套手势仲裁逻辑：纵向滑动不被误判为翻页，
        // 横向滑动仍正常翻页，滑块等横向拖拽控件不受影响
        pagerGestureGate = PagerGestureGate.attach(this, viewPager);
    }

    /**
     * 分发触摸事件前先做 ViewPager2 主方向仲裁，避免“基础设置”页的
     * 纵向滚动被误判成翻页。
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (pagerGestureGate != null) {
            pagerGestureGate.onTouchEvent(event);
        }
        return super.dispatchTouchEvent(event);
    }

    /**
     * 配置 ViewPager2 的适配器与翻页监听。
     */
    private void setupViewPager() {
        adapter = new OnboardingPagerAdapter(this);
        viewPager.setAdapter(adapter);
        // 预加载其余所有页面，保证后续能通过 adapter 拿到已创建的 Fragment 实例
        viewPager.setOffscreenPageLimit(adapter.getItemCount() - 1);

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                currentPage = position;
                updateIndicators(position);
                updateButton(position);

                // 翻到最后一页（加载页）：禁止再滑动，并开始加载动画
                if (position == adapter.getItemCount() - 1) {
                    viewPager.setUserInputEnabled(false);
                    startLoadingAnimation();
                }
            }
        });
    }

    /**
     * 创建底部页面指示器，默认选中第一页。
     * <p>选中态为品牌粉胶囊（22dp），未选中为 8dp 圆点，形成清晰的进度提示。
     */
    private void setupIndicators() {
        indicators = new ImageView[adapter.getItemCount()];
        for (int i = 0; i < indicators.length; i++) {
            indicators[i] = new ImageView(this);
            indicators[i].setImageResource(i == 0
                    ? R.drawable.onboarding_dot_selected : R.drawable.onboarding_dot_unselected);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    dpToPx(i == 0 ? 22 : 8), dpToPx(8));
            params.setMargins(dpToPx(4), 0, dpToPx(4), 0);
            indicators[i].setLayoutParams(params);
            indicatorContainer.addView(indicators[i]);
        }
    }

    /**
     * 刷新页面指示器：当前页使用品牌粉胶囊，其余页恢复小圆点。
     *
     * @param position 当前页面位置
     */
    private void updateIndicators(int position) {
        for (int i = 0; i < indicators.length; i++) {
            indicators[i].setImageResource(i == position
                    ? R.drawable.onboarding_dot_selected : R.drawable.onboarding_dot_unselected);
            LinearLayout.LayoutParams params =
                    (LinearLayout.LayoutParams) indicators[i].getLayoutParams();
            params.width = dpToPx(i == position ? 22 : 8);
            indicators[i].setLayoutParams(params);
        }
    }

    /**
     * “下一步”按钮点击：前进一页（最后一页不再响应，避免重复进入）。
     */
    private void setupButton() {
        btnSkip.setOnClickListener(v -> {
            if (currentPage < adapter.getItemCount() - 1) {
                viewPager.setCurrentItem(currentPage + 1, true);
            }
        });
    }

    /**
     * 根据当前页更新按钮与进度条状态。
     *
     * @param position 当前页面位置
     */
    private void updateButton(int position) {
        if (position < adapter.getItemCount() - 1) {
            btnSkip.setVisibility(View.VISIBLE);
            progressBar.setVisibility(View.GONE);
        } else {
            // 最后一页：按钮变为不可点的“正在进入”，改由进度条展示加载进度
            btnSkip.setText("正在进入...");
            btnSkip.setEnabled(false);
            btnSkip.setAlpha(0.7f);
            progressBar.setVisibility(View.VISIBLE);
            indicatorContainer.setVisibility(View.GONE);
        }
    }

    /**
     * 启动加载流程：前台播放 0~99 的模拟进度动画，后台执行真实的数据初始化，
     * 初始化完成后把进度补足到 100 并跳转主界面。
     */
    private void startLoadingAnimation() {
        // 模拟进度动画：0 → 99（最后 1% 留给真实初始化完成的时刻）
        ValueAnimator animator = ValueAnimator.ofInt(0, 99);
        animator.setDuration(MOCK_PROGRESS_DURATION);
        animator.addUpdateListener(animation -> {
            currentProgress = (int) animation.getAnimatedValue();
            progressBar.setProgress(currentProgress);
            updateLoadingProgress(currentProgress);
        });
        animator.start();

        // 后台执行真实初始化（保存用户设置并写入初始数据）
        new Thread(() -> {
            saveUserSettingsAndInitData();
            runOnUiThread(() -> {
                // 数据初始化完成，开始补足动画（如果尚未跳转）
                if (!isNavigating) {
                    // 从当前进度补到 100
                    ValueAnimator fillAnim = ValueAnimator.ofInt(currentProgress, 100);
                    fillAnim.setDuration(500);
                    fillAnim.addUpdateListener(animation -> {
                        int progress = (int) animation.getAnimatedValue();
                        progressBar.setProgress(progress);
                        updateLoadingProgress(progress);
                        if (progress == 100 && !isNavigating) {
                            isNavigating = true;
                            navigateToMainActivity();
                        }
                    });
                    fillAnim.start();
                }
            });
        }).start();
    }

    /**
     * 把进度同步给加载页 Fragment。
     *
     * @param progress 0~100 的进度值
     */
    private void updateLoadingProgress(int progress) {
        LoadingFragment fragment = (LoadingFragment) adapter.getFragment(PAGE_LOADING);
        if (fragment != null) {
            fragment.updateProgress(progress);
        }
    }

    /**
     * 引导完成：清除“首次使用”标记，播放按钮退出动画，并延迟跳转到主界面。
     */
    private void navigateToMainActivity() {
        SharedPreferences prefs = getSharedPreferences(PREF_CONFIG, MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_FIRST_ENTRY, false).apply();

        btnSkip.animate()
                .scaleX(0.8f)
                .scaleY(0.8f)
                .alpha(0f)
                .setDuration(300)
                .start();

        // 延迟跳转，让用户看到进度条走满 100%
        new Handler().postDelayed(() -> {
            // 必须启动“当前启用的入口 Activity”：切换图标风格后 MainActivity 可能已被禁用
            Intent intent = new Intent(this, AppIconHelper.getEntryClass(this));
            startActivity(intent);
            finish();
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        }, 1000);
    }

    /**
     * 调用设置页把用户选择的周期/经期数据保存到数据库（在后台线程执行）。
     * 保存失败时抛出异常，交由线程默认的未捕获异常处理。
     */
    private void saveUserSettingsAndInitData() {
        SetupFragment setupFragment = (SetupFragment) adapter.getFragment(PAGE_SETUP);
        if (setupFragment != null) {
            boolean flag = setupFragment.saveUserSettingsAndInitData();
            if (!flag) {
                throw new RuntimeException("saveUserSettingsAndInitData fail");
            }
            // 首次设置完成后立刻安排经期提醒，无需等到下次启动
            PeriodReminderScheduler.apply(this);
        }
    }

    /**
     * dp 转 px。
     *
     * @param dp dp 值
     * @return 对应的像素值
     */
    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
