package work.huacai.yuelan.adapters;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import work.huacai.yuelan.fragments.CalendarFragment;
import work.huacai.yuelan.fragments.HomeFragment;
import work.huacai.yuelan.fragments.SettingsFragment;
import work.huacai.yuelan.fragments.StatisticsFragment;

/**
 * 主界面 ViewPager2 适配器。
 * <p>
 * 固定 4 个页面：首页、日历、统计、设置；页面顺序与底部导航栏的菜单顺序一一对应。
 */
public class MainPagerAdapter extends FragmentStateAdapter {

    private static final String TAG = "ViewPagerAdapter";

    /** 主界面页面总数，与底部导航栏菜单项数量保持一致 */
    private static final int PAGE_COUNT = 4;

    /** 各页面在 ViewPager2 中的下标 */
    private static final int POSITION_HOME = 0;
    private static final int POSITION_CALENDAR = 1;
    private static final int POSITION_STATISTICS = 2;
    private static final int POSITION_SETTINGS = 3;

    /**
     * @param fragmentActivity 宿主 Activity，同时作为 Fragment 的宿主
     */
    public MainPagerAdapter(@NonNull FragmentActivity fragmentActivity) {
        super(fragmentActivity);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        switch (position) {
            case POSITION_CALENDAR:
                return new CalendarFragment();
            case POSITION_STATISTICS:
                return new StatisticsFragment();
            case POSITION_SETTINGS:
                return new SettingsFragment();
            case POSITION_HOME:
                return new HomeFragment();
            default:
                // 非法下标时记录日志并回退到首页，保证 UI 不会出现空白页
                Log.e(TAG, "createFragment invalid position, jump to home fragment");
                return new HomeFragment();
        }
    }

    @Override
    public int getItemCount() {
        return PAGE_COUNT;
    }
}
