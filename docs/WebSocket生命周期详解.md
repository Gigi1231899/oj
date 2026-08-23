明白了，您是要我**把那份文档原样转成标准 Markdown 格式**（而不是把回答转成 MD）。以下是转换后的完整文档：

---

# WebSocket 生命周期详解（连接 / 使用 / 关闭 / 回调时机）

> 基于当前代码实际行为整理，覆盖前端 `websocket.ts`、`App.vue`、`problemEditor.vue` 与后端 `SubmissionWSServer.java`、`ResultListener.java` 的完整链路。

---

## 1. 总览：一条长连接 + 多次一次性订阅

系统 WS 模型：

```
页面加载 ──► 建立 1 条连接（常驻）──────┐
                                        ├── 提交1 ──► 订阅 id=1 ──► 推送结果 ──► 订阅移除
                                        ├── 提交2 ──► 订阅 id=2 ──► 推送结果 ──► 订阅移除
                                        ├── ...
                                        └── 连接保持，直到页面关闭 / 网络断开 / 代理掐断
```

- **连接（Connection）**：整个应用只建一条，常驻不关，页面加载时创建。
- **订阅（Subscription）**：每次提交单独登记 `submissionId → session`，**推送成功后即移除**，一次性。

---

## 2. 连接什么时候创建

### 2.1 首次创建：页面加载时（`App.vue`）

`App.vue` 根组件 `onMounted` 时初始化连接：

```vue
<!-- DOJ-FE/src/App.vue（全文） -->
<script setup lang="ts">
import { onMounted } from "vue";
import { useWebSocket } from "@/utils/websocket";

onMounted(() => {
  // 在应用根组件挂载时，初始化 WebSocket 连接
  useWebSocket();
});
</script>
```

`useWebSocket()` 内部（`websocket.ts` 末尾）：

```ts
// DOJ-FE/src/utils/websocket.ts 第 148~156 行
export const useWebSocket = () => {
  if (!socket) {
    connectWebSocket();
  }
  return {
    subscribeToSubmission,
  };
};
```

`connectWebSocket()` 真正执行 `new WebSocket(wsUrl)`：

```ts
// DOJ-FE/src/utils/websocket.ts 第 44~55 行
const connectWebSocket = () => {
    if (socket && socket.readyState === WebSocket.OPEN) {
        return;
    }
    if (socket && socket.readyState === WebSocket.CONNECTING) {
        return;
    }

    const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws';
    const wsUrl = `${protocol}://${window.location.host}/ws/submission`;

    socket = new WebSocket(wsUrl);
    ...
}
```

要点：

- 模块级单例 `let socket`，全应用只建一条连接；
- `connectWebSocket` 防重入：`OPEN` / `CONNECTING` 状态直接 return；
- 地址拼的是当前页面的 host + `/ws/submission`，走 nginx / Ingress 反代到 submission 服务。

### 2.2 再次创建：断线重连

`onclose` 触发后 `socket = null`，若仍有等待结果的订阅，走指数退避重连（1s / 2s / 4s…封顶 10s），见第 7 节。

### 2.3 兜底创建：提交时发现没连接

`subscribeToSubmission` 发现 `socket == null` 时会先 `connectWebSocket()` 再重试订阅（正常走不到，`App.vue` 已提前建连）。

---

## 3. WebSocket 用在哪些地方

### 3.1 前端（3 处）

| 文件                                                 | 用途                                          |
| ---------------------------------------------------- | --------------------------------------------- |
| `DOJ-FE/src/App.vue`                                 | 页面加载时建连（只调 `useWebSocket()`）       |
| `DOJ-FE/src/utils/websocket.ts`                      | 连接管理 + 订阅 + 回调分发 + 断线重连（核心） |
| `DOJ-FE/src/components/CodeEditor/problemEditor.vue` | 提交成功拿到 `submissionId` 后订阅            |

`problemEditor.vue` 的订阅调用：

```ts
// DOJ-FE/src/components/CodeEditor/problemEditor.vue 第 121~129 行
const response = (await reqProblemValidate(formData)).data;
if (response.code === 200) {
  ElMessage.info("提交成功，判题中...");
  const submissionId = response.data;
  useWebSocket().subscribeToSubmission(submissionId, (result) => {
    judgeResult.value = result;
    submitting.value = false;
  });
}
```

### 3.2 后端（2 处）

| 文件                                                              | 用途                                                          |
| ----------------------------------------------------------------- | ------------------------------------------------------------- |
| `DOJ-BE/submission-service/.../websocket/SubmissionWSServer.java` | `@ServerEndpoint("/ws/submission")` 端点：订阅登记 + 结果推送 |
| `DOJ-BE/submission-service/.../mq/ResultListener.java`            | RabbitMQ 消费者，判题结果到达后调 `sendMessage` 推送          |

```java
// DOJ-BE/submission-service/src/main/java/com/decade/doj/submission/mq/ResultListener.java 第 68~72 行
submissionService.updateById(submission);
log.info("提交记录 {} 已更新", submissionId);

