# 3.7.8 呼噜猫、卡片字号与加粗

日期：2026-11-23，基于 3.7.7，版本 3.7.8 / 75。

## 修改

- 呼噜 SVG 的猫身与常规、逗猫棒、小老鼠姿态按同一高度 64 坐标单位、左侧 30、爪子基线 103 对齐。原 SVG 路径不重画，仅增加 body / effect 标记。
- 鼻泡围绕鼻端放大并淡出，三个 z 符号错开周期放大并淡出；变化限制在对应部件，猫身不缩放、不浮动。
- 桌面两列卡片标题从 16 sp 改为 14 sp，日期从 12 sp 改为 11 sp，正文预览从 12/17 sp 改为 11/16 sp；列表与文档正文字号不受影响。
- 本次小米系统字体配置将逻辑字重 600、700 均映射到 MiSans 450 轴值。按用户反馈进一步调轻：编辑器半粗体、加粗在 MiSans 可用的小米设备分别使用 480/520 轴值，保留逻辑字重 600/700（上一轮为 520/600 轴值）。MiSans 的轴值不等同于 CSS 字重；其他设备和加载失败时保持系统字体。普通正文与代码等宽字体保持原有处理。
- 导出图片的标题、加粗与编辑器使用相同原生字重。没有打包手机的系统字体文件，也没有改动文档存储格式。

## 构建和检查

按指定 Android Build Shell 使用 E 盘 Gradle 和临时目录，执行 assembleDebug、assembleDebugAndroidTest、assembleRelease。日志位于 `build/verification/visual378/`。

最终构建日志为 `build/verification/visual378/lighter-build.log`，结果 BUILD SUCCESSFUL（2m 9s）。`git diff --check` 通过。

## 小米 17 真机结果

设备 77079774 / 25113PN0EC。用户允许重试后，独立测试应用与测试包均安装成功。仅使用该真机，未使用模拟器代替。

执行：`adb -s 77079774 shell am instrument -w -e only visual378 com.kongye.docs.audit.test/com.kongye.docs.RegressionInstrumentation`。

最终结果：**ALL 8 VISUAL CHECKS PASSED**，日志 `build/verification/visual378/xiaomi-lighter-results.txt`。

- 局部动作保持刚性旋转与固定轴心，逗猫棒端点连接不脱开。
- 三个 SVG 姿态有局部动作，猫身像素不变。
- 呼噜、逗猫棒、小老鼠猫身高度、左侧位置与爪子基线一致。
- 泡泡与三个 z 的放大淡出周期正确。
- 实际绘制的泡泡与 z 像素随时间变化，猫背固定。
- 小米变量字重加载成功，原系统加粗样本深色像素 5804，修正后 7452，且低于上一轮 600 轴值的 8578；普通正文与代码字体设置保持原值。
- 实际桌面两列卡片显示缩小后的标题、日期与预览。
- 实际编辑器标题、行内加粗、展开键盘与导出图片正常；执行加粗、撤销与重做烟雾检查。

截图保存在 `build/verification/visual378/lighter-screenshots/`：`cats-0.png` 至 `cats-4.png`、`snore-effect-before.png` / `snore-effect-after.png`、`desktop-cards.png`、`editor-bold.png`、`editor-bold-keyboard.png`、`font-system.png` / `font-corrected.png`、`export-bold.png`。

一加风格以用户截图作为视觉参考，本次未连接一加进行同机对照。测试使用独立包和临时样例文档，没有向正式版写入测试文档。

初次安装曾被手机 USB 安装限制阻止；重试已解除。测试中修正了呼噜猫基线参数与无障碍窗口读取配置，最终结果以上述通过日志为准。

## 正式 APK 与安装

- 产物：`build/verification/KongyeDocs-3.7.8.apk`。
- SHA-256：`11DF70E6E1EFE4226B53AC210E075D9B8D1ADF49B19661B51BECDE85167A7F37`。
- 签名 SHA-256：`5dadaf6d9507a878df0b175581254caeb608fdf004f1af44aed0c8d40f1f8da6`，与已有安装一致。
- 小米 17 正式包覆盖安装成功，已启动；`dumpsys package` 确认版本 `3.7.8 / 75`。
- GitHub 发布使用以上最终 APK，保留此 SHA-256 供下载核对。
