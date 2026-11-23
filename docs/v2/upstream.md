> 合并上游时，如果这里没写清楚，可以查旧文档 docs/custom/ 里对应编号的文件；查到的事实补回这里，写短。改代码、写文档不要照着旧文档做。
# 上游文件合并清单

合并只记恢复挂钩；以上游新文件为底按表恢复。

|上游文件|对应功能编号|风险|冲突时怎么办|
|---|---|---|---|
|`.github/workflows/build-debug.yaml`|#2 #3 #4 #5 #14 #18 #20 #40 #54|中|以上游为底，重加：只留 workflow_dispatch、预下载 Gradle、预下载 geo 文件并加 geo 文件缓存兜底（#14 #18）、签名步骤、arm64 参数（含 -x…|
|`.github/workflows/build-pre-release.yaml`|#2 #3|中|以上游为底，重加：只留 workflow_dispatch、预下载 Gradle|
|`gradle.properties`|#85|低|重加末尾的 kotlin.daemon.jvmargs=-Xmx3g -Xss256m（#85：IpTestDialog.ask 是很大的 suspend 函数，Kotlin 2.1.详见旧文档对应编号|
|`core/src/main/golang/native/tunnel.go`|#1 #7 #37 #147|低|重加 ipdelay.TestGroup(name) 和 queryTunnelState 里的 current 字段（CurrentSet()、currentNode / curre…|
|`core/src/main/golang/native/connections.go`|#8 #15 #101|低|重加 queryCustomConnections 的自定义分派和 closeCustomConnection 的自定义关闭分支；#101 在 CANCEL_IPTEST 前加 CAN详见旧文档对应编号|
|`core/src/main/golang/native/tunnel/proxies.go`|#1 #55|低|重加 IPDelays 字段和填充行|
|`core/src/main/golang/native/config/load.go`|#1 #22|低|重加 ipdelay.CaptureConfig(...)（#1）和它后面的 ipdelay.CaptureDNS(rawCfg.DNS)（#22）|
|`core/src/main/golang/native/delegate/init.go`|#7|低|重加 ipdelay.InstallSocketHook(home)（必须在上游给 DefaultSocketHook 赋值之后；home 是 Init 的第一个参数，用来定位 #10详见旧文档对应编号|
|`core/src/main/java/com/github/kr328/clash/core/model/Proxy.kt`|#1|低|重加 ipDelays 参数|
|`core/src/main/java/com/github/kr328/clash/core/model/TunnelState.kt`|#7 #37 #147|低|重加 current* 参数（currentNode / currentIp / currentCountry / currentIps / currentMore）、CurrentI详见旧文档对应编号|
|`design/src/main/java/com/github/kr328/clash/design/component/ProxyView.kt`|#1 #12 #55 #151|高|以上游新版为底，按 #1 表格的描述重做 IP 行的高度和绘制，再按 #12 加最后一行"N 分钟前测速"，不要硬合|
|`design/src/main/res/layout/design_main.xml`|#7 #8 #20 #21 #22 #32 #53 #168|中|以上游新版为底，按 #7 重做：新增变量 customIp、包住 App 名字并加红字 custom_geosite_notice、代理卡片 subtext 改成带 customIp、…|
|`design/src/main/res/layout/design_proxy.xml`|#169|低|顶部工具栏新增 carrier_switch_view，位于 ⚡ 左侧；返回栏的 layout_toStartOf 改为指向新图标；底部悬浮测速按钮不变。|
|`design/src/main/java/com/github/kr328/clash/design/MainDesign.kt`|#7 #8 #20 #21 #22 #32 #53 #146|低|重加 customCards、setCustomCards()；#146 setCustomIssues(IssuesDisplayState)；Request 枚举加 OpenCon…|
|`design/src/main/java/com/github/kr328/clash/design/ProxyDesign.kt`|#169|低|增加 carrier_switch_view 点击挂钩，通过 setClassName 打开运营商映射设置页；运营商入口、测速入口和更多菜单另有三处长按提示。业务逻辑放在新建的 Car…|
|`design/src/main/java/com/github/kr328/clash/design/MetaFeatureSettingsDesign.kt`|#6 #16|低|重加 geoSiteDownload() 一行（#16 起它一次加 GeoSite / GeoIP / ASN 三块，不用再改这里）|
|`app/src/main/java/com/github/kr328/clash/MainActivity.kt`|#6 #7 #8 #20 #21 #22 #35 #53|低|重加 fetch() 里的 setCustomCards(...)、customNodeIpEvents() 和 select 里的 customNodeIp.onReceive 分支…|
|`service/src/main/java/com/github/kr328/clash/service/clash/module/DynamicNotificationModule.kt`|#7 #35|低|通知正文改用 NodeIpText.get(service)（原来是上传/下载速度），去掉不再用的 queryTrafficNow；加 nodeIpChanged 通道、NodeIpN详见旧文档对应编号|
|`service/src/main/java/com/github/kr328/clash/service/clash/module/SuspendModule.kt`|#53|低|复用已有亮灭屏广播，在启动、屏幕状态变化和配置载入后通过 SORT_ISSUES 推送 isInteractive；不新增屏幕监听|
|`service/src/main/java/com/github/kr328/clash/service/ClashManager.kt`|#7 #8 #15 #158 #169|低|queryTunnelState() 开头加 NodeIpNotifier.start(context)；加 queryCustomConnections(sort: Int, fil…|
|`service/src/main/java/com/github/kr328/clash/service/remote/IClashManager.kt`|#8 #15 #158 #169|低|重加 queryCustomConnections(sort: Int, filter: String): String 和 closeCustomConnection(id: Str…|
|`app/src/main/AndroidManifest.xml`|#8 #17 #20 #21 #22 #46 #156 #169|低|在 LogsActivity 前重加 .custom.ConnectionsActivity（#8）、.custom.BackupActivity（#17，必须带 android:th…|
|`app/src/main/java/com/github/kr328/clash/FilesActivity.kt`|#46 #50|低|OpenFile 分支：EditorActivity.canOpen(...) 为真就 startActivity(EditorActivity.intent(..., uuid, "详见旧文档对应编号|
|`design/src/main/res/layout/adapter_profile.xml`|#47 #53 #55|中|以上游新版为底：<data> 里加变量 edit；时间 TextView（elapsed_view）layout_marginEnd 改 20dp、layout_toStartOf 改…|
|`design/src/main/java/com/github/kr328/clash/design/adapter/ProfileAdapter.kt`|#47|低|构造函数末尾加带默认值的 onEditClicked，onBindViewHolder 里加 binding.setEdit { onEditClicked(current) }|
|`design/src/main/java/com/github/kr328/clash/design/ProfilesDesign.kt`|#47|低|Request 加 EditConfig(profile)；创建 ProfileAdapter 时多传一个 lambda（requests.trySend(Request.EditCo详见旧文档对应编号|
|`app/src/main/java/com/github/kr328/clash/ProfilesActivity.kt`|#47 #50|低|when 里加 EditConfig 分支：startActivity(EditorActivity.intent(this@ProfilesActivity, "${uuid}/co详见旧文档对应编号|
|`core/src/main/cpp/CMakeLists.txt`|#7|低|add_library 源文件末尾加 custom_bridge.c|
|`core/src/main/golang/go.mod`|#79 #80|中|以上游版本的依赖选择为准，将 github.com/metacubex/http、github.com/metacubex/quic-go、github.com/metacubex/t…|
|`app/src/main/java/com/github/kr328/clash/MainApplication.kt`|#16 #21|低|onCreate() 里 extractGeoFiles() 后重加一行 GeoFileKeeper.restoreAfterUpdate(this)（#16），在 onCreate(详见旧文档对应编号|
|`design/src/main/java/com/github/kr328/clash/design/AppSettingsDesign.kt`|#17|低|preferenceScreen 块末尾（"服务"那个开关之后）重加一行 customBackup()|
|`service/src/main/java/com/github/kr328/clash/service/ProfileProcessor.kt`|#27 #28 #56 #169 #170|低|apply() 里两处：① fetchProfile(...) 外面包 try { ... } catch (e: Exception) { throw ConfigErrorText…|
|`app/src/main/java/com/github/kr328/clash/LogsActivity.kt`|#31|低|setContentDesign(design) 后加一行 LogLevelControl.attach(this, design)；加一个 import|
|`design/src/main/res/layout/design_logs.xml`|#31|低|在 delete_view（三横线）左边加 log_level_view（ 起是显示当前级别文字的 TextView，原来是图标 ImageView）；标题栏的 layout_toSt详见旧文档对应编号|
|`design/src/main/java/com/github/kr328/clash/design/OverrideSettingsDesign.kt`|#31|低|删掉"日志级别"那一个 selectableList（换成一行 CUSTOM 注释）；入口搬到日志页|
|`build.gradle.kts`|#44|低|重加 queryConfigProperty 后面的 customRev（读 CUSTOM.md 的"当前版本："那一行，取最后的 r<数字>），release 构建类型里的 vers…|
|`design/src/main/res/layout/design_about.xml`|#44|低|内层竖向 LinearLayout（App 名字和版本号那一层）的 layout_width 由 match_parent 改成 0dp，加 layout_weight=1，版本号太长详见旧文档对应编号|
|`service/src/main/java/com/github/kr328/clash/service/ProfileWorker.kt`|#53|低|completed() 和 failed() 末尾各加一行 IssueNotifier.subscription(this, name, null / reason)|
|`design/src/main/java/com/github/kr328/clash/design/view/LargeActionCard.kt`|#57|低|subtext 的 setter 里，binding.subtextView.text = value 改成 binding.subtextView.text = com.github…|
|`design/build.gradle.kts`|#87|低|增加 libs.kotlin.serialization.json 依赖，供配置检查页将最近一轮 IpTestReport 编码 / 解码为本地 JSON；不改插件、构建任务或其他依赖|

