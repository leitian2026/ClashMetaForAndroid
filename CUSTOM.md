# CUSTOM.md — 自定义功能登记表

基线：上游 `1a4f949213fcbb7f0c9e2a4a21138642d84b8f22`（版本 2.11.36）。
当前版本：2_11_36-r131
已归档（内容已被后面的条目取代，仅留作记录）：#88（被 #89 取代）、#90（被 #91 #93 取代），见 `CUSTOM_ARCHIVE.md`。代码注释里写着这两个编号的，去那个文件里找。
所有挂钩处在代码里都带 `CUSTOM` 注释，可用 `grep -rn CUSTOM` 找全。
规则和同步上游的步骤见 `AGENTS.md`。每次改动都要同步更新本文件（含下面的总表）。

## 上游文件总表（同步上游时冲突只会出现在这里）
| 上游文件 | 对应条目 | 冲突风险 | 冲突时怎么办 |
|---|---|---|---|
| `.github/workflows/build-debug.yaml` | #2 #3 #4 #5 #14 #18 #20 #40 #54 | 中（`on:` 段、Build 步骤） | 以上游为底，重加：只留 `workflow_dispatch`、预下载 Gradle、预下载 geo 文件并加 geo 文件缓存兜底（#14 #18）、签名步骤、arm64 参数（含 `-x :app:downloadGeoFiles`）与上传、`Checkout submodules` 之后的 `Apply mihomo patches` 步骤（#20）、上传前的 `Artifact name` 步骤和上传步骤里的 `name:`（#40）、`Apply mihomo patches` 前的 `Remove deleted custom files` 步骤（#54） |
| `.github/workflows/build-pre-release.yaml` | #2 #3 | 中 | 以上游为底，重加：只留 `workflow_dispatch`、预下载 Gradle |
| `core/src/main/golang/native/tunnel.go` | #1 #7 #37 | 低 | 重加 `ipdelay.TestGroup(name)` 和 `queryTunnelState` 里的 current 字段（`CurrentSet()`、`currentNode` / `currentIp` / `currentCountry` / `currentIps` / `currentMore`），以及 `reach` 字段（`ipdelay.Reach(node)`，#37） |
| `core/src/main/golang/native/connections.go` | #8 #15 #101 | 低 | 重加 `queryCustomConnections` 的自定义分派和 `closeCustomConnection` 的自定义关闭分支；#101 在 `CANCEL_IPTEST` 前加 `CANCEL_CHECK` 分支，调用 `conftest.CancelCheck()` |
| `core/src/main/golang/native/tunnel/proxies.go` | #1 #55 | 低 | 重加 `IPDelays` 字段和填充行 |
| `core/src/main/golang/native/config/load.go` | #1 #22 | 低 | 重加 `ipdelay.CaptureConfig(...)`（#1）和它后面的 `ipdelay.CaptureDNS(rawCfg.DNS)`（#22） |
| `core/src/main/golang/native/delegate/init.go` | #7 | 低 | 重加 `ipdelay.InstallSocketHook(home)`（必须在上游给 `DefaultSocketHook` 赋值之后；`home` 是 `Init` 的第一个参数，用来定位 #10 #11 的小文件） |
| `core/src/main/java/com/github/kr328/clash/core/model/Proxy.kt` | #1 | 低 | 重加 `ipDelays` 参数 |
| `core/src/main/java/com/github/kr328/clash/core/model/TunnelState.kt` | #7 #37 | 低 | 重加 `current*` 参数（`currentNode` / `currentIp` / `currentCountry` / `currentIps` / `currentMore`）、`CurrentIp` 和 `ipsText()`，以及 `reach: Int = 0`（#37） |
| `design/src/main/java/com/github/kr328/clash/design/component/ProxyView.kt` | #1 #12 #55 | **高**（改了 `onMeasure` / `onDraw`） | 以上游新版为底，按 #1 表格的描述重做 IP 行的高度和绘制，再按 #12 加最后一行"N 分钟前测速"，不要硬合 |
| `design/src/main/res/layout/design_main.xml` | #7 #8 #20 #21 #22 #32 #53 | **高**（App 名字 `TextView` 外包了一层 `LinearLayout`；代理卡片改了 `subtext` 并去掉 `visibility`） | 以上游新版为底，按 #7 重做：新增变量 `customIp`、包住 App 名字并加红字 `custom_geosite_notice`、代理卡片 `subtext` 改成带 `customIp`、删掉代理卡片的 `visibility`；#8 在"日志"标签前加"连接"标签，#20 在它后面再加"底层连接"标签（`Request.OpenMux`），#21 在"日志"标签前再加"错误与诊断"标签（`Request.OpenErrors`），#22 在"错误与诊断"标签前再加"配置检查"标签（`Request.OpenConfigCheck`）；#32 在顶部（logo + App 名字）那一行末尾加右上角的 `ImageView`（`@+id/custom_more`，三点图标，48dp）；同时把包住 App 名字的 `LinearLayout`（#7 加的那层）改成 `layout_width=0dp` + `layout_weight=1`（r38，原来是 `wrap_content`，窄屏上名字占满宽度，三点被挤出屏幕） |
| `design/src/main/java/com/github/kr328/clash/design/MainDesign.kt` | #7 #8 #20 #21 #22 #32 #53 | 低 | 重加 `customCards`、`setCustomCards()`；`Request` 枚举加 `OpenConnections`（#8）、`OpenMux`（#20）、`OpenErrors`（#21）、`OpenConfigCheck`（#22）；#32 重加 `customMenu = MainCustomMenu(context, binding.root)` 和它的 import |
| `design/src/main/java/com/github/kr328/clash/design/MetaFeatureSettingsDesign.kt` | #6 #16 | 低 | 重加 `geoSiteDownload()` 一行（#16 起它一次加 GeoSite / GeoIP / ASN 三块，不用再改这里） |
| `app/src/main/java/com/github/kr328/clash/MainActivity.kt` | #6 #7 #8 #20 #21 #22 #35 #53 | 低 | 重加 `fetch()` 里的 `setCustomCards(...)`、`customNodeIpEvents()` 和 `select` 里的 `customNodeIp.onReceive` 分支；`OpenConnections` 分支（#8）、`OpenMux` 分支和 import（#20）、`OpenErrors` 分支和 import（#21）、`OpenConfigCheck` 分支和 import（#22）；#35 流量定时器那一支的条件由 `clashRunning` 改成 `clashRunning && activityStarted` |
| `service/src/main/java/com/github/kr328/clash/service/clash/module/DynamicNotificationModule.kt` | #7 #35 | 低 | 通知正文改用 `NodeIpText.get(service)`（原来是上传/下载速度），去掉不再用的 `queryTrafficNow`；加 `nodeIpChanged` 通道、`NodeIpNotifier.start`、一个 `select` 分支；#35 加属性 `lastPosted`，`update()` 里文字没变就不 `notify`，定时器 1 秒改 5 秒，亮屏和换配置时清 `lastPosted` |
| `service/src/main/java/com/github/kr328/clash/service/ClashManager.kt` | #7 #8 #15 | 低 | `queryTunnelState()` 开头加 `NodeIpNotifier.start(context)`；加 `queryCustomConnections(sort: Int, filter: String)` / `closeCustomConnection(id)` 两个实现（#8 #15） |
| `service/src/main/java/com/github/kr328/clash/service/remote/IClashManager.kt` | #8 #15 | 低 | 末尾重加 `queryCustomConnections(sort: Int, filter: String): String` 和 `closeCustomConnection(id: String): Boolean` |
| `app/src/main/AndroidManifest.xml` | #8 #17 #20 #21 #22 #46 | 低 | 在 `LogsActivity` 前重加 `.custom.ConnectionsActivity`（#8）、`.custom.BackupActivity`（#17，**必须带** `android:theme="@style/BackupTranslucentTheme"`，否则页面不透明，对话框后面是黑的）、`.custom.MuxActivity`（#20）、`.custom.ErrorsActivity`（#21）、`.custom.ConfigCheckActivity`（#22）和 `.custom.EditorActivity`（#46，**必须带** `configChanges` 里的 `orientation|screenSize|...`，否则转一下屏页面就重建，没保存的文字丢了）的 `<activity>` |
| `app/src/main/java/com/github/kr328/clash/FilesActivity.kt` | #46 #50 | 低 | `OpenFile` 分支：`EditorActivity.canOpen(...)` 为真就 `startActivity(EditorActivity.intent(..., uuid, "", profile.type != Profile.Type.File))`，否则还是原来的外部编辑器；顶部 `import ...custom.EditorActivity` |
| `design/src/main/res/layout/adapter_profile.xml` | #47 #53 #55 | 中（改了时间那个 `TextView` 的位置） | 以上游新版为底：`<data>` 里加变量 `edit`；时间 `TextView`（`elapsed_view`）`layout_marginEnd` 改 20dp、`layout_toStartOf` 改成 `@id/edit_view`；在它和那条 2dp 竖线之间加 `FrameLayout`（`@+id/edit_view`，铅笔图标 `ic_baseline_edit`，`onClick="@{edit}"`） |
| `design/src/main/java/com/github/kr328/clash/design/adapter/ProfileAdapter.kt` | #47 | 低 | 构造函数末尾加带默认值的 `onEditClicked`，`onBindViewHolder` 里加 `binding.setEdit { onEditClicked(current) }` |
| `design/src/main/java/com/github/kr328/clash/design/ProfilesDesign.kt` | #47 | 低 | `Request` 加 `EditConfig(profile)`；创建 `ProfileAdapter` 时多传一个 lambda（`requests.trySend(Request.EditConfig(it))`） |
| `app/src/main/java/com/github/kr328/clash/ProfilesActivity.kt` | #47 #50 | 低 | `when` 里加 `EditConfig` 分支：`startActivity(EditorActivity.intent(this@ProfilesActivity, "${uuid}/config.yaml", "config.yaml", uuid, name))`（注意 select 里 `this` 不是 Activity）；加 import |
| `core/src/main/cpp/CMakeLists.txt` | #7 | 低 | `add_library` 源文件末尾加 `custom_bridge.c` |
| `app/src/main/java/com/github/kr328/clash/MainApplication.kt` | #16 #21 | 低 | `onCreate()` 里 `extractGeoFiles()` 后重加一行 `GeoFileKeeper.restoreAfterUpdate(this)`（#16），在 `onCreate()` 里 `val processName` 之前重加 `CrashRecorder.install(this)`（#21）（及 import） |
| `design/src/main/java/com/github/kr328/clash/design/AppSettingsDesign.kt` | #17 | 低 | `preferenceScreen` 块末尾（"服务"那个开关之后）重加一行 `customBackup()` |
| `service/src/main/java/com/github/kr328/clash/service/ProfileProcessor.kt` | #27 #28 #56 | 低 | `apply()` 里两处：① `fetchProfile(...)` 外面包 `try { ... } catch (e: Exception) { throw ConfigErrorText.explain(context, context.processingDir, e) }`（#27，保存失败时报出是哪一行）；② 在 `context.importedDir.resolve(snapshot.uuid.toString()).deleteRecursively()` 前一行加 `if (snapshot.type == Profile.Type.File) ConfigHistory.keep(context, snapshot.uuid)`（#28 #56，只给自己编辑的配置记下被替换的和新的版本）；`delete()` 里 `imported.deleteRecursively()` 后一行加 `ConfigHistory.deleteAll(context, uuid)`（#56，配置删了，历史一起删）；加两个 import |
| `app/src/main/java/com/github/kr328/clash/LogsActivity.kt` | #31 | 低 | `setContentDesign(design)` 后加一行 `LogLevelControl.attach(this, design)`；加一个 import |
| `design/src/main/res/layout/design_logs.xml` | #31 | 低 | 在 `delete_view`（三横线）左边加 `log_level_view`（r34 起是显示当前级别文字的 `TextView`，原来是图标 `ImageView`）；标题栏的 `layout_toStartOf` 改指向它 |
| `design/src/main/java/com/github/kr328/clash/design/OverrideSettingsDesign.kt` | #31 | 低 | 删掉"日志级别"那一个 `selectableList`（换成一行 CUSTOM 注释）；入口搬到日志页 |
| `build.gradle.kts` | #44 | 低（只加一个 `customRev` 和两处 `versionNameSuffix`） | 重加 `queryConfigProperty` 后面的 `customRev`（读 `CUSTOM.md` 的"当前版本："那一行，取最后的 `r<数字>`），`release` 构建类型里的 `versionNameSuffix = customRev`，`debug` 构建类型里的 `versionNameSuffix` 改成 `".debug$customRev"`（后缀必须放在构建类型里，才会排在变体后缀 `.Alpha` / `.Meta` 的后面） |
| `design/src/main/res/layout/design_about.xml` | #44 | 低 | 内层竖向 `LinearLayout`（App 名字和版本号那一层）的 `layout_width` 由 `match_parent` 改成 `0dp`，加 `layout_weight=1`，版本号太长时才会换行 |
| `service/src/main/java/com/github/kr328/clash/service/ProfileWorker.kt` | #53 | 低 | `completed()` 和 `failed()` 末尾各加一行 `IssueNotifier.subscription(this, name, null / reason)` |
| `design/src/main/java/com/github/kr328/clash/design/view/LargeActionCard.kt` | #57 | 低 | `subtext` 的 setter 里，`binding.subtextView.text = value` 改成 `binding.subtextView.text = com.github.kr328.clash.design.custom.CheckMark.paint(context, value)`（首页代理卡片的 ✓ 绿 / ✗ 红） |
| `design/build.gradle.kts` | #87 | 低 | 增加 `libs.kotlin.serialization.json` 依赖，供配置检查页将最近一轮 `IpTestReport` 编码 / 解码为本地 JSON；不改插件、构建任务或其他依赖 |

共 35 个上游文件（含 `FilesActivity.kt`、`build.gradle.kts`、`design_about.xml`、 `ClashManager.kt`、`IClashManager.kt`、`AndroidManifest.xml`、`CMakeLists.txt`、`MainApplication.kt`、`AppSettingsDesign.kt`、`ProfileProcessor.kt`）。同步后可运行 `bash scripts/check-custom.sh`（#19）自动检查。同步后 `grep -rn CUSTOM` 应在这些文件（加 `custom/` 下新文件）里全部找到。

## 构建事实
- `build-debug.yaml` 实际执行 `app:assembleAlphaRelease`，**R8 已开**，不是"没混淆的 debug"。
- 无法本地编译，只能手动触发 Actions 验证；`build-release.yaml`、`update-dependencies.yaml` 没有改动。

## 1. 代理页：每个 IPv4 的测速结果

**效果：** 在"代理"页点 ⚡（或右上角测速）后，节点卡片下方多出几行 `IP  延迟`。
- IP 来源：节点 `server` 在配置 `hosts:` 里对应的 IP 列表；没有 `hosts` 条目就实时解析节点域名。
- 只测 IPv4。`server` 本身就是单个 IP 的节点、`ip-version: ipv6` 的节点不显示（没有可比较的对象）。
- 仅支持 `proxies:` 里直接写的节点；订阅 / provider 里的节点不显示。
- 失败或超时显示"超时 / Timeout"。
- **国旗：** 每个 IP 的延迟测通后，再经这个 IP 请求一次"查询自己出口国家"的服务（`https://api.country.is/`，失败再试 `https://ipinfo.io/country`），把出口国家画成国旗放在 IP 前面（落地国家，不是 IP 注册地）。查不到显示"？"。多出的请求只在用户手动测速时发生。
- 功耗：只在用户手动测速时才跑，不轮询，不跟随息屏恢复时的自动健康检查（`healthCheckAll`）。

**r41：测哪些 IP、并发和国家查询**
- **（r44 改了，见下面 r44 一节）** ~~每个节点最多测 4 个 IP~~。在用的 IP（`inUseLocked`：#7 记下的请求最近走过的 IP，没有就取最近拨的 IP；只取 IPv4）仍然一定会测。显示顺序仍按名单顺序，不是"在用的排最前"，这样前后两次对得上；不在名单里的在用 IP 排在最后。
- **名单顺序固定：** `hosts:` 里写了的，按你写的顺序；实时解析的，先把解析到的 IPv4 **排序**再取（DNS 返回的顺序每次可能不同）。
- **"共 N 个，仅测了 M 个"（r44 起只有实时解析的节点会出现，`hosts:` 的节点不显示）：** 名单（解析到的全部 IPv4，加上不在名单里的在用 IP）超过测的个数时，页面最后一行（"N 分钟前测速"那一行）左边多写这一句。N 是**这一次解析到的个数**，不是域名一共有多少个；DNS 每次只给其中一部分的域名，N 可能前后不一样。没超过就不显示。
- **在用的 IP 前面有 `●`：** `Get()` 返回时现算（`IPDelay.InUse`），不存；节点最近没有请求时是"最后一批"（和首页一致，直到下一个请求才变）。
- **~~限量并发~~（r44 去掉了 `testSlots` / `testJitter`，改成下面的"一个节点内一个接一个测"）。**
- **国家查询（r44 起按来源，见下）：** 只对测通的 IP 查；**这个节点 + 这个 IP 的国家已经知道（内存里或 #11 记在磁盘上的）就不再查**，直接用，少发一次请求；新出现的 IP 才查。代价：一个 IP 的出口国家变了的话，测速不会自己刷新它（首页的失败重试照旧）。
- **改动（r44 又改了其中的限量、并发和 `candidateIPs`，见下一节）：** `ipdelay.go`（`maxTestIPs` / `maxConcurrent` / `testJitter`、`IPDelay.Total` / `InUse`、`candidateIPs` 不再截断并对解析结果排序、新增 `pickIPs` / `inUseLocked` / `throttledTest`、`testProxy`、`Get`、`testIP` 多一个 `known` 参数）、`IpDelay.kt`（`total`、`inUse`）、`ProxyView.kt`（在用标记和"共 N 个"一行，行数没变，不改 `onMeasure`）、文案 `ip_delay_total`。

**r44：按来源收窄测速（`hosts:` 的全测，DNS 解析出来的只测少数）**
- **测几个：** `hosts:` 里你写的 IPv4 **全测**，最多 8 个（`maxHostsIPs`；超过 8 个时在用的一定在里面，其余按你写的顺序补）。实时解析出来的（`hosts:` 里没有这个 server）排序后只测前 2 个（`maxResolvedIPs`），**在用的那个再加 1 个**（已经在前 2 个里就不加；有几个在用的只加最近用的那一个）。选法在 `pickIPs`（多了参数 `withInUseOnTop`）。
- **一个节点内一个接一个测：** 同一个节点的几个 IP 依次测，两个之间**随机隔 1 到 3 秒**（`gapMin` / `gapMax`，间隔不计入延迟）；不同节点之间照旧各测各的。去掉了原来"所有节点一共同时最多 4 个"（`testSlots`）和测前的随机等待（`testJitter`、`throttledTest`）。代价：一个节点有 8 个 IP 时要测 7 到 21 秒以上。
- **国家查询：** `hosts:` 的 IP **测通的都查**（缓存里有就不查）；实时解析出来的**只查在用的那一个**（缓存里有就不查），其余的 IP 只显示延迟，国旗位置显示"？"（没查，不是失败）。`testIP` 多了参数 `wantCountry`。
- **"共 N 个，仅测了 M 个"：** 只在实时解析的节点上出现（`testProxy` 里 `hosts:` 的节点 `Total` 写 0）。
- **没有时间限制：** 每次点测速都真测，没有"多久内不重测"。
- **`candidateIPs`** 多返回一个 `fromHosts`（名单是不是来自 `hosts:`）。
- **改动：** 只有 `ipdelay.go`（上面这些，加 `inUseV4`、`setFastest`）。Kotlin 一行没动。

**新增文件**
- `core/src/main/golang/native/ipdelay/ipdelay.go`：逐 IP 测速逻辑（临时复制节点、把 server 换成该 IP、经节点拨号请求测速 URL）。
- `core/src/main/java/com/github/kr328/clash/core/model/IpDelay.kt`：数据类（`ip`、`delay`、`country`、`at`）。
- `core/src/main/java/com/github/kr328/clash/core/util/CountryFlag.kt`：国家码转国旗 emoji（放在 core，design 和 service 都用）。
- `design/src/main/res/values/strings_custom.xml`、`design/src/main/res/values-zh/strings_custom.xml`：文案。

**改了上游文件（冲突只会出现在这里）**
| 文件 | 改动 |
|---|---|
| `core/src/main/golang/native/tunnel.go` | import 加 `cfa/native/ipdelay`；导出函数 `healthCheck` 里 `tunnel.HealthCheck(name)` 后加一行 `ipdelay.TestGroup(name)` |
| `core/src/main/golang/native/tunnel/proxies.go` | import 加 `cfa/native/ipdelay`；`Proxy` 结构体加字段 `IPDelays`；`convertProxies` 里填 `IPDelays: ipdelay.Get(name)` |
| `core/src/main/golang/native/config/load.go` | import 加 `cfa/native/ipdelay`；`hub.ApplyConfig(cfg)` 后加 `ipdelay.CaptureConfig(rawCfg.Proxy, rawCfg.Hosts)` |
| `core/src/main/java/com/github/kr328/clash/core/model/Proxy.kt` | 构造参数末尾加 `val ipDelays: List<IpDelay> = emptyList()` |
| `design/src/main/java/com/github/kr328/clash/design/component/ProxyView.kt` | #1 r41：IP 行前加 `●` 标记，最后一行左边写"共 N 个，仅测了 M 个"；import `R`；`state` 加 setter（`requestLayout()`）；`onMeasure` 增加 IP 行高度；`onDraw` 基础布局改用"去掉 IP 行后的高度"，末尾新增 IP 行绘制 |

**依赖的 mihomo API（上游内核升级后若编译报错先查这些）：**
`adapter.ParseProxy`、`C.Proxy.DialContext`、`C.Proxy.ExtraDelayHistories`、`resolver.LookupIP`、`outboundgroup.ProxyGroup`、`config.RawConfig` 的 `Proxy` / `Hosts` 字段。

## 2. 构建改为手动
- `.github/workflows/build-debug.yaml`：去掉 `pull_request` 和 `push` 触发，只保留 `workflow_dispatch`。
- `.github/workflows/build-pre-release.yaml`：去掉 `push` 触发，只保留 `workflow_dispatch`。
- 同步上游时这两个文件的 `on:` 段最容易冲突，保留"只有 `workflow_dispatch`"即可。

## 3. 构建前预下载 Gradle（带重试）
- 原因：`services.gradle.org` 偶尔超时（wrapper 固定 10 秒），导致构建在编译前就失败。
- `.github/workflows/build-debug.yaml`、`.github/workflows/build-pre-release.yaml`：在 `Build` / `Pre-release Build` 步骤前加一个带 `CUSTOM` 注释的 `Prefetch Gradle wrapper` 步骤，最多重试 5 次 `./gradlew --no-daemon --version`。
- 没有改 `gradle-wrapper.properties`（上游文件）。同步上游时这两个 workflow 若冲突，保留上游内容后把这一步重新加回去即可。
- 功耗：只影响 CI，对手机无影响。

## 4. 固定签名（仅 build-debug）
- 原因：CI 每次用新生成的 debug 密钥签名，包签名每次不同，无法覆盖安装。
- `.github/workflows/build-debug.yaml`：把原本注释掉的 `Signing properties` 步骤改为启用，并在其中先把 secret `SIGNING_KEYSTORE_BASE64` 解码成工作区里的 `release.keystore`（只在 CI 工作区覆盖，不提交；仓库里上游的 `release.keystore` 不动），再用 secrets 写 `signing.properties`。已去掉原来的 `cat signing.properties`，避免密码进日志。
- 需要在仓库 Settings → Secrets and variables → Actions 里设置 4 个 secret：`SIGNING_KEYSTORE_BASE64`、`SIGNING_STORE_PASSWORD`、`SIGNING_KEY_ALIAS`、`SIGNING_KEY_PASSWORD`。
- 没改任何上游文件；`build.gradle.kts` 本来就会在 `signing.properties` 存在时使用 `release.keystore` 签名。
- 同步上游时 `build-debug.yaml` 冲突，保留这一步即可。
- 密钥库文件务必自行备份，丢了就无法再用同一签名更新。

## 5. build-debug 只构建 arm64-v8a
- 原因：每次为 4 个架构各编译一遍 Go 内核和 C++，太慢；日常只用 arm64。
- `.github/workflows/build-debug.yaml`：`Build` 命令加 `-Pandroid.injected.build.abi=arm64-v8a`；上传步骤只保留一个 arm64-v8a，匹配 `app/build/intermediates/apk/alpha/release/*.apk`（加了注入 ABI 参数后，APK 不会出现在 `build/outputs/` 下，而是留在 `build/intermediates/` 里），找不到文件时报错；上传前多一步 `List APK files` 用 `find` 打印 APK 实际位置。
- 没改上游文件（`build.gradle.kts` 里写死的 4 个架构不动，用 Gradle 参数限制）。
- 同步上游时若 `build-debug.yaml` 冲突，保留这两处即可。
- 要出其他架构时，去掉该参数并把上传步骤加回来。

## 6. GeoSite 按地址下载并安装
**效果：** "设置 → Meta 特性"页的"Geo 文件"区域最下方，多出一块常显的 GeoSite 下载区：地址输入框（常显，默认预填官方 `latest/geosite.dat` 地址，之后记住上次填的）、"下载并安装"按钮（下载中变"取消"）、进度/结果文字，以及一行常显提示。
- 只接受 `https://` 地址。先下载到临时文件，检查大小 ≥ 1 KB 且首字节为 `0x0A`（geosite.dat 的 protobuf 头；GeoIP / ASN 见 #16），通过后才替换 `geosite.dat`；失败或取消不会动原文件。
- 安装后需重新加载配置（或重启服务）才生效（内核已把规则读进内存）。r11 起下载成功后出现"立即重载配置"按钮，见 #13。
- **更新应用后：** r12 起（#16）下载的文件会自动恢复，不再被换回内置版。下面是旧版（r11 及以前）下载、没有副本的文件的情况，首页仍会提示： 上游 `MainApplication.extractGeoFiles()` 会在应用更新后把旧于安装时间的 `geosite.dat` 换回内置版本（这部分不改）。下载成功时记录文件修改时间（`installed_at`）；首页判断"记录时间早于应用最后更新时间"时，在首页顶部 App 名字下方显示一行红字"域名集已恢复为内置版本，点此重新下载"（不是卡片），点击跳到"设置 → Meta 特性"。重新下载成功后记录时间变新，红字自动消失；从未下载过（没有记录）则不显示。纯本地判断，只在首页显示 / 刷新时判断一次，无定时、无联网。设置页里的小字提示保留。
- 功耗：只在用户点按钮时联网，不轮询、不后台；退出页面会取消正在进行的下载。
- 地址保存在独立的 SharedPreferences `custom_geosite`（键 `url`），不写入 override 配置。

**新增文件**
- `design/src/main/java/com/github/kr328/clash/design/custom/GeoSiteDownloader.kt`：下载、校验、替换逻辑。
- `design/src/main/java/com/github/kr328/clash/design/custom/GeoSiteResetNotice.kt`：判断"更新应用后下载的 GeoSite 是否被换回内置版"（`isPending`），不再弹窗、不再清记录。
- `design/src/main/java/com/github/kr328/clash/design/preference/GeoSiteDownloadPreference.kt`：设置页里的这一块界面（`PreferenceScreen.geoSiteDownload()`）。
- `design/src/main/res/layout/preference_geosite_download.xml`：布局。r34：和上下的设置行对齐——左边距改成设置行文字的起点（`@dimen/geosite_download_indent` = 65dp，新文件 `values/dimens_custom.xml`，等于图标宽度加两边的间距），输入框用 `OutlinedBox.Dense`（矮一点），下载按钮用 `OutlinedButton`（不再是实心大按钮），说明文字用和别的行说明一样的 `Body2`（原来是更小的 `Caption`）。只改布局，下载逻辑没动。
- 文案追加在 `design/src/main/res/values/strings_custom.xml` 和 `values-zh/strings_custom.xml`（`geosite_download_*`）。

**改了上游文件（冲突只会出现在这里）**
| 文件 | 改动 |
|---|---|
| `design/src/main/java/com/github/kr328/clash/design/MetaFeatureSettingsDesign.kt` | "导入 ASN 数据库"那个 `clickable` 之后加一行 `geoSiteDownload()`（带 `CUSTOM` 注释） |
| `app/src/main/java/com/github/kr328/clash/MainActivity.kt` | 见 #7（r9 起不再调用 `GeoSiteResetNotice.showIfNeeded`，改由首页红字显示） |

**依赖的上游约定（上游改动后先查这些）：** geo 文件目录为 `filesDir/clash`（与 app 模块 `Context.clashDir` 一致，这里直接写成 `context.filesDir.resolve("clash")`）；`PreferenceScreen`/`addElement`/`Preference` 这套设置页接口。

## 7. 当前节点 IP + 国旗（首页代理卡片、状态栏通知；r32 起显示一次访问实际用到的所有 IP）
**效果：**
- 首页"代理"卡片改为**常显**（停止时也显示），副标题在原来的模式后面加 ` · 🇺🇸 45.139.193.125`（正在承载流量的节点所用 IP 和出口国旗）。没有连接 / 未运行时只显示模式。国旗查不到显示"？"。没有单独的 IP 卡片，也没有点击刷新。
- 状态栏通知正文由"上传/下载速度"改为 `🇺🇸 IP · 节点名`（没有连接时显示"暂无节点连接"）；副标题（累计流量）保持上游原样。
- #6 的 GeoSite 提示在 App 名字下方（红字，与名字隔开 6dp，布局里 `custom_geosite_notice`），也在这里的布局里。
- **节点怎么定的（r10）：** 遍历内核当前连接（`statistic.DefaultManager.Range`），链路（`Chains()`）里出现的节点名（在配置 `proxies:` 里的）计数，连接数最多的就是"正在用的节点"；没有连接时退回最近一次拨号记录。
- **IP 怎么定的：** 内核每建一个连接都会经过 `dialer.DefaultSocketHook`，这里包了一层，记下每个节点最近拨的 IP。节点只有一个地址就直接用它。`hosts:` 里不属于任何节点 server 的条目（例如 dns 里 DoH 域名映射的 IP）会让内核自己去连，这些 IP 不记入，避免把 DoH 的直连误当成节点使用（r9 的错误来源）。
- **国旗怎么来的：** 该 IP 还没有国家时，查询会在后台起一次探测（经该节点、该 IP 请求同样的国家查询服务），结果缓存；探测中显示"？"，下一次刷新就有。失败 5 分钟内不重试。测速时测到的国家会直接复用。
- **更新时机（事件触发，无定时器 / 轮询）：** 内核（`ipdelay.record`）发现拨号用的节点 IP 和上一次不同，或国家探测刚得到结果时，等 0.8 秒（让新连接登记、合并连续拨号）后调用一次 `Current()`，只有"节点 / IP / 国家"和上次通知的不一样才通知应用：Go → `fetch_report` 回调（复用上游的 FetchCallback JNI 通路）→ Kotlin `NodeIpWatcher` → 服务进程发自广播 `ACTION_NODE_IP_CHANGED` → 首页（`MainActivity` 只在页面 started 期间注册接收器）重新读一次、通知（`DynamicNotificationModule`）重建一次文本。IP 没变就什么都不做。另外照上游本来的事件刷新：进入首页、Clash 启动 / 停止、配置变化。
- **订阅点：** 服务进程里 `NodeIpNotifier.start()` 只订阅一次（幂等），由 `DynamicNotificationModule.run()` 和 `ClashManager.queryTunnelState()` 触发。
- **功耗：** 没有新增 ticker / 定时器 / 轮询（只有 IP 变化时才排一个 0.8 秒的一次性延时）；通知里不再每秒查速度（少了一次 JNI），文本只在 IP 变化后重建；息屏时通知不重建，亮屏后第一次更新才用新文本；hook 只在内核本来就要开 socket 时做一次 map 查询；探测只在 IP 变化后首次读取时发 1~2 个请求。测速和探测自己发起的连接不会被记成"使用中"。

- **r42：首页和通知只显示最近一个请求走的那个 IP（一个）。** 下面 r32 说的"最近 1.5 秒内的所有 IP、最多 3 个、等 N 个"不再显示：`CurrentSet()` 只返回集合里最新的那个（`more` 恒为 0）；集合本身（`recent`）照旧记着，测速页的在用标记（#1 r41）和连接页还在用它；集合变了才通知这一点不变，所以两个 IP 轮流时显示的是上一次通知时最新的那个，下次进首页会刷新。
- **r32：显示的是"请求实际走过的 IP"，可以是几个。** 旧做法（上面"IP 怎么定的"）记的是内核**拨号**的 IP：多路复用时新请求直接放在已经开着的连接上，不会拨号，首页就不变；`tcp-concurrent` 并发拨号时输掉的那几个 IP 也被记进去。新做法：内核的 xhttp 客户端给每个请求（新连接和复用的连接一样）都有一次 `GotConn` 回调，里面能拿到**这个请求实际用的那条连接的对端地址**；补丁 `patches/mihomo/0002-request-remote.patch` 在三个 `GotConn` 里加一行，把它报给应用（`dialer.NoteUse` → `dialer.SetUseHook`）。应用（`ipdelay/inuse.go`）按节点记下"每个 IP 最近一次被请求用到的时刻"，**显示的是最近一个请求之前 1.5 秒内（`useWindow`）所有请求用到的 IP，去重，最近用的排前面，最多 3 个，再多写"等 N 个"**（例：`规则模式 · 🇺🇸 104.17.146.59 · 🇺🇸 172.64.79.23`）。窗口以最近一次请求的时刻为准，不是以"现在"为准：没有新请求时一直保持上一批，不会清空；下一个请求出现才重新算。一个网站的请求陆续用了两个 IP 就显示两个；一直在连续请求时只显示最近 1.5 秒里的。
  - **通知栏**用同一份文本：`🇺🇸 IP · 🇺🇸 IP · 节点名`（TunnelState.ipsText）。
  - **更新时机：** 只有"这一批 IP（排序后的集合）"和上次不同才排一个 0.8 秒的一次性通知（和原来合并拨号用的是同一个 `scheduleNotify`），两个 IP 轮流做"最近一个"不算变化；国家探测完成时照旧通知。没有新增定时器 / 后台任务；钩子在请求本来就要做的 `GotConn` 里被调用，没人监听时只是一次原子读取，有人监听时一次带锁的小 map 更新。我们自己的测速 / 国家探测（`selfDialing`）不计入。
  - **非 xhttp 的节点（r33）：** ws、普通 tcp 这类"一个请求一条自己的连接"的传输，补丁 0002 在 `component/dialer/conncount.go` 的 `DialContext`（0001 加的拨号包装）里，拨号成功后把**赢家连接**（`tcp-concurrent` 竞速赢的那个）的对端 IP 也报给同一个钩子（`opt.owner` 非空才报，`dialer-proxy` 之类不是本进程 socket 的不报）。这样 ws 节点显示的是真正连上的 IP，不再是竞速输掉的那几个（socket 钩子 `record` 会把每个并发 socket 都记下，那是 r32 之前 ws 显示不准的原因）。xhttp 新建连接时这里也会报一次，和 `GotConn` 报的重复，集合去重所以没有影响。没有任何请求记录时仍退回"最近拨的 IP"。
  - **IP 切换历史（#10）**：一个 IP 第一次进入这一批、或节点换了，才记一条；两个 IP 轮流做最近一个不会每次都记。
  - **连接页每条连接的"节点 IP"（#8）**（`ipdelay.Lookup`）也优先取"请求最近走过的 IP"，没有记录才退回拨号记录。注意：它仍然是节点级的一个 IP，不是每条连接各自的（内核没有给连接记下它走的哪条底层连接）。
  - **对照内核源码（AGENTS.md 第 8 条）：** mihomo 提交 `3f27ef39f8c96bbfbc8b0b9bb10f5c9ef60149d8`。`transport/xhttp/reuse.go`（`ReuseManager.GetTransport` 给每个新请求挑一个已有的传输或新建；这里每个传输是一条 h2 连接）、`transport/xhttp/client.go`（`DialStreamOne` / `DialStreamUp` / `DialPacketUp` 里已有的 `httptrace.ClientTrace.GotConn`，`httputils.NewAddrContext` 也是靠它记 `conn.NetAddr`，所以复用的连接上 `GotConn` 也会触发）、`adapter/outbound/vless.go`（`xhttp.NewClient` 之后设 `OnRemote`）。补丁只加一个字段 `Client.OnRemote`、三处调用、一处赋值和新文件 `component/dialer/usehook.go`。内核升级后这几处要重新对照。
  - **局限：** ⓪ 会复用连接的其他传输（grpc、h2、smux 多路复用）没有挂钩：新请求放在已开的连接上，不会拨号，只能显示最近一次拨号的 IP；① 这里没有 Go / Kotlin 编译环境，补丁用 `patch --dry-run` 在内核源码上按顺序（0001、0002）试打通过，Go / Kotlin 部分没有编译过，要靠 Actions 构建验证；② `stream-up` / `packet-up` 模式里单独的下载请求（GET）没有挂钩，只有主请求；你用的 `stream-one` 不受影响；③ `GotConn` 可能对一个请求回调多次，集合去重所以没有影响；④ 1.5 秒是按"一次访问"估的，间隔取小了一个网站的请求会被拆成几批，取大了连着打开两个网站会并成一批，要调改 `useWindow`。

**新增文件**
- `design/src/main/java/com/github/kr328/clash/design/custom/MainCustomCards.kt`：红字提示的显示控制，以及生成代理卡片副标题后缀。
- `app/src/main/java/com/github/kr328/clash/custom/MainCustomRefresh.kt`：`MainDesign.refreshCustom()`。
- `app/src/main/java/com/github/kr328/clash/custom/NodeIpEvents.kt`：首页接收"IP 变化"广播（started 期间注册）。
- `service/src/main/java/com/github/kr328/clash/service/custom/NodeIpText.kt`：通知正文文本（脏标记，变了才重建）。
- `service/src/main/java/com/github/kr328/clash/service/custom/NodeIpNotifier.kt`：订阅内核事件并发广播。
- `core/src/main/java/com/github/kr328/clash/core/custom/NodeIpWatcher.kt`：自己的 JNI 入口 `CustomBridge` + 订阅封装（不改上游 `Bridge.kt`）。
- `core/src/main/cpp/custom_bridge.c`：JNI 函数 `nativeSubscribeNodeIp`（调用 Go 导出的 `subscribeNodeIp`）。
- `core/src/main/golang/native/nodeip.go`：Go 导出 `subscribeNodeIp`，用 `fetch_report` 回调通知。
- `core/src/main/golang/native/ipdelay/inuse.go`（r32）：请求实际走过的 IP 的记录、`CurrentSet()`（首页 / 通知用）、`Current()`（取第一个）、集合的比较键。
- `patches/mihomo/0002-request-remote.patch`（r32，r33 加第 4 处）：内核补丁，新文件 `component/dialer/usehook.go`（`SetUseHook` / `NoteUse`）；改 `transport/xhttp/client.go`（`Client.OnRemote` 字段，三个 `GotConn` 里各一行）、`adapter/outbound/vless.go`（import 一行、`NewClient` 之后一行）和 `component/dialer/conncount.go`（`DialContext` 里 `NoteUse` 一处，这个文件是 0001 新建的，所以 0002 必须在 0001 之后应用）。由 `build-debug.yaml` 的 `Apply mihomo patches` 按文件名顺序在 0001 之后应用。
- `common/src/main/java/com/github/kr328/clash/common/custom/CustomIntents.kt`：广播 action。
- `core/src/main/java/com/github/kr328/clash/core/util/CountryFlag.kt`（同 #1）。
- 文案：`design/.../strings_custom.xml`（`main_*`）、`service/src/main/res/values/strings_custom.xml` 和 `values-zh/strings_custom.xml`（`custom_notification_no_ip`）。

