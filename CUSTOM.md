# CUSTOM.md — 自定义功能登记表

基线：上游 `1a4f949213fcbb7f0c9e2a4a21138642d84b8f22`（版本 2.11.36）。
所有挂钩处在代码里都带 `CUSTOM` 注释，可用 `grep -rn CUSTOM` 找全。
规则和同步上游的步骤见 `AGENTS.md`。每次改动都要同步更新本文件（含下面的总表）。

## 上游文件总表（同步上游时冲突只会出现在这里）
| 上游文件 | 对应条目 | 冲突风险 | 冲突时怎么办 |
|---|---|---|---|
| `.github/workflows/build-debug.yaml` | #2 #3 #4 #5 #14 #18 #20 | 中（`on:` 段、Build 步骤） | 以上游为底，重加：只留 `workflow_dispatch`、预下载 Gradle、预下载 geo 文件并加 geo 文件缓存兜底（#14 #18）、签名步骤、arm64 参数（含 `-x :app:downloadGeoFiles`）与上传、`Checkout submodules` 之后的 `Apply mihomo patches` 步骤（#20） |
| `.github/workflows/build-pre-release.yaml` | #2 #3 | 中 | 以上游为底，重加：只留 `workflow_dispatch`、预下载 Gradle |
| `core/src/main/golang/native/tunnel.go` | #1 #7 | 低 | 重加 `ipdelay.TestGroup(name)` 和 `queryTunnelState` 里的三个 current 字段 |
| `core/src/main/golang/native/tunnel/proxies.go` | #1 | 低 | 重加 `IPDelays` 字段和填充行 |
| `core/src/main/golang/native/config/load.go` | #1 | 低 | 重加 `ipdelay.CaptureConfig(...)` |
| `core/src/main/golang/native/delegate/init.go` | #7 | 低 | 重加 `ipdelay.InstallSocketHook(home)`（必须在上游给 `DefaultSocketHook` 赋值之后；`home` 是 `Init` 的第一个参数，用来定位 #10 #11 的小文件） |
| `core/src/main/java/com/github/kr328/clash/core/model/Proxy.kt` | #1 | 低 | 重加 `ipDelays` 参数 |
| `core/src/main/java/com/github/kr328/clash/core/model/TunnelState.kt` | #7 | 低 | 重加三个 `current*` 参数 |
| `design/src/main/java/com/github/kr328/clash/design/component/ProxyView.kt` | #1 #12 | **高**（改了 `onMeasure` / `onDraw`） | 以上游新版为底，按 #1 表格的描述重做 IP 行的高度和绘制，再按 #12 加最后一行"N 分钟前测速"，不要硬合 |
| `design/src/main/res/layout/design_main.xml` | #7 #8 #20 #21 #22 | **高**（App 名字 `TextView` 外包了一层 `LinearLayout`；代理卡片改了 `subtext` 并去掉 `visibility`） | 以上游新版为底，按 #7 重做：新增变量 `customIp`、包住 App 名字并加红字 `custom_geosite_notice`、代理卡片 `subtext` 改成带 `customIp`、删掉代理卡片的 `visibility`；#8 在"日志"标签前加"连接"标签，#20 在它后面再加"底层连接"标签（`Request.OpenMux`），#21 在"日志"标签前再加"错误与诊断"标签（`Request.OpenErrors`），#22 在"错误与诊断"标签前再加"配置检查"标签（`Request.OpenConfigCheck`） |
| `design/src/main/java/com/github/kr328/clash/design/MainDesign.kt` | #7 #8 #20 #21 #22 | 低 | 重加 `customCards`、`setCustomCards()`；`Request` 枚举加 `OpenConnections`（#8）、`OpenMux`（#20）、`OpenErrors`（#21）、`OpenConfigCheck`（#22） |
| `design/src/main/java/com/github/kr328/clash/design/MetaFeatureSettingsDesign.kt` | #6 #16 | 低 | 重加 `geoSiteDownload()` 一行（#16 起它一次加 GeoSite / GeoIP / ASN 三块，不用再改这里） |
| `app/src/main/java/com/github/kr328/clash/MainActivity.kt` | #6 #7 #8 #20 #21 #22 | 低 | 重加 `fetch()` 里的 `setCustomCards(...)`、`customNodeIpEvents()` 和 `select` 里的 `customNodeIp.onReceive` 分支；`OpenConnections` 分支（#8）、`OpenMux` 分支和 import（#20）、`OpenErrors` 分支和 import（#21）、`OpenConfigCheck` 分支和 import（#22） |
| `service/src/main/java/com/github/kr328/clash/service/clash/module/DynamicNotificationModule.kt` | #7 | 低 | 通知正文改用 `NodeIpText.get(service)`（原来是上传/下载速度），去掉不再用的 `queryTrafficNow`；加 `nodeIpChanged` 通道、`NodeIpNotifier.start`、一个 `select` 分支 |
| `service/src/main/java/com/github/kr328/clash/service/ClashManager.kt` | #7 #8 #15 | 低 | `queryTunnelState()` 开头加 `NodeIpNotifier.start(context)`；加 `queryCustomConnections(sort: Int, filter: String)` / `closeCustomConnection(id)` 两个实现（#8 #15） |
| `service/src/main/java/com/github/kr328/clash/service/remote/IClashManager.kt` | #8 #15 | 低 | 末尾重加 `queryCustomConnections(sort: Int, filter: String): String` 和 `closeCustomConnection(id: String): Boolean` |
| `app/src/main/AndroidManifest.xml` | #8 #17 #20 #21 #22 | 低 | 在 `LogsActivity` 前重加 `.custom.ConnectionsActivity`（#8）、`.custom.BackupActivity`（#17）、`.custom.MuxActivity`（#20）、`.custom.ErrorsActivity`（#21）和 `.custom.ConfigCheckActivity`（#22）的 `<activity>` |
| `core/src/main/cpp/CMakeLists.txt` | #7 | 低 | `add_library` 源文件末尾加 `custom_bridge.c` |
| `app/src/main/java/com/github/kr328/clash/MainApplication.kt` | #16 #21 | 低 | `onCreate()` 里 `extractGeoFiles()` 后重加一行 `GeoFileKeeper.restoreAfterUpdate(this)`（#16），在 `onCreate()` 里 `val processName` 之前重加 `CrashRecorder.install(this)`（#21）（及 import） |
| `design/src/main/java/com/github/kr328/clash/design/AppSettingsDesign.kt` | #17 | 低 | `preferenceScreen` 块末尾（"服务"那个开关之后）重加一行 `customBackup()` |

