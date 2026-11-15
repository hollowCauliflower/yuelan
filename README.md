# 月澜（Yuelan）

一款以本地存储为核心的 Android 经期记录工具：记录每天的月经情况，自动推算「经期 / 卵泡期 / 排卵期 / 黄体期」四个阶段，并通过日历、统计图表和提醒帮助用户了解自己的周期节律。

- 包名：`work.huacai.yuelan`
- 最低支持：Android 7.0（API 24）
- 目标版本：Android 14（API 34）
- 数据默认只保存在本机，不需要账号，不上传云端

## 主要功能

| 功能 | 说明 |
| --- | --- |
| 周期记录 | 记录每天是否处于经期、流量等级、痛经程度、备注等；支持补签历史日期，未来日期只读 |
| 四阶段预测 | 根据最近一次连续实际记录推算经期、卵泡期、排卵期、黄体期，并在日历中区分「实际记录」与「预测」 |
| 日历视图 | 月历展示周期阶段、实际记录标记与提醒标记；支持点按查看、长按编辑、单日补签与批量连续补签 |
| 统计图表 | 使用 MPAndroidChart 展示流量趋势、痛经分布、周期长度与经期天数 |
| 自定义提醒 | 三种方式：自选日期（仅一次 / 每天 / 每周 / 每月 / 间隔 / 指定多日期）、按周期节点（经期开始等）、按阶段区间（阶段内每天提醒，如整个黄体期吃药） |
| 强提醒（全屏闹钟） | 可选开启：到点点亮屏幕、越过锁屏全屏展示并循环响铃/震动；响铃时长可选 1/3/5/10 分钟或一直响 |
| 通知与闹钟 | 经期提醒、自定义提醒；使用系统「闹钟」方式排程准点触发，开机后自动重建 |
| 自定义图标 / 定制包 | 内置多套风格图标入口；另提供打包脚本，把用户上传的图片写进 APK 生成「定制包」 |
| 数据导出 / 导入 | 导出为 JSON 文件（周期、每日记录、提醒三张表），可在设置页导入恢复或清空数据 |
| 应用更新检测 | 从可配置的 JSON 地址读取最新版本信息，发现新版本时用应用内品牌弹窗提示（可选） |

## 基本使用

### 首次启动

1. 欢迎页 → 基础设置：填写「上次月经开始日期」「月经周期长度」「经期天数」。
2. 开始日期默认今天，**不能选择今天之后的日期**；超出时会弹窗提示并让用户重新选择。
3. 进入加载页后自动生成从起始日到今天的基础记录与建议。

### 首页

- 环形进度展示当前周期进度、当前第几天、周期总长、剩余天数和下次经期。
- 右下角悬浮按钮展开后可记录「流量」「痛经」或查看「今日建议」。
- 进入应用时会自动弹出今日建议；勾选「今天不再提醒」后当天不再自动弹。

### 日历

- 点按日期：有记录时打开当天建议；过去/今天无记录时进入补签；未来日期只展示预测。
- 长按日期：直接打开当天流量 / 痛经录入弹窗；长按带提醒的日期也可查看记录。
- 颜色含义：
  - 实心色块 = 用户实际记录；
  - 浅色块 = 按周期推算的预测；
  - 右下角「预」标记 = 预测日；
  - 顶部图例弹窗首次进入时展示一次。
- 日历左上角可切换「圆点 / 文字」两种事项展示模式，右上角可切换阶段底色；文字模式下「已记录」不会再遮挡日期数字。
- 补签规则：
  - **单日补签只记录这一天，不自动重排后续预测**；
  - 需要重排整段阶段时，使用补签弹窗里的「批量调整」进入涂抹式范围编辑器；
  - 设置页的「更新本轮周期」属于显式调整，会按新参数重新生成预测。
- 跨零点后重新进入应用，会自动对齐到新的一天，不会再把今天误判为未来日期。

### 统计

统计页只统计用户实际记录，不统计自动生成的预测记录。

