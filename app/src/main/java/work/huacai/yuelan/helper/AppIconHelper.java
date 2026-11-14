package work.huacai.yuelan.helper;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.widget.ImageView;

import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;

import work.huacai.yuelan.R;
import work.huacai.yuelan.ShortcutEntryActivity;

/**
 * 桌面图标自定义助手。
 * <p>
 * Android 不允许普通应用在运行时把用户任选图片写成桌面图标（桌面图标必须是 APK 内置资源），
 * 因此这里采用「预设风格 + 入口 Activity 切换」的方案：
 * <ul>
 *   <li>默认风格即 {@code MainActivity}，其余风格各对应一个继承 MainActivity 的空壳 Activity
 *       （携带各自的桌面图标与启动页主题）；</li>
 *   <li>切换时启用目标入口、禁用其余入口，桌面图标与 API 31+ 的启动页图标同步变化；</li>
 *   <li>当前风格同时写入 SharedPreferences，供引导页 Logo 等应用内展示跟随。</li>
 * </ul>
 * 在此之上还支持「自定义图片」：用户从相册选一张图，作为应用内图标与启动页 Logo，
 * 并可通过系统“固定快捷方式”把这张图放到桌面（桌面原图标受系统限制无法替换）。
 */
public final class AppIconHelper {

    /** 图标风格偏好文件（与应用配置共用） */
    public static final String PREFS_NAME = "menstruation_config";
    /** 当前图标风格的偏好键 */
    public static final String KEY_ICON_STYLE = "app_icon_style";
    /** 是否启用自定义图片的偏好键 */
    public static final String KEY_CUSTOM_ICON = "app_icon_custom";
    /** 打包图标集中当前选中的图片下标的偏好键（-1 = 非打包图标集来源） */
    private static final String KEY_BUNDLED_ICON_INDEX = "app_icon_bundled_index";

    /** 打包定制图标集在 assets 中的目录 */
    private static final String BUNDLED_ICONS_DIR = "custom_icons";
    /** 打包定制图标集的清单文件（assets/custom_icons/icons.json，内容为图片文件名数组） */
    private static final String BUNDLED_ICONS_MANIFEST = BUNDLED_ICONS_DIR + "/icons.json";
    /** 打包定制图标集的默认名称清单（assets/custom_icons/names.json，内容为原始文件名数组） */
    private static final String BUNDLED_NAMES_MANIFEST = BUNDLED_ICONS_DIR + "/names.json";
    /** 打包图标集自定义名称的偏好键前缀（后接图片下标） */
    private static final String KEY_BUNDLED_LABEL_PREFIX = "app_icon_label_";

    /** 风格序号：默认鲸尾月牙 */
    public static final int STYLE_DEFAULT = 0;
    /** 风格序号：蔚蓝月 */
    public static final int STYLE_MOON = 1;
    /** 风格序号：薄荷星 */
    public static final int STYLE_STAR = 2;
    /** 风格序号：紫罗兰 */
    public static final int STYLE_VIOLET = 3;
    /** 风格总数 */
    public static final int STYLE_COUNT = 4;

    /** 自定义图片存放目录名（应用私有 files 下） */
    private static final String CUSTOM_ICON_DIR = "app_icon";
    /** 自定义图片文件名 */
    private static final String CUSTOM_ICON_FILE = "custom_icon.png";
    /** 自定义图片保存边长（px） */
    private static final int CUSTOM_ICON_SIZE = 512;
    /** 桌面快捷方式的短 id */
    private static final String SHORTCUT_ID = "custom_app_icon";
    /** 系统固定快捷方式成功后的回调 action */
    public static final String ACTION_SHORTCUT_PINNED =
            "work.huacai.yuelan.action.SHORTCUT_PINNED";

    /** 各风格在设置弹窗里的名称 */
    private static final int[] STYLE_NAME_RES = {
            R.string.icon_style_default,
            R.string.icon_style_moon,
            R.string.icon_style_star,
            R.string.icon_style_violet
    };

