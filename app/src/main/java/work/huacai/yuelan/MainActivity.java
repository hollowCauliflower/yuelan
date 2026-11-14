package work.huacai.yuelan;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.TooltipCompat;
import androidx.viewpager2.widget.ViewPager2;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import work.huacai.yuelan.activities.OnboardingActivity;
import work.huacai.yuelan.adapters.MainPagerAdapter;
import work.huacai.yuelan.helper.UpdateChecker;
import work.huacai.yuelan.widget.PagerGestureGate;

/**
 * 应用主界面。
 * <p>
 * 采用 ViewPager2 + BottomNavigationView 组合，承载首页、日历、统计、设置四个页面：
 * 底部导航点击时切换页面，左右滑动页面时反向同步底部导航选中项。
 * 首次进入应用（未完成引导）时直接跳转到 {@link OnboardingActivity}。
 */
public class MainActivity extends AppCompatActivity {

    /** 应用配置使用的 SharedPreferences 文件名 */
    private static final String PREF_CONFIG = "menstruation_config";
    /** 是否首次使用 App 的标记，true 表示用户尚未完成引导流程 */
    private static final String KEY_FIRST_ENTRY = "first_entry";

    /** 底部导航项与 ViewPager2 页面位置的映射，数组下标即页面位置 */
    private static final int[] TAB_IDS = {
            R.id.nav_home,
            R.id.nav_calendar,
            R.id.nav_statistics,
            R.id.nav_settings
    };

    private ViewPager2 viewPager;
    private BottomNavigationView bottomNavigationView;
    /** ViewPager2 与页面内纵向滚动控件的手势方向仲裁器 */
    private PagerGestureGate pagerGestureGate;

    /**
     * 界面入口：判断是否首次启动，首次启动跳转引导页，否则初始化主界面各组件。
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        SharedPreferences prefs = getSharedPreferences(PREF_CONFIG, MODE_PRIVATE);

        // 首次使用 App：进入引导页（欢迎 -> 设置 -> 加载 -> 主界面）
        if (prefs.getBoolean(KEY_FIRST_ENTRY, true)) {
            Intent intent = new Intent(this, OnboardingActivity.class);
            startActivity(intent);
            // 结束当前页面，防止用户在引导页按返回键又回到主界面
            finish();
            return;
        }

        initViews();
        setupViewPager();
        setupBottomNavigation();
        setupPageChangeCallback();
        // 启动后静默检查更新：只有发现新版本才弹品牌弹窗，失败/未配置时不打扰用户
        UpdateChecker.checkSilently(this);
    }

    /**
     * 绑定布局中的核心控件。
     */
    private void initViews() {
        viewPager = findViewById(R.id.view_pager);
        bottomNavigationView = findViewById(R.id.bottom_navigation);
        // 统一在 Activity 层仲裁手势主方向，覆盖首页/日历/统计/设置四个页面
        pagerGestureGate = PagerGestureGate.attach(this, viewPager);
    }

    /**
     * 分发触摸事件前先做 ViewPager2 主方向仲裁：
     * 纵向滑动时阻止 ViewPager2 横向拦截，横向滑动时保持正常翻页。
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (pagerGestureGate != null) {
            pagerGestureGate.onTouchEvent(event);
        }
        return super.dispatchTouchEvent(event);
    }

    /**
     * 配置 ViewPager2：设置四页适配器并预加载全部相邻页面。
     */
    private void setupViewPager() {
        MainPagerAdapter adapter = new MainPagerAdapter(this);
        viewPager.setAdapter(adapter);

        // 共 4 个页面，预加载 3 个，保证滑动切换时不重新创建 Fragment
        viewPager.setOffscreenPageLimit(3);
    }

    /**
     * 底部导航点击回调：点击某个 Tab 后切换到 ViewPager2 对应页面。
     */
    private void setupBottomNavigation() {
        bottomNavigationView.setOnItemSelectedListener(item -> {
            int position = indexOfTab(item.getItemId());
            if (position < 0) {
                // 未知的导航项，不处理
                return false;
            }
            viewPager.setCurrentItem(position, true);
            return true;
        });

        // Material 会在每次构建菜单视图时给导航项重新设置 Tooltip（内容为菜单标题），
        // 仅在 post 里清一次并不保险：页面切换/重建后可能再次被设置。
        // 因此这里既清空 Tooltip，又消费掉长按事件（监听器返回 true 时框架不会再弹 Tooltip），
        // 并在每次布局变化后重新应用，彻底避免长按出现标题提示。
        bottomNavigationView.post(this::suppressNavItemTooltips);
        bottomNavigationView.addOnLayoutChangeListener(
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                        suppressNavItemTooltips());
    }

    /**
     * 清除底部导航项的 Tooltip 并吞掉长按事件，防止长按图标时弹出菜单标题。
     */
    private void suppressNavItemTooltips() {
        for (int id : TAB_IDS) {
            View itemView = bottomNavigationView.findViewById(id);
            if (itemView == null) {
                continue;
            }
            TooltipCompat.setTooltipText(itemView, null);
            // 关闭长按能力：框架只有在 longClickable 时才会检测长按并弹出 Tooltip，
            // 相比消费长按事件，这样长按后松手仍能正常切换页面
            itemView.setOnLongClickListener(null);
            itemView.setLongClickable(false);
        }
    }

    /**
     * 页面滑动回调：滑动切换后同步底部导航的选中状态。
     */
    private void setupPageChangeCallback() {
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);

                if (position >= 0 && position < TAB_IDS.length) {
                    bottomNavigationView.setSelectedItemId(TAB_IDS[position]);
                }
            }
        });
    }

    /**
     * 查找底部导航项对应的页面位置。
     *
     * @param itemId 底部导航项的资源 id
     * @return 对应的 ViewPager2 页面位置，未匹配时返回 -1
     */
    private int indexOfTab(int itemId) {
        for (int i = 0; i < TAB_IDS.length; i++) {
            if (TAB_IDS[i] == itemId) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 提供给 Fragment 调用的页面切换方法。
     *
     * @param position 目标页面位置
     */
    public void setCurrentItem(int position) {
        if (viewPager != null) {
            viewPager.setCurrentItem(position, false);
        }
    }

    /**
     * 返回键处理：不在首页时先回到首页，在首页时才交给系统结束当前界面。
     */
    @Override
    public void onBackPressed() {
        if (viewPager.getCurrentItem() != 0) {
            viewPager.setCurrentItem(0, true);
        } else {
            super.onBackPressed();
        }
    }
}