- **流量趋势折线图**：只绘制当天填写过护垫/血块数量的记录，没填流量的日期不会出现数据点。
- **痛经分布柱状图**：当天只要填写过「流量或痛经」就计入；痛经等级 0 会显示为「无痛经」。因此只记流量的日期也会在痛经图里落一档，看起来会比流量图“多”。
- **周期切分**：相邻经期日期相隔 ≥ 10 天视为新的一轮；只有下一次经期已经开始，上一轮才算完整周期。
- **平均流量 / 平均痛经**：平均流量只统计经期里填写过流量数据的日期；平均痛经统计填写过「流量或痛经」的经期日期，未选择痛经时按「无痛经」参与平均。

### 提醒

自定义提醒支持三种方式：

- **自选日期**：仅一次、每天、每周、每月、每隔 N 天、指定多个日期；
- **按周期节点**：经期开始、经期结束、卵泡期开始、排卵期开始、黄体期开始、下次月经前 3 天，每个周期触发一次；
- **按阶段区间**：选择一个阶段（经期 / 卵泡期 / 排卵期 / 黄体期），阶段内**每天**同一时间提醒，阶段结束自动停止，适合“整个黄体期每天吃黄体酮”这类场景。

编辑弹窗顶部会实时显示提醒节奏（如“21:00 每天 · 整个黄体期 · 共 14 天”），常用提醒（叶酸、黄体酮、红糖姜茶等）可一键填充标题、备注与推荐时间；带阶段归属的预设会自动切换到「阶段区间」。

- 强提醒（全屏闹钟）：
  - 到点通过 full-screen intent 点亮屏幕、越过锁屏全屏显示并循环响铃/震动；
  - 可设置**响铃时长**：1 / 3 / 5 / 10 分钟或一直响；到达时长后自动静音并停止震动，但页面保留，仍需点击「关闭提醒」；
  - Android 14+ 需要允许「全屏通知」；若同时允许「显示在其他应用上层」，亮屏使用中也能直接弹出全屏闹钟页，否则以闹钟铃声 + 高优先级横幅提醒；
  - ColorOS / 一加还需允许「后台弹出界面」「锁屏显示」；
  - 新建提醒默认开启强提醒，响铃时长默认 3 分钟。
- 提醒使用 `setAlarmClock` 排程：系统按“用户可见闹钟”处理，不做批量对齐，避免 Android 14+ 缺少「闹钟与提醒」权限或 ROM 智能省电造成的延迟；若设备仍未授权，提醒列表顶部会出现可点击提示，点一下直达系统授权页。
- 到点通知为高优先级、锁屏可见；经期提醒与自定义提醒都会在开机后自动重建。

### 设置

- 「当前周期设置」可修改上次月经开始日、周期长度、经期长度；开始日不能晚于今天。
- 可切换浅色/深色/跟随系统主题，以及应用内图标风格。
- 支持数据导出、导入、清空。
- 「检查更新」会读取 `app_config.json` 中配置的更新地址。

## 近期更新

- 新增「按阶段区间」提醒：阶段内每天同一时间提醒，阶段结束自动停止，覆盖整个黄体期服药等场景。
- 提醒编辑弹窗重构：顶部节奏摘要卡、自选日期 / 周期节点 / 阶段区间三段式切换、常用提醒横向滑动、每月日期改为选择器，明显缩短弹窗。
- 强提醒可靠性修复：提醒改用 `setAlarmClock` 排程，补充 `USE_EXACT_ALARM` 权限与授权提示，解决到点延迟；强提醒渠道改为带闹钟铃声，亮屏且已授权悬浮窗时可直接弹出全屏页。
- 新增强提醒响铃时长（1/3/5/10 分钟或一直响），到时自动静音并保留关闭入口。
- 修复跨零点进入应用时误显示「当天无记录」：无落库记录时按周期推算预测阶段，日历每次绘制重新对齐“今天”。
- 重排「认识日历颜色」图例弹窗，补齐内边距，四个阶段改为两行两列。
- 明确痛经图表口径（见「统计」章节）。
- 「上次月经开始时间」增加校验：晚于今天时弹窗提示并要求重新选择，不写入未来日期。
- 自定义提醒新增「强提醒（全屏闹钟）」开关与全屏闹钟页面。
- 修复日历文字模式下「已记录」与日期数字重叠。
- 单日补签、单日改阶段不再自动重排预测；需要重排时使用显式「批量调整阶段」或设置页周期设置。

## 配置项（需要手动填写）