    /** 各风格在应用内展示用的图标资源（引导页 Logo / 设置页预览） */
    private static final int[] STYLE_ICON_RES = {
            R.drawable.ic_launcher_art_whale_moon,
            R.drawable.ic_icon_style_moon,
            R.drawable.ic_icon_style_star,
            R.drawable.ic_icon_style_violet
    };

    /**
     * 各风格对应的通知栏小图标（单色 alpha 图）。
     * <p>Android 通知小图标只取 alpha 通道，彩色图会被系统渲染成白块，
     * 因此这里按风格提供同形状的白色剪影，而不是直接复用彩色应用图标。
     */
    private static final int[] STYLE_NOTIFICATION_RES = {
            R.drawable.ic_notif_heart,
            R.drawable.ic_notif_moon,
            R.drawable.ic_notif_star,
            R.drawable.ic_notif_violet
    };

    /** 各风格对应的入口 Activity 类名（与 AndroidManifest 保持一致；默认风格即 MainActivity） */
    private static final String[] ENTRY_CLASS_NAMES = {
            "work.huacai.yuelan.MainActivity",
            "work.huacai.yuelan.LauncherStyleMoon",
            "work.huacai.yuelan.LauncherStyleStar",
            "work.huacai.yuelan.LauncherStyleViolet"
    };

    private AppIconHelper() {
    }

    /**
     * 读取当前选择的图标风格。
     *
     * @param context 任意 Context
     * @return 风格序号（见 {@link #STYLE_DEFAULT} 等常量）
     */
    public static int getCurrentStyle(Context context) {
        return prefs(context).getInt(KEY_ICON_STYLE, STYLE_DEFAULT);
    }

    /**
     * 切换桌面图标风格：启用目标入口、禁用其它入口，并记录选择。
     * 桌面图标与启动页（API 31+）图标会随之同步变化。
     *
     * @param context 任意 Context
     * @param style   目标风格序号
     */
    public static void applyStyle(Context context, int style) {
        if (style < 0 || style >= STYLE_COUNT) {
            return;
        }

        PackageManager pm = context.getApplicationContext().getPackageManager();
        for (int i = 0; i < ENTRY_CLASS_NAMES.length; i++) {
            ComponentName component = new ComponentName(context.getApplicationContext(),
                    ENTRY_CLASS_NAMES[i]);
            int state = (i == style)
                    ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP);
        }

