# 空叶文档 KongyeDocs

> [!IMPORTANT]
> **本项目仅供个人学习与交流使用，请勿用于任何商业用途。**
>
> 应用的界面外观与交互设计参考了「石墨文档」，「石墨文档」相关的名称、商标、界面设计及著作权均归其权利人所有，本项目与石墨文档官方无任何关联。
>
> **如有侵权，请通过 Issues 联系我，我会第一时间删除。**

一款用 Kotlin + Jetpack Compose 编写的原生 Android 笔记应用（不是网页套壳）。默认在手机本地保存内容，无需注册，可选 WebDAV 云端备份。

[下载 v2.2 APK](https://github.com/Mayleaves/KongyeDocs/releases/download/v2.2/KongyeDocs-v2.2.apk) · [更新记录](CHANGELOG.md)

## v2.2 数据安全更新

- 保留 v2.1 的包名、数据目录和签名方式，可直接覆盖升级；升级前请另外导出一份备份。
- 文件写入同步落盘并保留上一份副本；正文和目录一起提交，恢复操作使用可在重启后回滚的事务。
- 目录或正文读取失败时保留原件并进入数据保护模式，不再用新库或空白正文覆盖。
- 备份格式升级为 v2，包含历史版本、自定义模板、下载记录及副本；附带 SHA-256 完整性校验，继续读取 v2.1 生成的旧备份。
- 恢复前保存安全副本，覆盖当前正文前保留本地版本；旧文件夹状态不会隐藏备份之后新建的文档。
- 回收站不再自动永久删除文件；启动和彻底删除文档时不再连带清除可能被其他版本引用的图片。
- 本地和 WebDAV 备份写入后回读校验，通过后才清理过期自动备份，并至少保留最近三份自动备份文件。
- 使用系统持久任务定期检查备份，离开应用时有数据变化会补充备份；连续编辑最多每 5 秒尝试保存一次。
- WebDAV 密码使用 Android Keystore 加密保存，默认禁止 HTTP；局域网明文连接需要用户显式开启。
- 图片 EXIF 读取改用 [AndroidX ExifInterface 1.4.2](https://developer.android.com/jetpack/androidx/releases/exifinterface)。

备份为未加密 JSON，应放在可信位置；SHA-256 用于完整性检查，不提供加密或来源认证。账号密码、系统备份配置及文件夹授权不随文档备份导出。单个 JSON 备份限 64 MB。应用内部的“恢复前安全备份”随卸载或清除数据一起删除，需要长期保存时请另存到外部。系统调度受省电和强制停止状态影响，重要修改后仍可手动备份。

## 功能

- **文档与文件夹**：新建、重命名、移动、收藏、删除；回收站保留至手动彻底删除
- **整理**：长按多选，批量收藏 / 移动 / 删除；长按拖动调整顺序（手动排序）；按文件名、类型、创建时间、更新时间排序；列表 / 缩略图两种视图
- **首页**：「最近」「收藏」「创建」三栏，左右滑动切换
- **搜索**：搜索标题、正文和评论，可按时间和类型筛选
- **编辑器**：标题、有序 / 无序列表、待办、引用、代码块、分割线、图片、表格、链接；加粗、斜体、下划线、删除线、文字颜色、背景高亮；段落首行缩进；撤销 / 重做；工具栏顺序可自定义
- **Markdown 快捷输入**：`#`、`-`、`1.`、`[]`、`>`、```` ``` ````、`---` 加空格即可转换
- **评论**：选中文字添加评论，可回复、结束评论，查看全部评论
- **版本**：查看目录、查找替换、历史版本、本地版本（每 10 分钟自动保存），可预览并恢复
- **分享**：分享为长图、文字或 Markdown；自定义图片落款；下载记录
- **模板**：内置会议纪要、周报、读书笔记、待办、日记、旅行、项目计划等模板，也可以把文档存为模板
- **备份**：本地备份（系统文件夹）和云端备份（WebDAV，如坚果云），均支持自动备份、手动备份和从备份恢复
- **深色模式**：跟随系统、浅色、深色、定时切换
- **小彩蛋**：「我的」里会随时间变换动作的线条小猫，「关于」里的落叶……

## 技术栈

| 项目 | 说明 |
| --- | --- |
| 语言 | Kotlin |
| 界面 | Jetpack Compose（Compose BOM 2026.09）、Material 3 |
| 存储 | 应用私有目录下的 JSON 文件（kotlinx.serialization） |
| 异步 | Kotlin Coroutines |
| 网络 | OkHttp（仅用于 WebDAV 云端备份） |
| 系统要求 | Android 8.0（API 26）及以上，targetSdk 36 |

## 项目结构

```
app/src/main/java/com/moji/notes/
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

1. 用较新版本的 Android Studio 打开项目（需要 JDK 17 或以上）
2. 等待 Gradle 同步完成后运行，或在命令行执行：

```bash
./gradlew assembleDebug
```

release 包沿用 debug 签名。自行构建时使用本机的 debug.keystore，证书可能与发布包不同；覆盖已安装的发布版本请使用 Releases 中的 APK。

debug 包使用独立包名 `com.moji.notes.audit` 和“空叶文档测试”名称，可与正式版本并存。真机存储回归测试使用合成数据和独立目录：

```bash
./gradlew assembleDebug assembleDebugAndroidTest assembleRelease lintRelease
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.moji.notes.audit.test/com.moji.notes.RegressionInstrumentation
```

## 声明

- 本项目仅用于学习 Android / Jetpack Compose 开发，不提供任何商业服务，也不会上架任何应用市场。
- 界面外观参考了「石墨文档」，相关权利归其权利人所有。
- 如果本项目的任何内容侵犯了您的权益，请通过 Issues 联系，我会立即删除。