// 2. 通过 WebSocket 推送给前端
SubmissionWSServer.sendMessage(submissionId, JSON.toJSONString(submission));
```

---

## 4. 连接什么时候关闭

前端代码里**没有任何主动 `socket.close()`**，销毁只来自外部：

| 触发原因                   | 说明                                 | close code   |
| -------------------------- | ------------------------------------ | ------------ |
| 页面关闭 / 刷新 / 路由卸载 | 浏览器自动断开 TCP 并发 WS close 帧  | 1000 / 1001  |
| 网络断开                   | 物理断网、WiFi 切换                  | 1006（异常） |
| 代理掐线                   | nginx / Ingress / 负载均衡的空闲超时 | 1006         |
| 服务端重启 / 崩溃 / 缩容   | JVM 没了，连接自然断                 | 1006         |
| 后端主动 close             | **当前代码没有**这个逻辑             | -            |

> 注意：此前"判题结果收不到"的真因就是第 4 行——判题耗时 80s，而代理默认 `proxy_read_timeout` 60s，WS 静默期被掐断。已通过 Ingress 超时改 3600s + 前端自动重连修复。

销毁后流程：

```
onclose → socket = null → 有等待中的订阅？ → 是：scheduleReconnect() 指数退避重连
                                            → 否：不重连（页面闲着重连无意义）
```

后端 `@OnClose` 同时触发，清理失效 session：

```java
// DOJ-BE/.../websocket/SubmissionWSServer.java 第 40~45 行
@OnClose
public void onClose(Session session) {
    // 清理无效的 session
    ONLINE_SESSIONS.values().removeIf(s -> !s.isOpen());
    log.info("WebSocket 连接已关闭: {}", session.getId());
}
```

---

## 5. 和谁有关系（完整调用链）

```
App.vue (onMounted)
   │ useWebSocket()
   ▼
websocket.ts ── new WebSocket("/ws/submission") ──► nginx/Ingress ──► SubmissionWSServer (@ServerEndpoint)
   │                                                        ▲                  │
   │                                                        │                  │ 订阅登记
   │                                                        │                  │ ONLINE_SESSIONS.put(id, session)
   │                                                        │                  ▼
problemEditor.vue ── subscribeToSubmission(id, cb) ── send({"submissionId":id}) ─┘
   │                                                                                 │
   │                                                        RabbitMQ 结果消息 ──► ResultListener
   │                                                                                 │ 更新 DB
   │                                                                                 │ SubmissionWSServer.sendMessage(id, json)
   │                                                                                 ▼
   └────────────── onmessage(result) ◄──────── sendText 推送（订阅移除） ─────────────┘
