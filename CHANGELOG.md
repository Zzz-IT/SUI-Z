# Changelog

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
