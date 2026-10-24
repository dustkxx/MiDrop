# MiDrop 0.2 验证记录

## Android 0.2.1 菜单修复（2026-10-24）

- 将文字三点替换为 24dp 矢量图标，提供 48dp 点击区域及明暗主题点击反馈，不随系统字体缩放而换行。
- 在独立 Android 16 模拟器覆盖安装成功，检查浅色普通字体、深色 1.5 倍字体截图，三点均完整显示；点击能打开设置。
- APK 版本为 0.2.1 / versionCode 3，v2/v3 签名验证通过，签名证书与原版相同。两套 Android 构建配置已同步。
- 本次未连接真实手机，HyperOS 上的显示需覆盖安装后确认。本次只修改菜单显示，没有重跑完整传输测试。
- 截图：`previews/android-menu-fixed-light.png`、`previews/android-menu-fixed-large-dark.png`、`previews/android-menu-settings.png`。

## 0.2.0 验证

验证日期：2026-10-22。构建为 Mac 原生 App 与 Android API 35 目标 APK；Android 运行验证在 **API 36 / Android 16 的独立 AOSP arm64 ATD 模拟器**进行，不等同于 HyperOS 真机验收。

## 已通过

| 类别 | 实际验证 |
| --- | --- |
| 协议回归 | 23 项真实 HTTPS / Unix Socket 集成测试通过；包含原版全部 13 项测试 |
| 首次连接 | 独立同签名测试 APK 调用实际 Android Activity 连接流程；待批准时没有令牌，Mac 本机批准后手机持久化令牌和证书指纹，全程不输入配对码 |
| Mac → Android | 确认前不发布文件；确认后完成多文件、中文名、空文件和 32 MiB 内容接收 |
| Android → Mac | 使用真实 MediaStore 内容 URI 启动上传服务，32 MiB 文件回传后 SHA-256 与原文件一致 |
| 取消与拒绝 | 限速测试设备后取消正在接收的文件，未发布 MediaStore 条目被清理；后台拒绝请求后不下载内容 |
| 重名 | MediaStore 创建新的条目 / 文件，旧内容保留；Mac 不覆盖既有文件 |
| 通知与在线状态 | 关闭测试设备的通知权限后，Mac 将其标记为不可接收；恢复权限并打开 App 后恢复 |
| Android UI | 最新 APK 的明暗主题、圆角接收弹层及 1.3 倍字体截图已检查；确认操作仍可完成接收 |
| APK 升级 | 模拟器实际安装 0.1 APK 后覆盖安装 0.2，版本号由 1 升至 2，私有偏好数据逐字节不变；两个 APK 的签名证书相同 |
| Mac 运行与升级 | 已启动新版接收端，HTTPS API 2 可访问；原来的证书和设备凭据文件未变化，已有一台设备信任记录保留；本机 Socket 权限 0600 |
| Mac UI 与图标 | 360×480 原生小面板明暗主题渲染检查通过；图标母版、icns、自适应与单色资源已打包；App 代码签名验证、APK v2/v3 签名与 zipalign 检查通过 |
| 中断恢复 | Mac 下次启动仅清理自己保留命名空间内的私有未完成文件，正常接收文件不受影响；Android 启动清理记录的未发布条目 |

## 仍需真机验证

- REDMI Turbo 4 Pro，国行 HyperOS 3.0.305.0 / Android 16 上的系统分享入口和实际 Wi-Fi Bonjour 自动发现。
- 真实网络切换、Mac 重启后端口变化时的自动重新发现。
- HyperOS 的后台限制、长时间待机和锁屏通知横幅表现。系统勿扰、通知渠道和省电策略可能影响显示，应用不强制弹全屏窗口。
- Android 8–9 的旧存储权限及保存分支；本轮重点运行验证为用户所用的 Android 16。
- 接近 20 GiB 上限的长时间传输。本轮实际大文件传输为 32 MiB，另对大小边界做了协议测试。

当前没有通过 USB 连接的真实手机，以上项目没有标记为通过。

## 可复现材料

- `tests/test_receiver.py`：协议、授权边界、隔离、反向传输、校验与恢复。
- `scripts/emulator-smoke.py`：仅允许 `emulator-5556` 的独立测试；真实 App 下载、上传、拒绝、取消和通知权限验证。
- `scripts/emulator-smoke.py --visual-only`：只复查最终 UI 和接收动作，生成明暗与大字体截图。
- `scripts/build-qa-instrumentation.sh`、`scripts/emulator-trust-smoke.py`：单独构建测试 APK，不向正式 App 加入测试入口。
- `scripts/emulator-upgrade-smoke.py`：只在独立模拟器验证 0.1 → 0.2 覆盖升级。
- `previews/*-report.json`：自动化运行结果；截图位于同一目录。

Mac 预览使用 NSHostingView 的原生界面配合演示设备和文件数据；Android 预览来自最新 APK 的实际运行，ATD 默认不绘制 UI，测试中仅为该独立模拟器启用了绘制。没有修改真实手机、系统代理或生产配对数据。