|`service/src/main/java/com/github/kr328/clash/service/clash/module/ConfigurationModule.kt`|#169|低|配置成功并发送 ACTION_PROFILE_LOADED 后调用 CarrierSwitch.applyAfterProfileLoaded(service)；只恢复事件挂钩，不改上游载入失败即停止服务的逻辑。|

## 细节
### `.github/workflows/build-debug.yaml`
以上游为底，重加：只留 workflow_dispatch、预下载 Gradle、预下载 geo 文件并加 geo 文件缓存兜底（#14 #18）、签名步骤、arm64 参数（含 -x :app:downloadGeoFiles）与上传、Checkout submodules 之后的 Apply mihomo patches 步骤（#20）、上传前的 Artifact name 步骤和上传步骤里的 name:（#40）、Apply mihomo patches 前的 Remove deleted custom files 步骤（#54）
### `core/src/main/golang/native/tunnel.go`
重加 ipdelay.TestGroup(name) 和 queryTunnelState 里的 current 字段（CurrentSet()、currentNode / currentIp / currentCountry / currentIps / currentMore），以及 reach 字段（ipdelay.Reach(node)，#37）；#147 追加 reachReason / reachAt 两个 JSON 字段
### `design/src/main/java/com/github/kr328/clash/design/component/ProxyView.kt`
以上游新版为底，按 #1 表格的描述重做 IP 行的高度和绘制，再按 #12 加最后一行"N 分钟前测速"，不要硬合
### `design/src/main/res/layout/design_main.xml`
以上游新版为底，按 #7 重做：新增变量 customIp、包住 App 名字并加红字 custom_geosite_notice、代理卡片 subtext 改成带 customIp、删掉代理卡片的 visibility；#8 在"日志"标签前加"连接"标签，#20 在它后面再加"底层连接"标签（Request.OpenMux），#21 在"日志"标签前再加"错误与诊断"标签（Request.OpenErrors），#22 在"错误与诊断"标签前再加"配置检查"标签（Request.OpenConfigCheck）；#32 在顶部（logo + App 名字）那一行末尾加右上角的 ImageView（@+id/custom_more，三点图标，48dp）；同时把包住 App 名字的 LinearLayout（#7 加的那层）改成 layout_width=0dp + layout_weight=1（，原来是 wrap_content，窄屏上名字占满宽度，三点被挤出屏幕）
### `design/src/main/res/layout/design_proxy.xml`
顶部工具栏新增 carrier_switch_view，位于 ⚡ 左侧；返回栏的 layout_toStartOf 改为指向新图标；底部悬浮测速按钮不变。
### `design/src/main/java/com/github/kr328/clash/design/MainDesign.kt`
重加 customCards、setCustomCards()；#146 setCustomIssues(IssuesDisplayState)；Request 枚举加 OpenConnections（#8）、OpenMux（#20）、OpenErrors（#21）、OpenConfigCheck（#22）；#32 重加 customMenu = MainCustomMenu(context, binding.root) 和它的 import
### `design/src/main/java/com/github/kr328/clash/design/ProxyDesign.kt`
增加 carrier_switch_view 点击挂钩，通过 setClassName 打开运营商映射设置页；运营商入口、测速入口和更多菜单另有三处长按提示。业务逻辑放在新建的 CarrierSwitchActivity / CarrierSwitchDesign / 服务侧 CarrierSwitch。
### `app/src/main/java/com/github/kr328/clash/MainActivity.kt`
重加 fetch() 里的 setCustomCards(...)、customNodeIpEvents() 和 select 里的 customNodeIp.onReceive 分支；OpenConnections 分支（#8）、OpenMux 分支和 import（#20）、OpenErrors 分支和 import（#21）、OpenConfigCheck 分支和 import（#22）；#35 流量定时器那一支的条件由 clashRunning 改成 clashRunning && activityStarted
### `service/src/main/java/com/github/kr328/clash/service/clash/module/SuspendModule.kt`
复用已有亮灭屏广播，在启动、屏幕状态变化和配置载入后通过 SORT_ISSUES 推送 isInteractive；不新增屏幕监听
### `service/src/main/java/com/github/kr328/clash/service/ClashManager.kt`
queryTunnelState() 开头加 NodeIpNotifier.start(context)；加 queryCustomConnections(sort: Int, filter: String) / closeCustomConnection(id: String) 两个实现（#8 #15）；#158 通过现有 Clash.subscribeLogcat() 和 ILogObserver 转发下载进度事件；#169 增加 applyCarrierSwitch()，调用服务侧同一套运营商映射逻辑。
### `service/src/main/java/com/github/kr328/clash/service/remote/IClashManager.kt`
重加 queryCustomConnections(sort: Int, filter: String): String 和 closeCustomConnection(id: String): Boolean；#158 增加带 ILogObserver 参数的下载进度请求，复用既有 logcat 事件接口；#169 增加设置保存后应用当前缓存映射的远程方法。
### `app/src/main/AndroidManifest.xml`
在 LogsActivity 前重加 .custom.ConnectionsActivity（#8）、.custom.BackupActivity（#17，必须带 android:theme="@style/BackupTranslucentTheme"，否则页面不透明，对话框后面是黑的）、.custom.MuxActivity（#20）、.custom.ErrorsActivity（#21）、.custom.ConfigCheckActivity（#22）、.custom.SavedIpActivity（#156，标签 @string/saved_ip_title）和 .custom.EditorActivity（#46，必须带 configChanges 里的 orientation|screenSize|...，否则转一下屏页面就重建，没保存的文字丢了）的 <activity>；#169 登记 .custom.CarrierSwitchActivity
### `design/src/main/res/layout/adapter_profile.xml`
以上游新版为底：<data> 里加变量 edit；时间 TextView（elapsed_view）layout_marginEnd 改 20dp、layout_toStartOf 改成 @id/edit_view；在它和那条 2dp 竖线之间加 FrameLayout（@+id/edit_view，铅笔图标 ic_baseline_edit，onClick="@{edit}"）
### `core/src/main/golang/go.mod`
以上游版本的依赖选择为准，将 github.com/metacubex/http、github.com/metacubex/quic-go、github.com/metacubex/tls 和 golang.org/x/sys 从间接依赖提升到直接 require；保持已核对的版本（http v0.1.8、项目锁定的 quic-go、tls v0.1.9、x/sys v0.30.0），不擅自升级。若上游更新这些依赖，先重新核对 http3 API 和模块版本，再同步调整 go.sum。
### `service/src/main/java/com/github/kr328/clash/service/ProfileProcessor.kt`
apply() 里两处：① fetchProfile(...) 外面包 try { ... } catch (e: Exception) { throw ConfigErrorText.explain(context, context.processingDir, e) }（#27，保存失败时报出是哪一行）；② 在 context.importedDir.resolve(snapshot.uuid.toString()).deleteRecursively() 前一行加 if (snapshot.type == Profile.Type.File) ConfigHistory.keep(context, snapshot.uuid)（#28 #56，只给自己编辑的配置记下被替换的和新的版本）；delete() 里清理 ConfigHistory（#56）、ConfigUpdateSnapshot（#170）后调用 CarrierSwitchStore.delete(context, uuid) 清理同 UUID 的运营商映射（#169）；加对应 import
### `build.gradle.kts`
重加 queryConfigProperty 后面的 customRev（读 CUSTOM.md 的"当前版本："那一行，取最后的 r<数字>），release 构建类型里的 versionNameSuffix = customRev，debug 构建类型里的 versionNameSuffix 改成 ".debug$customRev"（后缀必须放在构建类型里，才会排在变体后缀 .Alpha / .Meta 的后面）
### `design/src/main/java/com/github/kr328/clash/design/view/LargeActionCard.kt`
subtext 的 setter 里，binding.subtextView.text = value 改成 binding.subtextView.text = com.github.kr328.clash.design.custom.CheckMark.paint(context, value)（首页代理卡片的 ✓ 绿 / ✗ 红）