共 20 个上游文件（含 `ClashManager.kt`、`IClashManager.kt`、`AndroidManifest.xml`、`CMakeLists.txt`、`MainApplication.kt`、`AppSettingsDesign.kt`）。同步后可运行 `bash scripts/check-custom.sh`（#19）自动检查。同步后 `grep -rn CUSTOM` 应在这些文件（加 `custom/` 下新文件）里全部找到。

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
| `design/src/main/java/com/github/kr328/clash/design/component/ProxyView.kt` | import `R`；`state` 加 setter（`requestLayout()`）；`onMeasure` 增加 IP 行高度；`onDraw` 基础布局改用"去掉 IP 行后的高度"，末尾新增 IP 行绘制 |

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
- `design/src/main/res/layout/preference_geosite_download.xml`：布局。
- 文案追加在 `design/src/main/res/values/strings_custom.xml` 和 `values-zh/strings_custom.xml`（`geosite_download_*`）。

**改了上游文件（冲突只会出现在这里）**
| 文件 | 改动 |
|---|---|
| `design/src/main/java/com/github/kr328/clash/design/MetaFeatureSettingsDesign.kt` | "导入 ASN 数据库"那个 `clickable` 之后加一行 `geoSiteDownload()`（带 `CUSTOM` 注释） |
| `app/src/main/java/com/github/kr328/clash/MainActivity.kt` | 见 #7（r9 起不再调用 `GeoSiteResetNotice.showIfNeeded`，改由首页红字显示） |

**依赖的上游约定（上游改动后先查这些）：** geo 文件目录为 `filesDir/clash`（与 app 模块 `Context.clashDir` 一致，这里直接写成 `context.filesDir.resolve("clash")`）；`PreferenceScreen`/`addElement`/`Preference` 这套设置页接口。

## 7. 当前节点 IP + 国旗（首页代理卡片、状态栏通知）
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