**改了上游文件（冲突只会出现在这里）**
| 文件 | 改动 |
|---|---|
| `core/src/main/golang/native/delegate/init.go` | import 加 `cfa/native/ipdelay`；`Init` 末尾加 `ipdelay.InstallSocketHook(home)`（r11 起带 `home` 参数，见 #10 #11） |
| `core/src/main/golang/native/tunnel.go` | `queryTunnelState` 里取 `ipdelay.CurrentSet()`（r32 前是 `Current()`），响应结构加 `currentNode` / `currentIp` / `currentCountry`，r32 起再加 `currentIps`（每个 IP 和国家）/ `currentMore`（都带 `omitempty`） |
| `core/src/main/java/com/github/kr328/clash/core/model/TunnelState.kt` | 构造参数加 `currentNode` / `currentIp` / `currentCountry`（默认空串），r32 起再加 `currentIps: List<CurrentIp>` / `currentMore`，内嵌类 `CurrentIp`，函数 `ipsText()`（拼"国旗 IP · 国旗 IP 等 N 个"） |
| `design/src/main/res/layout/design_main.xml` | 加变量 `customIp`；App 名字 `TextView` 外包一层竖向 `LinearLayout`，其下加红字 `custom_geosite_notice`（默认 gone）；代理卡片去掉 `visibility`，`subtext` 改为 `@{customIp == null ? mode : mode + customIp}` |
| `design/src/main/java/com/github/kr328/clash/design/MainDesign.kt` | import `MainCustomCards`；加 `customCards` 属性和 `setCustomCards()`（写 `binding.customIp`） |
| `app/src/main/java/com/github/kr328/clash/MainActivity.kt` | import `refreshCustom` / `customNodeIpEvents`；`fetch()` 里加 `setCustomCards(clashRunning, state)`；`main()` 里加 `val customNodeIp = customNodeIpEvents()` 和 `select` 里一个 `customNodeIp.onReceive { design.refreshCustom(clashRunning) }` 分支 |
| `service/src/main/java/com/github/kr328/clash/service/clash/module/DynamicNotificationModule.kt` | import；`update()` 去掉 `queryTrafficNow` 及上传/下载速度变量，`setContentText` 改为 `NodeIpText.get(service)`；`run()` 里加 `nodeIpChanged` 广播通道、`NodeIpNotifier.start(service)` 和 `select` 里一个分支 |
| `service/src/main/java/com/github/kr328/clash/service/ClashManager.kt` | `queryTunnelState()` 里先调用 `NodeIpNotifier.start(context)`（幂等）；加 import |
| `core/src/main/cpp/CMakeLists.txt` | `add_library(bridge ...)` 源文件列表末尾加 `custom_bridge.c` |

**依赖的上游接口：** `statistic.DefaultManager.Range` + `Tracker.Chains()`；`dialer.DefaultSocketHook`（在 `delegate.Init` 里被上游赋值，这里在它之后包一层，所以必须在赋值之后调用）；`bridge.h` 里的 `fetch_report` / `release_object`、`main.c` 里 `JNI_OnLoad` 已注册的 `FetchCallback.report`；`Bridge` 的 `System.loadLibrary("bridge")` 库名；`DynamicNotificationModule` 的 `receiveBroadcast` / `select` 循环；`Clash.queryTunnelState()`；`libclash.h` 由 cgo 根据 `//export` 自动生成（新增导出函数会自动出现，不用改头文件）。

## 8. 连接列表页（首页"连接"）
**效果：** 首页"日志"上方多一个"连接"入口，打开后列出内核当前的连接（最新的在最上面，最多 300 条，超出时顶部写明）。
- 每行三行字：目标 `域名:端口`；实际承载连接的节点（`国旗 节点名 · 节点 IP`，IP / 国旗只有内核已经知道时才有，不触发探测）；`TCP · 规则 · ↑上传 ↓下载 · 应用包名 · 开始时间`。
- 点一行弹出详情（目标、来源、网络和入站类型、从策略组到节点的完整链路、节点 IP、规则、应用、开始时间、流量），可"关闭连接"。弹窗打开期间列表不刷新。
- 顶部栏右侧两个图标：刷新（手动）和 IP 切换历史（见 #10）。
- **刷新时机：** 进入页面 / 回到页面、手动点刷新、Clash 启动停止、**节点 IP 变化广播**（和首页同一个事件，见 #7）、以及页面在前台且代理在运行时**每 2 秒**一次。2 秒这个定时只在页面 started 且代理运行时才参与 `select`；页面进入后台 / 息屏后不再有任何唤醒（和首页本来就有的 1 秒流量 ticker 同一类，且更严）。代理没运行时不查询，显示"代理未运行"。
- r12 起（#15）：顶部有搜索框、按时间 / 按流量排序、"全部关闭"。查询带两个参数（排序、搜索词），见 #15。
- 数据来源：内核 `statistic.DefaultManager.Range`，每个连接取 `Info()` 的 JSON（即 REST API `/connections` 的稳定格式）再取字段，不依赖内核内部结构体的字段名。内核只在被问到时才处理，没有后台任务。

**新增文件**
- `core/src/main/golang/native/connlist/connlist.go`：取连接快照、按 id 关闭连接。
- `core/src/main/golang/native/connections.go`：Go 导出 `queryCustomConnections` / `closeCustomConnection`。
- `core/src/main/cpp/custom_bridge.c`：追加两个 JNI 函数 `nativeQueryConnections` / `nativeCloseConnection`；`CustomBridge`（`core/.../custom/NodeIpWatcher.kt`）里声明。
- `core/src/main/java/com/github/kr328/clash/core/custom/CustomConnections.kt`：服务进程里的封装。
- `core/src/main/java/com/github/kr328/clash/core/model/ConnectionSnapshot.kt`：`ConnectionItem` / `ConnectionSnapshot` 数据类和解析。
- `design/.../custom/ConnectionsDesign.kt`、`ConnectionAdapter.kt`（含 `ConnectionText`）、`res/layout/design_connections.xml`、`res/layout/adapter_connection.xml`；文案 `connection*`。
- `app/src/main/java/com/github/kr328/clash/custom/ConnectionsActivity.kt`。

**改了上游文件：** 见总表（`IClashManager.kt`、`ClashManager.kt`、`AndroidManifest.xml`、`design_main.xml`、`MainDesign.kt`、`MainActivity.kt`）。app 进程通过 IClashManager 的两个新方法取 JSON（返回 `String`，Go 侧限制 300 条，远低于 binder 1 MB 上限）。

**依赖的 mihomo API：** `statistic.DefaultManager.Range`、`Tracker.ID()` / `Info()` / `Chains()` / `Close()`，以及 `Info()` 能被 `json.Marshal`（REST API 同款）。

## 9. （已删除）首页配置卡片显示剩余流量和到期时间
- r12 起删除：这个信息上游本来就能看到（订阅页），不需要在首页重复。
- 已还原的上游文件挂钩：`design_main.xml`（变量 `customProfile`、配置卡片 `subtext`）、`MainDesign.kt`（`setCustomProfile`）、`MainActivity.kt`（`setCustomProfile` 调用、`ProfileUpdateCompleted` 事件），并删除 `ProfileUsage.kt` 和 `main_profile_*` 文案。
- 编号 9 不再使用，别的条目的编号不变。

## 10. IP 切换历史
- 内核在"节点 / IP / 国家"变化并通知应用时（#7 的同一时机），把这次切换记到 `<filesDir>/custom/ip_history.json`，最多 50 条；同一节点 IP 连续出现不重复记，只是国家后来才查到时补全最后一条。
- 只在发生切换时写文件（先写临时文件再改名），没有定时写入。服务重启后历史仍在（文件）。
- 查看：连接页右上角的历史图标，弹出列表（新的在上）。app 进程直接读这个文件，不走 IPC。
- 文件：`core/src/main/golang/native/ipdelay/persist.go`（`recordHistory`）；`core/.../custom/IpHistory.kt`；弹窗在 `ConnectionsDesign.showHistory()`。目录来自 `InstallSocketHook(home)` 的 `home`（`filesDir/clash`）的上一级加 `custom`，和 Kotlin 侧 `filesDir/custom` 一致。

## 11. 国家缓存持久化
- 经节点 + IP 查到的出口国家（#1 测速、#7 探测得到的）存到 `<filesDir>/custom/country_cache.json`（键 `节点名|IP`）。重启 / 重载配置后直接用，不再重新探测，首页国旗马上就有。
- 7 天内有效；最多 300 条（超出丢最旧的）。重新测速会刷新结果。查失败的不保存。
- 写文件时机：一次手动测速结束后写一次；探测到新国家时写一次；内容没变（1 小时内同样的答案）不写。不用定时器。
- 加载时只取当前配置里有的节点名。`CaptureConfig` 原来每次重载都清空国家表，现在改为用缓存填充。
- 文件：`core/.../ipdelay/persist.go`（`savedCountries` / `rememberCountries`）、`ipdelay.go` 里的 `CaptureConfig` / `probeCountry` / `testProxy` / `TestGroup`。

## 12. 测速结果标注时间
- 代理页节点卡片的 IP 行最下面多一行小字"3 分钟前测速"（刚刚 / N 秒 / 分钟 / 小时 / 天前）。时间是该节点这次逐 IP 测速完成的时刻（`IPDelay.at`，毫秒）。
- 文字在 View 绘制时按当前时间算，**不会自己走**；再次进入页面或重绘时更新。没有定时器。
- 改动：`ipdelay.go`（`IPDelay.At`、`testProxy` 里写入）、`IpDelay.kt`（`at`）、`ProxyView.kt`（`onMeasure` 多一行高度、`onDraw` 画这一行）、文案 `ip_delay_age_*`。

## 13. GeoSite 下载成功后一键重载
- #6 的下载区在下载成功后显示"立即重载配置"按钮，点一下发 `ACTION_OVERRIDE_CHANGED` 自广播（服务里 `ConfigurationModule` 收到后重新加载当前配置，新 geosite.dat 即生效）。服务没运行时没人接收，下次启动自然会用新文件，界面会这样提示。
- 开始新的下载时按钮隐藏。
- 改动：`preference_geosite_download.xml`（`reload_button`）、`GeoSiteDownloadPreference.kt`、文案 `geosite_download_reload*`。没有新增上游文件改动。

## 14. 构建前带重试地预下载 geo 文件（仅 build-debug）
- 原因：上游 `app/build.gradle.kts` 的 `downloadGeoFiles` 任务从 GitHub 下载 4 个文件（`geoip.metadb`、`geosite.dat`、`ASN.mmdb`、`BundleMRS.7z`），没有重试，GitHub 返回一次 504 整个构建就失败（r11 第二次构建的失败原因）。
- `.github/workflows/build-debug.yaml`：新增 `Prefetch geo files` 步骤，用 `curl` 下载到 `app/src/main/assets/`（上游任务本来写入的位置，已在 `.gitignore`），每个文件最多重试 8 次、间隔 20 秒，文件必须非空；`Build` 命令加 `-x :app:downloadGeoFiles` 跳过上游任务。`Prefetch Gradle wrapper` 的重试也加到 8 次、间隔 20 秒。
- 没改任何上游文件（用 Gradle 命令行参数跳过任务）。下载地址、文件名要和上游 `downloadGeoFiles` 里的保持一致；上游增删文件时这里要同步。
- 同步上游时 `build-debug.yaml` 冲突，保留这一步和 `-x` 参数即可。`build-pre-release.yaml` 没有加。

## 15. 连接页：搜索、排序、全部关闭、少做无用功（r12）
**效果：**
- 搜索框：输入一个或多个词（空格分隔，全部要匹配，不分大小写），在域名、IP、端口、应用包名、规则、链路里所有节点 / 策略组名中查找。搜索覆盖**全部**连接，不只是显示的 300 条；摘要变成"匹配 N / 共 M 个连接"。
- "按时间 / 按流量"按钮：切换排序（最新在前 / 上传加下载最多在前）。按流量时是在全部连接里取最多的 300 条。
- "全部关闭"按钮：确认后关闭所有连接（节点或 IP 切换后常用，让应用重新连到新路径）。内核侧用特殊 id `*`（`CustomConnections.CLOSE_ALL`）表示全部，所以没有新增 IPC 方法。
- **刷新少做无用功：** 答案（JSON 原文）和上次一样、且排序 / 搜索词 / 运行状态没变时，不解析、不 diff、不重画；摘要文字没变不重写。列表行本来就按连接 id 做 diff，只有流量变了的行才重绑。
- **Go 侧成本：** 默认（按时间、无搜索）时只把"本次新出现的连接"和"要显示的 300 条"转成 JSON；其余连接只查一次开始时间缓存（`startCache`，每次查询重建，只含当时活着的 id）。连接数上千时比以前每条都 marshal 省很多。按流量或有搜索词时必须看全部连接，会全部转换（只在用户这样选时才发生）。
- 功耗：没有新增定时器；每次输入搜索词会立即查询一次（本地调用）。

**改了的文件（都是 #8 已有的挂钩，没有新增上游文件）**
- `core/src/main/golang/native/connlist/connlist.go`（`Query(sortMode, filter)`、`Close` 支持 `*`）、`connections.go`、`core/src/main/cpp/custom_bridge.c`（`nativeQueryConnections(sort, filter)`）。
- `core/.../custom/NodeIpWatcher.kt`（`CustomBridge`）、`CustomConnections.kt`、`ConnectionSnapshot.kt`（新增 `matched`）。
- `IClashManager.kt` / `ClashManager.kt`：`queryCustomConnections` 多了 `sort`、`filter` 两个参数（总表已更新）。
- `design/.../custom/ConnectionsDesign.kt`、`design/.../res/layout/design_connections.xml`、`app/.../custom/ConnectionsActivity.kt`、文案 `connections_*`。

## 16. 下载的 geo 文件更新应用后自动恢复；GeoIP / ASN 也能按地址下载（r12）
**效果：**
- "设置 → Meta 特性 → Geo 文件"下面现在有三块：GeoSite（`geosite.dat`）、GeoIP（`geoip.metadb`）、ASN（`ASN.mmdb`），每块一个地址框、"下载并安装"、下载成功后"立即重载配置"。默认地址都是 MetaCubeX 的 latest 发布；GeoSite 保存地址的键不变，旧设置保留。
- 校验：GeoSite 看首字节 `0x0A`；GeoIP / ASN 看文件尾 128 KiB 里有没有 MaxMind DB 的元数据标记 `\xAB\xCD\xEFMaxMind.com`。通过才替换，失败不动原文件。
- **自动恢复：** 下载成功后另存一份副本到 `<filesDir>/custom/geo/`。应用更新后第一次启动，上游 `extractGeoFiles()` 照旧把文件换成内置版，紧接着挂钩 `GeoFileKeeper.restoreAfterUpdate()` 把副本放回（早于内核读 geo 文件）。恢复后的文件修改时间是"现在"，所以上游之后不会再删它。每次应用更新只处理一次（`handled_update` 记录应用的 `lastUpdateTime`），用文件锁防止 app 进程和服务进程同时恢复。
- 从没下载过的人：`custom/geo/` 不存在，挂钩什么都不做（零成本）。
- 首页红字提示：有 GeoSite 副本就不再显示。只有旧版（r11 及以前）下载、没有副本的文件，第一次更新后仍会被换回内置版，红字照旧提示，重新下载一次就有副本了。
- 已知边界：如果用户下载后又用上游"导入 geo 文件"手动覆盖，再更新应用，恢复的是下载的那份副本，不是手动导入的。两个进程恰好在同一毫秒内一起启动时，理论上有极小概率被上游的解压覆盖一次（下次更新之前文件会是内置版，红字不会提示）。
- 功耗：只在用户点下载时联网；启动时多一次读小文件，更新后第一次启动多拷贝几个文件。无定时器。

**新增文件：** `design/.../custom/GeoFile.kt`（三种文件的名字、默认地址、校验）、`GeoFileKeeper.kt`（副本和恢复）。
**改了的文件：** `GeoSiteDownloader.kt`（下载哪个文件由参数决定，成功后保存副本）、`GeoSiteResetNotice.kt`（有副本就不提示）、`GeoSiteDownloadPreference.kt`（`geoSiteDownload()` 一次加三块；标题、地址提示、说明文字在代码里设置）、`preference_geosite_download.xml`、文案 `geofile_*`；上游文件 `MainApplication.kt` 一行挂钩（见总表）。

## 17. 备份与恢复（r12）
**效果：** "设置 → 应用"最下面新增"备份与恢复"：
- **导出备份：** 选保存位置，生成一个 zip：`backup.json`（订阅列表、当前启用的订阅、服务设置、界面设置、三个 geo 下载地址、覆盖设置原文）加 `profiles/<uuid>/` 下每个订阅的文件。`type` 为 External 的订阅（别的 App 创建的）不导出。**文件里有订阅地址和 age 密钥，要自己保管。**
- **导入备份：** 选 zip，先显示备份时间和订阅数让用户确认，然后：恢复服务设置和界面设置（替换现在的）、写回覆盖设置、逐个添加订阅（名称 + 类型 + 地址都相同的已有订阅跳过）。订阅走上游自己的流程（`create` → 写入备份里的文件 → `patch` 设更新间隔 → `commit`），订阅类型的会**重新联网下载**；失败的留在订阅页，列在结果里，可手动更新。如果现在没有启用的订阅，才把备份里启用的那个设为启用。
- 不在备份里：geo 文件（可重新下载）、每个订阅的节点选择记录、缓存、签名密钥。"隐藏应用图标"不恢复（它是启动器组件的开关，不只是一个设置项）。
- 覆盖设置、服务相关设置需要重新加载配置或重启服务后生效（结果里会提示）。
- zip 解压时检查路径，防止条目跳出临时目录（zip slip）；临时文件在 `cacheDir/custom_restore`，恢复完或取消后删除。
- 功耗：只在用户点导出 / 导入时运行，无后台工作。
- 不闪屏（r17）：`BackupActivity` 用透明、无动画的主题 `BackupTranslucentTheme`（`design/.../res/values/themes_custom.xml`），并在装内容前把窗口背景设成透明，所以文件选择器打开 / 关闭时设置页一直在后面，不会闪一个空白页。"请稍候"是个小黑条，有对话框时隐藏。

**新增文件：** `design/.../res/values/themes_custom.xml`（透明主题）、`app/.../custom/BackupActivity.kt`（选文件、确认、结果）、`BackupManager.kt`（导出 / 导入逻辑，JSON 用 `org.json`）、`design/.../custom/BackupDesign.kt`（几乎空白的页面，只显示状态和对话框）、`design/.../preference/BackupPreference.kt`（`PreferenceScreen.customBackup()`，用类名启动 `BackupActivity`，因为 design 模块看不到 app 模块）、文案 `backup_*`。
**改了上游文件：** `AppSettingsDesign.kt`（一行 `customBackup()`）、`AndroidManifest.xml`（`BackupActivity`）。
**依赖的上游接口：** `IProfileManager` 的 `create` / `patch` / `commit` / `queryAll` / `queryActive` / `queryByUUID` / `setActive`；`pendingDir` / `importedDir`；`ServiceStore` 的各个属性（上游新增设置项时可在 `BackupManager.serviceToJson` / `restoreSettings` 里加）；`override.json` 在 `filesDir/clash` 下（内核的 `OverrideSlotPersist` 路径）。

**r34：四个入口的图标各不相同。** 原来导出备份和导出诊断包都是保存图标，导入备份和配置历史都是还原图标。现在：导出备份 = 保存（`ic_baseline_save`），导入备份 = 下载（`ic_baseline_get_app`），配置历史 = 还原（`ic_baseline_restore`），导出诊断包 = 信息（`ic_baseline_info`）。只换图标，`BackupPreference.kt` 里四个 `icon =`。

**r38：窄屏上首页右上角的三点被挤出屏幕。** 名字那一列原来是 `wrap_content`，吃掉了整行剩余宽度；改成 `0dp` + `layout_weight=1`，让它收缩，三点图标始终留在屏幕里（见 #32）。

**r37：补上了丢失的透明主题。** `AndroidManifest.xml` 里 `BackupActivity` 的 `<activity>` 没有 `android:theme`，所以上面"不闪屏"那条实际没生效：本页是不透明的，所有对话框（本地导出 / 导入、坚果云设置、云端备份 / 恢复）后面是黑底。现在加上 `android:theme="@style/BackupTranslucentTheme"`，对话框盖在首页（或设置页）上。

**r35：导出备份 / 导入备份的入口搬到了首页右上角菜单**（见 #32）。"设置 → 应用"的"备份与恢复"里只剩"配置历史"和"导出诊断包"；`BackupPreference.kt` 删了导出 / 导入两项，逻辑（`BackupActivity` 的 `ACTION_EXPORT` / `ACTION_IMPORT`）没动。

## 18. 构建时的下载：缓存和重试（仅 build-debug）
- 上游 workflow 本来就缓存了 Go 编译缓存和模块（`actions/cache`），`gradle/actions/setup-gradle` 也缓存 Gradle，这两处不用再加。
- `build-debug.yaml`：新增 `Restore geo files cache` 步骤（缓存 `app/src/main/assets`，key `geo-files-<run_id>`，靠 `restore-keys` 取最近一次的）；`Prefetch geo files` 改成先下到 `.part` 再改名，重试 8 次都失败时，如果缓存里有旧文件就用旧文件并给出警告，而不是让整个构建失败。每次仍然先尝试下载最新的。
- **r17 又加（构建日志里的其余下载）：**
  - 子模块 mihomo（体积最大）：先按固定 commit 缓存 `.git/modules`（`Submodule commit` / `Restore submodule cache` / `Save submodule cache`）；检出失败重试 6 次，每次先清掉半截的克隆。
  - Go 工具链：`Restore Go toolchain cache` / `Save Go toolchain cache` 缓存 `/opt/hostedtoolcache/go-*`（已打过补丁的版本，key 含补丁文件的哈希），命中时 `setup-go` 在工具缓存里直接找到，不再下载；`Apply Patches` 用 `.patched` 标记，缓存里的工具链不会重复打补丁。
  - CMake 3.22.1：`Restore CMake cache` / `Save CMake cache`（构建失败时也保存，`if: always()`），省掉每次从 Google 现装。
  - `Update CA`：runner 上已有 `ca-certificates` 时不再 `apt-get update / install`。
  - 以前就有、没变：Go 模块和编译缓存（`actions/cache`，key 按 `go.sum`）；Gradle 依赖缓存（`setup-gradle`）；Gradle wrapper 预下载重试；geo 文件重试加缓存兜底。
  - `Setup Go` 日志里的 `Restore cache failed: Dependencies file is not found` 警告是 `setup-go` 自带缓存找不到根目录的 `go.sum`，无害（Go 缓存由上面单独的 `actions/cache` 负责）。
- 所有保存缓存的步骤都带 `continue-on-error: true`，缓存失败不会让构建失败。第一次运行时缓存都是空的，要成功构建一次后才有。
- 没改上游文件。同步上游时 `build-debug.yaml` 冲突，保留这些步骤即可。

## 19. 自检脚本 `scripts/check-custom.sh`
- 用法：`bash scripts/check-custom.sh`（在 git 仓库里、有 `main` 分支时最准；没有 `main` 分支时用宽松检查）。
- 检查：① 带 `CUSTOM` 标记且存在于上游（`main` 分支里有）的文件，必须在本文件"上游文件总表"里；② 带标记的新文件，本文件里必须提到；③ 总表里的每个文件都还存在、还带 `CUSTOM` 标记（合并时挂钩没丢）。有问题时列出来并返回 1。
- 每次改动后、同步上游后各跑一次。上游文件名、标记写法（`CUSTOM`）变了才需要改脚本。

## 20. 底层连接页：每个节点的底层连接数（多路复用有没有生效）（r16，r27 加每个 IP 的开始时间）
**效果：** 首页"连接"下面多一个"底层连接"入口，打开后是单独的一页（连接页本身不变）。页面里每个节点一行，节点下面按对端 IP 拆开，所有行一次显示（超出屏幕高度时整页滚动，没有行数上限），字号、颜色、行距和连接页的概览行一致：
```
节点 A · 底层 5 条 / 请求 40 个
    🇺🇸 162.159.153.2 · 3 条 · 13:42:07
    104.18.2.5 · 2 条 · 13:51:30
节点 B · 底层 1 条 / 请求 12 个 · UDP 2 个
    UDP 8.8.8.8 · 1 个 · 13:40:03
    UDP 地址未知（没指定对端） · 1 个 · 13:40:03
```
- 页面顶部一行说明文字（小字）；右上角刷新按钮；代理没运行时显示"代理未运行"，没有连接时显示"当前没有连接"。
- **刷新时机：** 打开 / 回到页面、点刷新、Clash 启动停止、以及页面在前台且代理在运行时每 2 秒一次（和连接页同一套：页面进入后台后定时分支不再参与 `select`，没有任何后台唤醒）。答案和上次一样就不解析、不重画。
- **底层 N 条**：内核真正为这个节点开着的 TCP socket 数（和终端里 `ss` 看到的出站连接一致）；开了多路复用（smux 等）或 gRPC / h2 时，N 远小于请求数。**请求 M 个**：内核连接列表里由这个节点承载的连接数（连接的路径里第一个名字就是承载它的节点）。
- 每个 IP 一行：该节点现在有几条连到这个 IP；国旗只有已经知道（测速或 #7 探测过）时才显示，不额外查询。没有连接的 IP 不列。
- **每个 IP 的开始时间（r27）**：每个 IP 行（TCP 和 UDP 都有）末尾加该节点"开始连这个 IP"的时刻。含义：这个 IP 的底层连接数从 0 变成 1 的那一刻；之后只要还有连接活着就不变（其中一条断了、另一条还在，时间不变）；全部断开后再连上，算新的一次，时间重新记。显示成绝对时间 `13:42:07`，不是今天的写成 `11-10 23:08`；不写"几分钟前"，因为那要定时刷新才会准。时间在内核补丁里记（socket 登记表里每个"节点+网络+IP"除了计数多存一个毫秒时间，登记时顺带取一次 `time.Now()`，没有任何额外定时器或后台工作），随原来的查询一起带到界面，页面只在原来的刷新点（打开、点刷新、前台每 2 秒那一套）重画。退出 App / 内核重启后清空。开始时间是 socket 的创建时间，不是"请求"的开始时间（后者是内核连接列表里的 `start`，那一页用不上）。
- **UDP（r24 起列地址）**：节点行末尾写 `UDP u 个`（该节点开着的 UDP socket 数，包括 QUIC 类节点），节点下面再按对端地址各列一行 `UDP 地址 · n 个`（和 TCP 的地址行同样的国旗规则，排序和 12 条上限也一样）。内核记录的就是 socket 创建时的对端地址；用 `ListenPacket` 创建、当时没指定对端的 socket 没有地址，显示"地址未知（没指定对端）"。数据本来就在 `dialer.ConnCounts` 里（补丁没改），只是以前没有显示。QUIC 类节点（hysteria、tuic）整个会话在一个 UDP socket 上，底层数本来就是 1 左右，不能用来判断复用。
- 没有归属节点的 socket（DNS 等）归到"其他（不属于任何节点）"。链式代理（`dialer-proxy`）只算真正的物理 socket，算在第一跳节点上，里层节点会显示 `底层 0 条 / 请求 M 个`。
- 节点按底层数从多到少排，最多 30 个节点、每个节点最多 12 个 IP（节点行的数字是全部的合计，不受截断影响）。
- 打开了 smux 的 `statistic: true` 时，承载连接本身也会登记成内核连接，并算进"请求"数（也会让首页的流量统计翻倍，那个选项本来是调试用）。
- **关闭网站后数字不会马上归零**：空闲的复用连接会保留一段时间才断开，不等于复用没生效。

**怎么数的（改了内核，见下）：** 内核里所有出站 TCP / UDP 连接都经过 `component/dialer` 的 `DialContext` / `ListenPacket`。补丁在这两个函数外面各包一层：socket 建成时按"节点名 + 对端 IP + 网络"登记一次，关闭时注销一次（`Close` 里注销，幂等；没关就被回收时由 finalizer 注销）。节点名来自 `Base.DialOptions()` 新加的 `WithOwner(b.name)`，所有节点类型共用这一个入口，smux 的承载连接也是经节点自己的拨号建的，所以同样会被数到。经自定义拨号器（如 `dialer-proxy` 的 `netDialer`）得到的连接不是本进程的 socket，不计。读数只在这一页查询时做（`dialer.ConnCounts()`）。
- **走的通道：** 复用 #8 的 `queryCustomConnections(sort, filter)`：`sort` 取 2（`SortMux`）时，`connections.go` 里的导出函数改为返回 `connlist.QueryMux()`（只数连接、不转换，也不碰连接页的缓存），所以没有新增 JNI 函数、没有给 `IClashManager` 加方法。
- **功耗：** 没有定时器、轮询、后台线程；建立和关闭 socket 时各一次带锁的 map 加减；数据只在这一页前台查询时读一次。连接页不受影响（它的查询不带这些）。
- 包装类型内嵌了真正的 `*net.TCPConn` / `*net.UDPConn`，所以它们的全部方法（`SyscallConn`、`ReadMsgUDP`、`CloseWrite`、`ReadFrom` 等）都还在，只有 `Close` 多做一步；唯一变化是类型断言 `.(*net.UDPConn)`，内核里对拨号结果这样断言的只有 `common/net/packet`，不是 `*net.UDPConn` 时它自己有通用后备。

**新增文件**
- `patches/mihomo/0001-conn-count.patch`：给 mihomo 打的补丁（**内核文件不直接改，构建时由 workflow 应用**）。内容：新文件 `component/dialer/conncount.go`（登记表（r27 起每项除了计数还存"从 0 变 1 的时刻" `since`）、包装类型、`DialContext` / `ListenPacket`、`WithOwner`、`ConnCounts`（带 `Since`））；`component/dialer/dialer.go` 里原来的 `DialContext` / `ListenPacket` 改名为 `dialContextRaw` / `listenPacketRaw`（各一行）；`component/dialer/options.go` 的 `option` 结构体加 `owner string`；`adapter/outbound/base.go` 的 `DialOptions()` 末尾加 `dialer.WithOwner(b.name)`。
- `core/src/main/golang/native/connlist/mux.go`：`QueryMux`、`MuxNode` / `MuxIp` / `MuxSnapshot`、`SortMux`；`ipdelay.go`（#1 #7 的文件）加 `CountryOf(node, ip)`（只读缓存）；`connections.go`（#8 的文件）里 `queryCustomConnections` 加一个 `SortMux` 分支；`core/.../custom/CustomConnections.kt` 加常量 `SORT_MUX`。
- `core/src/main/java/com/github/kr328/clash/core/model/MuxSnapshot.kt`（`MuxNode` / `MuxIp` / `MuxSnapshot` 和解析）。
- `design/.../custom/MuxDesign.kt`、`MuxText.kt`（把节点列表排成文字）、`res/layout/design_mux.xml`；文案 `mux_*`、`connections_mux_*`。
- `app/src/main/java/com/github/kr328/clash/custom/MuxActivity.kt`。

**改了上游文件：** `.github/workflows/build-debug.yaml`（`Checkout submodules` 和它的缓存步骤之后加 `Apply mihomo patches` 步骤，对子模块目录 `patch -p1`，先 `--dry-run`，打不上就让构建失败；它**不是** `Apply Patches` 那个对 `GOROOT` 打补丁的上游步骤）；首页入口：`design_main.xml`（"连接"后面加一个 `LargeActionLabel`）、`MainDesign.kt`（枚举加 `OpenMux`）、`MainActivity.kt`（一个 `OpenMux` 分支和 import）、`AndroidManifest.xml`（`MuxActivity`），见总表。
**依赖的上游接口（升级内核后先查这些）：** `dialer.DialContext` / `dialer.ListenPacket` 的签名、`option` 结构体和 `applyOptions`、`Base.DialOptions()`、`dialer.Dialer` 的 `Opt`（`WithOption(d.Opt)` 会带上 `owner`）、`statistic.Tracker.Chains()`。补丁每个文件只有几行 diff，对不上时按上面的描述在新内核上重做。
**已知限制：** 补丁最初是对着 mihomo Alpha `478af2a57c48bdb8e85f6ed93b2c3735e90ceabd` 写的。r27 起已对照实际构建用的子模块提交 `3f27ef39f8c96bbfbc8b0b9bb10f5c9ef60149d8` 的源码核对：`patch -p1` 能干净打上（`dialer.go` 的 `DialContext` / `ListenPacket`、`options.go` 的 `option`、`base.go` 的 `DialOptions()` 都在原位）；`conncount.go` 里计数和 `since` 的逻辑单独编译并跑过一个小测试（两条连接同 IP、断一条时间不变、全断后重连时间更新）。**但整个内核（含依赖）和 App 没有本地编译过**（这里没有网络，拉不下 Go 依赖），Kotlin 部分只做了人工检查。第一次 Actions 构建失败时，先看 `Apply mihomo patches` 步骤的输出。

## 21. 错误与诊断页（r18，r23 加折叠，r24 修折叠和清除）
**效果：** 首页"日志"上面多一个"错误与诊断"入口。页面把**已经存在的数据**排成文字，一页看清"哪里出错、什么原因"，四块（哪块没内容就不显示）：
1. **软件崩溃 / 异常退出**：时间、进程、线程、版本和堆栈前 8 行（Java 崩溃，由 `CrashRecorder` 记录）；加上 Android 自己记录的"原生崩溃（内核异常退出）/ 无响应 ANR / 内存不足被系统结束 / 进程初始化失败"（Android 11 以上，`getHistoricalProcessExitReasons`）。
2. **节点测速超时**：当前配置所有代理组里最近一次测速超时的节点（`delay` 大于 `Short.MAX_VALUE`，0 是没测过，不算）。
3. **连接失败**（按节点分组，失败多的在前）：每条写时间、TCP/UDP、目标 `host:port`、命中的规则、次数 `×n`，下一行是"原因：<中文解释>（内核原文：…）"。网站打不开就在这里：内核每次拨号失败本来就写一行 `[TCP] dial <节点> (match <规则>) <源> --> <目标> error: <原因>`，这里只是把它拆开显示。
4. **其他警告 / 错误**：内核的其他 WARNING / ERROR 日志原文（配置、DNS、订阅更新等），能识别原因时附一行中文解释。
- 原因归类（`errlog.classify`）：超时、被拒绝、被重置、对方提前关闭、域名解析失败、网络不可达、TLS / 证书、认证失败、被拒绝（规则）、其他；中文 / 英文解释在 `strings_custom.xml` 的 `errors_code_*`。认不出的原因直接显示内核原文。
- **折叠（r23）：** 不算问题的几类日志不再当条目记，只计数，页面末尾一个"已折叠"小块，每类一行（次数 + 最近时间）：① **当前网络没有 IPv6**：原因是"network is unreachable"，并且文字里的地址全是 IPv6（文字里只要有一个 IPv4 地址就不折，照常记录）；② **主动取消 / 连接已关闭**：`context canceled`、`use of closed network connection`、`broken pipe`。`connection reset by peer`、IPv4 下的不可达和超时都不折，因为可能是节点被封的真信号。折叠的不进"连接失败 / 其他警告"的次数，也不占 300 条的环；清除记录会一起清零。判断在 `errlog.record()` 里（`foldKind`），只做几次字符串判断，没有新的 goroutine 或定时器。
- **r24 修正：** ① 拨号失败的原因可以有多行（每个地址一行），之前 `reDialRule` / `reDial` 的 `.` 不匹配换行，这类日志整条落到"其他"、折叠规则也被来源地址 `127.0.0.1` 挡住；现在两个正则加了 `(?s)`，多行原因解析成"连接失败"，折叠只看原因部分（不含来源地址）。② **清除记录也会收起"节点测速超时"**：这一块是节点最近一次测速的结果，不是日志，内核里没有可清的东西，所以点清除时把当前显示的节点名存进页面自己的 SharedPreferences（`custom_errors` / `dismissed_timeouts`），之后不再显示；某个节点测速通过一次后它的名字就从保存里去掉，再超时会重新出现。局限：同一个节点清除后再测仍然超时，不会重新出现，要等它通过过一次。读写只发生在页面刷新和点清除时。
- 右上角：复制全部、清除记录、刷新。文字可以长按选择。代理没运行时只显示崩溃记录，并说明连接失败和节点超时要代理运行时才能看。
- **局限：** ① 连接失败记录在内核进程里，只保留最近 300 种（相同的合并计数），内核重启后重新计数；② 订阅更新失败、服务启动失败等 App 本来就弹提示的错误没有被另外保存，只有它们同时写进内核日志时才会出现在第 4 块；③ Go 内核自己崩溃时 Java 来不及记录，靠 Android 的退出记录（Android 11 以上）；④ 内核日志行格式没有逐版本核对，不符合的行会按"其他"显示原文，不会丢。

**怎么做的（没有任何新采集）：**
- Go：新包 `core/src/main/golang/native/errlog/`。`init()` 里订阅内核的 `log.Subscribe()`（和 `log.go` 同一个机制；订阅能收到所有等级，不受用户设的日志等级影响），只留 WARNING / ERROR，放进 300 条的环（相同的 种类 + 节点 + 目标 + 原因 合并，次数 +1，时间更新）。阻塞在 channel 上的一个 goroutine，没有日志就不占 CPU。
- 通道：复用 #8 / #20 的 `queryCustomConnections(sort, filter)`：`sort` 取 3（`SORT_ERRORS`）时 `connections.go` 返回 `errlog.Query()`；`closeCustomConnection("!errors")`（`CLEAR_ERRORS`）清空记录。没有新增 JNI 函数，没有给 `IClashManager` 加方法。
- 崩溃：`CrashRecorder.install()` 在 `MainApplication.onCreate()`（两个进程都会走）里设置默认未捕获异常处理器：真崩溃时写一个小文本文件到 `filesDir/custom/crash/`（最多 10 个），然后交给原来的处理器，崩溃行为不变。平时什么都不做。
- 页面：`ErrorsActivity` 只在打开 / 回到页面、代理启动停止、配置加载、点刷新时读一次；**没有定时器**。答案不变时不重画。

**新增文件**
- `core/src/main/golang/native/errlog/errlog.go`、`errlog_test.go`（r23 加 `Fold`、`foldKind` 和 `TestFold`）。
- `core/src/main/java/com/github/kr328/clash/core/model/ErrorSnapshot.kt`（`ErrorEntry` / `ErrorFold` / `ErrorSnapshot` / `CrashRecord` / `ExitRecord`）；`core/.../custom/CustomConnections.kt`（#8 的文件）加 `SORT_ERRORS`、`CLEAR_ERRORS`；`connections.go`（#8 的文件）加 errors 分支和清除。
- `design/.../custom/CrashRecorder.kt`、`ErrorsReport.kt`、`ErrorsText.kt`、`ErrorsDesign.kt`、`res/layout/design_errors.xml`；文案 `errors_*`。
- `app/src/main/java/com/github/kr328/clash/custom/ErrorsActivity.kt`。

**改了上游文件：** `design_main.xml`、`MainDesign.kt`、`MainActivity.kt`、`AndroidManifest.xml`（入口，同 #20）；`MainApplication.kt`（一行 `CrashRecorder.install(this)` 和 import），见总表。