| 配置项 | 位置 | 说明 |
| --- | --- | --- |
| Release 签名 | [signing.properties](signing.properties) | `storeFile` / `storePassword` / `keyAlias` / `keyPassword`；当前已配置为 `keystore/yuelan-release.jks`，`keyAlias=yuelan` |
| 版本号 | [version.properties](version.properties) | `VERSION_CODE` 每次发版 +1；`VERSION_NAME` 用于展示与 APK 文件名 |
| 联系方式 / 更新地址 | [app_config.json](app/src/main/assets/app_config.json) | `contact_email`、`contact_wechat` 当前为占位值；`update_check_url` 为空表示不启用更新检查 |
| 定制打包邮箱 | [icon_packager.config.json](tools/icon_packager.config.json) | `email_account`、`email_auth_code`、`imap_host`、`smtp_host`；当前授权码仍为占位符，需填写后才能收邮件打包 |
| SDK 路径 | [local.properties](local.properties) | `sdk.dir`，一般由 Android Studio 自动生成，不需要手改 |
| 签名密钥文件 | [keystore/yuelan-release.jks](keystore) | 已存在；**务必离线备份**，丢失后已安装用户无法覆盖升级 |
| Node.js | 本机环境 | 定制打包脚本需要 Node.js LTS；脚本为零 npm 依赖，不需要 `npm install` |
| 更新 JSON 服务 | 自己的服务器 | 只有填写 `update_check_url` 才需要，返回 `versionCode` / `versionName` / `notes` / `downloadUrl` |

以下文件已加入 [.gitignore](.gitignore)，不会被提交：

- `signing.properties`
- `keystore/`、`*.jks`、`*.keystore`
- `tools/icon_packager.config.json`
- `tools/inbox/`、`tools/dist/`

## 构建方式

### 环境要求

- JDK 17（Android Studio 自带即可）
- Android SDK Platform 34、Build-Tools
- 首次构建需要联网下载依赖；之后可使用 `--offline`

### 一键打包（推荐）

双击 `tools/打包普通APK.bat`，按提示操作即可：

1. 显示当前 `VERSION_CODE` / `VERSION_NAME`，并询问本次是否修改版本号（避免忘记 +1）；
2. 选择使用 release key 还是 debug key；
3. 自动执行 `clean` + `assembleRelease/Debug`，完成后自动打开 APK 所在目录并显示文件大小。

脚本位置：

- `tools/打包普通APK.bat`
- `tools/pack-apk.ps1`
- `tools/pack-common.ps1`（三个打包脚本共用的版本号/签名/构建逻辑）

### Debug 构建

