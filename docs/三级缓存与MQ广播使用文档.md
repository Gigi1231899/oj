# 三级缓存 + MQ 缓存广播 使用文档

> 适用范围：problem-service（题目服务）的题目详情读取链路
> 代码位置：`DOJ-BE/problem-service/src/main/java/com/decade/doj/problem/service/impl/ProblemServiceImpl.java`
> 配置位置：`DOJ-BE/problem-service/src/main/java/com/decade/doj/problem/config/CacheConfig.java`、`.../mq/MqConfig.java`

---

## 1. 三级缓存架构总览

题目的热点数据（题目描述、样例、时限等）按"**读多写少**"设计为三级缓存：

```
┌─────────────────────────────────────────────────────────────┐
│  ① L1 本地缓存 Caffeine（JVM 进程内，毫秒级）                │
│     容量 500 条，5 分钟过期，key: problemDetail:{id}         │
├─────────────────────────────────────────────────────────────┤
│  ② L2 分布式缓存 Redis（多副本共享，命中后回填 L1）          │
│     30 分钟过期，key: problemDetail:{id}                     │
├─────────────────────────────────────────────────────────────┤
│  ③ L3 数据库 MySQL（唯一持久层，未命中才查）                 │
│     查库成功后回填 L1 + L2                                    │
└─────────────────────────────────────────────────────────────┘
```

**各级特性对比：**

| 级别 | 存储 | 速度 | 容量 | 是否跨副本共享 | 过期时间 |
|------|------|------|------|---------------|----------|
| L1 Caffeine | JVM 内存 | 纳秒级 | 500 条 | 否（每实例独立） | 5 分钟 |
| L2 Redis | 分布式内存 | 微秒级 | 大 | 是 | 30 分钟 |
| L3 MySQL | 磁盘 | 毫秒级 | 无限 | 是（唯一事实源） | — |

**为什么要两级内存缓存：** L1 快但每个副本各存一份（缓存穿透到 Redis 仍快）；L2 共享但网络往返约 0.5~1ms。热点题目 L1 直接命中，冷门题目 L2 命中并回填 L1，避免每个副本都打 Redis/MySQL。

---

## 2. 三级缓存读取链路（核心方法 getById）

`ProblemController.getProblemById` → `problemService.getById(id)`：

```
用户请求 GET /problem/{id}
        │
        ▼
┌─ ① L1 查询 ──→ 命中？──是──→ 直接返回 ✅（不碰 Redis/MySQL）
│
└─ 未命中
        │
        ▼
┌─ ② L2 查询 ──→ 命中？──是──→ 回填 L1 → 返回 ✅（只查了一次 Redis）
│
└─ 未命中
        │
        ▼
┌─ ③ L3 查库 selectById(id) ──→ 回填 L1 + L2 → 返回 ✅（此后 5 分钟/30 分钟内命中 L1/L2）
```

**对应代码：**

```java
private static final String CACHE_NAME = "problemDetail";   // 缓存名（key 前缀）

public Problem getById(Serializable id) {
    String cacheKey = CACHE_NAME + ":" + id;               // key = problemDetail:{id}
    // ① L1 Caffeine
    Cache caffeine = caffeineCacheManager.getCache(CACHE_NAME);
    if (caffeine != null) {
        Problem cached = caffeine.get(cacheKey, Problem.class);
        if (cached != null) return cached;                  // L1 命中直接返回
    }
    // ② L2 Redis
    Cache redis = redisCacheManager.getCache(CACHE_NAME);
    if (redis != null) {
        Problem cached = redis.get(cacheKey, Problem.class);
        if (cached != null) {
            caffeine.put(cacheKey, cached);                 // L2 命中 → 回填 L1
            return cached;
        }
    }
    // ③ L3 MySQL（查库并回填两级）
    Problem problem = baseMapper.selectById(id);
    if (problem != null) {
        redis.put(cacheKey, problem);                       // 回填 L2
        caffeine.put(cacheKey, problem);                    // 回填 L1
    }
    return problem;
}
```

**缓存名与 key 规则：** 统一 `problemDetail:{题目id}`，例如 `problemDetail:62`。

---

## 3. 缓存失效：MQ 广播（一致性保证）

### 3.1 问题：多副本下缓存如何保持一致

problem-service 部署了多个副本，每副本都有独立 L1（Caffeine）。如果副本 A 改了题目只清自己的缓存，副本 B 的 L1 还是旧数据 → 读到脏数据。

### 3.2 方案：写操作后广播"失效通知"，所有副本收到后各自清缓存