## 22. 配置检查页（r20，r22 改为同配置只测一次）
**效果：** 首页"错误与诊断"上面多一个"配置检查"入口。点"ECH检查"（r29 起按钮叫这个名字，原来叫"开始测试"，功能不变）才会真的联网跑一次，检查配置里**写了的**字段有没有效，每项给出结论和原因。这一版只有第一项：节点的 `ech-opts`；r25 起另有"检查 DNS"按钮（见 #25）；其余字段（规则、provider 等）以后逐项加。
- **输入框：** 填一个确定被封的域名（例如 `xxx.workers.dev`），必须由节点的服务器（或它前面的 CDN）托管。只在测试里当内层 SNI 用，**不改配置**；最后填的域名记在页面自己的 SharedPreferences（`custom_config_check`）。
- **每项只用自己需要的字段，不代入整个配置：** ECH 项只读节点的 `type`、`server`、`port`、`alpn`、`servername` / `sni`、`ech-opts`，不碰 xhttp 路径、Host、UUID 等。以后每一项都这样。
- **ECH 配置从哪来：** `ech-opts.config` 写了就直接用（base64）；没写就和内核一样去 DNS 取：向 DoH 查某个域名的 HTTPS 记录（类型 65），取出里面的 `ech` 参数。查的域名依次是 `query-server-name`、节点的 `servername`、`sni`、输入框的域名。DoH 服务器依次是配置 `dns:` 里所有 `https://` 开头的地址（加载配置时 `ipdelay.CaptureDNS` 记下，`#` 后面的参数去掉；通过 JSON 遍历整个 `dns:`，不依赖内核结构体的字段名），再是三个公共 DoH（`223.5.5.5`、`1.12.12.12`、`1.1.1.1`）。DNS 报文自己拼、自己解析（`doh.go`），不引入新依赖。拨号都走内核的 `dialer.DialContext`，`hosts:` 映射生效，socket 不走 VPN。
- **怎么测（r22）：** 先按"ECH 配置内容 + ALPN"给节点分组，配置相同的节点算一组，**一组只测一次**；向 DNS 取配置也是每个名字只问一次（同一轮里缓存）。一组里所有节点的地址（字面 IP，或 `hosts:` 里这个名字的各个 IP，每个节点最多 8 个；都没有就用 `server` 本身）合并去重，**IPv4 排在 IPv6 前面**，一个一个试：每个地址做两次只到 TLS 握手为止的连接（开 ECH 和关 ECH，内层 SNI 都是输入的域名，ALPN 用节点自己写的，没写默认 `h2`，两次同时发），**拿到答复就停**（任一次握手通了，或服务器拒绝了 ECH 配置），否则换下一个，最多试 3 个地址。所以当前网络没有 IPv6 时，IPv6 地址要么排在后面根本没碰，要么几毫秒就报"网络不可达"。用 Go 标准库 TLS，所以"服务器是否接受了 ECH"是 TLS 自己报告的（`ConnectionState().ECHAccepted`）。不校验证书，只看握手能不能通过。每次握手最多等 6 秒，不同组最多同时 4 路。
- **显示（r22）：** 每组一段：节点名、ECH 配置来源、结论、试过的每个地址一行（开 / 关 ECH 各一个短结果，失败只写归类后的原因，认不出的原因才带内核原文，不再显示本机地址和端口）。不能测的节点（`enable` 不是 true、QUIC 系、config 不合法、取配置失败）仍各写一小段。
- **注意：** 结论针对"试过的那个地址"，同一组里别的 IP 如果被封的情况不同，这里看不出来；想逐个地址查，要另做。
- **节点状态：** 已测试；`enable` 不是 true；QUIC 系节点（hysteria、hysteria2、tuic、wireguard、ssh）不适用；`config` 不是合法 base64 或缺 server / port；没写 config 且从 DNS 取失败（列出每个 DoH 的失败原因）。
- **每组的结论（最后试的那个地址决定）：** 生效且绕过封锁（开能连上、关被封）；生效但网络没封这个域名（无法证明绕过）；服务器拒绝了配置（`ECHRejectionError`，配置过期或不匹配）；开了不通而关了能通；无法判断（两种都不通，或连上了但服务器没接受 ECH）。失败原因沿用 #21 的原因归类，另加 `echrejected`。
- **局限：** ① 测的是"这份 ECH 配置 + 这个服务器在当前网络下能不能藏住 SNI"，不是节点完整协议的连接（xhttp、gRPC、CDN 设置是另一层），页面底部有说明；② 握手用 Go 标准库 TLS，指纹不是节点的 `client-fingerprint`（uTLS 模拟的 Chrome 等），被封网络里指纹不同结果可能不同；③ 代理没运行时没有已载入的配置，页面显示"代理未运行"；④ 取消只是不再等结果，核心里的握手会自己在超时内结束；⑤ 构建要用支持 ECH 的 Go（构建用 1.26，没问题；`go.mod` 写的 1.20 只影响 `go vet` 的版本提示，不影响编译）。
- **功耗：** 没有定时器，没有后台任务，点一次测一次。
- **r45：** 页面多一个"测试其他 IP"（测不在配置里的 IP），见 #43。

**新增文件**
- `core/src/main/golang/native/conftest/conftest.go`、`doh.go`（DNS 报文和 DoH 取配置）、`conftest_test.go`（分组、`Run`、判定逻辑、握手）；`ipdelay.go`（#1 #7 的文件）加 `RawProxies()`、`NodeAddresses()`、`CaptureDNS()` / `DoHServers()`；`errlog.go`（#21）加 `Classify`；`connections.go`（#8 的文件）加 `SortCheck` 分支；`CustomConnections.kt` 加 `SORT_CHECK`。通道仍是 `queryCustomConnections`，`filter` 带请求 JSON `{"domain":"..."}`，没有新增 JNI 函数。
- `core/.../model/ConfigCheckReport.kt`（r22 加 `ConfigCheckGroup`，报告里 `groups` 是测过的组，`items` 只剩不能测的节点）。
- `design/.../custom/ConfigCheckDesign.kt`、`ConfigCheckText.kt`、`res/layout/design_config_check.xml`；文案 `check_*`、`errors_code_echrejected`；`ErrorsText.explain` 改为公开。
- `app/.../custom/ConfigCheckActivity.kt`。

**改了上游文件：** `design_main.xml`、`MainDesign.kt`、`MainActivity.kt`、`AndroidManifest.xml`（入口，同 #20 #21）、`config/load.go`（`CaptureConfig` 后面加一行 `ipdelay.CaptureDNS(rawCfg.DNS)`），见总表。

## 23. 没有 IPv6 的网络里，IPv6 节点的测速超时不算错误（r25）
**效果：** "错误与诊断"页的"节点测速超时"里，只能走 IPv6 的节点在当前网络没有 IPv6 时必然超时，这不是节点坏了。现在这些节点不再列进"测速超时"，改写进折叠块："当前网络没有 IPv6：N 个只能走 IPv6 的节点测速超时，属于预期现象，没有计入超时：节点名…"。网络有 IPv6 时一切照旧。代理页的延迟数字不变（内核照常测，只是错误页不当错误报）。
- **哪些节点算 IPv6 节点：** 按配置里写的判断，不按名字：`ip-version: ipv6`；或 `server` 是字面 IPv6 地址；或 `server` 在 `hosts:` 里的地址**全部**是 IPv6。域名同时有 IPv4 和 IPv6 的节点不算（它可能走 IPv4 能通）。`ipv6-prefer` 不算（会退回 IPv4）。
- **怎么知道当前网络有没有 IPv6：** `NetworkIpv6.hasIpv6()` 读系统对"VPN 之下那张网络"的描述（优先 Wi-Fi、有线，再其它，已验证的优先）：有全局 IPv6 地址（不含本地链路、唯一本地 fc00::/7、回环），并且有 IPv6 默认路由。**只在错误页刷新时读一次**，没有回调、没有轮询。读不出来（没网络、没权限、出异常）按"有 IPv6"算，宁可多报也不藏节点。
- **通道：** `queryCustomConnections(SORT_NODEINFO = 5, "")` 返回 `NodeInfo`（`ipv6` 节点名、`testedAt`、`networkChangedAt`，后两个见 #24）。没有新增 JNI 函数。
- **取消"已处理"的影响：** 被分流的节点不进"已处理"记录；网络恢复 IPv6 后如果仍然超时，会重新出现在"测速超时"里。

**新增文件**
- `core/src/main/golang/native/ipdelay/nodeinfo.go`（`IPv6Nodes()`、`NodeInfo()`、`MarkTested()`、`MarkNetworkChanged()`、`DNSTree()`、`RawHosts()`）、`nodeinfo_test.go`。
- `core/.../model/NodeInfo.kt`；`common/.../custom/NetworkIpv6.kt`。
- `ErrorsActivity.kt`（刷新时分流）、`ErrorsReport.kt`（加 `ipv6Skipped`）、`ErrorsText.kt`（折叠行）；文案 `errors_fold_ipv6_nodes`。

## 24. 网络切换后自动重测一次，并标明结果新旧（r25）
**效果：** 在 Wi-Fi 和移动数据之间切换、换了一个 Wi-Fi、或 IPv6 出现 / 消失之后，旧网络上的测速结果对新网络没有意义，却会一直留在页面上。现在网络稳定后自动做一次**只测延迟**的测速（内核自带的那种，不做 #1 的逐 IP 测试，也不发国家探测请求）；错误页的"节点测速超时"末尾写"（N 分钟前测速）"，如果网络在这次测速之后又换过，再加一行"这次测速早于最近一次网络切换，结果可能已经不准"。
- **事件驱动：** `NetworkRetest`（服务进程）注册系统的网络回调（`NOT_VPN` + `INTERNET`），收到事件后先等一个**一次性**的 4 秒（`SETTLE_MS`，每来一个新事件重新计时，网络还在起来时不测半截），到点只看一次："VPN 之下那张网络"的特征（`NetworkIpv6.signature()`：哪张网络、什么类型、有没有 IPv6）有没有变，没变就什么都不做（链路属性反复上报很常见）。**特征里不含 IP 地址**：IPv6 隐私地址会不停轮换，那不值得重测。没有定时器，没有轮询。
- **息屏：** 屏幕关着时不测（只会让射频白忙）。变化仍然记下，同时临时注册一个 `ACTION_SCREEN_ON` 接收器，屏幕亮起时测一次并立即注销。没有这件事要等的时候，没有注册任何东西。
- **启动：** `NodeIpNotifier.start()` 里多一行 `NetworkRetest.start(app)`（它本来就是"每进程只启动一次"的入口，`DynamicNotificationModule` 和 `ClashManager.queryTunnelState` 已经会调它），所以**没有新增上游文件改动**。
- **核心里怎么做：** `queryCustomConnections(SORT_RETEST = 6, "changed")` 只记下"网络变了"的时间；`"run"` 对所有代理组并发做一次 `tunnel.HealthCheck`，立刻返回，全部结束后记下 `testedAt`（`retest.go`；没载入配置时 `recover` 掉，什么都不测）。手动测速（#1 的 `TestGroup`）开头也会记 `testedAt`，所以"几分钟前"对手动测速同样成立。
- **局限：** ① `testedAt` 只记**我们触发的**测速（手动或网络切换后）；url-test 组自己的定时健康检查不更新它，所以显示的时间可能比实际最近一次测速更早，不会更晚。② 没有为"测完了"发通知，页面要点刷新（或下次打开）才看到新结果。③ 代理没运行时没有回调要处理。
- **功耗：** 只有系统网络事件才会走到这里；一次网络切换最多一次测速（息屏时推迟到亮屏）。

**新增文件**
- `service/.../custom/NetworkRetest.kt`；`core/src/main/golang/native/retest.go`；`ipdelay/nodeinfo.go`（同 #23）。
- `design/.../custom/AgeText.kt`（"N 分钟前测速"，用 #12 的 `ip_delay_age_*` 文案）；文案 `errors_timeout_age`、`errors_timeout_stale`。
- `ipdelay.go`（#1 #7 的文件）：`TestGroup` 开头加 `MarkTested()`。`connections.go`（#8 的文件）：加 `SortNodeInfo` / `SortRetest` 两个分支；`CustomConnections.kt` 加 `SORT_NODEINFO`、`SORT_RETEST`。

## 25. 配置检查页的 DNS 检查（r25）
**效果：** "配置检查"页（#22）在输入框下面多一个"检查 DNS"按钮，用输入框里的域名（任何域名都行）。点一次查一次，只读配置的 `dns:` 和 `hosts:` 两段，不代入整个配置。结果分三块：
1. **应用拿到的是什么（按配置推断）：** `dns.use-hosts`（默认 true）为 true 时先看 `hosts:`：有条目盖住这个域名 → 直接用 hosts 的值，不问 DNS；条目的值是单个非 IP 的域名则是**别名**，改查那个域名（最多跟 8 跳，成环就停），下面的 fake-ip-filter 和服务器测试都针对别名；`use-hosts` 为 false 时 hosts 不参与，页面会写明。否则 `enhanced-mode` 是 fake-ip → 看 `fake-ip-filter`（和 `fake-ip-filter-mode`）：没被放行就是 fake-ip 地址池里的假地址，被放行是真实地址；不是 fake-ip 模式就是真实地址。另外按内核行为补一句 AAAA：fake-ip 但没有 `fake-ip-range6` → 应答为空；`dns.ipv6` 为 false → 应答为空；`respect-rules` 为 true 时提示内核自己的查询走规则、和下面的直连测试可能不同。`fake-ip-filter` 里有 `geosite:`、`rule-set:` 这类这里判断不了的条目，或 mode 是 `rule`，就明说"按配置判断不了"，不乱猜。
2. **内核 DNS 服务器的实际回答：** 配置写了 `dns.listen` 时，向 `127.0.0.1:端口` 真的问一次（UDP），看内核自己给应用的答案是 fake-ip 还是真实地址。没写 `listen` 就不显示这一块；写了但没回答会提示。
3. **每个 DNS 服务器：** `nameserver`、`fallback`、`default-nameserver`、`proxy-server-nameserver`、`direct-nameserver` 和各种 `nameserver-policy`（只取看起来像服务器的值）里的每个地址，**去重后各查一次 A 和 AAAA**（两个同时发，最多 6 个服务器并发，每次最多等 5 秒）：通不通、多少毫秒、返回的 IPv4 / IPv6 地址、返回地址是否落在 fake-ip 地址池（说明这个服务器指回了内核自己，形成环）、失败原因（超时、被拒绝、TLS 错误……或服务器返回码 `SERVFAIL` / `REFUSED`）。同一个地址写在几处就合并显示"[nameserver, fallback]"。
- **支持的服务器写法：** 纯 IP / 域名（`223.5.5.5`、`223.5.5.5:53`、`udp://`）、`tcp://`、`tls://`（DoT，默认 853，校验证书）、`https://` 和 `http://`（DoH，HTTP/2）。**DoH 的请求形态照内核**：GET，`?dns=` 加无填充 base64url，报文 ID 为 0，`Accept: application/dns-message`，不带 User-Agent（只回应这种形态的 Worker 对内核有效、对别的客户端无效，所以检查必须同样发）；ECH 检查取配置仍用原来的 POST，没改。`quic://`、`h3://`、`dhcp://`、`system`、`rcode://` 这里查不了，会写"这种类型的服务器没法检查"。`#` 后面的参数：`#组名` 表示内核经那个代理组访问它，这里是**直接查**，页面会写明结果可能不同；`h3=true` 这里仍用 HTTP/2，也会写明。
- **自建 DoH Worker 有用：** 能看到 HTTP 状态码，和"返回的不是 DNS 报文"（Worker 返回了 JSON / 网页 / 别的 Content-Type）时它返回了什么（Content-Type 和开头 60 个可见字符），用来确认它在内核里到底能不能用。
- **对照内核源码（AGENTS.md 第 8 条）：** 对照的是 mihomo 提交 `3f27ef39f8c96bbfbc8b0b9bb10f5c9ef60149d8`（与子模块一致）。域名匹配是 `component/trie` 域名树的移植（精确 > `*` 单层通配 > `+` / `.` 后缀；带结尾点的写法会被内核拒绝）；hosts 查找与别名照 `component/resolver/host.go`；DNS 中间件顺序（hosts、fake-ip、映射、解析器）和 fake-ip 过滤的 blacklist / whitelist / rule 三种模式照 `dns/` 目录；DoH 请求形态照 `dns/doh.go`；`dns:` 配置键名是 kebab-case。内核升级后这些要重新对照。
- **局限：** 每个服务器都是直接查的，内核实际会选哪一个（`nameserver` / `fallback` 的取舍、策略、多个里取最快）不模拟；DoH 服务器地址里的域名由内核的拨号器解析（和 ECH 检查一样，`hosts:` 生效）；`dns.listen` 只试 UDP。
- **通道：** 还是 `queryCustomConnections(SORT_CHECK, 请求)`，请求 JSON 多一个 `"kind":"dns"`（没有这个字段就是原来的 ECH 检查）；`conftest.RunAny` 按它分派。没有新增 JNI 函数。
- **功耗：** 没有定时器，没有后台任务，点一次查一次。

**新增文件**
- `core/src/main/golang/native/conftest/dnslogic.go`（纯逻辑：读 `dns:`、域名匹配、DNS 报文，只依赖标准库）、`dnslogic_test.go`、`dnscheck.go`（联网部分和 `RunDNS` / `RunAny`）；`doh.go` 把 `dohQuery` 拆成 `dohExchange`（POST，ECH 检查用，行为不变）+ `dohExchangeLikeCore`（内核式 GET，DNS 检查用）+ 解析，另有 `doh_test.go`；`conftest.go` 的 `Request` 加 `Kind`；`ipdelay/ipdelay.go` 的 `CaptureDNS` 多记一份 `dns:` 的 JSON 树（`DNSTree()`），另有 `RawHosts()`（在 `nodeinfo.go`）。
- `core/.../model/DnsCheckReport.kt`。
- `design/.../custom/DnsCheckText.kt`；`ConfigCheckDesign.kt` 的 `Request` 加 `Dns`，`setRunning(running, dns)`；`res/layout/design_config_check.xml` 加按钮；文案 `check_dns_*`。
- `app/.../custom/ConfigCheckActivity.kt`：`runDns()`。

## 26. DNS 检查的增强（r26，r32 重排显示）
**效果：** "配置检查"页的 DNS 检查（#25）多了下面几样，都在同一份报告里：
1. **污染判断（报告最上面的"结论"）：** 把所有服务器的答案放在一起读。DoH / DoT 的答案当作参照（链路上改不了）。明文（UDP / TCP）服务器如果：返回了不可能属于真实网站的地址（私有、回环、保留、CGNAT、测试网段、Teredo `2001::/32` 等）→ **很可能是假的**；返回 NXDOMAIN 而别的服务器能解析出地址 → 明文的算"很可能"，别的算"可疑"；地址和加密 DNS 的答案**没有任何共同网络**（IPv4 比前两段、IPv6 比前 32 位，只比两边都有的地址族）→ "可疑"；多个明文服务器都对不上、而且它们彼此的网络也互不相同 → 升级为"很可能"（CDN 按地区给不同地址时彼此通常还在同一网络）。结论分：`polluted` / `suspect` / `consistent` / `single`（只有一个服务器给了地址）/ `nxdomain_all` / `none`。被标记的服务器在自己那一段下面多一行 ⚠。**只是判断，不是证明**：文案里"可疑"明确写成怀疑。
2. **全部 NXDOMAIN 时的提示：** 结论写"域名多半不存在，不是 DNS 故障"；域名最后一段是 `workers` / `pages`（建议补 `.dev`）、`trycloudflare`（补 `.com`），或常见的顶级域拼错（`con` `cmo` `ocm` `vom` `comm` → `com`，`ogr` `rog` → `org`，`nte` `ent` → `net`）时给出"是不是想查 …"；域名里没有点时提示"像是只写了一部分"。只是猜测，不会自动改。
3. **内核解析器的实际答案：** 直接调内核自己的解析器（和控制接口 `/dns/query` 同一个调用：`resolver.DefaultResolver.ExchangeContext`，A 和 AAAA 各问一次，5 秒超时）。这是内核按 nameserver / fallback / nameserver-policy 选完之后真正拿到的答案（**不含 hosts 和 fake-ip**，那两样在解析器之前处理），并写出和哪些被检查的服务器**有共同地址**（找不到时说明可能来自内核的 DNS 缓存）。没载入配置时写"没有可用的"。
4. **`nameserver-policy` 命中哪一条：** 加载配置时 `ipdelay.CaptureDNS` 另存一份**带顺序**的 policy（`ipdelay/policy.go`，用 JSON token 流读，因为通用的 map 树会丢顺序）。匹配照 `config/config.go` 的 `parseNameServerPolicy`（键里的逗号拆成多个模式，`geosite:` / `rule-set:` 后面的逗号拆名字）和 `dns/resolver.go` 的 `matchPolicy` / `makePolicy`（按顺序第一个命中的策略生效；连续的普通域名模式合成一棵域名树，树内取最具体的）。`geosite:` / `rule-set:` 这里判断不了，排在命中之前的会列成"可能先命中"。没写 policy 时写"没有写"。
5. **清缓存按钮：** 输入框下面的"清缓存"，弹出两项：只清 DNS 缓存（`resolver.ClearCache`）；清 DNS 缓存和 fake-ip 映射（`resolver.FlushFakeIP`，和控制接口 `POST /cache/dns/flush`、`/cache/fakeip/flush` 同一个调用）。后者之后内核里"假地址 ↔ 域名"的对应没了，还拿着旧假地址的应用会对不上，所以**同时关闭所有连接**。通道：`closeCustomConnection` 的特殊 id `!flush-dns` / `!flush-fakeip`（`CustomConnections.FLUSH_DNS` / `FLUSH_FAKEIP`），没有新增 JNI 函数；`ConfigCheckActivity.flush()` 先弹选择框，选了才执行。
6. **测试过的域名记在下拉里：** 域名输入框改成 `AutoCompleteTextView`，点一下输入框弹出最近 8 个测过的域名（存在页面自己的 SharedPreferences `custom_config_check` 的 `history`，每次点测试时更新，最新的在前）。

7. **r32：报告重排，只改显示，查询和判断逻辑（Go 侧、`DnsCheckReport`）没动。** 原来是一长串：开头一份"哪几个服务器对不上"的列表，中间一堆说明，后面每个服务器的全部地址铺开，最后又标一遍"可疑"，同一件事说三遍，最重要的"内核实际用的答案和谁一致"埋在中间。现在是：
   - **第一行结论（粗体）：** 内核解析器有答案且和被检查的某些服务器一致 → "可用。内核实际用的答案和 N 个服务器一致"；有答案但谁都对不上 → "不确定……可能来自内核的 DNS 缓存"；内核没问 / 失败 → 按原来的 `verdict.kind` 给一句（一致 / 只有一个 / 很可能被污染 / 疑似 / 没有答案）；全部 NXDOMAIN 沿用原来的话和"是不是想查……"。接着一行内核的答案（A / AAAA / 毫秒）。
   - **哪几个有问题、影不影响：** 把被标记的服务器按组分开。内核真正在问的（`nameserver`、`fallback`、`nameserver-policy`）里有问题 → 红字"会直接影响解析"；只用于特定场景的（`direct-nameserver`、`default-nameserver`、`proxy-server-nameserver` 等）有问题 → 橙字"疑似被污染，它们不是主 DNS，只在直连域名或解析 DoH 服务器自己的域名时才用到"。这只说它们是干什么的，不替你判断这个域名受不受影响（域名走不走直连，这个报告不知道）。
   - **服务器按这两组列，每个一行**：标记（✓ 正常 / ⚠ 有问题 / ✗ 没应答 / · 不支持或域名不存在）、地址、结果一个词（和内核一致 / 有应答 / 没应答 / 不支持 / 域名不存在 / 很可能是假的 / 疑似被污染）和毫秒数。**点一行**才展开它的 A / AAAA、失败原因、h3 等细节（`DnsCheckText.serverDetail`）。
   - **"详情"折叠区默认收起：** 应用拿到的地址（hosts / fake-ip）、内核解析器完整答案、`nameserver-policy`、AAAA 说明、说明文字（`DnsCheckText.details`，内容和以前中间那段一样）。
   - 新文件 `design/.../custom/DnsCheckView.kt`；`DnsCheckText.build()` 改成 `DnsCheckView(...).plain()`（全部展开的文本，复制按钮和诊断包用它，`DiagnosticBundle` 不用改）；`ConfigCheckDesign.showDns()`（和推算最终配置页一样，点击交给文本里的链接）；`ConfigCheckActivity.runDns()` 改调 `showDns`；文案 `check_dns_c_*` `check_dns_g_*` `check_dns_r_*` `check_dns_fold_*` `check_dns_tap_hint`。点一下只重画，不再查询；没有新增定时器。
   - **局限：** 服务器按"角色名"分组，角色名就是 `dns:` 里的键名；`dns.listen` 那一项（内核自己的 DNS 服务器）不在列表里，仍在详情里；没有编译过，要靠 Actions 构建验证。

**对照内核源码（AGENTS.md 第 8 条）：** mihomo 提交 `3f27ef39f8c96bbfbc8b0b9bb10f5c9ef60149d8`（与子模块一致）。`hub/route/dns.go`（`queryDNS`）、`hub/route/cache.go`（两个 flush）、`component/resolver/resolver.go`（`DefaultResolver`、`ClearCache`、`DefaultDNSTimeout`）、`component/resolver/enhancer.go`（`FlushFakeIP`）、`dns/resolver.go`（`ExchangeContext`、`matchPolicy`、`makePolicy`）、`config/config.go`（`parseNameServerPolicy`、`NameServerPolicy` 是有序 map）。内核升级后这些要重新对照。
**局限：** ① 污染判断靠"网络不同"这类规律，没有 IP 归属数据库，CDN 在不同地区给出差别很大的地址时可能被判成"可疑"，所以只有"很可能"才有把握；② 内核解析器的答案可能来自缓存；③ policy 里的 `geosite:` / `rule-set:` 只能列出，不能判断；④ `proxy-server-nameserver-policy` 只用于解析节点域名，没有做匹配。
**功耗：** 点一次查一次 / 清一次，没有定时器、后台任务。
**新增文件：** `core/src/main/golang/native/conftest/dnsextra.go`（纯逻辑：结论、拼写提示、policy 匹配，只依赖标准库）、`dnsextra_test.go`、`dnscore.go`（调内核解析器、清缓存）；`core/src/main/golang/native/ipdelay/policy.go`、`policy_test.go`；`core/.../model/DnsCheckReport.kt` 加 `DnsCheckVerdict` / `DnsCheckFlag` / `DnsCheckPolicy` / `DnsCheckCore`；文案 `check_dns_v_*` `check_dns_core_*` `check_dns_policy_*` `check_dns_why_*` `check_dns_flag_*` `check_flush_*`。
**改了的自己的文件（#22 #25 的）：** `dnscheck.go`（`DNSReport` 加 `Verdict` / `Policy` / `Core`，`RunDNS` 里加三处调用）、`ipdelay.go`（`CaptureDNS` 里加 `setPolicy(parsePolicy(data))`）、`connections.go`、`CustomConnections.kt`、`DnsCheckText.kt`、`ConfigCheckDesign.kt`、`ConfigCheckActivity.kt`、`design_config_check.xml`（输入框改 `AutoCompleteTextView`，加"清缓存"按钮）。

## 27. 保存配置时说清是哪一行，以及"检查配置有没有错"（r26）
**效果：**
- **保存时的错误：** 你在"文件"里用外部编辑器改了 `config.yaml`，回到配置页点保存，上游本来就会让内核先检查（`ProfileProcessor.apply` 里的 `fetchAndValid`），不合格就不替换正在用的配置。以前只弹内核的一句话，现在如果这句话里有"line N"，就在后面加上那一行和上下各一行（`> 12 | 内容`）。没提行号的错误原样抛出。
- **"检查配置有没有错"：** （r68 起入口是编辑页顶部的"检查"，见 #56；原来设置里的"配置历史与检查"已删。）让内核读配置的 `config.yaml`（自己编辑的配置读**编辑框里的文字**）、套上 App 的补丁、解析一遍，但**不载入、不联网下载**，只告诉你能不能通过；不能通过就写原因和那一行。用来在点保存之前先查。
- 外部编辑器是直接写文件的，App 没办法在编辑器"保存"的那一刻拦住它，所以"保存前"指的是：点"保存"时先检查、失败就不替换；以及上面这个随时可用的预检。

**怎么做的：** Go 的 `config.Validate(dir)`（新文件 `native/config/custom_validate.go`）就是 `FetchAndValid` 的校验部分：`UnmarshalAndPatch` → `Parse` → `destroyProviders`，不下载、不 `hub.ApplyConfig`；错误里的 `line N` 用正则取出。通道：`queryCustomConnections(SORT_VALIDATE = 7, 文件夹路径)`，没有新增 JNI 函数、没有给 `IClashManager` 加方法。Kotlin 的 `ConfigErrorText`（service）负责取行号、读文件、拼出上下文，编辑页（`EditorActivity.check()`）负责界面（r68 前是 `ConfigTools.check()`）。
**功耗：** 点一次查一次。
**新增文件：** `core/src/main/golang/native/config/custom_validate.go`；`service/.../custom/ConfigErrorText.kt`；`app/.../custom/ConfigTools.kt`；文案 `history_check_*`、service 的 `custom_config_error`。
**改了上游文件：** `ProfileProcessor.kt`（见总表）。
**依赖的上游接口：** `config.UnmarshalAndPatch`、`config.Parse`、`config.destroyProviders`（`native/config` 包里的，和 `FetchAndValid` 用的同一组）。

## 28. 配置历史：保留最近 10 个版本，对比，回滚（r26；**r68 起搬进编辑页并改了逻辑，见 #56**，下面写的是 r26 的旧样子）
**效果：** "设置 → 应用 → 配置历史与检查 → 历史版本与回滚"。
- **什么时候记：** 点"保存"（`ProfileProcessor.apply`）成功检查之后、替换正在用的配置之前，把**被替换的版本**和**新版本**的 `config.yaml` 各存一份到 `<filesDir>/custom/config_history/<uuid>/<毫秒时间>.yaml`；和最新一份内容相同就不重复存；每个配置最多 10 个，多的删最旧的。只记 `config.yaml`，不记 provider 文件。2 MB 以上的 `config.yaml` 不记。
- **只有"自己编辑的配置"（类型 File）有历史：** 订阅（URL）每次保存都会重新下载覆盖，回滚没有意义，所以不列。
- **界面：** 选配置 → 版本列表（时间、行数、和前一版相比 `+增 −删`，正在使用的标出来）→ 选一个版本："和正在使用的版本对比"（只显示改动行和上下各 2 行，最多 300 行，是"恢复后会变成什么样"：`-` 是会被删掉的、`+` 是会增加的）/ "恢复这个版本"。
- **恢复：** 确认后：`patch`（保证有一份待保存的副本）→ 把这个版本写进待保存的 `config.yaml` → `commit`。走上游的保存流程，所以内核照样先检查；通过才替换并通知重新载入，不通过就丢掉待保存的副本、正在用的配置不变，并显示上面 #27 那种带行号的错误。恢复本身也会被记成一个新版本（所以可以再退回来）。没保存的编辑会被丢弃（确认框里写明）。
- 差异算法：先去掉头尾相同的行，中间用最长公共子序列；中间太大（行数乘积超过 600 万）时显示成"全删再全加"。

**功耗：** 只在点保存、和你打开这个页面时才读写文件，没有后台工作。
**新增文件：** `service/.../custom/ConfigHistory.kt`；`app/.../custom/LineDiff.kt`、`ConfigTools.kt`（同 #27）；`BackupDesign.kt`（#17 的文件）加 `pick()` / `showText()`；`BackupActivity.kt`（#17）加 `ACTION_HISTORY`；`BackupPreference.kt`（#17）加一项入口；文案 `history_*`。
**改了上游文件：** `ProfileProcessor.kt`（见总表）。
**依赖的上游接口：** `IProfileManager` 的 `patch` / `commit` / `release` / `queryAll`；`importedDir` / `pendingDir` / `processingDir`；`ProfileProcessor.apply` 里"确认待保存的还是同一份之后、替换 importedDir 之前"这个位置。

## 29. 导出诊断包（r26）
**效果：** "设置 → 应用 → 导出诊断包"。先弹确认框（写明里面有什么），再选保存位置，生成一个 zip：
- `summary.txt`：应用版本、内核版本、Android 版本和机型、内核是否在运行、当前配置的名字和类型（不含订阅地址）；
- `config.yaml`：当前启用配置的 `config.yaml`，**密钥已隐藏**（见下）；`override.json`：覆盖设置，同样隐藏；
- `dns-check.txt`：对"配置检查"页上最后填的域名做一次 DNS 检查（#25 #26，只在内核运行、填过域名时有）；
- `errors.json`：错误与诊断页（#21）的原始数据；`mux.json`：底层连接（#20）的原始数据；（这两样只在内核运行时有）
- `ip_history.json`：节点 IP 切换历史（#10）；`crash/*.txt`：崩溃记录（#21）。
某一项读不出来就跳过，其余照常写。
- **隐藏的规则（`ConfigMask`）：** 下面这些键的值换成 `***`：`password`、`passwd`、`uuid`、`psk`、`private-key`、`pre-shared-key`、`public-key`、`auth-str`、`auth`、`obfs-password`、`token`、`secret`、`short-id`、`username`；`url:` 的值只留协议和域名（后面写 `/***`）。块写法和一行 `{...}` 写法都处理。**尽力而为**：节点服务器地址不隐藏，不在上面名单里的键下面的秘密也不会被隐藏，`errors.json` 里有最近连接失败的域名，所以确认框和 `summary.txt` 都提醒发给别人之前自己看一遍。
**功耗：** 点导出才读，没有后台。
**新增文件：** `app/.../custom/DiagnosticBundle.kt`、`ConfigMask.kt`；`BackupActivity.kt`（#17）加 `ACTION_DIAG`；`BackupPreference.kt`（#17）加入口；文案 `diag_*`。
**依赖：** `queryCustomConnections` 的 `SORT_ERRORS` / `SORT_MUX` / `SORT_CHECK`；`Bridge.nativeCoreVersion()`；`clashDir`。

## 30. 推算最终配置：你的配置上直接标出内核和 App 改了什么（r28，r29 修正归属和锚点，r31 改排序和颜色）
**效果：** "配置检查"页（#22）第二排按钮多一个"推算最终配置"。点一次，对**正在使用的配置**（已保存的那份 `config.yaml`，不是没保存的编辑）做一次对比，结果显示在页面的文字区里：以**你写的配置为底**，差异直接标在原位，不另开一段：
- 没变的行：普通颜色。
- 被改写的值（**蓝色**，行首 `~`；r31 起，原来是橙色，手机上和红色容易看混）：旧值划线灰色，紧跟着是实际生效的值（蓝色加粗）。例：`~ external-controller: ~~127.0.0.1:9090~~ ""`。
- 被删除的字段（红色加删除线，行首 `-`）：留在原位。包括内核不认识的字段（行末写"内核不认识，已忽略"）、被 App 去掉的监听（tproxy / redir / tun）。
- 被补上的字段（绿色，行首 `+`）：按层级放在它该在的位置（同一层的末尾；整段补上的顶层字段在文件末尾）。例：`dns.nameserver` 末尾补的 `system://`、没开 DNS 时整段换成的默认值。
- **内核补的整块里，后面的步骤改了的值单独标**（r29）：例如你没写 `tun`，内核补上整段默认值，App 又把其中的 `auto-route`、`auto-detect-interface` 关掉。显示为 `+ tun:（内核默认值）`，里面只有被改的那两项显示成 `~ auto-route: ~~true~~ false（App 补丁）`，其余默认值照常隐藏。同样，覆写设置改了内核默认的 `log-level`，显示成 `~ log-level: ~~info~~ debug（覆写设置）`。
- **锚点定义不算差异**（r29）：配置里 `proxy-defaults: &xxx { ... }` 这种只为被 `<<: *xxx` 引用而写的键，内核不认识会丢掉，但它的内容已经合并进引用它的地方。只显示一行灰字 `proxy-defaults:（锚点定义，内核不认识，只靠引用生效，不算差异）`，不显示它的内容，也不计入"删除"。判断依据：用户写的这个值带 `&` 锚点，且最终配置里没有这个键。
- 每个差异块末尾有一个小灰字标签说明是谁做的：`（覆写设置）`、`（App 补丁）`、`（内核默认值）`、`（内核改写）`、`（内核不认识，已忽略）`。一个块只在第一行写一次（改写的值每行都写）。
- 颜色之外有 `~` `-` `+` 和删除线，不靠颜色也能分清；整页等宽字体，缩进对得齐。
- **折叠：** 连续没变的行折成一行"… 共 N 行没变（点击展开）"，点一下展开。有差异的行会保留它所在的上层行（比如 `dns:`、`- name: xxx`）和上下各一行，所以能看出差异在哪。
- **内核补的默认字段默认隐藏**（里面被后面步骤改过的值和它们所在的上层行除外，见上）： 你没写的字段内核会补上默认值（`tun`、`sniffer`、`ntp` ……），这些一般有几十上百行，只说明"内核有默认值"，所以默认只显示一行"内核补上的默认字段共 N 个，已隐藏，点这里显示"，点了才穿插显示出来。内核补的空值（`null`、空列表、空表）不算。
- **排序（r31）：** 你写的配置原样排在前面（行序、缩进和你写的一样），改写（蓝）和删除（红）就在原位；App / 覆写设置在你写了的块里新补的键（例如 `dns.nameserver` 末尾的 `system://`）紧跟在它所属的块里。**内核自己整段补的顶层块**（`profile`、`ntp`、`tun` ……，判断：缩进 0、状态"补上"、来源"内核"）不再穿插，全部放到文件末尾单独一个折叠区"内核补充的默认块（N 个）"，默认收起，点开才看；点开后里面只显示被后面步骤改过的那几项（如 `~ tun.enable`）和它们所在的上层行，其余默认值仍然隐藏，要看全部就点顶部"显示"那一行（点它会同时展开这个折叠区）。你写了的块里内核补的默认子字段仍按原样隐藏，点顶部"显示"时显示在它所属的块里。复制按钮复制的文本同样是这个顺序，折叠区展开时才带上它（前面加一行 `# 内核补充的默认块`）。只改了 `EffectiveText.kt` 的显示顺序和颜色，对比计算（Go 侧、`EffectiveConfig`）没动。
- 顶部一行汇总（改写 / 删除 / 补上各几处）和图例。复制按钮复制的是完整文本（含当前"是否显示内核默认值"的选择，不折叠）。

**怎么做的：** 内核侧 `config.Effective(profileDir)`（新文件 `native/config/custom_effective.go`）：读 `config.yaml` → 用内核的 `UnmarshalRawConfig`（内核在这一步补默认值、丢掉不认识的字段）→ 按 `process.go` 里的 `processors` 顺序一步一步执行（覆写设置 `patchOverride` 和各个 App 补丁），每一步之后把整个 `RawConfig` 用 `yaml.Marshal` 写成 yaml 再读回成有序树。用户的原文也读成有序树（`custom_effective_yaml.go`：保留键的书写顺序，展开锚点、别名和 `<<` 合并）。然后 `effcfg.Diff`（新包 `native/effcfg`，纯标准库，有测试）逐层对比：
- 键按用户写的顺序走；两边都有就比下去；只有用户有 → 删除；只有最终有 → 补上（空值不算）。
- 列表按"条目内容"做最长公共子序列：不在两边的条目显示为删除 / 补上（所以被改了内容的条目显示为"删旧条目、补新条目"）。
- 值的写法不同但意思一样不算差异（`true` / `True`、`1` / `1.0`、`"7890"` / `7890`、空列表 / 空表 / null）。
- "谁做的"：**整块新增**的，取"这个键第一次出现（且非空）"的那一步（每一步之后都有一份完整快照）作为整块的来源；块里的值如果后来被别的步骤改了（跟那一步之后的值比较），那个值单独标成"改写"，来源取**最后一次改它**的那一步；块里后来才加的键，来源取加它的那一步。**被改写 / 被删除**的，取这个位置的值最后一次变化发生在哪一步。内核那步（`UnmarshalRawConfig`）算"内核"，`patchOverride` 算"覆写设置"，其余算"App 补丁"。判断靠 `processors` 里函数的身份（`reflect`），不靠顺序。（r28 的错误：整块新增的只要有一个值被后面的步骤改过，整块都标成那一步，所以 `tun` 的 50 多行全标成"App 补丁"。）
- 顶部汇总的数字改成对最终的行数一遍统计出来（改写 = 改写行数；删除 / 补上 = 块的根数），隐藏的"内核默认字段个数"和实际隐藏的块对得上。
- 通道：`queryCustomConnections(SORT_EFFECTIVE = 8, 配置文件夹)`，返回 JSON（每行 `i` 缩进、`p` 前缀、`v` 值、`o` 旧值、`s` 状态、`r` 来源）。没有新增 JNI 函数。Kotlin 的 `EffectiveText`（design）把它排成带颜色的文字并处理折叠。

