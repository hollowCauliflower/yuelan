package work.huacai.yuelan.helper;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import work.huacai.yuelan.model.Weather;

/**
 * Open-Meteo 天气接口封装。
 * <p>
 * 只查询“某一天的天气”：未来 16 天内走实时预报接口（forecast），
 * 更早的日期走历史归档接口（ERA5 archive，官方约有 5 天数据延迟）。
 * 网络框架为 OkHttp + Gson；请求失败 / 返回异常时通过 {@link WeatherCallback#onError(String)} 上报，
 * 由调用方决定如何降级（例如隐藏天气信息）。
 */
public class OpenMeteoHelper {
    private static final String TAG = "OpenMeteoHelper";
    /** 实时预报接口，支持未来 16 天 */
    private static final String FORECAST_URL = "https://api.open-meteo.com/v1/forecast";
    /** 历史归档接口（ERA5），数据有约 5 天延迟 */
    private static final String ARCHIVE_URL = "https://archive-api.open-meteo.com/v1/era5";
    /** 归档数据延迟天数：距今不足该天数的历史日期查不到 */
    private static final int ARCHIVE_DELAY_DAYS = 5;
    /** 接口要求的日期格式（yyyy-MM-dd，固定 Locale.US） */
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    /** 进程内共享的 OkHttpClient，避免每次请求都新建连接池（双重检查锁保证单例） */
    private static volatile OkHttpClient sharedClient;
    private static final Gson sharedGson = new Gson();

    /** 当前请求的弱引用，供 cancelRequest 取消；弱引用避免长时间持有请求对象 */
    private WeakReference<Call> currentCall;

    /**
     * 天气请求回调
     */
    public interface WeatherCallback {
        /**
         * @param weather 解析成功后的天气数据
         */
        void onSuccess(Weather weather);

        /**
         * @param error 失败原因（网络、HTTP 状态码、数据格式等）
         */
        void onError(String error);
    }

    /**
     * 获取共享的 OkHttpClient（首次调用时创建，10 秒连接/读取超时）。
     */
    private static OkHttpClient getSharedClient() {
        if (sharedClient == null) {
            synchronized (OpenMeteoHelper.class) {
                if (sharedClient == null) {
                    sharedClient = new OkHttpClient.Builder()
                            .connectTimeout(10, TimeUnit.SECONDS)
                            .readTimeout(10, TimeUnit.SECONDS)
                            .build();
                }
            }
        }
        return sharedClient;
    }

    /**
     * 查询指定日期（按当前天气地点）的天气，供每日建议按日期展示。
     * <p>
     * 优先读缓存；未命中时，用户在设置页指定了城市就直接用该城市坐标，
     * 否则走系统定位（无权限/失败时由 {@link LocationHelper} 回退默认城市）。
     * 未来 16 天内走预报接口，更早的日期走历史归档接口。
     *
     * @param context  任意 Context
     * @param date     目标日期
     * @param callback 结果回调（缓存命中时在调用线程同步回调，否则在 OkHttp 工作线程）
     */
    public static void fetchWeatherForDate(Context context, Date date, WeatherCallback callback) {
        if (callback == null || date == null) {
            return;
        }

        final String dateKey = formatApiDate(date);
        final String locationKey = WeatherLocationHelper.getCacheKey(context);

        Weather cached = WeatherCacheHelper.get(context, locationKey, dateKey);
        if (cached != null) {
            callback.onSuccess(cached);
            return;
        }

        // 成功拿到天气后写入缓存，避免来回翻日期或重复打开弹窗时再次请求
        WeatherCallback cacheAndForward = new WeatherCallback() {
            @Override
            public void onSuccess(Weather weather) {
                WeatherCacheHelper.put(context, locationKey, dateKey, weather);
                callback.onSuccess(weather);
            }

            @Override
            public void onError(String error) {
                callback.onError(error);
            }
        };

        resolveCoordinates(context, new CoordinateCallback() {
            @Override
            public void onCoordinates(double lat, double lon) {
                new OpenMeteoHelper().getWeatherForDate(lat, lon, dateKey, cacheAndForward);
            }

            @Override
            public void onError(String error) {
                callback.onError(error);
            }
        });
    }

    /**
     * 预热今天与明天的天气缓存。
     * <p>每日建议弹窗会自动弹出，若等弹窗打开后才定位+请求，天气行会迟到一两秒；
     * 首页 resume 时先预热，弹窗通常可以直接命中缓存。失败静默忽略，不影响本地建议。
     *
     * @param context 任意 Context
     */
    public static void prefetchDailyWeather(Context context) {
        final Date today = new Date();
        Calendar tomorrowCal = Calendar.getInstance();
        tomorrowCal.setTime(today);
        tomorrowCal.add(Calendar.DAY_OF_MONTH, 1);
        final Date tomorrow = tomorrowCal.getTime();

        final String locationKey = WeatherLocationHelper.getCacheKey(context);
        final String todayKey = formatApiDate(today);
        final String tomorrowKey = formatApiDate(tomorrow);

        resolveCoordinates(context, new CoordinateCallback() {
            @Override
            public void onCoordinates(double lat, double lon) {
                prefetchOne(context, lat, lon, locationKey, todayKey);
                prefetchOne(context, lat, lon, locationKey, tomorrowKey);
            }

            @Override
            public void onError(String error) {
                // 预热失败不打扰用户，弹窗打开时会再按需请求一次
            }
        });
    }