```
副本 A（执行写操作）
   │  ① 改库 save/updateById/removeById/updateProblemStats
   │  ② evictCache(本机)             ← 清自己 L1 + L2
   │  ③ convertAndSend("cache.update.exchange", "", problemId)   ← 发广播
   ▼
FanoutExchange cache.update.exchange（广播给所有绑定的队列）
   │
   ├─→ 匿名队列(副本A) ──→ 副本A 收到 → evictCache  ← 自己也收一份（幂等，无妨）
   ├─→ 匿名队列(副本B) ──→ 副本B 收到 → evictCache  ← 清副本B的 L1 + L2
   └─→ 匿名队列(副本C) ──→ 副本C 收到 → evictCache
```

**为什么用 Fanout 广播 + 匿名队列：**

- **Fanout 交换机**：不区分路由键，把消息发给所有绑定的队列 → 天然实现"通知到每一个副本"
- **匿名队列（AnonymousQueue）**：每个消费者实例启动时自动创建**专属临时队列**并绑定，实例下线自动删除 → **新增副本无需改配置**，自动开始接收广播

### 3.3 对应代码

**发送端（写操作后调用）：**

```java
private static final String CACHE_UPDATE_EXCHANGE = "cache.update.exchange";

private void evictAndBroadcast(Long problemId) {
    evictCache(problemId);                                 // 先清本机
    try {
        rabbitTemplate.convertAndSend(CACHE_UPDATE_EXCHANGE, "", String.valueOf(problemId));
    } catch (Exception e) {
        log.warn("广播缓存更新失败: id={}", problemId, e);   // 广播失败不影响主流程
    }
}
```

**接收端（每个副本的监听器）：**

```java
@RabbitListener(queues = "#{problemCacheUpdateQueue.name}")   // 绑定到本实例的匿名队列
public void onCacheUpdate(String cacheKey) {
    log.info("收到缓存更新广播: {}", cacheKey);
    evictCache(cacheKey);    // 清 L1 + L2
}

private void evictCache(String problemId) {
    String cacheKey = CACHE_NAME + ":" + problemId;
    caffeineCacheManager.getCache(CACHE_NAME).evict(cacheKey);  // 清 L1
    redisCacheManager.getCache(CACHE_NAME).evict(cacheKey);     // 清 L2
}
```

**哪些写操作会触发广播：**

| 操作 | 方法 | 广播触发 |
|------|------|----------|
| 新增题目 | `save()` | ✅ 保存成功 → `evictAndBroadcast` |
| 修改题目 | `updateById()` | ✅ 更新成功 → `evictAndBroadcast` |
| 删除题目 | `removeById()` | ✅ 删除成功 → `evictAndBroadcast` |
| 统计更新（AC/WA 计数） | `updateProblemStats()` | ✅ MQ 回调更新后 → `evictAndBroadcast` |

> 注意：**删缓存而不是更新缓存**。删掉后下次查询自然重建，避免"改一半的脏数据被写进缓存"。

---

## 4. 三个示例场景（对照全链路）

### 示例 1：用户第一次看题 62（三级全穿透）

```
① L1 未命中 → ② L2 未命中 → ③ 查 MySQL 返回题目
   ④ 回填 L2(30min) + L1(5min)
下次再看题 62：直接 L1 命中，纳秒级返回
```

### 示例 2：管理端修改题 62 的描述（广播失效全链路）

```
① updateById(62) 改库成功
② 同步 ES 文档（problemRepository.save）
③ evictAndBroadcast(62)：
   - 本机 evictCache → L1、L2 的 problemDetail:62 删除
   - 发消息到 cache.update.exchange（Fanout）
④ 副本 B、C 的匿名队列收到 → onCacheUpdate → 各自 evictCache
⑤ 任何副本再查题 62 → 全缓存空 → 查库回填新数据 ✅ 无不一致
```

### 示例 3：判题结果 AC 触发题目统计更新（MQ 事件 + 缓存联动）

```
用户 AC 题 62
   │
   ▼
sandbox-service 判题完成
   │  convertAndSend("doj.topic", "judging.result", {submissionId, executeMessage})
   ▼
submission-service ResultListener 消费 → 更新 submission 状态
   │  convertAndSend("doj.topic", "submission.created", {problemId:62, isAccepted:true})
   ▼
problem-service StatsUpdateListener 消费（队列 problem.stats.update.queue）
   │  updateProblemStats(62, true) → total_pass +1，改库
   │  → evictAndBroadcast(62) → 广播失效 → 所有副本缓存清空
   ▼
前端再看题 62 的通过人数 → 拉到最新值 ✅
```

---

## 5. RabbitMQ 队列清单（全项目）

### 5.1 交换机

