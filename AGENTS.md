# AGENTS.md — 给所有在本仓库工作的 AI / 开发者

这是 `MetaCubeX/ClashMetaForAndroid` 的 fork，带少量自定义功能。**最高目标：以后能轻松合并上游更新。**

## 动手前清单（必须照做，每次任务开头先做完）
1. 读本文件。
2. 读 `CUSTOM.md`（现在只是索引，不长）：基线和当前版本、“怎么找”、上游文件总表、功能一览。
3. 按要改的东西找详情，**只读相关的，不要通读 `docs/custom/`**：
   - 要改某个上游文件 → 在总表里找那一行，读行里列的 #N 详情；
   - 要改某个功能 → 在功能一览里找 #N，读 `docs/custom/<N 补零到 3 位>.md`（#7 → `007.md`，#109a → `109a.md`）；
   - 代码里看到 `CUSTOM.md #N` 注释 → 同上，编号就是文件名；
   - 只知道文件名 → `grep -rl 文件名 docs/custom`，命中的详情和 `docs/custom/history/` 里的记录都要看。
4. 回复（或任务说明）开头先写一行：`已读：AGENTS.md、CUSTOM.md、docs/custom/…；要改：#…`。没有这一行就说明没读，不算开始动手。
5. 找不到对应的 #N 就先问用户，不要自己新编号，也不要凭印象改。

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
7. 每加 / 改一个功能，都要登记：新增了哪些文件、改了上游哪些文件和哪几处。登记写在三处：对应的 `docs/custom/<编号>.md` 详情（**原地改写成现在的样子**，不要在后面追加"rXXX 又改了什么"；新功能新建 `docs/custom/<新编号>.md`）、`CUSTOM.md` 功能一览里那一行、以及改了上游文件时开头的"上游文件总表"。`docs/custom/history/` 是旧流水，只读，不再追加。**代码和这些登记必须在同一次提交里一起改，不能只改其一。**
8. **需要对照内核（mihomo）源码时，先给出地址让用户下载给你，不要凭记忆或猜测对照着写。** 子模块没拉下来的环境里看不到内核源码，不要假装知道它的字段名、接口签名、匹配规则；写出来的代码里凡是依赖内核行为的地方，都要能指出是对照哪个文件的哪一段。
9. **文档大小有上限**，由 `scripts/check-custom.sh` 检查（`CUSTOM.md` #157）：`CUSTOM.md` ≤ 64 KB，`docs/custom/` 下每个文件 ≤ 40 KB。超了就在同一次提交里拆开，不要放着；`Check Custom` 工作流变红就先修它。

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
- **`IpTestDialog.ask`（`design/.../custom/IpTestDialog.kt`）已接近 Kotlin 2.1.0 编译器的栈上限。** 它是一个很大的 suspend 函数，里面嵌了很多回调、`try/catch` 和协程；编译器的协程转换器（`CoroutineTransformerMethodVisitor.collectSuspensionPoints`）按方法长度递归，默认栈会 `StackOverflowError`。现在靠 `gradle.properties` 的 `kotlin.daemon.jvmargs=-Xmx3g -Xss256m` 才编得过（`CUSTOM.md` 第 85 步）。
  - 构建日志里出现 `StackOverflowError` 且栈里有 `collectSuspensionPoints`，就是这个问题。
  - **加一个拆一个：** 每往 `ask` 里加一个功能，同一次任务里必须先从 `ask` 里拆出一块，拆出去的比新加的不小，让 `ask` 不再变大。新功能的主体逻辑写在新文件里，`ask` 里只留最少几行调用。
    - 拆和加分成两个提交：先拆（`custom #N：从 IpTestDialog.ask 拆出…`），再加。拆的时候只做原样搬家、不改逻辑；回调之间共用的局部变量（如 `hintExpanded`、`resolveChecks`、`selected`、`input`、`scope`）收进一个小状态类，不要零散传参；协程的取消和作用域（`cancelResolve`、`CancellationException`）仍在同一套里。
    - **回复里必须说清楚：** 这次拆了哪一块、搬到哪个文件、`ask` 拆前拆后各多少行，然后才说加了什么。没写这几项，就说明没按规则做。
    - 同一次交付里又拆又加时，也要写明先后，并说明拆出去的那块没改逻辑；构建失败时先回退新加的，再查拆的。运行时没有副作用，风险只在改的过程里，而且这类错误编译不一定报，要实测。
  - 这个栈设置只影响构建，不影响 App 运行、体积和功耗。

## 交付约定（给用户发文件时）
- 压缩包文件名必须带版本号，格式 `ClashMetaForAndroid-custom-<上游版本>-r<数字>.zip`，每次交付 r 数字加 1。**同时把 `CUSTOM.md` 开头的 `当前版本：` 改成这次的 `<上游版本>-r<数字>`**（Actions 的构建产物名从这一行读，见 CUSTOM.md #40；App 里的版本号末尾的 `-r<数字>` 也从这一行读，见 #44）。
- 增量包只放这次改动过的文件，路径从仓库根目录算起，外面不要再套一层目录；不要打进 `.idea` 等 IDE 文件。
- 每次交付都要同步更新登记（见规则 7）和 `CUSTOM.md` 的 `当前版本：`。
- 增量包里**必须包含"这次被还原成旧内容"的文件**，不能因为"和上一个版本相同"就不放。否则用户仓库里残留的新版会和别的文件冲突（r16 就因此构建失败：`connlist.go` 还带着旧的汇总代码，和新的 `mux.go` 重复定义）。拿不准用户仓库是什么状态时，把这个功能涉及的文件整组都放进去。
- 删除了文件时，在回复里列出"需要手动删除的文件"（覆盖解压不会删文件）。
- 没被明确要求时，不要改文件、不要发文件。
