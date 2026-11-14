# CUSTOM.md — 自定义功能登记表

基线：上游 `1a4f949213fcbb7f0c9e2a4a21138642d84b8f22`（版本 2.11.36）。
当前版本：2_11_36-r53
所有挂钩处在代码里都带 `CUSTOM` 注释，可用 `grep -rn CUSTOM` 找全。
规则和同步上游的步骤见 `AGENTS.md`。每次改动都要同步更新本文件（含下面的总表）。

## 上游文件总表（同步上游时冲突只会出现在这里）
| 上游文件 | 对应条目 | 冲突风险 | 冲突时怎么办 |
|---|---|---|---|
| `.github/workflows/build-debug.yaml` | #2 #3 #4 #5 #14 #18 #20 #40 | 中（`on:` 段、Build 步骤） | 以上游为底，重加：只留 `workflow_dispatch`、预下载 Gradle、预下载 geo 文件并加 geo 文件缓存兜底（#14 #18）、签名步骤、arm64 参数（含 `-x :app:downloadGeoFiles`）与上传、`Checkout submodules` 之后的 `Apply mihomo patches` 步骤（#20）、上传前的 `Artifact name` 步骤和上传步骤里的 `name:`（#40） |
| `.github/workflows/build-pre-release.yaml` | #2 #3 | 中 | 以上游为底，重加：只留 `workflow_dispatch`、预下载 Gradle |
| `core/src/main/golang/native/tunnel.go` | #1 #7 #37 | 低 | 重加 `ipdelay.TestGroup(name)` 和 `queryTunnelState` 里的 current 字段（`CurrentSet()`、`currentNode` / `currentIp` / `currentCountry` / `currentIps` / `currentMore`），以及 `reach` 字段（`ipdelay.Reach(node)`，#37） |
| `core/src/main/golang/native/tunnel/proxies.go` | #1 | 低 | 重加 `IPDelays` 字段和填充行 |
| `core/src/main/golang/native/config/load.go` | #1 #22 | 低 | 重加 `ipdelay.CaptureConfig(...)`（#1）和它后面的 `ipdelay.CaptureDNS(rawCfg.DNS)`（#22） |
| `core/src/main/golang/native/delegate/init.go` | #7 | 低 | 重加 `ipdelay.InstallSocketHook(home)`（必须在上游给 `DefaultSocketHook` 赋值之后；`home` 是 `Init` 的第一个参数，用来定位 #10 #11 的小文件） |
| `core/src/main/java/com/github/kr328/clash/core/model/Proxy.kt` | #1 | 低 | 重加 `ipDelays` 参数 |
| `core/src/main/java/com/github/kr328/clash/core/model/TunnelState.kt` | #7 #37 | 低 | 重加 `current*` 参数（`currentNode` / `currentIp` / `currentCountry` / `currentIps` / `currentMore`）、`CurrentIp` 和 `ipsText()`，以及 `reach: Int = 0`（#37） |
| `design/src/main/java/com/github/kr328/clash/design/component/ProxyView.kt` | #1 #12 | **高**（改了 `onMeasure` / `onDraw`） | 以上游新版为底，按 #1 表格的描述重做 IP 行的高度和绘制，再按 #12 加最后一行"N 分钟前测速"，不要硬合 |
| `design/src/main/res/layout/design_main.xml` | #7 #8 #20 #21 #22 #32 | **高**（App 名字 `TextView` 外包了一层 `LinearLayout`；代理卡片改了 `subtext` 并去掉 `visibility`） | 以上游新版为底，按 #7 重做：新增变量 `customIp`、包住 App 名字并加红字 `custom_geosite_notice`、代理卡片 `subtext` 改成带 `customIp`、删掉代理卡片的 `visibility`；#8 在"日志"标签前加"连接"标签，#20 在它后面再加"底层连接"标签（`Request.OpenMux`），#21 在"日志"标签前再加"错误与诊断"标签（`Request.OpenErrors`），#22 在"错误与诊断"标签前再加"配置检查"标签（`Request.OpenConfigCheck`）；#32 在顶部（logo + App 名字）那一行末尾加右上角的 `ImageView`（`@+id/custom_more`，三点图标，48dp）；同时把包住 App 名字的 `LinearLayout`（#7 加的那层）改成 `layout_width=0dp` + `layout_weight=1`（r38，原来是 `wrap_content`，窄屏上名字占满宽度，三点被挤出屏幕） |
| `design/src/main/java/com/github/kr328/clash/design/MainDesign.kt` | #7 #8 #20 #21 #22 #32 | 低 | 重加 `customCards`、`setCustomCards()`；`Request` 枚举加 `OpenConnections`（#8）、`OpenMux`（#20）、`OpenErrors`（#21）、`OpenConfigCheck`（#22）；#32 重加 `customMenu = MainCustomMenu(context, binding.root)` 和它的 import |
| `design/src/main/java/com/github/kr328/clash/design/MetaFeatureSettingsDesign.kt` | #6 #16 | 低 | 重加 `geoSiteDownload()` 一行（#16 起它一次加 GeoSite / GeoIP / ASN 三块，不用再改这里） |
| `app/src/main/java/com/github/kr328/clash/MainActivity.kt` | #6 #7 #8 #20 #21 #22 #35 | 低 | 重加 `fetch()` 里的 `setCustomCards(...)`、`customNodeIpEvents()` 和 `select` 里的 `customNodeIp.onReceive` 分支；`OpenConnections` 分支（#8）、`OpenMux` 分支和 import（#20）、`OpenErrors` 分支和 import（#21）、`OpenConfigCheck` 分支和 import（#22）；#35 流量定时器那一支的条件由 `clashRunning` 改成 `clashRunning && activityStarted` |
| `service/src/main/java/com/github/kr328/clash/service/clash/module/DynamicNotificationModule.kt` | #7 #35 | 低 | 通知正文改用 `NodeIpText.get(service)`（原来是上传/下载速度），去掉不再用的 `queryTrafficNow`；加 `nodeIpChanged` 通道、`NodeIpNotifier.start`、一个 `select` 分支；#35 加属性 `lastPosted`，`update()` 里文字没变就不 `notify`，定时器 1 秒改 5 秒，亮屏和换配置时清 `lastPosted` |
| `service/src/main/java/com/github/kr328/clash/service/ClashManager.kt` | #7 #8 #15 | 低 | `queryTunnelState()` 开头加 `NodeIpNotifier.start(context)`；加 `queryCustomConnections(sort: Int, filter: String)` / `closeCustomConnection(id)` 两个实现（#8 #15） |
| `service/src/main/java/com/github/kr328/clash/service/remote/IClashManager.kt` | #8 #15 | 低 | 末尾重加 `queryCustomConnections(sort: Int, filter: String): String` 和 `closeCustomConnection(id: String): Boolean` |
| `app/src/main/AndroidManifest.xml` | #8 #17 #20 #21 #22 #46 | 低 | 在 `LogsActivity` 前重加 `.custom.ConnectionsActivity`（#8）、`.custom.BackupActivity`（#17，**必须带** `android:theme="@style/BackupTranslucentTheme"`，否则页面不透明，对话框后面是黑的）、`.custom.MuxActivity`（#20）、`.custom.ErrorsActivity`（#21）、`.custom.ConfigCheckActivity`（#22）和 `.custom.EditorActivity`（#46，**必须带** `configChanges` 里的 `orientation|screenSize|...`，否则转一下屏页面就重建，没保存的文字丢了）的 `<activity>` |
| `app/src/main/java/com/github/kr328/clash/FilesActivity.kt` | #46 | 低 | `OpenFile` 分支：`EditorActivity.canOpen(...)` 为真就 `startActivity(EditorActivity.intent(...))`，否则还是原来的外部编辑器；顶部 `import ...custom.EditorActivity` |
| `design/src/main/res/layout/adapter_profile.xml` | #47 | 中（改了时间那个 `TextView` 的位置） | 以上游新版为底：`<data>` 里加变量 `edit`；时间 `TextView`（`elapsed_view`）`layout_marginEnd` 改 20dp、`layout_toStartOf` 改成 `@id/edit_view`；在它和那条 2dp 竖线之间加 `FrameLayout`（`@+id/edit_view`，铅笔图标 `ic_baseline_edit`，`onClick="@{edit}"`） |
| `design/src/main/java/com/github/kr328/clash/design/adapter/ProfileAdapter.kt` | #47 | 低 | 构造函数末尾加带默认值的 `onEditClicked`，`onBindViewHolder` 里加 `binding.setEdit { onEditClicked(current) }` |
| `design/src/main/java/com/github/kr328/clash/design/ProfilesDesign.kt` | #47 | 低 | `Request` 加 `EditConfig(profile)`；创建 `ProfileAdapter` 时多传一个 lambda（`requests.trySend(Request.EditConfig(it))`） |
| `app/src/main/java/com/github/kr328/clash/ProfilesActivity.kt` | #47 | 低 | `when` 里加 `EditConfig` 分支：`startActivity(EditorActivity.intent(this@ProfilesActivity, "${uuid}/config.yaml", "config.yaml", uuid, name))`（注意 select 里 `this` 不是 Activity）；加 import |
| `core/src/main/cpp/CMakeLists.txt` | #7 | 低 | `add_library` 源文件末尾加 `custom_bridge.c` |
| `app/src/main/java/com/github/kr328/clash/MainApplication.kt` | #16 #21 | 低 | `onCreate()` 里 `extractGeoFiles()` 后重加一行 `GeoFileKeeper.restoreAfterUpdate(this)`（#16），在 `onCreate()` 里 `val processName` 之前重加 `CrashRecorder.install(this)`（#21）（及 import） |
| `design/src/main/java/com/github/kr328/clash/design/AppSettingsDesign.kt` | #17 | 低 | `preferenceScreen` 块末尾（"服务"那个开关之后）重加一行 `customBackup()` |
| `service/src/main/java/com/github/kr328/clash/service/ProfileProcessor.kt` | #27 #28 | 低 | `apply()` 里两处：① `fetchProfile(...)` 外面包 `try { ... } catch (e: Exception) { throw ConfigErrorText.explain(context, context.processingDir, e) }`（#27，保存失败时报出是哪一行）；② 在 `context.importedDir.resolve(snapshot.uuid.toString()).deleteRecursively()` 前一行加 `ConfigHistory.keep(context, snapshot.uuid)`（#28，记下被替换的和新的版本）；加两个 import |
| `app/src/main/java/com/github/kr328/clash/LogsActivity.kt` | #31 | 低 | `setContentDesign(design)` 后加一行 `LogLevelControl.attach(this, design)`；加一个 import |
| `design/src/main/res/layout/design_logs.xml` | #31 | 低 | 在 `delete_view`（三横线）左边加 `log_level_view`（r34 起是显示当前级别文字的 `TextView`，原来是图标 `ImageView`）；标题栏的 `layout_toStartOf` 改指向它 |
| `design/src/main/java/com/github/kr328/clash/design/OverrideSettingsDesign.kt` | #31 | 低 | 删掉"日志级别"那一个 `selectableList`（换成一行 CUSTOM 注释）；入口搬到日志页 |
| `build.gradle.kts` | #44 | 低（只加一个 `customRev` 和两处 `versionNameSuffix`） | 重加 `queryConfigProperty` 后面的 `customRev`（读 `CUSTOM.md` 的"当前版本："那一行，取最后的 `r<数字>`），`release` 构建类型里的 `versionNameSuffix = customRev`，`debug` 构建类型里的 `versionNameSuffix` 改成 `".debug$customRev"`（后缀必须放在构建类型里，才会排在变体后缀 `.Alpha` / `.Meta` 的后面） |
| `design/src/main/res/layout/design_about.xml` | #44 | 低 | 内层竖向 `LinearLayout`（App 名字和版本号那一层）的 `layout_width` 由 `match_parent` 改成 `0dp`，加 `layout_weight=1`，版本号太长时才会换行 |