```

参与者：

1. **`App.vue`** —— 连接起点，页面挂载即建连；
2. **`websocket.ts`** —— 前端唯一连接管理器（建连 / 订阅 / 回调 / 重连）；
3. **`problemEditor.vue`** —— 订阅发起方，提交后注册回调；
4. **nginx / Ingress** —— WS 反代层，负责 `/ws/` 转发与超时配置（3600s）；
5. **`SubmissionWSServer.java`** —— 后端端点，`ONLINE_SESSIONS` 内存 Map 维护订阅；
6. **`ResultListener.java`** —— 判题结果出口，触发推送。

---

## 6. `ws.on` 各函数含义（用本例解释）

### 6.1 `socket.onopen` —— 连接建立成功时

握手成功（HTTP Upgrade → 101）后触发一次。本例中：

```ts
// DOJ-FE/src/utils/websocket.ts 第 57~65 行
socket.onopen = () => {
  console.log("WebSocket 连接已建立。");
  reconnectAttempts = 0;
  // 重连成功后，重新订阅所有还在等待结果的 submissionId
  pendingSubscriptions.forEach((id) => {
    socket?.send(JSON.stringify({ submissionId: id }));
    console.log(`重连后重新提交订阅 ID: ${id}`);
  });
};
```

首次建连时它只是打日志（等待集合为空）；**断线重连成功**时它把还欠着的订阅全部重新发一遍（关键的自愈逻辑）。

### 6.2 `socket.onmessage` —— 收到后端推送时

判题结果到达（`sendText` 推送）时触发，是本例的**核心消费点**：

```ts
// DOJ-FE/src/utils/websocket.ts 第 67~95 行
socket.onmessage = (event) => {
  try {
    const result = JSON.parse(event.data);
    console.log("收到判题结果:", result);

    // 结果已到，从等待集合中移除（后端推送的是 Submission 对象，id 即 submissionId）
    if (result.id != null) {
      pendingSubscriptions.delete(Number(result.id));
    }

    if (activeCallback) {
      activeCallback(result); // problemEditor.vue 里注册的回调：渲染结果
    } else {
      ElNotification({
        // 没有回调时（比如在别的页面）弹通知
        title: `提交 #${result.id} 已完成`,
        message: renderResultMessage(result),
        type: result.status === "Accepted" ? "success" : "error",
        duration: 15000,
        onClick: () => {
          router.push(`/status?submissionId=${result.id}`);
        },
        position: "bottom-right",
      });
    }
  } catch (e) {
    console.error("处理 WebSocket 消息失败:", e);
  }
};
```

分支逻辑：有注册回调 → 调回调（更新编辑器里的判题结果）；无回调 → 弹 Element 通知并可点击跳状态页。

### 6.3 `socket.onclose` —— 连接关闭时

见第 4 节。核心动作：`socket = null` + 有等待结果则调度重连。

```ts
// DOJ-FE/src/utils/websocket.ts 第 97~102 行
socket.onclose = (event) => {
  console.log("WebSocket 连接已关闭:", event);
  socket = null;
  // 还有结果在等 → 指数退避自动重连（1s/2s/4s...封顶 10s）
  scheduleReconnect();
};
```

### 6.4 `socket.onerror` —— 出错时

只打日志。浏览器通常在 `onerror` 之后还会触发 `onclose`，所以重连逻辑放在 `onclose` 里即可。

### 6.5 `socket.send()` —— 主动上行

本例里唯一的 send 是**订阅**：`send(JSON.stringify({ submissionId }))`，告诉后端"这个 id 的结果请推给我"。

### 6.6 与后端注解的对应关系

| 前端              | 后端                                | 触发时机                     |
| ----------------- | ----------------------------------- | ---------------------------- |
| `new WebSocket()` | `@OnOpen`                           | 握手成功（101）              |
| `socket.send()`   | `@OnMessage`                        | 上行消息到达（订阅登记）     |
| 连接断开          | `@OnClose`                          | 连接关闭（清理无效 session） |
| -                 | `sendMessage()`（静态方法，非注解） | 判题结果到达，主动下推       |

```java
// DOJ-BE/.../websocket/SubmissionWSServer.java 第 28~38 行：订阅登记
@OnMessage
public void onMessage(String message, Session session) {
    try {
        Map<String, Object> data = JSON.parseObject(message);
        Long submissionId = Long.valueOf(data.get("submissionId").toString());
        ONLINE_SESSIONS.put(submissionId, session);
        log.info("submissionId: {} 已订阅 WebSocket 通知", submissionId);
    } catch (Exception e) {
        log.error("处理 WebSocket 消息时出错: {}", message, e);
    }
}
```

---

## 7. 用本例（提交 366）解释 WS 使用全过程

以一次真实提交为例（此前排查时的日志：WS 10:06 建立 → 10:07:53 EOFException → 10:08:15 结果到达，判题 80s）：

| 时间轴 | 前端                                                                                                                                                                                                                                                            | 后端 / 中间件                                                                                                                                                                                         |
| ------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| t0     | 页面加载 App.vue onMounted<br>└─ useWebSocket() → new WebSocket()                                                                                                                                                                                               | nginx 反代 → submission:8084<br>握手 101                                                                                                                                                              |
| t1     | onopen 触发<br>（连接常驻，什么都不做）                                                                                                                                                                                                                         | @OnOpen 触发<br>日志: WebSocket 连接已建立                                                                                                                                                            |
| t2     | 用户点"提交"<br>POST /api/sandbox/validate ──► 网关 ──► sandbox ──► 创建 PENDING 提交<br>拿到 submissionId = 366<br>└─ subscribeToSubmission(366, cb)<br>&nbsp;&nbsp;&nbsp;pendingSubscriptions.add(366)<br>&nbsp;&nbsp;&nbsp;socket.send({"submissionId":366}) | （WS 消息上行）                                                                                                                                                                                       |
| t3     |                                                                                                                                                                                                                                                                 | @OnMessage 触发<br>ONLINE_SESSIONS.put(366, session)<br>日志: submissionId: 366 已订阅                                                                                                                |
| t4     | 判题中……（连接保持沉默，双方无数据流动）                                                                                                                                                                                                                        | ← 这里就是 60s 代理超时的风险点                                                                                                                                                                       |
| t5     |                                                                                                                                                                                                                                                                 | 判题完成<br>RabbitMQ 结果消息<br>ResultListener 更新 DB<br>sendMessage(366, "{...}")<br>找到 session → sendText 推送<br>日志: 成功向 submissionId: 366 推送<br>ONLINE_SESSIONS.remove(366) ← 订阅结束 |
| t6     | onmessage 触发<br>pendingSubscriptions.delete(366)<br>activeCallback(result) → judgeResult 渲染 / submitting = false                                                                                                                                            |                                                                                                                                                                                                       |
| t7     | 连接保持，等待下一次提交 → 回到 t2                                                                                                                                                                                                                              |                                                                                                                                                                                                       |

**关键点**：

- 订阅（t3）和推送（t5）发生在**同一条长连接**上；
- 推送成功后订阅记录即删除，同一条 `submissionId` 只会推一次；
- 若 t5 发生时连接已断（比如 60s 代理超时掐线），后端查不到 session **静默丢弃**，前端重连后重新订阅也拿不到（后端不缓存）——这是当前版本已知缺陷（见第 9 节）。

---

## 8. 断线重连机制（前端自愈）

```
断线（60s 代理超时 / 网络闪断 / 服务端重启）
   │
   ├─ onclose → socket = null
   ├─ pendingSubscriptions 非空 → scheduleReconnect()
   │     1s → 2s → 4s → 8s → 10s → 10s...（封顶 10s，退避计数在 onopen 重置）
   │
