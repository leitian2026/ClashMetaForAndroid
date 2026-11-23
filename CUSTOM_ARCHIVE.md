> **旧文档，已冻结：不要往里写任何内容，改代码和写文档不要照着它做。合并上游时可以查它，但先看新文档：`docs/v2/`（`upstream.md`、`rules.md`、`changes.md`）。**

# CUSTOM_ARCHIVE.md — 已被取代的自定义条目（仅留作记录）

这里的条目内容已被 `CUSTOM.md` 里后面的条目取代，不再代表现行做法。编号保持原样，代码注释里的 `CUSTOM.md #N` 如果指向这里的编号，来这里找。

## 88. 配置检查页操作按钮分组
- **布局：** 域名输入框与“ECH检查”保持原位；下一行并排放置“检查 DNS”和“测试其他 IP”，归为网络测试；再下一行并排放置“查看有效配置”和“清理缓存”，归为辅助操作。
- **视觉：** 四个按钮继续使用原有 `Widget.MaterialComponents.Button.TextButton`，沿用 App 主题、默认尺寸和点击反馈；不增加自定义背景、颜色、圆角、图标或新控件样式。仅调整按钮分组和顺序。
- **保留：** 所有按钮 ID、点击回调、请求参数、检查逻辑和结果显示不变；域名输入、开始检查、顶部复制按钮及下方结果区域不变。
- **功耗 / 网络：** 仅调整静态 XML 排列，不增加监听、定时器、轮询、网络请求或后台任务。
- **改动文件：** `design/src/main/res/layout/design_config_check.xml` 和本登记文件；没有修改上游文件总表中的挂钩。
- **验证：** 完成 XML 结构与按钮 ID / 回调源码检查；未运行 Android 编译或真机验证。按仓库约定由用户手动触发 Build Debug；不会自动触发构建。


## 90. 配置检查页说明文字折叠与域名输入框方框化（r106）
- **说明文字：** 顶部 `check_hint` 默认只显示一行并以省略号收尾；点击后展开全文，再点收起。使用可点击背景提供涟漪反馈。展开状态只保存在当前页面实例内，退出页面后不保存；原文字和字符串资源不变。
- **域名输入框：** 原有 `check_domain_view` 改为 `TextInputLayout` + `MaterialAutoCompleteTextView`，采用 `Widget.MaterialComponents.TextInputLayout.OutlinedBox`。原 `check_domain_hint` 从输入控件移到外层 `TextInputLayout` 的 `hint`，字符串本身不改。保留输入框 ID、`textUri`、单行、自动补全阈值、历史下拉和偏好读写；外层行原有水平 padding 不变，不额外增加上下 / 左右间距。
- **严格保留：** 所有按钮的位置、尺寸和点击回调；域名当前值及历史记录在 `prefs` 中的读写；#87 最近一轮 IP 测试结果保存与恢复；ECH、DNS、IP 测试逻辑和结果显示；其他布局及文字内容。没有增加定时器、轮询、额外监听或后台工作。
- **改动文件：** `design/src/main/res/layout/design_config_check.xml`、`design/src/main/java/com/github/kr328/clash/design/custom/ConfigCheckDesign.kt` 和本登记文件。两个代码文件均为自定义功能文件，没有修改上游文件总表中的挂钩；上游文件总表不变。
- **验证：** 完成源码级检查：说明文字点击切换逻辑、Material 控件层级、原输入框 ID / `textUri` / 历史适配器与 prefs 调用均保留。未运行 Android 编译或真机验证，因此 ViewBinding 生成类型及 Kotlin 编译、历史下拉实际弹出、软键盘布局、浅色 / 深色主题边框与提示色均**未实测**；需手动触发 Build Debug，并按上述项目在真机检查。
