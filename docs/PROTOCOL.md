# MiDrop 0.2 协议

Mac 为唯一网络监听端，提供 TLS 1.2+ HTTPS。手机主动连接。Bonjour 发布 `_midrop._tcp.`，TXT 中的 `id` 为 Mac 证书 SHA-256 前 24 位，仅作发现标识，不能代替完整证书验证。

## 身份与兼容

- `GET /health` 返回兼容旧版的 `protocol: 1`，新增 `api: 2`、`server_id` 与能力列表。
- `devices.json` 保留原来的令牌哈希 → 设备名称结构，不写入原始令牌；新设备元数据单独保存在 `device_meta.json`。
- `POST /pair` 保留旧版接口，供兼容和回归测试；新版 UI 不展示配对码。
- 新版初次只探测证书和提交请求；Mac 本机批准后，手机取得令牌并固定此前完整证书指纹。请求名称为对端声明，不是账号认证。

## 新建信任

- `POST /trust/request`：`name`、`client_id` → 随机 `request_id`、私密 `ticket`，有效期 60 秒。
- `POST /trust/status`：提交 ID 和 ticket，返回 pending / approved / rejected / expired。只有 approved 才提供令牌，且只保留至短期领取期限。
- 全局每分钟至多五次请求，最多五个待处理请求。过期后不可批准。
- **网络 API 不提供批准操作**。Mac UI 只能通过本机 Unix Socket 的 `approve` 操作批准。

## 会话与双向传输

已信任请求携带 `Authorization: Bearer …`，设备身份从令牌推导，不能通过请求中的设备 ID 选择其他设备。

- `POST /events`：提交是否可接收，记录在线状态，长轮询返回本设备待接收批次。最后心跳超过 60 秒视为离线。
- `POST /upload?name=…`：手机发送原始字节；长度及 `X-Content-SHA256` 必填，`X-Transfer-ID` 可指定取消标识。旧版未携带标识时服务端自动生成。
- 本机 `queue` 操作：选择可信且可接收的手机，异步预校验文件并创建批次，准备完成后等待确认 120 秒。
- `POST /offers/{id}/decision`：本设备 accept / reject。确认前不能获取文件内容。
- `GET /files/{batch}/{file}`：只能由批次所属设备，在已确认且未结束时下载。服务端再次检查源文件大小和修改时间。
- `POST /offers/{id}/ack`：接收端校验并保存后，逐文件提交 ID 与 SHA-256。所有文件确认才完成批次。
- `POST /offers/{id}/cancel`、`POST /transfers/{id}/cancel`：取消本设备的传输；批次中已保存的完整文件保留。

传输状态统一为 preparing、waiting、accepted、transferring、completed、rejected、cancelled、expired、failed。已接受但五分钟无进展的批次失败，不自动续传。

## 本机边界与保存

`~/Library/Application Support/MiDrop` 权限 0700；私钥、凭据、状态和 `control.sock` 权限 0600。

Unix Socket 接受一行 JSON，支持 snapshot、approve、queue、revoke、cancel。状态快照不包含 ticket、原始令牌或可直接授权的秘密。

Mac 临时文件完整接收和校验后，使用不可覆盖的原子硬链接提交到下载目录。Android 10+ 先写入 `IS_PENDING=1` 的 MediaStore 条目，长度与 SHA-256 符合后才发布；取消或失败则删除条目，进程异常退出后下次启动清理记录的未完成条目。

接收成功但确认响应丢失时，文件仍保留，发送端可能显示失败；再次发送会产生副本。服务重启不恢复未完成批次。