| 交换机 | 类型 | 用途 |
|--------|------|------|
| `doj.topic` | Topic | 判题事件总线（判题结果、提交事件、首 AC 事件） |
| `cache.update.exchange` | Fanout | 缓存失效广播（所有副本必达） |

### 5.2 队列

| 队列 | 绑定（交换机 + 路由键） | 生产者 | 消费者 | 用途 |
|------|------------------------|--------|--------|------|
| `judging.result.queue` | doj.topic + `judging.result` | sandbox-service | submission-service | 判题结果回写 DB |
| `problem.stats.update.queue` | doj.topic + `submission.created` | submission-service | problem-service | 题目 AC/提交数统计 |
| `problem.solved` 路由（无独立队列） | doj.topic + `problem.solved` | submission-service | user-service | 用户首次 AC 更新榜单/积分 |
| 匿名队列（每实例专属） | cache.update.exchange（Fanout） | problem-service（任意副本） | problem-service（所有副本） | 缓存失效广播 |

### 5.3 消息流全景图

```
                    ┌─────────────────────────────────────────────┐
 判题完成后         │                doj.topic (Topic)             │
 sandbox-service ──┤                                             │
   judging.result ─┼──→ judging.result.queue ──→ submission-service
                    │        （回写判题结果到 DB）                   │
                    │                                             │
                    │   submission.created ──→ problem.stats.update.queue
                    │        （更新题目统计）──→ problem-service     │
                    │                                             │
                    │   problem.solved ──→ user-service           │
                    │        （首 AC：更新榜单/积分）                │
                    └─────────────────────────────────────────────┘

                    ┌─────────────────────────────────────────────┐
                    │      cache.update.exchange (Fanout)         │
 problem-service ──┤   problemId 广播                            │
 写操作后发广播      ├──→ 匿名队列A ──→ 副本A evictCache             │
                    ├──→ 匿名队列B ──→ 副本B evictCache             │
                    └──→ 匿名队列C ──→ 副本C evictCache             │
                    └─────────────────────────────────────────────┘
```

### 5.4 消费端配置（shared-rabbitmq.yaml）

```yaml
listener:
  simple:
    acknowledge-mode: auto          # 方法正常返回自动 ack
    prefetch: 1                     # 每个消费者每次只取 1 条 → 写库并发被钳制
    concurrency: 2
    max-concurrency: 5
    retry:
      enabled: true
      max-attempts: 3
      initial-interval: 2000
```

**prefetch=1 的意义**：判题结果回写数据库时，无论消息积压多少，写库并发恒为 2~5 个线程 → 数据库连接池永不被打满（削峰指标的核心机制）。

---

## 6. 观察与排查

### 6.1 看缓存是否命中

当前 `getById` 无日志，如需观察可临时在命中分支加：

```java
log.info("题目 {} 缓存命中: L1={}, L2={}", id, l1Hit, l2Hit);
```

### 6.2 看 MQ 积压

```bash
# RabbitMQ 管理后台
#   GET /api/queues/%2F/judging.result.queue        → Ready / Unacked
#   GET /api/queues/%2F/problem.stats.update.queue
#   GET /api/exchanges/%2F/cache.update.exchange    → 绑定的匿名队列数 = 副本数

curl -u guest:guest http://<rabbitmq-host>:15672/api/queues/%2F/judging.result.queue
```

### 6.3 手动清缓存

```sql
-- 直连 Redis 删除某题缓存
DEL problemDetail:62
-- 或删除整个缓存空间（谨慎，全量失效）
-- redis-cli KEYS 'problemDetail:*' | xargs redis-cli DEL
```

---

## 7. 答辩要点（一页话术）

> 题目详情采用**三级缓存**架构：L1 Caffeine 本地缓存（纳秒级、500 条、5 分钟）、L2 Redis 分布式缓存（30 分钟、多副本共享、命中回填 L1）、L3 MySQL 兜底。判题链路中每次获取题目时限/测试数据都走这条缓存链路，绝大多数请求在 L1 直接命中，不触碰数据库。
>
> 缓存一致性用 **RabbitMQ Fanout 广播**保证：任何副本修改/删除题目或更新统计后，先清本机缓存，再向 `cache.update.exchange` 广播题目 ID，所有副本通过各自**匿名队列**收到通知后同步清缓存——匿名队列保证新增副本零配置自动纳入广播，解决多副本缓存不一致问题。
>
> 消息队列在整个系统里有三条链路：`judging.result.queue` 回写判题结果（prefetch=1 钳制写库并发）、`problem.stats.update.queue` 异步更新题目统计、`problem.solved` 更新用户榜单，配合 `cache.update.exchange` 缓存广播，实现**削峰 + 解耦 + 缓存一致**三位一体。
