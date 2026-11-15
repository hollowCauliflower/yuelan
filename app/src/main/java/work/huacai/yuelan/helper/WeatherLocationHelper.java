package work.huacai.yuelan.helper;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.List;
import java.util.Locale;

import work.huacai.yuelan.R;

/**
 * 天气定位地点设置。
 * <p>
 * 默认跟随系统定位；无定位权限时回退到默认城市（南昌）。
 * 用户也可以手动指定一个城市，这样即使不开定位，也能拿到想要城市的天气。
 */
public final class WeatherLocationHelper {

    /** 天气设置所在的 SharedPreferences 文件名 */
    public static final String PREF_NAME = "weather_settings";
    private static final String KEY_MODE = "weather_location_mode";
    private static final String KEY_CITY = "weather_location_city";
    /** 系统定位解析出的城市名缓存，用于在设置页直接展示"跟随系统定位（xx）" */
    private static final String KEY_DEVICE_CITY = "weather_device_city";

    /** 跟随系统定位 */
    public static final String MODE_DEVICE = "device";

    /**
     * 可选城市：名称 + 纬度 + 经度。
     * <p>坐标取城市中心附近，误差对“查当天天气”没有影响；
     * 第一项同时作为默认兜底城市（南昌）。
     */
    public static final City[] CITIES = {
            new City("南昌", 28.6829, 115.8582),
            new City("北京", 39.9042, 116.4074),
            new City("上海", 31.2304, 121.4737),
            new City("广州", 23.1291, 113.2644),
            new City("深圳", 22.5431, 114.0579),
            new City("杭州", 30.2741, 120.1551),
            new City("成都", 30.5728, 104.0668),
            new City("武汉", 30.5928, 114.3055),
            new City("西安", 34.3416, 108.9398)
    };

    /** 默认城市：跟随定位但拿不到坐标时的兜底 */
    public static final City DEFAULT_CITY = CITIES[0];

    private WeatherLocationHelper() {
    }

    /**
     * 城市坐标。
     */
    public static final class City {
        /** 城市名称，同时作为设置项与缓存 key 的一部分 */
        private final String name;
        /** 纬度 */
        private final double lat;
        /** 经度 */
        private final double lon;

        City(String name, double lat, double lon) {
            this.name = name;
            this.lat = lat;
            this.lon = lon;
        }

        public String getName() {
            return name;
        }

        public double getLat() {
            return lat;
        }

        public double getLon() {
            return lon;
        }
    }

    /**
     * 是否跟随系统定位。
     *
     * @param context 任意 Context
     * @return 跟随定位返回 true（默认值）
     */
    public static boolean isDeviceMode(Context context) {
        return MODE_DEVICE.equals(getPrefs(context).getString(KEY_MODE, MODE_DEVICE));
    }

    /**
     * 当前手动选择的城市。
     *
     * @param context 任意 Context
     * @return 跟随定位时返回 null；城市名无法匹配时返回默认城市
     */
    @Nullable
    public static City getSelectedCity(Context context) {
        if (isDeviceMode(context)) {
            return null;
        }
        String name = getPrefs(context).getString(KEY_CITY, null);
        return findCity(name);
    }

    /**
     * 设置页里"天气地点"的展示文案。
     * <p>跟随系统定位时附带已解析出的城市名，例如"跟随系统定位（南昌）"；
     * 还没解析出来或没有定位权限时只显示"跟随系统定位"。
     *
     * @param context 任意 Context
     * @return 设置项展示文案
     */
    public static String getDisplayName(Context context) {
        if (isDeviceMode(context)) {
            String deviceCity = getPrefs(context).getString(KEY_DEVICE_CITY, null);
            if (deviceCity == null || deviceCity.isEmpty()) {
                return context.getString(R.string.settings_weather_location_device);
            }
            return context.getString(R.string.settings_weather_location_device_with_city,
                    deviceCity);
        }
        City city = getSelectedCity(context);
        return city != null ? city.getName() : DEFAULT_CITY.getName();
    }

    /**
     * 异步解析"跟随系统定位"对应的城市名，用于设置页展示。
     * <p>无定位权限时直接回退默认城市（南昌）；有权限时定位后用系统 Geocoder 反查城市，
     * 解析结果会缓存到 SharedPreferences，供下次进入设置页立即展示。
     *
     * @param context  任意 Context
     * @param callback 结果回调（主线程）
     */
    public static void resolveDeviceCityName(Context context, @NonNull CityNameCallback callback) {
        if (!isDeviceMode(context)) {
            return;
        }
        // 没有定位权限：天气本来就会用默认城市，直接展示南昌
        if (!hasLocationPermission(context)) {
            saveDeviceCity(context, DEFAULT_CITY.getName());
            callback.onCityName(DEFAULT_CITY.getName());
            return;
        }

        new LocationHelper(context).startLocation(new LocationHelper.LocationCallback() {
            @Override
            public void onLocationResult(double lat, double lon, boolean isDefault) {
                if (isDefault) {
                    // 定位不可用时与天气请求保持一致，回退默认城市
                    postCityName(context, DEFAULT_CITY.getName(), callback);
                    return;
                }
                // Geocoder 是同步阻塞调用，放到子线程，避免卡住设置页
                new Thread(() -> {
                    String cityName = reverseGeocode(context, lat, lon);
                    postCityName(context, cityName, callback);
                }, "weather-city-geocode").start();
            }

            @Override
            public void onLocationError(String error) {
                postCityName(context, DEFAULT_CITY.getName(), callback);
            }
        });
    }

