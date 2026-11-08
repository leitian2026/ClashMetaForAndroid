# CUSTOM.md — 自定义功能登记表

基线：上游 `1a4f949213fcbb7f0c9e2a4a21138642d84b8f22`（版本 2.11.36）。
所有挂钩处在代码里都带 `CUSTOM` 注释，可用 `grep -rn CUSTOM` 找全。
规则和同步上游的步骤见 `AGENTS.md`。每次改动都要同步更新本文件（含下面的总表）。

## 上游文件总表（同步上游时冲突只会出现在这里）
| 上游文件 | 对应条目 | 冲突风险 | 冲突时怎么办 |
|---|---|---|---|
| `.github/workflows/build-debug.yaml` | #2 #3 #4 #5 | 中（`on:` 段、Build 步骤） | 以上游为底，重加：只留 `workflow_dispatch`、预下载 Gradle、签名步骤、arm64 参数与上传 |
| `.github/workflows/build-pre-release.yaml` | #2 #3 | 中 | 以上游为底，重加：只留 `workflow_dispatch`、预下载 Gradle |
| `core/src/main/golang/native/tunnel.go` | #1 #7 | 低 | 重加 `ipdelay.TestGroup(name)` 和 `queryTunnelState` 里的三个 current 字段 |
| `core/src/main/golang/native/tunnel/proxies.go` | #1 | 低 | 重加 `IPDelays` 字段和填充行 |
| `core/src/main/golang/native/config/load.go` | #1 | 低 | 重加 `ipdelay.CaptureConfig(...)` |
| `core/src/main/golang/native/delegate/init.go` | #7 | 低 | 重加 `ipdelay.InstallSocketHook()`（必须在上游给 `DefaultSocketHook` 赋值之后） |
| `core/src/main/java/com/github/kr328/clash/core/model/Proxy.kt` | #1 | 低 | 重加 `ipDelays` 参数 |
| `core/src/main/java/com/github/kr328/clash/core/model/TunnelState.kt` | #7 | 低 | 重加三个 `current*` 参数 |
| `design/src/main/java/com/github/kr328/clash/design/component/ProxyView.kt` | #1 | **高**（改了 `onMeasure` / `onDraw`） | 以上游新版为底，按 #1 表格的描述重做 IP 行的高度和绘制，不要硬合 |
| `design/src/main/res/layout/design_main.xml` | #7 | **高**（App 名字 `TextView` 外包了一层 `LinearLayout`） | 以上游新版为底，重加 `custom_container` id，并按 #7 重新包住 App 名字、加红字 `custom_geosite_notice` |
| `design/src/main/java/com/github/kr328/clash/design/MainDesign.kt` | #7 | 低 | 重加 `RefreshCustom`、`customCards`、`setCustomCards()` |
| `design/src/main/java/com/github/kr328/clash/design/MetaFeatureSettingsDesign.kt` | #6 | 低 | 重加 `geoSiteDownload()` 一行 |
| `app/src/main/java/com/github/kr328/clash/MainActivity.kt` | #6 #7 | 低 | 重加 `RefreshCustom -> design.fetch()` 和 `setCustomCards(...)` |

共 13 个上游文件。同步后 `grep -rn CUSTOM` 应在这些文件（加 `custom/` 下新文件）里全部找到。

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
- `core/src/main/java/com/github/kr328/clash/core/model/IpDelay.kt`：数据类（`ip`、`delay`、`country`）。
- `design/src/main/java/com/github/kr328/clash/design/custom/CountryFlag.kt`：国家码转国旗 emoji。
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
- 只接受 `https://` 地址。先下载到临时文件，检查大小 ≥ 1 KB 且首字节为 `0x0A`（geosite.dat 的 protobuf 头），通过后才替换 `geosite.dat`；失败或取消不会动原文件。
- 安装后需重新加载配置（或重启服务）才生效（内核已把规则读进内存）。
- **更新应用后不保留，但会在首页明确提示（常驻，直到重新下载）：** 上游 `MainApplication.extractGeoFiles()` 会在应用更新后把旧于安装时间的 `geosite.dat` 换回内置版本（这部分不改）。下载成功时记录文件修改时间（`installed_at`）；首页判断"记录时间早于应用最后更新时间"时，在首页顶部 App 名字下方显示一行红字"域名集已恢复为内置版本，点此重新下载"（不是卡片），点击跳到"设置 → Meta 特性"。重新下载成功后记录时间变新，红字自动消失；从未下载过（没有记录）则不显示。纯本地判断，只在首页显示 / 刷新时判断一次，无定时、无联网。设置页里的小字提示保留。
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

