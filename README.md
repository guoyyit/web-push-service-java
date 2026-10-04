# web-push-service-java

一个可在本地独立运行的浏览器推送通知服务，完整覆盖：

- 推送订阅的创建、更新、查询、删除（端点与密钥格式校验，错误可区分）
- VAPID 形式的 ES256 JWT 凭据生成与校验（签名、过期时间、接收方）
- 推送负载端到端加密（ECDH P-256 + HKDF + AES-128-GCM，符合 RFC 8291 / RFC 8188 原语）
- 消息存活时间（TTL）、紧急度（Urgency）、主题（Topic）合并
- 投递失败处理：永久失效自动清理订阅，临时失败按上限与指数退避重试
- 同一订阅的并发投递与订阅变更通过分锁保持状态自洽
- 订阅、VAPID 密钥、待/已投递状态、模拟端点均持久化，进程重启不丢失
- 本地模拟推送端点注册表，不依赖任何外部推送服务或真实浏览器

## 技术栈

- Java 17、Spring Boot 3.4（spring-boot-starter-web）
- 标准 JCA：`EC`（secp256r1/P-256）、`ECDH`、`SHA256withECDSA`、`HmacSHA256`、`AES/GCM/NoPadding`
- Jackson + JSON 文件持久化（原子写：临时文件 + 文件锁 + 原子移动）

## 构建与运行

```bash
mvn spring-boot:run
# 或
mvn clean package
java -jar target/web-push-service-java-1.0-SNAPSHOT.jar
```

服务默认监听 `http://localhost:8080`。

## 配置项（application.properties / 环境变量）

| 配置键 | 默认值 | 说明 |
| --- | --- | --- |
| `server.port` | `8080` | HTTP 端口 |
| `webpush.data-dir` | `data` | 持久化目录（订阅、投递、VAPID 密钥、模拟端点） |
| `webpush.max-retries` | `3` | 单条消息临时失败的最大投递尝试次数（含首次），到顶后 `FAILED_RETRIES_EXHAUSTED` |
| `webpush.retry-backoff-millis` | `500` | 首次重试退避毫秒数 |
| `webpush.backoff-multiplier` | `2.0` | 退避乘数，第 n 次重试等待 `retry-backoff * multiplier^(n-1)` |
| `webpush.dispatch-interval-millis` | `200` | 后台调度扫描间隔 |
| `webpush.vapid-ttl-seconds` | `43200` | VAPID JWT 有效期（12 小时） |

## HTTP 接口

鉴权：除订阅 CRUD 与 VAPID 公钥查询外，发送推送、触发调度、轮换密钥需要
`Authorization: vapid t=<JWT>, k=<publicKey>`（也接受 `Bearer <JWT>` 与裸 JWT）。
JWT 必须为 ES256，且 `aud` 等于请求来源的 `scheme://host[:port]`。

### 服务端 VAPID

- `GET  /api/vapid/public-key` —— 返回当前 VAPID 公钥（base64url，未压缩 P-256）
- `POST /api/vapid/rotate`（需鉴权）—— 轮换服务端密钥并持久化；轮换后旧私钥签发的 JWT 立即失效

### 订阅

- `POST   /api/subscriptions` —— 创建订阅，body：`{endpoint, p256dh, auth}`
- `PUT    /api/subscriptions/{id}` —— 更新端点/密钥
- `GET    /api/subscriptions` / `GET /api/subscriptions/{id}` —— 查询
- `DELETE /api/subscriptions/{id}` —— 删除（同时取消其未完成投递）

校验规则与错误码：

- 端点必须是 `http(s)`、含 host，且路径形如 `/push/{id}`，否则 `INVALID_ENDPOINT`
- `p256dh` 必须是 65 字节未压缩 P-256 点（`0x04` 前缀），否则 `INVALID_PUBLIC_KEY`
- `auth` 必须是 16 字节 base64url；其余参数非法返回 `INVALID_ARGUMENT`
- 订阅不存在返回 `SUBSCRIPTION_NOT_FOUND`

### 推送与投递

- `POST /api/subscriptions/{id}/push`（需鉴权）
  - body：`{payload, ttlSeconds, urgency, topic}`
  - `urgency ∈ {VERY_LOW, LOW, NORMAL, HIGH}`；同 `topic` 的待投递消息只保留最新一条，旧消息置为 `COALESCED`，响应里 `coalescedDeliveryId` 指向被合并的投递
  - 超过 TTL 未投递的消息置为 `EXPIRED`，不会被投递
- `GET /api/subscriptions/{id}/deliveries[/{deliveryId}]` —— 投递状态
- `POST /api/subscriptions/{id}/dispatch-tick`（需鉴权）—— 立即执行一次调度，便于本地验收与测试

投递状态：`PENDING`、`RETRY_WAIT`、`SENT`、`EXPIRED`、`COALESCED`、
`FAILED_PERMANENT`、`FAILED_RETRIES_EXHAUSTED`。

### 模拟推送端点（代替真实浏览器推送服务）

- `POST /mock/endpoints` —— 注册一个模拟订阅方，返回 `{endpointUrl, p256dh, auth, ...}`
- `GET  /mock/endpoints` / `GET /mock/endpoints/{id}` —— 查询（含收到的消息）
- `DELETE /mock/endpoints/{id}`、`DELETE /mock/endpoints/{id}/messages`
- `POST /mock/endpoints/{id}/failures?mode=TEMPORARY|PERMANENT&remaining=N` —— 注入失败
  - `TEMPORARY`：接下来 N 次返回 503，之后恢复
  - `PERMANENT`：持续返回 410，调度器据此删除订阅