**新增文件**
- `design/src/main/java/com/github/kr328/clash/design/custom/MainCustomCards.kt`：红字提示的显示控制，以及生成代理卡片副标题后缀。
- `app/src/main/java/com/github/kr328/clash/custom/MainCustomRefresh.kt`：`MainDesign.refreshCustom()`。
- `app/src/main/java/com/github/kr328/clash/custom/NodeIpEvents.kt`：首页接收"IP 变化"广播（started 期间注册）。
- `service/src/main/java/com/github/kr328/clash/service/custom/NodeIpText.kt`：通知正文文本（脏标记，变了才重建）。
- `service/src/main/java/com/github/kr328/clash/service/custom/NodeIpNotifier.kt`：订阅内核事件并发广播。
- `core/src/main/java/com/github/kr328/clash/core/custom/NodeIpWatcher.kt`：自己的 JNI 入口 `CustomBridge` + 订阅封装（不改上游 `Bridge.kt`）。
- `core/src/main/cpp/custom_bridge.c`：JNI 函数 `nativeSubscribeNodeIp`（调用 Go 导出的 `subscribeNodeIp`）。
- `core/src/main/golang/native/nodeip.go`：Go 导出 `subscribeNodeIp`，用 `fetch_report` 回调通知。
- `common/src/main/java/com/github/kr328/clash/common/custom/CustomIntents.kt`：广播 action。
- `core/src/main/java/com/github/kr328/clash/core/util/CountryFlag.kt`（同 #1）。
- 文案：`design/.../strings_custom.xml`（`main_*`）、`service/src/main/res/values/strings_custom.xml` 和 `values-zh/strings_custom.xml`（`custom_notification_no_ip`）。

**改了上游文件（冲突只会出现在这里）**
| 文件 | 改动 |
|---|---|
| `core/src/main/golang/native/delegate/init.go` | import 加 `cfa/native/ipdelay`；`Init` 末尾加 `ipdelay.InstallSocketHook(home)`（r11 起带 `home` 参数，见 #10 #11） |
| `core/src/main/golang/native/tunnel.go` | `queryTunnelState` 里取 `ipdelay.Current()`，响应结构加 `currentNode` / `currentIp` / `currentCountry`（三个字段都带 `omitempty`） |
| `core/src/main/java/com/github/kr328/clash/core/model/TunnelState.kt` | 构造参数加 `currentNode` / `currentIp` / `currentCountry`（默认空串） |
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

## 20. 底层连接页：每个节点的底层连接数（多路复用有没有生效）（r16）
**效果：** 首页"连接"下面多一个"底层连接"入口，打开后是单独的一页（连接页本身不变）。页面里每个节点一行，节点下面按对端 IP 拆开，所有行一次显示（超出屏幕高度时整页滚动，没有行数上限），字号、颜色、行距和连接页的概览行一致：
```
节点 A · 底层 5 条 / 请求 40 个
    🇺🇸 162.159.153.2 · 3 条
    104.18.2.5 · 2 条
节点 B · 底层 1 条 / 请求 12 个 · UDP 2 个
```
- 页面顶部一行说明文字（小字）；右上角刷新按钮；代理没运行时显示"代理未运行"，没有连接时显示"当前没有连接"。
- **刷新时机：** 打开 / 回到页面、点刷新、Clash 启动停止、以及页面在前台且代理在运行时每 2 秒一次（和连接页同一套：页面进入后台后定时分支不再参与 `select`，没有任何后台唤醒）。答案和上次一样就不解析、不重画。
- **底层 N 条**：内核真正为这个节点开着的 TCP socket 数（和终端里 `ss` 看到的出站连接一致）；开了多路复用（smux 等）或 gRPC / h2 时，N 远小于请求数。**请求 M 个**：内核连接列表里由这个节点承载的连接数（连接的路径里第一个名字就是承载它的节点）。
- 每个 IP 一行：该节点现在有几条连到这个 IP；国旗只有已经知道（测速或 #7 探测过）时才显示，不额外查询。没有连接的 IP 不列。
- **UDP**：只在节点行末尾写 `UDP u 个`（该节点开着的 UDP socket 数，包括 QUIC 类节点）。UDP socket 创建时对端地址常常还不知道，所以不按 IP 拆。QUIC 类节点（hysteria、tuic）整个会话在一个 UDP socket 上，底层数本来就是 1 左右，不能用来判断复用。
- 没有归属节点的 socket（DNS 等）归到"其他（不属于任何节点）"。链式代理（`dialer-proxy`）只算真正的物理 socket，算在第一跳节点上，里层节点会显示 `底层 0 条 / 请求 M 个`。
- 节点按底层数从多到少排，最多 30 个节点、每个节点最多 12 个 IP（节点行的数字是全部的合计，不受截断影响）。
- 打开了 smux 的 `statistic: true` 时，承载连接本身也会登记成内核连接，并算进"请求"数（也会让首页的流量统计翻倍，那个选项本来是调试用）。
- **关闭网站后数字不会马上归零**：空闲的复用连接会保留一段时间才断开，不等于复用没生效。