```powershell
.\gradlew.bat assembleDebug --offline
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

### Release 构建

```powershell
.\gradlew.bat assembleRelease --offline
```

产物：`app/build/outputs/apk/release/Yuelan-v<版本名>-release.apk`

### 签名与 key

| 入口 | 使用的 key |
| --- | --- |
| Android Studio 直接点 Run / Build APK(s)（默认 debug 变体） | Android Studio 自带的 debug key：`~/.android/debug.keystore` |
| Android Studio「Generate Signed Bundle / APK」 | 向导中选择的 keystore |
| 命令行 `assembleRelease` / Build Variants 切到 release | [signing.properties](signing.properties) 或 `YUELAN_*` 环境变量配置的 release key |

`app/build.gradle` 只给 `release` 配置了 `signingConfig`，所以 debug 变体始终使用 debug key。若 `signing.properties` 缺失或不完整，`assembleRelease` 仍会执行，但只会产出未签名 APK，无法直接安装。

可以用以下命令确认 APK 实际签名：

```powershell
& 'D:\program\Android\Sdk\build-tools\<版本>\apksigner.bat' verify --print-certs app\build\outputs\apk\debug\app-debug.apk
& 'D:\program\Android\Sdk\build-tools\<版本>\apksigner.bat' verify --print-certs app\build\outputs\apk\release\Yuelan-v1.0-release.apk
```

debug 包与 release 包签名不同，**不能互相覆盖安装**；覆盖升级时必须使用相同 key。

## 版本号与发版

版本号唯一真源是根目录 [version.properties](version.properties)：

```properties
VERSION_CODE=1      # 每次发版 +1，更新检测用它比较新旧
VERSION_NAME=1.0    # 展示给用户的版本名，同时进入 APK 文件名
```

- [app/build.gradle](app/build.gradle) 自动读取这两个字段，无需再改 Gradle 文件。
- Release APK 文件名使用 `VERSION_NAME`：`Yuelan-v<版本名>-release.apk`。
- 定制包文件名同样读取 `VERSION_NAME`：`月澜定制_<标识>_v<版本名>_<时间戳>.apk`。
- 应用更新检测使用 `versionCode` 比较新旧，远端 `versionCode` 必须大于本地才会提示更新。

## 定制图标 / 定制包

系统不允许应用在运行时替换桌面图标，因此「换图标」通过重新打包 APK 实现：

- 应用内置多套风格入口，可在设置页切换应用内图标预览。
- `tools/icon_packager.js` 可把用户上传的图片写入 `res/mipmap-*`、启动页和风格入口，再自动构建 APK。
- 脚本为零 npm 依赖：邮件收发使用 Node.js 内置 TLS 实现，图像处理调用同目录的 `IconResizer.java`。只需要安装 Node.js。

### 使用方式

```powershell
node tools/icon_packager.js --from-dir <图片目录> --name <标识>
node tools/icon_packager.js --from-dir <图片目录> --name <标识> --release
node tools/icon_packager.js --from-email [--release]
```

Windows 下也可以直接双击：

- `tools/定制打包-微信图片.bat`：从 `tools/inbox/<文件夹名>` 读取图片；
- `tools/定制打包-收邮件.bat`：读取 QQ 邮箱未读邮件中的图片附件。

产物输出到 `tools/dist/`，脚本结束后会自动还原工程内图标资源，避免留下脏数据。

每次定制打包都会先执行一次 Gradle `clean`，强制重新生成 APK，避免复用上次的构建产物；因此会比增量构建多花一点时间，但能确保拿到的就是本次图片对应的包。

两个定制打包脚本在开始前也会显示当前 `VERSION_CODE` / `VERSION_NAME`，并询问是否更新版本号；如果是正式发布，请选择更新并把 `VERSION_CODE` +1，否则应用内更新检测不会提示新版本。

### 定制包使用哪个 key

双击批处理时，会在打包前询问本次使用的签名：

```text
请选择本次打包使用的签名：
  1. release key（推荐，使用 signing.properties 配置的密钥）
  2. debug key（Android Studio 默认调试签名，不能覆盖升级正式包）