        prefs(context).edit().putInt(KEY_ICON_STYLE, style).apply();
    }

    /**
     * 获取风格名称资源。
     *
     * @param style 风格序号
     * @return 字符串资源 id；越界时返回默认风格
     */
    public static int getStyleNameRes(int style) {
        return STYLE_NAME_RES[clamp(style)];
    }

    /**
     * 获取风格对应的应用内图标资源。
     *
     * @param style 风格序号
     * @return Drawable 资源 id；越界时返回默认风格
     */
    public static int getStyleIconRes(int style) {
        return STYLE_ICON_RES[clamp(style)];
    }

    /**
     * 获取当前图标风格对应的通知栏小图标资源。
     * <p>
     * 通知小图标会随用户在设置里选择的图标风格变化；用户从相册选择的照片无法转成
     * 单色剪影，因此这种情况下回退到默认粉心图标。打包图标集（定制 APK）自带风格映射，
     * 会跟随对应风格变化。
     *
     * @param context 任意 Context
     * @return 单色通知小图标资源 id
     */
    public static int getNotificationIconRes(Context context) {
        if (hasCustomIcon(context) && getBundledIconIndex(context) < 0) {
            return R.drawable.ic_notif_heart;
        }
        return STYLE_NOTIFICATION_RES[clamp(getCurrentStyle(context))];
    }

    /**
     * 获取当前风格对应的入口 Activity 类。
     * <p>
     * 重启应用（如清空数据后重走引导页）时必须启动“当前启用中的入口”，
     * 否则启动了被禁用的入口会抛 ActivityNotFoundException。
     *
     * @param context 任意 Context
     * @return 当前启用的入口 Activity Class；解析失败时回退到 MainActivity
     */
    public static Class<?> getEntryClass(Context context) {
        try {
            return Class.forName(ENTRY_CLASS_NAMES[clamp(getCurrentStyle(context))]);
        } catch (ClassNotFoundException e) {
            return work.huacai.yuelan.MainActivity.class;
        }
    }

    // ==================== 自定义图片 ====================

    /**
     * 是否已启用自定义图片（且图片文件存在）。
     *
     * @param context 任意 Context
     * @return 已启用返回 true
     */
    public static boolean hasCustomIcon(Context context) {
        return prefs(context).getBoolean(KEY_CUSTOM_ICON, false)
                && getCustomIconFile(context).exists();
    }

    /**
     * 把用户选择的图片裁剪为正方形并保存到应用私有目录，同时启用自定义图片。
     * <p>
     * 图片会先按采样率解码，再居中裁剪、缩放为 512×512，避免大图占用过多内存。
     *
     * @param context 任意 Context
     * @param uri     图片选择器返回的 Uri
     * @throws Exception 无法读取图片、解码失败或写盘失败
     */
    public static void saveCustomIcon(Context context, Uri uri) throws Exception {
        Context appContext = context.getApplicationContext();
        Bitmap source = decodeBitmap(appContext, uri, CUSTOM_ICON_SIZE);
        saveCustomIconBitmap(appContext, cropToSquare(source, CUSTOM_ICON_SIZE));
        prefs(appContext).edit()
                .putBoolean(KEY_CUSTOM_ICON, true)
                // 相册来源，不属于打包图标集
                .putInt(KEY_BUNDLED_ICON_INDEX, -1)
                .apply();
    }

    /**
     * 保存已经裁剪好的快捷方式图片，作为应用内展示的自定义图标。
     * <p>只在桌面快捷方式真正创建成功后调用，避免创建失败也替换应用内图标。
     *
     * @param context 任意 Context
     * @param icon    已裁剪为正方形的图片
     * @throws Exception 写盘失败
     */
    public static void saveCustomIcon(Context context, Bitmap icon) throws Exception {
        Context appContext = context.getApplicationContext();
        saveCustomIconBitmap(appContext, icon);
        prefs(appContext).edit()
                .putBoolean(KEY_CUSTOM_ICON, true)
                .putInt(KEY_BUNDLED_ICON_INDEX, -1)
                .apply();
    }

    /**
     * 读取打包进 APK 的定制图标集（assets/custom_icons/icons.json）。
     * <p>
     * 定制包（按用户图片打包）会带上该清单；普通包没有此文件时返回空列表，
     * 设置页按常规"预设风格 + 相册选图"展示。
     *
     * @param context 任意 Context
     * @return 图标图片的 asset 路径列表（如 "custom_icons/icon_1.png"）
     */
    public static List<String> getBundledIconAssets(Context context) {
        List<String> result = new ArrayList<>();
        try (InputStream is = context.getAssets().open(BUNDLED_ICONS_MANIFEST)) {
            byte[] buffer = new byte[is.available()];
            int read = is.read(buffer);
            if (read <= 0) {
                return result;
            }
            JSONArray array = new JSONArray(new String(buffer, 0, read, StandardCharsets.UTF_8));
            for (int i = 0; i < array.length(); i++) {
                String name = array.optString(i, null);
                if (name != null && !name.isEmpty()) {
                    result.add(BUNDLED_ICONS_DIR + "/" + name);
                }
            }
        } catch (Exception ignored) {
            // 非定制包：没有清单文件，返回空列表
        }
        return result;
    }

    /**
     * 打包图标集每张图片的展示名称。
     * <p>
     * 优先使用用户在设置页里改过的名称，其次使用打包时记录的原始文件名，
     * 都没有时回退到"我的图片 N"。
     *
     * @param context 任意 Context
     * @return 名称列表，顺序与 {@link #getBundledIconAssets} 一致
     */
    public static List<String> getBundledIconLabels(Context context) {
        List<String> labels = new ArrayList<>();
        List<String> fileNames = readStringArray(context, BUNDLED_NAMES_MANIFEST);
        int count = getBundledIconAssets(context).size();
        for (int i = 0; i < count; i++) {
            String custom = prefs(context).getString(KEY_BUNDLED_LABEL_PREFIX + i, null);
            if (custom != null && !custom.isEmpty()) {
                labels.add(custom);
                continue;
            }
            String fileName = i < fileNames.size() ? fileNames.get(i) : null;
            if (fileName != null && !fileName.isEmpty()) {
                labels.add(fileName);
            } else {
                labels.add(context.getString(R.string.icon_style_bundled_item, i + 1));
            }
        }
        return labels;
    }

    /**
     * 修改打包图标集某张图片的展示名称；传空值表示恢复默认名称。
     *
     * @param context 任意 Context
     * @param index   图片下标
     * @param label   新名称
     */
    public static void setBundledIconLabel(Context context, int index, String label) {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (label == null || label.trim().isEmpty()) {
            editor.remove(KEY_BUNDLED_LABEL_PREFIX + index);
        } else {
            editor.putString(KEY_BUNDLED_LABEL_PREFIX + index, label.trim());
        }
        editor.apply();
    }

    /**
     * 打包图标集中第 N 张图片对应的预设风格入口。
     * <p>
     * 定制包打包时会把预设风格入口的图标资源一并换成用户图片，
     * 因此这里可以直接复用"切换入口"实现真正替换桌面图标，无需依赖桌面快捷方式。
     *
     * @param index 图片下标
     * @return 风格序号；没有对应入口（超过 4 张图）时返回 -1
     */
    public static int bundledIndexToStyle(int index) {
        int[] map = {STYLE_DEFAULT, STYLE_MOON, STYLE_STAR, STYLE_VIOLET};
        return index >= 0 && index < map.length ? map[index] : -1;
    }

    /**
     * 读取 assets 里的字符串数组清单。
     *
     * @param context 任意 Context
     * @param path    assets 相对路径
     * @return 数组内容；文件不存在或格式错误时返回空列表
     */
    private static List<String> readStringArray(Context context, String path) {
        List<String> result = new ArrayList<>();
        try (InputStream is = context.getAssets().open(path)) {
            byte[] buffer = new byte[is.available()];
            int read = is.read(buffer);
            if (read <= 0) {
                return result;
            }
            JSONArray array = new JSONArray(new String(buffer, 0, read, StandardCharsets.UTF_8));
            for (int i = 0; i < array.length(); i++) {
                String name = array.optString(i, null);
                if (name != null && !name.isEmpty()) {
                    result.add(name);
                }
            }
        } catch (Exception ignored) {
            // 非定制包没有名称清单，交给调用方回退默认名称
        }
        return result;
    }

    /**
     * 把打包图标集中的一张图片设为自定义图标。
     * <p>
     * 处理管线和相册选图一致（居中裁切、缩放、写入私有目录），
     * 并记录选中的下标，供设置页回显当前选中项。
     *
     * @param context    任意 Context
     * @param assetPath  图片的 asset 路径（来自 {@link #getBundledIconAssets}）
     * @param index      该图片在图标集中的下标
     * @throws Exception 读取/解码/写盘失败
     */
    public static void saveBundledIconAsCustom(Context context, String assetPath, int index) throws Exception {
        Context appContext = context.getApplicationContext();
        Bitmap source;
        try (InputStream is = appContext.getAssets().open(assetPath)) {
            source = BitmapFactory.decodeStream(is);
        }
        if (source == null) {
            throw new Exception("图片解码失败");
        }
        saveCustomIconBitmap(appContext, cropToSquare(source, CUSTOM_ICON_SIZE));
        prefs(appContext).edit()
                .putBoolean(KEY_CUSTOM_ICON, true)
                .putInt(KEY_BUNDLED_ICON_INDEX, index)
                .apply();
    }

    /**
     * 当前自定义图标在打包图标集中的下标。
     *
     * @return 下标；-1 表示未启用自定义图标或图片来源不是打包图标集
     */
    public static int getBundledIconIndex(Context context) {
        if (!hasCustomIcon(context)) {
            return -1;
        }
        return prefs(context).getInt(KEY_BUNDLED_ICON_INDEX, -1);
    }

    /**
     * 把处理好的正方形位图写入自定义图标文件。
     *
     * @param appContext 应用 Context
     * @param square     已裁切缩放的正方形位图
     * @throws Exception 写盘失败
     */
    private static void saveCustomIconBitmap(Context appContext, Bitmap square) throws Exception {
        File file = getCustomIconFile(appContext);
        File dir = file.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            throw new Exception("无法创建图片目录");
        }

        try (FileOutputStream fos = new FileOutputStream(file)) {
            if (!square.compress(Bitmap.CompressFormat.PNG, 100, fos)) {
                throw new Exception("保存图片失败");
            }
        }
    }

    /**
     * 读取已保存的自定义图片。
     *
     * @param context 任意 Context
     * @return 自定义图片；未启用或读取失败时返回 null
     */
    public static Bitmap loadCustomIconBitmap(Context context) {
        if (!hasCustomIcon(context)) {
            return null;
        }
        return BitmapFactory.decodeFile(getCustomIconFile(context).getAbsolutePath());
    }

    /**
     * 移除自定义图片，回到预设风格图标。
     *
     * @param context 任意 Context
     */
    public static void clearCustomIcon(Context context) {
        prefs(context).edit()
                .putBoolean(KEY_CUSTOM_ICON, false)
                .putInt(KEY_BUNDLED_ICON_INDEX, -1)
                .apply();
        getCustomIconFile(context).delete();
    }

    /**
     * 把当前应用图标（自定义图片优先，否则预设风格图标）加载到 ImageView。
     *
     * @param context   任意 Context
     * @param imageView 目标 ImageView
     */
    public static void loadAppIcon(Context context, ImageView imageView) {
        if (imageView == null) {
            return;
        }
        Bitmap custom = loadCustomIconBitmap(context);
        if (custom != null) {
            imageView.setImageBitmap(custom);
        } else {
            imageView.setImageResource(getStyleIconRes(getCurrentStyle(context)));
        }
    }

    /**
     * 在桌面创建一个使用自定义图片的快捷方式。
     * <p>
     * 系统不允许应用直接替换桌面图标资源，固定快捷方式是唯一能把用户图片放到桌面的途径；
     * 需要桌面支持（API 26+）并由用户在系统弹窗中确认。
     *
     * @param activity 当前 Activity（系统弹窗需要 Activity 上下文）
     * @return 已发起创建返回 true；不支持或没有自定义图片时返回 false
     */
    public static boolean requestCustomIconShortcut(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return false;
        }

        Bitmap icon = loadCustomIconBitmap(activity);
        if (icon == null || !ShortcutManagerCompat.isRequestPinShortcutSupported(activity)) {
            return false;
        }

        Intent intent = new Intent(activity, ShortcutEntryActivity.class);
        intent.setAction(Intent.ACTION_VIEW);

        ShortcutInfoCompat shortcut = new ShortcutInfoCompat.Builder(activity, SHORTCUT_ID)
                .setShortLabel(activity.getString(R.string.app_name))
                .setLongLabel(activity.getString(R.string.app_name))
                .setIcon(IconCompat.createWithBitmap(icon))
                .setIntent(intent)
                .build();

        return ShortcutManagerCompat.requestPinShortcut(activity, shortcut, null);
    }

    /**
     * 用内存中的图片请求创建桌面快捷方式，不修改应用内图标。
     * <p>用于「自定义桌面快捷方式」入口：图片只作为快捷方式图标，
     * 成功与否以系统是否真正固定快捷方式为准。
     *
     * @param activity   当前 Activity
     * @param icon       已裁剪好的快捷方式图标
     * @param callback   系统固定成功后的回调；可为 null
     * @param shortcutId 本次快捷方式的唯一 id，避免重复创建时报“图标已存在”
     * @return 已发起系统确认弹窗返回 true；不支持或参数非法返回 false
     */
    public static boolean requestShortcut(Activity activity, Bitmap icon, PendingIntent callback,
                                          String shortcutId) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return false;
        }
        if (icon == null || shortcutId == null || shortcutId.isEmpty()
                || !ShortcutManagerCompat.isRequestPinShortcutSupported(activity)) {
            return false;
        }

        // 固定指向 ShortcutEntryActivity，切换图标风格后入口被禁用也不影响快捷方式打开应用
        Intent intent = new Intent(activity, ShortcutEntryActivity.class);
        intent.setAction(Intent.ACTION_VIEW);

        ShortcutInfoCompat shortcut = new ShortcutInfoCompat.Builder(activity, shortcutId)
                .setShortLabel(activity.getString(R.string.app_name))
                .setLongLabel(activity.getString(R.string.app_name))
                .setIcon(IconCompat.createWithBitmap(icon))
                .setIntent(intent)
                .build();

        return ShortcutManagerCompat.requestPinShortcut(activity, shortcut,
                callback == null ? null : callback.getIntentSender());
    }

    /**
     * 判断指定 id 的桌面快捷方式是否已经真正固定在桌面。
     *
     * @param context    任意 Context
     * @param shortcutId 快捷方式 id
     * @return 已固定返回 true
     */
    public static boolean isShortcutPinned(Context context, String shortcutId) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || shortcutId == null) {
            return false;
        }
        for (ShortcutInfoCompat info : ShortcutManagerCompat.getShortcuts(
                context, ShortcutManagerCompat.FLAG_MATCH_PINNED)) {
            if (shortcutId.equals(info.getId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否已经存在本应用创建的自定义桌面快捷方式。
     * <p>Android 不允许应用自动删除已固定的快捷方式，创建新快捷方式前用它判断
     * 是否需要提醒用户手动清理旧图标。
     *
     * @param context 任意 Context
     * @return 已存在返回 true
     */
    public static boolean hasPinnedCustomShortcuts(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return false;
        }
        for (ShortcutInfoCompat info : ShortcutManagerCompat.getShortcuts(
                context, ShortcutManagerCompat.FLAG_MATCH_PINNED)) {
            String id = info.getId();
            if (id != null && id.startsWith("custom_app_icon")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从相册 Uri 解码并裁剪出快捷方式图标。
     *
     * @param context 任意 Context
     * @param uri     图片选择器返回的 Uri
     * @return 512×512 的正方形图标
     * @throws Exception 读取或解码失败
     */
    public static Bitmap decodeShortcutIcon(Context context, Uri uri) throws Exception {
        Bitmap source = decodeBitmap(context, uri, CUSTOM_ICON_SIZE);
        return cropToSquare(source, CUSTOM_ICON_SIZE);
    }

    /**
     * 自定义图片文件路径。
     */
    private static File getCustomIconFile(Context context) {
        return new File(new File(context.getApplicationContext().getFilesDir(), CUSTOM_ICON_DIR),
                CUSTOM_ICON_FILE);
    }

    /**
     * 按目标尺寸采样解码图片，避免超大图片导致内存溢出。
     *
     * @param context 任意 Context
     * @param uri     图片 Uri
     * @param target  期望的最小边长
     * @return 解码后的位图
     * @throws Exception 无法读取或解码失败
     */
    private static Bitmap decodeBitmap(Context context, Uri uri, int target) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            if (is == null) {
                throw new Exception("无法读取所选图片");
            }
            BitmapFactory.decodeStream(is, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new Exception("所选文件不是有效图片");
        }

        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) {
            sample *= 2;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        Bitmap bitmap;
        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            if (is == null) {
                throw new Exception("无法读取所选图片");
            }
            bitmap = BitmapFactory.decodeStream(is, null, options);
        }
        if (bitmap == null) {
            throw new Exception("图片解码失败");
        }
        return bitmap;
    }

    /**
     * 居中裁剪成正方形并缩放到指定边长。
     *
     * @param source 原始图片
     * @param size   目标边长（px）
     * @return 处理后的正方形图片
     */
    private static Bitmap cropToSquare(Bitmap source, int size) {
        int width = source.getWidth();
        int height = source.getHeight();
        int side = Math.min(width, height);
        int x = (width - side) / 2;
        int y = (height - side) / 2;
        Bitmap square = Bitmap.createBitmap(source, x, y, side, side);
        return Bitmap.createScaledBitmap(square, size, size, true);
    }

    private static int clamp(int style) {
        return (style >= 0 && style < STYLE_COUNT) ? style : STYLE_DEFAULT;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
