# 路线图 / 交接说明

> 这份文档写给「下一位接手这个项目的工程师」（很可能就是未来的我）。
> 记录**当前质量基线、已知取舍、以及按优先级排序的下一步**。

## 一、当前质量基线（v1.1.0）

| 维度 | 状态 |
|---|---|
| 编译 | `assembleRelease` 通过，产出签名 APK |
| 测试 | **61 个 JVM 单元测试**，覆盖周次计算 / 单双周 / 冲突检测 / 备份编解码 / 口令 / CSV 解析 / 教务课表解析 / CAS 密码加密 / 开学日推算 |
| 分层 | `core`（纯 JVM 逻辑，零 Android 依赖）→ `data`（Room + DataStore）→ `ui`（Compose）→ `widget` / `update` |
| 数据安全 | 课程+时间段写入在事务内；备份导入做字段兜底；DB 有正式迁移 |
| 已上线 | GitHub 仓库 + Release，应用内更新链路实测可用 |
| 教务导入 | 支持中南大学（CAS 统一认证 + 强智 jsxsd），解析逻辑纯 JVM 且有 24 个测试覆盖 |

## 二、刻意没做的事（以及为什么）

工程上「不做」和「做」一样重要，以下都是**有意推迟**：

1. **R8 代码压缩（`isMinifyEnabled`）**：开启需要 Gson 反射 keep 规则，而本项目无法在真机上验证运行时行为——一旦规则不全，导入/更新功能会在用户手机上静默失效。宁可 APK 大 4MB，也不拿功能正确性换体积。若要开启，必须先补真机回归测试。
2. **上课提醒通知**：需要 `WorkManager` 周期任务 + Android 13 `POST_NOTIFICATIONS` 运行时权限 + 时区/免打扰处理，属于「必须真机验证」的功能。
3. **其他学校的教务系统**：目前只适配了中南大学（CAS + 强智 jsxsd）。
   好消息是 `core/jw/` 的解析器已按「强智通用」写（表头偏移、星期顺序、多课一格、单双周都做成了自适应），
   换学校大概率只需改 `JwClient` 里的登录地址与域名白名单。
4. **targetSdk 升到 35**：当前 34 完全够用（不上架 Google Play）。上架前必须升级并处理前台服务/通知权限新规。
5. **多语言（i18n）**：目前界面文案硬编码中文。真要国际化，应先抽 `strings.xml` 再谈翻译。

## 三、下一步优先级

### P0 — 影响数据正确性
- [ ] **Room schema 导出 + 迁移测试**：`exportSchema = true` 并对每一次版本升级写迁移测试（本机无法跑 instrumented test，需在 Android Studio 里补）
- [ ] **导入合并模式**：目前 CSV / JSON 导入一律新建课表；应支持「并到当前课表」并做去重

### P1 — 用户可感知的体验缺口
- [ ] **上课提醒**（见上文「刻意没做」第 2 条，需真机验证）
- [x] ~~教务系统直接导入~~（v1.2.0 已支持中南大学）
- [ ] **教务导入验证码支持**：若学校登录页启用了验证码，需要把验证码图片显示给用户手输
- [ ] **周视图整屏截图分享**：把当前周渲染成图片分享给同学
- [ ] **课表 A/B 对比**：两张课表并排看（换课前后的差异）
- [ ] **今日/明日卡片**：主界面顶部可以更激进地突出「下一节课还有多久」

### P2 — 工程与体验打磨
- [ ] **ViewModel 化收口**：`AppearanceScreen` / `SettingsScreens` / `CourseEditScreen` 目前直接持有 Repository，应统一走 ViewModel（现在能跑，但不利于测试与状态管理）
- [ ] **Compose UI 测试**：`createAndroidComposeRule` 覆盖「添加课程 → 出现在网格」等关键路径
- [ ] **主题动效**：Material You 动态取色、翻页周切换的过渡动画
- [ ] **i18n**：抽 `strings.xml`，至少支持中/英

### P3 — 平台与发布
- [ ] targetSdk 35 + 上架前合规检查
- [ ] GitHub Actions 自动构建 + 自动发 Release（当前靠本地脚本 + REST API 推送）
- [ ] 国内 CDN 托管 `version.json` 与 APK，改善更新检查可达性

## 四、本机开发环境备忘（Windows）

本机环境有若干坑，已踩平，记录以免重复：

| 项目 | 说明 |
|---|---|
| JDK | `D:/Program Files/JDK17`（Temurin 17，需设 `JAVA_HOME`） |
| Android SDK | `%LOCALAPPDATA%/Android/Sdk`（platform-34 / build-tools 34.0.0），`local.properties` 已指向 |
| Gradle | `%LOCALAPPDATA%/gradle-8.9/bin/gradle.bat`（wrapper 下载器在本机网络下必失败，改用 curl 断点续传手动安装） |
| 依赖仓库 | `settings.gradle.kts` 内置阿里云镜像；**插件仓库必须留官方源**（阿里云的 KSP marker 有问题） |
| 非 ASCII 路径 | 项目路径含中文 → 需 `android.overridePathCheck=true`；**单元测试的 Test Worker JVM 在中文 `GRADLE_USER_HOME` 下无法启动**，需 `GRADLE_USER_HOME=D:\gradle-home`，且工程最好复制到 `D:\wakeup-build` |
| git push | 本机 `git push` 长连接必被掐断；**推送一律走 GitHub Git Data REST API**（blobs → tree → commit → ref） |
| 密钥 | `keystore.properties`（不入库）保存签名密码，缺失时退回内置学习用默认值 |
