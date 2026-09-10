# WakeUp课表 · 逆向还原版

以 **WakeUp课程表**（wakeup.fun）官方文档与界面为蓝本，用 **Android 原生（Kotlin + Jetpack Compose）** 从零重写的课表应用。非反编译产物，全部代码为重新实现。

## 功能对照

| 功能 | 官方 App | 本项目 |
|---|---|---|
| 周视图课表网格（5/7 列 × N 节） | ✅ | ✅ |
| 左右滑动切周 / 顶部周数指示 | ✅ | ✅ |
| 「非本周」一键回到当前周 | ✅ | ✅ |
| 日期栏（今日高亮、非本周淡化） | ✅ | ✅ |
| 左侧时间轴（节数 + 上下课时间，可关） | ✅ | ✅ |
| 课程块：彩色圆角 + 课程名 + @地点 | ✅ | ✅ |
| 课程详情弹窗（编辑/删除） | ✅ | ✅ |
| 添加课程（一门课多时间段） | ✅ | ✅ |
| 单双周 / 起止周 / 连上节数 | ✅ | ✅ |
| 修改当前周（自动反推开学日期） | ✅ | ✅ |
| 更多功能面板：周数滑竿 + 课表切换器 | ✅ | ✅ |
| 多课表（配置独立：开学日/周数/节数/时间/外观） | ✅ | ✅ |
| 上课时间设置（每节课起止时间） | ✅ | ✅ |
| 课表设置 / 全局设置（深色模式） | ✅ | ✅ |
| **课表外观：背景（默认/纯色/相册图片）** | ✅ | ✅ |
| **课程块透明度调节** | ✅ | ✅ |
| **显示 / 隐藏周末两列** | ✅ | ✅ |
| 已添课程列表 | ✅ | ✅ |
| 导出：备份文件（JSON，含外观配置） | ✅ | ✅ |
| 导出：分享口令 | ✅ | ✅（`WKUP:` + Base64） |
| 导入：备份文件 / 分享口令 | ✅ | ✅ |
| **导入：Excel/CSV 模板** | ✅ | ✅（CSV，Excel 另存即可） |
| 桌面小部件：今日课程列表 | ✅ | ✅ |
| **桌面小部件：整周课表网格** | ✅ | ✅（Canvas 绘制） |
| 教务系统一键导入（1800+ 高校） | ✅ | ❌（需各校爬虫，不计划） |
| 日历文件导出 | ✅ | ❌ |
| 鸿蒙 / iOS 版 | ✅ | ❌（仅 Android） |

## 技术栈

- **Kotlin 2.0 + Jetpack Compose（Material 3）**：全部 UI 声明式实现
- **Room（v2，含迁移）**：课表 / 课程 / 时间段 / 节次时间 四张表，外键级联
- **DataStore**：当前课表、深色模式、时间轴开关
- **Navigation Compose**：单 Activity 多页面
- **Coil**：课表背景图加载（相册图片，含持久化读权限）
- **RemoteViews AppWidget ×2**：今日课程列表 + 整周网格（Canvas 绘 Bitmap）
- **Gson**：备份 JSON 与分享口令编解码
- **JUnit4 单元测试**：周次计算 / 单双周过滤 / 分享口令 / CSV 解析（纯 JVM，可 CI）
- **core 纯逻辑层**：`ScheduleMath` / `ShareCodec` / `CsvScheduleParser` 不依赖 Android

## 构建运行

环境要求：Android Studio Koala+（自带 JDK 17）。

1. 用 Android Studio 打开本目录，等待 Gradle Sync。
2. 或命令行直接构建（已带官方 gradle wrapper，会自动下载 Gradle 8.9）：

```bash
# Windows
gradlew.bat assembleDebug
# 单元测试
gradlew.bat testDebugUnitTest
```

3. APK 产出位置：`app/build/outputs/apk/debug/app-debug.apk`，直接装 Android 8.0+ 设备。

首次启动自动写入「大三上·示例课表」（以当天为第 2 周），打开即可看到完整效果。

> **国内网络提示**：本工程 `settings.gradle.kts` 已内置阿里云 Maven 镜像（官方源兜底），依赖下载失败时会自动回退。
> **非 ASCII 路径提示**：若项目或用户名路径含中文（如 `C:\Users\靳中轩`），`gradle.properties` 里的 `android.overridePathCheck=true` 已处理编译；但**单元测试任务**的 Test Worker JVM 在非 ASCII 的 `GRADLE_USER_HOME` 下无法启动，需将 `GRADLE_USER_HOME` 指向纯英文路径（如 `D:\gradle-home`）再跑测试。

