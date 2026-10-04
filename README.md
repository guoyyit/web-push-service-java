# Web Push Service (Java)

一个本地可运行的浏览器推送通知服务，完整覆盖推送订阅管理、VAPID 身份凭据、负载端到端加密与投递调度。全链路在本地闭环：推送端点由内置的本地模拟端点注册表（`EndpointRegistry`）提供，不依赖任何外部推送服务或真实浏览器。

## 快速开始

```bash
mvn spring-boot:run          # 启动服务（默认 8080 端口）
mvn test                     # 运行全部 39 个自动化测试
```

### 端到端调用示例

```bash
# 1. 注册一个本地模拟推送端点
curl -X POST localhost:8080/api/mock-endpoints
# => {"endpoint":"endpoint://<id>"}

# 2. 创建订阅（p256dh 为订阅方 P-256 公钥，auth 为 >=16 字节的 base64url 密钥）
curl -X POST localhost:8080/api/subscriptions -H 'Content-Type: application/json' -d '{
  "endpoint": "endpoint://<id>",
  "p256dh": "<65 字节未压缩点的 base64url>",
  "auth": "<base64url 密钥>"
}'

# 3. 获取服务端 VAPID 凭据（本地验收辅助接口）
TOKEN=$(curl -s -X POST localhost:8080/api/keys/token | jq -r .token)

# 4. 发送推送（携带 VAPID JWT）
curl -X POST localhost:8080/api/push -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{
    "subscriptionId": "<订阅 id>",
    "payload": "hello",
    "ttlSeconds": 3600,
    "urgency": "high",
    "topic": "news"
  }'

# 5. 查看端点收到的加密消息
curl localhost:8080/api/mock-endpoints/<id>/messages
```

## REST API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/subscriptions` | 创建订阅（校验端点与公钥格式） |
| GET | `/api/subscriptions/{id}` | 查询订阅 |
| GET | `/api/subscriptions` | 列出订阅 |
| PUT | `/api/subscriptions/{id}` | 更新订阅（版本号递增） |
| DELETE | `/api/subscriptions/{id}` | 删除订阅并清理其待投递消息 |
| POST | `/api/push` | 发送推送（需 `Authorization: Bearer <VAPID JWT>`） |
| GET | `/api/keys/public` | 查询服务端 VAPID 公钥 |
| POST | `/api/keys/token` | 签发一个 VAPID JWT（本地验收辅助） |
| POST | `/api/keys/rotate` | 轮换服务端密钥（旧凭据立即失效） |
| POST | `/api/mock-endpoints` | 注册本地模拟端点 |
| GET | `/api/mock-endpoints/{id}/messages` | 查看端点收到的加密消息 |

错误响应统一为 `{"code": "...", "message": "..."}`，`code` 可区分：`INVALID_ENDPOINT`、`INVALID_KEY`、`VAPID_MISSING`、`VAPID_MALFORMED`、`VAPID_SIGNATURE_INVALID`、`VAPID_EXPIRED`、`VAPID_AUDIENCE_MISMATCH`、`SUBSCRIPTION_NOT_FOUND`、`MESSAGE_EXPIRED` 等。

## 安全模型

### VAPID 请求凭据

- 服务端持有 P-256（secp256r1）密钥对，私钥持久化在数据目录，公钥通过 `/api/keys/public` 公开。
- 请求凭据为 JWT（`alg=ES256`），包含 `aud`（接收方标识）、`exp`（过期时间）、`sub` 三项声明。
- 校验逐项进行：签名（ES256）→ 过期时间（允许 60 秒时钟偏移）→ 接收方标识，任一不通过即拒绝，错误码分别为 `VAPID_SIGNATURE_INVALID`、`VAPID_EXPIRED`、`VAPID_AUDIENCE_MISMATCH`。
- 调用 `/api/keys/rotate` 轮换密钥后，旧密钥签名的凭据全部失效（`VAPID_SIGNATURE_INVALID`），轮换结果持久化，重启后仍然有效。

### 负载端到端加密

加密流程（`PayloadCipher`）：

