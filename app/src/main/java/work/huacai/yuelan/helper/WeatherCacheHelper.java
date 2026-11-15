package work.huacai.yuelan.helper;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

import work.huacai.yuelan.model.Weather;

/**
 * 天气按天缓存。
 * <p>
 * 同一天、同一地点只请求一次定位与天气：内存缓存供进程内复用（支持在弹窗里来回翻日期），
 * SharedPreferences 缓存最近一天，保证重启后当天再次打开每日建议时无需重新请求。
 * 跨天或切换天气地点后缓存自动失效。
 */
public final class WeatherCacheHelper {

    private static final String PREF_NAME = "weather_cache";
    private static final String KEY_CACHE_KEY = "cache_key";
    private static final String KEY_TEXT = "weather_text";
    private static final String KEY_TEMPERATURE = "temperature";

    /** 进程内缓存：key = 日期@地点，翻看历史/未来日期时可直接命中 */
    private static final Map<String, Weather> memoryCache = new HashMap<>();

    private WeatherCacheHelper() {
    }

    /**
     * 读取缓存。
     *
     * @param context     任意 Context
     * @param locationKey 定位标识（见 {@link WeatherLocationHelper#getCacheKey(Context)}）
     * @param dateKey     日期（yyyy-MM-dd）
     * @return 命中返回天气对象，否则返回 null
     */
    @Nullable
    public static Weather get(Context context, String locationKey, String dateKey) {
        String key = buildKey(locationKey, dateKey);
        Weather memoryWeather = memoryCache.get(key);
        if (memoryWeather != null) {
            return memoryWeather;
        }

        SharedPreferences prefs = getPrefs(context);
        if (!key.equals(prefs.getString(KEY_CACHE_KEY, null))) {
            return null;
        }
        String text = prefs.getString(KEY_TEXT, null);
        if (text == null) {
            return null;
        }
        Weather weather = new Weather(dateKey, text, prefs.getString(KEY_TEMPERATURE, null));
        memoryCache.put(key, weather);
        return weather;
    }

    /**
     * 写入缓存（内存可保留多天，本地只保留最近一天，避免 SharedPreferences 膨胀）。
     *
     * @param context     任意 Context
     * @param locationKey 定位标识
     * @param dateKey     日期（yyyy-MM-dd）
     * @param weather     天气数据
     */
    public static void put(Context context, String locationKey, String dateKey,
                           @NonNull Weather weather) {
        String key = buildKey(locationKey, dateKey);
        memoryCache.put(key, weather);
        getPrefs(context).edit()
                .putString(KEY_CACHE_KEY, key)
                .putString(KEY_TEXT, weather.getWeatherText())
                .putString(KEY_TEMPERATURE, weather.getTemperature())
                .apply();
    }

    /**
     * 清空缓存：切换天气地点后调用，避免继续使用旧城市的数据。
     *
     * @param context 任意 Context
     */
    public static void clear(Context context) {
        memoryCache.clear();
        getPrefs(context).edit().clear().apply();
    }

    /** 缓存 key = 日期 + 地点，任一变化都视为未命中 */
    private static String buildKey(String locationKey, String dateKey) {
        return dateKey + "@" + locationKey;
    }

    /** 天气缓存使用的 SharedPreferences（统一用 ApplicationContext，避免泄漏） */
    private static SharedPreferences getPrefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }
}
