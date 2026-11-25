# 3.7.10 网页保存确认与完整字段收集

日期：2026-11-25。版本 3.7.10 / 77，基于 3.7.9。

## 行为

- 保存确认页明文展示账号、密码和附加字段，便于核对；窗口仍保留 FLAG_SECURE。
- 附加字段可改名、编辑、勾选保留或移除；“保存后隐藏 / 保存后显示”只控制入库后的隐藏设置，确认页继续显示明文。
- 密码空间中的密码仍默认隐藏，附加字段沿用其 secret 设置。
- 系统自动填充收集浏览器或 App 提供的可读取输入项，包括邮箱、手机、其他文本、空字段，以及可读取的选择项、勾选项和日期；隐藏网页控件不会采集。
- 多步骤登录保留前面获取的信息，同类字段分配不同名称，避免互相覆盖；确认密码、验证码等也可在保存前自行移除。
- 用户名与主账号相同的重复项不另存；邮箱、手机不因内容与主账号相同而丢失。
- 密码不变但有新增字段内容时仍可确认补充；空白可选字段不会单独导致重复提示，已有字段的隐藏偏好会保留。

## 构建

通过指定 Android Build Shell，使用 E 盘 Gradle 与临时目录构建 assembleDebug、assembleDebugAndroidTest、assembleRelease。

- 最终日志：`build/verification/credential3710/final-build.log`，BUILD SUCCESSFUL（1m 23s）。
- `git diff --check` 通过。
- APK：`build/verification/KongyeDocs-3.7.10.apk`。
- SHA-256：`6CB5919DCDE4F23826E10EE188929009A8994692F6EDBEA0C2DFC9B269B3CD18`。
- 签名 SHA-256：`5dadaf6d9507a878df0b175581254caeb608fdf004f1af44aed0c8d40f1f8da6`。

## 验证

复用已连接的一加 PHK110 / 525114fb，在独立测试应用及合成数据目录执行 `only=credential-save`。未启动新模拟器，未修改系统自动填充服务设置。

最终日志：`build/verification/credential3710/oneplus-results.txt`，**ALL 5 CREDENTIAL SAVE CHECKS PASSED**。

- 注册字段模型保留用户名、邮箱、手机、公司、确认密码、验证码和空字段；主密码的前后空格保持原值。
- 多步骤、重复邮箱和非文本选项的模型检查通过，非文本勾选项不会误当成主账号。
- 已保存内容与空白可选项不会反复触发补充提示，有新增值时能识别为需要补充。
- 真实保存确认页可读到合成账号、密码及标为保存后隐藏的邮箱明文；FLAG_SECURE 保持开启，初次打开不弹键盘。
- 真实界面完成核心字段改名、附加字段改名、添加内容、移除内容与保存；入库后验证隐藏偏好为 true，已移除字段没有入库。

合成数据截图位于 `build/verification/credential3710/screenshots/`。截图时仅测试代码短暂关闭独立测试窗口的 FLAG_SECURE，随后恢复。

这组检查覆盖提取模型和真实保存界面；本轮未逐个实测所有浏览器的提交流程。可获取哪些控件，仍取决于浏览器或 App 向 Android 自动填充提供的页面结构。

## 安装

一加正式版覆盖安装成功，包管理器确认 `versionName=3.7.10`、`versionCode=77`。正式版未添加合成测试数据。GitHub 发布使用以上同一 APK 与 SHA-256。
