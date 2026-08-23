# DOJ WebSocket 推送链路说明

> 本文档梳理 D-OnlineJudge 中 WebSocket 的使用方式：从用户提交代码到前端实时收到判题结果的完整链路，以及各环节的代码位置、消息格式和部署注意事项。

---

## 1. 一句话概述

WebSocket 只干一件事：**判题完成后，把结果实时推送给正在等待的用户**。其余功能（提交、查询、统计）全部走 HTTP API。

- 连接端点：`/ws/submission`
- 服务端实现：`submission-service`（端口 `8084`）
- 订阅方式：前端连上后发送一条 `{"submissionId": xxx}` 消息即完成对该次提交的订阅

---

## 2. 整体架构图

```
┌────────────┐  ① POST /api/sandbox/validate（提交代码）   ┌────────────┐
│   浏览器    │ ──────────────────────────────────────────▶ │  Ingress   │
│  (Vue SPA) │                                            └─────┬──────┘
│            │           ② /api → gateway:8080                │
└─────┬──────┘ ◀───────────────────────────────────────────────┘
      │             ⑨ 返回 submissionId
      │
      │ ⑦ 订阅：{"submissionId": 123}   （WS，绕过网关）
      ▼ ──────────────────────────────────────┐
  ┌──────────┐   ⑧ 推送判题结果 JSON          │
  │ doj-fe   │  ◀─────────────────────────────┤
  │ (nginx)  │   location /ws/ → submission:8084
  └────┬─────┘
       │
       ▼  /ws/submission
  ┌─────────────────────────────┐      ⑤ doj.topic / judging.result
  │    submission-service       │ ◀────────────────────────────┐
  │  ┌───────────────────────┐  │                              │
  │  │ SubmissionWSServer    │  │  ③ 创建 PENDING 提交记录     │
  │  │ (ONLINE_SESSIONS Map) │  │     (Feign /submission/submit)│
  │  └──────────┬────────────┘  │                              │
  │  ┌──────────▼────────────┐  │                              │
  │  │ ResultListener        │  │                              │
  │  │ (judging.result.queue)│  │                              │
  │  └───────────────────────┘  │                              │
  └─────────────────────────────┘                              │
       ▲                                                       │
       │ ⑥ 更新 DB + WS 推送                                   │
       │                                                       │
  ┌────┴──────────┐  ④ Redis List "judging:queue"   ┌─────────▼────────┐
  │ RabbitMQ      │ ◀─────────────────────────────── │  sandbox-service │
  │ doj.topic     │                                  │  JudgingWorker   │
  └───────────────┘                                  │  (轮询 Redis 队列)│
                                                     └──────────────────┘
```

**两条链路分离是关键：**
- **HTTP 链路**：浏览器 → Ingress(`/api`) → gateway:8080 → 各微服务（带 JWT 鉴权）
- **WS 链路**：浏览器 → Ingress(`/ws` 命中前端规则) → doj-fe nginx → **直连** `submission:8084`（绕过网关，无鉴权）

---

## 3. 前端使用方式

### 3.1 全局单例连接 — `DOJ-FE/src/utils/websocket.ts`

- 模块级单例 `socket`，全应用只建立**一条** WebSocket 连接（而非每个提交一条）。
- `App.vue` 在 `onMounted` 时调用一次 `useWebSocket()` 触发建连：

```ts
// DOJ-FE/src/App.vue
onMounted(() => {
  useWebSocket();   // 页面加载即建立 WS 连接
});
```

- 连接地址**动态拼接当前域名**（生产自动为 `wss://ohjudge.asia/ws/submission`，本地开发为 `ws://localhost:5173/ws/submission`，由 Vite 代理转发到 8084）：

```ts
// DOJ-FE/src/utils/websocket.ts
const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws';
const wsUrl = `${protocol}://${window.location.host}/ws/submission`;
```

- **注意**：连接只在 `onclose` 后置 `socket = null`，本身没有自动重连；重连动作发生在下次调用 `subscribeToSubmission` 时（见下）。

### 3.2 提交并订阅结果 — `DOJ-FE/src/components/CodeEditor/problemEditor.vue`

用户点击 submit 后：

```ts
const response = (await reqProblemValidate(formData)).data;   // POST /api/sandbox/validate
if (response.code === 200) {
    const submissionId = response.data;                       // 后端返回提交 ID
    useWebSocket().subscribeToSubmission(submissionId, (result) => {
        judgeResult.value = result;                            // 内联展示判题结果
        submitting.value = false;
    });
}
```

`subscribeToSubmission` 的逻辑（websocket.ts）：

1. 设置 `activeCallback`（本次订阅的接收回调，模块级单例，同时只能有一个）。
2. `trySubscribe()`：
   - 连接已 `OPEN` → 直接发送 `JSON.stringify({ submissionId })`
   - 连接 `CONNECTING` → 500ms 后重试
   - 未连接 → 调 `connectWebSocket()` 重连，500ms 后重试

### 3.3 接收结果（两种展示方式）

`socket.onmessage` 收到消息后：

- **有 activeCallback**（编辑页提交场景）→ 交给回调内联渲染，不弹通知。
- **无 activeCallback**（用户在其他页面，比如题目详情页或状态页看历史提交，连接仍活着）→ 弹右下角 `ElNotification`，点击可跳转 `/status?submissionId={id}`。

通知/面板渲染的状态来源（`renderResultMessage`）：
- `result.status`：`Accepted` / `Wrong Answer` / 其他（红/绿色区分）
- `result.time`：秒（前端 `×1000` 显示为 ms）
- `result.memory`：KB
- `result.message`：判题信息（WA 时展示）

---

## 4. 后端使用方式

### 4.1 连接与订阅 — `SubmissionWSServer.java`

```java
@ServerEndpoint("/ws/submission")          // 端点注册在根路径（服务无 context-path）
public class SubmissionWSServer {
    private static final Map<Long, Session> ONLINE_SESSIONS = new ConcurrentHashMap<>();

