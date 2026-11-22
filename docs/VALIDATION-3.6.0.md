# 空叶文档 3.6.0 发布验证

日期：2026-11-22。

## 安装包

- 包名 `com.kongye.docs`，版本 `3.6.0`，versionCode `66`。
- 文件 `build/verification/KongyeDocs-3.6.0.apk`，2,246,870 字节。
- SHA-256 `49b0e87c91fcb921601ea337fe29e003683ac164b141a6db2fb02ef8586236d2`。
- 沿用原签名，签名证书 SHA-256 `5dadaf6d9507a878df0b175581254caeb608fdf004f1af44aed0c8d40f1f8da6`。
- 已在小米 17（Xiaomi 25113PN0EC）覆盖安装，返回 Success，核实版本 3.6.0 / 66，保留应用数据并启动正式应用。

## 修改范围

- 复制成功沿用原来居中的深灰对勾提示。提示归属当前最前的应用窗口，详情面板或对话框不会将它遮住，保留面板内复制状态。
- 替换打呼噜、逗猫棒、小老鼠三个 SVG，原始路径、线宽、颜色及裁切保持一致，仅添加图层角色标记。
- 动画采用局部刚性旋转：猫爪、尾巴、鼻泡、小老鼠与逗猫棒轻摆。猫身固定，鱼线和鱼跟随棒端保持连接，不做整体动态缩放和上下浮动。
- 抓老鼠和逗猫棒依据猫身路径对齐常规姿态的 64 单位高度、x=30 左缘及 y=103 落脚线。采用静态等比换算，玩具不参与决定猫身大小。
- 保留 3.6.0 已有布局调整、本地备份并行与流式校验改进。未重新引入已撤回的搜索返回行为修改。

## 构建

通过 `E:/Vibe Coding/Android/Open Android Build Shell.cmd` 启动，在本工程运行 Gradle，缓存及临时文件使用 `E:/Vibe Coding/Android`。

`assembleDebug`、`assembleDebugAndroidTest`、`assembleRelease` 全部通过，包含正式版压缩与 lintVital。源码通过 `git diff --check`；导入的原始 SVG 保留其行末空格，单独排除素材文本的空白检查。

## 小米 17 真机检查

独立测试包 `com.kongye.docs.audit` 使用测试数据，测试不读取正式版密码库。

### 前景提示和小猫：9 项通过

命令参数 `only=foreground-toast-cats`，结果 `ALL 9 FOREGROUND TOAST AND CAT CHECKS PASSED`。

- 各局部动作保持刚性旋转、固定锚点及棒端连接。
- 三个姿态的局部像素变化，猫身区域像素固定。
- 逗猫棒和抓老鼠的猫身高度、位置及落脚线与常规姿态对齐。
- 短详情和 18 个多行字段的高详情面板中，复制提示的窗口层级高于面板，截图确认可见。
- 提示显示时下层按钮仍可操作，嵌套对话框显示前景提示，关闭后回到主窗口提示宿主。
- 三个 SVG 的浅色、深色模式、眼部裁切和虚线绘制通过。
- 真实“我的”页常规、逗猫棒、抓老鼠及夜间睡觉姿态截图已检查。
- 复制保持 `Toast.success=true`，使用原版居中对勾样式。

日志 `build/verification/xiaomi17-cat-size-results.txt`，截图 `build/verification/toast-cat-360/`。

### WebDAV 和本地备份：6 项通过

命令参数 `only=fixed-dav`，结果 `FIXED DAV CHECKS PASSED`。

三种旧目录恢复、新备份统一目录、计划备份、本地备份与被阻塞云端上传并行、云端设置不显示目录输入项均通过。

日志 `build/verification/release360-fixed-dav-results.txt`。

### 密码变更备份：5 项通过

命令参数 `only=vault-change-backups`，结果 `VAULT CHANGE BACKUP CHECKS PASSED`。

新增/修改/删除分别保留快照；读取和无实际更改不触发；删除最后一条密码仍保存加密空库；云端失败持久化并重试且不重复本地备份；未设置备份密码时明确报错。

日志 `build/verification/release360-vault-backup-results.txt`。

以上真机验证不代表已覆盖所有设备和输入法。