重连成功 → onopen
   │
   └─ pendingSubscriptions.forEach → 重新 send 订阅
        后端 @OnMessage 重新 put 登记 → 等结果推送
```

```ts
// DOJ-FE/src/utils/websocket.ts 第 109~124 行
const scheduleReconnect = () => {
  if (pendingSubscriptions.size === 0) {
    return; // 没有等待中的订阅则不重连（避免页面闲着重连）
  }
  if (reconnectTimer !== null) {
    return; // 已有重连任务在排队，不重复调度
  }
  const delay = Math.min(1000 * Math.pow(2, reconnectAttempts), 10000);
  reconnectAttempts++;
  reconnectTimer = window.setTimeout(() => {
    reconnectTimer = null;
    console.log("WebSocket 断线重连...");
    connectWebSocket();
  }, delay);
};
```

配合 nginx / Ingress 超时已调到 3600s，静默期被掐断的概率大幅降低。

---

## 9. 关键特性与已知限制

| 项                      | 现状                   | 说明                                                                            |
| ----------------------- | ---------------------- | ------------------------------------------------------------------------------- |
| 连接数量                | 全局 1 条，常驻        | `App.vue` 挂载即建，不随路由销毁                                                |
| 订阅生命周期            | 一次性                 | 推送成功后 `ONLINE_SESSIONS.remove`                                             |
| 断线重连                | 有（仅当有等待结果时） | 指数退避 1s~10s，重连后重订阅                                                   |
| 结果先于订阅 / 连接已断 | **会丢**               | `sendMessage` 查不到 session 静默丢弃、无缓存（当前版本缺陷）                   |
| 心跳保活                | 无                     | 靠 nginx `proxy_read_timeout 3600s` 兜底                                        |
| 鉴权                    | 无                     | 任何人连上即可订阅任意 submissionId                                             |
| 多副本                  | 必须单副本             | `ONLINE_SESSIONS` 是进程内静态内存 Map，跨 Pod 不共享（已配 `replicaCount: 1`） |
| `activeCallback`        | 全局单例               | 极端情况下后订阅会覆盖先订阅的回调                                              |

---

## 10. 相关文件速查

| 文件                                                              | 作用                                            |
| ----------------------------------------------------------------- | ----------------------------------------------- |
| `DOJ-FE/src/App.vue`                                              | 页面加载时初始化连接                            |
| `DOJ-FE/src/utils/websocket.ts`                                   | 连接管理 + 订阅 + 回调 + 重连（核心）           |
| `DOJ-FE/src/components/CodeEditor/problemEditor.vue`              | 提交成功后发起订阅                              |
| `DOJ-BE/submission-service/.../websocket/SubmissionWSServer.java` | 后端 WS 端点，订阅登记 + 推送                   |
| `DOJ-BE/submission-service/.../mq/ResultListener.java`            | RabbitMQ 消费结果 → 调 `sendMessage`            |
| `DOJ-FE/nginx.conf`                                               | 生产 WS 反代（`/ws/` → submission，超时 3600s） |
| `deploy/helm/doj/templates/ingress.yaml`                          | Ingress 层 WS 支持 + 超时 3600s                 |

---

以上即为完整的 Markdown 格式文档，可直接复制保存为 `.md` 文件使用。