    @OnMessage
    public void onMessage(String message, Session session) {
        Map<String, Object> data = JSON.parseObject(message);
        Long submissionId = Long.valueOf(data.get("submissionId").toString());
        ONLINE_SESSIONS.put(submissionId, session);   // submissionId → 会话，进程内内存
    }

    public static void sendMessage(Long submissionId, String message) {
        Session session = ONLINE_SESSIONS.get(submissionId);
        if (session != null && session.isOpen()) {
            session.getBasicRemote().sendText(message);   // 推送给订阅该提交的会话
            ONLINE_SESSIONS.remove(submissionId);          // 推送成功即移除（一次性订阅）
        }
    }
}
```

- `WebSocketConfig.java` 提供 `ServerEndpointExporter` 完成端点注册（`javax.websocket` + Tomcat 容器）。
- 订阅 key 是 `submissionId`，**一次推送后即从 Map 移除**，不会重复推送。

### 4.2 判题结果消费与推送 — `ResultListener.java`

`submission-service` 监听 RabbitMQ 队列 `judging.result.queue`（绑定交换机 `doj.topic`，routing key `judging.result`，见 `MqConfig.java`）：

```java
@RabbitListener(queues = "judging.result.queue")
public void onMessage(Map<String, Object> message) {
    Long submissionId = ...;
    Submission submission = submissionService.getById(submissionId);
    // 1. 补全并更新 DB（用户名、题目名、status、exitValue、time、memory、复杂度等）
    submissionService.updateById(submission);
    // 2. 通过 WebSocket 推送给前端
    SubmissionWSServer.sendMessage(submissionId, JSON.toJSONString(submission));
    // 3. 发 MQ 事件更新统计（submission.created / 首次 AC 发 problem.solved）
}
```

---

## 5. 完整链路（一次判题的全过程）

以用户在题目详情页点击 Submit 为例：

| 步骤 | 内容 | 组件 | 说明 |
|---|---|---|---|
| ① | 前端 `POST /api/sandbox/validate`（multipart：文件+pid+language） | 浏览器 → Ingress → gateway → sandbox-service | 走 HTTP 链路，带 JWT |
| ② | `SandboxController.runProblemValidate`：保存代码文件、从 problem-service 拉题目测试用例生成输入文件 | sandbox-service | 代码文件写入共享存储 |
| ③ | 创建 **PENDING** 提交记录：Feign `submissionClient.submit()` → submission-service `POST /submission/submit`（仅 `save`） | sandbox → submission | 拿到 `submissionId` |
| ④ | 组装 `JudgingTask` 推入 **Redis List `judging:queue`** | sandbox-service | 判题队列（Redis 而非 MQ） |
| ⑤ | 返回 `submissionId` 给前端 | | 前端拿到后发起 WS 订阅 |
| ⑥ | 前端 `subscribeToSubmission(id)` → WS 发 `{"submissionId": id}` | 浏览器 → nginx → submission:8084 | `ONLINE_SESSIONS.put(id, session)` |
| ⑦ | `JudgingWorker` 调度线程从 Redis `judging:queue` 轮询任务，分发给 `JudgingThreadPool` 执行 `sandboxService.execute(task)` | sandbox-service | 真正的沙箱判题（K8s 起 Pod 等） |
| ⑧ | 判题完成：`rabbitTemplate.convertAndSend("doj.topic", "judging.result", msg)`（submissionId + executeMessage + complexity）；异常兜底发 System Error | sandbox-service | 结果走 MQ |
| ⑨ | `ResultListener` 消费 `judging.result.queue`：更新 DB → `SubmissionWSServer.sendMessage(id, submissionJson)` | submission-service | 从内存 Map 找会话并推送 |
| ⑩ | 前端 `socket.onmessage` 收到 JSON → activeCallback 内联渲染 / 弹通知 | 浏览器 | 用户看到结果 |

**关键点：判题任务走 Redis 队列，判题结果走 RabbitMQ，两者不同。WS 推送发生在第 ⑨ 步。**

---

## 6. 消息格式

### 6.1 前端 → 后端（订阅）

```json
{"submissionId": 123}
```

### 6.2 后端 → 前端（结果推送）

`Submission` 实体 JSON（fastjson 序列化），核心字段：

```json
{
  "id": 123,
  "problemId": 5,
  "userId": 1,
  "userName": "zhangsan",
  "problemName": "两数之和",
  "language": "cpp",
  "code": "……",
  "status": "Accepted",
  "exitValue": 0,
  "message": "……",
  "time": 0.015,
  "memory": 2048
}
```

- `status`：`Accepted` / `Wrong Answer` / `Time Limit Exceeded` / `Memory Limit Exceeded` / `Compile Error` / `System Error` 等
- `time` 单位**秒**，`memory` 单位 **KB**（前端展示时换算）

---

## 7. 部署链路

### 7.1 开发环境

- 前端 `vite.config.ts` 配置了 `/ws` 代理 → `ws://localhost:8084`，所以本地 `npm run dev` 时前端仍用 `location.host`（localhost:5173）即可，无需改代码。
- 前提：submission-service 本地启动且监听 8084。

