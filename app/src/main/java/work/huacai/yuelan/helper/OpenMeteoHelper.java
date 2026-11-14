package work.huacai.yuelan.helper;

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
        // 只要每日最高温与天气码，且时区跟随坐标自动推断
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

                    // 字段映射：daily.time / daily.temperature_2m_max / daily.weathercode 均为数组，
                    // 这里固定只取第 0 个元素（即请求的那一天）
                    WeatherResponse weatherResponse = sharedGson.fromJson(json, WeatherResponse.class);
                    if (weatherResponse != null && weatherResponse.daily != null
                            && weatherResponse.daily.time != null
                            && weatherResponse.daily.time.length > 0) {

                        String weatherText = decodeWeatherCode(weatherResponse.daily.weathercode[0]);
                        double tempValue = weatherResponse.daily.temperature_2m_max[0];
                        // 统一带上“℃”，供 GenerateSuggestionHelper 再解析回整数
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
     * 规则：目标日期在 [今天, 今天+16] 内走预报接口；
     * 早于今天 5 天以上走归档接口；今天之前的 0~5 天属于归档数据延迟区，返回 null 表示暂不可查。
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

        if (diffDays >= 0) {
            // 未来日期：预报接口最多覆盖 16 天
            return diffDays <= 16 ? "forecast" : null;
        } else {
            // 历史日期：需超过归档延迟才能查到
            return Math.abs(diffDays) > ARCHIVE_DELAY_DAYS ? "archive" : null;
        }
    }

    /**
     * 把 Open-Meteo 的 WMO 天气码翻译为中文描述。
     * <p>返回词表需与 {@code GenerateSuggestionHelper} 中的关键字匹配逻辑保持一致。
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