**怎么数的（改了内核，见下）：** 内核里所有出站 TCP / UDP 连接都经过 `component/dialer` 的 `DialContext` / `ListenPacket`。补丁在这两个函数外面各包一层：socket 建成时按"节点名 + 对端 IP + 网络"登记一次，关闭时注销一次（`Close` 里注销，幂等；没关就被回收时由 finalizer 注销）。节点名来自 `Base.DialOptions()` 新加的 `WithOwner(b.name)`，所有节点类型共用这一个入口，smux 的承载连接也是经节点自己的拨号建的，所以同样会被数到。经自定义拨号器（如 `dialer-proxy` 的 `netDialer`）得到的连接不是本进程的 socket，不计。读数只在这一页查询时做（`dialer.ConnCounts()`）。
- **走的通道：** 复用 #8 的 `queryCustomConnections(sort, filter)`：`sort` 取 2（`SortMux`）时，`connections.go` 里的导出函数改为返回 `connlist.QueryMux()`（只数连接、不转换，也不碰连接页的缓存），所以没有新增 JNI 函数、没有给 `IClashManager` 加方法。
- **功耗：** 没有定时器、轮询、后台线程；建立和关闭 socket 时各一次带锁的 map 加减；数据只在这一页前台查询时读一次。连接页不受影响（它的查询不带这些）。
- 包装类型内嵌了真正的 `*net.TCPConn` / `*net.UDPConn`，所以它们的全部方法（`SyscallConn`、`ReadMsgUDP`、`CloseWrite`、`ReadFrom` 等）都还在，只有 `Close` 多做一步；唯一变化是类型断言 `.(*net.UDPConn)`，内核里对拨号结果这样断言的只有 `common/net/packet`，不是 `*net.UDPConn` 时它自己有通用后备。

**新增文件**
- `patches/mihomo/0001-conn-count.patch`：给 mihomo 打的补丁（**内核文件不直接改，构建时由 workflow 应用**）。内容：新文件 `component/dialer/conncount.go`（登记表、包装类型、`DialContext` / `ListenPacket`、`WithOwner`、`ConnCounts`）；`component/dialer/dialer.go` 里原来的 `DialContext` / `ListenPacket` 改名为 `dialContextRaw` / `listenPacketRaw`（各一行）；`component/dialer/options.go` 的 `option` 结构体加 `owner string`；`adapter/outbound/base.go` 的 `DialOptions()` 末尾加 `dialer.WithOwner(b.name)`。
- `core/src/main/golang/native/connlist/mux.go`：`QueryMux`、`MuxNode` / `MuxIp` / `MuxSnapshot`、`SortMux`；`ipdelay.go`（#1 #7 的文件）加 `CountryOf(node, ip)`（只读缓存）；`connections.go`（#8 的文件）里 `queryCustomConnections` 加一个 `SortMux` 分支；`core/.../custom/CustomConnections.kt` 加常量 `SORT_MUX`。
- `core/src/main/java/com/github/kr328/clash/core/model/MuxSnapshot.kt`（`MuxNode` / `MuxIp` / `MuxSnapshot` 和解析）。
- `design/.../custom/MuxDesign.kt`、`MuxText.kt`（把节点列表排成文字）、`res/layout/design_mux.xml`；文案 `mux_*`、`connections_mux_*`。
- `app/src/main/java/com/github/kr328/clash/custom/MuxActivity.kt`。

**改了上游文件：** `.github/workflows/build-debug.yaml`（`Checkout submodules` 和它的缓存步骤之后加 `Apply mihomo patches` 步骤，对子模块目录 `patch -p1`，先 `--dry-run`，打不上就让构建失败；它**不是** `Apply Patches` 那个对 `GOROOT` 打补丁的上游步骤）；首页入口：`design_main.xml`（"连接"后面加一个 `LargeActionLabel`）、`MainDesign.kt`（枚举加 `OpenMux`）、`MainActivity.kt`（一个 `OpenMux` 分支和 import）、`AndroidManifest.xml`（`MuxActivity`），见总表。
**依赖的上游接口（升级内核后先查这些）：** `dialer.DialContext` / `dialer.ListenPacket` 的签名、`option` 结构体和 `applyOptions`、`Base.DialOptions()`、`dialer.Dialer` 的 `Opt`（`WithOption(d.Opt)` 会带上 `owner`）、`statistic.Tracker.Chains()`。补丁每个文件只有几行 diff，对不上时按上面的描述在新内核上重做。
**已知限制：** 补丁是对着 mihomo Alpha `478af2a57c48bdb8e85f6ed93b2c3735e90ceabd` 写的，并确认能干净地打上；实际构建用的子模块提交是 `3f27ef39f8c96bbfbc8b0b9bb10f5c9ef60149d8`，**没有对照过它的源码**，也没有本地编译过（Go 代码只做了人工检查）。第一次 Actions 构建失败时，先看 `Apply mihomo patches` 步骤的输出。

