# 3.5.12 验证记录

日期：2026-11-20

## 安装包

- 应用：空叶文档，包名 `com.kongye.docs`。
- 版本：3.5.12，versionCode 64。跳过已撤回的 3.5.11 / 63。
- 文件：`E:/Claude/KongyeDocs-3.5.12.apk`。
- SHA-256：`b8402190f3a016a1fdd72a6b72bc030e981afffb526294acc60edc38aacf026c`。
- 签名 SHA-256：`5dadaf6d9507a878df0b175581254caeb608fdf004f1af44aed0c8d40f1f8da6`，与 3.5.10 一致。

## 构建与静态检查

- `:app:assembleDebug :app:assembleDebugAndroidTest` 通过。
- `:app:assembleRelease` 通过，包含正式包压缩和 lintVital 检查。
- `git diff --check` 通过。
- SearchOverlay.kt 与当前 main 完全一致，未恢复已撤回的搜索修改。

## 行为回归

在独立 Android API 37 模拟器（emulator-5590）中运行：

`am instrument -w -e only editor3512 com.kongye.docs.audit.test/com.kongye.docs.RegressionInstrumentation`

结果：`ALL 11 EDITOR 3.5.12 CHECKS PASSED`。

1. H1/H2/H3、引用、代码、有序/无序/待办列表在段首退格时合并前文，保留当前段落类型、缩进和文字样式，支持撤销、重做。首段没有前置换行，保持原格式且不产生撤销记录。
2. 剪贴板的 content URI 图片通过系统粘贴命令插入，正确拆分前后文字，并可一步撤销及重做。
3. 正文 EditorInfo 声明 image/*，真实 InputConnection 接受 commitContent 图片（flags=0），键盘保持展开。
4. 图片边缘光标可继续粘贴图片。
5. InputConnection.deleteSurroundingText 在标题段首合并前文并保留标题格式，键盘保持展开。
6. 硬件退格键使用相同的段落边界删除规则。
7. 固定面板后切换三个段落并批量加序号，面板保持展开、键盘保持收起。
8. 固定面板时双击仍可选择文字。
9. 取消固定后单击正文，关闭面板并展开键盘。
10. 面板右侧键盘按钮可直接返回输入。
11. 未固定面板时双击正文，在第一下切回键盘后仍可识别第二下并选字。

测试中的触摸直接派发到真实 Activity 窗口，避免 API 37 模拟器的 starting_reveal 动画等待将单击延迟成长按。键盘行为通过真实输入连接与窗口 IME Insets 验证。本次未连接实机，不表示已覆盖所有手机和输入法。

## 实现说明

- 图片在后台导入，使用现有图片存储和结构化粘贴流程；位置或内容在读取过程中改变时提示重新粘贴，避免覆盖新的编辑内容。取消任务也会释放输入法授予的图片读取权限。
- 面板默认不固定。图钉按钮可保持面板，用于连续调整多个段落；取消固定后，单击正文恢复键盘。双击手势状态在面板与键盘切换时保留。
- 安装验证：已覆盖安装到一加 PHK110，安装结果为 Success，确认 versionName=3.5.12、versionCode=64。发布附件与实机安装 APK 使用上述相同 SHA-256。