    /** 单个日期的预热：已有缓存就跳过，否则请求并写入缓存 */
    private static void prefetchOne(Context context, double lat, double lon,
                                    String locationKey, String dateKey) {
        if (WeatherCacheHelper.get(context, locationKey, dateKey) != null) {
            return;
        }
        new OpenMeteoHelper().getWeatherForDate(lat, lon, dateKey, new WeatherCallback() {
            @Override
            public void onSuccess(Weather weather) {
                WeatherCacheHelper.put(context, locationKey, dateKey, weather);
            }

            @Override
            public void onError(String error) {
                // 预热失败静默处理
            }
        });
    }

    /**
     * 解析当前天气地点的坐标：手动城市直接用配置坐标，否则走系统定位。
     */
    private static void resolveCoordinates(Context context, CoordinateCallback callback) {
        WeatherLocationHelper.City city = WeatherLocationHelper.getSelectedCity(context);
        if (city != null) {
            callback.onCoordinates(city.getLat(), city.getLon());
            return;
        }

        new LocationHelper(context).startLocation(new LocationHelper.LocationCallback() {
            @Override
            public void onLocationResult(double lat, double lon, boolean isDefault) {
                callback.onCoordinates(lat, lon);
            }

            @Override
            public void onLocationError(String error) {
                callback.onError(error);
            }
        });
    }

    /** 把日期格式化为接口要求的 yyyy-MM-dd */
    private static String formatApiDate(Date date) {
        synchronized (DATE_FORMAT) {
            return DATE_FORMAT.format(date);
        }
    }

    /** 坐标解析回调 */
    private interface CoordinateCallback {
        void onCoordinates(double lat, double lon);

        void onError(String error);
    }

    /**
     * 查询今天的天气。
     *
     * @param lat      纬度
     * @param lon      经度
     * @param callback 结果回调
     */
    public void getCurrentWeather(double lat, double lon, WeatherCallback callback) {
        String today;
        synchronized (DATE_FORMAT) {
            today = DATE_FORMAT.format(new Date());
        }
        getWeatherForDate(lat, lon, today, callback);
    }

