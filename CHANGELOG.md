# Changelog

## v1.1.6

> 相比 v1.1.5：修掉十余处会让权限卡死、设置闪退或进程被误杀的隐患；日志不再刷屏，配置不再重复写盘。

### 对比 v1.1.5

| 场景 | v1.1.5 | v1.1.6 |
| --- | --- | --- |
| Shell 模式下的授权弹窗 | 没有超时，回调不复用即不回收，可能一直挂着 | 10 秒超时，回调用完立刻释放 |
| 多个应用同时连接 | 遍历连接表时可能抛 `ConcurrentModificationException` | 加锁，遍历副本 |
| 注入 Settings 时 | `application` 还没就绪就直接用，空指针闪退 | 空安全，延后取用 |
| 写 `sui_uids.txt` | 直接覆写，断电或并发读取可能拿到半截内容 | 临时文件 + `fsync` + 原子改名 |
| Shell 端重复注册 token | 旧 pid 的 token 覆盖新的，后续请求被拒 | 同 pid 幂等，死进程自动失效 |

### 看门狗

- 锁文件原先在子 shell 里写 `$$`，记下的是父进程 pid，等于「永远认为已有实例在跑」。改为记录真实后台进程，并在锁的持有者已死时直接接管，不再双开或误杀。
- 5 分钟内重启超过 5 次则退避 300 秒，崩溃循环不会反复唤醒设备（v1.1.5 已有 120 秒稳态巡检，这里补的是异常路径）。

### 底层

- binder hook 改为幂等：重复注入不再叠加第二层 hook。
- 隐藏检查失败时日志限流为 10 秒一条，不再每次 binder 调用刷一行。
- adb root 先校验 ELF 头再执行，不再把任意文件当 `adbd` 拉起。
- 读 `/proc/<pid>/cmdline` 读到文件尾，长命令行不再被截断成错误的进程名。
- 未知 uid 的隐藏检查改为失败关闭（宁可拒绝，不放行）。

### 测试

- `test_watchdog.sh` 的 POSIX 语法检查扩展到 `post-fs-data.sh`；`ShellConfigRulesTest` 新增 2 项断言，锁死 Java 与 native 两侧的目录名常量一致。

## v1.1.5

### Optimized
- 显著降低看门狗空闲耗电：`service.sh` 由每 5 秒 `pidof` 轮询改为缓存 pid + `read` 内置命令校验 `/proc`，稳态巡检间隔 120 秒。原先约 17,000 次/天的进程派生降至约 720 次/天，且稳态下不再扫描 `/proc`。
- 为看门狗加入崩溃循环退避：5 分钟内重启超过 5 次则退避 300 秒，避免守护进程异常时整机被反复唤醒。
- `post-fs-data.sh` 中 API ≤ 23 的 oat 权限修复循环由 10Hz 无上限（约 864,000 次 `chmod -R`/天）改为 2 秒间隔、最多 300 次后自行退出。
- 收敛管理界面内存占用：`AppIconCache` 的 Bitmap LRU 由堆的 1/4 改为 1/16 并夹在 [8MB, 64MB]。512MB 堆上从 128MB 降至 32MB。
- 合并管理界面重复线程池：`AppIconCache` 与 `AppLabelCache` 各自创建 `cores/2` 个线程（8 核机共 8 条常驻后台线程），改为共用 `UiWorkerDispatcher` 一个有界池（上限 2 条）。
  - 该界面是注入到 Settings / SystemUI 进程的，这些开销都由用户并未主动打开的进程承担。
- 统一重试退避曲线：新增 `RetryPolicy`，把散落在 `BridgeServiceClient`（服务端与 UI 端两处）、`ManagerProcess`、`SuiApk` 中的硬编码 `Thread.sleep(1000)`、`postDelayed(..., 1000)` 与局部 `retryDelay *= 2` 收敛为一条共享曲线（起始 100ms，倍率 2，单次上限 30s，总超时 10s）。
  - 这些重试路径只在"服务尚未就绪"时触发，属冷启动偶发事件；原先无论服务状态是否变化都按固定间隔唤醒，且多处各自维护一套曲线容易漂移。
  - **注意这不是把轮询改成回调**：桥接 Binder 只在崩溃时才断，没有稳定事件源可订阅（原本已用 `linkToDeath` / `DeathRecipient` 驱动重连）。此处统一的是"重试时的等待时长"，并保证总超时后可终止。
