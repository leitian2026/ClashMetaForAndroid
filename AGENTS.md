# AGENTS.md — 给所有在本仓库工作的 AI / 开发者

这是 `MetaCubeX/ClashMetaForAndroid` 的 fork，带少量自定义功能。**最高目标：以后能轻松合并上游更新。**
动手前必须先读本文件和 `CUSTOM.md`（自定义功能登记表 + 改了哪些上游文件的总表）。

## 分支规则
- `main`：只跟随官方上游，**不放任何自定义内容**。
- `custom`：唯一的功能开发分支，也是默认分支。所有改动只提交到这里。
- 子模块（`core/src/foss/golang/clash`，即 mihomo）只同步，**不要擅自修改**。确实要改内核时，只能写成 `patches/mihomo/*.patch`，由 `build-debug.yaml` 的 `Apply mihomo patches` 步骤在构建时应用（见 `CUSTOM.md` #20）；不要往 `.github/patch/` 放（那里的补丁是打到 Go 工具链上的）。

## 改代码时必须遵守（合并友好）
1. 新功能优先**新增文件 / 类 / 页面**，不要重写上游已有逻辑。
2. 必须改上游文件时，只加最少几行"挂钩"，真正的逻辑放在新文件里；挂钩处加 `CUSTOM` 注释方便搜索。
3. 不要格式化、重命名、移动、重排上游代码，不要顺手"优化"无关部分。
4. 字符串 / 资源放进独立的新文件（如 `strings_custom.xml`），不要往上游资源文件里插行。
5. 一个功能一个提交，功能之间互不依赖，方便单独 rebase 或回退。提交信息统一写 `custom #N：简述`（N 是 `CUSTOM.md` 里的编号）。
6. **功耗**：不用定时器 / 轮询，用事件驱动；息屏和后台少干活。需要"测一次"的功能只在用户操作时触发。
7. 每加 / 改一个功能，都要在 `CUSTOM.md` 登记：新增了哪些文件、改了上游哪些文件和哪几处，并更新开头的"上游文件总表"。**代码和 `CUSTOM.md` 必须在同一次提交里一起改，不能只改其一。**
8. **需要对照内核（mihomo）源码时，先给出地址让用户下载给你，不要凭记忆或猜测对照着写。** 子模块没拉下来的环境里看不到内核源码，不要假装知道它的字段名、接口签名、匹配规则；写出来的代码里凡是依赖内核行为的地方，都要能指出是对照哪个文件的哪一段。

## 同步上游的步骤
1. 一次性设置：`git config rerere.enabled true`（记住冲突解法，下次自动套用）。
2. 把 `main` 快进到 `upstream/main`（`main` 不允许出现自定义提交）。
3. 把 `custom` rebase（或 merge）到 `main`。推送 rebase 后的分支只用 `--force-with-lease`，不要用裸 `--force`。
4. 冲突只会出现在 `CUSTOM.md` 总表列出的上游文件里。处理方法：**以上游新版本为底**，再按 `CUSTOM.md` 对应条目的描述重新加上挂钩和 `CUSTOM` 注释；不要为了保留旧改动而丢掉上游的新内容。
5. 合并后检查：
   - `grep -rn CUSTOM` 找到的挂钩数量和总表一致，没有丢；
   - 总表里每个"依赖的上游接口"（mihomo API、`dialer.DefaultSocketHook`、`statistic`、`PreferenceScreen` 等）在新版本里还在；
   - 子模块提交号变了以后，`patches/mihomo/*.patch` 要还能打上（Actions 里 `Apply mihomo patches` 步骤通过）；打不上就对照 `CUSTOM.md` #20 在新内核上重做补丁，不要删掉步骤；
   - 手动触发一次 Actions 的 Build Debug，构建成功再认为同步完成（这里无法本地编译，只能靠它验证）。
6. 同步后更新 `CUSTOM.md` 开头的基线提交号和版本号。

## 构建事实（别误判）
- 日常构建用 `build-debug.yaml`，实际跑的是 `app:assembleAlphaRelease`（**R8 已开**），只构建 arm64-v8a，固定签名。只能手动触发（`workflow_dispatch`）。
- `build-release.yaml`、`update-dependencies.yaml` 没有改动。

## 交付约定（给用户发文件时）
- 压缩包文件名必须带版本号，格式 `ClashMetaForAndroid-custom-<上游版本>-r<数字>.zip`，每次交付 r 数字加 1。
- 增量包只放这次改动过的文件，路径从仓库根目录算起，外面不要再套一层目录；不要打进 `.idea` 等 IDE 文件。
- 每次交付都要同步更新 `CUSTOM.md`。
- 增量包里**必须包含"这次被还原成旧内容"的文件**，不能因为"和上一个版本相同"就不放。否则用户仓库里残留的新版会和别的文件冲突（r16 就因此构建失败：`connlist.go` 还带着旧的汇总代码，和新的 `mux.go` 重复定义）。拿不准用户仓库是什么状态时，把这个功能涉及的文件整组都放进去。
- 删除了文件时，在回复里列出"需要手动删除的文件"（覆盖解压不会删文件）。
- 没被明确要求时，不要改文件、不要发文件。