### 7.2 生产环境（K8s + Ingress）

1. 浏览器连接 `wss://ohjudge.asia/ws/submission`
2. Ingress 没有 `/ws` 专属规则，按 `/( )(.*)` 前缀规则命中 `doj-fe`（rewrite-target `/$2` 不影响该路径）
3. doj-fe 容器内 nginx 的 `location /ws/` 反代到 **K8s Service `submission:8084`**：

```nginx
location /ws/ {
    proxy_pass http://submission:8084;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Host $host;
    proxy_read_timeout 3600s;
    proxy_send_timeout 3600s;
}
```

4. `@ServerEndpoint("/ws/submission")` 无 context-path，路径精确命中。

> ⚠️ WS 链路**绕过网关**（gateway 无 `/ws/**` 路由），直达 submission-service 8084。

---

## 8. 已知限制与注意事项

| 限制 | 说明 | 现状/对策 |
|---|---|---|
| **会话存进程内存，多副本丢失推送** | `ONLINE_SESSIONS` 是 JVM 内存 Map。多副本时 WS 连接落在 Pod A，但 RabbitMQ 结果可能被 Pod B 消费，Pod B 查不到会话 → 静默丢弃 | **必须单副本**（`values.yaml` 已设 `replicaCount: 1`）；恢复多副本需先做 Redis/STOMP 会话共享 |
| **无鉴权** | WS 端点不校验 JWT，知道 `submissionId` 即可订阅该提交的结果 | 当前可接受（ID 难以猜测），严格场景需加 token 校验 |
| **无心跳保活** | 前后端都没有 ping/pong，长连接依赖 nginx 的 `proxy_read_timeout 3600s` 兜底 | 如需更稳可加心跳，代理超时相应调整 |
| **单次订阅、单一回调** | `activeCallback` 全局唯一，一次只支持一个订阅回调；推送成功后会话从 Map 移除 | 页面内只会在同一时间等待一个判题结果，符合现状 |
| **断线不自动重连** | `onclose` 仅置 `socket = null`；只有再次 `subscribeToSubmission` 才会触发重连 | 连接断开期间完成的判题结果收不到，需刷新/重新提交 |
| **未设置提交初始状态** | `SubmissionController.submit` 仅 `save`，status 初始值取决于实体默认（PENDING） | 如实体默认值为 null，前端拿到后 status 为 null，属预期（判题中） |

---

## 9. 相关代码索引

| 文件 | 职责 |
|---|---|
| `DOJ-FE/src/utils/websocket.ts` | 前端 WS 封装：连接、订阅、重试、消息处理 |
| `DOJ-FE/src/App.vue` | 页面挂载时建连 |
| `DOJ-FE/src/components/CodeEditor/problemEditor.vue` | 提交 + 订阅判题结果并内联展示 |
| `DOJ-FE/vite.config.ts` | 开发环境 `/ws` 代理 |
| `DOJ-FE/nginx.conf` | 生产 `/ws/` 反代到 submission:8084 |
| `submission-service/.../websocket/SubmissionWSServer.java` | WS 端点：订阅管理、结果推送 |
| `submission-service/.../config/WebSocketConfig.java` | `ServerEndpointExporter` 注册 |
| `submission-service/.../mq/ResultListener.java` | 消费判题结果 → 更新 DB → WS 推送 |
| `submission-service/.../config/MqConfig.java` | 交换机 `doj.topic` + 队列 `judging.result.queue` 绑定 |
| `sandbox-service/.../controller/SandboxController.java` | `/sandbox/validate`：收代码、建提交、入 Redis 判题队列 |
| `sandbox-service/.../worker/JudgingWorker.java` | 轮询 Redis `judging:queue` 并调度判题 |
| `deploy/helm/doj/values.yaml` | submission 副本数（必须 1） |
| `deploy/helm/doj/templates/ingress.yaml` | Ingress 路由（`/api` → gateway，其余 → doj-fe） |
