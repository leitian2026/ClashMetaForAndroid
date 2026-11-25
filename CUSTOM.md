> **旧文档，已冻结：不要往里写任何内容，改代码和写文档不要照着它做。合并上游时可以查它，但先看新文档：`docs/v2/`（`upstream.md`、`rules.md`、`changes.md`）。**

基线：上游 `1a4f949213fcbb7f0c9e2a4a21138642d84b8f22`（版本 2.11.36）。
当前版本：2_11_46-r406
已归档（内容已被后面的条目取代，仅留作记录）：#88（被 #89 取代）、#90（被 #91 #93 取代），见 `CUSTOM_ARCHIVE.md`。代码注释里写着这两个编号的，去那个文件里找。
所有挂钩处在代码里都带 `CUSTOM` 注释，可用 `grep -rn CUSTOM` 找全。
规则和同步上游的步骤见 `AGENTS.md`（开头有“动手前清单”）。每次改动都要同步更新登记，写在哪里见下面“怎么找”和 `AGENTS.md` 规则 7。

## 怎么找（这个文件只是索引，详情在 `docs/custom/`）
- 改某个**上游文件**：在下面“上游文件总表”里找那一行，按行里列的 #N 去读详情。
- 改某个**功能**：在下面“功能一览”里找 #N，读 `docs/custom/<N 补零到 3 位>.md`（#7 → `007.md`，#109a → `109a.md`，#156 → `156.md`）。
- 代码里看到 `CUSTOM.md #N` 注释：同上，编号就是文件名。被归档的 #88、#90 在 `CUSTOM_ARCHIVE.md`。
- 只知道文件名：`grep -rl 文件名 docs/custom`，命中的详情和 `docs/custom/history/` 里的记录都要看。
- #156（已保存 IP 清单）及其后面的分步记录、IP 测试审查修正，按时间原样放在 `docs/custom/history/`，见最后的“修改记录”表。这些只记录当时改了什么，不一定是现在的样子。
- 只读和这次要改的东西相关的文件，不要通读整个 `docs/custom/`。登记要写在哪里、大小上限，见 `AGENTS.md` 规则 7、规则 9 和 #157。

