# CUSTOM.md — 自定义功能登记表

基线：上游 `1a4f949213fcbb7f0c9e2a4a21138642d84b8f22`（版本 2.11.36）。
所有挂钩处在代码里都带 `CUSTOM` 注释，可用 `grep -rn CUSTOM` 找全。

## 1. 代理页：每个 IPv4 的测速结果

**效果：** 在"代理"页点 ⚡（或右上角测速）后，节点卡片下方多出几行 `IP  延迟`。
- IP 来源：节点 `server` 在配置 `hosts:` 里对应的 IP 列表；没有 `hosts` 条目就实时解析节点域名。
- 只测 IPv4。`server` 本身就是单个 IP 的节点、`ip-version: ipv6` 的节点不显示（没有可比较的对象）。
- 仅支持 `proxies:` 里直接写的节点；订阅 / provider 里的节点不显示。
- 失败或超时显示"超时 / Timeout"。
- 功耗：只在用户手动测速时才跑，不轮询，不跟随息屏恢复时的自动健康检查（`healthCheckAll`）。

**新增文件**
- `core/src/main/golang/native/ipdelay/ipdelay.go`：逐 IP 测速逻辑（临时复制节点、把 server 换成该 IP、经节点拨号请求测速 URL）。
- `core/src/main/java/com/github/kr328/clash/core/model/IpDelay.kt`：数据类。
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