共 31 个上游文件（含 `FilesActivity.kt`、`build.gradle.kts`、`design_about.xml`、 `ClashManager.kt`、`IClashManager.kt`、`AndroidManifest.xml`、`CMakeLists.txt`、`MainApplication.kt`、`AppSettingsDesign.kt`、`ProfileProcessor.kt`）。同步后可运行 `bash scripts/check-custom.sh`（#19）自动检查。同步后 `grep -rn CUSTOM` 应在这些文件（加 `custom/` 下新文件）里全部找到。

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
**效果：** 首页"错误与诊断"上面多一个"配置检查"入口。点"ECH测试"（r29 起按钮叫这个名字，原来叫"开始测试"，功能不变）才会真的联网跑一次，检查配置里**写了的**字段有没有效，每项给出结论和原因。这一版只有第一项：节点的 `ech-opts`；r25 起另有"检查 DNS"按钮（见 #25）；其余字段（规则、provider 等）以后逐项加。
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
   - 新文件 `design/.../custom/DnsCheckView.kt`；`DnsCheckText.build()` 改成 `DnsCheckView(...).plain()`（全部展开的文本，复制按钮和诊断包用它，`DiagnosticBundle` 不用改）；`ConfigCheckDesign.showDns()`（和最终生效配置页一样，点击交给文本里的链接）；`ConfigCheckActivity.runDns()` 改调 `showDns`；文案 `check_dns_c_*` `check_dns_g_*` `check_dns_r_*` `check_dns_fold_*` `check_dns_tap_hint`。点一下只重画，不再查询；没有新增定时器。
   - **局限：** 服务器按"角色名"分组，角色名就是 `dns:` 里的键名；`dns.listen` 那一项（内核自己的 DNS 服务器）不在列表里，仍在详情里；没有编译过，要靠 Actions 构建验证。

