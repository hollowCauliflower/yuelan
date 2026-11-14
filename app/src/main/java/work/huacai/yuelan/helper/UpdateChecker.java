package work.huacai.yuelan.helper;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import work.huacai.yuelan.R;
import work.huacai.yuelan.widget.AppDialogFactory;

/**
 * 轻量应用更新检测。
 * <p>
 * 从 {@link AppConfig#getUpdateCheckUrl()} 配置的 JSON 地址读取最新版本信息，格式：
 * <pre>
 * {
 *   "versionCode": 2,
 *   "versionName": "1.1",
 *   "notes": "本次更新内容…",
 *   "downloadUrl": "https://example.com/app.apk"
 * }
 * </pre>
 * 本地 {@code versionCode} 更低时，用应用已有的品牌弹窗提示；
 * 地址未配置、网络失败、JSON 损坏时静默返回，绝不打扰用户（手动检查时才用 Toast 反馈结果）。
 * 网络层使用 JDK 自带的 {@link HttpURLConnection}，不额外引入依赖。
 */
public final class UpdateChecker {

    private static final String TAG = "UpdateChecker";
    /** 连接与读取超时（毫秒），失败即放弃，避免拖慢启动 */
    private static final int TIMEOUT_MS = 8000;
    /** 响应体最大长度，防止异常地址返回超大内容 */
    private static final int MAX_RESPONSE_CHARS = 64 * 1024;

    /** 每个进程只自动检查一次，避免 Activity 重建时重复请求 */
    private static boolean autoChecked = false;

    private UpdateChecker() {
        // 工具类禁止实例化
    }

    /**
     * 启动时静默检查更新：只有发现新版本才弹窗，其它情况什么都不做。
     *
     * @param activity 用于弹窗的 Activity
     */
    public static void checkSilently(@NonNull Activity activity) {
        if (autoChecked) {
            return;
        }
        autoChecked = true;
        check(activity, false);
    }

    /**
     * 设置页手动检查更新：无论成功与否都给出简短反馈。
     *
     * @param activity 用于弹窗 / Toast 的 Activity
     */
    public static void checkManually(@NonNull Activity activity) {
        check(activity, true);
    }

    /**
     * 实际检查逻辑。
     *
     * @param activity 宿主 Activity
     * @param manual   true 表示用户主动触发，需要 Toast 反馈
     */
    private static void check(@NonNull Activity activity, boolean manual) {
        String url = AppConfig.get(activity).getUpdateCheckUrl();
        if (TextUtils.isEmpty(url)) {
            // 未配置地址：自动检查完全静默；手动检查给一条轻提示
            if (manual) {
                Toast.makeText(activity, R.string.update_check_unconfigured, Toast.LENGTH_SHORT).show();
            }
            return;
        }

        Context appContext = activity.getApplicationContext();
        new Thread(() -> {
            UpdateInfo info = null;
            try {
                info = fetch(url);
            } catch (Exception e) {
                Log.w(TAG, "检查更新失败: " + e.getMessage());
            }
            final UpdateInfo result = info;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                long localCode = localVersionCode(appContext);
                if (result == null) {
                    if (manual) {
                        Toast.makeText(activity, R.string.update_check_failed,
                                Toast.LENGTH_SHORT).show();
                    }
                    return;
                }
                if (result.versionCode <= localCode) {
                    if (manual) {
                        Toast.makeText(activity, R.string.update_check_latest,
                                Toast.LENGTH_SHORT).show();
                    }
                    return;
                }
                showUpdateDialog(activity, result);
            });
        }, "update-check").start();
    }

    /**
     * 请求更新配置地址并解析为 {@link UpdateInfo}。
     */
    private static UpdateInfo fetch(String url) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException("HTTP " + code);
            }
            StringBuilder sb = new StringBuilder();
            try (InputStream in = conn.getInputStream();
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(in, StandardCharsets.UTF_8))) {
                char[] buffer = new char[4096];
                int read;
                while ((read = reader.read(buffer)) != -1
                        && sb.length() < MAX_RESPONSE_CHARS) {
                    sb.append(buffer, 0, read);
                }
            }
            JSONObject json = new JSONObject(sb.toString());
            return new UpdateInfo(
                    json.optLong("versionCode", 0),
                    json.optString("versionName", ""),
                    json.optString("notes", ""),
                    json.optString("downloadUrl", ""));
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 展示品牌风格的新版本弹窗；有下载链接时提供“立即更新”按钮。
     */
    private static void showUpdateDialog(Activity activity, UpdateInfo info) {
        String versionName = TextUtils.isEmpty(info.versionName)
                ? String.valueOf(info.versionCode) : info.versionName;
        String title = activity.getString(R.string.update_check_title, versionName);
        String current = activity.getString(R.string.update_check_current_version,
                localVersionName(activity));
        String notes = TextUtils.isEmpty(info.notes)
                ? activity.getString(R.string.update_check_no_notes) : info.notes;
        String message = notes + "\n\n" + current;

        if (TextUtils.isEmpty(info.downloadUrl)) {
            AppDialogFactory.showConfirm(activity, title, message,
                    R.string.dialog_i_know, 0, null);
            return;
        }
        AppDialogFactory.showConfirm(activity, title, message,
                R.string.update_check_update_now,
                R.string.update_check_later,
                () -> openDownloadUrl(activity, info.downloadUrl));
    }

    /**
     * 用系统浏览器 / 应用商店打开下载链接。
     */
    private static void openDownloadUrl(Activity activity, String url) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, R.string.update_check_failed,
                    Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 读取本机安装包的 versionCode，兼容 Android 9 以下。
     */
    private static long localVersionCode(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return info.getLongVersionCode();
            }
            //noinspection deprecation
            return info.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    /**
     * 读取本机安装包的 versionName，取不到时返回空串。
     */
    private static String localVersionName(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return TextUtils.isEmpty(info.versionName) ? "" : info.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    /**
     * 更新配置解析结果。
     */
    private static final class UpdateInfo {
        final long versionCode;
        final String versionName;
        final String notes;
        final String downloadUrl;

        UpdateInfo(long versionCode, String versionName, String notes, String downloadUrl) {
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.notes = notes;
            this.downloadUrl = downloadUrl;
        }
    }
}
