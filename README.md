# MiDrop 0.2

无需账号的 Mac ↔ 小米 / Android 局域网文件分享。Mac 常驻菜单栏，手机接入系统分享菜单；首次在 Mac 点一次允许，之后自动重连。

![MiDrop 两端界面预览](previews/overview.png)

## 获取源码

```bash
git clone https://github.com/dustkxx/MiDrop.git ~/Codes/MiDrop
cd ~/Codes/MiDrop
```

仓库包含源码、图标与测试记录。SDK、模拟器、签名密钥、配对凭据和 `dist/` 构建产物均不提交；构建方法见下文。首次自行构建 Android 时会生成本机签名密钥；后续升级需使用同一密钥。

## 安装与升级

- Mac 当前版本为 **0.2.1**：状态变化时才刷新界面，降低常驻待机 CPU。Android 为 **0.2.1**（versionCode 3）：修复右上角三点菜单在部分字体下只显示一个点的问题。
- Mac：打开 `dist/MiDrop.app`，菜单栏会出现新的双弧标识。点击它展开小面板。
- 手机：安装 `dist/MiDrop-android.apk`，覆盖原版即可。**不需要卸载，也不要清除应用数据。**
- 原有证书、配对凭据和 Android 签名密钥已保留。新连接流程和双向传输需要两端都更新到 0.2。
- 本机 Mac 构建依赖 Command Line Tools 提供的 `/usr/bin/python3`。当前产物为本机签名版本，未做 Developer ID 公证。

## 第一次连接

1. 两台设备连接同一 Wi-Fi / 局域网，打开 Mac 菜单栏 MiDrop。
2. 手机打开 MiDrop，点击发现的 Mac。
3. Mac 小面板出现手机请求，点击 **允许并记住**。不需要输入配对码。
4. 手机按提示允许通知，以便在后台确认接收；在设置中可暂停后台接收。

已连接过的手机升级后会继续使用原凭据。证书或设备信任被清除时，需要重新允许连接。

## 手机发送到 Mac

在相册或文件管理器选择文件 → 分享 → **MiDrop** → 点击已信任的 Mac 卡片。

也可以在手机 MiDrop 中点「选择文件」。文件默认保存到 Mac 的 `~/Downloads/MiDrop`。

## Mac 发送到手机

在菜单栏 MiDrop 小面板选择在线手机，拖入文件，或点击拖入区域选择文件。手机收到请求后：

- App 在前台：显示接收弹层。
- App 在后台：显示系统通知，点击 **接收 / 拒绝**。
- 每批文件均需手机确认，确认前不下载文件内容；两分钟未处理会过期。

手机保存位置为 **Download/MiDrop**，可在系统文件管理器查看，也可从 MiDrop 设置打开最近收到的文件。

## 界面与设置

主面板只显示设备、当前传输和最近文件。地址、指纹、取消设备信任和日志入口都在 Mac 齿轮设置中；Android 右上角「•••」打开设置。

Mac 关闭小面板后仍在后台接收；在底部菜单选择「退出 MiDrop」才会停止。Android 的后台接收依赖系统前台服务，关闭 App 页面后可继续等待请求，但系统强制停止应用会中断连接。

通知权限、通知渠道、勿扰模式及小米系统的后台限制会影响提示。后台提示使用系统通知，不强制拉起全屏窗口，也不申请悬浮窗权限。若手机不可接收，Mac 会显示接收暂停或离线；停止进程后最长约一分钟更新离线状态。

## 传输行为

- 本地 HTTPS 加密，首次由 Mac 人工允许建立信任，后续固定完整证书指纹。
- 保留原文件字节，单文件上限 20 GiB。支持多文件、中文文件名和空文件。
- SHA-256 校验通过才发布文件；失败或取消会清理未完成内容。
- 重名文件另存副本，保留已有内容。失败重试从头开始，已成功的文件可能产生重名副本。
- Android 10+ 使用 MediaStore 保存到 Download/MiDrop，无需全部文件访问权限；Android 8–9 需要单独允许保存权限。
- 当前手机保存一台 Mac 的信任，Mac 可信任多台手机。仅支持局域网 IPv4，不支持互联网中转或断点续传。
- Wi-Fi 访客隔离或防火墙可能阻止连接。手动地址入口只用于自动发现失败，不能绕过网络隔离。

## 构建

```bash
cd ~/Codes/MiDrop

# 矢量母版 → Mac icns 与 Android 图标（更改图标时执行）
bash scripts/build-icons.sh

# Mac
bash scripts/build-mac.sh

# Android：本机已验证的独立 SDK 构建，无需 Gradle / Android Studio
JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home bash scripts/build-android-sdk.sh
```

Android SDK 位于项目 `.sdk/`；保留 `.tools/debug.keystore`，后续 APK 才能直接覆盖安装。另提供 `android/` Gradle 项目，版本号与直接构建保持同步；本轮实际验证的是独立 SDK 构建路径。

下载工具支持进程级代理或指定 Google 域名解析，不修改系统网络设置：

```bash
python3 scripts/download-sdk.py --proxy http://127.0.0.1:7890
# 或使用 dl.google.com 当前有效的 IPv4 独立直连
python3 scripts/download-sdk.py --google-ip <当前有效IPv4>
```

## 验证与预览

协议集成测试：

```bash
PYTHONPYCACHEPREFIX=/private/tmp/midrop-pycache python3 -m unittest discover -s tests -v
```

`previews/` 提供 Mac 明暗主题原生界面渲染及 Android 实际测试截图；具体结果与待验证项目见 `docs/VALIDATION.md`。图标母版为 `assets/logo.svg`。

Mac 状态监听测试：`bash scripts/test-mac-state-monitor.sh`。`previews/mac-idle-cpu-report.json` 记录同机空闲 CPU 对比；短时间采样不代表长期平均能耗。

`docs/PROTOCOL.md` 记录 API 与本机控制边界。源码结构为 `mac/`、`android/app/`、`assets/`、`scripts/` 和 `tests/`。