**局限：**
- 只比对"载入时内核和 App 做的那几步"（`UnmarshalRawConfig` + `processors`）。内核之后 `ParseRawConfig` 里再做的事（例如某些字段的二次处理）不在内，所以那里补的东西这里看不到。
- 用户的原文按块写法重新排版：注释不保留，`{ ... }` 一行写法会展开成多行。
- 缩进、引号是重新生成的，不一定和你原文一样；字符串只在必要时加引号。
- 没载入的配置（没运行时）看不了，页面写"代理未运行"。看的是正在使用的那份已保存配置。
- **整个内核和 App 没有本地编译过**（这里没有网络，拉不下 Go 依赖）。`effcfg` 单独编译并通过了测试；读 yaml 的部分和 `Effective` 用同名的替身包做过类型检查和小测试；Kotlin 只做了人工检查。第一次 Actions 构建出错时先看这几个文件。
- 对照内核源码（AGENTS.md 第 8 条）：mihomo 提交 `3f27ef39f8c96bbfbc8b0b9bb10f5c9ef60149d8`：`config/config.go` 的 `RawConfig` 字段和 `UnmarshalRawConfig`（`DefaultRawConfig()` 先填默认值，再 `yaml.Unmarshal`）、`common/yaml/yaml.go`（`Marshal` / `Unmarshal`，实际用的是 `go.yaml.in/yaml/v3`）。`go.mod` 里已经有 `go.yaml.in/yaml/v3 v3.0.5 // indirect`，直接 import 它不用改 `go.mod`；如果 `go mod tidy` 把 `// indirect` 去掉也没关系。App 那几步对照的是 `native/config/process.go`（没有改动）。
**功耗：** 点一次对比一次（读一次文件、做十来次内存里的序列化），没有定时器、后台任务；页面里的折叠和"显示内核默认值"只是在内存里重画。

**新增文件**
- `core/src/main/golang/native/effcfg/effcfg.go`、`effcfg_test.go`（纯逻辑和测试）。
- `core/src/main/golang/native/config/custom_effective.go`、`custom_effective_yaml.go`。
- `core/.../core/model/EffectiveConfig.kt`；`design/.../custom/EffectiveText.kt`。
- 文案 `eff_*`（`values/` 和 `values-zh/` 的 `strings_custom.xml`；r31 加 `eff_tail_*` 四条）。

**改了自己的文件（#8 #22 的）：** `connections.go`（加 `SortEffective` 分支）、`CustomConnections.kt`（加 `SORT_EFFECTIVE`）、`ConfigCheckDesign.kt`（`Request` 加 `Effective`、`showEffective()`、`showText` 里恢复可选择）、`design_config_check.xml`（加按钮）、`ConfigCheckActivity.kt`（`runEffective()`）。**没有改任何上游文件。**
**依赖的上游接口：** `config.UnmarshalRawConfig`、`config.RawConfig`、`native/config/process.go` 里的 `processors` / `patchOverride` / `processor`、`yaml.Marshal`。

## 31. 日志级别搬到日志页（r30）

**效果：** 日志页右上角、三横线（清空日志）左边多一个调节图标，点开是"消息 / 警告 / 错误 / 调试 / 停用 / 不修改"的文字单选（和原来覆写设置里的是同一组文字）。覆写设置页里不再有"日志级别"。
- 存的还是覆写设置（持久槽，`override.json`），所以覆写页以前存的值保留、仍然生效。
- 选了具体级别且内核在运行：马上交给内核（`SORT_LOG_LEVEL = 9`，Go 里 `log.SetLevel`），提示"立刻生效"；选"不修改"或内核没运行：只保存，提示"下次载入配置时生效"（"不修改"没有级别可给，要重新载入配置才回到配置文件里的级别）。
- 功耗：只在点按时弹一次对话框、写一个小文件；没有定时器、没有轮询。

**新增文件**
- `app/src/main/java/com/github/kr328/clash/custom/LogLevelControl.kt`
- `core/src/main/golang/native/config/custom_loglevel.go`
- `design/src/main/res/drawable/ic_custom_tune.xml`
- 字符串 `log_level_live / log_level_next / log_level_failed`（两个语言的 `strings_custom.xml`）

**r34：按钮显示当前级别的文字，不再用图标。** 日志页工具栏里原来是一个小图标（`ic_custom_tune`），现在是一个 `TextView`，直接显示设置的级别：停用 / 调试 / 信息 / 警告 / 错误，没有设置（"不修改"）时显示"不修改"，读不到时显示"日志级别"。点它还是弹出选择框，选完立刻换成新的字。打开页面时读一次覆盖设置文件（`LogLevelControl.currentIndex`），之后只在选择后更新，没有新增定时器。最多宽 120dp，超出省略号。`ic_custom_tune.xml` 不再被用，留着没删。

**改的自有文件：** `native/connections.go`（`SortLogLevel` 分支）、`CustomConnections.kt`（`SORT_LOG_LEVEL`）。

## 32. 首页右上角菜单：备份到坚果云 / 从坚果云恢复（多设备共用）（r35，r36 改）
**效果：** 首页顶部（logo 和 App 名字那一行）最右边加一个"⋮"，点开是：
- **备份到坚果云：** 导出和 #17 一样的内容（订阅列表和各订阅文件、当前启用的订阅、服务设置、界面设置、geo 下载地址、覆盖设置），在内存里打成 zip，用"备份密码"加密后上传。**设备专属的内容不进云端备份：** 服务设置里的"访问控制"模式和应用名单（每台设备装的应用不同）。本来就不在备份里的还有节点选择记录、geo 文件、缓存、"隐藏应用图标"。
- **从坚果云恢复（r36 改）：** 先弹出列表：云端所有备份，新的在前，每项"时间 · 设备名"，本机的标"（本机）"，选哪份恢复哪份（可回退）。选完下载、解密，显示备份时间、设备名和订阅数，确认框里有一个**"同时替换本机设置"勾选项，默认不勾**。确认后：① 先把本机现状完整存一份（`filesDir/custom_backups/pre-restore-<毫秒>.zip`，保留最近 3 份，存不成功就不往下做）；② 备份里的订阅按 #17 的流程加入本机（名称 + 类型 + 地址相同的保留本机的，文件类订阅没有地址可重新下载，用备份里的内容替换；订阅类会重新联网下载，失败的留在订阅页）；**不删除任何本机订阅**，只有本机没有启用的订阅时才把备份里启用的那个设为启用；③ 只有勾了那个选项，才替换应用设置、服务设置（IPv6、系统代理等）、覆盖设置和 geo 下载地址，不勾则一个设置都不动（结果里写"本机的设置没有改动"）。（r35 是整体覆盖：设置替换、备份里没有的本机订阅会被删除。别的手机开了 IPv6 而本机不想开，所以改成了这样。）
- **删除云端备份（r40，见 #36）：** 列出云端所有备份，选一份，确认后从坚果云删掉（任何设备的都能删）。
- **坚果云设置：** 三项：坚果云账号（邮箱）、第三方应用密码（坚果云网页端 账户信息 → 安全选项 → 第三方应用管理 → 添加应用 生成，不是登录密码）、备份密码（加密用，忘记就打不开备份，每台共用的设备要填同一个）。保存后立刻连一次坚果云，告诉用户账号和应用密码对不对。第一次点备份 / 恢复时没设置会先弹这个。三项存在 `custom_cloud_backup` 私有偏好文件里（只在本机，不在任何备份里；备份密码和应用密码是明文存的，和订阅地址一样靠 App 私有目录保护）。输入框是明文显示（不是圆点），免得备份密码打错到另一台设备才发现。
- **本地导出备份 / 本地导入备份：** 从"设置 → 应用"搬到这里（#17 的功能没改）。
- **撤销上次云端恢复：** 回到上面第 ① 步存的那份（同样是整体覆盖，同样先列出会删除的订阅并确认），用完删掉那份。

**坚果云上的结构**（一个账号下所有设备共用一个文件夹）：`CMFA-backup/history/<UTC 时间 yyyyMMdd-HHmmss>_<设备编号>_<设备名>.enc`，每次推送一份。**每台设备各留自己最新的 5 份**（推送成功后顺手删本机多出来的旧份，删失败不算推送失败），所以一台手机备份得勤也不会挤掉别的手机的。没有 `latest.enc` 了（r35 写过，r36 起每次推送顺手把旧的删掉）。设备名读系统设置里的"设备名称"（`Settings.Global` 的 `device_name`），读不到用机型；文件名里不能有的字符换成 `-`，最长 40 个字，放进网址时百分号编码（`CloudBackup.path`），列表时解码。设备编号 = 本机随机 4 位（存在 `custom_cloud_backup` 里），用来区分同名的两台手机、也是"各留 5 份"的依据。r35 的旧文件（`<时间>_<机型-编号>.enc`）仍然能列出和恢复，设备名显示为那串机型-编号。

**加密：** `BackupCrypto`。文件格式 `CMFAENC1`（8 字节）｜迭代次数（4）｜盐（16）｜IV（12）｜AES-256-GCM 密文和标签；密钥 = PBKDF2-HMAC-SHA256（迭代 60000，自己写了一遍，因为系统自带的 `PBKDF2WithHmacSHA256` 要 Android 8 才有，本 App 支持 Android 5）；头部（到盐为止）作为附加认证数据。密码不对或文件被改过，GCM 校验失败，提示"备份密码不对，或者文件已损坏"。坚果云上只有密文。

**传输：** `WebDavClient`，对 `https://dav.jianguoyun.com/dav/` 发 MKCOL / PUT / GET / PROPFIND / DELETE，Basic 认证。不用 `HttpURLConnection`（它不认 MKCOL 和 PROPFIND），也不加 OkHttp 依赖，所以是自己在 `SSLSocket` 上写的最小 HTTP/1.1（`Connection: close`，支持 Content-Length、chunked、读到连接关闭；证书主机名用 `HttpsURLConnection.getDefaultHostnameVerifier()` 检查）。文件都很小，全放内存。

**功耗：** 全部只在用户点菜单项时运行（连一次网、做一次加密），没有定时器、轮询、后台任务、广播接收器。

**新增文件：** `app/.../custom/WebDavClient.kt`（WebDAV 请求）、`BackupCrypto.kt`（加密）、`CloudBackup.kt`（设置、坚果云上的读写、历史、恢复前的本机存档）、`CloudBackupFlow.kt`（设置对话框、选历史、确认、结果、撤销）；`design/.../custom/MainCustomMenu.kt`（右上角菜单，用类名启动 `BackupActivity`，因为 design 模块看不到 app 模块）；文案 `cloud_*`、`custom_menu_title`（`strings_custom.xml` 中英文）。
**改了自己的文件：** `BackupManager.kt`（`export` 拆出 `write`；新增 `exportBytes` / `exportFile` / `open(bytes)` / `open(file)`；`restore` 加 `replace` / `applySettings` / `replaceFiles` 三个参数（本地导入不传，行为和 #17 一样；云端恢复传 `replace=false, applySettings=勾选项, replaceFiles=true`；撤销传 `replace=true`）；新增 `planDeletes`（只有撤销用）；`RestoreResult` 加 `deleted`；`serviceToJson` 加 `sync` 参数）、`BackupActivity.kt`（四个新 action 和网络错误提示）、`BackupDesign.kt`（新增 `form(...)` 输入对话框和 `confirmWithOption(...)` 带勾选项的确认框）、`BackupPreference.kt`（去掉导出 / 导入两项）。
**改了上游文件：** `design_main.xml`（右上角 `custom_more`）、`MainDesign.kt`（一行 `customMenu` 加一个 import）。`AndroidManifest.xml` 没有新增 Activity（沿用 `BackupActivity`）。
**依赖的上游接口：** 同 #17（`IProfileManager` 的 `create` / `patch` / `commit` / `queryAll` / `queryActive` / `queryByUUID` / `setActive`，另外 `delete`；`pendingDir` / `importedDir`；`ServiceStore`）。

## 33. 首页"网络状况"卡（r39，**r42 取消**）
r39 加过这张卡，r42 整张去掉：它的第一行（IP、节点名）和首页代理卡片 / 通知重复，第二行（最近切换）是连接页 IP 切换历史（#10）的摘要，只有"节点延迟"是新的，而延迟在代理页测速里本来就有。**现在的状态：** 首页没有这张卡；`MainDesign.setCustomCards(running, state)` 和 `refreshCustom(running)` 回到两个参数；`design_main.xml` 没有 `customNet` 和那张卡；`custom_net_*` 文案删掉。`MainCustomNet.kt`、`MainCustomNetLoader.kt` 两个文件已没有人用，r42 包里把它们换成了只有一行 `package` 的空文件，**可以直接删除**（不删也不影响构建）。

## 34. 推送 custom 分支时的快速检查（r39）
**效果：** `.github/workflows/check-custom.yaml`（新文件）：往 `custom` 分支推送（rebase 到上游 main 之后、或任何改动）时自动跑，也可以手动启动；**不做 Gradle 构建**，大约一分钟，构建仍然只能手动（#2）。检查两件事：① `bash scripts/check-custom.sh`（#19，有 `origin/main` 时用 `UPSTREAM_REF=origin/main`，没有就用宽松检查）：挂钩丢了、新挂钩没登记会失败；② 只检出内核子模块（`--depth 1`，带重试），按顺序对 `patches/mihomo/*.patch` 先 `--dry-run` 再真打：内核升级后补丁对不上会失败，不用等到构建失败才发现。
**注意：** 上游文件 `.github/workflows/build-*.yaml` 没动。`check-custom.yaml` 是新文件，不会和上游冲突。

## 35. 省电：通知栏、首页后台、noteUse（r40）
- **通知栏定时器 1 秒改 5 秒，文字没变就不发（`DynamicNotificationModule.kt`）：** 副标题里的累计流量只是每 5 秒（原来 1 秒）读一次；每次先拼出正文和副标题，和上次发出去的一样就不调用 `notify()`（没有流量、没有换 IP 时基本不发）。**IP 变化不等定时器：** `nodeIpChanged` 那一支（#7）照旧立刻更新。亮屏时和换配置时（标题变了）清掉"上次发出的"，保证下一次一定发。息屏时本来就不更新（上游的 `shouldUpdate`），没动。
- **首页后台不再每秒查流量（`MainActivity.kt`，上游代码）：** 流量定时器那一支的条件由 `clashRunning` 改为 `clashRunning && activityStarted`，和连接页、底层连接页等一样：页面不在前台时，生产者停在"等人接收"，不唤醒、不做 IPC。回到前台时 `ActivityStart` 事件会让循环重新判断，定时器立刻给出一次。
- **`noteUse` 快速通道（`ipdelay/inuse.go`）：** 内核每个新请求都会调用它。现在先用读锁看一眼：这个节点最近一次记录的 IP 就是这个 IP，并且是 0.5 秒（`repeatGap`，小于 `useWindow` 的 1.5 秒）之内记的，就直接返回，不拿写锁、不分配切片、不排序。集合和顺序在这种情况下不可能变，所以显示结果一样；只是最近一次的时间戳最多晚 0.5 秒更新。IP 不同或隔了 0.5 秒以上，走原来的完整流程。
- **检查过、没发现问题的：** 网络切换重测（#24）、IP 变化广播（#7）、云端备份（#32）、配置检查（#22）、错误页（#21）、连接页和底层连接页（前台才刷新）都是事件驱动，息屏时不干活，没有残留的定时器或轮询。`errlog` 对每条警告 / 错误日志跑几个正则，日志刷屏时有点耗，影响小，没改。**r43 又对照代码盘点了一遍：** 自家新增的东西里，测速的随机等待只在手动测速时有（#1 r41），首页状态检测（#37）没有定时器、最多每 3 分钟一个请求；其余定时器都是上游原有的并且已经在 #35 里限制过：通知栏 5 秒（亮屏才跑，因为副标题还要显示累计流量）、首页流量 1 秒和连接页 / 底层连接页 2 秒（页面在前台且代理运行才参与）、日志页 0.5 秒（页面在前台才参与）、应用列表缓存（只等安装卸载广播）。上游里首页以外的"提供者 / 文件 / 配置"三个页面各有一个 1 分钟的定时器，页面进后台后仍会走，每分钟一次、没有请求，影响很小，**没改**（改它们要动三个上游文件，合并时多三处冲突）。

## 36. 云端备份：删除入口，恢复确认框里列出订阅名（r40）
- **删除云端备份：** 首页右上角菜单多一项"删除云端备份"：列出云端所有备份（时间 · 设备名，本机的有标记），选一份，确认（"删除后永久消失，所有设备都不能再用它恢复"）后从坚果云删除。任何设备的备份都能删。`CloudBackup.delete`、`CloudBackupFlow.delete`；动作 `CLOUD_DELETE`（`BackupActivity` 和 `MainCustomMenu` 里各一份常量）。
- **恢复前看到有哪些订阅：** 确认框在原来的说明后面列出三类名字（每类最多 20 个，多的写"…共 N 个"，没有的类不显示）：将加入的、本机已有的（保留本机的）、文件类订阅里会被备份内容替换的。判断规则和恢复时一样（名称 + 类型 + 地址相同 = 同一个订阅）。`BackupManager.compare`。

## 本版没做的（决定不做）
网络切换自动改模式、按应用统计流量排行、节点历史用量与延迟趋势、GeoSite 只在不计费网络下更新：不做。网络切换后自动重测（#24）本来就有。

## 37. 首页代理卡片显示"现在通不通"（r43）
**效果：** 首页"代理"卡片的副标题在 IP 后面多一段：`✓ 86 ms`（节点在用且能通）或 `✗ 不通`；还没测过时什么都不写。用来一眼分清"代理坏了"还是"浏览器自己的缓存坏了"（关了代理再打开打不开外网时，先看这里）。
- **测什么：** 经**正在用的节点**（`CurrentSet` 的那个节点）请求一次测速地址（和测速页同一个 `defaultTestURL`、同样 5 秒超时），不走代理组、不测别的节点。没有任何请求走过节点（还不知道在用哪个）时不测，也就不显示。
- **什么时候测（没有定时器）：** 有人问隧道状态（打开首页、换 IP 事件、通知文字重建）时，如果上一次的结果是"通"且超过 3 分钟（`reachFresh`），或上一次是"不通"且超过 20 秒（`reachRetry`），或还没测过，就在后台发一次；另外**网络切换稳定之后**（#24 的 `MarkNetworkChanged`）对刚才那个节点再测一次。同一时间只发一个。载入新配置时（`CaptureConfig`）清空。
- **怎么告诉首页：** 只有"从没结果变成有结果"或"通 ↔ 不通"切换时，才用 #7 的同一个事件通知（`notifyNow`，不记 IP 切换历史），首页和通知栏各读一次；延迟毫秒数的小变化不通知，下次打开首页才刷新。
- **功耗：** 最多每 3 分钟一个请求，且只在首页或通知本来就要读状态时；息屏时没有人问，就不测。没有轮询。
- **新增文件：** `core/src/main/golang/native/ipdelay/reach.go`；文案 `custom_reach_ok` / `custom_reach_fail`。
- **改了上游文件：** `tunnel.go`（`queryTunnelState` 多一个 `reach` 字段）、`TunnelState.kt`（`reach`），见总表；自家文件 `ipdelay.go`（`CaptureConfig` 末尾一行 `resetReach()`）、`nodeinfo.go`（`MarkNetworkChanged` 末尾一行 `refreshReach()`）、`MainCustomCards.kt`（拼接 reach 文字）。
- **对"关了代理再打开，浏览器打不开外网"：** 内核这边本来就没有残留：每次启动和停止都会走 `Clash.reset()` → `reset()` → `tunnel.CloseAllConnections()`，`profile.store-fake-ip` 你的配置里也已经开了。所以这一条改不了"代理工具"这一侧，只能靠本条的状态判断问题在哪边。

## 38. 配置历史里标出"这个版本用过没出错"（r43）
**效果：** 编辑页的历史版本列表（r68 前在设置里，见 #56）里，用过且当时代理是通的那个版本后面多 `· ✓ 用过没出错`，回滚时可以优先选它。
- **怎么标：** 首页拿到 #37 的"通"（`reach > 0`）时（`MainCustomRefresh.refreshCustom`），把**正在用的配置**和它的历史版本逐字节比较，相同的那一份旁边建一个空文件 `<毫秒>.ok`（`ConfigHistory.markVerified`）。只在 `config.yaml` 变了之后才重新比较（`ConfigVerified` 记着上次的 uuid + 修改时间 + 大小）。订阅（没有历史）、没在历史里的版本不标。删历史版本时一起删它的 `.ok`。
- **限制：** 只在首页打开着、并且收到"通"的那一刻才会标（后台不做）；通知栏也会收到事件，但标记是首页做的。
- **功耗：** 不在后台跑；一次小文件比较，且同一份配置只做一次。
- **新增文件：** `app/.../custom/ConfigVerified.kt`；`ConfigHistory.kt` 加 `Version.verified` / `markVerified`；`ConfigTools.kt` 版本标签加一段；`MainCustomRefresh.kt` 末尾多一行调用；文案 `history_verified`。

## 39. 连接页写明"为什么直连 / 被拒绝"（r43）
**效果：** 连接列表每行第三行里，走直连或被拒绝的连接，规则前面加结果：`直连 ← GeoSite,cn`、`拒绝 ← 规则`；点一行的详情里多一行"结果"：`直连，没走代理：命中了规则 GeoSite,cn` / `被拒绝：…` / `走了代理（组 → 节点）：命中了规则 …`。
- **怎么判断：** 内核列出的链路里**真正承载的那一项**（`chains` 的第一个）是 `DIRECT` 就是直连，以 `REJECT` 开头就是拒绝；规则取自原来就有的 `rule` / `payload`，没有新的内核调用。
- **改的文件：** `design/.../custom/ConnectionAdapter.kt`（`ruleWithVerdict` / `verdict` / `leafKind`）；文案 `connection_row_*` / `connection_label_verdict` / `connection_verdict_*`。没有功耗影响。

## 40. 构建产物名带版本号和时间（r43）
**效果：** Actions 里下载的产物不再叫 `CMFA Debug Unsigned APK (arm64-v8a)`，而是 `CMFA-custom-2_11_36-r43-1113-1530`（`CUSTOM.md` 开头"当前版本："那一行 + 北京时间的月日-时分）。
- **每次交付改一行：** `CUSTOM.md` 开头的 `当前版本：` 要和压缩包名里的 `<上游版本>-r<数字>` 一致（规则写进了 `AGENTS.md`）。读不到这一行时产物名里写 `unknown`，构建不会失败。
- **改了上游文件：** `.github/workflows/build-debug.yaml`（上传前一个 `Artifact name` 步骤，上传步骤的 `name:`），见总表。

## 41. 上游差异报告（手动，r43）
**效果：** Actions 里手动运行 `Upstream Status`，在运行摘要里看到：上游有多少个我们还没有的提交、它们改了多少文件、其中**哪些文件在本表里**（合并时可能冲突）、内核子模块有没有动（补丁要不要重新对）、最新 15 条提交标题。只读，不合并、不推送。
- **上游地址：** 默认 `https://github.com/MetaCubeX/ClashMetaForAndroid.git` 的 `main`；要改，在仓库设置里加变量 `UPSTREAM_URL` / `UPSTREAM_BRANCH`。本地也能跑：`bash scripts/upstream-status.sh`（`UPSTREAM_REF` 可以指向本地已有的引用）。
- **新增文件：** `scripts/upstream-status.sh`、`.github/workflows/upstream-status.yaml`。没有定时触发，不占 Actions 额度。

## 42. 自动用最快的 IP（内核补丁 0003，r44）
**效果：** 你写在 `hosts:` 里的节点，手动点测速（`TestGroup`）测完后，在**测通的 `hosts:` IP 里取延迟最小的**设为这个节点的"首选 IP"。之后这个节点的**新连接只拨这一个 IP**，不再对每个 IP 都握手（`tcp-concurrent` 原来是所有 IP 同时拨、谁先通用谁）。
- **只对 `hosts:` 里你写的 IP 生效。** 实时解析的节点（DNS 每次返回的子集会变，首选 IP 可能不在下次的结果里，而且每次只测 2 个，"最快"没有意义）不设首选；以后想放开再加。
- **拨不通就退回：** 首选 IP 拨号失败（并且不是因为调用方取消了连接），这一次接着用完整的 IP 列表拨（和没有补丁时一样），同时把这个节点的首选清掉；再下一次测速重新选。
- **什么时候设、什么时候清：** 只有手动测速会设（#24 网络切换后的自动重测只测延迟、不走 `TestGroup`，所以不会改首选，换网络后首选可能不再是最快的，要再点一次测速；首选拨不通时会自己退回并清掉）。清掉的时机：载入新配置（`CaptureConfig`）；一个 `hosts:` 的 IP 都没测通；节点不再有名单（`testProxy` 里 `len(all)==0`）；拨号时首选 IP 不在这次解析结果里则补丁不用它（没有副作用，也不清）。
- **效果和限制：** 只对**新建**的连接生效，已经开着的连接最长复用 1800 到 3000 秒才会换；首选的节点每条新连接只握手一次。只挂在 `DialContext`（TCP 拨号，以及走它的 `udp`）上，hysteria 之类走 `ListenPacket` 的 UDP 传输不受影响。节点名取自 0001 给拨号加的 `owner` 选项（`WithOwner(b.name)`），所以只有带这个选项的拨号才生效。
- **功耗：** 没有任何定时器 / 后台任务；每次拨号读一次带读锁的小 map，测速结束写一次。
- **新增文件：** `patches/mihomo/0003-preferred-ip.patch`（内核补丁，新文件 `component/dialer/preferip.go`：`SetPreferredIP` / `ClearPreferredIP` / `ClearPreferredIPs` 和包内的 `preferredIn` / `dropPreferred`；改 `component/dialer/dialer.go` 的 `dialContextRaw`，在 `parseAddr` 之后、`tcp-concurrent` 判断之前加一段，带 `CUSTOM` 注释）。**必须在 0001 之后应用**（`dialContextRaw` 是 0001 改的名字，`opt.owner` 也是 0001 加的）；`build-debug.yaml` 的 `Apply mihomo patches` 本来就按文件名顺序全打，没有改工作流。
- **改了上游文件（App 侧）：** 只有 #1 已经登记的 `ipdelay.go`（新文件，不是上游）：`testProxy` 末尾 `setFastest`、`CaptureConfig` 末尾 `dialer.ClearPreferredIPs()`。上游文件一行没动。
- **对照内核源码（AGENTS.md 第 8 条）：** mihomo 提交 `3f27ef39f8c96bbfbc8b0b9bb10f5c9ef60149d8`。`component/dialer/dialer.go` 的 `dialContextRaw`（原 `DialContext`）、`parseAddr`（`resolver.LookupIPWithResolver` 返回的是解析到的全部 IP，不截断）、`dialContext`（单个 IP 的拨号；`parallelDialContext` / `serialDialContext` / `dualStackDialContext` 在只有一个 IP 时最后都是调它）、`component/resolver/resolver.go` 的 `LookupIPWithResolver`（`hosts:` 命中时直接返回名单里的全部 IP，包括 IPv6）。
- **验证和局限：** 补丁在上面这份内核源码上按 0001、0002、0003 的顺序 `patch --dry-run` 再真打通过，打完的文件 `gofmt` 干净；`preferip.go` 单独拿出来跑过 `go vet` 和 `go test -race`（设置、清除、4in6、不在列表里、只清自己那个、非法参数）；`pickIPs` 单独跑过 8 个用例。**没有编译整个内核**（这里拉不到内核的依赖），Go / Kotlin 要靠 Actions 构建验证。如果构建因为 0003 出错，把 `0003-preferred-ip.patch` 单独删掉，同时把 `ipdelay.go` 里的 `dialer.SetPreferredIP` / `ClearPreferredIP` / `ClearPreferredIPs` 三处调用（`setFastest`、`testProxy` 的 `len(all)==0` 分支、`CaptureConfig` 末尾）去掉；#1 的测速收窄不受影响。

## 43. 测试其他 IP（配置检查页，r45；r46 加域名、调深小字颜色）
**效果：** 配置检查页在"推算最终配置 / 清缓存 / 检查 DNS"那一行下面多一个"测试其他 IP"按钮，测**不在配置里**的 IP（r46 起也可以填域名，见 B）。点开是一个对话框：多行输入框填 IP 或域名（每行一个，也可以用逗号、分号、空格分隔，全角的也行；`1.1.1.1:443`、`[2606::1]:443`、`example.com:443` 取其中的地址或域名，端口不用；重复的去掉；既不是 IP 也不是域名的词在结果里列出来），下面选一个节点，两个按钮："直连测 IP"（A）和"套入节点测"（B）。填的 IP 和选的节点记在页面自己的 SharedPreferences（`custom_config_check` 的 `ip_text`、`ip_node`）。结果出来后，**点一行复制这个 IP**，最后一行"复制全部能用的 IP"；右上角的复制按钮复制整页文字。

**结果页的小字（ECH 配置来源、TLS 和运营商那一行、提示、时间）r46 起用深一点的灰（浅色 `#616161`，夜间 `#BDBDBD`），原来固定的 `#9E9E9E` 在浅色背景上太淡；只改了这一页，DNS 检查页（`DnsCheckView`）的 `#9E9E9E` 还没动。**

**一次最多 20 个**（多的不测，结果里写"还有 N 个没测"）。**一个接一个测，两个之间随机隔 1 到 3 秒**（`ipGapMin` / `ipGapMax`）。结果按延迟从小到大排，没通的排最后（按填的顺序）。测的时候按钮变成"取消"，再点一次，界面立刻停，同时通知内核停（`closeCustomConnection("!cancel-iptest")` → `CancelIPTest`），已经测完的行仍然显示（"已取消"）。点测试按钮才测，没有定时器、没有后台任务。

**A：直连测 IP（不经过节点）**
- **只测 IP 地址（r46）：** 填了域名的条目不测，结果里写"域名只能用套入节点测，这里没测：…"；只填了域名就直接提示"直连测 IP 只测 IP 地址，域名要用套入节点测"。
- 用输入框里**已有的那个域名**当 SNI 和 Host（没填会提示）；直连 `IP:443`，拨号用 `dialer.DialContext`（和 ECH 测试一样，不走 VPN）。
- 每个 IP 显示：延迟（连接 + TLS 握手，毫秒）、TLS 版本、ALPN（握手时给 `h2` 和 `http/1.1`，看服务器选哪个）、ECH 是否被接受、是不是 Cloudflare（实测）、机房；运营商 r48 起点一下才查（见 #45）。已经在某个节点的 `hosts:` 里的标"已在配置里"。
- **ECH：** 这一轮开头向 DoH 取一次这个域名的 ECH 配置（先配置里 `dns:` 的 `https://` 服务器，再几个公共的，一共最多等 8 秒）；取到了就带 ECH 握手（TLS 1.3），"已接受 / 未接受 / 被拒绝"；被拒绝时再用普通握手试一次，这个 IP 通不通、延迟多少看第二次；取不到就不带 ECH，页面写"这个域名没有查到 ECH 配置"。证书不验证，只看握手过没过。
- **机房：** 握手后在同一条连接上发一个 `HEAD /`，读 Cloudflare 的 `Cf-Ray` 响应头末尾的机房代号（例如 `HKG`）；不是 Cloudflare、或者没回应就不显示。连的是这个 IP 本身，所以读到的是它自己的机房。
- **运营商：** 先查 `ipinfo.io/<ip>/org`，没有再查 `ipwho.is`（两个都是公共服务，有频率限制），查到的记在内存里，下次不再查；查不到就不显示，不影响这个 IP 算不算通。**r48 起不再自动查，改成点一下才查，见 #45。**

**B：套入节点测**
- **域名（r46）：** 条目是域名时，把所选节点复制一份，`server` 换成这个域名（节点名另取 `<节点名> (test)`），其余和 IP 一样。域名由内核拨号时自己解析，所以 `hosts:` 里写了这个域名就用你写的 IP；节点没写 `servername` / `sni` 时 SNI 就是这个域名。**换名是为了避开 #42 的首选 IP**：内核按节点名认首选 IP，临时节点同名的话，域名解析结果里只要有首选 IP，就只会拨那一个，测出来的就不是这个域名本身了。域名行不查"已在 hosts"，也不读、不记国家缓存（国家照样查），因为那是按 IP 记的。域名合法性：至少两段，每段只含字母、数字、连字符（不以连字符开头结尾），最后一段要含字母（所以 `1.2.3`、`300.1.1.1` 不算域名）；中文域名要写成 punycode。
- 选的节点原样复制一份，只把 `server` 换成这个 IP，用节点自己的协议请求测速地址（`ipdelay.TryIP`，和 #1 逐 IP 测速是同一个做法，延迟数字可以直接比），通了再取出口国家（这个节点 + 这个 IP 已经知道国家就直接用）。每个 IP 显示：国旗（查不到显示"？"）、延迟、"比对照快 N 毫秒"、"已在 hosts"（这个节点的 `hosts:` 里已经有）。
- **对照：** 页面顶上一行写这个节点 `hosts:` 里的地址在**上一次测速**里最快的那个和延迟（和"测了多久前"）。没测过就写"还没测过，到代理页点一次测速"，不会自己去测。
- **局限：** 和 #1 一样只换 `server`，节点没写 `servername` / `sni`（或 Host）时，这些字段默认取 `server`，换成 IP 后就是这个 IP（vless 看过源码，其他协议没逐个看）；这时测出来的"不通"可能是这个原因，不是 IP 的问题。测的过程中我们自己的套接字会计入 `selfDialing`，不会被当成真实流量记进"在用的 IP"。

**新增文件**
- `core/src/main/golang/native/conftest/iptest.go`（`RunIP`、`CancelIPTest`、A 的握手 / `Cf-Ray` / 运营商）、`iptest_logic.go`（`parseTargets` 读 IP 和域名、排序、机房代号，不依赖内核，可单独测）、`iptest_test.go`、`iptest_net_test.go`（本机 TLS 服务器：h2 和 http/1.1、`Cf-Ray`、连不上、取消、各模式的错误）。
- `core/src/main/golang/native/ipdelay/iptry.go`（`TryIP`，B 用）。
- `core/.../model/IpTestReport.kt`。
- `design/.../custom/IpTestView.kt`（结果页）、`IpTestDialog.kt`（对话框，代码里搭，没有布局文件）。
- `design/src/main/res/values/strings_iptest.xml`、`values-zh/strings_iptest.xml`（文案 `iptest_*`）。

**改了已登记的自定义文件（上游文件一行没动）：** `conftest/dnscheck.go`（`RunAny` 多一个 `kind == "ip"`）、`connections.go`（`closeCustomConnection` 多一个取消 id）、`CustomConnections.kt`（`CANCEL_IPTEST`）、`ConfigCheckDesign.kt`（`Request.IpTest`、`setIpRunning`、`askIpTest`、`showIpTest`）、`ConfigCheckActivity.kt`（`runIpTest`、`cancelRunning`；ECH 测试和 DNS 检查的"取消"也走 `cancelRunning`，会一并通知内核停）、`design_config_check.xml`（多一行一个按钮）。

**验证和局限：** Go 部分：`iptest.go`、`iptest_logic.go` 拿桩代码（假的 `errlog` / `ipdelay` / `dialer`）做过类型检查（`go vet` 通过），读 IP 和域名列表（含各种写法、非法词）、排序、机房代号、运营商拼接有单元测试，A 的握手用本机 TLS 服务器端到端跑过（h2 和 http/1.1 都读到了 `Cf-Ray` 的机房、ALPN、TLS 版本；连不上的 IP 给出原因；中途取消只留已测完的那一行、一秒内返回）。**没有真的编译**：`ipdelay/iptry.go`（用了 `ipdelay.go` 里的未导出函数，r46 加的域名分支也没真跑过）和整个 Kotlin 部分这里编译不了，也没在手机上跑过，要靠 Actions 构建验证。`ipinfo.io`、`ipwho.is` 的返回格式按它们的文档写的，没在真实网络里试过；A 的 ECH 部分依赖 DoH 能取到配置，取不到只是不显示 ECH。

## 44. App 里的版本号带上 r 数字，关于页长版本号换行（r47）
**效果：** App 自己的版本号由 `2.11.36.Alpha` 变成 `2.11.36.Alpha-r47`（Actions 实际跑的是 `app:assembleAlphaRelease`，所以是 `.Alpha`；构建 Meta 变体是 `2.11.36.Meta-r47`，debug 构建类型是 `2.11.36.Alpha.debug-r47`）。关于页、系统的应用信息、崩溃记录、诊断包读的都是 `versionName`，所以都看得到。关于页的版本号太长时在屏幕里换行，不再被挤出屏幕。
- **每次交付只改一行：** `r47` 取自本文件开头"当前版本："那一行（和 #40 的产物名是同一行），不用另外改 `build.gradle.kts`。读不到这一行、或最后一段不是 `r<数字>` 时不加后缀，构建不会失败。
- **`release_name` 不带后缀：** `build.gradle.kts` 里 `release_name`（`AndroidManifest.xml` 的元数据）是 `v2.11.36`，没动，它是上游在配置阶段算的，别处没有用到。
- **内核那边：** `versionName` 也会传给内核，出现在内核的版本字符串里（`...-CMFA-2.11.36.alpha-r47`，小写），只是显示，没有逻辑依赖。
- **改了上游文件：** `build.gradle.kts`、`design/src/main/res/layout/design_about.xml`，见总表。
- **局限：** 没真的编译，也没在手机上看过关于页；换行靠布局宽度 `0dp` + `layout_weight=1`，要靠 Actions 构建验证。

## 45. 测 IP：实测确认是不是 Cloudflare，运营商点一下才查（r48；**"点一下才查"r58 起取消，改回自动查，见 #52**）
**效果：**
- 直连测 IP 握手通了之后，已经会发一个 `HEAD /`（为了读 `Cf-Ray` 的机房）。现在顺带看这个回答：有 `Cf-Ray` 头，或 `Server: cloudflare`，就在这个 IP 的小字里显示"确认是 Cloudflare"。这是当场拿到的证据，没有时效问题，也不多发请求。**只有正面证据：** 服务器没回答，或两样都没有，不显示"不是 Cloudflare"，什么都不显示。
- 运营商（ASN）不再对每个测通的 IP 自动联网查。每个通的 IP 的小字末尾多一个蓝色的"查归属（联网）"，点了才查（`ipinfo.io`，没有再 `ipwho.is`），查到就显示在这一行，下次测同一个 IP 直接用内存里的。没查到弹"没查到归属"。
- Go 端新增 mode `"asn"`（`ips` 里只看第一个 IP，域名不查；没查到时 `error` 是 `"not found"`）。
- 改动：`iptest.go`（`fetchEvidence`、`lookupOwners`，`IPRow.CF`）、`iptest_logic.go`（`isCloudflare`）、`IpTestReport.kt`（`cf`）、`IpTestView.kt`（"查归属"链接）、`ConfigCheckDesign.kt`（`onLookup`、`setIpOwner`、点完后重新排版）、`ConfigCheckActivity.kt`（`lookupOwner`）、两份 `strings_iptest.xml`。都是自有文件，没有动上游文件。
- 耗电：没有任何自动运行的东西，查归属只在点的时候发两个请求。

