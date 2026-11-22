# 3.7.7 倒序字母索引

日期：2026-11-22，基于 3.7.6，版本 3.7.7 / 74。

- 将排序方向传给密码字母栏，倒序显示 #、Z、Y……A，正序显示 A、B……Z、#。
- 渲染字母、手指命中映射、空分组跳转和波浪圆点共用当前方向的索引列表；方向变化时重建手势处理，避免继续使用旧字母顺序。
- 保留列表分组顺序、倒序偏移、方向记忆、菜单箭头和滑动时隐藏新增按钮。

使用指定 Android Build Shell 执行 assembleDebug、assembleDebugAndroidTest、assembleRelease，日志位于 `build/verification/vault377/build.log`。

小米 17 / 77079774 的独立测试包执行 vault-sort，使用虚构条目检查六种排序和方向记忆。新增检查：倒序栏 # 在 Z 上方、Z 在 A 上方；从顶部开始连续滑动到 #、Z 和 A 均定位正确；正序恢复后栏顺序同步恢复；保存倒序波浪截图。结果与截图位于 `build/verification/vault377/`。

正式包 `build/verification/KongyeDocs-3.7.7.apk`，同签名 install -r 安装，不清除现有数据；签名、摘要和安装确认保存在同目录 delivery-package.txt。