## 上游文件总表（同步上游时冲突只会出现在这里）
| 上游文件 | 对应条目 | 冲突风险 | 冲突时怎么办 |
|---|---|---|---|
| `.github/workflows/build-debug.yaml` | #2 #3 #4 #5 #14 #18 #20 #40 #54 | 中（`on:` 段、Build 步骤） | 以上游为底，重加：只留 `workflow_dispatch`、预下载 Gradle、预下载 geo 文件并加 geo 文件缓存兜底（#14 #18）、签名步骤、arm64 参数（含 `-x :app:downloadGeoFiles`）与上传、`Checkout submodules` 之后的 `Apply mihomo patches` 步骤（#20）、上传前的 `Artifact name` 步骤和上传步骤里的 `name:`（#40）、`Apply mihomo patches` 前的 `Remove deleted custom files` 步骤（#54） |
| `.github/workflows/build-pre-release.yaml` | #2 #3 | 中 | 以上游为底，重加：只留 `workflow_dispatch`、预下载 Gradle |
| `gradle.properties` | #175 | 低 | 保留末尾的 `kotlin.daemon.jvmargs=-Xmx3g`；#175 拆分长函数后，Build Debug 已验证无需额外 `-Xss`。 |
| `core/src/main/golang/native/tunnel.go` | #1 #7 #37 #147 | 低 | 重加 `ipdelay.TestGroup(name)` 和 `queryTunnelState` 里的 current 字段（`CurrentSet()`、`currentNode` / `currentIp` / `currentCountry` / `currentIps` / `currentMore`），以及 `reach` 字段（`ipdelay.Reach(node)`，#37）；#147 追加 `reachReason` / `reachAt` 两个 JSON 字段 |
| `core/src/main/golang/native/connections.go` | #8 #15 #101 | 低 | 重加 `queryCustomConnections` 的自定义分派和 `closeCustomConnection` 的自定义关闭分支；#101 在 `CANCEL_IPTEST` 前加 `CANCEL_CHECK` 分支，调用 `conftest.CancelCheck()` |
| `core/src/main/golang/native/tunnel/proxies.go` | #1 #55 | 低 | 重加 `IPDelays` 字段和填充行 |
| `core/src/main/golang/native/config/load.go` | #1 #22 | 低 | 重加 `ipdelay.CaptureConfig(...)`（#1）和它后面的 `ipdelay.CaptureDNS(rawCfg.DNS)`（#22） |
| `core/src/main/golang/native/delegate/init.go` | #7 | 低 | 重加 `ipdelay.InstallSocketHook(home)`（必须在上游给 `DefaultSocketHook` 赋值之后；`home` 是 `Init` 的第一个参数，用来定位 #10 #11 的小文件） |
| `core/src/main/java/com/github/kr328/clash/core/model/Proxy.kt` | #1 | 低 | 重加 `ipDelays` 参数 |
| `core/src/main/java/com/github/kr328/clash/core/model/TunnelState.kt` | #7 #37 #147 | 低 | 重加 `current*` 参数（`currentNode` / `currentIp` / `currentCountry` / `currentIps` / `currentMore`）、`CurrentIp` 和 `ipsText()`，以及 `reach: Int = 0`（#37）、`reachReason` / `reachAt`（#147） |
| `design/src/main/java/com/github/kr328/clash/design/component/ProxyView.kt` | #1 #12 #55 #151 | **高**（改了 `onMeasure` / `onDraw`） | 以上游新版为底，按 #1 表格的描述重做 IP 行的高度和绘制，再按 #12 加最后一行"N 分钟前测速"，不要硬合 |
| `design/src/main/res/layout/design_main.xml` | #7 #8 #20 #21 #22 #32 #53 #168 | #168：运行中状态卡片置于当前 IP / 问题卡片上方 **高**（App 名字 `TextView` 外包了一层 `LinearLayout`；代理卡片改了 `subtext` 并去掉 `visibility`） | 以上游新版为底，按 #7 重做：新增变量 `customIp`、包住 App 名字并加红字 `custom_geosite_notice`、代理卡片 `subtext` 改成带 `customIp`、删掉代理卡片的 `visibility`；#8 在"日志"标签前加"连接"标签，#20 在它后面再加"底层连接"标签（`Request.OpenMux`），#21 在"日志"标签前再加"错误与诊断"标签（`Request.OpenErrors`），#22 在"错误与诊断"标签前再加"配置检查"标签（`Request.OpenConfigCheck`）；#32 在顶部（logo + App 名字）那一行末尾加右上角的 `ImageView`（`@+id/custom_more`，三点图标，48dp）；同时把包住 App 名字的 `LinearLayout`（#7 加的那层）改成 `layout_width=0dp` + `layout_weight=1`（r38，原来是 `wrap_content`，窄屏上名字占满宽度，三点被挤出屏幕） |
| `design/src/main/res/layout/design_proxy.xml` | #169 | 低 | 顶部工具栏新增 `carrier_switch_view`，位于 ⚡ 左侧；返回栏的 `layout_toStartOf` 改为指向新图标；底部悬浮测速按钮不变。
| `design/src/main/java/com/github/kr328/clash/design/MainDesign.kt` | #7 #8 #20 #21 #22 #32 #53 #146 | 低 | 重加 `customCards`、`setCustomCards()`；#146 `setCustomIssues(IssuesDisplayState)`；`Request` 枚举加 `OpenConnections`（#8）、`OpenMux`（#20）、`OpenErrors`（#21）、`OpenConfigCheck`（#22）；#32 重加 `customMenu = MainCustomMenu(context, binding.root)` 和它的 import |
| `design/src/main/java/com/github/kr328/clash/design/ProxyDesign.kt` | #169 | 低 | 增加 `carrier_switch_view` 点击挂钩，通过 `setClassName` 打开运营商映射设置页；运营商入口、测速入口和更多菜单另有三处长按提示。业务逻辑放在新建的 `CarrierSwitchActivity` / `CarrierSwitchDesign` / 服务侧 `CarrierSwitch`。
| `design/src/main/java/com/github/kr328/clash/design/MetaFeatureSettingsDesign.kt` | #6 #16 | 低 | 重加 `geoSiteDownload()` 一行（#16 起它一次加 GeoSite / GeoIP / ASN 三块，不用再改这里） |
| `app/src/main/java/com/github/kr328/clash/MainActivity.kt` | #6 #7 #8 #20 #21 #22 #35 #53 | 低 | 重加 `fetch()` 里的 `setCustomCards(...)`、`customNodeIpEvents()` 和 `select` 里的 `customNodeIp.onReceive` 分支；`OpenConnections` 分支（#8）、`OpenMux` 分支和 import（#20）、`OpenErrors` 分支和 import（#21）、`OpenConfigCheck` 分支和 import（#22）；#35 流量定时器那一支的条件由 `clashRunning` 改成 `clashRunning && activityStarted` |
| `service/src/main/java/com/github/kr328/clash/service/clash/module/DynamicNotificationModule.kt` | #7 #35 | 低 | 通知正文改用 `NodeIpText.get(service)`（原来是上传/下载速度），去掉不再用的 `queryTrafficNow`；加 `nodeIpChanged` 通道、`NodeIpNotifier.start`、一个 `select` 分支；#35 加属性 `lastPosted`，`update()` 里文字没变就不 `notify`，定时器 1 秒改 5 秒，亮屏和换配置时清 `lastPosted` |
| `service/src/main/java/com/github/kr328/clash/service/clash/module/SuspendModule.kt` | #53 | 低 | 复用已有亮灭屏广播，在启动、屏幕状态变化和配置载入后通过 `SORT_ISSUES` 推送 `isInteractive`；不新增屏幕监听 |
| `service/src/main/java/com/github/kr328/clash/service/ClashManager.kt` | #7 #8 #15 #158 #169 | 低 | `queryTunnelState()` 开头加 `NodeIpNotifier.start(context)`；加 `queryCustomConnections(sort: Int, filter: String)` / `closeCustomConnection(id: String)` 两个实现（#8 #15）；#158 通过现有 `Clash.subscribeLogcat()` 和 `ILogObserver` 转发下载进度事件；#169 增加 `applyCarrierSwitch()`，调用服务侧同一套运营商映射逻辑。
| `service/src/main/java/com/github/kr328/clash/service/remote/IClashManager.kt` | #8 #15 #158 #169 | 低 | 重加 `queryCustomConnections(sort: Int, filter: String): String` 和 `closeCustomConnection(id: String): Boolean`；#158 增加带 `ILogObserver` 参数的下载进度请求，复用既有 logcat 事件接口；#169 增加设置保存后应用当前缓存映射的远程方法。
| `app/src/main/AndroidManifest.xml` | #8 #17 #20 #21 #22 #46 #156 #169 | 低 | 在 `LogsActivity` 前重加 `.custom.ConnectionsActivity`（#8）、`.custom.BackupActivity`（#17，**必须带** `android:theme="@style/BackupTranslucentTheme"`，否则页面不透明，对话框后面是黑的）、`.custom.MuxActivity`（#20）、`.custom.ErrorsActivity`（#21）、`.custom.ConfigCheckActivity`（#22）、`.custom.SavedIpActivity`（#156，标签 `@string/saved_ip_title`）和 `.custom.EditorActivity`（#46，**必须带** `configChanges` 里的 `orientation|screenSize|...`，否则转一下屏页面就重建，没保存的文字丢了）的 `<activity>`；#169 登记 `.custom.CarrierSwitchActivity` |
| `app/src/main/java/com/github/kr328/clash/FilesActivity.kt` | #46 #50 | 低 | `OpenFile` 分支：`EditorActivity.canOpen(...)` 为真就 `startActivity(EditorActivity.intent(..., uuid, "", profile.type != Profile.Type.File))`，否则还是原来的外部编辑器；顶部 `import ...custom.EditorActivity` |
| `design/src/main/res/layout/adapter_profile.xml` | #47 #53 #55 | 中（改了时间那个 `TextView` 的位置） | 以上游新版为底：`<data>` 里加变量 `edit`；时间 `TextView`（`elapsed_view`）`layout_marginEnd` 改 20dp、`layout_toStartOf` 改成 `@id/edit_view`；在它和那条 2dp 竖线之间加 `FrameLayout`（`@+id/edit_view`，铅笔图标 `ic_baseline_edit`，`onClick="@{edit}"`） |
| `design/src/main/java/com/github/kr328/clash/design/adapter/ProfileAdapter.kt` | #47 | 低 | 构造函数末尾加带默认值的 `onEditClicked`，`onBindViewHolder` 里加 `binding.setEdit { onEditClicked(current) }` |
| `design/src/main/java/com/github/kr328/clash/design/ProfilesDesign.kt` | #47 | 低 | `Request` 加 `EditConfig(profile)`；创建 `ProfileAdapter` 时多传一个 lambda（`requests.trySend(Request.EditConfig(it))`） |
| `app/src/main/java/com/github/kr328/clash/ProfilesActivity.kt` | #47 #50 | 低 | `when` 里加 `EditConfig` 分支：`startActivity(EditorActivity.intent(this@ProfilesActivity, "${uuid}/config.yaml", "config.yaml", uuid, name))`（注意 select 里 `this` 不是 Activity）；加 import |
| `core/src/main/cpp/CMakeLists.txt` | #7 | 低 | `add_library` 源文件末尾加 `custom_bridge.c` |
| `core/src/main/golang/go.mod` | #79 #80 | **中**（HTTP/3 / MTU 依赖由间接依赖提升为直接依赖） | 以上游版本的依赖选择为准，将 `github.com/metacubex/http`、`github.com/metacubex/quic-go`、`github.com/metacubex/tls` 和 `golang.org/x/sys` 从间接依赖提升到直接 `require`；保持已核对的版本（`http v0.1.8`、项目锁定的 `quic-go`、`tls v0.1.9`、`x/sys v0.30.0`），不擅自升级。若上游更新这些依赖，先重新核对 `http3` API 和模块版本，再同步调整 `go.sum`。 |
| `app/src/main/java/com/github/kr328/clash/MainApplication.kt` | #16 #21 | 低 | `onCreate()` 里 `extractGeoFiles()` 后重加一行 `GeoFileKeeper.restoreAfterUpdate(this)`（#16），在 `onCreate()` 里 `val processName` 之前重加 `CrashRecorder.install(this)`（#21）（及 import） |
| `design/src/main/java/com/github/kr328/clash/design/AppSettingsDesign.kt` | #17 | 低 | `preferenceScreen` 块末尾（"服务"那个开关之后）重加一行 `customBackup()` |
| `service/src/main/java/com/github/kr328/clash/service/ProfileProcessor.kt` | #27 #28 #56 #169 #170 | 低 | `apply()` 里两处：① `fetchProfile(...)` 外面包 `try { ... } catch (e: Exception) { throw ConfigErrorText.explain(context, context.processingDir, e) }`（#27，保存失败时报出是哪一行）；② 在 `context.importedDir.resolve(snapshot.uuid.toString()).deleteRecursively()` 前一行加 `if (snapshot.type == Profile.Type.File) ConfigHistory.keep(context, snapshot.uuid)`（#28 #56，只给自己编辑的配置记下被替换的和新的版本）；`delete()` 里清理 `ConfigHistory`（#56）、`ConfigUpdateSnapshot`（#170）后调用 `CarrierSwitchStore.delete(context, uuid)` 清理同 UUID 的运营商映射（#169）；加对应 import |
| `app/src/main/java/com/github/kr328/clash/LogsActivity.kt` | #31 | 低 | `setContentDesign(design)` 后加一行 `LogLevelControl.attach(this, design)`；加一个 import |
| `design/src/main/res/layout/design_logs.xml` | #31 | 低 | 在 `delete_view`（三横线）左边加 `log_level_view`（r34 起是显示当前级别文字的 `TextView`，原来是图标 `ImageView`）；标题栏的 `layout_toStartOf` 改指向它 |
| `design/src/main/java/com/github/kr328/clash/design/OverrideSettingsDesign.kt` | #31 | 低 | 删掉"日志级别"那一个 `selectableList`（换成一行 CUSTOM 注释）；入口搬到日志页 |
| `build.gradle.kts` | #44 | 低（只加一个 `customRev` 和两处 `versionNameSuffix`） | 重加 `queryConfigProperty` 后面的 `customRev`（读 `CUSTOM.md` 的"当前版本："那一行，取最后的 `r<数字>`），`release` 构建类型里的 `versionNameSuffix = customRev`，`debug` 构建类型里的 `versionNameSuffix` 改成 `".debug$customRev"`（后缀必须放在构建类型里，才会排在变体后缀 `.Alpha` / `.Meta` 的后面） |
| `design/src/main/res/layout/design_about.xml` | #44 | 低 | 内层竖向 `LinearLayout`（App 名字和版本号那一层）的 `layout_width` 由 `match_parent` 改成 `0dp`，加 `layout_weight=1`，版本号太长时才会换行 |
| `service/src/main/java/com/github/kr328/clash/service/ProfileWorker.kt` | #53 | 低 | `completed()` 和 `failed()` 末尾各加一行 `IssueNotifier.subscription(this, name, null / reason)` |
| `design/src/main/java/com/github/kr328/clash/design/view/LargeActionCard.kt` | #57 | 低 | `subtext` 的 setter 里，`binding.subtextView.text = value` 改成 `binding.subtextView.text = com.github.kr328.clash.design.custom.CheckMark.paint(context, value)`（首页代理卡片的 ✓ 绿 / ✗ 红） |
| `design/build.gradle.kts` | #87 | 低 | 增加 `libs.kotlin.serialization.json` 依赖，供配置检查页将最近一轮 `IpTestReport` 编码 / 解码为本地 JSON；不改插件、构建任务或其他依赖 |