**没做（要对照内核源码才能做，AGENTS.md 第 8 条）：** 读本机的 `ASN.mmdb` 离线给出归属并标注库的更新日期。App 构建时已经把这个文件放进了数据目录（`MainApplication.kt`），但读它要用内核里的 mmdb 读取代码，这次没有内核源码（子模块不在包里），不凭记忆写。需要 mihomo 提交 `3f27ef39f8c96bbfbc8b0b9bb10f5c9ef60149d8` 的源码。

**验证和局限：** 这次没有 Go 和 Kotlin 编译环境，Go、Kotlin 都没有编译、没有跑单元测试，只新增了测试（`TestIsCloudflare`、`TestRunIPAsnDomainOnly`，`TestProbeLocal` 里加了 `CF` 检查），靠 Actions 构建和测试验证。"查归属"链接和点完后重新排版没在手机上看过。

## 46. 内置文本编辑器：行号、YAML 高亮、撤销、查找替换、配置键补全（r49）
**效果：** 在"文件"页点一个文本文件（`yaml`、`yml`、`json`、`txt`、`conf`、`ini`、`js`、`list`、`toml`，**不超过 1 MB**），不再丢给别的 App，而是在 App 里的编辑器打开。其他类型和更大的文件仍然交给别的 App。编辑器右上角 ⋮ 有"用其他应用打开"。
- **编辑：** 行号；对屏幕上的行（和上下各 40 行）做 YAML 高亮（键、字符串、数字、`true/null` 之类、锚点、注释、`-` 和 `:`）；撤销和重做（连着打的字合成一步，退格也合成一步；也认外接键盘的 Ctrl+Z / Ctrl+Y）；回车保持缩进（`key:` 后多两格、`- 文字` 后自动补 `- `）；硬件键盘 Tab 缩进、Shift+Tab 减缩进。底部一排符号键（缩进、减缩进、`-`、`:`、`#`、引号、括号、`,` 等），点它们不会收起键盘。
- **查找替换：** 上一个、下一个、区分大小写、替换、全部替换（全部替换只改变化的那一段，是一步撤销）。
- **补全：** 只对 `config.yaml` 开。输入时按光标所在的层级提示还可以写的键（按 `type` 区分节点和策略组类型）、键的取值（`true/false`、`mode` 的几个值等）、规则的类型和各段（`DOMAIN-SUFFIX,`、`no-resolve` 等）、以及文件里自己定义的节点名、策略组名、provider 名。点一项就插入；键会带上换行和缩进。别的文件只提示文件里已有的词。
- **保存和检查：** 保存是通过和外部编辑器同一个文档提供者写回去的，所以保存的 `config.yaml` 是配置的"未保存副本"，和用外部编辑器改完一样，回配置页点保存时内核照常检查。顶部"检查"（只在一个配置顶层的 `config.yaml` 上有）会先保存、再让内核读一遍（和 #27 的"检查配置有没有错"是同一个调用），通过就提示，不通过就把出错那一行标红并跳过去，弹窗里写原因和那一行。有没保存的修改按返回，会问保存、放弃或继续编辑。
- **转屏不会丢：** 活动在 `AndroidManifest.xml` 里声明了 `configChanges`，转屏不重建。但 App 进程在后台被系统杀掉，没保存的修改会丢。

**来源（AGENTS.md 第 8 条）：** 补全的设计、配置结构表来自 FlClash（GPL-3.0，同协议）：`lib/features/editor/clash_completion.dart`、`completion_matcher.dart`、`yaml_lines.dart`、`clash_schema.dart`。FlClash 是 Flutter（Dart），编辑器本体是它自己画出来的控件（`plugins/code_forge`），**没法复制**，原生这边是用 `EditText` 重写的，只移植了补全逻辑和数据。配置结构表由 `scripts/clash_schema_to_json.py` 从 `clash_schema.dart` 转成 `design/src/main/res/raw/clash_schema.json`（60 个顶层键，34 个共用定义，约 56 KB）；FlClash 更新了这个表，重跑这个脚本即可：`python3 scripts/clash_schema_to_json.py <clash_schema.dart> design/src/main/res/raw/clash_schema.json`。

**新增文件（都在 `design/.../custom/editor/` 下，除非注明）：** `YamlLines.kt`（读 YAML 行的结构，光标在哪个键下面、这一层已经有哪些键）、`ClashSchema.kt`（读 JSON 配置结构表）、`ClashCompletion.kt`（补全、排序、从文件里读出节点名和策略组名）、`UndoStack.kt`（撤销栈）、`YamlHighlighter.kt`（一行的着色）、`CodeEditText.kt`（文本框：行号、着色、撤销、查找、补全弹窗）、`EditorDesign.kt`（页面，纯代码搭，没有布局文件）、`strings_editor.xml`（两份）、`res/raw/clash_schema.json`；`app/.../custom/EditorActivity.kt`。上游文件改动见总表：`FilesActivity.kt`、`AndroidManifest.xml`。

**功耗：** 没有定时器、后台任务。补全是在打字停下 150 毫秒后才算一次；着色的做法 r52 起改了，见 #48（原来是滚动时只给屏幕附近着色，滑动会卡）。补全在文件超过 60 万字符时不开。配置结构表（约 56 KB）只在打开 `config.yaml` 时在后台线程读一次。

**局限和没做的：**
- **r49 的第一次构建只有一个编译错误**（`FilesActivity.kt` 里 `select { }` 块中的 `this` 是 `SelectBuilder` 不是 Activity，r50 改成 `this@FilesActivity`），编辑器的全部代码（`design` 模块）编译通过了；但**没在手机上看过，也没跑过任何测试**。是新写的一大块 UI，第一次构建很可能有编译错误或排版问题，要靠 Actions 验证，贴出错误我再改。
- 没有代码折叠、片段（snippet）、provider 文件（`proxy-providers`、`rule-providers` 里的文件）的专用补全（FlClash 有，这次没移植）、JavaScript 脚本补全。
- 文本是整个排版的，所以限制 1 MB；很长的订阅配置（几万行）会超过，会交给外部编辑器。
- 补全弹窗用的是 `PopupWindow`，位置按光标算；键盘弹出时空间小，可能挤在光标上方，也没在真机上验证。
- `YamlLines.kt`、`ClashCompletion.kt`、`UndoStack.kt`、`YamlHighlighter.kt` 不依赖 Android，可以在 JVM 上写测试，这次没写。

## 47. 配置卡片直达编辑入口；编辑器字体加减、保存 / 导入到手机任意位置（r51）
**效果：**
- **配置页卡片：** "N 小时前"往左移开（和右边的铅笔、竖线、⋮ 隔开），时间右边新增一个铅笔图标，点一下**直接**打开这个配置的 `config.yaml`（内置编辑器 #46），不用再经过"⋮ → 文件 → config.yaml"。
- **字体：** 编辑器底部状态行右边有 `A−` 数字 `A+`，每点一次 ±2sp（9 到 37，默认 13，行号跟着变），选的大小用 `SharedPreferences`（`custom_editor` / `size_sp`）记住，下次打开沿用。
- **保存到手机任意位置：** 编辑器右上 ⋮ →"保存到手机任意位置…"，用系统文件选择器（`CreateDocument`）选目录和文件名，把页面上的文字写过去。从配置卡片进来时默认文件名是"配置名.yaml"，别处进来是原文件名。这是"另存一份"，不动配置本身，也不清掉"未保存"状态。
- **从手机任意位置打开 / 导入：** ⋮ →"从手机任意位置打开 / 导入…"，用系统文件选择器（`GetContent`）选一个文件，读出来**替换**当前页面的文字（一步可撤销，页面变成"未保存"，点保存才写进配置）。超过 1 MB 的文件拒绝（和编辑器的上限一致；同时从卡片直达时也加了这个检查）。

**新增 / 改动文件：** 没有新文件。`custom/EditorActivity.kt`（`saveAs`、`importFrom`、`readText`、`suggestName`，`intent()` 多一个可选参数 `title`）、`custom/editor/EditorDesign.kt`（`Request.SaveAs` / `ImportFrom`、字号按钮和记忆、⋮ 菜单两项）、`custom/editor/CodeEditText.kt`（`setSizeSp`、`replaceWhole`）、两份 `strings_editor.xml`。上游文件改动见总表。

**功耗：** 全部由点击触发，没有定时器；字号只在点击时写一次偏好；文件选择器和读写都在点击后做一次。

**局限：** 没有 Kotlin 编译环境，没编译、没在手机上看过，靠 Actions 构建验证。"导入"是把文件内容放进正在编辑的那个配置里，不是新建配置（新建配置仍走配置页右上角的 +）。导入时遇到不是 UTF-8 的文件会出乱码。

## 48. 编辑器滑动卡顿的根治：整份文本着色一次，滑动时不再着色（r52）
**原因：** r49 的着色是把颜色标记（`ForegroundColorSpan`）挂在文本框的同一份文本上，只给屏幕和上下各 40 行上色。滑出这个范围就要在主线程上把几百个旧标记一个个删掉、再解析范围内每一行、再加上几百个新标记（每加删一个文本框都要处理一次通知），而且 `subSequence` 取一行文字还会连这一行的标记一起复制。滑动时反复触发，所以卡。对照 FlClash（`plugins/code_forge` 的 `syntax_highlighter.dart`、`renderer_layout.dart`）：它的颜色按"行文字"缓存，滑动只是把缓存好的行画出来，滑动路径上没有任何上色工作；滑动时颜色一直都在。
**现在：**
- 文本不超过 3000 行且不超过 15 万字符时，**打开文件后给整份文本上一次色**（`highlightNow()` 的整份模式，`wholeDone = true`），之后**滑动不做任何着色工作**（`onScrollChanged`、`onSizeChanged` 直接跳过），颜色滑动时一直都在。
- 编辑时只重新给**改到的那几行**上色（`recolour()`，在 `afterTextChanged` 里当场做，范围是这次修改涉及的行，先删这几行的旧标记再加新标记），不再整份重来，也不再等 100 毫秒。一次粘贴涉及超过 500 行时放弃逐行处理，等一会儿整份重来一次。
- 取一行文字改用 `TextUtils.substring`（不复制标记）。
- 超过上面大小的文件仍用原来的办法（只给屏幕附近上色，滑出范围才重新上色），这种文件滑动仍可能有点卡，没有处理。
**没做的和局限：** 没有 Kotlin 编译环境，没编译、没在手机上看过，靠 Actions 构建验证，**滑动是否真的顺了要在手机上看**；如果还卡，说明根源不只这一处，需要在手机上测每一步用了多少毫秒。每一行的解析结果没有按"行文字"缓存（FlClash 有），只在打开和编辑那一行时算。
**功耗：** 没有定时器；打开文件时算一次，打字时只算改到的行，滑动时不算。

## 49. 编辑器惯性滚动：划一下松手后继续滑（r53）
**原因：** 编辑器的"卡"其实不是性能问题（#48 的着色改动对它没有帮助，但保留）：安卓自带的 `EditText` 只会手指拖多少动多少，手指一抬文字就停，系统没有给它做甩动后的惯性滚动。对照 FlClash：它的编辑器是 Flutter 自己画的，滚动用 Flutter 自带的惯性物理，划一下会继续滑。
**现在：** `CodeEditText` 里加了惯性滚动：松手时用系统的 `GestureDetector` 读手指的速度，用 `OverScroller` 按这个速度继续滑，到顶或到底自动停；手指再按下立即停住。滑行期间 `bringPointIntoView` 不生效（否则文本框可能把文字拉回光标处）。
**功耗：** 没有定时器；只在一次甩动之后到停下之前按屏幕刷新走（`postInvalidateOnAnimation`），停了就不再运行。
**r54：补成和连接页（`RecyclerView`）一样：**
- **边缘光效：** 手指在顶或底继续往外拖，会拉出系统的边缘光（`EdgeEffect`）；甩动撞到顶或底，光效吃掉剩下的速度，滑行结束。画法照 `ScrollView.draw`。
- **滑行中按住：** 只是让文字停住，不当成点击（这一下的抬起改成取消事件交给文本框，光标不会移过去）；按住后继续拖照常滚动。
**r55：划动不再弹输入法。** 文本框有焦点时，每次手指抬起都会弹输入法（系统行为，不分点击还是划动；划到顶或底时文字滑不动，还会被当成点击把光标移过去）。现在手指拖动超过触摸误差的手势，抬起时把 `ACTION_UP` 改成 `ACTION_CANCEL` 交给文本框，不移光标、不弹输入法；惯性滚动用的仍是真实的抬起事件。有选中文字时不改（那一下抬起属于选字）。副作用：划动后光标不会自动跳回屏幕里，留在原来的位置。点一下仍然移光标、弹输入法。
**局限：** 没有 Kotlin 编译环境，没编译、没在手机上看过，靠 Actions 构建验证，边缘光的位置和方向要在手机上看。只有竖向惯性，不支持嵌套滚动（编辑器不在别的可滚动容器里，用不上）。

## 50. 编辑器：大文件分段、缩进参考线、括号引号配对、问题标记、显示空白字符、订阅提示（r56）
**效果：**
- **大文件：** 文件上限从 1 MB 提到 8 MB。不超过 3000 行（且 15 万字符）的文件和以前一样整份在文本框里；超过的分段显示：文本框里只放一段（最多 3000 行），上面和下面的行以普通字符串存着。行号、状态栏、"跳到行"、检查报的行号都是整份文件的行号。底部一行有 ◀ ▶ 换段（新一段和旧一段重叠约 100 行，看得出接在哪），⋮ 菜单有"跳到行…"，查找会搜整份文件、结果不在这一段时自动换段，"全部替换"替换整份。保存、检查、导出用的是整份文本。每段整段着色一次，滑动不着色（同 #48）。
- **缩进参考线：** 每两列一条竖线；空行取上下最近两行缩进的较小值，竖线不断。
- **括号引号配对：** 光标紧挨着 `() [] {}` 时，它和另一个都加浅蓝底；找不到另一个（一直找到文件头 / 尾也没有）加红底。引号只在"值或列表项开头"处算开头（`don't` 里的撇号不算），同一行内配对，没关的引号标红。
- **问题标记（只对 `.yaml` / `.yml`）：** 打字停 0.7 秒后，在另一个线程读一遍整份文本（`YamlLint.kt`），有问题的行在行号旁画红点（错误）或橙点（警告）；光标所在行的问题写在底部一行；底部的 `⚠ N` 点开是问题列表，点一项跳到那一行。内容：缩进用了 Tab（红）、缩进比上一行多但上一行不是以 `:` 结尾的键（红）、缩进和上面各层都对不上（红）、同一层重复的键（橙，`<<` 除外）、重复的节点名 / 策略组名（橙，节点和策略组共用一套名字）、策略组 `proxies` 里写了不存在的节点或策略组（橙，认 `DIRECT REJECT REJECT-DROP PASS COMPATIBLE GLOBAL`）、策略组 `use` 里写了不在 `proxy-providers` 的名字、规则最后一段指向不存在的策略组、`RULE-SET` 指向不存在的 `rule-providers`（`SUB-RULE` 不查）。块文本（`|` `>`）、跨行的 `[ ]` `{ }`、跨行的引号字符串里面的行不查。行数变了、检查结果还没更新时红点先不画，避免错位。
- **显示空白字符：** Tab 画成带 → 的淡黄底，行尾空格 / Tab 画成淡橙底（光标就在行尾时不画，免得打字时闪）；⋮ 菜单里"显示 Tab 和行尾空格"可关，选择记在 `SharedPreferences`（`custom_editor` / `show_whitespace`，默认开）。
- **订阅提示：** r57 起被 #51 取代（订阅类配置在编辑器里根本写不进去，改成只读）。

**新增 / 改动文件：** 新增 `design/.../custom/editor/YamlLint.kt`（检查，无 Android 类）、`YamlPairs.kt`（括号引号配对，无 Android 类）；改动 `CodeEditText.kt`（分段、绘制、查找、后台检查）、`EditorDesign.kt`（问题行 / 按钮、跳到行、空白开关、订阅提示条）、`custom/EditorActivity.kt`（8 MB、`lint` / 提示参数、`EXTRA_UPDATES`）、两份 `strings_editor.xml`。上游文件：`ProfilesActivity.kt`、`FilesActivity.kt` 各多传一个参数，见总表。

**功耗：** 没有定时器。检查只在打字停下 0.7 秒后（和打开文件时）跑一次，每次新开一个线程、跑完即退，结果过期就丢；配对只在光标动或文字变时查（最多各向两边找 2 万字符）；参考线和空白字符只画屏幕上的行。

**局限和没做的：**
- 没有 Kotlin 编译环境，**没编译、没在手机上看过**。检查的算法先用 Python 写了一份（`YamlLint.kt` 是它的逐行对照）在示例配置和仓库里的 workflow 文件上跑过，没有误报，再手工翻成 Kotlin；Kotlin 本身只有 Actions 能验证。
- 换段会清空撤销记录；分段显示时补全（`config.yaml` 的键提示）关掉（光标上层的键可能在窗口外）；文件整个以字符串存着，8 MB 的中文文件内存约几十 MB。
- 引用检查认的是文件里写出来的名字：节点来自别处合并、或由 provider 里的节点在 `proxies` 里被引用，会被报成"不存在"；单行 `{...}` 写法的节点和策略组认，跨行的 `{...}` 不认。规则的策略取最后一段（去掉 `no-resolve` / `src`），自定义写法的规则可能误报。
- 没写 JVM 单元测试。

## 51. 编辑器保存即生效；订阅 / 外部导入配置只读（r57）
**原因（读代码得出）：** 编辑器写文件走 `FilesProvider.openDocument` → `Picker.pick(path, writable=true)`：先 `cloneToPending`（把正在用的 `imported` 复制成待保存副本 `pending`，并建一条 `Pending` 记录），再写进副本。副本要等配置页 ⋮ → 属性 → 保存（`ProfileManager.commit` → `ProfileProcessor.apply`：内核校验，通过才替换 `imported`、删 `pending`、广播 `PROFILE_CHANGED`，在用的配置随之重载）才生效。所以 r49–r56 的"已保存"并没有生效。另外 `pick` 对 `type != File`（订阅、外部导入）的写模式在 `cloneToPending` **之后**抛 `invalid open mode`：这类配置在编辑器里保存必失败，还会白留一份 `pending`（卡片上多出"未保存"）。r56 的"订阅更新会覆盖"提示前提是错的，已去掉。
**效果：**
- 点保存：先写进副本，再直接调用 `commit`（和属性页的保存是同一个调用）。内核接受 → 提示"已保存并生效"，在用的配置立即重载一次；内核拒绝 → 弹窗写原因（带出错行的片段），config.yaml 里把出错那行标红并跳过去，在用的配置不变，修改留在副本里，改好再保存。保存、检查、用其他应用打开、离开时选保存，都走这条路。
- `type != File` 的配置（`ProfilesActivity` 铅笔、`FilesActivity` 传来的 `readOnly`）：编辑器只读（过滤器拒绝一切改动，不弹键盘），隐藏撤销 / 重做 / 保存 / 替换行 / 符号行 / 导入，顶部有一条说明；仍可查看、查找、"保存到手机任意位置"、跳到行。读文件用只读模式，不会触发 `cloneToPending`。
**改动文件：** `CodeEditText.kt`（`readOnly`、`InputFilter`）、`EditorDesign.kt`（`readOnly` 参数取代 `updateNotice`）、`custom/EditorActivity.kt`（`applyProfile`、`EXTRA_READ_ONLY`）、两份 `strings_editor.xml`。没有新上游文件改动。
**功耗：** 只在点保存时多一次 `commit`，没有定时器。
**局限：** 没编译、没在手机上看过。保存即生效会让在用的配置重载一次（连接会断一下）。属性页退出时（`PropertiesActivity` 的 `defer` → `release`）仍会无条件丢弃 `pending`：编辑器现在保存即提交，不再受这个影响，但提交失败后留在副本里的改动，进属性页再退出会被丢掉。

## 52. 测 IP 页面改成表格：逐个 IP 测 10 次、端口、自动归属地、机房城市、点一行才测速、实时状态（r58）
**效果：** 点"测试其他 IP"填好后，结果页不再是一段文字，而是表格（我们自己的主题，间距和颜色是参照一张截图估的）加表格下面的"实时状态"。
- **直连列：** IP地址 | 机房（城市） | 归属地 | 端口 | 丢包率 | 最小延迟 | 最大延迟 | 平均延迟 | 测速 | 速度。**套入节点列：** IP地址 | 出口（国旗） | 端口 | 丢包率 | 最小 | 最大 | 平均延迟 | 比对照快 | 测速 | 速度。IP 列固定在左边，其余横向滚动。点 IP 复制（IP 下面是虚线，`DashedTextView`），IP 下面的灰色小字是 TLS 版本、ALPN、ECH 结果、"确认是 Cloudflare"、"已在配置里"（没通的行是失败原因）。平均延迟 <150 ms 绿、<300 ms 橙、否则红（`IpTestView.latencyColor`）；丢包率 0 绿、<50% 橙、否则红。按平均延迟从小到大排，全失败的排最后。
- **每个 IP 测 10 次**（最多 5 个同时，每次握手最多 3 秒，最坏约 6 秒）→ 丢包率、最小、最大、平均延迟。IP 之间仍随机隔 1 到 3 秒，**这个等待现在在 App 里**（协程 `delay`，没有定时器）。套入节点测同样 10 次（`ipdelay.TryIP`），但 `TryIP` 自己的超时是 5 秒，改不了 3 秒（要改 `ipdelay` 里的 `testTimeout`，没动）。
- **机房 → 城市：** 每一轮开头向 `https://speed.cloudflare.com/locations` 取一次 Cloudflare 的机房列表（JSON 数组，每项有 `iata`、`city`、`cca2`，字段名是查 cf_speedtest 工具源码确认的，**这个地址在本机没能直接请求，真实返回没亲眼看过**），只放内存；取不到时机房只显示代号，日志里写原因。Go 模式 `"colo"`。
- **归属地：自动查**（取消 #45 的"点一下才查"）：每个通了的 IP 测完后，`ipinfo.io` 没有再 `ipwho.is`，串行（和 IP 之间的间隔一起），记在内存；查不到或被限流只是不显示，日志写"没查到（可能被限流）"，不影响这个 IP 算不算通。
- **端口：** 直连时，通了的 IP 自动对 `443, 2053, 2083, 2087, 2096, 8443` 并行试握手（每个最多 3 秒，只握手，SNI 用页面的域名，不带 ECH）；443 就是前面 10 次用的端口，不重复试。表格里通的端口绿色，不通的灰色带叉；**点端口那一格弹窗，列出每个端口通不通、延迟、失败原因**，并写明"端口不通可能是 IP 的问题，也可能是运营商拦了这个端口"。端口列表在对话框里可改，记在 `custom_config_check` 的 `ip_ports`（最多 12 个）。**套入节点测的"端口"列显示的是节点自己配置的端口，不是测出来的。**
- **测速（只在点那一行的"测速"时才下载）：** 一次只一个；下载时那一行的按钮变"取消"，别的行的按钮变灰；测 IP 的一轮在跑时所有"测速"都灰着。最多 10 MB 或 5 秒（先到先停），前 1 秒不计（慢启动），报稳定速度（MB/s，1 MB = 10^6 字节），单连接 HTTP/1.1。默认地址 `https://speed.cloudflare.com/__down?bytes=10485760`，直连到被测 IP（SNI 和 Host 用地址里的域名，不经过 VPN）；套入节点测时走所选节点（`ipdelay.DownloadVia`）。地址在对话框里可改，记在 `ip_url`。再点一次重测并覆盖；不会自动下载。只是参考：和你实际用的线路、时段、服务器都有关。
- **实时状态：** 表格下面的等宽小窗，一行 `[HH:MM:SS] 文字`，最新在底部、自动滚到底，最多留 200 行，背景跟随 App 的浅色 / 深色（不是截图里的深蓝黑）。事件：开始、机房列表取到 / 没取到、开始测第几个、这个 IP 完成（平均延迟、丢包、通的端口）或失败、归属地找到 / 没找到、取消、全部完成；测速的开始、结果、失败、取消。右上角复制按钮复制表格加日志。
- **做法：** App 一次只让内核测一个 IP（Go 新增模式 `"parse"` 先把输入读成列表、`"probe"` 直连测一个、`"nodeone"` 套入节点测一个、`"download"`、`"colo"`），每个 IP 测完更新表格、写日志，粒度是每个 IP 一行日志。没有新的推送通道，没有轮询。点"取消"：App 停循环并通知内核停当前这个（`closeCustomConnection("!cancel-iptest")`），表格留着，日志写"已取消"。旧的整批模式 `"direct"` / `"node"` / `"asn"` 还留在 Go 里，App 不再用（测试在用）。
- **没做（说好不做的）：** 内置机房表、带备注的复制格式、/24 分组、和上次对比、HTTP 状态 / 首包时间、证书信息。
**新增文件：** `conftest/iptest_probe.go`（`runOne`、`probeDirect`、`probeNode`、`downloadOne`、`fetchColos`、`parseList`、端口、10 次统计、ECH 配置缓存 2 分钟）、`iptest_probe_test.go`、`ipdelay/iptry_download.go`（`MeasureDownload`、`DownloadVia`）、`ipdelay/iptry_download_test.go`。
**改了的自有文件：** `iptest.go`（`IPRequest` / `IPRow` / `IPReport` 多字段，`RunIP` 多一个分发，`handshakeWithin`）、`IpTestReport.kt`（新字段、`IpPortResult`、`colos`）、`IpTestView.kt`（整个换成表格，文件名没变，所以没有要手动删的文件）、`IpTestDialog.kt`（多端口和下载地址两个输入框）、`ConfigCheckDesign.kt`（`ipBegin` / `ipRows` / `ipLog` / `ipSpeedBusy` 等，去掉 `showIpTest` / `setIpOwner` / `onLookup`）、`ConfigCheckActivity.kt`（`runIpTest` 改成逐个 IP 的循环，新增 `runSpeed` / `cancelSpeed`，去掉 `lookupOwner`）、`design_config_check.xml`（文字外面套一层，多一个放表格的容器 `check_ip_container`）、两份 `strings_iptest.xml`（去掉不用的 `iptest_lookup*`、`iptest_copy_all` 等，加列名、日志、端口弹窗文案）。没有动上游文件。
**功耗：** 全程没有定时器和轮询：IP 之间的等待是挂起的协程，每个 IP 一次内核调用；下载只在点"测速"时发生，最多 5 秒。
**验证和局限：** Go：`iptest.go`、`iptest_probe.go`、`iptry_download.go` 用桩代码（假的 `errlog` / `ipdelay` 其余部分 / `dialer` / `constant`）做了 `go vet` 和单元测试：10 次统计、端口读取、机房列表读取、本机 TLS 服务器上的直连测（h2、`Cf-Ray`、端口通 / 不通、归属地从缓存取）、全部失败、取消（几百毫秒内返回）、各种错误、本机 TLS 服务器上的下载（字节数、`Host`）、限时和限字节的下载、读列表。**没编译的：** 真正的 `ipdelay` 包（`DownloadVia` 用了 `newVariant`、`closeProxy`、`selfDialing`，都是照 `TryIP` 抄的，没跑过）和全部 Kotlin（表格、弹窗、循环），要靠 Actions 构建验证，也没在手机上看过版式（列宽、行高、左列固定是估的）。机房列表地址、`ipinfo.io` / `ipwho.is` 的格式没在真实网络里试过。


## 53. 编辑器上下边距、配置卡片铅笔、双指缩放字号、键盘不打乱选中、运行中配置问题（并进错误与诊断页）（r59，r60 合并，r61 改成被动读取）
**编辑器 / 配置页（小改）：**
- 编辑器顶部不再和状态栏重叠：`EditorDesign` 的内边距取"系统栏（ViewCompat 监听）"和"`surface.insets`（其他页面用的那个）"里较大的，键盘出来时底部取键盘高度。
- 底部变矮：原来的提示行、"勾（问题）"行合并进状态行（状态文字后面直接跟当前问题的描述，窗口提示也在这一行），问题按钮只在有问题时出现在同一行里，符号行和按钮高度降到 34 / 32dp。
- 配置页卡片：铅笔图标 18dp（原来 24dp 加更大的内边距），和右边时间、左边文字的间距缩小（`adapter_profile.xml`）。
- 双指缩放字号：`CodeEditText` 用 `ScaleGestureDetector`，每放大或缩小 1.08 倍加或减一个字号，手指不抬可以连续缩放；缩放时保持顶部那一行不跳。
- 长按选 IP 后弹键盘打乱选中：**没法在手上复现，这是按读代码得出的原因做的缓解**——手指还按着时，不做"滚到光标"，也不把键盘高度加进内边距（等手指抬起再做）。API < 30 的 `adjustResize` 由系统调整窗口，延不了。
**运行中配置问题：** 内核运行时发现有配置没生效，一个问题一条：
- **通知**（最多显示两条，只有出现新问题才响，没有问题时自动撤掉）、**首页顶部一张卡片**（显示前两条，放不下写"点此查看全部"）；两者点开都进**错误与诊断页**，页面最上面是"配置问题"块：每条有"忽略"，已忽略和已恢复的分开列在下面，可取消忽略；右上角"清除记录"同时清除已恢复和未忽略的问题。忽略记在内核 home 目录的 `issues-ignored.json`，重启后仍然有效。复制全部时，未忽略的问题排在最前面。
- **不重复：** 从内核日志读到的问题（下面第 4、5 类）只出现在"配置问题"块，不再同时出现在错误页的"其他警告 / 错误"里（`errlog.Hook` 返回 true 就不记进那份列表）。
- **原则：** 内核已经在日志里告诉我们的，直接读；内核不说的，**不主动探测**，只在"DNS 真的失败了"时才顺带问一次。r59 / r60 的"配置加载后、网络变化后主动探测所有 DNS 服务器和节点"在 r61 **整个去掉**（`ipdelay.CaptureDNS` 的 `OnLoaded` 钩子、`retest.go` 的触发、节点 TCP / 延迟探测都删了）。
- **对照内核源码（mihomo 3f27ef39）确认过的事实：** `log.Warnln` 等函数在判断日志级别**之前**就把事件发给订阅者（`log.go`：`logCh <- event` 在 `print` 之前），所以订阅日志不用改内核日志级别，DEBUG 行也收得到。但单个 DNS 服务器失败在**任何级别都没有日志**（`dns/util.go` 的 `batchExchange`：每个服务器的错误只交给 `picker`，先返回的答案胜出、其余被取消，只在全部失败时报 `all DNS requests failed, first error: …`，第一个错误也没写是哪个服务器）。节点拨号失败有日志（`tunnel/tunnel.go` 629 / 631，WARNING，已被 `errlog` 读取），原因里的 `dial tcp <节点IP>:<端口>: …` 是 Go 网络错误的文字。
- **能发现的：**
  1. **节点地址不通（`ip`）：** 读真实连接失败。`[TCP] dial <节点> … error: <原因>` 的原因里取出 `dial tcp|udp <地址>:<端口>`，那个地址就是不通的节点地址（`issues.fromDial`）。不算：DIRECT / REJECT 等、地址就是目标本身、"context canceled / closed / broken pipe"、"network is unreachable / no route to host"（没 IPv6 或没网）。QUIC / UDP 类节点的原因文字不一定带地址，读不出就不报。这条拨号失败行**仍然留在**错误页的"失败的连接"里（那里按目标列，信息不同）。**没有"已恢复"的信号**（内核不记成功），要等你忽略、点清除或内核重启。
  2. **DNS 服务器不通（`doh`）：** 只在**真的有连接因为所有 DNS 都失败**时才问：日志里出现 `all DNS requests failed`（拨号失败的原因里，或单独一行）→ `issuecheck.DNSFailed()` → 对每个服务器问一次 `www.cloudflare.com`（`conftest.ProbeIssues`，写了 `#代理组名` 的服务器通过那个代理组问），哪个不通报哪个；之前报过、这次问通了的标"已恢复"。**一分钟内最多一轮**（比较上次开始的时间，不是定时器）；没网（1.1.1.1 和 223.5.5.5 的 443 都连不上）时整轮不报。
  3. 订阅更新失败（`sub`）：`ProfileWorker.failed()` 末尾通知；更新成功会标"已恢复"。
  4. 订阅源（provider）更新失败、GeoSite / GeoIP / MMDB / ASN 文件缺失或损坏或更新失败、`nameserver-policy` 里有无效项、所有 DNS 请求失败（`provider` / `geo` / `dns`）：来自内核日志的 WARNING / ERROR 行，措辞对照源码确认过。这几类（拨号失败行除外）被 `issues` 取走，不再出现在错误页的"其他警告 / 错误"里（`errlog.Hook` 返回 true 就不记）。
  5. ECH 被拒绝 / 过期（`ech`）：按日志里同时出现 `ech` 和 reject / expired / retry / fail / invalid 的行判断，**措辞没有逐行对过源码，尽力匹配，可能漏报**。
- **做法：** Go 侧新包 `issues`（存储，变化后延迟 1.5 秒回调一次；`FromLog` 读日志行；`OnDNSFailed` 钩子）、`issuecheck`（`DNSFailed`：冷却、单飞、问一轮）；`conftest/dnsissue.go`（`ProbeIssues`）；`ipdelay/issueprobe.go`（`WithSelfDial`，让我们自己的连接不算真实流量）；`connections.go` 加排序码 10（`SORT_ISSUES`，过滤字段为命令：空 = 列表，`sub|名|原因`，`subok|名`）和 `!issue-ignore:` / `!issue-unignore:` / `!issue-clear`；`nodeip.go` 里订阅回调时设置钩子（`errlog.Hook`、`issues.OnDNSFailed`、变化时用 `fetch_report("issues")` 通知）。Kotlin：`NodeIpWatcher.start(onIssues, onChange)`；服务侧 `IssueNotifier`（通知，渠道 `custom_issues_channel`，点开进 `ErrorsActivity`）；`ACTION_ISSUES_CHANGED` 自广播，首页的 `customNodeIpEvents` 多监听这一个动作；首页卡片 `custom_issues_card`（`design_main.xml` 里一张 `LargeActionCard`，由 `MainCustomCards.setIssues` 填，点开进错误页）；`refreshIssues` 在每次首页获取和每次事件时查询；错误页的"配置问题"块由 `IssuesBlock` 画进 `design_errors.xml` 里的 `issues_list`。
**新增文件：** `core/.../native/issues/issues.go`、`issues_test.go`、`issuecheck/issuecheck.go`、`issuecheck_test.go`、`conftest/dnsissue.go`、`ipdelay/issueprobe.go`；`core/.../core/custom/IssuesSnapshot.kt`；`service/.../custom/IssueNotifier.kt`；`design/.../custom/IssuesBlock.kt`、`IssueText.kt`；`strings_issues.xml`（design 和 service 各自 `values` / `values-zh`）。（r59 的 `IssuesActivity.kt`、`IssuesDesign.kt`、`design_issues.xml` 在 r60 删除，见交付说明。）
**改了的自有文件：** `errlog.go`（`Hook`）、`doh.go`（`dohDoDial`）、`dnslogic.go`（`serverSpec.Dial`）、`dnscheck.go`（DoH 用 `spec.Dial`）、`connections.go`、`nodeip.go`、`CustomConnections.kt`、`NodeIpWatcher.kt`、`NodeIpNotifier.kt`、`CustomIntents.kt`、`NodeIpEvents.kt`、`MainCustomCards.kt`、`MainCustomRefresh.kt`、`ErrorsActivity.kt`、`ErrorsDesign.kt`、`ErrorsReport.kt`、`design_errors.xml`、`EditorDesign.kt`、`CodeEditText.kt`。上游文件见上面的表（`design_main.xml`、`MainDesign.kt`、`MainActivity.kt`、`adapter_profile.xml`、`ProfileWorker.kt`）。
**功耗：** 没有定时器、没有轮询，也没有任何主动探测的周期：读日志是订阅内核本来就在发的日志事件（`errlog` 早就在读）；只有"所有 DNS 都失败"时才问一轮 DNS 服务器，一分钟内最多一轮。通知和卡片只在问题列表变化时更新。退出编辑器等页面不会留下后台任务。
**验证和局限：** `issues`、`issuecheck` 用桩代码做了 `go vet` 和单元测试（日志解析、拨号失败里的节点地址（含 IPv6，不算 DIRECT / 目标本身 / 取消 / 没网）、忽略持久化、清除、命令、冷却和单飞、没网不报、报告和恢复）；真正的 `conftest.ProbeIssues`（经代理组拨号：`tunnel.Proxies()` 的 `DialContext`，目标是 IP 时填 `DstIP`）和全部 Kotlin 都**没编译过**，要靠 Actions 验证，版式也没在手机上看过。**发现不了的：** 只坏一个 DNS 服务器而别的服务器还能答（内核没有任何日志，不触发）；节点没被真实流量用到时坏了（没有失败的连接）；QUIC / UDP 类节点地址读不出来的失败；内核悄悄忽略的配置项（需要对照内核的配置解析源码）；走代理的 UDP DNS 服务器和 `#网卡名` 的 DNS 服务器（探测时不判断）。日志来源的问题（含节点地址）保持到你忽略、点清除或内核重启为止，不会自己标"已恢复"（DNS 服务器除外：下一轮问通了会标）。

