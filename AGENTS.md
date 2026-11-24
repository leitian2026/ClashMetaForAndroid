# AGENTS.md — 给所有在本仓库工作的 AI / 开发者

这是 `MetaCubeX/ClashMetaForAndroid` 的 fork，带少量自定义功能。**最高目标：以后能轻松合并上游更新。**

## 动手前清单（必须照做，每次任务开头先做完）
1. 读本文件和 `docs/v2/rules.md`。
2. 改代码、写文档：只看 `docs/v2/rules.md`、`docs/v2/upstream.md`、`docs/v2/changes.md`，不看旧文档，不照着旧文档做。
3. 合并上游：先看 `docs/v2/upstream.md`；新文档没写清、或冲突解决不了时，可以去 `docs/custom/` 查对应编号的旧文档，查到的事实补回 `docs/v2/upstream.md`（只补事实，写短）。找不到编号先问用户。
4. 要改某个功能时，读 `docs/v2/changes.md` 对应编号；找不到编号先问用户，不要自行编编号。
5. 回复开头写明已读的规范文档和本次要改的功能编号。

## 分支规则
- `main`：只跟随官方上游，**不放任何自定义内容**。
- `custom`：唯一的功能开发分支，也是默认分支。所有改动只提交到这里。
- 子模块（`core/src/foss/golang/clash`，即 mihomo）只同步，**不要擅自修改**。确实要改内核时，只能写成 `patches/mihomo/*.patch`，由 `build-debug.yaml` 的 `Apply mihomo patches` 步骤在构建时应用（见 `docs/v2/changes.md` #20）；不要往 `.github/patch/` 放（那里的补丁是打到 Go 工具链上的）。

## 修改规范
合并友好、功耗、缓存及文档登记统一遵守 `docs/v2/rules.md`，不要在本文件另行维护重复规则。

## 同步上游的步骤
1. 一次性设置：`git config rerere.enabled true`（记住冲突解法，下次自动套用）。
2. 把 `main` 快进到 `upstream/main`（`main` 不允许出现自定义提交）。
3. 把 `custom` rebase（或 merge）到 `main`。推送 rebase 后的分支只用 `--force-with-lease`，不要用裸 `--force`。
4. 冲突只会出现在 `docs/v2/upstream.md` 列出的上游文件里。处理方法：**以上游新版本为底**，再按 `docs/v2/upstream.md` 对应行重新加上挂钩和 `CUSTOM` 注释；不要为了保留旧改动而丢掉上游的新内容。
5. 合并后检查：
   - `grep -rn CUSTOM` 找到的挂钩数量和总表一致，没有丢；
   - `docs/v2/upstream.md` 总表里每个"依赖的上游接口"（mihomo API、`dialer.DefaultSocketHook`、`statistic`、`PreferenceScreen` 等）在新版本里还在；
   - 子模块提交号变了以后，`patches/mihomo/*.patch` 要还能打上（Actions 里 `Apply mihomo patches` 步骤通过）；打不上就对照 `docs/v2/changes.md` 的 #20 在新内核上重做补丁，不要删掉步骤；
   - 手动触发一次 Actions 的 Build Debug，构建成功再认为同步完成（这里无法本地编译，只能靠它验证）。
6. 同步后更新 `CUSTOM.md` 开头的基线提交号和版本号。

## 构建事实（别误判）
- 日常构建用 `build-debug.yaml`，实际跑的是 `app:assembleAlphaRelease`（**R8 已开**），只构建 arm64-v8a，固定签名。只能手动触发（`workflow_dispatch`）。
- `build-release.yaml`、`update-dependencies.yaml` 没有改动。
- `IpTestDialog.ask` 曾因 suspend 函数过长触发 Kotlin 2.1.0 编译器 `StackOverflowError`；#175 已拆分该函数及相关长函数。当前 Build Debug 已在 `kotlin.daemon.jvmargs=-Xmx3g`（未设置 `-Xss`）下成功。
- 后续如再次出现编译器栈溢出，优先拆分导致问题的长函数；不得仅凭历史问题恢复 `-Xss256m`，是否需要额外栈设置以当前 Build Debug 实测结果为准。

## 提交方式（直接在仓库里改的 AI 照这个做）
- 默认：直接提交到 `custom` 分支，用户自己手动构建。不开分支、不开 PR、不 force push，不打包、不发增量包。
- 不要因为工具拿不到 Git tree SHA、没有“一次提交多个文件”的接口，就停下来或向用户要 tree SHA。工具一次只能提交一个文件时，就逐文件提交：每个文件一个提交，每次先读该文件在 `custom` 上的最新内容再改，不要覆盖别人的改动。
- 开工前先告诉用户：要改哪些文件、提交总数。提交信息写 `custom #N：简述（第 k/总数）`。每完成一个回复“第 k/总数 个提交完成”并附提交号。超过总数还没完就说明卡住了，要停下来说明卡在哪。
- 同一个功能的提交连续做完，`docs/v2/` 登记文件放在最后。`CUSTOM.md` 开头的 `当前版本：` 只在这次任务的最后一个提交里加 1（先读现在是多少）；只改文档、不影响构建的任务不加。
- 只有完全无法提交（读不了文件、写入被拒）时才停下，把接口返回的原话发给用户。不要自己改成开分支、开 PR 或打包。

## 交付约定（只有用户明确要压缩包时才用）
默认不发压缩包，见上面“提交方式”。下面只在用户明确要压缩包时适用。
- 压缩包文件名必须带版本号，格式 `ClashMetaForAndroid-custom-<上游版本>-r<数字>.zip`，每次交付 r 数字加 1。**同时把 `CUSTOM.md` 开头的 `当前版本：` 改成这次的 `<上游版本>-r<数字>`**（Actions 的构建产物名从这一行读，见 `docs/v2/changes.md` 的 #40；App 里的版本号末尾的 `-r<数字>` 也从这一行读，见 `docs/v2/changes.md` 的 #44）。
- 增量包只放这次改动过的文件，路径从仓库根目录算起，外面不要再套一层目录；不要打进 `.idea` 等 IDE 文件。
- 每次交付都要同步更新 `docs/v2/` 登记和 `CUSTOM.md` 的 `当前版本：`。
- 增量包里**必须包含"这次被还原成旧内容"的文件**，不能因为"和上一个版本相同"就不放。否则用户仓库里残留的新版会和别的文件冲突（r16 就因此构建失败：`connlist.go` 还带着旧的汇总代码，和新的 `mux.go` 重复定义）。拿不准用户仓库是什么状态时，把这个功能涉及的文件整组都放进去。
- 删除了文件时，在回复里列出"需要手动删除的文件"（覆盖解压不会删文件）。
- 没被明确要求时，不要改文件、不要发文件。