**对照内核源码（AGENTS.md 第 8 条）：** mihomo 提交 `3f27ef39f8c96bbfbc8b0b9bb10f5c9ef60149d8`（与子模块一致）。`hub/route/dns.go`（`queryDNS`）、`hub/route/cache.go`（两个 flush）、`component/resolver/resolver.go`（`DefaultResolver`、`ClearCache`、`DefaultDNSTimeout`）、`component/resolver/enhancer.go`（`FlushFakeIP`）、`dns/resolver.go`（`ExchangeContext`、`matchPolicy`、`makePolicy`）、`config/config.go`（`parseNameServerPolicy`、`NameServerPolicy` 是有序 map）。内核升级后这些要重新对照。
**局限：** ① 污染判断靠"网络不同"这类规律，没有 IP 归属数据库，CDN 在不同地区给出差别很大的地址时可能被判成"可疑"，所以只有"很可能"才有把握；② 内核解析器的答案可能来自缓存；③ policy 里的 `geosite:` / `rule-set:` 只能列出，不能判断；④ `proxy-server-nameserver-policy` 只用于解析节点域名，没有做匹配。
**功耗：** 点一次查一次 / 清一次，没有定时器、后台任务。
**新增文件：** `core/src/main/golang/native/conftest/dnsextra.go`（纯逻辑：结论、拼写提示、policy 匹配，只依赖标准库）、`dnsextra_test.go`、`dnscore.go`（调内核解析器、清缓存）；`core/src/main/golang/native/ipdelay/policy.go`、`policy_test.go`；`core/.../model/DnsCheckReport.kt` 加 `DnsCheckVerdict` / `DnsCheckFlag` / `DnsCheckPolicy` / `DnsCheckCore`；文案 `check_dns_v_*` `check_dns_core_*` `check_dns_policy_*` `check_dns_why_*` `check_dns_flag_*` `check_flush_*`。
**改了的自己的文件（#22 #25 的）：** `dnscheck.go`（`DNSReport` 加 `Verdict` / `Policy` / `Core`，`RunDNS` 里加三处调用）、`ipdelay.go`（`CaptureDNS` 里加 `setPolicy(parsePolicy(data))`）、`connections.go`、`CustomConnections.kt`、`DnsCheckText.kt`、`ConfigCheckDesign.kt`、`ConfigCheckActivity.kt`、`design_config_check.xml`（输入框改 `AutoCompleteTextView`，加"清缓存"按钮）。