## 54. 测 IP 表格：字号加 2、列铺满页面不再藏到屏外、长文字换行、机房名后加中文；清掉 r59 的旧文件（r62）
**构建错误的原因：** r60 起"配置问题"并进了错误与诊断页（#53），r59 的 `IssuesActivity.kt`、`IssuesDesign.kt`、`design_issues.xml` 本该删掉，但增量压缩包没法带上"删除"，仓库里还留着它们；`design_issues.xml` 用到的 `issues_clear` / `issues_hint`（`IssuesDesign.kt` 还用 `issues_empty` / `issues_empty_not_running`）早就没有定义，AAPT 资源链接失败。**r62 把这三个文件删掉了**（没有别的地方引用它们，已全仓库搜过；也对照所有 `R.string.*` / `@string/*` 的使用和定义查过，没有别的缺失，只剩构建时生成的 `application_name` / `launch_name` / `release_name`）。*以后增量包里凡是要删的文件，在交付说明里单独列出来。*
**字号（sp）全部加 2（只改 `IpTestView.kt`）：** 表格里的 IP 13→15（粗体、虚线）；IP 下面的灰色小字 10→12；表头 12→14（粗体）；数据格 12→14；测速按钮 12→14；表格上方说明 13→15；表格下面的提示 11→13；"实时状态"标题 12→14（粗体）；实时状态日志 11→13（等宽）。配置检查页本身的输入框、按钮、灰色说明没动。
**列宽（`IpTestView.widths()`）：** 不再用固定宽度加横向滚动。每列有一个**最窄宽度**（IP 列按这一页最长的 IP 在 15 sp 粗体里量出来，112 到 150 dp；机房 / 归属地 / 端口 84 dp，丢包率 52，最小 / 最大 / 平均延迟 54，测速 52，速度 58 dp）；页面比这些最窄宽度之和更宽（平板）时，多出来的宽度按比例分给各列（机房、归属地、端口分得多一点，权重 1.8 倍），表格正好铺满页面，**不需要横向滚动，所有列都在屏幕里**；页面比最窄宽度之和还窄（手机竖屏）时才退回原来的样子：IP 列固定在左边，其余横向滚动。宽度变了（转屏、分屏）才重排一次（`tableBox` 的 `OnLayoutChangeListener`，宽度没变不动），没有定时器、没有轮询。
**换行：** 数据格、表头不再限制 3 行加省略号，全部换行；每一行的高度取这一行里最高的那一格（`heightOf` 先按列宽量一遍），固定的 IP 列和右边部分用同一个高度，所以两边始终对齐；表头同理。端口一格里的端口按空格换行，归属地（"AS13335 Cloudflare, Inc."）按词换行，IP 下面的小字最多 6 行。测速按钮的左右内边距从 10 dp 缩到 6 dp，这样 54 dp 的列里放得下"测速" / "取消"。
**机房名后加中文（`ColoNames.kt`，新文件）：** 机房一格显示 `SJC（圣何塞）`。中文名是**内置的**（约 330 个 Cloudflare 机房的 IATA 代码，按记忆整理，个别冷门代码可能没收或写得不准）——因为网络上的机房列表只有英文，而且这次就是 HTTP 403 没取到。没有内置中文名的代码用网络列表里的英文城市放括号里，两个都没有就只显示代码。列够宽时代码和括号在一行，不够宽时括号部分换到下一行（不会把代码切开）。重名的城市写了区分：`SAN` 圣地亚哥·美国 / `SCL` 圣地亚哥·智利，`SJC` 圣何塞 / `SJO` 圣何塞·哥斯达黎加，`MAN` 曼彻斯特·英国 / `MHT` 曼彻斯特·美国，`SSA` 萨尔瓦多·巴西。复制按钮复制出来的表格里机房也是这个写法（一行）。
**功耗：** 没有新增定时器或轮询；量字号用的是只在重排时才做的一次性测量，最多 20 行。
**验证和局限：** 没编译过（本机没有 Android 构建环境），要靠 Actions 验证；版式没在平板上看过。最窄宽度是按 14 sp、字体缩放 1.0 估的，系统字体调大后最窄宽度之和可能超过平板宽度，那时会退回横向滚动（IP 列仍固定）。
**新增文件：** `design/.../custom/ColoNames.kt`。**改了的自有文件：** `IpTestView.kt`。**删除的文件：** `app/.../custom/IssuesActivity.kt`、`design/.../custom/IssuesDesign.kt`、`design/.../layout/design_issues.xml`。
**r62 补充（同一版本）：** 你解压 r62 后 Actions 仍报同样的错，因为压缩包解压覆盖不会删掉仓库里已有的旧文件。所以 `.github/workflows/build-debug.yaml` 在构建前加了一步 `Remove deleted custom files`，每次构建都先 `rm -f` 这三个文件；以后凡是删文件，都往这一步里加。同步上游时这一步属于 `build-debug.yaml` 要重加的内容（#54）。

## 55. 编辑器普通文字换色、代理页卡片常显 IP、配置卡片铅笔居中、测 IP 表头灰底不再有缺口（r64）
**编辑器（`EditorDesign.kt`）：** 没被高亮的普通文字（规则行 `DOMAIN-SUFFIX,xxx,...`、`select`、`ipv4` 这类）原来用接近黑色的默认文字色，和注释的灰（`#757575` / 夜间 `#7F8C98`）太像。现在编辑区的默认文字色是 `colorPlain`：浅色 `#2E7D32`，夜间 `#C3E88D`（r65 起，和带引号字符串的绿色相同；r64 原为玫红）。只是 `setTextColor` 一处，没有新增着色 span，不增加耗电。key / 字符串 / 数字 / 注释等原有颜色不变；查找、替换输入框不受影响。
**代理页卡片常显 IP（`ipdelay.GetForDisplay` / `PrepareDisplay`、`tunnel/proxies.go`、`ProxyView.kt`；r66 扩到所有 `proxies:` 里的节点）：** 没测过速的节点也在卡片下方列出它的 IPv4（最多 `maxHostsIPs` = 8 个，在用的排前面，和测速时选的一样）。地址来源三种：① `server` 本身是一个 IPv4 → 就是这一个；② `server` 是域名且 `hosts:` 里有 → `hosts:` 的 IPv4，按你写的顺序；③ `server` 是域名但 `hosts:` 里没有 → 打开代理页（`QueryProxyGroup`）时调用 `PrepareDisplay`，把这一组里还没解析过的域名**并发解析一次**（最多等 `displayResolveTimeout` = 3 秒），结果排序后存在内存 `displayIPs`，同一次加载配置内不再解析；失败的 5 分钟（`retryAfter`）后、且只在再次打开代理页时才重试。换配置（`CaptureConfig`）清空缓存。没测的 `delay = -1`：不写延迟数字，不写"N 分钟前"一行；之前测出过国家的显示国旗，没有就不显示。测过之后和以前一样（测速的选取规则没改）。**没有定时器、不轮询、不自动解析，只在打开代理页时最多解析一次。** 订阅 / provider 里的节点仍然不显示（原来的限制）。`ip-version: ipv6` 的节点不显示。`Get` 本身没改（配置检查页还在用），代理页调 `GetForDisplay`。
**配置卡片铅笔（`adapter_profile.xml`）：** 铅笔左侧额外间距由 24 dp 收紧为 0 dp；保留时间文字与铅笔之间 6 dp 的布局间距及图标自身内边距，使铅笔与时间、右侧 ⋮ 的可见图标间距接近，避免两侧留白过大。
**测 IP 表头（`IpTestView.kt`）：** 表头灰底原来是每个标题各画一块，标题折成两行的列（机房、最小 / 最大 / 平均延迟）上方会出现缺口。现在灰底画在整行容器上，标题格子透明。数据行高度在测量值上多留 4 dp，避免端口一格的最后一行被裁掉。
**r67（`IpTestView.kt`）：** ① 表格里所有格子改成**顶部对齐**（`textCell` / `ipCell` / `testCell`，上内边距都是 5 dp / 测速按钮 2 dp），每个格子的第一行和 IP 的第一行在同一条线上；原来的居中让端口、机房、归属地这类多行格子的内容从行中间往下排，上面空一大块、最后一行被裁掉。② `widths()`：最窄宽度之和只比页面宽一点点时（IP 列因为有长 IP 变宽），除 IP 列外的其他列最多一起收窄 15%（文字多换一次行），表格重新铺满页面，最右边的"速度"列不再被挤出屏幕；收窄 15% 还不够才像以前一样横向滚动。

## 56. 编辑页：历史版本、保存和应用分开、历史逻辑修正；删掉设置里的"配置历史与检查"（r68）
**效果：** 只对**自己编辑的配置**（类型 File 的 `config.yaml`）。订阅、外部导入、provider 等其他文件的编辑和保存和 r57 一样，不变。
- **顶部按钮：** `存历史`（"保存为历史版本"，条上放不下全名，长按 / 对话框里是全名）和 `应用`。⋮ 菜单里多"历史版本…"和"和正在用的配置对比…"。
- **保存为历史版本：** 只写进历史（`ConfigHistory.add`），**不碰待提交的副本、不应用**，正在用的配置不变。和**任何一个**已存版本内容相同就不再存（提示"历史里已有相同的版本"）；空配置、超过 2 MB 的不存。
- **应用：** 先保证正在用的版本在历史里（`keepInUse`），再把编辑框的文字存成历史版本（已有就沿用），再写进待提交副本并 `commit`。内核通过才生效；不通过：正在用的配置不变，这个版本标"✗ 未通过检查"，这次应用自己建的待提交副本丢掉（原来就有的不动），弹窗写原因并跳到出错行。编辑框文字和正在用的完全一样时只提示"不用再应用"。
- **检查：** 检查的是编辑框里的文字——写进缓存目录 `editor_check/config.yaml` 临时文件夹给内核（`SORT_VALIDATE` 只读那个文件夹里的 `config.yaml`），查完删掉；不再碰待提交副本。
- **历史版本：** 只列正在编辑的这个配置（按 uuid 找，改名不影响）。每行：时间、行数、比前一版 `+增 −删`，再加状态：`正在使用`（和正在用的逐字节相同）/ `已保存未应用` / `✗ 未通过检查` / `✓ 用过没出错`（#38）。点一个版本：编辑框有没保存的修改先问"是否覆盖"；然后只是**载入编辑框**（可撤销，不应用）；~~并弹出对比页~~（r73 起不再弹，改成直接标在编辑框里，见 #58）。打开列表时顺手清孤儿历史目录。
- **对比页（`ConfigDiff.kt` + `EffectiveText` 的 `compare` 模式）：** 样式和"推算最终配置"一样：`~` 改写（旧值划线，蓝）、`-` 删除（红，划线，是正在用的有而这份没有的）、`+` 新增（绿）；**所有行都展开，不折叠**，没有"内核默认值"那一类。顶部有"改写 N · 删除 N · 新增 N 行"汇总。**只在打开时算一次**，另有"重新对比"按钮（对比的是此刻编辑框里的文字）；菜单里的"和正在用的配置对比"随时可开。
  - **为什么不用内核算：** 推算最终配置那一页是内核按**文件夹**读 `config.yaml` 做的"加载时被改了什么"，不能拿两段文本比。所以在 App 里做：不引入 YAML 库，按缩进把文本排成树；同一层的节点按"名字"配对——键按键名，列表项按 `name:`（`{...}` 里的也认），没有就按第一个键或整行；配对上的比值，再比下面的子节点；删掉的行放在它原来的位置。**注释、空行、`---` 不比。** 局限：整个 `{...}` 写在一行里的节点（常见的 `- {name: a, type: ss, ...}`）值不同时整行显示成"旧→新"，不拆到字段；缩进风格不同的两份文本照样能比（按树不按列）。
- **退出：** 有改动时问"存为历史版本 / 丢弃 / 继续编辑"（不再有"保存并生效"）。退出时，编辑框里的这份还没应用、或历史里有比正在用的更新的"已保存未应用"版本，用 Toast 提醒一句（不拦）。"用其他应用打开"：先把改动存成历史版本，打开的是配置自己的文件。
- **历史逻辑修正：**
  - 去重和**所有**已存版本比（以前只和最新一份比）。应用前先存正在用的、再存新的，顺序不会错乱；`ProfileProcessor.apply` 里的 `ConfigHistory.keep` 发现已存在就沿用，并清掉它的"已保存未应用"和"未通过"标记。
  - 配置删了（`ProfileProcessor.delete`）一并删它的历史目录；打开历史列表时清掉已有的孤儿目录（不在"类型 File 且已导入"的配置里的 uuid）。**订阅的旧历史也算孤儿，会被清**；以后只有 File 类型记历史（`keep` 里加了类型判断）。备份恢复覆盖同 uuid 的配置时走 `delete`，那个配置的历史会被清掉。
  - 上限：最多 10 个；**不计数也不自动删**：正在用的、有 ✓ 的、最新的"已保存未应用"。其余留 10 个。
- **标记文件**（版本旁边的空文件）：`<毫秒>.ok`（#38）、`.saved`（用户存的、之后没应用）、`.bad`（应用时内核拒绝）。
- **删掉：** 设置 → 应用 里的"配置历史与检查"入口（`BackupPreference`）、`BackupActivity.ACTION_HISTORY`、`ConfigTools.kt`（版本列表搬进 `EditorActivity.history()`，检查搬进 `EditorActivity.check()`，不再有"恢复这个版本"那套 patch→写→commit→失败 release，应用走 `EditorActivity.apply()`）、`BackupDesign.showText`、22 条 `history_*` 文案。"导出诊断包"不动。**保留：** `ConfigHistory`、`LineDiff`（版本行的 +增 −删）、✓ 标记、`SORT_VALIDATE`、"配置检查"页。构建的"Remove deleted custom files"步骤加了 `ConfigTools.kt`。
**功耗：** 全靠点击：只在点"存历史"、"应用"、"历史版本"、"重新对比"时读写文件；没有定时器、没有轮询。差异在内存里算，退出对话框时才读一次配置文件做比较。
**新增文件：** `design/.../custom/ConfigDiff.kt`；文案 `editor_*`（`strings_editor.xml`）、`cmp_*`、`history_saved_unapplied`、`history_bad`（`strings_custom.xml`）。
**改了的自家文件：** `ConfigHistory.kt`（重写）、`EditorActivity.kt`、`EditorDesign.kt`、`EffectiveText.kt`（`compare` 参数）、`ProfileProcessor.kt`（见总表）。
**局限：** 没编译、没在手机上看过。顶部条上多了一个按钮，窄屏上标题会被挤得很短（按钮间距已收紧）。

## 57. 全 App 的 ✓ 和 ✗ 上色：✓ 绿、✗ 红，最粗最深（r69）
**效果：** 所有显示 ✓ / ✗（也包括 ✔ / ✘）的地方，✓ 一律绿色、✗ 一律红色，字重用最粗的（粗体再加一层"伪粗体"描边），颜色：白天 ✓ 深绿 `#005A0A`、✗ 深红 `#A50000`；深色主题下深色在黑底上看不见，改用最亮的 `#00E676` / `#FF5252`（r71 曾改成白天也用最亮，r72 按要求改回）。
- **涉及的地方：** 首页代理卡片的"✓ 123 ms / ✗ 不通"（#37）；编辑页历史版本列表的"✓ 用过没出错 / ✗ 未通过检查"（#38 #56）；DNS 检查每个服务器前的 ✓ / ✗（#25 #26）；测 IP 表格里不通的端口前的 ✗（#52）。
- **怎么做：** 新文件 `design/.../custom/CheckMark.kt`：`paint(context, 文字)` 扫一遍文字，给每个 ✓ / ✗ 单独加一个字符样式（`CharacterStyle`：颜色、粗体、`isFakeBoldText`）；`apply(...)` 给正在拼的文字的一段加样式。文字本身不变，复制出来的纯文本不受影响。以后新加带 ✓ / ✗ 的地方，调用其中一个即可。
- **首页卡片：** 文字是上游的 `LargeActionCard.subtext` 显示的（**r69 误挂在 `LargeActionLabel` 上，首页卡片没生效，r70 改正**），所以在它的 setter 里调了一次 `CheckMark.paint`（上游文件，已登记到总表）。
**功耗：** 文字显示时扫一遍，没有定时器。
**局限：** 没编译、没在手机上看过。

## 58. 历史版本载入后不弹对比页：增、改、删直接标在编辑页里（r73）
**效果：** 编辑页"历史版本…"选一个版本后，只载入编辑框（和以前一样），**不再弹对比页**；这个版本和**正在用的配置**相比，哪些行不同，直接画在编辑框里：
- 文字原有的颜色（key 蓝、字符串绿、数字橙…）不动，增改删只用**整行背景色**加**行号栏右边一条色带**。
- **新增行：粉红底**（白天 `#E91E8C` 淡底，夜间 `#F06EB4`）；**改写的行：棕褐底**（`#8D6E63` 系）；**删除的行：蓝灰底 + 灰色删除线文字**。这三种色相和编辑页已有的颜色（文字：蓝、绿、橙、紫、黄、灰、青；背景：搜索黄、报错红、行尾空格橙、Tab 琥珀）都不重合。
- **删除的行不在文本里：** 它们是旧配置有、这份没有的行，按原位置显示在原来那一行的上面（在最后一行之后的，显示在最后一个有字的行下面）。不能选中、不能编辑、不进文本、不会被保存，复制出来的也没有它们。改写的行只显示新值（旧值不画）。
- 对比的基准和算法没变，仍是 `ConfigDiff`（#56）：`ConfigDiff.diff` 的结果按"新文本里参与比较的行（跳过空行、注释、`---`）"一一对应到文本行号，对不上时一律不标（不会标错行）。菜单里的"和正在用的配置对比…"页没动，仍是 #56 的样子。
- **说明栏（r74，r75 改成照搬对比页的头）：** 编辑框上方一整条栏，**字号 15sp**，只在载入了历史版本、带着标记时出现。内容、文字和顺序和对比页的头（`EffectiveText.header` 的 `compare` 模式）一样：汇总"改写 N 行 · 删除 N 行 · 新增 N 行（相对正在用的配置）"；一行颜色说明 `~ 改写（旧值划线）   - 删除（正在用的有，这份没有）（带删除线）   + 新增`；没有差别时再加一行"和正在用的配置没有差别。"；灰字说明"比较配置内容和注释：空行不算，所有行都显示，不折叠。"（r77 起注释也比较，原来写的是"注释和空行不算"，见 #60）。**和对比页不同的只有两处：** ① 记号和文字的颜色用编辑框里实际画的棕褐 / 蓝灰 / 粉红（不用对比页的蓝 / 红 / 绿，否则和配置原有的颜色撞）；② 说明文字去掉了"只在打开这一页时算一次，改了编辑框以后点"重新对比""（没有这一页，也没有这个按钮了）。点一下这条栏收起成只剩汇总行，再点展开；载入别的文字时自动消失；对不上行时（`Marks.reliable` 为假）不显示这条栏，也不标。
- **改写的行：** 和对比页一样，旧值划线（棕褐色）画在这一行末尾、新值后面（`Marks.old` 存旧值，`drawDiff` 在 `onDraw` 里画；不进文本、不能选中、不会被保存）。这一行后面没有地方放时，旧值在屏幕边缘被切掉（不换行）。旧值不加粗新值，因为编辑框里的文字是真文本。
**怎么做的：** `CodeEditText` 里两种 span，都不是文本的一部分：`DiffSpan`（普通标记，覆盖整行，`onDraw` 里在文字下面画满宽的底色，行号栏画色带）和 `GhostSpan`（`LineHeightSpan`：把它所在的那一行加高 `删除行数 × 行高`，`onDraw` 在腾出的地方画删除行）。span 跟着文字走，编辑时不用重算；换一份文字（`replaceWhole` / 载入）时全部清掉。`EditorDesign.showDiffMarks` 是入口，`EditorActivity.history()` 载入后算一次、调一次。
**功耗：** 载入时算一次，之后只在绘制时画屏幕上看得到的行；没有定时器、没有轮询、没有后台工作。
**新增 / 改动文件：** `ConfigDiff.kt`（新增 `Marks` 和 `marks()`）、`CodeEditText.kt`（`setDiffMarks`、`drawDiff`、两种 span、三个颜色的只读属性）、`EditorDesign.kt`（`showDiffMarks`、说明栏 `diffNote`）、`EditorActivity.kt`（`history()` 末尾）、两份 `strings_editor.xml`（`editor_diff_note`；其余文案用 #56 的 `cmp_*`）。没有改上游文件。`editor_compare_loaded_title` 文案不再有人用（没删）。
**局限：** 没编译、没在手机上看过。超过 3000 行的大文件只标第一段（分段显示时翻到别的段，标记就没了）。载入后再手动改动文字，标记跟着行走但不会重新计算。深色 / 浅色下的颜色只是按上面的色相选的，实际好不好看要装上看。

## 59. 编辑框能打中文：去掉"可见密码"输入类型（r76）
**效果：** 编辑页的编辑框以前的输入类型带 `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD`，很多输入法把它当密码框，自动切英文、不让切中文拼音，写注释、改节点名打不了中文。现在只留"文本 + 多行 + 不要联想纠正（`NO_SUGGESTIONS`）"，输入法可以正常用中文。代码注释也改成了说明这件事。
**改动文件：** 只有 `design/.../custom/editor/CodeEditText.kt` 的 `inputType` 那两行（和它上面的注释）。没有改上游文件，没有新文件。
**功耗：** 没有影响：只是输入类型的标志位，没有定时器、没有新的绘制。
**局限：** 没编译、没在手机上看过。个别输入法在 `NO_SUGGESTIONS` 下仍可能自己显示候选条，那是输入法自己的设置。`BackupDesign.kt` 里密码类输入框用的 `VISIBLE_PASSWORD` 是对的（填账号密码），没动。

## 60. 改注释也算修改：对比和编辑页的增改删标记把注释也比进去（r77）
**效果：** 以前 `ConfigDiff` 比较时跳过所有注释，只改了注释的版本显示"和正在用的配置没有差别"。现在：
- **整行注释**算一行：新增的标"新增"，删掉的标"删除"（显示在原位置），改了字的注释显示为"删除旧的 + 新增新的"两行（按注释的文字匹配，不按位置）。整行注释属于它后面紧跟的那一行；文件最后没有后续行的注释单独放在最后比。
- **行尾注释**（`key: 值 # 说明`）算进这一行的值：只改了行尾注释，这一行标"改写"，旧值划线里是旧的整行内容。
- **空行和 `---` 仍然不比较。** 注释只改了缩进、文字没变，不算修改。
- 编辑页历史版本载入后的行标记、说明栏和菜单里的"和正在用的配置对比…"页用的是同一个 `ConfigDiff`，一起生效；说明文字（`editor_diff_note`、`cmp_note`，中英文各一份）改成"比较配置内容和注释：空行不算"。
- 历史版本的去重、"和正在用的是同一份"按文件字节比，本来就把注释算在内，没动。
**怎么做：** 只改 `design/.../custom/ConfigDiff.kt`：解析时整行注释先收进 `lead` 列表，挂在后面第一个有内容的行上（`Node.lead`），结尾剩下的放 `Parsed.tail`；行尾注释存进 `Node.note`，`valueOf` / `preOf` 把它带上；`diffPair` 先比 `lead` 再比这一行，`emitAll` 先输出 `lead`；`marks()` 里对应文本行的规则同步改成"只跳过空行和 `---`"（行数对不上时仍然一律不标）。节点的匹配名（`id`）不含行尾注释，所以改注释不会让一行被当成"删除 + 新增"。
**功耗：** 没有变化：还是载入时 / 点"重新对比"时在内存里算一次，没有定时器、没有后台工作；只是多比较了注释的几行。
**新增 / 改动文件：** 没有新文件；`ConfigDiff.kt`、两份 `strings_editor.xml`、两份 `strings_custom.xml`。没有改上游文件。
**局限：** 没编译、没在手机上看过。改了字的注释会显示成一删一增，不显示成"改写 + 旧值划线"。注释很多的配置，标记会明显变多。

## 61. 编辑页的提示改成从上面弹出（r78）
**效果：** 编辑页里的小提示（已保存、已应用、检查通过、历史版本已载入等，共 9 处 `design.toast`）以前贴在页面底部，会被键盘和正在打字的地方挡着；现在显示在页面顶部、状态栏下面（离状态栏 8dp），淡入淡出。其他页面（首页、日志等）的提示没动，还在下面。
- **怎么做：** 只改 `design/.../custom/editor/EditorDesign.kt` 的 `toast()`：不再调上游 `Design.showToast`，自己用 `Snackbar.make(root, ...)`，把它的位置参数改成靠上（`Gravity.TOP`，保留原来的左右对齐），上边距 = 状态栏高度（`barTop` 和 `surface.insets.top` 取大的）+ 8dp；动画用淡入淡出（`ANIMATION_MODE_FADE`），因为 Snackbar 自带的滑动是从底部来的，放到顶部会从屏幕下面飞上来。没有改上游文件。
- **没动的：** 离开编辑页时那条"未应用"的系统 `Toast`（`EditorActivity.leaveSplit`）：它是在页面关掉时弹的，页面已经没有了，仍由系统显示在下面。
**功耗：** 没有影响：只是显示提示时换了位置，没有定时器、没有后台工作。
**改动文件：** 只有 `EditorDesign.kt`（`toast()`、一个 import）。没有新文件。
**局限：** 没编译、没在手机上看过。提示是淡入淡出，不是从上往下滑；如果想要真正滑下来，要自己画一个提示条，可以再改。

## 62. 测 IP 表（直连）加"归属地""落地"，机房带国旗和中英文，归属运营商改名，速度列移到屏幕外（r79）
只改配置检查页的测 IP 表和它的核心代码，代理页没动。
- **直连列：** IP地址 | 机房 | 归属运营商 | 归属地 | 落地 | 端口 | 丢包率 | 最小延迟 | 最大延迟 | 平均延迟 | 测速 | 速度。**套入节点列：** 只把"出口"改名"落地"，格子从只有国旗改成"国旗 国家名"。
- **机房：** 显示 `🇺🇸 LAS（拉斯维加斯 Las Vegas）`。国旗是机房所在国家（Cloudflare 机房列表里的 `cca2`，`parseColoCountries`），英文城市来自同一份列表，中文名仍是内置的 `ColoNames`（没有新增后备）。列表没取到时：中文名还在，英文名和国旗没有，日志写"机房不显示英文名和国旗"。列不够宽时国旗、代码一行，括号里的名字放下一行。
- **机房列表 HTTP 403：** 原因没有验证，只确认请求是裸的（Go 默认 User-Agent，没有 Origin / Referer）。`fetchColos` 现在带浏览器的 User-Agent、Accept、Origin、Referer；失败时错误里加上响应头 `Cf-Mitigated`、`Server`（有才加），日志里能看出是被拦还是网络问题。**这一步能不能修好，要在手机上试。**
- **归属运营商 / 归属地：** 原来叫"归属地"的列（`AS2914 NTT America, Inc.`）改名"归属运营商"。新的"归属地"是 IP 登记的国家（旗＋国家名，国家名用系统自己的国家名表，`CountryName.kt`）。两个都来自同一次 `ipinfo.io/<ip>/json`（原来是 `/org`），只有这一次没给全才问 `ipwho.is`，所以请求数不变；结果存在内存 `asnCache` / `geoCache`。核心里没有离线 GeoIP 库可用（mihomo 源码这次不在仓库里，没核对它的接口），所以是联网查的。
- **落地（直连）：** 直连测一个 IP 是看不到落地的（落地要经过节点由外面的服务看出口）。现在：配置里有节点的 `server`（或 `servername` / `sni`）等于所填域名时，每个测通的 IP 用这个节点（server 换成这个 IP）问一次 `api.country.is`（不行再 `ipinfo.io/country`），`ipdelay.ExitCountry`；这个节点之前测出过这个 IP 的国家就直接用，不再问。有几个节点符合就取名字排最前的一个。**没有符合的节点就不查**，落地显示 `-`，日志写一次原因。连不通的 IP 不查。查的结果不存盘，也不进代理页的国旗缓存。
- **列宽（`IpTestView.widths()`）：** 除"速度"外的列放得下时，它们占满页面（多出来的宽度按文字长短分），"速度"留在右边屏幕外，横向滑动才看到；连不含"速度"也放不下才走原来的收窄 15% 或横向滚动。表格下面的提示加了一句说明。
- **表头：** 高度在测量值上多留 6 dp（数据行早就多留 4 dp），两行的标题（机房、最小 / 最大 / 平均延迟）不再贴着灰底下沿。
- **没动的 / 局限：** 没改"测速"和"速度"的逻辑；端口列的红叉、失败行的原因都没改。没编译、没在手机上看过（这次环境里没有 Go 和 Android 编译环境）。归属地和落地是两个不同的东西，对 Cloudflare 的 IP 经常不一样。
- **功耗：** 没有定时器、没有轮询。每个测通的 IP 比以前多一次落地查询（最多 2 个请求，只在有符合的节点时），其他请求数不变。
- **新增文件：** `design/.../custom/CountryName.kt`。**改了的自有文件：** `iptest.go`（`IPRow.Geo`、`IPReport.ColoCountries` / `LandNode`、`lookupOwner`）、`iptest_probe.go`、`ipdelay/iptry.go`（`ExitCountry`）、`iptest_probe_test.go`、`IpTestReport.kt`、`IpTestView.kt`、`ConfigCheckDesign.kt`、`ConfigCheckActivity.kt`、两份 `strings_iptest.xml`。没有改上游文件。

## 63. 套入节点测补"机房""归属运营商""归属地"（r80）
只改配置检查页的测 IP 表（套入节点测）和它的核心代码，直连测、代理页没动。
- **套入节点列：** 机房 | 归属运营商 | 归属地 | 落地 | 端口 | 丢包率 | 最小延迟 | 最大延迟 | 平均延迟 | 比对照快 | 测速 | 速度（前三列是这次新加的，格子样式和直连表一样）。延迟、丢包、测速、落地仍然全部经过节点，没变。
- **机房：** 经过节点发的请求拿不到接入机房（最后一跳是 Worker 去访问目标）。所以每个测通的 IP 额外**直连一次**（只在这个 IP 通了才做）：用节点自己的域名当 SNI（`servername`，没有就 `sni`，再没有就 `server`，取第一个是域名而不是 IP 的），端口用节点自己的端口（没有就 443），握手后 HEAD 请求读 `Cf-Ray`（`nodeOwner`、`nodeSNI`，复用 `handshakeECH` / `fetchEvidence`）。这一次和节点走的是同一个 IP、同一个网络，机房通常一致，**但不保证每次都一样**，日志里这行也这样写。节点没有域名可当 SNI（server 是 IP 且没有 servername / sni）就不读，机房显示 `-`，日志写原因；直连失败也是 `-`，日志写失败原因（`IPRow.ColoErr`）。
- **归属运营商 / 归属地：** 和直连测一样来自 `lookupOwner`（`ipinfo.io/<ip>/json`，不够再问 `ipwho.is`，结果存内存缓存），所以和直连测请求数一样、缓存共用。
- **填的是域名的行：** 域名没有确定的 IP，这三列显示 `-`（只有 IP 行才读机房和查归属）。
- **功耗：** 没有定时器、没有轮询。每个测通的 IP 比以前多一次直连握手 + 一次 HEAD + 最多 2 个归属查询（缓存里有就不查），只在点"套入节点测"时发生，息屏和后台不做。
- **改了的自有文件：** `iptest.go`（`IPRow.ColoErr`）、`iptest_probe.go`（`probeNode` 里调用 `nodeOwner`，新增 `nodeSNI`、`nodeOwner`）、`IpTestReport.kt`（`coloErr`）、`IpTestView.kt`（套入节点的列表）、`ConfigCheckActivity.kt`（套入节点时多三行日志；机房列表（英文名、国旗）套入节点测也取一次）、两份 `strings_iptest.xml`（`iptest_log_node_colo_*`）。没有新文件，没有改上游文件。
- **没动的：** 直连测的逻辑和列；"测速""速度"；端口列显示的仍是节点自己配置的端口。
- **局限：** 没编译、没在手机上跑过（这里只有 `gofmt`，没有 Android / mihomo 编译环境），要靠 Actions 构建验证。没有为 `nodeSNI` / `nodeOwner` 写单测：它们依赖真实的 `ipdelay` 包（`RawProxies`）。机房的准确度取决于"额外直连"和节点实际路径是否落在同一个机房。

## 64. 还原 `LargeActionLabel.kt`，让 CheckCustom 通过（r81）
CheckCustom 报 `LargeActionLabel.kt` 有 `CUSTOM` 标记却不在总表里：这是 r69 误挂在它上面的 `CheckMark.paint` 钩子，r70 改挂到 `LargeActionCard.kt`（#57）后，这个文件当时没有被还原，一直留着。现在把 `subtext` 的 setter 还原成上游的 `binding.subtextView.text = value`，没有 `CUSTOM` 标记了。首页卡片的 ✓ / ✗ 着色由 `LargeActionCard.kt` 上的钩子负责，没变。
- **改动文件：** 只有 `design/.../view/LargeActionLabel.kt`（还原成上游）。**功耗：** 没有影响。
- **局限：** 没有 Actions 可跑，只确认了该文件不再含 `CUSTOM` 一词。

## 65. 修 r80 英文文案的撇号（r82）
r80 加的 `iptest_log_node_colo_ok`（英文）里有两个没转义的撇号（`node's`），Android 资源合并报 `Invalid unicode escape sequence`，构建失败。改成 `node\'s`。只改 `design/src/main/res/values/strings_iptest.xml` 一处；中文那份没有撇号，没动。没有功耗影响。

## 66. 套入节点测加"目标网址"：对你实际要用的网站测（r83）
只改配置检查页的套入节点测，直连测没动。
- **对话框：** 多一个"目标网址"输入框（只用于套入节点测，可不填，记在 `custom_config_check` 的 `ip_target`）。填一个你实际要用的网站（`http://` 或 `https://` 开头，别的写法提示"目标网址必须以 http:// 或 https:// 开头"），不填就和 r82 一样（对节点自己的测试地址测）。
- **做法：** 填了以后，每个 IP 的 10 次测试都经过节点（server 换成这个 IP）对目标网址发 GET（`ipdelay.TryTarget`，新文件 `ipdelay/iptry_target.go`，不跟随跳转，第一个回应就是"反应"，不验证证书，和 `TryIP` 一样的构建节点副本的办法）。**只要网站有回应就算通**（不管状态码），延迟是从拨号到收到响应头；没有回应（超时、拒绝等）算丢包。
- **新增两列（填了目标网址才有）：** "目标站反应"（最常见的状态码；跳转显示 `302 → 域名`；2xx 绿、3xx 橙、其他红，如 `403` 就是被拦了）和"出口机房"（读目标站回应里的 `Cf-Ray`，是 Worker 出去访问目标站那一侧的机房，**不是**"机房"列的接入机房；目标站不在 Cloudflare 上就是 `-`）。"比对照快"这一列去掉，对照是用节点自己的测试地址测的，和目标网址没有可比性。
- **落地：** 仍然是经过同一个节点、同一个 IP，问回显服务（`api.country.is`、`ipinfo.io`）得到的出口国家，不是目标站判断的；目标站怎么判断看"目标站反应"那一列。
- **测速：** 没改，仍然下载"下载测速地址"那一格的网址（想测目标站，把那一格也填成它的一个大文件地址）。
- **日志：** 每个 IP 多一行"目标站：HTTP 状态，跳转，出口机房"（或"没有回应"）；表格上方说明里写明目标网址。
- **功耗：** 没有定时器、没有轮询；只在点"套入节点测"时发请求，每个 IP 的请求数不变（还是 10 次，只是对象换成目标网址），落地多的查询和 r80 一样。
- **新增文件：** `core/src/main/golang/native/ipdelay/iptry_target.go`。**改了的自有文件：** `iptest.go`（`IPRequest.Target`、`IPRow.TargetStatus` / `TargetNote` / `TargetColo`）、`iptest_probe.go`（`probeNode`）、`IpTestReport.kt`、`IpTestDialog.kt`（`IpTestChoice.target`、输入框）、`ConfigCheckDesign.kt`（`ipBegin` 多一个参数）、`IpTestView.kt`（两列）、`ConfigCheckActivity.kt`（校验、请求、说明、日志）、两份 `strings_iptest.xml`。没有改上游文件。
- **局限：** 没编译、没在手机上跑过（这里没有 Android / mihomo 编译环境，`go vet` 也因没有网络拉不到依赖，只做了 `gofmt`）。拿不到目标站对"国家"的判断，只能看到它的状态码和跳转；有的站对非浏览器请求会返回 403，那一格红并不一定代表浏览器里也被拦（请求的 User-Agent 是 `curl/8.5.0`，没有带浏览器的头和 Cookie）。

## 67. 目标站反应：读页面内容，认出页面自己的跳转和疑似地区限制（r84）
只改套入节点测填了"目标网址"时的"目标站反应"，其余没动。r83 只看状态码和响应头，网站用 200 加一页"本地区不可用"、或用 `<meta refresh>` / 一行脚本把你转走，都只会显示绿色的 `200`。
- **做法：** `ipdelay.TryTarget` 在拿到响应头、算完延迟之后，再读页面最多 64 KB（`sniffMax`，读失败或被截断只是少一点文字，延迟不受影响），交给新文件 `ipdelay/pagesniff.go` 的 `SniffPage`（纯函数，不依赖别的包，有单测 `pagesniff_test.go`）。它按顺序找第一个：① `<meta http-equiv="refresh" content="…url=…">`（`refresh`，记下地址）；② **页面不超过 4096 字节**时的 `location = "…"` / `location.replace("…")` / `location.assign("…")`（`script`，记下地址；大页面不判，因为大页面的正常代码里到处是这种写法）；③ 一份短的短语表（`region`，记下匹配到的那句），如 "not available in your country"、"unsupported_country_region_territory"、"您所在的国家/地区不可用" 等，都是整句，普通文字不会碰巧匹配。10 次里取第一个有结果的。
- **显示：** "目标站反应"那一格在状态码下面多一行：`页面自己跳转 域名`（橙）或 `页面疑似写了地区不可用`（红）；状态码那行的颜色也跟着变（有页面判断时不再显示绿色）。日志多一行写明地址或匹配到的那句话。
- **局限（必须知道）：** 这是从文字猜的。① **用脚本在页面加载后才画出内容的网站（大多数单页应用）读不到**，Gemini、ChatGPT 这类很可能就是，页面里看不到提示，这里就什么都不显示，这种情况**不代表没被限制**；② 短语表不可能包含每个网站的写法，没匹配到不代表没写；③ 脚本跳转只在小页面上判；④ 只读了页面前 64 KB，也没有执行脚本。所以：显示了"疑似"可以当有力的提示，什么都没显示不能当"没问题"。
- **功耗：** 没有定时器、没有轮询。每次请求多读最多 64 KB 页面，只在点"套入节点测"时发生。
- **新增文件：** `core/src/main/golang/native/ipdelay/pagesniff.go`、`pagesniff_test.go`。**改了的自有文件：** `iptry_target.go`（`TargetResult.Soft` / `SoftNote`，读页面）、`iptest.go`（`IPRow.TargetSoft` / `TargetSoftNote`）、`iptest_probe.go`（`probeNode`）、`IpTestReport.kt`、`IpTestView.kt`（`softLine`、颜色）、`ConfigCheckActivity.kt`（日志）、两份 `strings_iptest.xml`（`iptest_soft_*`、`iptest_log_soft_*`）。没有改上游文件。
- **验证：** `SniffPage` 单独放进一个临时模块跑了单测（meta 跳转、属性顺序、没有地址、脚本跳转、大页面不判、地区短语英文和中文、普通中文不误判），通过；`gofmt` 通过。`TryTarget` 的改动和 Kotlin 部分没有编译（没有 Android / mihomo 编译环境，也拉不到 Go 依赖）。

## 68. 测 IP 加"抖动"和"分段耗时"两列（r85）
直连测和套入节点测的表格，在"平均延迟"后面多两列。**不多发任何请求**，都是从原来就在做的 10 次测试 / 那一次 HEAD 里多记几个数。
- **抖动：** 10 次里成功的几次，各自离平均值差多少，再取平均（四舍五入到毫秒；`summarize` 里算，`tryStats.Jitter` → `IPRow.Jitter`）。只有 1 次成功或全失败是 0。颜色：低于 30 ms 绿、低于 100 ms 橙、其余红（只是个大致的参考，主要用来在几个 IP 之间比）。和"最大延迟"不同，一次偶然的慢不会把它拉得很高。
- **分段耗时（单元格三行）：**
  - **直连测：** `TCP`（建立 TCP 连接）、`TLS`（握手，含 ECH），都是 10 次里成功的几次的平均；`首字节`只有一个样本：保持下来的那条连接上发一次 HEAD，从发出到收到响应头（HTTP/2 的话包含建立 h2 的那一点）。做法：`handshakeWithin` 拆成 `handshakeSplit`（多返回 TCP 那一段的毫秒，`handshakeWithin` 变成它的包装），`handshakeECH` 同样有 `handshakeECHSplit`，`fetchEvidence` 有 `fetchEvidenceTimed`；老函数还在，别处调用没动。
  - **套入节点测：** `拨号`（节点的 `DialContext` 返回用了多久）、`握手`（HTTP 传输层在隧道里和目标站做 TLS 的开始到结束）、`首字节`（请求写完到收到第一个字节）。用 `net/http/httptrace` 读，在新文件 `ipdelay/iptry_phase.go`：`requestPhased`（和 `requestVia` 一样的请求，没有读正文）给 `TryIP`，`TryTarget` 里直接带上 `phaseClock`。**`requestVia` 没改**（还有 3 处别的调用）。每一段取"测到了的那几次"的平均，没测到的次不拉低平均。
  - **怎么读"拨号"和"握手"：** 从 r84 的报错（`xhttp stream-one bad status: 502` 出在请求里而不是拨号里）推断，xhttp 节点的隧道很可能是第一次读写才真正建立，那样的话"拨号"会很小，隧道建立和 Worker 去连目标站的时间都落在"握手"里。这是推断，没对照内核源码核实；不管怎样，三个数都是实际量到的，只是名字对应的是上面写的那三个时刻。目标是 `http://` 的地址没有 TLS，"握手"显示 `-`。
  - 失败的 IP 三行都是 `-`；某一段没测到显示 `-`。
