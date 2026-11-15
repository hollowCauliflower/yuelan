package work.huacai.yuelan.helper;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.core.app.ActivityCompat;

/**
 * 定位辅助类。
 * <p>
 * 只请求一次定位（{@code requestSingleUpdate}），优先使用网络定位；
 * 无权限、定位开关关闭、请求异常或 15 秒超时都降级为默认坐标（南昌），
 * 这样天气请求始终有值可用，不会阻塞首页加载。
 * 用户也可以在设置页手动指定天气城市，此时不会走这里。
 * 注意：单个实例只服务于一次定位请求。
 */
public class LocationHelper {

    /** 定位超时时间，超时后回退到默认坐标 */
    private static final long LOCATION_TIMEOUT_MS = 15000;
    /** 默认纬度 / 经度（南昌），用作定位失败时的兜底位置 */
    private static final double DEFAULT_LAT = 28.6829;
    private static final double DEFAULT_LON = 115.8582;

    private final Context context;
    private final LocationManager locationManager;
    private final Handler mainHandler;
    private LocationListener locationListener;
    private Runnable timeoutRunnable;
    private LocationCallback callback;

    /**
     * 定位结果回调
     */
    public interface LocationCallback {
        /**
         * @param lat       纬度
         * @param lon       经度
         * @param isDefault true 表示定位失败、返回的是默认坐标
         */
        void onLocationResult(double lat, double lon, boolean isDefault);

        /**
         * @param error 失败原因，用于日志/提示
         */
        void onLocationError(String error);
    }

    /**
     * @param context 任意 Context，内部会转换为 ApplicationContext 以避免泄漏
     */
    public LocationHelper(Context context) {
        this.context = context.getApplicationContext();
        this.locationManager = (LocationManager) this.context.getSystemService(Context.LOCATION_SERVICE);
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * 开始一次定位。
     * <p>回调都在主线程触发；定位成功、失败或超时后都会自动停止定位。
     *
     * @param callback 结果回调，不能为 null（为 null 会在无权限时抛出空指针）
     */
    public void startLocation(LocationCallback callback) {
        this.callback = callback;

        // 未授予精确定位权限时回退默认坐标，保证天气建议仍然可用（与 README 说明一致），
        // 同时避免 SecurityException
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            callback.onLocationResult(DEFAULT_LAT, DEFAULT_LON, true);
            return;
        }

        // 检查 GPS / 网络定位是否开启
        boolean gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        boolean networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);

        if (!gpsEnabled && !networkEnabled) {
            // 定位服务全部关闭，使用默认位置
            callback.onLocationResult(DEFAULT_LAT, DEFAULT_LON, true);
            return;
        }

        // 创建定位监听：拿到第一个结果即停止，避免持续耗电
        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                stopLocation();
                if (callback != null) {
                    callback.onLocationResult(location.getLatitude(), location.getLongitude(), false);
                }
            }

            // 以下回调在当前场景不需要处理，仅实现接口以兼容旧版本系统
            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}

            @Override public void onProviderEnabled(String provider) {}

            @Override public void onProviderDisabled(String provider) {}
        };

        // 超时兜底：15 秒内没拿到结果就用默认坐标
        timeoutRunnable = () -> {
            stopLocation();
            if (callback != null) {
                callback.onLocationResult(DEFAULT_LAT, DEFAULT_LON, true);
            }
        };
        mainHandler.postDelayed(timeoutRunnable, LOCATION_TIMEOUT_MS);

        // 发起定位：网络定位更快，不可用时再退回 GPS
        try {
            if (networkEnabled) {
                locationManager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, locationListener, Looper.getMainLooper());
            } else {
                locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, locationListener, Looper.getMainLooper());
            }
        } catch (SecurityException e) {
            // 权限在实际请求时被回收时可能抛出，转为错误回调
            callback.onLocationError("定位异常: " + e.getMessage());
        }
    }

    /**
     * 停止定位并移除超时任务。
     * <p>多次调用是安全的（内部字段会被置空）。
     */
    public void stopLocation() {
        if (timeoutRunnable != null) {
            mainHandler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
        if (locationManager != null && locationListener != null) {
            locationManager.removeUpdates(locationListener);
            locationListener = null;
        }
    }
}
