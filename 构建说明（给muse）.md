# 相册备份 v2.0.0 · 构建说明（给 muse）

## 你的任务
把本仓库编译成**一个可直接覆盖安装的 APK**：`album-backup-v2.0.0.apk`，交给用户。
源码已经在本地编译通过过一次（`assembleDebug` 无报错），正常情况下你只需要编译和签名，**不需要改代码**。

## 这是什么
- 安卓 App，Kotlin，**纯 framework 控件，没有 AndroidX、没有任何第三方库**
- 包名 `cn.rikka.album`，versionCode 5，versionName 2.0.0
- minSdk 29，compileSdk / targetSdk 34
- 需要 root：所有对备份目录和相册文件的读写都通过 `su` 执行

## 环境
- JDK 17
- Android SDK：`platforms;android-34`、`build-tools;34.0.0`（或更新）
- Gradle 用仓库自带的 wrapper（Gradle 8.9），插件版本：AGP 8.5.2、Kotlin 1.9.24
- 在仓库根目录建 `local.properties`，写上 `sdk.dir=<你的 SDK 路径>`（或设置 `ANDROID_HOME`）

## 步骤
```bash
chmod +x gradlew
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release-unsigned.apk
```

签名（**必须用之前给 `cn.rikka.album` 签名的那个 keystore**，别名 `rikka`。用户手机上装着旧版，签名不一致就没法覆盖安装）：
```bash
BT=$ANDROID_HOME/build-tools/34.0.0
$BT/zipalign -f -p 4 app/build/outputs/apk/release/app-release-unsigned.apk aligned.apk
$BT/apksigner sign --ks <rikka.p12 路径> --ks-key-alias rikka --out album-backup-v2.0.0.apk aligned.apk
```

如果 Maven Central 报 429（Too Many Requests），隔一会儿重试，或加 `--max-workers=1` 降低并发。

## 验收（全部通过才算完成）
```bash
A=album-backup-v2.0.0.apk
BT=$ANDROID_HOME/build-tools/34.0.0
$BT/aapt dump badging $A | grep -E "^package"
#   应为 name='cn.rikka.album' versionCode='5' versionName='2.0.0'
$BT/aapt dump badging $A | grep "application-label"
#   应为 '相册备份'
unzip -l $A | grep -E "mipmap-anydpi-v26/ic_launcher.xml|res/drawable/ic_launcher_fg.xml|classes.dex"
#   应列出 3 行
$BT/apksigner verify --print-certs $A | grep SHA-256
#   证书指纹必须和用户手机上旧版（v1.1.0）的一致
$BT/aapt dump xmltree $A AndroidManifest.xml | grep -E "SyncService|MediaJob|BootReceiver|foregroundServiceType"
#   四行都要有，foregroundServiceType 那行的值是 0x1（dataSync）
```

## 必须遵守
- **不要改包名 `cn.rikka.album`**，不要改版本号。用户是覆盖安装。
- **不要改这些约定**，它们和用户已有的备份数据对应，改了会读不到：
  - 备份目录：`/data/photo_backup`（`Store.DIR`），文件放在 `files/年/月/` 下
  - 清单文件名：`index.tsv`（`Store.MANIFEST`），首行 `#album-backup-manifest\t2`，格式在 `Store.kt` 的 `Manifest` 里
  - 导出目录名：`相册备份导出`（`Exporter.DIR_NAME`），里面必须有 `.nomedia`
- 不要开代码混淆（`isMinifyEnabled = false` 保持不变）。
- 编译报错时只做**最小修改**让它通过，不要重写逻辑。不要加 AndroidX 或其他依赖。
- 改了什么，最后列成清单（文件、改动、原因）交给用户。

## 文件结构
```
app/src/main/java/cn/rikka/album/
  Root.kt          常驻 su shell（优先 su --mount-master），读写、校验、改时间
  Db.kt            SQLite 索引：item（每个照片视频）+ blob（备份文件，同内容只存一份）
  Store.kt         备份目录操作、清单读写、索引找回
  Media.kt         媒体库扫描与刷新
  SyncEngine.kt    一轮同步：增量备份 / 延迟 / 去重 / 校验 / 空间保护 / 删除倒计时 / 首次数量核对
  Organizer.kt     整理乱文件夹（移动、时间核对、空文件夹清理）
  Restorer.kt      三种还原 + 还原后核对报告
  Exporter.kt      导出到 U 盘/文件夹、清空暂存、导出提醒
  Saf.kt           文件夹选择器路径转换、没 root 时的还原来源
  Worker.kt        唯一后台线程（所有任务排队），root 自授权、唤醒锁、校验全部
  SyncService.kt   前台服务、通知、开机自启、媒体变化触发任务
  Prefs.kt / Lg.kt / Status.kt   设置、日志、状态
  MainActivity.kt / HomePage.kt / PendingPage.kt / ToolsPage.kt / SettingsPage.kt   界面
  Ui.kt / Thumbs.kt  界面工具、缩略图、首页胶片条
app/src/main/res/
  values/ values-night/        浅色 / 深色两套配色
  drawable/ic_launcher_*.xml   自适应图标（两张叠放的照片，琥珀色那张代表备份；含单色主题图标）
```

## 交付
- `album-backup-v2.0.0.apk`
- 改动清单（没改就写「无改动」）
- 验收命令的输出

## 用户装上后怎么确认能用
1. 打开 App，管理器（SukiSU）弹出 root 授权时允许
2. 首页右上角显示「● 实时同步中」，开始首次备份（首页有进度条，可以随时停止）
3. 首次备份完成后，打开系统相册，照片视频数量应和之前一样，没有出现两份
4. 拍一张照片，等延迟时间（默认 3 分钟）后回首页，胶片条第一格应是这张
5. 在相册删掉这张，回 App「待删除」页能看到它，点「放回」回到相册原位置
6. 出问题时看「工具 → 日志」，或终端执行 `logcat -s AlbumBackup`