- `AppListBuilder` 的 `GET_PERMISSIONS` 改为按需请求。原先无条件设置，导致 PackageManager 把每个 app 的完整权限列表序列化跨 Binder 传输后被丢弃，每次列表刷新都付一次；现仅在"仅显示 Shizuku 应用"筛选开启时才请求（新增 `AppQueryFlags`）。

### Fixed
- 修正看门狗单实例锁记录 pid 错误的问题：原先在子 shell 内写 `$$`，展开的是父进程 pid，导致锁文件恒指向已死进程、永远检测不到活的同类守护进程。改为在父 shell 中获取并从 `$!` 取后台进程 pid。
- `SuiApk` 等待服务端 Binder 的循环原先固定 `sleep(1000)` × 10 次，且超时后仍继续执行并要求 `openApk()` 非空（会抛 NPE）。改为按 `RetryPolicy` 退避并在总超时后明确抛出 `IllegalStateException`。
- UI 端 `BridgeServiceClient.requestBinderFromBridge()` 原先吞掉 `InterruptedException` 且不恢复中断标志，导致调用方无法感知取消；现在恢复中断状态并返回 null。
- **修复失效操作被静默抑制的问题**：`SuiService.shouldInvalidateUid()` 用 `System.currentTimeMillis()` 计算 2 秒去抖窗口。墙钟在 NTP 校正或用户改表时会回跳，使 `now - last` 变为负数，被读作"时间还没到"，从而**无限期抑制**该 uid 的包失效操作——app 会带着过期权限继续运行，且没有任何日志。改为 `SystemClock.elapsedRealtime()`（单调），并在时钟异常时"失败开放"（放行而非抑制）。
- 修复 `lastInvalidationTime` 无界增长：原先只有 `put` 没有 `remove`。改为有界结构（窗口过期清扫 + 4096 条硬上限 + 包移除时主动清理）。
- `Uninstaller` 的 30 秒等待 deadline 同样改用 `elapsedRealtime()`，避免墙钟前跳导致等待提前结束、快捷方式残留。

### Added
- 新增 `scripts/test_watchdog.sh`：在构建主机上以假文件系统驱动真实 `service.sh`，覆盖健康态、崩溃恢复、`sui_shell` 误判、cmdline 回退、崩溃退避、锁文件与 POSIX 兼容性，共 16 项断言。
- 新增 `scripts/baseline.sh`：采样 `sui` / `sui_shell` 的 PSS、CPU jiffies、次缺页、自愿上下文切换与系统进程派生数，输出 CSV 供构建间对比。
- 新增 `scripts/verify_tests_ran.sh`：读取 JUnit XML 统计实际执行用例数，为零则失败。
- 新增 `ShellConfigRules` 及 18 项单元测试，将 shell 工作目录名校验与解析抽为 Java 侧单一定义，`SuiConfigManager` 不再自带一份重复实现。
  - 注：`sui_main.hpp` 中的原生副本无法消除（Java 与 native 之间无共享代码路径），已在两处互留注释指明对应关系，并由 `ShellConfigRulesTest` 固定 Java 侧行为——两者一旦不一致，shell 服务端将无法解析自己的 `sui_uids.txt`，且失败是静默的。
- 新增 `CacheSizing` 及 12 项单元测试，把图标缓存容量与工作线程数的上限从 `AppIconCache` / `AppLabelCache` 中抽出并加测试固定。
- 新增 `UiWorkerDispatcher`，作为管理界面唯一的有界后台线程池。
- 新增 `RetryPolicy` 及 11 项单元测试，统一服务端与 UI 端的重试退避曲线；测试覆盖单调性、上界、退化输入（负数/超时）与常量自洽性。
- 新增 `AppQueryFlags` 及 9 项单元测试；常量经 `javap` 核对 android-36 `android.jar`，测试锁定精确位模式与两 flag 不重叠。
- 新增 `DebouncePolicy` 及 12 项单元测试，覆盖窗口边界、时钟回跳的"失败开放"、过期判定与 `shouldRun` 的一致性，以及窗口值未被顺带改掉（钉住 2000ms）。

### Changed
- CI `unit-tests` 工作流新增"实际执行用例数"校验与看门狗行为测试；`static-guards` 新增测试源文件存在性、看门狗间隔与无上限轮询断言。
  - **背景**：此前 `unit-tests` 工作流执行 `:module:testDebugUnitTest`，但仓库中不存在任何测试源，该 job 长期"永远绿但从不验证任何东西"。

## v1.1.4

