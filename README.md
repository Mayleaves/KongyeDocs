# 空叶文档 KongyeDocs

> [!IMPORTANT]
> **本项目仅供个人学习与交流使用，请勿用于任何商业用途。**
>
> 应用的界面外观与交互设计参考了「石墨文档」，「石墨文档」相关的名称、商标、界面设计及著作权均归其权利人所有，本项目与石墨文档官方无任何关联。
>
> **如有侵权，请通过 Issues 联系我，我会第一时间删除。**

一款用 Kotlin + Jetpack Compose 编写的原生 Android 笔记应用（不是网页套壳）。默认在手机本地保存内容，无需注册，可选 WebDAV 云端备份。

[下载APK](https://github.com/Mayleaves/KongyeDocs/releases) · [更新日志](CHANGELOG.md)

## 功能

- **文档与文件夹**：新建、重命名、移动、收藏、删除；回收站保留至手动彻底删除
- **整理**：长按多选，批量收藏 / 移动 / 删除；长按拖动调整顺序（手动排序）；按文件名、类型、创建时间、更新时间排序；列表 / 缩略图两种视图
- **我的**：顶部显示文件、文件夹和收藏数量，不计回收站内的条目；收藏与首页收藏列表采用相同范围
- **首页**：「最近」「收藏」「创建」三栏，左右滑动切换
- **搜索**：搜索标题、正文和评论，可按时间和类型筛选
- **编辑器**：标题、有序 / 无序列表、待办、引用、代码块、分割线、图片、表格、链接；加粗、斜体、下划线、删除线、文字颜色、背景高亮；段落首行缩进；正文跨段选择、输入法全选（只选正文，不包含文档标题）；撤销 / 重做保持键盘展开；工具面板切换保持展开；工具栏顺序可自定义
- **Markdown 快捷输入**：`#`、`-`、`1.`、`[]`、`>`、```` ``` ````、`---` 加空格即可转换
- **评论**：选中文字添加评论，可回复、结束评论，查看全部评论
- **文档工具**：查看目录、查找替换、文档信息
- **本地版本**：系统自动备份默认开启；编辑有变化且距上次本地版本记录超过 10 分钟时记录新版本，也可手动保存。所选版本与当前编辑正文比较：绿色为本地版本新增，红色删除线为本地版本删除；支持原文预览、恢复、生成副本和删除
- **分享**：分享为长图、文字或 Markdown；自定义图片落款；下载记录
- **模板**：内置会议纪要、周报、读书笔记、待办、日记、旅行、项目计划等模板，也可以把文档存为模板
- **备份**：本地备份（系统文件夹）和云端备份（WebDAV，如坚果云），均支持自动备份、手动备份、恢复和删除备份文件
- **深色模式**：跟随系统、浅色、深色、定时切换
- **小彩蛋**：「我的」里会随时间变换动作的线条小猫，支持点击互动和待机动作；小鱼干猫碗、热气、毛线球、打呵欠、逗猫棒和小老鼠使用 SVG 路径绘制，玩耍时猫爪、玩具和尾巴一起运动，颜色随主题变化。「关于」里还有落叶动画

## 保存与备份

- 正文会自动写入应用私有目录；“系统自动备份”控制本地版本记录，不是正文保存开关。卸载应用或清除应用数据会删除这些内部文件。
- 本地 / WebDAV 文件备份与本地版本是独立设置，按各自开关和频率检查；系统后台限制可能推迟执行，不保证应用关闭时准点备份。重要内容建议手动备份，并检查备份文件可以读取。
- 文件备份包含文档、图片、仍保留的 LOCAL 本地版本、自定义模板和下载副本；已停用的 HISTORY 历史记录不再导出或恢复。账户密码和备份目录授权不导出。
- 本地备份为未加密 JSON；云端备份通过 WebDAV 上传，同样需要妥善保管。删除备份会真实删除对应本地 / 云端文件；恢复前会在应用内部另存安全备份。

## 技术栈

| 项目 | 说明 |
| --- | --- |
| 语言 | Kotlin |
| 界面 | Jetpack Compose（Compose BOM 2026.09）、Material 3 |
| 存储 | 应用私有目录下的 JSON 文件（kotlinx.serialization） |
| 异步 | Kotlin Coroutines |
| 网络 | OkHttp（WebDAV 云端备份和 GitHub 版本更新） |
| 系统要求 | Android 8.0（API 26）及以上，targetSdk 36 |

## 项目结构

```
app/src/main/java/com/kongye/docs/
├── data/          数据模型、存储（Repository）、设置、模板、本地 / WebDAV 备份
├── render/        把文档绘制成分享用的长图
├── ui/
│   ├── home/      首页、文件、我的（含线条小猫、拖动排序）
│   ├── editor/    块编辑器、工具栏、评论、查找替换
│   ├── screens/   文件夹、回收站、模板、版本、备份、关于等页面
│   ├── search/    搜索
│   ├── components/ 通用组件、图标、弹窗
│   └── theme/     颜色与浅色 / 深色主题
└── util/          格式化、分享、文件读写
```

## 构建

项目当前使用 Gradle 9.8.2、Android Gradle Plugin 9.4.1、Kotlin 2.4.21，compileSdk 37、targetSdk 36、minSdk 26。本次构建使用 Android Studio 自带的 JBR。

1. 用支持上述工具链的 Android Studio 打开项目，配置兼容的 JDK 并安装 Android SDK 37
2. 等待 Gradle 同步完成后运行，或在命令行执行：

```bash
./gradlew assembleDebug
```

release 包沿用 debug 签名。自行构建时使用本机的 debug.keystore，证书可能与发布包不同；覆盖已安装的发布版本请使用 Releases 中的 APK。

下列命令中的 `DEVICE_SERIAL` 请替换为 `adb devices` 列出的目标设备序列号。

debug 包使用独立包名 `com.kongye.docs.audit` 和“空叶文档测试”名称，可与正式版本并存。真机存储回归测试使用合成数据和独立目录：

```bash
./gradlew assembleDebug assembleDebugAndroidTest assembleRelease lintRelease
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s DEVICE_SERIAL install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s DEVICE_SERIAL shell am instrument -w com.kongye.docs.audit.test/com.kongye.docs.RegressionInstrumentation
```

只检查猫碗和两种玩具动画（合成画面，含浅色 / 深色模式）：

```bash
adb -s DEVICE_SERIAL shell am instrument -w -e only cats com.kongye.docs.audit.test/com.kongye.docs.RegressionInstrumentation
```

## 声明

- 本项目仅用于学习 Android / Jetpack Compose 开发，不提供任何商业服务，也不会上架任何应用市场。
- 界面外观参考了「石墨文档」，相关权利归其权利人所有。
- 如果本项目的任何内容侵犯了您的权益，请通过 Issues 联系，我会立即删除。