## 27. 保存配置时说清是哪一行，以及"检查配置有没有错"（r26）
**效果：**
- **保存时的错误：** 你在"文件"里用外部编辑器改了 `config.yaml`，回到配置页点保存，上游本来就会让内核先检查（`ProfileProcessor.apply` 里的 `fetchAndValid`），不合格就不替换正在用的配置。以前只弹内核的一句话，现在如果这句话里有"line N"，就在后面加上那一行和上下各一行（`> 12 | 内容`）。没提行号的错误原样抛出。
- **"检查配置有没有错"：** "设置 → 应用 → 配置历史与检查 → 检查配置有没有错"。选一个配置，让内核读它的 `config.yaml`（有**还没保存的编辑**就读那一份，页面会说明）、套上 App 的补丁、解析一遍，但**不载入、不联网下载**，只告诉你能不能通过；不能通过就写原因和那一行。用来在点保存之前先查。
- 外部编辑器是直接写文件的，App 没办法在编辑器"保存"的那一刻拦住它，所以"保存前"指的是：点"保存"时先检查、失败就不替换；以及上面这个随时可用的预检。

**怎么做的：** Go 的 `config.Validate(dir)`（新文件 `native/config/custom_validate.go`）就是 `FetchAndValid` 的校验部分：`UnmarshalAndPatch` → `Parse` → `destroyProviders`，不下载、不 `hub.ApplyConfig`；错误里的 `line N` 用正则取出。通道：`queryCustomConnections(SORT_VALIDATE = 7, 文件夹路径)`，没有新增 JNI 函数、没有给 `IClashManager` 加方法。Kotlin 的 `ConfigErrorText`（service）负责取行号、读文件、拼出上下文，`ConfigTools.check()`（app）负责界面。
**功耗：** 点一次查一次。
**新增文件：** `core/src/main/golang/native/config/custom_validate.go`；`service/.../custom/ConfigErrorText.kt`；`app/.../custom/ConfigTools.kt`；文案 `history_check_*`、service 的 `custom_config_error`。
**改了上游文件：** `ProfileProcessor.kt`（见总表）。
**依赖的上游接口：** `config.UnmarshalAndPatch`、`config.Parse`、`config.destroyProviders`（`native/config` 包里的，和 `FetchAndValid` 用的同一组）。