## 7. 首页：当前节点 IP + 国旗
**效果：** 首页状态卡片下方有一张常显的卡片，显示正在承载流量的节点所用的 IP，前面是它出口国家的国旗，副标题是节点名；点击卡片刷新。没有连接 / 未运行时显示"暂无节点连接"。国旗查不到显示"？"。#6 的 GeoSite 提示不在这里，是 App 名字下方的红字（布局里 `custom_geosite_notice`）。
- **节点怎么定的（r10）：** 遍历内核当前连接（`statistic.DefaultManager.Range`），链路（`Chains()`）里出现的节点名（在配置 `proxies:` 里的）计数，连接数最多的就是"正在用的节点"；没有连接时退回最近一次拨号记录。
- **IP 怎么定的：** 内核每建一个连接都会经过 `dialer.DefaultSocketHook`，这里包了一层，记下每个节点最近拨的 IP。节点只有一个地址就直接用它。`hosts:` 里不属于任何节点 server 的条目（例如 dns 里 DoH 域名映射的 IP）会让内核自己去连，这些 IP 不记入，避免把 DoH 的直连误当成节点使用（r9 的错误来源）。
- **国旗怎么来的：** 该 IP 还没有国家时，首页查询会在后台起一次探测（经该节点、该 IP 请求同样的国家查询服务），结果缓存；探测中显示"？"，再点一下刷新就有。失败 5 分钟内不重试。测速时测到的国家会直接复用。
- **更新时机（无定时器 / 轮询）：** 进入首页、Clash 启动 / 停止、配置变化、点击卡片刷新。
- **功耗：** hook 只在内核本来就要开 socket 时做一次 map 查询；探测只在 IP 变化后首次刷新时发 1~2 个请求。测速和探测自己发起的连接不会被记成"使用中"。

**新增文件**
- `design/src/main/java/com/github/kr328/clash/design/custom/MainCustomCards.kt`：首页当前 IP 卡片，以及 App 名字下方红字提示的显示控制。
- `design/src/main/java/com/github/kr328/clash/design/custom/CountryFlag.kt`（同 #1）。
- 文案追加在 `strings_custom.xml`（`main_*`）。

**改了上游文件（冲突只会出现在这里）**
| 文件 | 改动 |
|---|---|
| `core/src/main/golang/native/delegate/init.go` | import 加 `cfa/native/ipdelay`；`Init` 末尾加 `ipdelay.InstallSocketHook()` |
| `core/src/main/golang/native/tunnel.go` | `queryTunnelState` 里取 `ipdelay.Current()`，响应结构加 `currentNode` / `currentIp` / `currentCountry`（三个字段都带 `omitempty`） |
| `core/src/main/java/com/github/kr328/clash/core/model/TunnelState.kt` | 构造参数加 `currentNode` / `currentIp` / `currentCountry`（默认空串） |
| `design/src/main/res/layout/design_main.xml` | 内容 `LinearLayout` 加 `android:id="@+id/custom_container"`；App 名字 `TextView` 外包一层竖向 `LinearLayout`，其下加红字 `custom_geosite_notice`（默认 gone） |
| `design/src/main/java/com/github/kr328/clash/design/MainDesign.kt` | import `MainCustomCards`；`Request` 加 `RefreshCustom`；加 `customCards` 属性和 `setCustomCards()` |
| `app/src/main/java/com/github/kr328/clash/MainActivity.kt` | `fetch()` 里加 `setCustomCards(clashRunning, state)`；`when` 里加 `RefreshCustom -> design.fetch()` |

**依赖的 mihomo API：** `statistic.DefaultManager.Range` + `Tracker.Chains()`；`dialer.DefaultSocketHook`（在 `delegate.Init` 里被上游赋值，这里在它之后包一层，所以必须在赋值之后调用）。