## 21. 错误与诊断页（r18）
**效果：** 首页"日志"上面多一个"错误与诊断"入口。页面把**已经存在的数据**排成文字，一页看清"哪里出错、什么原因"，四块（哪块没内容就不显示）：
1. **软件崩溃 / 异常退出**：时间、进程、线程、版本和堆栈前 8 行（Java 崩溃，由 `CrashRecorder` 记录）；加上 Android 自己记录的"原生崩溃（内核异常退出）/ 无响应 ANR / 内存不足被系统结束 / 进程初始化失败"（Android 11 以上，`getHistoricalProcessExitReasons`）。
2. **节点测速超时**：当前配置所有代理组里最近一次测速超时的节点（`delay` 大于 `Short.MAX_VALUE`，0 是没测过，不算）。
3. **连接失败**（按节点分组，失败多的在前）：每条写时间、TCP/UDP、目标 `host:port`、命中的规则、次数 `×n`，下一行是"原因：<中文解释>（内核原文：…）"。网站打不开就在这里：内核每次拨号失败本来就写一行 `[TCP] dial <节点> (match <规则>) <源> --> <目标> error: <原因>`，这里只是把它拆开显示。
4. **其他警告 / 错误**：内核的其他 WARNING / ERROR 日志原文（配置、DNS、订阅更新等），能识别原因时附一行中文解释。
- 原因归类（`errlog.classify`）：超时、被拒绝、被重置、对方提前关闭、域名解析失败、网络不可达、TLS / 证书、认证失败、被拒绝（规则）、其他；中文 / 英文解释在 `strings_custom.xml` 的 `errors_code_*`。认不出的原因直接显示内核原文。
- 右上角：复制全部、清除记录、刷新。文字可以长按选择。代理没运行时只显示崩溃记录，并说明连接失败和节点超时要代理运行时才能看。
- **局限：** ① 连接失败记录在内核进程里，只保留最近 300 种（相同的合并计数），内核重启后重新计数；② 订阅更新失败、服务启动失败等 App 本来就弹提示的错误没有被另外保存，只有它们同时写进内核日志时才会出现在第 4 块；③ Go 内核自己崩溃时 Java 来不及记录，靠 Android 的退出记录（Android 11 以上）；④ 内核日志行格式没有逐版本核对，不符合的行会按"其他"显示原文，不会丢。

**怎么做的（没有任何新采集）：**
- Go：新包 `core/src/main/golang/native/errlog/`。`init()` 里订阅内核的 `log.Subscribe()`（和 `log.go` 同一个机制；订阅能收到所有等级，不受用户设的日志等级影响），只留 WARNING / ERROR，放进 300 条的环（相同的 种类 + 节点 + 目标 + 原因 合并，次数 +1，时间更新）。阻塞在 channel 上的一个 goroutine，没有日志就不占 CPU。
- 通道：复用 #8 / #20 的 `queryCustomConnections(sort, filter)`：`sort` 取 3（`SORT_ERRORS`）时 `connections.go` 返回 `errlog.Query()`；`closeCustomConnection("!errors")`（`CLEAR_ERRORS`）清空记录。没有新增 JNI 函数，没有给 `IClashManager` 加方法。
- 崩溃：`CrashRecorder.install()` 在 `MainApplication.onCreate()`（两个进程都会走）里设置默认未捕获异常处理器：真崩溃时写一个小文本文件到 `filesDir/custom/crash/`（最多 10 个），然后交给原来的处理器，崩溃行为不变。平时什么都不做。
- 页面：`ErrorsActivity` 只在打开 / 回到页面、代理启动停止、配置加载、点刷新时读一次；**没有定时器**。答案不变时不重画。

