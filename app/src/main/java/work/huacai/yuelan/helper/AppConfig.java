package work.huacai.yuelan.helper;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 应用级配置读取器。
 * <p>
 * 配置来自 {@code assets/app_config.json}，在首次调用时读取并缓存：
 * <ul>
 *   <li>{@code contact_email}：自定义图标定制流程展示的邮箱；</li>
 *   <li>{@code contact_wechat}：定制流程展示的微信号；</li>
 *   <li>{@code update_check_url}：应用更新检测的 JSON 地址，留空表示不启用。</li>
 * </ul>
 * 文件缺失、格式错误或字段为空时，一律回退到中性占位值，保证应用可正常运行。
 * 这样仓库中可以只提交占位值，真实联系方式留在本地配置里，不会泄露到公开仓库。
 */
public final class AppConfig {

    private static final String TAG = "AppConfig";
    /** 配置文件在 assets 中的路径 */
    private static final String ASSET_NAME = "app_config.json";

    /** 缺省联系方式：中性占位值，避免把个人信息写进代码 */
    private static final String DEFAULT_CONTACT_EMAIL = "contact@example.com";
    private static final String DEFAULT_CONTACT_WECHAT = "your_wechat_id";

    private static volatile AppConfig instance;

    private final String contactEmail;
    private final String contactWechat;
    private final String updateCheckUrl;

    private AppConfig(String contactEmail, String contactWechat, String updateCheckUrl) {
        this.contactEmail = contactEmail;
        this.contactWechat = contactWechat;
        this.updateCheckUrl = updateCheckUrl;
    }

    /**
     * 获取全局唯一的配置实例（首次调用时读取 assets 文件）。
     *
     * @param context 任意 Context，内部使用 ApplicationContext
     * @return 配置实例，永不返回 null
     */
    public static AppConfig get(Context context) {
        if (instance == null) {
            synchronized (AppConfig.class) {
                if (instance == null) {
                    instance = load(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    /**
     * 读取并解析 assets/app_config.json，失败时返回全默认值。
     */
    private static AppConfig load(Context context) {
        String email = DEFAULT_CONTACT_EMAIL;
        String wechat = DEFAULT_CONTACT_WECHAT;
        String updateUrl = "";
        try (InputStream in = context.getAssets().open(ASSET_NAME);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            JSONObject json = new JSONObject(sb.toString());
            email = nonEmpty(json.optString("contact_email"), DEFAULT_CONTACT_EMAIL);
            wechat = nonEmpty(json.optString("contact_wechat"), DEFAULT_CONTACT_WECHAT);
            updateUrl = nonEmpty(json.optString("update_check_url"), "");
        } catch (Exception e) {
            // 配置缺失或损坏不应影响应用启动，回退到中性占位值
            Log.w(TAG, "读取 " + ASSET_NAME + " 失败，使用默认配置: " + e.getMessage());
        }
        return new AppConfig(email, wechat, updateUrl);
    }

    private static String nonEmpty(String value, String fallback) {
        return value != null && !value.trim().isEmpty() ? value.trim() : fallback;
    }

    /** @return 定制图标流程展示的联系邮箱 */
    public String getContactEmail() {
        return contactEmail;
    }

    /** @return 定制图标流程展示的微信号 */
    public String getContactWechat() {
        return contactWechat;
    }

    /** @return 更新检测 JSON 地址；空字符串表示未配置 */
    public String getUpdateCheckUrl() {
        return updateCheckUrl;
    }
}
