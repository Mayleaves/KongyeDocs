# 3.7.9 本地版本图片与编辑顶栏

日期：2026-11-24，基于 3.7.8，版本 3.7.9 / 76。

## 修改

- 修改对比按正文顺序显示图片，图片按文件标识作为完整对象比较，支持新增、删除、替换和重复引用。
- 保留原有文字差异配色；图片差异使用相同方向的绿色 / 红色边框与文字标签，缺失文件显示占位提示。
- 图像解码在后台执行，列表按需加载；文档预览仍显示所选本地版本的原始内容。
- 编辑顶栏提示按屏幕中心对齐，右侧按钮使用统一容器与垂直中心。窄屏且多个操作图标同时显示时简化为“自动保存”，避免遮挡。

## 构建与校验

通过指定 Android Build Shell 构建 assembleDebug、assembleDebugAndroidTest、assembleRelease。日志：`build/verification/visual379/build.log`，BUILD SUCCESSFUL（2m 23s）。

- 正式 APK：`build/verification/KongyeDocs-3.7.9.apk`。
- SHA-256：`A9477E21E0F00C1640FD3255760531B4BCD0C20F1BD40359A38538F906C69635`。
- 证书 SHA-256：`5dadaf6d9507a878df0b175581254caeb608fdf004f1af44aed0c8d40f1f8da6`。
- `git diff --check` 通过。

## 真机验证

专项入口：`only=visual379`，测试使用独立应用与合成文档、图片。

索尼 A202SO 在安装前断开，后续使用小米 17 / 77079774。用户允许 USB 安装后，测试包安装成功。

执行：`adb -s 77079774 shell am instrument -w -e only visual379 com.kongye.docs.audit.test/com.kongye.docs.RegressionInstrumentation`。

最终结果：**ALL 6 VERSION IMAGE AND TOP BAR CHECKS PASSED**，日志 `build/verification/visual379/xiaomi-results.txt`。

- 相同图片、原始文字、emoji、字面“[图片]”与私用区字符均保留。
- 图片新增、删除、替换与重复引用正确区分。
- 实际版本对比画面同时出现当前图片、所选版本图片的像素，并使用尚未落盘的编辑正文作为比较基线。
- 切换文档预览后仅显示所选本地版本的图片。
- 缺失图片文件显示占位提示，后续正文继续显示。
- 四种置顶 / 收藏组合下，可见提示文字的中心均为 x=610，与 1220 像素屏幕中心一致；右侧图标中心高度相差不超过 1 像素，文字不与操作重叠。

截图与测量记录：`build/verification/visual379/screenshots/`，包括 `version-image-comparison.png`、`version-document-preview.png`、`missing-image-fallback.png`、`editor-top-bar-00.png` / `10.png` / `01.png` / `11.png` 和 `top-bar-measurements.txt`。

测试期间修正了样例图片文件名，以及自动测量的字形裁切范围。最终按截图中的实际字形测量居中位置，未以不完整的无障碍文字边界代替可见内容。

## 安装

小米 17 正式版覆盖安装成功并已启动，包管理器确认 `versionName=3.7.9`、`versionCode=76`。测试使用独立数据目录，未向正式版添加测试文档。GitHub 发布使用以上同一 APK 与 SHA-256。