## 28. 配置历史：保留最近 10 个版本，对比，回滚（r26）
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

## 30. 最终生效配置：你的配置上直接标出内核和 App 改了什么（r28，r29 修正归属和锚点，r31 改排序和颜色）
**效果：** "配置检查"页（#22）第二排按钮多一个"最终生效配置"。点一次，对**正在使用的配置**（已保存的那份 `config.yaml`，不是没保存的编辑）做一次对比，结果显示在页面的文字区里：以**你写的配置为底**，差异直接标在原位，不另开一段：
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
**效果：** #28 的版本列表里，用过且当时代理是通的那个版本后面多 `· ✓ 用过没出错`，回滚时可以优先选它。
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
**效果：** 配置检查页在"最终生效配置 / 清缓存 / 检查 DNS"那一行下面多一个"测试其他 IP"按钮，测**不在配置里**的 IP（r46 起也可以填域名，见 B）。点开是一个对话框：多行输入框填 IP 或域名（每行一个，也可以用逗号、分号、空格分隔，全角的也行；`1.1.1.1:443`、`[2606::1]:443`、`example.com:443` 取其中的地址或域名，端口不用；重复的去掉；既不是 IP 也不是域名的词在结果里列出来），下面选一个节点，两个按钮："直连测 IP"（A）和"套入节点测"（B）。填的 IP 和选的节点记在页面自己的 SharedPreferences（`custom_config_check` 的 `ip_text`、`ip_node`）。结果出来后，**点一行复制这个 IP**，最后一行"复制全部能用的 IP"；右上角的复制按钮复制整页文字。

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

## 45. 测 IP：实测确认是不是 Cloudflare，运营商点一下才查（r48）
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
**局限：** 没有 Kotlin 编译环境，没编译、没在手机上看过，靠 Actions 构建验证。只有竖向惯性。滑行中手指按住停下时，系统会把这一下当成点击，光标可能移到按住的位置。