共 39 个上游文件（含 `FilesActivity.kt`、`build.gradle.kts`、`design_about.xml`、 `ClashManager.kt`、`IClashManager.kt`、`AndroidManifest.xml`、`CMakeLists.txt`、`MainApplication.kt`、`AppSettingsDesign.kt`、`ProfileProcessor.kt`）。同步后可运行 `bash scripts/check-custom.sh`（#19）自动检查。同步后 `grep -rn CUSTOM` 应在这些文件（加 `custom/` 下新文件）里全部找到。

## 构建事实
- `build-debug.yaml` 实际执行 `app:assembleAlphaRelease`，**R8 已开**，不是"没混淆的 debug"。
- 无法本地编译，只能手动触发 Actions 验证；`build-release.yaml`、`update-dependencies.yaml` 没有改动。

## 本版没做的（决定不做）
网络切换自动改模式仍不做，按运营商切节点见 #169；按应用统计流量排行、节点历史用量与延迟趋势、GeoSite 只在不计费网络下更新：不做。网络切换后自动重测（#24）本来就有。

## 功能一览（每个编号一行；详情文件见最后一列）

| # | 内容 | 详情 |
|---|---|---|
| 1 | 代理页：每个 IPv4 的测速结果 | [001.md](docs/custom/001.md) |
| 2 | 构建改为手动 | [002.md](docs/custom/002.md) |
| 3 | 构建前预下载 Gradle（带重试） | [003.md](docs/custom/003.md) |
| 4 | 固定签名（仅 build-debug） | [004.md](docs/custom/004.md) |
| 5 | build-debug 只构建 arm64-v8a | [005.md](docs/custom/005.md) |
| 6 | GeoSite 按地址下载并安装 | [006.md](docs/custom/006.md) |
| 7 | 当前节点 IP + 国旗（首页代理卡片、状态栏通知；r32 起显示一次访问实际用到的所有 IP） | [007.md](docs/custom/007.md) |
| 8 | 连接列表页（首页"连接"；r157–r158 补充名称、说明与错误状态） | [008.md](docs/custom/008.md) |
| 9 | （已删除）首页配置卡片显示剩余流量和到期时间 | [009.md](docs/custom/009.md) |
| 10 | 节点 IP 使用记录 | [010.md](docs/custom/010.md) |
| 11 | 国家缓存持久化 | [011.md](docs/custom/011.md) |
| 12 | 测速结果标注时间 | [012.md](docs/custom/012.md) |
| 13 | GeoSite 下载成功后一键重载 | [013.md](docs/custom/013.md) |
| 14 | 构建前带重试地预下载 geo 文件（仅 build-debug） | [014.md](docs/custom/014.md) |
| 15 | 连接页：搜索、排序、全部关闭、少做无用功（r12） | [015.md](docs/custom/015.md) |
| 16 | 下载的 geo 文件更新应用后自动恢复；GeoIP / ASN 也能按地址下载（r12） | [016.md](docs/custom/016.md) |
| 17 | 备份与恢复（r12；订阅备份保存运营商映射并按 UUID 恢复；普通恢复合并已保存 IP，replace 恢复整体覆盖；恢复结果显示 IP 合并和运营商映射摘要；新增备份实现见 `BackupRestore.kt`、`BackupExtras.kt`、`BackupExtrasRestore.kt`、`BackupExtrasRestorePolicy.kt`、`SelectionBackup.kt`） | [017.md](docs/custom/017.md) |
| 18 | 构建时的下载：缓存和重试（仅 build-debug） | [018.md](docs/custom/018.md) |
| 19 | 自检脚本 `scripts/check-custom.sh`（含超大 Kotlin 文件告警，默认 2500 行，可用 BIG_FILE_LINES 调整；只告警不影响退出码） | [019.md](docs/custom/019.md) |
| 20 | 底层连接页：每个节点的底层连接数（多路复用有没有生效）（r16，r27 加每个 IP 的开始时间，r156 完善说明与截断提示，r211 改成分组布局并限宽，r327 列表字号缩小 2sp、说明文字不变） | [020.md](docs/custom/020.md) |
| 21 | 错误与诊断页（r18，r23 加折叠，r24 修折叠和清除） | [021.md](docs/custom/021.md) |
| 22 | 配置检查页（r20，r22 改为同配置只测一次） | [022.md](docs/custom/022.md) |
| 23 | 没有 IPv6 的网络里，IPv6 节点的测速超时不算错误（r25） | [023.md](docs/custom/023.md) |
| 24 | 网络切换后自动重测一次，并标明结果新旧（r25） | [024.md](docs/custom/024.md) |
| 25 | 配置检查页的 DNS 检查（r25） | [025.md](docs/custom/025.md) |
| 26 | DNS 检查的增强（r26，r32 重排显示） | [026.md](docs/custom/026.md) |
| 27 | 保存配置时说清是哪一行，以及"检查配置有没有错"（r26） | [027.md](docs/custom/027.md) |
| 28 | 配置历史：保留最近 10 个版本，对比，回滚（r26；**r68 起搬进编辑页并改了逻辑，见 #56**，下面写的是 r26 的旧样子） | [028.md](docs/custom/028.md) |
| 29 | 导出诊断包（r26） | [029.md](docs/custom/029.md) |
| 30 | 推算最终配置：你的配置上直接标出内核和 App 改了什么（r28，r29 修正归属和锚点，r31 改排序和颜色） | [030.md](docs/custom/030.md) |
| 31 | 日志级别搬到日志页（r30） | [031.md](docs/custom/031.md) |
| 32 | 首页右上角菜单：备份到坚果云 / 从坚果云恢复（多设备共用）（r35，r36 改；WebDAV 响应头、响应体和请求读取时间均有限制，上传上限 32 MiB） | [032.md](docs/custom/032.md) |
| 33 | 首页"网络状况"卡（r39，**r42 取消**） | [033.md](docs/custom/033.md) |
| 34 | 推送 custom 分支时的快速检查（r39） | [034.md](docs/custom/034.md) |
| 35 | 省电：通知栏、首页后台、noteUse（r40） | [035.md](docs/custom/035.md) |
| 36 | 云端备份：删除入口，恢复确认框里列出订阅名（r40） | [036.md](docs/custom/036.md) |
| 37 | 首页代理卡片显示"现在通不通"（r43） | [037.md](docs/custom/037.md) |
| 38 | 配置历史里标出"节点能连通"（r43） | [038.md](docs/custom/038.md) |
| 39 | 连接页写明"为什么直连 / 被拒绝"（r43） | [039.md](docs/custom/039.md) |
| 40 | 构建产物名带版本号和时间（r43） | [040.md](docs/custom/040.md) |
| 41 | 上游差异报告（手动，r43） | [041.md](docs/custom/041.md) |
| 42 | 自动用最快的 IP（内核补丁 0003，r44） | [042.md](docs/custom/042.md) |
| 43 | 测试其他 IP（配置检查页；输入历史、域名解析及测试框内逐行已保存 IP 提示；缓存 HashSet，LineHeightSpan + LineBackgroundSpan + UpdateLayout 触发即时重排，绘制 Paint 复用） | [043.md](docs/custom/043.md) |
| 44 | App 里的版本号带上 r 数字，关于页长版本号换行（r47） | [044.md](docs/custom/044.md) |
| 45 | 测 IP：实测确认是不是 Cloudflare，入口运营商 / 登记国家自动查询（r48；r58 起恢复自动查询，见 #52） | [045.md](docs/custom/045.md) |
| 46 | 内置文本编辑器：行号、YAML 高亮、撤销、查找替换、配置键补全（r49，r345 惰性读取补全行、按版本缓存名字表；撤销、重做和更多图标支持长按提示） | [046.md](docs/custom/046.md) |
| 47 | 配置卡片直达编辑入口；编辑器字体加减、保存 / 导入到手机任意位置（r51，r348 字号保存合并到手势结束 / onStop） | [047.md](docs/custom/047.md) |
| 48 | 编辑器滑动卡顿的根治：整份文本着色一次，滑动时不再着色（r52） | [048.md](docs/custom/048.md) |
| 49 | 编辑器惯性滚动：划一下松手后继续滑（r53；触摸结束仅在光标或文字变化时补偿滚动） | [049.md](docs/custom/049.md) |
| 50 | 编辑器：大文件分段、缩进参考线、括号引号配对、问题标记、显示空白字符、订阅提示（r56，r347 无标记时跳过差异绘制、无 Tab 时跳过字符扫描） | [050.md](docs/custom/050.md) |
| 51 | 编辑器保存即生效；订阅 / 外部导入配置只读（r57） | [051.md](docs/custom/051.md) |
| 52 | 测 IP 页面改成表格：直连测试首批 5 次全失败时提前停止，Sent 按实际握手次数统计；自动登记国家查询改为顺序后台队列，不阻塞后续 IP，轮次结束前排空（r58；r342 表格三处正则编译改为常量复用；r349 修复重复调用；r360 分批探测与归属查询队列） | [052.md](docs/custom/052.md) |
| 53 | 编辑器上下边距、配置卡片铅笔、双指缩放字号、键盘不打乱选中、运行中运行中的问题（并进错误与诊断页）（r59，r60 合并，r61 改成被动读取，r348 双指缩放结束后保存字号；触摸结束仅在光标或文字变化时补滚，双指缩放未变化不补滚） | [053.md](docs/custom/053.md) |
| 54 | 测 IP 表格：字号加 2、列铺满页面不再藏到屏外、长文字换行、机房名后加中文；运营商列最多三行，不够则加宽并允许整表横向滚动（r62） | [054.md](docs/custom/054.md) |
| 55 | 编辑器普通文字换色、代理页卡片常显 IP、配置卡片铅笔居中、测 IP 表头灰底不再有缺口（r64） | [055.md](docs/custom/055.md) |
| 56 | 编辑页：历史版本、保存和应用分开、历史逻辑修正；删掉设置里的"配置历史与检查"（r68） | [056.md](docs/custom/056.md) |
| 57 | 全 App 的 ✓ 和 ✗ 上色：✓ 绿、✗ 红，最粗最深（r69） | [057.md](docs/custom/057.md) |
| 58 | 历史版本载入后不弹对比页：增、改、删直接标在编辑页里（r73） | [058.md](docs/custom/058.md) |
| 59 | 编辑框能打中文：去掉"可见密码"输入类型（r76） | [059.md](docs/custom/059.md) |
| 60 | 改注释也算修改：对比和编辑页的增改删标记把注释也比进去（r77） | [060.md](docs/custom/060.md) |
| 61 | 编辑页的提示改成从上面弹出（r78） | [061.md](docs/custom/061.md) |
| 62 | 测 IP 表（直连）加"登记国家""落地国家"，入口机房带国旗和中英文，入口运营商改名，速度列移到屏幕外（r79） | [062.md](docs/custom/062.md) |
| 63 | 套入节点测补"入口机房""入口运营商""登记国家"（r80） | [063.md](docs/custom/063.md) |
| 64 | 还原 `LargeActionLabel.kt`，让 CheckCustom 通过（r81） | [064.md](docs/custom/064.md) |
| 65 | 修 r80 英文文案的撇号（r82） | [065.md](docs/custom/065.md) |
| 66 | 套入节点测加"目标网址"：对你实际要用的网站测（r83） | [066.md](docs/custom/066.md) |
| 67 | 目标站响应：读页面内容，认出页面自己的跳转和疑似地区限制（r84） | [067.md](docs/custom/067.md) |
| 68 | 测 IP 加"延迟波动"和"耗时明细"两列（r85） | [068.md](docs/custom/068.md) |
| 69 | 测其他 IP 的对话框加"读取配置的 IP"（r86） | [069.md](docs/custom/069.md) |
| 70 | 编辑页历史版本标记与固定、顶栏整份文本复制；底部操作按钮与符号键共用一行，点击切换，状态行保留 | [070.md](docs/custom/070.md) |
| 71 | 读取配置的 IP 改用代理页的逻辑、"填入"覆盖输入框、历史版本点选后关闭、编辑页三个点放大（r88） | [071.md](docs/custom/071.md) |
| 72 | 测 IP：落地国家、入口运营商、登记国家全部实时查，不再用任何记忆；归属查询使用顺序队列，轮次结束前排空；三个点缩小（r89；r360） | [072.md](docs/custom/072.md) |
| 73 | 目标站响应的状态码后面加几个字的说明（r90） | [073.md](docs/custom/073.md) |
| 74 | 编辑器页面与原版主题控件统一（r91） | [074.md](docs/custom/074.md) |
| 75 | IP 测速页面视觉统一（r92） | [075.md](docs/custom/075.md) |
| 76 | 问题列表与错误诊断视觉统一（r93） | [076.md](docs/custom/076.md) |
| 77 | 本地与云端备份对话框视觉统一（r94） | [077.md](docs/custom/077.md) |
| 78 | 连接列表页视觉统一（r95） | [078.md](docs/custom/078.md) |
| 79 | 修复备份表单读取输入内容的 Kotlin 编译错误（r96） | [079.md](docs/custom/079.md) |
| 80 | 测 IP 行内按钮强调色跟随 App 主题（r97） | [080.md](docs/custom/080.md) |
| 81 | 补齐编辑页主题控件适配（r98） | [081.md](docs/custom/081.md) |
| 82 | 修复编辑页跳转行号输入框的 Kotlin 构造参数错误（r99） | [082.md](docs/custom/082.md) |
| 83 | 测 IP“读取配置的 IP”按钮统一为 Material 主题样式（r100） | [083.md](docs/custom/083.md) |
| 84 | 收紧配置卡片铅笔图标两侧间距（r101） | [084.md](docs/custom/084.md) |
| 85 | 测 IP 总数和已测数量显示在测试模式标题行右侧；日志标题不再显示计数，Markdown 复制仍保留计数 | [085.md](docs/custom/085.md) |
| 86 | 将测 IP 进度计数紧跟“实时状态”标题显示（r103） | [086.md](docs/custom/086.md) |
| 87 | 保留最近一轮 IP 测试结果；变化合并落盘，强制时机即时保存（r104、r331、r338） | [087.md](docs/custom/087.md) |
| 89 | 配置检查页按钮重排（r105） | [089.md](docs/custom/089.md) |
| 91 | 配置检查页说明按钮与域名输入框提示修正（r107） | [091.md](docs/custom/091.md) |
| 92 | 将配置检查页说明按钮移入标题栏（r108） | [092.md](docs/custom/092.md) |
| 93 | 修复配置检查页域名输入框缺少 Material AutoCompleteTextView 默认尺寸（r109） | [093.md](docs/custom/093.md) |
| 94 | 配置检查页：表格行高、目标网址说明进弹窗、按钮列对齐（r110） | [094.md](docs/custom/094.md) |
| 95 | 显示 IP 下载测速的原始错误及中文解释（r111） | [095.md](docs/custom/095.md) |
| 96 | 下载测速失败原因显示在表格速度列（r112） | [096.md](docs/custom/096.md) |
| 97 | 测速失败表格只显示 HTTP 状态码（r113） | [097.md](docs/custom/097.md) |
| 98 | 直连与套入节点测速使用独立下载地址；直连默认 Cloudflare 文件调整为 30 MiB（r114） | [098.md](docs/custom/098.md) |
| 99 | 测 IP 页面术语统一及下载测速从首字节计时；下载量和时限可设置（1～200 MiB / 1～60 秒，默认 30 MiB / 6 秒），输入框失焦自动保存，只回写刚失焦且被夹紧的字段，值变化才提示、越界夹紧后提示原因，关闭弹窗时冲刷待保存值；Go 端夹紧请求上限并按实际上限判断是否不足；新增 IpDownloadLimit.kt、IpTestDialogInput.kt、strings_iptest_limit.xml（r115、r354、r355、r357） | [099.md](docs/custom/099.md) |
| 100 | 修复测 IP 英文字符串资源中的未转义撇号（r116） | [100.md](docs/custom/100.md) |
| 101 | 配置检查页交互、ECH / DNS 真取消及推算配置文案（r117） | [101.md](docs/custom/101.md) |
| 102 | 撤销 / 重做回到保存点时清除未保存状态（r118） | [102.md](docs/custom/102.md) |
| 103 | 编辑配置时顶栏显示配置名（r119） | [103.md](docs/custom/103.md) |
| 104 | 历史版本入口统一使用同一名称（r120） | [104.md](docs/custom/104.md) |
| 105 | 非 config.yaml 文件的保存按钮标明会生效（r121） | [105.md](docs/custom/105.md) |
| 106 | 将“正在用的配置”统一称为“已应用的版本”（r122） | [106.md](docs/custom/106.md) |
| 107 | 撤销 / 重做图标对齐 App 顶栏样式（r123） | [107.md](docs/custom/107.md) |
| 108 | split 模式应用按钮按已应用版本状态变淡（r124） | [108.md](docs/custom/108.md) |
| 109 | 历史版本长按菜单支持删除（r125） | [109.md](docs/custom/109.md) |
| 109a | 修正历史版本删除时的列表集合（r126） | [109a.md](docs/custom/109a.md) |
| 110 | 历史版本内容比较增加 SHA-256 缓存（r127） | [110.md](docs/custom/110.md) |
| 111 | 历史列表行数与增删行统计缓存（r128） | [111.md](docs/custom/111.md) |
| 112 | 查找与替换支持正则表达式（r129） | [112.md](docs/custom/112.md) |
| 113 | 编辑器跳到定义（r130） | [113.md](docs/custom/113.md) |
| 114 | 核对编辑器临时配置检查是否读取配置目录文件（r131） | [114.md](docs/custom/114.md) |
| 115 | 补齐历史版本哈希与 .stat 缓存实现（r132） （本号下有 2 段，同一文件） | [115.md](docs/custom/115.md) |
| 116 | 统一已应用版本文案及应用成功提示（r134） | [116.md](docs/custom/116.md) |
| 117 | 调整撤销重做点击盒并修正历史删除行映射（r135） | [117.md](docs/custom/117.md) |
| 118 | 将编辑器查找与替换统一到 TextSearch（r136，r346 查找输入防抖与关闭清理） | [118.md](docs/custom/118.md) |
| 119 | 修正正则偏好键与错误提示（r137） | [119.md](docs/custom/119.md) |
| 120 | 跳到定义改为按光标所在行解析引用（r138） | [120.md](docs/custom/120.md) |
| 121 | 收紧历史版本哈希缓存并规范 .stat 文件格式（r139） | [121.md](docs/custom/121.md) |
| 122 | 修正 YAML 跳转引用类型与正则零宽匹配边界（r140） | [122.md](docs/custom/122.md) |
| 123 | 补齐编辑器正则与定义跳转边界处理（r141） | [123.md](docs/custom/123.md) |
| 124 | 补齐历史版本提示与对比图例文案（r142） | [124.md](docs/custom/124.md) |
| 125 | 确认 split 检查临时目录与实际应用的 Provider 路径行为一致（r143） | [125.md](docs/custom/125.md) |
| 126 | 修正正则替换零宽推进与 UI 文本读取线程（r144） | [126.md](docs/custom/126.md) |
| 127 | 已应用版本分支直接更新 split 按钮状态（r145） | [127.md](docs/custom/127.md) |
| 129 | 清理普通文本全部替换的不可达分支（r147） | [129.md](docs/custom/129.md) |
| 128 | 对齐编辑器任务登记与实际提交（r146） | [128.md](docs/custom/128.md) |
| 130 | 移除未使用的应用状态比较请求（r148） | [130.md](docs/custom/130.md) |
| 131 | 精确修正文案分隔符（r149） | [131.md](docs/custom/131.md) |
| 132 | 补充缓存收益范围与应用按钮状态局限（r150） | [132.md](docs/custom/132.md) |
| 133 | 修复历史版本比较的哈希参数类型错误（r151） | [133.md](docs/custom/133.md) |
| 134 | 修复跳到定义和历史列表的两处 Kotlin 编译错误（r152） | [134.md](docs/custom/134.md) |
| 135 | 修正编辑器右上角更多按钮的图标对比度（r153） | [135.md](docs/custom/135.md) |
| 136 | 调整编辑页历史保存按钮文案（r154） | [136.md](docs/custom/136.md) |
| 137 | 更换编辑页撤销 / 重做图标并匹配查找文字大小（r155） | [137.md](docs/custom/137.md) |
| 138 | 修正运行中的问题与错误页说明文案（r161） | [138.md](docs/custom/138.md) |
| 139 | 将运行中的问题纳入诊断包（r162） | [139.md](docs/custom/139.md) |
| 140 | 节点地址问题增加失败门槛和断网排除（r163） | [140.md](docs/custom/140.md) |
| 141 | 节点测速成功后恢复对应的地址问题（r164） | [141.md](docs/custom/141.md) |
| 142 | 读取问题列表时惰性恢复长期未复现的问题（r165） | [142.md](docs/custom/142.md) |
| 143 | 清除节点超时记录时保存测速批次时间（r166） | [143.md](docs/custom/143.md) |
| 144 | 为运行中的问题添加页面内处理提示（r167） | [144.md](docs/custom/144.md) |
| 145 | 统一首页问题与节点连通文案（补充五 41–43） | [145.md](docs/custom/145.md) |
| 146 | 首页和通知栏区分读取失败（补充五 44–45） | [146.md](docs/custom/146.md) |
| 147 | 首页连通状态显示失败原因与结果时间，并限制历史标记（补充五 46–47） | [147.md](docs/custom/147.md) |
| 148 | 删除已废弃的空网络状态文件（补充五 48） | [148.md](docs/custom/148.md) |
| 149 | 勾叉后绘制固定颜色实心圆点（r170，补充六提交 1） | [149.md](docs/custom/149.md) |
| 150 | 配置历史勾叉接入及端口列宽适配（r170，补充六提交 2） | [150.md](docs/custom/150.md) |
| 151 | 代理页在用 IP 实心圆点（r171，补充六提交 4） | [151.md](docs/custom/151.md) |
| 152 | 全局圆点颜色统一为固定绿红两色（r173，补充六颜色修订及 Int 类型修正） | [152.md](docs/custom/152.md) |
| 153 | 勾叉后圆点放大、满饱和、像素对齐（r174） | [153.md](docs/custom/153.md) |
| 154 | 勾叉改为“实心圆 + 白色勾 / 叉”的图标（r175） | [154.md](docs/custom/154.md) |
| 155 | 勾叉图标缩小到字号的 0.6 倍（r176） | [155.md](docs/custom/155.md) |
| 156 | 已保存 IP 清单：筛选 / 展开状态重启持久化、按数据版本判断是否刷新、Store 解析结果按版本缓存、首次打开异步读取、配置来源局部刷新、批量删除撤销、测速请求提示、复制为 hosts、配置 IP 标记与置顶、筛选搜索、最快 IP、测试时间及失败详情；表格统一背景与分隔线；保存对话框删除运营商及其 IP；普通备份恢复按运营商和 IP 合并，replace 恢复整体覆盖（r310–r337；批量保存输入缓存与防抖） | [156.md](docs/custom/156.md) |
| 158 | 已保存 IP 独立直连 / 经节点下载测速；Go 端按约 250 ms 节流上报真实字节数与首字节后毫秒数，经现有 logcat / ILogObserver 通道传递；两页按钮按 max(bytes/maxBytes,time/maxSeconds) 显示百分比，并在按钮自身圆角边框内裁剪绘制进度填色；Short 标签使用通用“不足上限 / Under limit”；取消、失败、结束恢复；后台 / 息屏暂停 UI 更新并在前台补画（r353、r354） | [158.md](docs/custom/158.md) |
| 159 | 已保存 IP 按运营商独立保存双测速列排序：直连 / 经节点表头分别按各自速度值排序；按最快降序、按最慢升序，零值置后、同值稳定；状态保存列与方向；表头显示名称 / MB/s / 排序标签三行；按钮默认 14sp 两行，Short 时增加 11sp 橙色第三行，动态计算高度并留白居中 | [159.md](docs/custom/159.md) |
| 160 | 已保存 IP 表头按列多选筛选与自选显示列：稳定列名状态、隐藏列同步宽度 / 表头 / 单元格；IP 地址为唯一固定列，直连 / 经节点位于右侧横向滚动区最前面，其他列顺序不乱；隐藏列清筛选（r306、r320） | [160.md](docs/custom/160.md) |
| 161 | 配置检查页测 IP 表头和下载按钮固定分行，按文字实测宽度保护列宽（r301） | [161.md](docs/custom/161.md) |
| 162 | 测 IP 结果表与已保存 IP 共用同一张表，保存完整结果快照；相同结果跳过重绘，归属查询只更新状态单元格，诊断表仅在相关模式且行数据变化时重建；配置检查页经节点延迟结果表与 IP 详细诊断表统一背景、使用与已保存 IP 表相同的 1dp 行分隔线；已保存页文字列最多三行、按内容加宽，行高按最高单元格含测速按钮测量；已保存页所有行统一背景、相邻行使用 1dp 细分隔线，速度格容器不画底色、按钮填充透明，选中色只由整行绘制；下载测速按钮默认两行、Short 时三行，直连 / 经节点列按三行表头与按钮文字实测最小宽度（r335） | [162.md](docs/custom/162.md) |
| 163 | 已保存 IP 新增上限 300，单运营商展开，整表横向滚动；修复多选交互并新增滚动时表头吸顶副本；多选时整行任意位置均可切换选择，且不绕过状态 / 表头筛选及搜索；已激活多选后行级即时更新（r322–r326、r330） | [163.md](docs/custom/163.md) |
| 164 | 已保存页搜索匹配 IP 及表格八个可筛选列的显示文字；输入防抖重建、运营商独立状态筛选、表头筛选选项打开时按状态 / 其他列条件 / 搜索显示匹配行数（排除本列条件，0 也显示，显示文案与原始筛选值分离）；筛选结果为 0 时仍显示可交互表头、吸顶副本和空结果提示；双测速列独立排序与行级结果更新（r307、r314、r333） | [164.md](docs/custom/164.md) |
| 165 | 表格横向滚动恢复：主表格实时缓存 liveTableScrollX，同帧多次刷新合并为一次重建，新表布局完成后通过一次性 OnLayoutChangeListener 恢复；已保存页也改用布局回调，不再使用会取消绘制帧的 OnPreDrawListener；保留按运营商局部重建与筛选优化（r308、r311、r330、r334、r353） | [165.md](docs/custom/165.md) |
| 166 | 测 IP 表与已保存 IP 表按综合指标标出前三名推荐 IP（橙色“推荐”标签；测 IP 页标签置于 IP 第二行，已保存页置于已测时间前） | [166.md](docs/custom/166.md) |
| 167 | 配置检查页测 IP 表移除精简 / 完整显示列切换，始终显示完整列 | [167.md](docs/custom/167.md) |
| 168 | 网络运营商识别：移动卡优先读取 MCC/MNC，其他情况绑定底层网络查询双接口；结果跨进程缓存，息屏延后，首页当前 IP / 问题卡片常显；无问题时显示最近一次自动切换结果并提供映射页入口；状态栏问题通知同步显示当前运营商，错误诊断页纳入识别结果 | [168.md](docs/custom/168.md) |
| 169 | 按运营商自动切换节点：每个运营商只配置一个节点；自定义项支持关键词与省市组合匹配；节点选择和新增按钮为细边框，修改 / 删除为无边框文字按钮，保存为实心主按钮；网络识别完成或设置页保存命中当前网络时触发，代理页顶部提供入口；首页显示最近一次成功切换结果；映射随订阅备份恢复；删除订阅时清理映射文件，保存失败明确提示，代理页与编辑页图标支持长按提示 | [169.md](docs/custom/169.md) |
| 170 | 订阅更新后显示变更摘要：仅保留非 File 订阅上一份 config.yaml，打开错误与诊断页时按需比较并显示新增 / 删除行数 | [170.md](docs/custom/170.md) |
| 171 | 编辑器纯逻辑 JVM 测试：YamlLint、YamlPairs、TextSearch；Kotlin 2.1.0 直接编译运行，独立于 Gradle / Build Debug | [171.md](docs/custom/171.md) |
| 157 | 文档分层：CUSTOM.md 改成索引，详情按编号拆开，加大小和引用检查（r282；提交方式见 `AGENTS.md`） | [157.md](docs/custom/157.md) |

