# SUI Z

当前版本：**v1.1.6**。详细变更请见 [CHANGELOG.md](CHANGELOG.md#v116)。

基于 Sui 现代化重构的 Zygisk 模块。提供以 Root/Shell 身份调用 Android Framework API 和运行独立 Java 服务的 Shizuku API。

SUI Z 不向 PATH 中添加二进制文件，也不会在桌面暴露常规启动图标；管理界面以内置 sui.apk 形式随模块提供。只需安装模块并重启，即可在隐蔽的状态下为支持 Shizuku API 的应用提供特权服务。

## 简介

SUI Z 主要提供两项能力：
1. 直接使用 Android Framework API（就像在 Root 或 Shell 下通过 Java 调用系统 API 一样）。
2. 在 Root 或 Shell 下启动由应用定义的 AIDL Java 服务。

这使得开发特权 Android 应用变得非常舒适。相比于传统的 `su` 命令（需要解析文本输出，且只能调用系统预置的命令行工具），使用 SUI Z 可以直接通过 Binder 通信进行结构化数据的传递，速度更快，能力也更强。

## 安装指南

**要求：**
- Android 6.0+（API 23+）。
- Magisk 24.0+，且开启了 Zygisk。
- 或 KernelSU / APatch 环境下搭配 Zygisk Next、ReZygisk 等 Zygisk 实现。
- **注意：请勿将 SystemUI（系统界面）和 Settings（设置）加入到 Zygisk 的 DenyList（排除列表）中，否则管理界面将无法正常工作。**

**安装步骤：**
1. 在 [Releases](https://github.com/Zzz-IT/SUI-Z/releases) 页面下载最新版本的 ZIP 压缩包。
2. 打开您的 Root 管理器（Magisk / KernelSU / APatch），选择"从本地安装"并刷入下载的 ZIP。
3. 重启设备。

**升级到 v1.1.6 及以上：**
- 权限弹窗不再卡死：Shell 模式下的授权请求加了 10 秒超时，用完立刻回收。
- 设置界面不再闪退：注入时若 `application` 尚未就绪，原先会直接空指针。
- 看门狗不再误杀：单实例锁原先记的是父进程 pid，可能双开或错误接管。
- 写配置不再怕断电：`sui_uids.txt` 改为临时文件 + `fsync` + 原子改名。
- 日志更安静：隐藏检查失败时 10 秒最多一条，不再逐次刷屏。

**升级到 v1.1.5 及以上：**
- 空闲耗电显著降低：看门狗的 `pidof` 轮询由每 5 秒改为稳态 120 秒，日均进程派生数从约 17,000 降至约 720。
- 失效抑制已修复，卸载时会清理残留的桌面快捷方式。

## 唤出管理界面

SUI Z 默认不会在桌面上创建图标。可以通过以下几种方式唤出 SUI Z 的管理界面：
- 在系统设置中长按右上角或某处（具体视系统而定），部分设备上可以直接看到 SUI Z 入口。
- 在默认拨号器中输入 `*#*#784784#*#*`。
- 在 KernelSU / Magisk 管理器中，点击 SUI Z 模块上的 **操作（Action）** 按钮。
- （推荐）在唤出一次管理界面后，点击右上角菜单，选择**“添加快捷方式到桌面”**，以后即可通过桌面图标直接打开。

## 权限管理模式

- **询问 / 默认（Ask / Default）**：应用会弹窗请求权限。
- **允许 Root（Allow root）**：应用将被授予 Root 权限下的服务。
- **允许 Shell（Allow shell）**：应用将被授予 Shell 权限下的服务。
- **拒绝（Deny）**：拒绝应用使用 SUI Z。
- **隐藏（Hide）**：对目标应用彻底隐藏 SUI Z 存在。

---

# SUI Z (English)

Current version: **v1.1.6**. See [CHANGELOG.md](CHANGELOG.md#v116) for the full change log.

Modern superuser interface implementation for Android. Forked and refactored from Sui.

## Introduction

SUI Z provides Java APIs, namely the [Shizuku API](https://github.com/RikkaApps/Shizuku-API), for root / shell apps. It mainly provides two abilities:
1. Use Android Framework APIs directly, almost as if calling system APIs from Java as root or shell.
2. Start an app-defined AIDL-style Java service under root or shell.

SUI Z does not add binaries to PATH and does not expose a regular launcher icon; the management UI is bundled as sui.apk inside the module.

## Installation

**Requirements:**
- Android 6.0+ (API 23+).
- Magisk 24.0+ with Zygisk enabled.
- Or KernelSU / APatch with a Zygisk implementation (e.g., Zygisk Next, ReZygisk).
- **DO NOT add SystemUI and Settings to the Zygisk DenyList, otherwise the management UI will not work.**

**Steps:**
1. Download the latest ZIP from [Releases](https://github.com/Zzz-IT/SUI-Z/releases).
2. Install the ZIP from your Root Manager (Magisk / KernelSU / APatch).
3. Reboot.

**Upgrading to v1.1.6 or above:**
- The permission dialog no longer hangs: shell-mode requests now time out after 10 s and are released immediately.
- No more crash when injecting into Settings if `application` is not ready yet (previously a null pointer).
- The watchdog no longer kills the wrong process: the single-instance lock used to record the parent pid.
- Power-loss safe config writes: `sui_uids.txt` is written to a temp file, fsynced, then renamed.
- Quieter logs: hidden-check failures are rate-limited to one line per 10 s.

**Upgrading to v1.1.5 or above:**
- Idle power use dropped significantly: watchdog `pidof` polling went from 5 s to 120 s steady state, cutting daily forks from ~17,000 to ~720.
- Package invalidations are no longer silently dropped when the wall clock jumps backward; uninstall now also removes leftover home-screen shortcuts.

## Management UI

SUI Z does not show an app icon in the launcher by default. You can open the management UI via:
- Enter `*#*#784784#*#*` in the default dialer app.
- Tap the **Action** button on the SUI Z module in your Root Manager.
- Once opened, you can tap the menu in the top-right corner to **Add a shortcut to the home screen**.

## License
SUI Z is licensed under GPL-3.0-or-later.