**新增文件**
- `core/src/main/golang/native/errlog/errlog.go`、`errlog_test.go`。
- `core/src/main/java/com/github/kr328/clash/core/model/ErrorSnapshot.kt`（`ErrorEntry` / `ErrorSnapshot` / `CrashRecord` / `ExitRecord`）；`core/.../custom/CustomConnections.kt`（#8 的文件）加 `SORT_ERRORS`、`CLEAR_ERRORS`；`connections.go`（#8 的文件）加 errors 分支和清除。
- `design/.../custom/CrashRecorder.kt`、`ErrorsReport.kt`、`ErrorsText.kt`、`ErrorsDesign.kt`、`res/layout/design_errors.xml`；文案 `errors_*`。
- `app/src/main/java/com/github/kr328/clash/custom/ErrorsActivity.kt`。

**改了上游文件：** `design_main.xml`、`MainDesign.kt`、`MainActivity.kt`、`AndroidManifest.xml`（入口，同 #20）；`MainApplication.kt`（一行 `CrashRecorder.install(this)` 和 import），见总表。

## 22. 配置检查页（r20）
**效果：** 首页"错误与诊断"上面多一个"配置检查"入口。点"开始测试"才会真的联网跑一次，检查配置里**写了的**字段有没有效，每项给出结论和原因。这一版只有第一项：节点的 `ech-opts`；其余字段（DNS、规则、provider 等）以后逐项加。
- **输入框：** 填一个确定被封的域名（例如 `xxx.workers.dev`），必须由节点的服务器（或它前面的 CDN）托管。只在测试里当内层 SNI 用，**不改配置**；最后填的域名记在页面自己的 SharedPreferences（`custom_config_check`）。
- **怎么测：** 对每个写了 `ech-opts` 的节点，向它的 `server:port` 各做一次只到 TLS 握手为止的连接：开 ECH（用配置里写的 `config`）和关 ECH，内层 SNI 都是输入的域名。用 Go 标准库 TLS，所以"服务器是否接受了 ECH"是 TLS 自己报告的（`ConnectionState().ECHAccepted`），不是靠"连上了"去猜。拨号用内核的 `dialer.DialContext`（socket 不走 VPN）。不校验证书，只看握手能不能通过。每次握手最多等 6 秒，最多同时测 4 个节点。
- **结论：** 生效且绕过封锁（开能连上、关被封）；生效但网络没封这个域名（无法证明绕过）；服务器拒绝了配置（`ECHRejectionError`，配置过期或不匹配）；开了不通而关了能通；两种都不通（无法判断，可能服务器不托管这个域名）；`ech-opts` 没写 `config`（本测试不支持从 DNS 取）；`enable` 不是 true。失败原因沿用 #21 的原因归类，另加 `echrejected`。
- **局限：** ① 测的是"这份 ECH 配置 + 这个服务器在当前网络下能不能藏住 SNI"，不是节点完整协议的连接；② 只读 `ech-opts` 里直接写的 `config`；③ 代理没运行时没有已载入的配置，页面显示"代理未运行"；④ 取消只是不再等结果，核心里的握手会自己在超时内结束；⑤ 构建要用支持 ECH 的 Go（构建用 1.26，没问题；`go.mod` 写的 1.20 只影响 `go vet` 的版本提示，不影响编译）。
- **功耗：** 没有定时器，没有后台任务，点一次测一次。

**新增文件**
- `core/src/main/golang/native/conftest/conftest.go`、`conftest_test.go`（`Run`、判定逻辑、握手）；`ipdelay.go`（#1 #7 的文件）加 `RawProxies()`；`errlog.go`（#21）加 `Classify`；`connections.go`（#8 的文件）加 `SortCheck` 分支；`CustomConnections.kt` 加 `SORT_CHECK`。通道仍是 `queryCustomConnections`，`filter` 带请求 JSON `{"domain":"..."}`，没有新增 JNI 函数。
- `core/.../model/ConfigCheckReport.kt`。
- `design/.../custom/ConfigCheckDesign.kt`、`ConfigCheckText.kt`、`res/layout/design_config_check.xml`；文案 `check_*`、`errors_code_echrejected`；`ErrorsText.explain` 改为公开。
- `app/.../custom/ConfigCheckActivity.kt`。

**改了上游文件：** `design_main.xml`、`MainDesign.kt`、`MainActivity.kt`、`AndroidManifest.xml`（入口，同 #20 #21），见总表。