- **日志：** 每个成功的 IP 多一行：`抖动 N 毫秒；分段：…`。
- **复制：** "复制"出来的表里，三行的分段耗时合成一行（用空格隔开），不会打乱每行一个 IP。
- **局限：** ① 抖动用的是 10 次的样本，次数不多；这 10 次是 5 个一批并行发的（`probeParallel`，没改，改成串行会让测试时间拉长、耗电更多），并行会让数字比单个请求时略有波动。② 直连测的"首字节"只有一次。③ 套入节点测里各段是从 HTTP 层看到的，看不到隧道内部（TCP、xhttp 请求）各自的时间。
- **功耗：** 没有定时器、没有轮询、没有新增请求；只多记几个时间点、做几次整数运算。
- **新增文件：** `core/src/main/golang/native/ipdelay/iptry_phase.go`、`core/src/main/golang/native/conftest/iptest_phase.go`（`meanOf`、`addPhases`）。**改了的自有文件：** `ipdelay/iptry.go`（`TryResult.Ph`、`TryIP`）、`ipdelay/iptry_target.go`（`TargetResult.Ph`、`TryTarget`）、`conftest/iptest.go`（`IPRow.Jitter` / `Dial` / `Shake` / `First`、`handshakeSplit`、`fetchEvidenceTimed`）、`conftest/iptest_probe.go`（`summarize`、`setStats`、`handshakeECHSplit`、`probeDirect`、`probeNode`）、`conftest/iptest_probe_test.go`（两个单测）、`IpTestReport.kt`、`IpTestView.kt`（`Key.JITTER` / `Key.PHASE`、三份列表、`phaseText`）、`ConfigCheckActivity.kt`（日志）、两份 `strings_iptest.xml`。没有改上游文件，上游文件总表不变。
- **验证：** **这次什么都没跑过**：这个环境里没有 Go（连 `gofmt` 都没有），也没有 Android / mihomo 编译环境，所以 Go 和 Kotlin 都只靠人工对照检查。两个新单测（`TestSummarizeJitter`、`TestPhaseHelpers`）是按手算的结果写的，没执行。请以 Actions 的构建和 `go test` 为准。


## 69. 测其他 IP 的对话框加"读取配置的 IP"（r86）
> **r88 起，"读什么"和"填入"两处已被 #71 取代**：读的是代理页那一套（不再读 IPv6、不再跟 hosts 别名、不再自己问解析器），"填入"是覆盖不是追加。下面写的是 r86 当时的做法，留作记录；别名 / 通配键 / 没对照源码那几条因此不再有效。
只在点按钮时读一次，读出来让你勾选，勾上的填进地址输入框，之后的测试（直连测、套入节点测）照旧，没有新的测试逻辑。
- **入口：** "测其他 IP"对话框里输入框下面多一个按钮"读取配置的 IP"。点了以后内核读一遍当前配置，弹出一个勾选列表（默认全勾），每个地址下面写"节点：哪几个节点用它 · 来源"，来源是 `server 直接写的这个 IP` / `hosts：键` / `hosts 别名：键` / `由 域名 解析得到`。点"填入"把勾上的追加到输入框（已在输入框里的不重复加）。超过 20 个时，开始测试时仍按原来的上限截断，并显示"超过 20 个的上限，还有 N 个没测"。
- **读什么：** `proxies:` 里每个有 `server` 的节点：
  - server 是 IP（`1.2.3.4`、`2606::1`、`[2606::1]` 都认）：就是它；带 zone id 的链路本地地址测不了，列在"没读到"里。
  - server 是名字，`hosts:` 里有（精确键，或通配键 `+.a.com` / `*.a.com` / `.a.com`，精确键优先，通配里取标签最多的）：取那个条目的全部地址；条目里写成单个字符串或列表都行；值如果又是一个名字（别名）就接着往下找，最多 3 层，绕圈或太深报"hosts 别名太深或绕成了圈"；既不是地址也不是名字的值直接丢掉。
  - server 是名字，`hosts:` 里没有：问内核自己的解析器（`askCore`，和检查 DNS 页同一个函数，A 和 AAAA 各一个问题）。每个域名最多取 8 个（IPv4 在前、从小到大排序），多出来的数在列表上方说明；解析失败、有回应但没地址、只回了 198.18.0.0/15（默认 fake-ip 段）的地址、超时，分别写在列表上方，不当成空结果。
  - 节点写了 `ip-version: ipv4` / `ipv6` 时，另一种地址不列（数量在说明里写）。
  - 不属于任何节点的 `hosts:` 条目（如 `dns:` 里 DoH 域名的地址）不读；provider 里的节点不在 `proxies:` 里，也读不到。
  - 同一个地址被几个节点用，合成一行，列出所有节点；这一行只有在用它的节点全有 `dialer-proxy`（链式）时才标"链式节点，直连测可能连不上"。
  - 套入节点测用的是所选节点的**自己的端口和 sni**（`newVariant` 只换 server），所以这一点没改；直连测的端口仍是对话框里"端口"那一格，没有每节点端口。
- **功耗：** 没有定时器、没有轮询、不监听配置变化。读配置是内存里读一遍；只有解析域名才发请求，每个不同的域名一次（A 和 AAAA 各一个问题），同一个域名几个节点共用只问一次，同时最多问 4 个，整个读取最多 10 秒。关掉对话框会取消等待（内核那一边最多再跑到各自超时）。
- **新增文件：** `core/src/main/golang/native/conftest/iptest_config.go`、`iptest_config_test.go`、`core/src/main/golang/native/ipdelay/rawhosts.go`（`RawHosts`、`ToStrings`，只读）。**改了的自有文件：** `conftest/iptest.go`（`IPReport` 多 `Config` / `ConfigFail` / `ConfigOmitted` / `ConfigFamily`，`RunIP` 多一个 `config` 模式）、`IpTestReport.kt`（`IpTestConfigIp`、`IpTestConfigFail`、四个字段）、`IpTestDialog.kt`（按钮和勾选框）、`ConfigCheckDesign.kt`（`askIpTest` 多一个参数）、`ConfigCheckActivity.kt`（传入读取函数）、两份 `strings_iptest.xml`（`iptest_cfg_*`）。没有改上游文件，上游文件总表不变。
- **没对照内核源码的地方（必须知道）：** 内核源码不在这份仓库里（子模块没拉下来），所以下面两点是按我对 mihomo `hosts` 文档的记忆写的，没对照源码：① 通配键的匹配规则（`+.` = 域名本身和所有子域，`*.` = 只下面一层，`.` = 所有子域不含自己）和"精确优先、通配取标签最多"；② `hosts` 的值可以是一个名字（别名）。如果你的内核表现不一样，把 mihomo 源码地址发我，我对照着改。另外 fake-ip 段只认默认的 `198.18.0.0/15`，你自己改了 `fake-ip-range` 的话，解析器回的假地址不会被识别。解析器在 fake-ip 模式下回真实地址，是按"和控制接口 `/dns/query` 同一个调用"理解的，没核实。
- **局限：** 解析结果只是这一刻的快照，Cloudflare 这类域名每次、每个 DNS 返回的可能不同；只问内核解析器一次，不合并每个 DNS 服务器的结果。
- **验证：** 这次什么都没跑过：环境里没有 Go（`gofmt` 和 `go vet` 都没跑），也没有 Android 编译环境，Go 和 Kotlin 都只靠人工对照检查。`iptest_config_test.go` 里的三个单测是按手算的结果写的，没执行（里面一个 map 的对齐可能不是 gofmt 的样子）。请以 Actions 的构建和 `go test` 为准。

## 70. 编辑页：历史版本标出打开的那个、长按固定（固定的随备份上传）、顶栏撤销重做和底栏加粗加大（r87）
**1. 历史版本列表里标出"打开的那个"。** `EditorActivity.openedAt` 记着最近一次从列表载入到页面里的版本（时间）；列表里那一行整行蓝底（白天 `#D6E4F7`，夜间 `#26456E`），文字后面加"当前打开"；载入之后页面文字又改过的话写"当前打开（之后改过）"（打开列表时把页面文字和那个版本比一次）。关掉页面就忘了（没有存盘）。
**2. 长按固定 / 取消固定。** 列表是新的 `EditorDesign.pickVersion`（点一行 = 载入，和以前一样；长按一行 = 固定或取消固定，列表不关，行首出现 📌，弹一句"已固定 / 已取消固定"）。列表上方一行灰字说明。
- **存法：** `ConfigHistory` 里版本旁边多一种空标记文件 `<毫秒>.pin`（和 `.ok` / `.saved` / `.bad` 一样）。固定的版本**不计进 10 个的上限、不会被挤掉**（`prune` 跳过），但最多固定 20 个（`MAX_PINNED`；满了提示"最多固定 20 个，请先取消一个"）。取消固定后它变回普通版本，下次保存时才按上限清理（不在取消的当下删，免得屏幕上这一行失效）。
- **进备份：** 只有 File 类型的配置有历史，所以只有它们带。`BackupManager.write` 在 `backup.json` 里给每个 File 配置多一个 `"pinned": [毫秒, ...]`，zip 里多 `history/<uuid>/<毫秒>.yaml`。云备份、导出到文件用的是同一个 `write`，所以都带。**不会自动上传：** 固定之后要点一次"备份到坚果云"（或导出）才会进云端。
- **恢复（不合并，云端覆盖）：** `BackupManager.restore` 在配置都恢复完之后，对备份里有 `pinned` 键的、恢复成功（或本机已有同一份而跳过）的 File 配置：本机原来固定的全部取消固定（变成普通版本，上限 10 个重新生效），再把备份里的固定版本写进去并固定（本机已有同内容的版本就直接把那个固定，不重复存）。备份里 `pinned` 是空的就是"本机全取消固定"。**旧备份（没有 `pinned` 键）不动本机的固定。** 注意 #56 的老规则：恢复时"替换同名文件配置"走 `delete`，会清掉那个配置的历史，所以那种情况下本机的普通历史版本没了，只剩备份里固定的。
**3. 顶栏撤销 / 重做：** 原来是文字符号 `↶ ↷`（线很细、用浅蓝色）。改成两个实心箭头图标（Material 的 undo / redo 形状，28dp，新文件 `ic_editor_undo.xml`、`ic_editor_redo.xml`），颜色用正文颜色（白天近黑、夜间近白）；不能用时仍是半透明，透明度从 0.35 提到 0.4（不够灰的话再调）。
**4. 底栏：**
- 符号行（`⇥ ⇤ - : # "" '' [] {} , | & *`）：字号 16 → 19 sp，加粗（粗体 + 假粗体描边），按钮高 34 → 40 dp，整行高 34 → 42 dp。
- 最下面一行：状态文字（行列、问题）和字号数字 12 → 14 sp，颜色从灰改成正文色，字号数字加粗；右边的 ⚠ 问题数、◀ ▶ 窗口键、A− A+ 字号 15 → 17 sp，加粗，按钮最小高 32 → 36 dp、宽 36 → 40 dp。
- 没动的：顶栏的文字按钮（查找、检查、存历史、应用）、查找栏。
**功耗：** 全靠点击：长按一次只建 / 删一个空文件；备份多读写几个固定版本（每个 ≤ 2 MB，最多 20 个）；没有定时器、没有轮询。
**新增文件：** `design/src/main/res/drawable/ic_editor_undo.xml`、`ic_editor_redo.xml`。**改了的自有文件：** `ConfigHistory.kt`（`pinned`、`setPinned`、`replacePinned`、`MAX_PINNED`、`prune`）、`BackupManager.kt`（`write`、`restore`）、`EditorDesign.kt`（`pickVersion`、`iconAction`、底栏尺寸）、`EditorActivity.kt`（`history()`、`openedAt`）、两份 `strings_editor.xml`（`editor_pick_hint`、`history_opened`、`history_opened_changed`、`editor_pinned`、`editor_unpinned`、`editor_pin_limit`）。没有改上游文件，上游文件总表不变。
**局限：** 这次什么都没编译、没跑：环境里没有 Android 构建环境，Kotlin 只靠人工对照检查，请以 Actions 的构建为准。蓝底、图标大小、字号和按钮高度只是按数值选的，实际好不好看要装上看，不合适告诉我具体哪个再调。列表是系统 `ListView`，行文字颜色沿用系统的，夜间蓝底上能不能看清要看实际。从云端恢复时固定版本的时间沿用备份里的时间（不同手机的时钟不影响排序以外的东西）。

## 71. 读取配置的 IP 改用代理页的逻辑、"填入"覆盖输入框、历史版本点选后关闭、编辑页三个点放大（r88）
四处都是你点按钮才发生，没有新增定时器 / 轮询 / 监听。
- **读取配置的 IP = 代理页卡片上那份 IP（`conftest/iptest_config.go` 重写）：** 先 `ipdelay.PrepareDisplay(节点名们)`（和打开代理页时调的是同一个函数：没有 `hosts:` 条目的域名解析一次，结果缓存在内存，同一次加载配置内不再问，最多等 3 秒），再对每个节点调 `ipdelay.GetForDisplay(节点名)`。所以：只列 IPv4；每个节点最多 8 个，在用的排最前；server 是 IP 就是这一个；server 是域名且 `hosts:` 里有（**只认精确键**，和代理页一样）就是 hosts 里的 IPv4，按你写的顺序；否则是解析一次的结果。`ip-version: ipv6` 的节点、server 是 IPv6 地址的节点、provider 里的节点不列（代理页也不显示）。测过速的节点按代理页的规则显示测过的那些地址。弹窗里的地址和代理页一致。
- **合并：** 同一个地址被几个节点用，合成一行，列出所有节点；在用的地址排最前，并在那一行写"在用"（`ConfigIP.InUse`、`IpTestConfigIp.inUse`、`iptest_cfg_in_use`）；只有用它的节点全有 `dialer-proxy` 才标"链式"。来源只有三种：`server` / `hosts` / `dns`（不再有 `alias`）。一个域名没得到 IPv4（解析失败，或 hosts 里没有 IPv4）列在"没读到"里，原因写"解析失败"，hosts 的情况带说明"hosts 里没有 IPv4"。
- **去掉的东西：** 自己的 hosts 通配键匹配、别名展开、fake-ip 过滤、`askCore` 的 A+AAAA 查询、`ConfigRead.Omitted` / `Family`（字段还在、恒为 0，Kotlin 那边不用改）。因此 #69 里"没对照内核源码"那两点（通配键规则、值可以是名字）不再存在；这个功能现在不依赖内核行为，只调用 `ipdelay` 的两个导出函数。
- **"填入"改成覆盖（`IpTestDialog.kt`）：** 勾上的地址替换输入框原来的内容（每行一个），不再追加；一个都没勾就点"填入"，输入框保持不变。
- **历史版本浮窗（`EditorDesign.pickVersion`）：** 点某一行以前只把序号交出去，对话框要等调用方处理，结果浮窗一直留着；现在点行后立刻关闭（序号照常交出去，取消 / 点外面仍然返回 -1）。长按固定 / 取消固定不关。
- **编辑页右上角三个点：** 原来是文字 "⋮"（小、细）。改成一个实心矢量图标 `ic_editor_more.xml`（三个大圆点，28dp，和撤销 / 重做同一个 `iconAction`，点击区域同它们），描述文字"更多"（`editor_more`）。（r89：28dp 太大，改成 24dp、圆点半径 2.2，见 #72。）
- **新增文件：** `design/src/main/res/drawable/ic_editor_more.xml`。**改了的自有文件：** `conftest/iptest_config.go`（重写）、`iptest_config_test.go`（重写，只测合并）、`conftest/iptest.go`（调用不再传 context）、`IpTestReport.kt`（`IpTestConfigIp` 多 `inUse`）、`IpTestDialog.kt`、`EditorDesign.kt`、`strings_iptest.xml`（中英）、`strings_editor.xml`（中英）。没有改上游文件。
- **验证：** `iptest_config.go` 里和内核无关的合并部分，用一个只有四个函数签名的 `ipdelay` 桩包单独跑过 `go vet` 和 `go test`，通过；整个 Go 包和 Kotlin 没法在这里编译，只能靠 Actions 的 Build Debug。

## 72. 测 IP：落地、归属运营商、归属地全部实时查，不再用任何记忆；三个点缩小（r89）
只改配置检查页的测 IP（直连测、套入节点测）和编辑页的三个点；代理页的国旗缓存没动。
- **落地（`ipdelay.ExitCountry`）：** 以前先看代理页以前测出来的国家（`CountryOf`），有就直接用，所以可能是旧的。现在每次都经过节点问 `api.country.is`（不行再 `ipinfo.io`）。签名多一个 `isDomain`（域名行用换了名字的副本，和 `TryIP` 一样）。日志那行末尾写"实时查的"。
- **每个 IP 只问一次落地：** 以前 `TryIP` 每次成功的测试（每个 IP 10 次，最多 5 个同时）在没有缓存时都问一次落地，等于每个 IP 最多 10 个请求。现在 `TryIP` 不再问落地（`TryResult.Country` 字段留着，恒为空），测完 10 次以后，只要有通的，对这个 IP 问一次（`iptest_probe.go` 的 `nodeone`、`iptest.go` 的旧单次路径）。填了目标网址时仍然是非域名的 IP 问一次，和以前一样。
- **归属运营商 / 归属地（`lookupOwner`）：** 去掉 `asnCache` / `geoCache`，每次都问 `ipinfo.io`（不全再问 `ipwho.is`）；"直连握手那一行把以前问到的运营商从内存里再显示一遍"这一段也去掉了，所以握手那一刻运营商是空的，要等 `asn` 模式（点一下）或套入节点测、直连测自己的那一次查询回来才有。`lookupOwner` 现在转到变量 `ownerSource`（默认 `askOwner`），只是为了让单元测试不用联网。
- **没改成实时的（要知道）：** ① "对照 / 比对照快"：来自代理页上一次测速的结果（`ipdelay.Get`），本来就是"上一次"，页面上也写了多久前；要实时就得每次先把 hosts 里的 IP 全测一遍，请求多很多，没做。② ECH 配置的 2 分钟内存（`echFor`）：只是让同一轮里的几个地址不重复向 DoH 要同一份配置；"ECH 测试"自己和直连测一轮开始时都是现取。③ Cloudflare 机房列表（城市、国旗）：参考表，不是每个 IP 的参数。
- **功耗：** 落地的请求数反而比以前少（每个 IP 一次，以前没缓存时最多十次）；归属运营商每次一次（以前第二次起走内存）。仍然只在点按钮时发生，没有定时器、轮询。
- **三个点：** `ic_editor_more.xml` 24dp，圆点半径 2.2（r88 是 28dp、2.8，太大；最早的文字 "⋮" 太小）。
- **改了的文件：** `ipdelay/iptry.go`、`conftest/iptest.go`、`conftest/iptest_probe.go`、`conftest/iptest_probe_test.go`（测试里改用 `ownerSource`）、两份 `strings_iptest.xml`、`ic_editor_more.xml`、`CUSTOM.md`。没有改上游文件。
- **局限：** 没编译（这里没有 mihomo 源码，Go 包编不了），只做了 `gofmt`；要靠 Actions 的 Build Debug。

## 73. 目标站反应的状态码后面加几个字的说明（r90）
只加显示用的文字，不改任何测试、判断、颜色逻辑。
- **做法：** 新文件 `design/.../custom/TargetStatusText.kt`（`TargetStatusText.of(context, code)`，纯查表）：常见状态码（200 204 301 302 303 304 307 308 400 401 403 404 405 407 429 451 500 502 503 504，以及 Cloudflare 的 520–526）各有一句短话，其他码按类别给（2xx 成功、3xx 跳转、4xx 请求被拒或有误、5xx 服务器出错）；不是 HTTP 状态码（0，没有回应）返回空。
- **显示：** "目标站反应"那一格在状态码后面接上说明，如 `403 被拒绝`、`302 临时跳转 → www.google.com`；颜色、第二行（页面自己跳转 / 疑似地区不可用）都没动。复制表格时这一格也带说明。日志那行改成 `HTTP 403（被拒绝）`。
- **字符串：** 新文件 `values/strings_iptest_status.xml`（英文）和 `values-zh/strings_iptest_status.xml`（中文）；其他语言和 #43 起的其他字符串一样回落到英文。`iptest_log_target` 两份多了第 5 个参数 `%5$s`。
- **改了的文件：** 新增 `TargetStatusText.kt`、两份 `strings_iptest_status.xml`；改 `IpTestView.kt`（`targetText` 里加两行）、`ConfigCheckActivity.kt`（日志调用多传一个参数并加 import）、两份 `strings_iptest.xml`（只改 `iptest_log_target` 一行）、`CUSTOM.md`。没有改上游文件，总表不变。
- **功耗：** 只是显示时查一次固定表，没有请求、定时器、轮询，没有额外内存。
- **局限：** 没编译（这里没有 Android 编译环境）；说明是按标准 HTTP 含义写的，网站可以乱用状态码（比如谷歌的拦截页返回 429，含义就是"请求太多"，不一定是你的 IP 被判地区）。


## 74. 编辑器页面与原版主题控件统一（r91）
**目标：** 减少内置编辑器与 App 其他页面之间的视觉割裂，只统一通用页面外观，不改变编辑器的功能设计。
- **页面背景 / 表面：** 编辑器页面背景使用 App 的 `android:windowBackground`；顶栏使用原版 `ActivityBarLayout`；查找栏、符号栏、状态栏及行号槽使用 Material `colorSurface`。浅色 / 深色模式跟随 App 主题。
- **顶栏：** 高度使用原版 `toolbar_height`（56dp），标题使用原版工具栏标题的 20sp / medium 字重。系统栏内边距仍由原有的 #53 Insets 逻辑负责。撤销 / 重做图标、三个点图标、文字操作按钮的尺寸、触摸区域和行为不改；#70 明确要求保留的顶栏文字按钮样式不改。
- **文字层级：** 标题、状态文字和普通正文使用 App 的 `textColorPrimary`；辅助文字使用 `textColorSecondary`。YAML 正文、语法高亮、诊断、差异标记等语义色不改。
- **输入框：** 查找 / 替换框改用与原版搜索页相同的 `AppCompatEditText`；跳转行号弹窗改用原版 `dialog_text_field.xml` 所采用的 Material outlined 输入框样式和对话框内边距。
- **弹窗 / 菜单：** 对话框继续使用原有 `MaterialAlertDialogBuilder`；历史版本列表行改为 App 正文色、16sp、48dp 最小行高；更多菜单改用 AppCompat `PopupMenu`；补全弹窗背景跟随 Material `colorSurface`，文字跟随 App 正文色。
- **保留的既有约定：** #46–#73 的编辑器功能、功耗策略、语法高亮 / 问题颜色、差异标记颜色、字号范围、符号栏 19sp / 42dp、状态栏 14sp、底栏按钮 17sp / 36dp、历史版本当前行颜色、三个点 24dp 等均不改变。
- **功耗：** 只改主题取色和控件外观；没有新增定时器、轮询、网络请求或后台任务。
- **改动文件：** `design/src/main/java/com/github/kr328/clash/design/custom/editor/EditorDesign.kt`、`design/src/main/java/com/github/kr328/clash/design/custom/editor/CodeEditText.kt`、`CUSTOM.md`。没有修改上游文件，不改上游文件总表。
- **验证：** 仅完成源码级检查；未运行 Android 编译、未在手机上验证。需由用户手动触发 Build Debug 后确认主题取色、工具栏高度、输入框和弹窗在浅色 / 深色模式下的实际效果。


## 75. IP 测速页面视觉统一（r92）
**目标：** 让配置检查页中的 IP 测速表与 App 原版页面使用同一套主题文字和表面颜色，减少独立自绘界面的视觉割裂；只改显示层。
- **文字层级：** 普通文字、IP 地址、日志正文和页面说明标题使用 App 的 `textColorPrimary`；辅助说明、表头文字、失败详情等沿用 `textColorSecondary`。警告、成功、失败、延迟、丢包、目标站反应和链接等语义颜色保持原值，不改变颜色判断。
- **表格表面：** 表头和实时日志区域改用 Material `colorSurface`；交替行底色改用主题 `colorControlHighlight` 的低透明度，不再固定叠加灰色。浅色 / 深色模式由 App 主题提供颜色。
- **操作按钮：** 行内“测速 / 取消”改用 Material outlined button，保留原有点击回调、可用状态、下载 / 取消行为；收紧最小尺寸和内边距以适配原有窄列。
- **保留：** IP 固定列、横向滚动、列宽算法、表头高度、行高、单元格对齐、表格排序、复制内容、端口详情、测速日志、每 IP 10 次 / 最多 5 并发、下载上限、所有测试逻辑与网络请求均不改；#52–#73 已定义的列顺序、机房/国家信息、抖动/分段耗时、状态码说明及颜色语义不变。
- **功耗：** 只改取色和控件外观，没有新增定时器、轮询、网络请求或后台任务。
- **改动文件：** 只有 `design/src/main/java/com/github/kr328/clash/design/custom/IpTestView.kt` 和本登记文件；没有修改上游文件，上游文件总表不变。
- **验证：** 完成源码级修改和差异检查；没有运行 Android 编译，也没有真机截图验证。需手动触发 Build Debug 后检查浅色 / 深色主题、表头和日志表面、窄屏“测速”按钮显示。


## 76. 问题列表与错误诊断视觉统一（r93）
**目标：** 统一错误与诊断页顶部“配置问题”动态列表的文字层级、间距和分隔线；错误报告正文及所有诊断数据仍沿用原来的显示逻辑。
- **文字：** 问题标题使用 App 主题 `textColorPrimary`；详情 / 原因 / 发生时间使用 `textColorSecondary`；分组标题保留原有警告语义色，只加强字重，不改变错误分类或等级颜色。
- **列表：** 问题行之间增加主题高亮色分隔线，左右缩进与列表内容对齐；分组标题和列表项保持原有排列顺序。
- **按钮：** 保留现有 Material 忽略 / 取消忽略按钮样式、文案和点击回调，不改变状态切换。
- **保留：** 活跃 / 已忽略 / 已恢复三组分类、标题、详情、次数、时间、忽略 / 取消忽略、复制全部内容、清除记录、刷新和错误报告正文均不改。
- **功耗：** 只调整视图取色和列表分隔线，没有增加监听、轮询、定时器或后台任务。
- **改动文件：** 只有 `design/src/main/java/com/github/kr328/clash/design/custom/IssuesBlock.kt` 和本登记文件；没有修改上游文件，上游文件总表不变。
- **验证：** 源码级检查；未运行 Android 编译或真机验证。


## 77. 本地与云端备份对话框视觉统一（r94）
**目标：** 统一本地备份 / 恢复、云端备份设置及云端恢复确认所共用的动态对话框控件；不改变备份数据、传输、加密和恢复策略。
- **输入框：** 坚果云账号、第三方应用密码、备份密码改用 Material `TextInputLayout` + outlined `TextInputEditText`，标签放在输入框容器上；保留原有明文显示、单行、邮箱 / 可见密码输入类型和字段顺序。
- **勾选项：** 云端恢复的“同时替换本机设置”改用 Material `MaterialCheckBox`；默认不勾选，确认结果仍返回原来的布尔值。
- **间距：** 输入框之间增加统一的 8dp 间隔；原有对话框内边距、滚动容器和 MaterialAlertDialogBuilder 不变。
- **保留：** 本地导出 / 导入、云端推送 / 恢复 / 删除 / 撤销、密码校验、恢复前快照、订阅合并策略、设置勾选语义、确认 / 取消返回值、成功失败提示均不改。
- **透明窗口：** 不修改 `BackupTranslucentTheme`、`BackupActivity` 的透明窗口背景设置或无动画行为；文件选择器前后的页面显示方式保持不变。
- **状态提示：** 保留原有居中的深色状态提示条。它覆盖在仍可见的设置页上，是短暂进度提示，不作为普通页面背景处理。
- **功耗：** 只替换对话框控件，没有新增网络请求、定时器、轮询或后台任务。
- **改动文件：** 只有 `design/src/main/java/com/github/kr328/clash/design/custom/BackupDesign.kt` 和本登记文件；没有修改上游文件，上游文件总表不变。
- **验证：** 源码级检查；未运行 Android 编译或真机验证。


## 78. 连接列表页视觉统一（r95）
**目标：** 让连接列表页的搜索、操作区、统计信息和连接条目跟随 App 原版 Material 主题与文字层级；只调整界面，不改变连接查询和关闭行为。
- **搜索框：** 原生裸 `EditText` 改为 Material Components Dense Outlined `TextInputLayout` + `TextInputEditText`，标签仍使用原有搜索提示；保留原来的输入类型、单行、自动查询和搜索匹配规则。
- **操作区：** 搜索框独占一行，排序与“全部关闭”放在下一行并靠右，避免窄屏时搜索框被两个按钮挤压；排序继续使用 Material TextButton；“全部关闭”使用主题 `colorError` 表示危险操作。按钮点击事件和确认流程不变。
- **统计信息：** 摘要文字从强调色改为 App 次要文字色，不改变统计内容和计算。
- **连接条目：** 目标地址使用正文色；节点、网络 / 规则 / 流量 / 应用 / 时间使用次要文字色；条目底部增加主题高亮色细分隔线。保留原有点击涟漪、三行内容、单行截断和按连接 ID 的差异更新。
- **保留：** 顶栏 `ActivityBarLayout`、刷新和 IP 历史图标、搜索 / 排序 / 全部关闭逻辑、300 条上限、详情弹窗、单条关闭、全部关闭、刷新时机及前后台功耗策略均不改。
- **功耗：** 仅替换输入控件、调整布局与取色；不增加监听、定时器、轮询、网络请求或后台任务。
- **改动文件：** 只有 `design/src/main/res/layout/design_connections.xml`、`design/src/main/res/layout/adapter_connection.xml` 和本登记文件；没有修改上游文件，上游文件总表不变。
- **验证：** 源码级检查；未运行 Android 编译或真机验证。需手动触发 Build Debug 后检查浅色 / 深色主题、窄屏搜索框与按钮排列、危险操作文字颜色和连接行分隔线。


## 79. 修复备份表单读取输入内容的 Kotlin 编译错误（r96）
- **原因：** Build Debug 报 `BackupDesign.kt` 中 `Unresolved reference 'text'`；表单的 `inputs` 是 `TextInputLayout` 列表，不能直接读取 `it.text`。
- **修复：** 将 `inputs.map { it.text.toString() }` 改为 `inputs.map { it.editText?.text?.toString().orEmpty() }`，从每个 `TextInputLayout` 内部的 `TextInputEditText` 读取文本。取消 / 确认返回语义、字段顺序和备份恢复逻辑不变。
- **实际代码提交：** `e12ada26e828c8c2b8352557e4efe8aab7ba0912`（原提交漏记本文件，且提交说明未遵循仓库格式；本条在后续文档提交中补登记）。
- **改动文件：** `design/src/main/java/com/github/kr328/clash/design/custom/BackupDesign.kt`；本条为补记，不在本次重复修改该代码。没有修改上游文件。
- **功耗 / 网络：** 无新增请求、定时器、轮询、监听或后台任务。
- **验证：** 按 Build Debug 报错定位并做源码修复；修复后未重新运行 Android 构建，需由用户手动触发 Build Debug 验证。

## 80. 测 IP 行内按钮强调色跟随 App 主题（r97）
**原因：** #75 已将 IP 测速页的正文 / 辅助文字层级、表面颜色、交替行底色和 Material outlined 按钮统一到 App 主题，但行内“测速 / 取消”按钮的文字与描边仍使用固定的浅蓝色（浅色 `#1565C0`、深色 `#90CAF9`），没有跟随 App 的主题主色。
**修复：** `IpTestView.kt` 的 `colorLink` 改为读取 Material `colorPrimary`，仅在主题属性不可用时保留原来的浅 / 深色后备值。按钮仍使用原来的 Material outlined 样式、尺寸、文案、启用状态和点击回调。
**按 #75 复核并保留：** 普通文字 / IP / 日志 / 页面说明已使用 `textColorPrimary`，辅助说明 / 表头已使用 `textColorSecondary`；表头和日志已使用 `colorSurface`，交替行已使用低透明度 `colorControlHighlight`。这些部分无需重复改动。各类字号和单元格尺寸继续遵守 #54、#55、#62、#68 的表格布局约定，不为了套用通用文字样式而改变列宽、换行或行高。
**保留：** #52–#73 的表格列、数据、排序、复制、端口详情、测速与取消逻辑、网络请求、日志、布局算法及语义颜色均不变。
**功耗 / 网络：** 只增加一次主题属性取色，没有新增定时器、轮询、网络请求、监听或后台任务。
**改动文件：** 只有 `design/src/main/java/com/github/kr328/clash/design/custom/IpTestView.kt` 和本登记文件；没有修改上游文件，上游文件总表不变。
**验证：** 完成源码差异检查；未运行 Android 编译、未做真机浅色 / 深色截图。按仓库约定由用户手动触发 Build Debug 验证；不会自动触发构建。

## 81. 补齐编辑页主题控件适配（r98）
**依据：** 按 #74 的编辑器视觉统一约定复核实际代码。页面背景、顶栏、文字层级、查找 / 替换输入框、历史版本行、更多菜单和补全弹窗表面已按 #74 处理；仍有两处没有完整跟随原版主题。
- **跳转行号输入框：** `EditorDesign.askLine()` 的 `TextInputLayout` 使用 Material `textInputStyle` 主题属性，并显式设置 `BOX_BACKGROUND_OUTLINE`，与原版 `dialog_text_field.xml` 的 outlined 外观保持一致；保留原有对话框边距、数字输入、提示、确认和取消行为。
- **补全弹窗边框：** `CodeEditText` 的补全 `PopupWindow` 边框不再使用固定浅 / 深灰色，改为 App `textColorSecondary` 的低透明度颜色；背景仍为 Material `colorSurface`，圆角、阴影、尺寸、候选文字、选择行为和弹出位置不变。
- **保留：** #46–#74 的编辑功能、语法高亮、诊断 / 差异语义色、字号范围、工具栏与底栏尺寸、符号栏、状态栏、历史版本和补全逻辑均不改。
- **功耗 / 网络：** 仅调整输入框样式与边框取色，没有新增定时器、轮询、网络请求、监听或后台任务。
- **改动文件：** `design/src/main/java/com/github/kr328/clash/design/custom/editor/EditorDesign.kt`、`design/src/main/java/com/github/kr328/clash/design/custom/editor/CodeEditText.kt`、`CUSTOM.md`。没有修改上游文件，上游文件总表不变。
- **验证：** 完成源码差异检查；未运行 Android 编译、未做真机浅色 / 深色验证。按仓库约定由用户手动触发 Build Debug；不会自动触发构建。
## 82. 修复编辑页跳转行号输入框的 Kotlin 构造参数错误（r99）
- **原因：** Build Debug #102 在 `EditorDesign.kt:893` 报 `TextInputLayout` 构造函数无匹配重载。上次误将 XML 样式资源作为第四个构造参数传入；该组件在当前 Material 依赖中仅提供最多三个参数的构造函数，后续 `apply` 接收者错误是连带报错。
- **修复：** 改为合法的三参数构造函数 `TextInputLayout(context, null, com.google.android.material.R.attr.textInputStyle)`，并保留 `setBoxBackgroundMode(BOX_BACKGROUND_OUTLINE)`，确保使用主题输入框样式且保持 outlined 外观。未改变提示、数字输入、对话框布局和确认 / 取消行为。
- **改动文件：** `design/src/main/java/com/github/kr328/clash/design/custom/editor/EditorDesign.kt`、`CUSTOM.md`。没有修改上游文件。
- **验证：** 对照 #102 编译日志确认根因并检查构造函数签名；本次仅做源码检查，尚未重新编译或真机验证。需用户手动触发 Build Debug；不会自动触发构建。


## 83. 测 IP“读取配置的 IP”按钮统一为 Material 主题样式（r100）
- **原因：** 对照 #75 / #80 检查时发现，“测速 / 取消”行内按钮已使用 Material outlined 样式并跟随主题，但“读取配置的 IP”仍直接创建 android.widget.Button，没有明确使用 Material Components 按钮样式。
- **修复：** 将该按钮改为 MaterialButton(context)，由 App 当前 Material 主题提供按钮主色、文字对比色和形状；保留原有文案、全宽布局、启用 / 禁用状态、读取中提示、点击回调和读取逻辑。不额外硬编码浅色 / 深色颜色值。
- **保留：** 只改按钮控件类型，不改变配置 IP 的读取范围、解析、勾选、填入、网络请求或对话框其他控件。
- **功耗 / 网络：** 纯 UI 控件替换，没有新增请求、定时器、轮询、监听或后台任务。
- **改动文件：** design/src/main/java/com/github/kr328/clash/design/custom/IpTestDialog.kt 和本登记文件；没有修改上游文件，上游文件总表不变。
- **验证：** 完成源码差异检查；未运行 Android 编译、未做真机浅色 / 深色验证。需用户手动触发 Build Debug；不会自动触发构建。


## 84. 收紧配置卡片铅笔图标两侧间距（r101）
- **原因：** 复核 #47 / #55 的配置卡片布局时发现，铅笔容器额外设置了 24 dp 左边距，导致时间与铅笔之间出现过大的空白；该间距不利于时间、铅笔和右侧 ⋮ 三个元素形成紧凑且均衡的排列。
- **修复：** `adapter_profile.xml` 中 `edit_view` 的 `layout_marginStart` 从 24 dp 调整为 0 dp。时间 TextView 原有 6 dp 右边距、铅笔图标自身 8 dp 内边距、右侧菜单的尺寸 / 内边距均保留，让铅笔与两侧可见图标的间距接近。
- **保留：** 不改变卡片尺寸、时间文字、编辑 / 更多菜单点击区域、图标尺寸、配置编辑入口或其他配置卡片行为；不改 `ProfileAdapter` 和点击回调。
- **功耗 / 网络：** 仅调整静态 XML 布局间距，没有新增监听、定时器、轮询、网络请求或后台任务。
- **改动文件：** `design/src/main/res/layout/adapter_profile.xml` 和 `CUSTOM.md`；没有新增或修改上游文件总表中的挂钩。
- **验证：** 完成源码差异检查；未运行 Android 编译、未做真机截图验证。需用户手动触发 Build Debug 后检查实际视觉间距；不会自动触发构建。