    /**
     * 查询指定日期的天气（异步，回调在 OkHttp 的工作线程）。
     * <p>副作用：会替换并记录当前请求引用，供 {@link #cancelRequest()} 取消。
     *
     * @param lat      纬度
     * @param lon      经度
     * @param date     日期字符串（yyyy-MM-dd）
     * @param callback 结果回调；为 null 时仅打印日志并返回
     */
    public void getWeatherForDate(double lat, double lon, String date, WeatherCallback callback) {
        if (callback == null) {
            Log.e(TAG, "getWeatherForDate: callback is null");
            return;
        }

        // 先根据日期决定走预报还是归档接口；两者都不可用时直接回调错误
        String apiType = decideApiForDate(date);
        if (apiType == null) {
            callback.onError("无法获取该日期的天气数据（超出范围或数据延迟）");
            return;
        }

        String baseUrl = apiType.equals("forecast") ? FORECAST_URL : ARCHIVE_URL;
        // 只要每日最高温与天气码，且时区跟随坐标自动推断。
        // 注意：预报接口的 start_date 本身可以向前覆盖最近几天，
        // 但不能再叠加 past_days（两者互斥，会直接返回参数错误）。
        String url = baseUrl + "?latitude=" + lat + "&longitude=" + lon
                + "&daily=temperature_2m_max,weathercode"
                + "&start_date=" + date + "&end_date=" + date
                + "&timezone=auto";

        Request request = new Request.Builder().url(url).build();
        Call call = getSharedClient().newCall(request);
        currentCall = new WeakReference<>(call);

        call.enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (isCallCanceled(call)) {
                    // 主动取消不算失败，不回调
                    Log.d(TAG, "Request was canceled");
                    return;
                }
                callback.onError("网络请求失败: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) {
                if (isCallCanceled(call)) {
                    Log.d(TAG, "Request was canceled");
                    return;
                }

                try {
                    if (!response.isSuccessful()) {
                        callback.onError("HTTP错误: " + response.code());
                        return;
                    }

                    String json = response.body() != null ? response.body().string() : null;
                    if (json == null || json.isEmpty()) {
                        callback.onError("返回数据为空");
                        return;
                    }

                    Log.d(TAG, "Response: " + json);

                    // 字段映射：daily.time / daily.temperature_2m_max / daily.weathercode 均为数组；
                    // 预报接口带 past_days 时数组会包含过去几天，必须按日期找到下标，不能固定取 0
                    WeatherResponse weatherResponse = sharedGson.fromJson(json, WeatherResponse.class);
                    if (weatherResponse != null && weatherResponse.daily != null
                            && weatherResponse.daily.time != null
                            && weatherResponse.daily.time.length > 0) {
                        int index = indexOfDate(weatherResponse.daily, date);
                        if (index < 0
                                || weatherResponse.daily.weathercode == null
                                || index >= weatherResponse.daily.weathercode.length
                                || weatherResponse.daily.temperature_2m_max == null
                                || index >= weatherResponse.daily.temperature_2m_max.length) {
                            callback.onError("返回数据缺少目标日期");
                            return;
                        }

                        String weatherText = decodeWeatherCode(weatherResponse.daily.weathercode[index]);
                        double tempValue = weatherResponse.daily.temperature_2m_max[index];
                        // 统一带上“℃”，便于展示与解析
                        String temp = tempValue + "℃";
                        Weather data = new Weather(date, weatherText, temp);
                        callback.onSuccess(data);
                    } else {
                        callback.onError("返回数据格式错误");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "解析响应失败", e);
                    callback.onError("解析失败: " + e.getMessage());
                } finally {
                    // 无论成功失败都要关闭响应体，释放连接
                    if (response != null) {
                        response.close();
                    }
                }
            }
        });
    }

    /**
     * 判断请求是否已被取消（或为空）。
     */
    private boolean isCallCanceled(Call call) {
        return call == null || call.isCanceled();
    }

    /**
     * 取消当前未完成的请求。
     * <p>取消后 OkHttp 会回调 onFailure，但内部会识别取消状态而不上报错误。
     */
    public void cancelRequest() {
        if (currentCall != null) {
            Call call = currentCall.get();
            if (call != null && !call.isCanceled()) {
                call.cancel();
            }
            currentCall.clear();
        }
    }

    /**
     * 根据目标日期选择可用的接口。
     * <p>
     * 规则：目标日期在 [今天-5, 今天+16] 内走预报接口（过去几天用 {@code past_days} 补齐，
     * 这样"昨天/前天"也能查到天气）；更早的日期走归档接口；超过 16 天返回 null。
     *
     * @param targetDate 目标日期（yyyy-MM-dd）
     * @return "forecast" / "archive"；不可查询时返回 null
     */
    private String decideApiForDate(String targetDate) {
        if (targetDate == null || targetDate.isEmpty()) {
            return null;
        }

        Date target;
        Date today;
        synchronized (DATE_FORMAT) {
            try {
                target = DATE_FORMAT.parse(targetDate);
                today = new Date();
            } catch (ParseException e) {
                Log.e(TAG, "Failed to parse date: " + targetDate, e);
                return null;
            }
        }

        // 统一按今天 00:00 做差，避免当天的时分秒影响“天数”判断
        Calendar cal = Calendar.getInstance();
        cal.setTime(today);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        Date todayStart = cal.getTime();

        long diffDays = (target.getTime() - todayStart.getTime()) / (24 * 60 * 60 * 1000);

        if (diffDays >= -ARCHIVE_DELAY_DAYS && diffDays <= 16) {
            // 预报接口同时覆盖最近 5 天的过去日期（past_days）与未来 16 天
            return "forecast";
        }
        // 更早的历史日期：走归档接口
        return diffDays < -ARCHIVE_DELAY_DAYS ? "archive" : null;
    }

    /**
     * 在接口返回的日期数组中查找目标日期的下标。
     *
     * @param daily 接口 daily 节点
     * @param date  目标日期（yyyy-MM-dd）
     * @return 对应下标；找不到时返回 -1
     */
    private int indexOfDate(Daily daily, String date) {
        for (int i = 0; i < daily.time.length; i++) {
            if (date.equals(daily.time[i])) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 把 Open-Meteo 的 WMO 天气码翻译为中文描述。
     * <p>返回词表需与 {@link WeatherSuggestionHelper} 中的关键字匹配逻辑保持一致。
     *
     * @param code WMO weathercode
     * @return 中文天气描述；未知码返回“未知”
     */
    private String decodeWeatherCode(int code) {
        switch (code) {
            case 0: return "晴朗";
            case 1: return "多云";
            case 2: return "阴天";
            case 3: return "阴";
            case 45:
            case 48: return "雾";
            case 51:
            case 53:
            case 55: return "毛毛雨";
            case 61:
            case 63:
            case 65: return "雨";
            case 71:
            case 73:
            case 75: return "雪";
            case 80:
            case 81:
            case 82: return "阵雨";
            case 95: return "雷雨";
            default: return "未知";
        }
    }

    /**
     * 接口响应根对象，只声明用到的 daily 字段。
     */
    private static class WeatherResponse {
        Daily daily;
    }

    /**
     * daily 节点：Gson 按字段名自动映射，下划线字段需用 @SerializedName 显式声明。
     */
    private static class Daily {
        String[] time;
        @SerializedName("temperature_2m_max")
        double[] temperature_2m_max;
        int[] weathercode;
    }
}