请输入 1 或 2（直接回车默认 1）
```

- 选择 `1`（或直接回车）：使用 [signing.properties](signing.properties) 配置的 release key；
- 选择 `2`：使用 `~/.android/debug.keystore` 的 debug key；
- 选择 release 但 `signing.properties` 不存在时，脚本会直接报错并退出，不会误打 debug 包。

直接运行 node 命令时，行为保持不变：

- 不传 `--release`：执行 `assembleDebug`，使用 debug key；
- 传 `--release`：执行 `assembleRelease`，使用 release key；
- 收邮件路径也可以在 `tools/icon_packager.config.json` 中设置 `"build_type": "release"`，但脚本显式传入的 `--release` 优先级更高。

切换前请确认 [signing.properties](signing.properties) 已配置且 `keystore/yuelan-release.jks` 存在，否则 release 打包会失败。

## 应用更新检测

在 [app_config.json](app/src/main/assets/app_config.json) 中填写 `update_check_url`（留空表示不启用）。该地址返回 JSON：

```json
{
  "versionCode": 2,
  "versionName": "1.1",
  "notes": "本次更新内容…",
  "downloadUrl": "https://example.com/yuelan-1.1.apk"
}
```

- 应用启动时静默检查一次；仅当远端 `versionCode` 大于本地时才弹品牌弹窗。
- 设置页「关于」区域的「检查更新」可手动触发，结果用 Toast 反馈。
- 地址未配置、网络失败或 JSON 异常时不会打扰用户。
- 未引入额外网络库，使用系统自带 `HttpURLConnection`。

## 个性化配置

[app_config.json](app/src/main/assets/app_config.json) 是唯一的运行时配置文件：

| 字段 | 说明 | 缺省值 |
| --- | --- | --- |
| `contact_email` | 自定义图标说明中展示的邮箱 | `contact@example.com` |
| `contact_wechat` | 自定义图标说明中展示的微信号 | `your_wechat_id` |
| `update_check_url` | 更新检测 JSON 地址 | 空（不启用） |

仓库里只提交中性占位值，因此项目可以直接上传 GitHub。**以后要换成自己的联系方式，只改这个文件**；如果不想把真实值提交出去，可在本地修改后自行排除该文件的变更。

## 技术栈

- Java 8、AndroidX AppCompat、Material Components 1.9
- 自定义 View：`CalendarView`、`CircularPeriodProgressBar`、`CycleRhythmView`
- 数据存储：SQLite ，SharedPreferences 保存设置
- 网络：OkHttp / `HttpURLConnection`（天气、更新检查）
- 图表：MPAndroidChart v3.1.0
- JSON：Gson / `org.json`
- 构建：Gradle 8.2 + Android Gradle Plugin 8.2.2

## 项目结构

```
Yuelan/
├── app/
│   ├── build.gradle                 # 版本号读取、release 签名、依赖
│   └── src/main/
│       ├── java/.../
│       │   ├── activities/          # 提醒列表、引导页、全屏闹钟页等 Activity
│       │   ├── adapter(s)/          # ViewPager / 列表适配器
│       │   ├── dao/                 # 数据访问接口与实现
│       │   ├── database/            # SQLite 建表、备份/导入
│       │   ├── fragments/           # 首页、日历、统计、设置
│       │   ├── helper/              # 周期计算、提醒调度、主题、更新检查、配置读取
│       │   ├── model/               # 数据模型
│       │   ├── receiver/            # 闹钟、开机、经期提醒广播接收器
│       │   └── widget/              # 自定义控件与品牌弹窗
│       ├── assets/
│       │   ├── app_config.json      # 联系方式 / 更新检查地址（占位值）
│       │   └── custom_icons/        # 定制图标运行时资源（打包脚本生成）
│       └── res/                     # 布局、字符串（中/英）、颜色（含 values-night）
├── keystore/                        # 本地 release 密钥（已 gitignore）
├── tools/                           # 定制图标打包脚本与批处理
├── version.properties               # 版本号唯一真源
├── signing.properties.example       # release 签名配置模板
└── README.md
```

## 数据与隐私

- 经期记录、周期设置、提醒保存在本机 SQLite / SharedPreferences，不上传服务器。
- 需要通知权限（提醒）、精确闹钟权限（准时触发）、开机自启权限（重建闹钟）。
- 强提醒（全屏闹钟）在 Android 14+ 还需要「全屏通知」权限；「显示在其他应用上层」为可选，用于亮屏使用时也能直接弹出全屏闹钟页。
- 定位权限为可选：仅用于向 Open-Meteo 查询当地天气，位置不落库；拒绝后使用默认城市。
- 导出/导入由用户通过系统文件选择器主动完成；设置页可清空全部数据。
- 详细条款见应用内「隐私政策」「用户协议」。

## 已知限制

- **应用详情页图标**：部分系统（如 ColorOS）在应用管理/详情页展示的仍是安装包内图标，无法随应用内图标切换而改变。
- **ColorOS 等系统自启动管控**：系统可能限制后台自启动，导致开机后提醒不重建；需要在系统设置中手动允许自启动 / 关联启动。闹钟本身已使用 `setAlarmClock`，不受普通精确闹钟的批量对齐影响。
- **全屏通知限制**：Android 14+ 非闹钟类应用默认可能没有全屏通知权限，强提醒会降级为闹钟铃声 + 高优先级横幅；需用户手动在系统设置中允许。
- **亮屏时全屏弹出**：系统在解锁状态下不会主动拉起 full-screen intent，只有同时允许「显示在其他应用上层」时，应用才能直接启动全屏闹钟页。
- **通知渠道属性**：通知渠道一旦创建，其重要性、锁屏可见性等属性由系统保存，应用无法修改；如需调整需用户手动在系统通知设置中修改。
- **补签不自动重排**：单日补签只写入当天记录，不会改变后续预测；需要调整整段阶段时请使用批量编辑器或设置页周期设置。
- 预测结果仅供参考，不能替代医疗建议。
- 未来日期不可记录，仅展示预测。