1. 服务端为每条消息生成一次性 P-256 临时密钥对，与订阅方公钥做 ECDH 得到共享秘密 `Z`。
2. `IKM = HKDF-Extract(auth, Z)`；`PRK = HKDF-Extract(salt, IKM)`（`salt` 为 16 字节随机数）。
3. 由 `PRK` 经 HKDF-Expand 分别派生 16 字节 CEK 与 12 字节 Nonce，派生上下文绑定双方公钥。
4. AES-128-GCM 加密明文，密头（版本、临时公钥、salt、key-check、过期时间）作为 AAD 参与认证。

性质：

- **前向随机化**：每条消息使用新的临时密钥与随机 salt，同一负载重复加密的密文必然不同。
- **篡改检测**：密文或关联数据（密头任意字段）被修改时 GCM 认证失败，解密拒绝且不输出明文。
- **可区分的解密失败原因**：`KEY_MISMATCH`（密钥确认值不匹配，订阅方私钥错误）、`TAMPERED`（密钥确认通过但 GCM 认证失败）、`EXPIRED`（解密成功但负载已过期）、`MALFORMED`（结构非法）。

### 投递与一致性

- 消息支持 `ttlSeconds`（存活时间）、`urgency`（`very-low`/`low`/`normal`/`high`）、`topic`（主题）。
- 超过 TTL 的消息不投递，状态置为 `EXPIRED`（原因 `MESSAGE_EXPIRED`）。
- 同一订阅同一主题只保留最近一条待投递消息（新消息入队时原位替换）。
- 端点返回永久失败（404 未注册 / 410 已撤销）时自动删除订阅及其全部待投递消息；临时失败按指数退避重试，重试次数有上限，超限置为 `FAILED`（`MAX_RETRIES_EXCEEDED`）。
- 同一订阅的入队、投递、更新、删除在同一锁内串行化：并发投递不丢失不覆盖，删除后不会再有投递，并发更新不会静默丢失（版本号单调递增）。
- 订阅、服务端密钥与待投递状态全部写穿持久化到数据目录（原子写 JSON 文件），进程重启后已确认的订阅与未完成的投递自动恢复并继续调度。

## 配置项

均通过 `push.*` 前缀配置（见 `src/main/resources/application.properties`）：

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `push.data-dir` | `./push-data` | 持久化目录（订阅、密钥、待投递状态） |
| `push.vapid-audience` | `http://localhost:8080` | VAPID 接收方标识（`aud`） |
| `push.vapid-subject` | `mailto:admin@localhost` | VAPID `sub` 声明 |
| `push.vapid-expiry-seconds` | `43200` | 签发凭据的有效期 |
| `push.max-delivery-attempts` | `5` | 单条消息最大投递尝试次数（含首次） |
| `push.retry-base-delay-millis` | `200` | 退避基础延迟 |
| `push.retry-backoff-multiplier` | `2.0` | 退避倍率 |
| `push.max-retry-delay-millis` | `30000` | 退避延迟上限 |
| `push.scheduler-interval-millis` | `100` | 投递调度扫描间隔 |
| `push.default-ttl-seconds` | `3600` | 消息默认 TTL |
| `push.max-ttl-seconds` | `86400` | 消息 TTL 上限 |

## 测试

`mvn test` 覆盖：订阅生命周期与输入校验（`SubscriptionServiceTest`）、VAPID 签名校验（`VapidServiceTest`）、加解密回环与篡改检测（`PayloadCipherTest`）、TTL 过期/主题合并/重试上限/失败清理（`DeliveryServiceTest`）、并发投递与删除竞争（`ConcurrencyTest`）、重启恢复与密钥轮换（`PersistenceRestartTest`）、REST 全链路（`ApiIntegrationTest`）。

## 代码结构

```
src/main/java/com/github/guoyyit/webpushservicejava/push/
├── crypto/      # ES256 VAPID JWT、ECDH+HKDF+AES-GCM 负载加密
├── model/       # 订阅、消息、投递记录等模型
├── store/       # JSON 文件原子持久化（订阅/投递/服务端密钥）
├── endpoint/    # 本地模拟推送端点注册表
├── service/     # 订阅管理、投递调度、推送编排
├── config/      # 配置项与 Bean 装配
└── web/         # REST 控制器与错误映射
```
