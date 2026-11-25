> **旧文档，已冻结：不要往里写任何内容，改代码和写文档不要照着它做。合并上游时可以查它，但先看新文档：`docs/v2/`（`upstream.md`、`rules.md`、`changes.md`）。**

基线：上游 `1a4f949213fcbb7f0c9e2a4a21138642d84b8f22`（版本 2.11.36）。
当前版本：2_11_46-r401
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
| `core/src/main/golang/native/tunnel.go` | #1 #7 #37 #147 | 低 | 重加 `ipdelay.TestGroup(name)` 和 `queryTunnelState` 里的 current 字段（`CurrentSet()`、`currentNode` / `currentIp` / `currentCountry` / `currentIps` / `currentMore`），以及 `reach` 字段（`ipdelay.Reach(node)`，#37）；#147 追加 `reachReason` / `reachAt` 两个 JSON 字段 |

其余登记与历史内容保持原样。