    /**
     * 保存/读取系统定位城市名的回调。
     */
    public interface CityNameCallback {
        /**
         * @param cityName 解析出的城市名（失败时为默认城市）
         */
        void onCityName(String cityName);
    }

    /** 是否有定位权限（天气跟随定位时用于选择缓存 key 与展示文案） */
    private static boolean hasLocationPermission(Context context) {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 反查城市名；失败时返回 null（不写缓存，设置项保持"跟随系统定位"） */
    @Nullable
    private static String reverseGeocode(Context context, double lat, double lon) {
        try {
            Geocoder geocoder = new Geocoder(context.getApplicationContext(), Locale.getDefault());
            List<Address> addresses = geocoder.getFromLocation(lat, lon, 1);
            if (addresses != null && !addresses.isEmpty()) {
                Address address = addresses.get(0);
                String name = address.getLocality();
                if (name == null || name.isEmpty()) {
                    name = address.getSubAdminArea();
                }
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }
        } catch (Exception ignored) {
            // 反查失败不影响天气本身，设置项只显示"跟随系统定位"
        }
        return null;
    }

    /** 把解析结果切回主线程并落盘；城市名为 null 时只通知刷新，不写缓存 */
    private static void postCityName(Context context, @Nullable String cityName,
                                     CityNameCallback callback) {
        if (cityName != null) {
            saveDeviceCity(context, cityName);
        }
        new Handler(Looper.getMainLooper()).post(() -> callback.onCityName(
                cityName != null ? cityName : getDisplayName(context)));
    }

    private static void saveDeviceCity(Context context, String cityName) {
        getPrefs(context).edit().putString(KEY_DEVICE_CITY, cityName).apply();
    }

    /**
     * 切回跟随系统定位。
     *
     * @param context 任意 Context
     */
    public static void setDeviceMode(Context context) {
        getPrefs(context).edit().putString(KEY_MODE, MODE_DEVICE).apply();
    }

    /**
     * 手动指定城市。
     *
     * @param context 任意 Context
     * @param city    目标城市
     */
    public static void setCity(Context context, @NonNull City city) {
        getPrefs(context).edit()
                .putString(KEY_MODE, "city")
                .putString(KEY_CITY, city.getName())
                .apply();
    }

    /**
     * 天气缓存的定位标识：切换城市后缓存自动失效。
     * <p>跟随定位时把"是否有定位权限"也拼进 key，用户中途授权后能立即拿到真实定位城市的天气，
     * 不会继续复用默认城市的缓存。
     *
     * @param context 任意 Context
     * @return "device:located" / "device:default" / "city:城市名"
     */
    public static String getCacheKey(Context context) {
        City city = getSelectedCity(context);
        if (city != null) {
            return "city:" + city.getName();
        }
        return MODE_DEVICE + ":" + (hasLocationPermission(context) ? "located" : "default");
    }

    /**
     * 设置弹窗的选项文案：第一项为跟随系统定位，其后依次为城市。
     *
     * @param context 任意 Context
     * @return 选项文案数组
     */
    public static String[] getOptionNames(Context context) {
        String[] names = new String[CITIES.length + 1];
        names[0] = context.getString(R.string.settings_weather_location_device);
        for (int i = 0; i < CITIES.length; i++) {
            names[i + 1] = CITIES[i].getName();
        }
        return names;
    }

    /**
     * 当前选中项在 {@link #getOptionNames(Context)} 中的下标。
     *
     * @param context 任意 Context
     * @return 跟随定位为 0，城市为 城市下标 + 1
     */
    public static int getOptionIndex(Context context) {
        City city = getSelectedCity(context);
        if (city == null) {
            return 0;
        }
        for (int i = 0; i < CITIES.length; i++) {
            if (CITIES[i].getName().equals(city.getName())) {
                return i + 1;
            }
        }
        return 0;
    }

    /** 按名称查找城市；找不到时返回默认城市 */
    private static City findCity(@Nullable String name) {
        if (name == null) {
            return DEFAULT_CITY;
        }
        for (City city : CITIES) {
            if (city.getName().equals(name)) {
                return city;
            }
        }
        return DEFAULT_CITY;
    }

    /** 天气地点设置使用的 SharedPreferences（统一用 ApplicationContext，避免泄漏） */
    private static SharedPreferences getPrefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }
}
