package work.huacai.yuelan.widget;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.Calendar;
import java.util.Date;

import work.huacai.yuelan.R;
import work.huacai.yuelan.helper.CycleCalculator;
import work.huacai.yuelan.helper.OpenMeteoHelper;
import work.huacai.yuelan.helper.WeatherSuggestionHelper;
import work.huacai.yuelan.model.MenstrualCycle;
import work.huacai.yuelan.model.Weather;

/**
 * 每日建议弹窗里的天气区块。
 * <p>
 * 居中弹窗与底部弹窗共用，负责：
 * <ol>
 *   <li>按当前查看的日期加载天气（今天 / 过去 / 未来分别用不同称呼）；</li>
 *   <li>查看今天时，再结合阶段生成一句天气暖心提醒；</li>
 *   <li>加载期间预留一行高度，避免天气异步返回时弹窗突然跳动。</li>
 * </ol>
 */
final class DailyWeatherSection {

    /**
     * 天气加载完成回调。
     * <p>每日建议正文需要在天气到达后重新生成（天气类片段依赖气温/天气描述），
     * 这里把结果透传给宿主，避免宿主再发一次网络请求。
     */
    interface OnWeatherLoadedListener {
        /**
         * @param today    当天天气，失败时为 null
         * @param tomorrow 明天天气，仅“今天”会额外请求；未请求或失败时为 null
         */
        void onWeatherLoaded(@Nullable Weather today, @Nullable Weather tomorrow);
    }

    private final Context context;
    private final TextView tvWeather;
    private final TextView tvTip;
    /** 当前展示的日期，用于丢弃切换日期后才回来的旧请求结果 */
    private Date currentDate;
    /** 天气加载完成后的宿主回调，可为 null */
    private OnWeatherLoadedListener weatherLoadedListener;

    /**
     * @param context   视图上下文，用于取文案
     * @param tvWeather 天气行
     * @param tvTip     暖心提醒行
     */
    DailyWeatherSection(Context context, TextView tvWeather, TextView tvTip) {
        this.context = context;
        this.tvWeather = tvWeather;
        this.tvTip = tvTip;
    }

    /**
     * 设置天气加载完成回调。
     *
     * @param listener 回调；传 null 表示不通知
     */
    void setOnWeatherLoadedListener(@Nullable OnWeatherLoadedListener listener) {
        this.weatherLoadedListener = listener;
    }

    /**
     * 渲染指定日期的天气；今天的天气额外生成阶段相关的暖心提醒。
     *
     * @param date  当前查看的日期
     * @param phase 当天所属阶段，可为 null
     */
    void show(@Nullable Date date, @Nullable MenstrualCycle phase) {
        if (date == null || tvWeather == null) {
            return;
        }
        currentDate = date;
        hideTip();
        // 先占位：天气回来时只填充文字，弹窗高度不会突然变化
        tvWeather.setVisibility(View.INVISIBLE);

        OpenMeteoHelper.fetchWeatherForDate(context, date, new OpenMeteoHelper.WeatherCallback() {
            @Override
            public void onSuccess(Weather weather) {
                runOnView(() -> {
                    if (!isSameDay(date, currentDate)) {
                        return;
                    }
                    tvWeather.setText(buildWeatherText(date, weather));
                    tvWeather.setVisibility(View.VISIBLE);
                    // 暖心提醒只针对今天：历史/未来日期只展示天气本身
                    if (isToday(date) && phase != null) {
                        // 先取明天天气用于“明日降温”判断，取到后再统一通知宿主
                        loadTip(date, phase, weather);
                    } else {
                        notifyWeatherLoaded(weather, null);
                    }
                });
            }

            @Override
            public void onError(String error) {
                runOnView(() -> {
                    if (isSameDay(date, currentDate)) {
                        tvWeather.setVisibility(View.GONE);
                    }
                });
            }
        });
    }

    /**
     * 加载明天的天气，用于"明日降温"提醒；拿不到时退化为只看今天天气。
     */
    private void loadTip(Date today, MenstrualCycle phase, Weather todayWeather) {
        Calendar tomorrow = Calendar.getInstance();
        tomorrow.setTime(today);
        tomorrow.add(Calendar.DAY_OF_MONTH, 1);

        OpenMeteoHelper.fetchWeatherForDate(context, tomorrow.getTime(),
                new OpenMeteoHelper.WeatherCallback() {
                    @Override
                    public void onSuccess(Weather tomorrowWeather) {
                        renderTip(today, phase, todayWeather, tomorrowWeather);
                    }

                    @Override
                    public void onError(String error) {
                        renderTip(today, phase, todayWeather, null);
                    }
                });
    }

    /** 生成并展示天气提醒；没有匹配的提醒时保持隐藏 */
    private void renderTip(Date today, MenstrualCycle phase,
                           Weather todayWeather, @Nullable Weather tomorrowWeather) {
        runOnView(() -> {
            if (!isSameDay(today, currentDate) || tvTip == null) {
                return;
            }
            String tip = WeatherSuggestionHelper.buildTip(context, phase,
                    todayWeather, tomorrowWeather);
            if (tip == null || tip.isEmpty()) {
                hideTip();
            } else {
                tvTip.setText(tip);
                tvTip.setVisibility(View.VISIBLE);
            }
            // 无论有没有暖心提醒，都通知宿主天气已就绪：建议正文需要据此重新生成
            notifyWeatherLoaded(todayWeather, tomorrowWeather);
        });
    }

    /**
     * 通知宿主天气已加载完成。
     * <p>调用方（{@link #show} 与 {@link #renderTip}）已校验过日期，
     * 这里只负责把结果转发给宿主，不做二次判断。
     */
    private void notifyWeatherLoaded(@Nullable Weather today, @Nullable Weather tomorrow) {
        if (weatherLoadedListener == null || currentDate == null) {
            return;
        }
        weatherLoadedListener.onWeatherLoaded(today, tomorrow);
    }

    /**
     * 天气行文案：今天 / 过去 / 未来使用不同称呼。
     */
    private String buildWeatherText(Date date, Weather weather) {
        int labelRes;
        if (isToday(date)) {
            labelRes = R.string.daily_suggestion_weather_today;
        } else if (CycleCalculator.normalizeDate(date).before(CycleCalculator.normalizeDate(new Date()))) {
            labelRes = R.string.daily_suggestion_weather_past;
        } else {
            labelRes = R.string.daily_suggestion_weather_future;
        }
        return context.getString(labelRes, weather.getWeatherText(), weather.getTemperature());
    }

    private void hideTip() {
        if (tvTip != null) {
            tvTip.setVisibility(View.GONE);
        }
    }

    /**
     * 异步回调切回主线程。
     * <p>弹窗刚创建时视图可能还没 attach，这里不做过早的 attach 判断，
     * 直接 post 即可；弹窗关闭后更新已销毁的视图不会造成崩溃。
     */
    private void runOnView(Runnable action) {
        if (tvWeather == null) {
            return;
        }
        tvWeather.post(action);
    }

    private boolean isToday(Date date) {
        return isSameDay(date, new Date());
    }

    private static boolean isSameDay(Date a, Date b) {
        return CycleCalculator.formatDateId(a).equals(CycleCalculator.formatDateId(b));
    }
}
