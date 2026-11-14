package work.huacai.yuelan;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import work.huacai.yuelan.helper.AppIconHelper;

/**
 * 桌面快捷方式的固定入口。
 * <p>
 * 快捷方式不直接指向当前启用的桌面图标入口（MainActivity / LauncherStyle*），
 * 否则用户切换图标风格后，原入口会被禁用，旧快捷方式就无法打开应用。
 * 这里使用一个始终启用的中转 Activity，每次点击时再启动“当前启用”的入口。
 */
public class ShortcutEntryActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            Intent intent = new Intent(this, AppIconHelper.getEntryClass(this));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
        } catch (Exception e) {
            // 入口状态异常时回退到默认入口，保证快捷方式始终能打开应用
            Intent fallback = new Intent(this, MainActivity.class);
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(fallback);
        }
        finish();
    }
}
