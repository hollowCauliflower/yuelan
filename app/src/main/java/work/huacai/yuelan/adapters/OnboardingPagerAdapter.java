package work.huacai.yuelan.adapters;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import work.huacai.yuelan.fragments.WelcomeFragment;
import work.huacai.yuelan.fragments.SetupFragment;
import work.huacai.yuelan.fragments.LoadingFragment;

/**
 * 引导流程 ViewPager2 适配器。
 * <p>
 * 页面顺序：欢迎页 → 资料设置页 → 加载页。
 * 由于 {@code FragmentStateAdapter} 会自行缓存 Fragment，适配器额外保存每个位置的实例引用，
 * 供 {@code OnboardingActivity} 通过 {@link #getFragment(int)} 跨页面直接调用（如通知设置页更新状态）。
 */
public class OnboardingPagerAdapter extends FragmentStateAdapter {

    /** 引导流程页面总数 */
    private static final int PAGE_COUNT = 3;
    /** 各页面在 ViewPager2 中的下标 */
    private static final int POSITION_WELCOME = 0;
    private static final int POSITION_SETUP = 1;
    private static final int POSITION_LOADING = 2;

    /** 按位置保存已创建的 Fragment 实例，未创建的位置为 null */
    private final Fragment[] fragments;

    /**
     * @param fragmentActivity 宿主 Activity，同时作为 Fragment 的宿主
     */
    public OnboardingPagerAdapter(@NonNull FragmentActivity fragmentActivity) {
        super(fragmentActivity);
        fragments = new Fragment[PAGE_COUNT];
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        Fragment fragment;
        switch (position) {
            case POSITION_SETUP:
                fragment = new SetupFragment();
                break;
            case POSITION_LOADING:
                fragment = new LoadingFragment();
                break;
            case POSITION_WELCOME:
            default:
                // 非法下标同样回退到欢迎页
                fragment = new WelcomeFragment();
                break;
        }
        // 仅在合法位置缓存实例，越界下标不写入数组（与原有逻辑一致）
        if (position >= 0 && position < fragments.length) {
            fragments[position] = fragment;
        }
        return fragment;
    }

    /**
     * 获取指定位置已创建的 Fragment。
     *
     * @param position 页面下标
     * @return 对应 Fragment；下标越界或该页尚未创建时返回 null
     */
    public Fragment getFragment(int position) {
        if (position >= 0 && position < fragments.length) {
            return fragments[position];
        }
        return null;
    }

    @Override
    public int getItemCount() {
        return PAGE_COUNT;
    }
}