## 打包发布（release APK + 应用内更新）

**签名**：仓库根目录已带 `wakeup-release.keystore`（alias `wakeup`，密码 `wakeup2026`，个人学习用）。
正式发布请换成自己的 keystore 并妥善保管——**后续所有版本必须用同一签名，否则用户无法覆盖安装更新**。

```bash
gradlew.bat assembleRelease
# 产出：app/build/outputs/apk/release/app-release.apk
```

**应用内更新机制**（已实现）：

1. 把 `app-release.apk` 和根目录的 `version.json` 传到任意可公开访问的静态托管（GitHub Releases、对象存储、自己服务器均可）。
2. 把 `app/src/main/java/com/wakeup/schedule/update/UpdateChecker.kt` 里的 `UPDATE_CHECK_URL` 改成你的 version.json 地址。
3. 发新版时：`versionCode` +1、重新打包、更新 version.json 里的 `versionCode/versionName/apkUrl/notes`。
4. 用户打开 App 时自动检查 → 弹「发现新版本」→ 点立即更新 → 系统下载器下载 APK → 自动调起安装（同签名覆盖安装，数据保留）。

> 说明：这是「启动时检查更新」而不是服务器推送。真正的消息推送需要 FCM/厂商推送通道，个人项目用启动检查足够。

## Excel/CSV 模板导入

主界面右上角「导入」→「从 Excel/CSV 模板导入」。模板见仓库根目录 `template.csv`：

```
课程名,星期,开始节,连上节数,开始周,结束周,单双周,地点,教师
高等数学A(下),周一,1,2,1,16,每周,教一-201,王建国
操作系统实验,周四,7,2,3,17,双周,实验楼-C101,赵启明
```

- 星期：`1-7` 或 `周一~周日`；单双周：`每周/单周/双周`（或 `0/1/2`）
- 同名课程多行自动合并为一门课的多个时间段；坏行跳过并提示，不中断导入
- Excel 编辑后「另存为 CSV」即可；全角逗号也兼容

## 目录结构

```
app/src/main/java/com/wakeup/schedule/
├── MainActivity.kt            # 入口：首启写示例数据、深色模式
├── WakeUpApp.kt               # Application：仓库单例
├── core/                      # 纯 JVM 核心逻辑（可单测）
│   ├── ScheduleMath.kt        #   周次计算、单双周过滤、反推开学日
│   ├── ShareCodec.kt          #   分享口令编解码（java.util.Base64）
│   └── CsvScheduleParser.kt   #   CSV 模板解析
├── data/
│   ├── Entities.kt            # TimeTable(含外观) / Course / TimeSlot / SectionTime
│   ├── AppDatabase.kt         # Room v2 + MIGRATION_1_2
│   ├── Repository.kt          # CRUD 与聚合
│   ├── SampleData.kt          # 内置示例课表
│   ├── Backup.kt              # JSON 导入导出（含外观字段，兼容旧备份）
│   └── Prefs.kt               # DataStore 偏好
├── ui/
│   ├── AppRoot.kt             # 路由导航
│   ├── theme/                 # M3 主题 + 12 色课程色板
│   ├── schedule/
│   │   ├── ScheduleScreen.kt  # 主界面（含外观应用：背景/透明度/周末）
│   │   ├── FunctionPanel.kt   # 底部功能面板（周数滑竿+课表切换器）
│   │   └── ScheduleViewModel.kt
│   ├── edit/CourseEditScreen.kt   # 添加/编辑课程（多时间段）
│   ├── list/CourseListScreen.kt   # 已添课程
│   └── settings/                  # 课表设置/上课时间/全局设置/管理课表/课表外观
├── widget/
│   ├── TodayWidget.kt         # 今日课程列表小部件
│   └── WeekGridWidget.kt      # 整周网格小部件（Canvas 绘制）
app/src/test/java/...          # JUnit4 单元测试 ×3
```

## 数据模型

```
TimeTable 1───n Course 1───n TimeSlot
   │  └─ 外观字段：showWeekend / blockAlpha / bgType / bgValue
   └───n SectionTime（每节课起止时间）
```

- 周次 = `(今天 - 开学日) / 7 + 1`；「修改当前周」即反推开学日
- 单双周：`weekType` ∈ {0 每周, 1 单周, 2 双周}，配合 `startWeek..endWeek` 过滤
- 分享口令 = `WKUP:` + Base64(JSON)，与官方 App **不互通**（官方格式未公开）

## 说明

- `preview.html`：主界面与功能面板的高保真静态预览（浏览器直接打开）。
- 本项目仅供学习交流，「WakeUp课程表」商标归原作者所有。