- `POST /mock/endpoints/{id}/revoke` —— 模拟用户撤销订阅（410）
- `POST /push/{id}` —— 模拟推送服务接收加密消息的真实端点
- `POST /mock/endpoints/{id}/decrypt?messageIndex=N&expectedAssociatedData=...` —— 用订阅方私钥解密
- `POST /mock/endpoints/{id}/messages/{index}/tamper` —— 测试辅助：翻转密文一个字节

## 本地全链路验收（curl）

```bash
# 1) 注册模拟订阅方
curl -s -X POST http://localhost:8080/mock/endpoints
# 2) 用返回的 endpointUrl/p256dh/auth 创建订阅
curl -s -X POST http://localhost:8080/api/subscriptions \
  -H 'Content-Type: application/json' \
  -d '{"endpoint":"http://localhost:8080/push/<id>","p256dh":"...","auth":"..."}'
# 3) 取 VAPID 公钥并自行用 ES256 生成 aud=http://localhost:8080 的 JWT
curl -s http://localhost:8080/api/vapid/public-key
# 4) 发送推送
curl -s -X POST http://localhost:8080/api/subscriptions/<subId>/push \
  -H 'Authorization: vapid t=<jwt>, k=<pub>' -H 'Content-Type: application/json' \
  -d '{"payload":"你好","ttlSeconds":300,"urgency":"HIGH","topic":"news"}'
# 5) 查看模拟端点收到的密文，并用订阅方私钥解密
curl -s http://localhost:8080/mock/endpoints/<id>
curl -s -X POST "http://localhost:8080/mock/endpoints/<id>/decrypt?messageIndex=0&expectedAssociatedData=push-message:<deliveryId>"
```

测试中通过 `VapidService` 直接签发 JWT，无需第三方库即可完成自动化全链路验收。

## 安全模型

- **端到端机密性与完整性**：每次加密生成一次性临时 P-256 密钥对与 16 字节随机 salt，
  与订阅方公钥做 ECDH；按 RFC 8291 以 `auth` 密钥为 salt 派生 IKM，再按 RFC 8188
  以记录 salt 派生 16 字节 AES-GCM 密钥与 12 字节 nonce。重复加密同一明文，salt、
  临时公钥、密文均不同。
- **认证加密**：AES-128-GCM 带 128 位认证标签；AAD 绑定 salt、临时公钥、订阅方公钥、
  记录序号、记录大小与每条消息唯一的关联数据 `push-message:<deliveryId>`。
  密文或关联数据被篡改时标签校验失败，直接拒绝，不返回任何明文。
- **可区分的解密失败**：
  - `DECRYPT_KEY_MISMATCH`：信封中的订阅方密钥指纹（SHA-256）与持有的私钥不匹配；
  - `DECRYPT_TAMPERED`：GCM 标签校验失败（密文/关联数据/信封字段被改）；
  - `PAYLOAD_EXPIRED`：标签通过但内嵌的过期时间早于当前时间。
  过期时间放在 GCM 保护的内层明文中，攻击者无法篡改而不被发现。
- **服务端身份标识（VAPID）**：ES256 JWT，强制校验算法、ECDSA 签名、`exp` 与 `aud`，
  三者任一不符分别返回 `VAPID_BAD_SIGNATURE`、`VAPID_EXPIRED`、`VAPID_AUDIENCE_MISMATCH`；
  缺少凭据返回 `VAPID_MISSING`。密钥可轮换并持久化，旧凭据在轮换后立即失效。
- **投递安全**：消息 TTL 到期后不投递；同主题仅最新生效；临时失败有次数上限与指数退避；
  404/410 视为永久失效并清理订阅。每个订阅使用独立锁，投递与更新/删除串行化，
  不会向已删除订阅继续投递，也不会静默丢弃并发更新。
- **持久化**：订阅、投递全状态、VAPID 密钥、模拟端点均落盘；写操作为
  “临时文件 + 通道锁 + fsync + 原子移动”，崩溃不会产生半截状态文件。

> 说明：本服务面向本地验收与教学，HTTP 为明文；接入真实环境时应置于 TLS 之后。
> 线格式采用 RFC 8291/8188 的加密原语与密钥派生，但 JSON 信封是本服务约定的表示方式，
> 与真实浏览器推送服务的 `aes128gcm` 二进制记录头不完全相同（已由本地模拟端点对齐）。

## 测试

```bash
mvn clean test
```

覆盖：

- 订阅生命周期与各类输入校验（`SubscriptionServiceTest`）
- VAPID 签发/校验、过期、aud 不匹配、签名篡改、轮换失效、重启保持（`VapidServiceTest`）
- 加解密回环、重复加密密文不同、密钥不匹配、密文/关联数据篡改、负载过期、Unicode、
  JSON 序列化往返（`PayloadCryptoServiceTest`）
- 全链路：加密→投递→订阅方解密、TTL 过期、主题合并、临时失败重试、重试上限、
  永久失败清理订阅、无凭据拒绝、密钥轮换失效、密文篡改（`EndToEndIntegrationTest`）
- 并发：并发发送不丢失/不覆盖、投递与删除并发、主题合并并发（`ConcurrencyIntegrationTest`）
- 重启恢复：订阅与未完成投递、VAPID 密钥跨重启保持并继续投递（`RestartRecoveryIntegrationTest`）