### Fixed
- 修复了 Shell 服务 Binder 死亡时误清空注册 Token 导致无法重新连通的问题。
- 修复了 `BridgeServiceClient.getService()` 缺乏双重检查锁引发的多线程并发 IPC 及重复 unlink 风险。
- 修复了 `bridge_service.cpp` 中 JNI 抛出未捕获异常时直接调用 `recycle()` 导致 Native 崩溃隐患。

### Optimized
- 优化了主线程获取快捷方式 Token 的等待时长（从 2000ms 降至 200ms），防止 UI 假死与 ANR 风险。

## v1.1.3

### Changed
- 将前端生成的桌面快捷方式名字改为 SUI Z。
- 优化快捷方式生成的提示文案，去掉冗余字样并在生成成功时给予明确提示。

### Fixed
- 修复了 `sendBinder()` 中更新 `rootServerPid` 和 `shellServerPid` 的竞态条件，确保 Pid 赋值与 Binder 设置保持原子性，提高 `isTrustedServerDelegate()` 的判断稳定性。
- 移除了 `system_server` (BridgeService) 中的重试 `sleep`，优化为无阻塞快速路由。
- 默认在 Release 版关闭 DEBUG 和 VERBOSE 日志输出，使异常输出受控，显著减少 Binder 高频路径的日志量。
- 修复了 UserService / GKD 路径的等待机制，改进了重试逻辑（包含 deadline、backoff 及负缓存策略），应对系统时间跃变时更加稳定。
- 修复 Binder 死亡回调逻辑，防止误清新的 Binder，处理了潜在的内存泄露和竞态问题。

## v1.1.2

### Added
- 支持 KernelSU/Magisk/APatch 自动更新检测，使用 raw.githubusercontent.com 托管更新配置 `sui_zygisk.json`。
- 在 release 自动化工作流中，支持自动生成 update JSON，上传为 Release 附件，并自动回写提交至仓库默认分支。

## v1.1.1

### Fixed
- 修复了 GKD 客户端 Shizuku 用户服务的连接兼容性，支持通过调用 UID (calling UID) 自动路由 Binder 请求。
- 移除了启动 User Service 时冗余的 `--server-uid` 命令行参数。
- 增强了远程 Binder 事务 (`transactRemote`) 的异常安全性，确保即使 transact 抛出异常也必然通过 `try-finally` 结构恢复调用方的 Identity，防止 Identity 泄露。
- 新增了 `targetBinder == null` 的拦截校验。

## v1.1.0

### Changed
- 优化了 BridgeService 中对于客户端获取 binder 的兼容性及重试机制（修复 GKD 等客户端部分连接失败问题）。
- 全面更新项目名称为 SUI Z，添加了核心贡献者并在 UI 界面中予以展示。
- 更新重写了说明文档（README.md）。
- 增强并完善了 CI smoke_check 测试流程与错误日志诊断。

## v1.0.0

### Added
- 添加正式版发布流程：推送 tag 自动构建并发布 ZIP。
- 添加 CHANGELOG 驱动的 GitHub Release notes。
- 添加 reusable smoke check（scripts/smoke_check.sh）。
- 添加 bridge token 注册/验证完整闭环。
- 添加 shell delegate token 二次校验。

### Changed
- 优化 bridge 协议：SEND_BINDER / REGISTER_TOKEN 均返回业务 accepted 状态。
- 优化 root/shell binder 注册流程，token 失败时阻断后续注册。
- 优化启动阶段：root service 不再阻塞等待 SystemUI/Settings 60 秒。
- 优化 shell config reload：批量权限修改只执行最后一次 reload。
- 优化 Starter / Uninstaller：添加超时避免无限等待。
- TOML / Gradle wrapper / AGP 版本对齐上游。

### Fixed
- 修复 binder 注册成功状态误判（transact 成功但业务拒绝）。
- 修复 manager UID 后台刷新后未同步到 system_server 的问题。
- 修复 shell sync 旧任务仍可能写文件的问题。
- 修复 UI 包名/namespace 非法字符（rikka.SUI Z → rikka.sui）。
- 修复 CMake 宏双重引号（ZYGISK_MODULE_ID）。
- 修复 bridge_service.cpp goto 跨变量初始化。
- 修复 BuildConfig 包名引用错误（rikka.sui.ui → rikka.sui）。
- 修复 NewApi lint 错误（ConcurrentHashMap.compute / File.toPath）。

### Verification
- CI build / lint / package-check / static-guards / unit-tests 通过。
- release ZIP 通过 scripts/smoke_check.sh（module.prop / 4 ABI × 5 so）。