## 85. 测 IP 实时状态标题显示总数和已测数量（r102）
- **原因：** 测 IP 页面下方的“实时状态”只有标题和日志，测试过程中不方便直接判断本轮共有多少个 IP、已经完成多少个。
- **修复：** 在“实时状态”标题右侧显示“共 N 个 IP，已测 M 个”。总数取内核解析后的本轮实际测试列表（遵守去重、直连 / 套入节点规则及 20 个上限）；已测数在每个 IP 探测返回结果后递增，失败但已返回的探测也计入，取消时尚未完成的当前探测不计入。测试开始显示 0，进度随每个 IP 完成即时更新。
- **同步：** 复制测试结果时，实时状态标题也包含当时的总数和已测数量；英文资源同步增加对应文案。
- **保留：** 不改变 IP 解析、去重、测试顺序、并发 / 间隔、测速逻辑、日志内容及表格；不增加额外网络请求、定时器、轮询或后台任务。
- **改动文件：** design/src/main/java/com/github/kr328/clash/design/custom/IpTestView.kt、design/src/main/java/com/github/kr328/clash/design/custom/ConfigCheckDesign.kt、app/src/main/java/com/github/kr328/clash/custom/ConfigCheckActivity.kt、design/src/main/res/values-zh/strings_iptest.xml、design/src/main/res/values/strings_iptest.xml 和本登记文件；没有修改上游文件。
- **验证：** 完成源码差异检查；未运行 Android 编译、未做真机 UI 验证。需用户手动触发 Build Debug 检查标题布局和进度更新；不会自动触发构建。


## 86. 将测 IP 进度计数紧跟“实时状态”标题显示（r103）
- **原因：** #85 初版将标题与计数拆成横向两个控件，并让标题占据剩余空间，导致计数显示在整行最右侧；这与“在实时状态旁边显示”的要求不符。
- **修复：** 移除独立的进度 TextView 和横向占位布局，将“共 N 个 IP，已测 M 个”作为“实时状态”标题的紧邻后缀显示；标题保持原有粗体与正文色，计数使用次要文字色。窄屏时按同一段标题文本自然布局，不再将计数推到行尾。
- **保留：** 只调整标题与计数的组合及布局；总数来源、已测递增时机、取消计数规则、复制文本内容和测试逻辑均保持 #85 不变。
- **改动文件：** design/src/main/java/com/github/kr328/clash/design/custom/IpTestView.kt 和本登记文件；没有修改上游文件。
- **验证：** 完成源码差异检查；未运行 Android 编译、未做真机 UI 验证。需用户手动触发 Build Debug 检查标题紧邻显示及窄屏换行；不会自动触发构建。


## 87. 保留最近一轮 IP 测试结果（r104）
- **目标：** 离开“配置检查”页面后再次进入，仍显示最近一轮 IP 测试结果；App 进程重启后也可恢复。只保留最近一轮，不累积历史。
- **保存：** 使用配置检查页现有的 SharedPreferences 固定键 `ip_test_last`，以单份 JSON 覆盖保存测试模式、目标站模式、域名 / 节点 / 端口 / 下载地址、完整 IP 行数据、机房名称及国家映射、进度和最多 200 行实时日志。每次结果、进度或日志变化时更新同一键。
- **替换：** 新一轮 IP 测试开始时立即用空结果覆盖上一轮；不创建按轮次命名的文件，不追加历史。存储大小只取决于最近一轮结果。
- **恢复：** 重新进入页面时重建原表格、排序、机房显示、进度和日志；恢复行内下载测速所需的测试参数。恢复后不把测试状态或下载状态标记为运行中。
- **保留：** 不改变 IP 测试算法、探测 / 下载请求、测试顺序、表格列和排序、复制、行内测速及取消行为；其他配置检查页面行为不变。
- **改动文件：** `design/src/main/java/com/github/kr328/clash/design/custom/ConfigCheckDesign.kt`、`design/src/main/java/com/github/kr328/clash/design/custom/IpTestView.kt`、`app/src/main/java/com/github/kr328/clash/custom/ConfigCheckActivity.kt`、`design/build.gradle.kts` 和本登记文件；上游文件总表新增 `design/build.gradle.kts`（#87），其他上游挂钩不变。
- **功耗 / 网络：** 仅在用户启动测试或测试结果更新时写入本地偏好设置；恢复时只读取本地数据，不增加网络请求、定时器、轮询或后台任务。
- **验证：** 完成源码结构与调用路径检查；未运行 Android 编译或真机验证。需用户手动触发 Build Debug 检查退出 / 重进页面、进程重启、行内下载测速和新一轮覆盖；不会自动触发构建。


## 89. 配置检查页按钮重排（r105）
- **布局：** 域名输入框单独一行；下一行并排等宽放“查看有效配置”和“清理缓存”；再下一行并排等宽放“ECH检查”、“检查 DNS”、“测试其他 IP”，紧贴下方结果表格。取代 #88 的分组。
- **视觉：** 仍全部使用 `Widget.MaterialComponents.Button.TextButton`；只有“清理缓存”文字用 `?attr/colorError`，与连接页的危险操作一致。不增加背景、描边、图标或新控件样式。按钮等宽（`layout_weight`），“ECH检查”等按钮点击后变成“取消”时文字宽度变化不会挤动其他按钮。
- **保留：** 所有按钮 ID、点击回调、请求参数、检查逻辑和结果显示不变。
- **功耗 / 网络：** 仅调整静态 XML 排列，不增加监听、定时器、轮询、网络请求或后台任务。
- **改动文件：** `design/src/main/res/layout/design_config_check.xml` 和本登记文件；没有修改上游文件总表中的挂钩。
- **验证：** 完成 XML 结构与按钮 ID / 回调源码检查；未运行 Android 编译或真机验证。需用户手动触发 Build Debug 检查。

## 91. 配置检查页说明按钮与域名输入框提示修正（r107）
- **说明入口：** 移除上一版说明文字的单行折叠、点击展开 / 收起及相关状态代码；顶部改为 `Widget.MaterialComponents.Button.TextButton` 样式的“说明”按钮。点击后使用现有 `MaterialAlertDialogBuilder` 显示完整 `check_hint`，仅提供关闭按钮；不保存状态、不增加动画。原 `check_hint` 文案不变。
- **域名输入框：** 保留 `TextInputLayout` 的 OutlinedBox 和 `MaterialAutoCompleteTextView`。外层关闭 hint（`app:hintEnabled="false"`），`check_domain_hint` 改由输入控件自身的 `android:hint` 显示；不为输入控件添加自定义 padding、minHeight、textSize、background 或 lineSpacing，边框颜色继续跟随主题。
- **保留：** 输入控件 ID、`textUri`、单行、补全阈值、历史下拉适配器、点击展开历史及 prefs 读写；所有其他按钮、回调、IP 测试结果恢复、ECH / DNS / IP 测试逻辑和结果显示均不改。没有新增监听、定时器、轮询、网络请求或后台任务。
- **字符串：** 仅新增 `check_hint_button`，英文资源为 “Info”，中文资源为“说明”；`check_hint` 和 `check_domain_hint` 原文不改。
- **改动文件：** `design/src/main/res/layout/design_config_check.xml`、`design/src/main/java/com/github/kr328/clash/design/custom/ConfigCheckDesign.kt`、`design/src/main/res/values/strings_custom.xml`、`design/src/main/res/values-zh/strings_custom.xml` 和本登记文件；没有修改上游文件总表中的挂钩。
- **验证：** 已完成源码级检查：折叠相关代码 / 属性已移除；说明按钮绑定对话框；输入提示位于输入控件自身，外层禁用 hint；输入控件未添加自定义尺寸 / 内边距 / 背景属性；历史适配器及 prefs 调用保持不变。未运行 Android 编译或真机验证，因此按钮实际弹窗、输入框约 56dp 的最终测量高度、输入过滤下拉、软键盘布局及浅色 / 深色主题显示均**未实测**；需手动触发 Build Debug 并按上述项目真机检查。


## 92. 将配置检查页说明按钮移入标题栏（r108）
- **位置：** 将“说明”按钮从标题栏下方的独立一行移入顶部 `ActivityBarLayout` 内的 `RelativeLayout`，放在复制图标（`check_copy_view`）左侧并垂直居中；标题 `common_activity_bar` 的 `layout_toStartOf` 改为指向说明按钮，避免标题与按钮重叠。复制图标的位置、尺寸和属性不变。
- **空间：** 删除原来独立占行的说明按钮，域名输入框及后续内容随之上移，不额外增加一行高度。
- **保留：** 说明按钮的 Material TextButton 样式、字符串、点击对话框和关闭行为不变；域名输入框、历史下拉、所有其他按钮、回调、IP 测试结果恢复、ECH / DNS / IP 测试逻辑均不改。
- **改动文件：** `design/src/main/res/layout/design_config_check.xml` 和本登记文件；没有修改 Kotlin 逻辑、字符串资源或上游文件总表。
- **验证：** 已检查标题栏相对定位关系、说明按钮位于复制图标左侧、标题指向说明按钮、原独立按钮行已删除，复制图标属性未改。未运行 Android 编译或真机验证；需手动触发 Build Debug 检查窄屏标题空间、按钮点击区域及实际垂直居中效果。


## 93. 修复配置检查页域名输入框缺少 Material AutoCompleteTextView 默认尺寸（r109）
- **原因：** 真机截图估算输入框高度约 22–23dp，明显低于标准 OutlinedBox 约 56dp。源码检查确认控件类型正确，确实是 `MaterialAutoCompleteTextView`；没有自定义 padding、minHeight、固定 height、textSize 或 background，Kotlin 也没有运行时修改这些属性；App 主题继承 Material Components 主题，Material 依赖为 1.6.1。问题在于外层 `Widget.MaterialComponents.TextInputLayout.OutlinedBox` 的主题覆盖针对 `TextInputEditText`，不会自动给当前 `MaterialAutoCompleteTextView` 应用 AutoCompleteTextView 的 OutlinedBox 样式，导致控件未获得 Material 默认的上下内边距。
- **修复：** 仅给 `check_domain_view` 增加 `style="@style/Widget.MaterialComponents.AutoCompleteTextView.OutlinedBox"`，使用 Material 1.6.1 自带样式。该样式提供左右 16dp、上下 17dp 的默认内边距；按 16sp 文字行高约 22dp 计算，整体目标约 56dp。没有直接设置任何 padding / minHeight / height / textSize / background / lineSpacing。
- **保留：** 控件仍为 `MaterialAutoCompleteTextView`；外层仍为 `TextInputLayout` OutlinedBox，`app:hintEnabled="false"` 不变，提示仍由控件自身 `android:hint` 显示。输入 ID、`textUri`、单行、补全阈值、历史下拉、prefs 读写及页面其他功能均不改。
- **改动文件：** `design/src/main/res/layout/design_config_check.xml` 和本登记文件；没有修改 Kotlin、主题、依赖、上游文件总表或构建工作流。
- **验证：** 已核对 Material Components 1.6.1 的资源定义，确认该样式存在且提供上下各 17dp 默认内边距；完成 XML 变更范围检查。未运行 Android 编译，未进行真机测量；约 56dp 是按 Material 样式计算的目标值，不是实测值。需手动触发 Build Debug 后在真机确认方框高度、文字上下留白及浅色 / 深色显示。


## 94. 配置检查页：表格行高、目标网址说明进弹窗、按钮列对齐（r110）
- **表格行高：** 一行的高度取这一行里最高的那个格子（`IpTestView.redraw`）。成功的行下半截大片空白，原因是“分段耗时”列固定 84dp，窄于“首字节 447 ms”，每一行文字在 “ms” 前折行，三行变成五六行，把整行撑高。现在这一列的最小宽度取三行里最长一行的实际宽度（14sp，加格子内边距，最多 120dp），其余列宽、最小行高 `rowH`、固定 IP 列与横向滑动部分同高的规则都不变。表格比页面宽时的“其他列最多让 15%”那一步仍会按比例缩窄这一列，窄到装不下时 “ms” 还是会折行，这种情况没有特别处理。
- **目标网址说明：** `iptest_target_sub` 只留 “目标网址：%1$s”；原来括号里的解释拆成新字符串 `iptest_target_note`（中英文两份），在“说明”弹窗里作为 `check_hint` 之后的第二段，只在表格是套入节点测（有目标网址）时显示。弹窗显示与否由 `ConfigCheckDesign` 的 `ipTarget` 决定，进入文字页时清掉。已保存的上一轮结果（#87）里存的是旧的长 `sub`，恢复时仍显示旧文字，下一次测试起才是新的。
- **按钮列对齐：** “推算最终配置”“清缓存”所在行加一个空的第三格（`Space`，权重 1），这两个按钮和下面三个测试按钮的列对齐。按钮样式、ID、回调不变。
- **归档：** #88、#90 的内容已被后面的条目取代，移到 `CUSTOM_ARCHIVE.md`，主文件头部留一行说明。上游文件总表、`当前版本：` 那一行的格式和位置不变。其余条目里“部分被取代”的（#45 #50 #51 #69 等）没有动，因为要逐条判断哪些句子还有效。
- **改动文件：** `design/src/main/java/com/github/kr328/clash/design/custom/IpTestView.kt`、`ConfigCheckDesign.kt`、`design/src/main/res/layout/design_config_check.xml`、`design/src/main/res/values/strings_iptest.xml`、`design/src/main/res/values-zh/strings_iptest.xml`、`CUSTOM.md`、新增 `CUSTOM_ARCHIVE.md`；没有修改上游文件总表中的挂钩。
- **功耗 / 网络：** 只在表格重画时多量一次文字宽度，弹窗只在点击时拼字符串；没有新增监听、定时器、轮询、网络请求或后台任务。
- **验证：** 完成源码级检查。未运行 Android 编译、未做真机验证：行高实际降到多少、弹窗里第二段是否只在套入节点测时出现、新旧恢复结果的显示，都需要手动触发 Build Debug 后在真机确认。



## 95. 显示 IP 下载测速的原始错误及中文解释（r111）
- **原因：** 下载测速返回的 speedErr 是内核原始错误文本（例如 HTTP 403），不是 ErrorsText.explain() 所需的错误代码；直接把原文当代码翻译会统一落到“其他错误”，导致具体原因被隐藏。
- **修复：** 保留测速错误的分类解释逻辑；错误解释不放入日志。实际表格显示位置由 #96 修正。
- **保留：** 不改变测速地址、节点替换 / 直连方式、下载量上限、时间上限、跳过的首秒、测速结果计算、表格和取消逻辑；成功测速显示不变。没有新增网络请求、定时器、轮询或后台任务。
- **改动文件：** app/src/main/java/com/github/kr328/clash/custom/ConfigCheckActivity.kt、design/src/main/res/values-zh/strings_iptest.xml、design/src/main/res/values/strings_iptest.xml 和本登记文件；没有修改上游文件总表。
- **验证：** 完成源码调用路径与字符串资源引用检查；确认原始错误不会再传给错误代码翻译函数。未运行 Android 编译或真机验证；需手动触发 Build Debug 检查日志换行及不同错误的实际显示，不会自动触发构建。


## 96. 下载测速失败原因显示在表格速度列（r112）
- **问题：** 速度列原来只显示“失败”，没有显示具体错误；#95 把换行解释放进了实时日志，位置错误。
- **修复：** 失败行的“速度”单元格显示三行：红色“失败”、原始错误、括号内中文解释。HTTP 403 显示“（服务器拒绝访问）”；超时、连接拒绝、连接重置、EOF、网络不可达和 TLS / 证书错误按现有说明分类。报告错误、行内错误和异常错误都写回对应 IP 行，确保表格能显示原因。
- **日志：** 实时日志仍为单行，只记录原始错误；不添加第二行中文解释。
- **布局：** 加宽最右侧“速度”列以容纳错误文字；表格仍可横向滚动。成功测速结果和其他列不变。
- **保留：** 不改变测速地址、节点替换 / 直连方式、下载量上限、时间上限、跳过的首秒、测速计算、取消逻辑；没有新增网络请求、定时器、轮询或后台任务。
- **改动文件：** app/src/main/java/com/github/kr328/clash/custom/ConfigCheckActivity.kt、design/src/main/java/com/github/kr328/clash/design/custom/IpTestView.kt 和本登记文件；不改上游文件总表。
- **验证：** 完成源码级检查；未运行 Android 编译或真机验证。需手动触发 Build Debug 确认表格换行、错误行高及深浅主题颜色；不会自动触发构建。


## 97. 测速失败表格只显示 HTTP 状态码（r113）
- **表格：** 速度列失败时第一行只显示红色“失败”；第二行仅在原始错误中能提取到 HTTP 状态码时显示代码（例如“HTTP 403”）。不再在表格显示原始英文错误或中文解释；没有 HTTP 状态码时只显示“失败”。
- **日志：** 完整原始错误仍只写入实时日志，保持单行；不改变错误记录内容。
- **布局：** 速度列宽度由 112 dp 收回到 76 dp，供失败标签和状态码使用；其他列宽、横向滚动和成功测速显示不变。
- **保留：** 不改变测速地址、节点替换 / 直连方式、下载量上限、时间上限、跳过的首秒、测速计算、错误回写和取消逻辑；没有新增网络请求、定时器、轮询或后台任务。
- **改动文件：** design/src/main/java/com/github/kr328/clash/design/custom/IpTestView.kt 和本登记文件；没有修改上游文件总表。
- **验证：** 完成源码级检查；未运行 Android 编译或真机验证。需手动触发 Build Debug 检查 HTTP 状态码提取、无状态码时的单行显示、表格列宽及深浅主题颜色；不会自动触发构建。


## 98. 直连与套入节点测速使用独立下载地址（r114）
- **输入框：** “测试其他 IP”弹窗把原来的单个下载测速地址拆成两个独立输入框，分别标注直连和套入节点的地址要求。直连地址必须在 Cloudflare 上；套入节点地址必须不在 Cloudflare 上。
- **默认值：** 直连为 `https://speed.cloudflare.com/__down?bytes=10485760`；套入节点为 `https://proof.ovh.net/files/100Mb.dat`。
- **记忆：** 分别使用 SharedPreferences 键 `ip_url_direct` 和 `ip_url_node`。点击对应模式按钮时，将该模式的地址放进原有 `IpTestChoice.url`；两个输入框的值都会保存，留空分别回退各自默认值。旧键 `ip_url` 不迁移、不再读取或写入，因为无法判断旧值属于哪种模式。
- **保留：** `IpTestChoice.url` 字段及 `ConfigCheckActivity` 的保存 / 行内测速调用不变；不改测速请求、测速算法、Go 核心、其他弹窗控件或配置 IP 读取逻辑。
- **功耗 / 网络：** 仅在打开弹窗时读取偏好设置、点击直连 / 套入节点按钮时写入；不新增定时器、轮询、监听、网络请求或后台任务。
- **字符串：** 在 `strings_iptest.xml` 中新增中英文两组模式标签，删除不再使用的 `iptest_url_label`。
- **改动文件：** `design/src/main/java/com/github/kr328/clash/design/custom/IpTestDialog.kt`、`design/src/main/res/values/strings_iptest.xml`、`design/src/main/res/values-zh/strings_iptest.xml` 和本登记文件；没有修改上游文件总表。
- **验证：** 已完成源码级差异与引用检查；未运行 Android 编译或真机验证。需手动触发 Build Debug，并分别检查两个默认地址、独立记忆、空值回退及两种模式传入的 URL；不会自动触发构建。

## 99. 测 IP 页面术语统一及下载测速从首字节计时（r115）
- **模式名称：** 将“套入节点”统一改为“经节点”，两个模式按钮分别为“经节点测延迟”和“直连测延迟”。说明明确经节点模式会临时把所选节点的 server 替换为待测 IP / 域名，再经该节点发起测试；同时说明机房、归属运营商、归属地等信息来源，以及直连额外测试端口和 ECH。
- **下载测速命名：** 只有表格行内下载动作使用“下载测速”；两个模式按钮及延迟探测不称为测速。两个下载地址标签分别说明直连必须使用 Cloudflare 地址、经节点建议使用非 Cloudflare 地址（Worker 访问 CF 站可能较慢并超时）。弹窗说明下载地址只在测试后点击某行“下载测速”时使用，模式按钮不使用下载地址。
- **延迟定义：** 直连延迟称“握手延迟”，定义为 TCP 建连 + TLS 握手；经节点延迟称“经节点延迟”，定义为经节点请求目标网址的总时间（目标网址留空时使用节点自带测试地址）。日志平均值带上对应名称；表格上方说明补充两种定义和抖动（成功结果相对平均值的平均偏差）。
- **机房 / 国家 / 目标站：** “机房（城市）”改为“入口机房”；经节点入口机房列头带 *，说明及日志标明它来自对该 IP 的额外直连读取。出口国家替代“落地”；“出口机房”保留。目标网址标签说明其用于延迟与出口机房读取；目标站需在 Cloudflare 上才有出口机房。
- **失败率和端口：** 将界面“丢包率 / 丢包”改为“失败率”，仅改显示文案，内部 Loss 字段与计算不动。经节点模式的端口列显示“-”，说明标明该列原本来源于节点配置、未实际测试；直连端口测试和 iptest_port_none 既有含义保留。
- **对照显示核对：** 已按代码检查 IpTestView：直连模式的列集合本来就不包含“比对照快”；ConfigCheckActivity 直连分支显示 ECH 信息，不读取或显示对照。经节点且未填目标网址时才显示对照；填目标网址时显示目标网址信息并隐藏对照。因此不重复改动这段已正确的条件。
- **下载计时修正：** MeasureDownload 首字节等待上限设为 10 秒，使用 context.WithTimeout；经节点时该等待 context 同时覆盖节点拨号和收到首个响应数据字节。收到第一个 body 字节后才启动原有 5 秒下载时限、前 1 秒跳过计时及速度计算；最大 10 MB 不变。通过 context 完成等待与下载超时切换，不新增轮询或显式定时器。
- **失败原因：** 速度格显示“超时”“无数据”、HTTP 状态码或其他错误的前 20 个字符；完整原始原因仍写入实时日志。
- **保留 / 可选项：** 不改延迟、失败率、抖动、分段耗时、目标站请求、端口探测、归属信息和 ECH 的计算或请求逻辑；不改 IpTestReport 内部字段。第六部分上游通用文案（ip_delay_age_*、errors_* 等）整段跳过，iptest_ref_none 保留原文。第十二条对照显示逻辑按现状核实，无需改动。
- **改动文件：** core/src/main/golang/native/ipdelay/iptry_download.go、design/src/main/java/com/github/kr328/clash/design/custom/IpTestView.kt、design/src/main/java/com/github/kr328/clash/design/custom/IpTestDialog.kt、app/src/main/java/com/github/kr328/clash/custom/ConfigCheckActivity.kt、design/src/main/res/values-zh/strings_iptest.xml、design/src/main/res/values/strings_iptest.xml 和本登记文件；没有新增文件，没有改上游文件总表。
- **功耗 / 网络：** 不新增网络请求、定时器、轮询或后台任务；下载测速仍只在用户点击某行按钮时发生。
- **验证：** 已完成源码修改与字符串引用 / 占位符检查；未运行 Go 测试、Android 编译或真机验证。需要验证首字节 10 秒等待、首字节后 5 秒窗口、首秒跳过、10 MB 上限、错误原因显示及各模式文案；不会自动触发构建。


## 100. 修复测 IP 英文字符串资源中的未转义撇号（r116）
- **构建错误：** #99 的 Build Debug 在 `:app:mergeAlphaReleaseResources` 失败，AAPT2 报告 `iptest_dialog_hint`、`iptest_log_node_colo_ok`、`iptest_measure_node_note` 字符串资源解析失败。
- **原因与修复：** 英文资源这三条文案包含 `node's`，撇号未按 Android string resource 规则转义。现将这些撇号写为 `\'`；不改文案含义、占位符或中文资源。
- **验证：** 已复查英文 `strings_iptest.xml` 的全部字符串，确认没有未转义撇号；未重新运行 Android 构建，因此仍需下一次 Build Debug 确认 AAPT2 资源编译通过。
- **改动文件：** `design/src/main/res/values/strings_iptest.xml`、`CUSTOM.md`。没有改 Kotlin、Go、构建工作流或其他资源。


## 101. 配置检查页交互、ECH / DNS 真取消及推算配置文案（r117）
- **ECH 名称与输入提示：** “说明”统一称“ECH检查”；顶部域名框提示简化为 ECH / DNS / 直连 SNI 三种用途，完整说明写入“说明”：ECH 检查使用确定被封且由节点服务器或其前置 CDN 托管的域名；DNS 可用任意域名；直连测延迟把域名作为 SNI 和 Host，需要入口机房信息时域名必须在 Cloudflare 上。
- **运行中按钮：** ECH、DNS、测试其他 IP 运行时，只有当前任务按钮保持可点并显示“取消”；其他测试、推算配置、清缓存、说明和复制按钮禁用，任务结束或取消后恢复。由任务状态更新 UI，不使用定时器或轮询。清缓存和推算配置的业务逻辑不变。
- **ECH / DNS 真取消：** 新增 `CANCEL_CHECK` / `CancelCheck` 入口。ECH 配置获取、批次握手、DNS 服务器探测、内核解析器查询和本机 DNS 监听探测统一使用可取消 context；TCP / UDP 查询在 context 取消时关闭底层连接，DoH 请求和 TLS 握手使用该 context。Kotlin 取消时先捕获任务类型，再取消协程并调用对应内核取消入口，避免 finally 先清状态造成漏取消；不新增定时器或轮询。
- **ECH 结论重试：** ECH 已接受而明文握手失败时，明文握手最多再试 2 次；ECH 握手失败而明文成功时，ECH 最多再试 2 次。任一次重试成功立即停止；只有另一侧连续 3 次失败才给出“生效并绕过封锁”或“不生效”的结论。每个地址的总重试上限为 3 次，不改地址选择顺序。
- **清缓存失败：** 通用文案改为“清除失败”；Kotlin 能取得异常信息时另起一行显示原因。内核只返回失败布尔值时不伪造原因。
- **推算配置命名：** 按钮改为“推算最终配置”，结果页标题为“按已保存配置推算的最终配置”；说明明确读取已保存配置，不一定等于内核当前运行配置。无差异文案不再声称“内核跑的就是你写的”。已核对当前自定义实现 `config.Effective` 只读取活动 profile 目录的 `config.yaml` 并重新执行加载步骤；当前实现没有读取并比较内核运行时完整配置的路径，因此不标注两份一致性。
- **改动文件：** `core/src/main/golang/native/conftest/conftest.go`、`core/src/main/golang/native/conftest/dnscheck.go`、`core/src/main/golang/native/connections.go`、`core/src/main/java/com/github/kr328/clash/core/custom/CustomConnections.kt`、`app/src/main/java/com/github/kr328/clash/custom/ConfigCheckActivity.kt`、`design/src/main/java/com/github/kr328/clash/design/custom/ConfigCheckDesign.kt`、`design/src/main/java/com/github/kr328/clash/design/custom/EffectiveText.kt`、`design/src/main/res/values/strings_custom.xml`、`design/src/main/res/values-zh/strings_custom.xml` 和本登记文件。
- **保留 / 验证：** 不改清缓存和推算配置的核心业务逻辑，不改上游 mihomo 子模块；不新增网络请求、定时器或轮询。完成源码级变更和引用检查；未运行 Go 测试、Android 编译或真机验证，需后续构建验证 Go / Kotlin / 资源编译及取消时网络连接是否及时关闭。


## 102. 撤销 / 重做回到保存点时清除未保存状态（r118）
- **保存点：** `UndoStack` 增加 `markSaved()` 和当前位置状态。保存时记录撤销栈位置；撤销或重做回到该位置时，编辑页的 dirty 自动变为 false，离开该位置则为 true。
- **分支编辑：** 在保存点之后继续编辑仍可撤销回保存点；从保存点之前的撤销位置开始新编辑会使原保存点失效，因为原来的重做路径已被丢弃。
- **大文件：** 撤销栈清空时同时使保存点失效；此后任意编辑均视为未保存，直到下一次 `markSaved()`。
- **合并边界：** `markSaved()` 继续调用 `seal()`，保存点前后的输入不会合并成同一步。
- **改动文件：** `design/src/main/java/com/github/kr328/clash/design/custom/editor/UndoStack.kt`、`design/src/main/java/com/github/kr328/clash/design/custom/editor/EditorDesign.kt` 和本登记文件；没有修改上游文件总表。
- **验证：** 完成保存点、撤销、重做、分支编辑、栈清空和容量裁剪路径的源码检查；未运行 Android 编译或真机验证。


## 103. 编辑配置时顶栏显示配置名（r119）
- **显示规则：** 打开 profile 顶层的 `config.yaml` 时，顶栏使用 profile 名称；名称为空时回退到 `config.yaml`。其他文件仍显示文件名。
- **未保存标记：** 普通标题和 `setDirty` 拼接“●”时使用同一个显示名称，避免编辑后标题切回文件名。
- **实现：** 由 `EditorActivity` 将已有的 profile 标题传给 `EditorDesign`；不额外读取配置文件，也不解析 YAML。
- **改动文件：** `app/src/main/java/com/github/kr328/clash/custom/EditorActivity.kt`、`design/src/main/java/com/github/kr328/clash/design/custom/editor/EditorDesign.kt` 和本登记文件；没有修改上游文件总表或字符串资源。
- **验证：** 完成构造参数、初始标题和 dirty 标题路径的源码检查；未运行 Android 编译或真机验证。


## 104. 历史版本入口统一使用同一名称（r120）
- **中文：** 顶栏短按钮、保存历史版本的对话框标题和菜单入口统一显示“历史版本”。
- **英文：** 对应位置统一使用“History version”，不再使用含义不一致的 “Keep”。
- **保留：** 不改变保存历史版本的行为、菜单结构或历史列表标题。
- **改动文件：** `design/src/main/res/values/strings_editor.xml`、`design/src/main/res/values-zh/strings_editor.xml` 和本登记文件；没有修改 Kotlin 或上游文件总表。
- **验证：** 完成资源键与中英文对应项检查；未运行 Android 编译或真机验证。


## 105. 非 config.yaml 文件的保存按钮标明会生效（r121）
- **按钮文案：** 非 `config.yaml` 文件的保存按钮改为中文“保存并生效”、英文“Save & apply”，准确说明保存会提交并重载配置。
- **保留：** `config.yaml` 仍显示“保存”；split 模式仍显示历史版本入口，不改变按钮行为。
- **字符串：** 新增 `editor_save_apply` 中英文资源。
- **改动文件：** `design/src/main/java/com/github/kr328/clash/design/custom/editor/EditorDesign.kt`、`design/src/main/res/values/strings_editor.xml`、`design/src/main/res/values-zh/strings_editor.xml` 和本登记文件；没有修改上游文件总表。
- **验证：** 完成按钮文案条件与资源引用检查；未运行 Android 编译或真机验证。


## 106. 将“正在用的配置”统一称为“已应用的版本”（r122）
- **文案：** 编辑器中 `editor_compare_*`、`cmp_*`、`history_in_use`、对比菜单项和 `editor_leave_*` 等涉及当前已提交配置的中英文文案统一改称“已应用的版本 / applied version”；保留原有对比页和菜单项，只调整文案。
- **应用结果：** 应用成功及“已经是已应用版本”两条路径均通过现有 `queryActive()` profile 查询接口判断该 profile 是否为当前选中项。当前选中时提示“已应用并生效”；未选中时明确提示“已应用（这个配置当前没有被选中，所以没有加载）”。大文件无法保存历史时也保留相同的选中状态区分。
- **改动文件：** `app/src/main/java/com/github/kr328/clash/custom/EditorActivity.kt`、中英文 `strings_editor.xml`、中英文 `strings_custom.xml` 和本登记文件；没有删除或改动对比页及其菜单功能。
- **验证：** 核对 `ProfileManager.queryActive()` 的现有查询实现并检查所有新资源引用；未运行 Android 编译或真机验证。


## 107. 撤销 / 重做图标对齐 App 顶栏样式（r123）
- **图标：** 撤销、重做矢量图改为 24dp，并使用与 `design_logs.xml`、`design_profiles.xml` 中顶栏图标相同的 `?attr/colorControlNormal` 着色方式，不再由代码手动套 `colorText`。
- **点击区域：** 两个 ImageView 固定为 48dp × 48dp，图标通过 12dp 内边距保持 24dp 可视尺寸；三个点按钮不变。
- **禁用状态：** 撤销 / 重做不可用时透明度改为 0.38。
- **改动文件：** 两个编辑器图标资源、`EditorDesign.kt` 和本登记文件；没有改动其他顶栏按钮。
- **验证：** 对照原 App 顶栏 XML 中 24dp 图标、`colorControlNormal` tint 与 5dp 资源内边距；按要求保留约 48dp 点击区域。未运行 Android 编译或真机验证。


## 108. split 模式应用按钮按已应用版本状态变淡（r124）
- **显示：** 页面内容与 profile 已提交的配置完全相同时，split 模式“应用”按钮透明度为 0.38；不同时为正常透明度。按钮始终保持可点击，点击后仍走原有“已经是已应用版本”的提示。
- **比较时机：** 仅在页面载入完成、应用成功、应用时发现已相同、历史版本载入、导入和“全部替换”后比较一次；比较放在 IO 线程。没有定时器或轮询。
- **编辑过程：** `onEdited` 只把按钮恢复正常，不读取文件；全部替换结束后再发请求进行一次比较。状态通过 `EditorDesign.setApplyMatchesApplied()` 传入。
- **附带校正：** 撤销 / 重做两处状态刷新路径均统一使用 0.38 禁用透明度。
- **改动文件：** `EditorActivity.kt`、`EditorDesign.kt` 和本登记文件。
- **验证：** 检查了所有规定触发点与后台比较路径；未运行 Android 编译或真机验证。


## 109. 历史版本长按菜单支持删除（r125）
- 菜单：长按历史版本行弹出菜单，只提供固定 / 取消固定、删除。固定操作仍使用原有上限和状态提示；删除需二次确认。
- 保护：当前已应用版本不显示删除入口，删除方法内部也再次比较当前配置内容，避免误删已应用版本。
- 删除清理：同时删除版本 YAML 及 .ok、.saved、.bad、.pin 标记；若删除的版本正是页面当前打开版本，清零 openedAt。删除后列表不关闭，直接移除该行。
- 改动文件：ConfigHistory.kt、EditorActivity.kt、EditorDesign.kt、中英文 strings_editor.xml 和本登记文件。
- 验证：检查删除保护、确认流程、标记清理和行内刷新；未运行 Android 编译或真机验证。


## 109a. 修正历史版本删除时的列表集合（r126）
- **修正：** 历史版本列表在删除操作中需要同步移除对应的内存条目，读取后转为可变列表，避免对只读 List 调用 removeAt。
- **改动文件：** EditorActivity.kt 和本登记文件。
- **验证：** 静态核对删除回调对列表的修改类型；未运行 Android 编译或真机验证。


## 110. 历史版本内容比较增加 SHA-256 缓存（r127）
- 比较：文件长度不一致时立即判定不同；长度一致时比较 SHA-256，不再逐个读取版本文件并做完整字节数组比较。
- 缓存：文件哈希按路径、长度、修改时间作为缓存键，使用有上限的 LRU 内存缓存；文件变化后键变化并重新计算。文本侧哈希在每次 find / isInUse / unapplied 调用中只计算一次。
- 改动文件：ConfigHistory.kt 和本登记文件。
- 验证：静态检查 same / find / isInUse / unapplied / prune 路径均先检查长度；未运行 Android 编译或真机验证。


## 111. 历史列表行数与增删行统计缓存（r128）
- 每个历史版本旁新增同名 .stat 文件，保存行数、相对更旧版本增加行数、删除行数，以及比较所用的更旧版本时间戳。
- 打开历史列表时优先读取 .stat；只有缓存不存在、格式错误或更旧版本时间戳变化（例如版本被删除或挤出）时才重新读取文本、计算差异并写回缓存。
- 版本 remove / delete / prune 清理时一并删除对应 .stat。列表统计只在用户打开历史列表时触发，不添加后台任务。
- 同时修正 #110 的 unapplied：复用一次计算的文本哈希，不再通过 find 重复计算。
- 改动文件：ConfigHistory.kt、EditorActivity.kt 和本登记文件。
- 验证：静态核对 .stat 命名、olderAt 校验和版本清理路径；未运行 Android 编译或真机验证。


## 112. 查找与替换支持正则表达式（r129）
- 查找栏在“区分大小写”旁新增“正则”开关，状态写入 custom_editor SharedPreferences。
- 查找、查找计数、替换当前项和全部替换均支持正则；替换使用 Java Matcher 的替换语法，支持 $1 等捕获组。
- 语法错误或匹配超过约 2 秒会中断，并在计数位置显示“表达式有误”，不崩溃。使用带截止时间的 CharSequence，在正则引擎读取字符时检查超时。
- 零长度匹配依赖 Matcher.find() 的标准推进规则，避免循环卡死；大文件使用 fullText() 全文匹配，替换后按原有 windowed 路径重新载入。
- 改动文件：CodeEditText.kt、EditorDesign.kt、中英文 strings_editor.xml 和本登记文件。
- 验证：静态检查普通文本查找分支保持原逻辑、正则替换捕获组与超时捕获路径；未运行 Android 编译或真机验证。


## 113. 编辑器跳到定义（r130）
- 仅文件名为 config.yaml 时，在 ⋮ 菜单显示“跳到定义”。用户点击时才解析一次全文，打字过程中不运行。
- 复用 YamlLint 的名称 / 引用解析结果：proxy-groups 的 proxies 与 rules 策略名定位到 proxies / proxy-groups；use 名定位到 proxy-providers；RULE-SET 名定位到 rule-providers。重复定义按首次出现定位。
- 通过完整文本和全局光标偏移工作，大文件窗口外的引用也可定位；找不到时显示提示。
- 改动文件：YamlLint.kt、CodeEditText.kt、EditorDesign.kt、EditorActivity.kt、中英文 strings_editor.xml 和本登记文件。
- 验证：静态检查四类引用到定义映射、菜单仅 config.yaml 显示、跳转行号转换为 1-based；未运行 Android 编译或真机验证。


## 114. 核对编辑器临时配置检查是否读取配置目录文件（r131）
- **内核版本：** MetaCubeX/mihomo commit 3f27ef39f8c96bbfbc8b0b9bb10f5c9ef60149d8（用户提供的源码归档地址）。
- **结论：** mihomo 的 config.Parse / config.ParseRawConfig 负责解析并创建 Provider；ParseProxyProvider / ParseRuleProvider 对 type: file 只解析相对路径并创建 FileVehicle / Fetcher，不在构造时读取文件。实际读取发生在 Provider.Initial -> Fetcher.Initial，其中会对 Vehicle.Path 执行 os.Stat，并在文件存在时 os.ReadFile。
- **与本 App 验证流程的关系：** 当前 EditorActivity.check 调用 queryCustomConnections(SORT_VALIDATE)；native config.Validate 执行 UnmarshalAndPatch(profileDir) -> Parse(rawCfg) -> destroyProviders(cfg)。它没有调用 hub.ApplyConfig，也没有调用 provider.Initial。destroyProviders 仅关闭 Provider。因此临时 editor_check 目录无需把原配置目录其他文件 symlink 进来；不需要复制大文件，也不改验证代码。
- **源码依据：** mihomo config/config.go 的 Parse / ParseRawConfig / parseProxies / parseRuleProviders；adapter/provider/parser.go 的 ParseProxyProvider；rules/provider/parse.go 的 ParseRuleProvider；adapter/provider/provider.go 的 ProxySetProvider.Initial；rules/provider/provider.go 的 RuleSetProvider.Initial；component/resource/fetcher.go 的 Fetcher.Initial；本仓库 core/src/main/golang/native/config/custom_validate.go、config/load.go、config/provider.go 和 EditorActivity.kt 的 check。
- **改动文件：** 仅 CUSTOM.md；没有新增临时目录链接或修改代码。
- **验证：** 对照用户指定的固定 mihomo commit 和本仓库实际 Validate 调用链；未运行 Android 编译或真机验证。