## 修改记录（#156 起的分步流水，按原文顺序，只读）

| 文件 | r 范围 | 第一段 | 最后一段 |
|---|---|---|---|
| [h01-r178-r185.md](docs/custom/history/h01-r178-r185.md) | r178-r185 | 第 2/5 步：保存这一批 | 第 13 步：序列化改为显式指定，保存 / 读取失败如实提示（r185） |
| [h02-r186-r213.md](docs/custom/history/h02-r186-r213.md) | r186-r213 | 第 14 步：已保存 IP 通断状态改为纯色圆点（r186） | 第 39 步：底层连接页的“底层 / 上层”改名为“实际连接 / 请求数”（r213） |
| [h03-r214-r278.md](docs/custom/history/h03-r214-r278.md) | r214-r278 | 第 23 步：修复已保存 IP 多选布局及重测返回状态（r214） | r226 共用请求路径静态修正 |
| [h04-r227-r252.md](docs/custom/history/h04-r227-r252.md) | r227-r252 | r227 清理 IP 测试旧分支并补上主动归属地入口 | 第 65 步：说明出口国家与出口机房的区别（r252） |
| [h05-r255-r273.md](docs/custom/history/h05-r255-r273.md) | r255-r273 | 第 66 步：在“测试其他 IP”对话框增加按域名查询 IPv4 的内核接口（r255） | 第 82 步：测试框标题行增加最后修改时间和清除（r273） |
| [h06-r279-r281.md](docs/custom/history/h06-r279-r281.md) | r279-r281 | 第 83 步：新增 mihomo DNS 强制重新查询接口（r279） | 第 85 步：修复构建失败（补丁行数、Kotlin 编译栈溢出）（r281） |
