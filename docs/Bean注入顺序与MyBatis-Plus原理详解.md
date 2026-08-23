# Bean 注入顺序与 MyBatis-Plus 原理详解

> 基于 `common` / `gateway-service` / `problem-service` 实际代码整理。
> 说明：Spring 的"Bean 注入顺序"不是一个简单的先后队列，而是**配置类注册顺序 + 依赖图拓扑排序 + 初始化回调**三者共同作用的结果。本文按"启动时序"展开，尽量贴近真实执行顺序。

---

## 1. 模块总览

```
┌─────────────────────────────────────────────────────────────┐
│                      gateway-service (8080)                 │
│   spring-cloud-gateway（WebFlux 响应式）                      │
│   职责：JWT 鉴权 + 路由转发（不做业务，不连 DB/Redis/MQ）       │
│   依赖：common                                               │
└──────────────────────────┬──────────────────────────────────┘
                           │ lb:// 路由（LoadBalancerClientFilter）
        ┌──────────────────┼──────────────────┐
        ▼                  ▼                  ▼
   user-service     problem-service     submission-service
   sandbox-service（另有 /chat 代理）
   各业务服务：Spring MVC（Tomcat）阻塞式
   依赖：common + DB + Redis + RabbitMQ + ES
```

- `common` 是纯公共库，**不单独运行**，被所有服务依赖，提供 JWT、拦截器、Feign 客户端、MyBatis-Plus 插件、线程池、异常处理等。
- `common` 里的类**不是靠组件扫描加载的**（各服务主类只扫描自己包 `com.decade.doj.xxx`），而是通过下面 4 种显式机制注册。

---

## 2. common 模块的 Bean 清单与作用

| Bean                    | 类型/注解                                    | 作用                                                                                       |
| ----------------------- | -------------------------------------------- | ------------------------------------------------------------------------------------------ |
| `CommonExceptionConfig` | `@RestControllerAdvice`                      | 全局异常兜底：统一返回 `R` 格式，处理 DbException / CommonException / 参数校验 / 401 / 403 |
| `JwtProperties`         | `@ConfigurationProperties(doj.jwt)`          | JWT 配置：JKS 位置、密码、别名、TTL、header 名                                             |
| `ResourceProperties`    | `@ConfigurationProperties(doj.resource)`     | 静态资源（文件上传/代码临时目录）路径配置                                                  |
| `AppNameProperties`     | `@Component` + `@Value`                      | 拿到当前服务名 `spring.application.name`                                                   |
| `SecurityConfig`        | `@Configuration`                             | 定义 `KeyPair` Bean：从 JKS 读取 RSA 公私钥                                                |
| `JwtTool`               | `@Component` + `@Import(SecurityConfig)`     | JWT 生成/解析（RS256），构造器注入 `KeyPair`                                               |
| `MVCConfig`             | `@Configuration implements WebMvcConfigurer` | 静态资源映射 + **注册两个拦截器**（Identity → AdminCheck）                                 |
| `IdentityInterceptor`   | `@Component implements HandlerInterceptor`   | 拦截外部http，从请求头 `uid` 取 userId 写入 `UserContext`（ThreadLocal）；无则默认 3       |
| `AdminCheckInterceptor` | `@Component`                                 | 方法带 `@AdminRequired` 时，**Feign 调 user-service** 校验管理员角色                       |
| `DefaultFeignConfig`    | `@Configuration`                             | Feign 统一配置：日志 FULL + `RequestInterceptor` 把当前 userId 透传到下游                  |
| `MybatisConfig`         | `@Configuration`                             | 定义 `MybatisPlusInterceptor`（分页插件 `PaginationInnerInterceptor`，maxLimit 1000）      |
| `ThreadPoolConfig`      | `@Configuration + @EnableAsync`              | 两个线程池 Bean：`RunCodeThreadPool`、`JudgingThreadPool`                                  |
| `ProblemClient`         | `@FeignClient("problem-service")`            | Feign 客户端：查题目详情 / 题目总数                                                        |
| `SubmissionClient`      | `@FeignClient("submission-service")`         | Feign 客户端：批量查题目状态 / 用户状态                                                    |
| `UserClient`            | `@FeignClient("user-service")`               | Feign 客户端：查用户信息（管理员校验用）                                                   |
| `UserContext`           | 静态工具（ThreadLocal）                      | 线程内保存当前登录 userId                                                                  |

---

## 3. common 的 Bean 是怎么进容器的（4 种加载机制）

### 3.1 `@Import(...)` — 主类显式导入（最主要）

```16:20:DOJ-BE/problem-service/src/main/java/com/decade/doj/problem/ProblemApplication.java
@SpringBootApplication
@MapperScan("com.decade.doj.problem.mapper")
@EnableCaching
@EnableFeignClients(basePackages = "com.decade.doj.common.client", defaultConfiguration = DefaultFeignConfig.class)
@Import({MVCConfig.class, MybatisConfig.class, IdentityInterceptor.class, AdminCheckInterceptor.class})
```

各服务主类的 `@Import` 清单（决定加载哪些 common 配置）：

| 服务       | `@Import`                                                                                          |
| ---------- | -------------------------------------------------------------------------------------------------- |
| gateway    | `JwtTool`                                                                                          |
| user       | `JwtTool, MVCConfig, MybatisConfig, IdentityInterceptor, AppNameProperties, AdminCheckInterceptor` |
| problem    | `MVCConfig, MybatisConfig, IdentityInterceptor, AdminCheckInterceptor`                             |
| submission | `JwtTool, MVCConfig, MybatisConfig, IdentityInterceptor, AdminCheckInterceptor`                    |
| sandbox    | `ThreadPoolConfig, MVCConfig, MybatisConfig, IdentityInterceptor, AdminCheckInterceptor`           |

### 3.2 `spring.factories` — 自动装配（仅 1 个）

```1:2:DOJ-BE/common/src/main/resources/META-INF/spring.factories
org.springframework.boot.autoconfigure.EnableAutoConfiguration=\
  com.decade.doj.common.config.global.CommonExceptionConfig
```

只有 `CommonExceptionConfig` 走自动装配，所有服务都会加载（因此全局异常处理全服务生效）。

### 3.3 `@EnableFeignClients` — Feign 客户端 + 全局 Feign 配置

```15:20:DOJ-BE/problem-service/src/main/java/com/decade/doj/problem/ProblemApplication.java
@SpringBootApplication
@MapperScan("com.decade.doj.problem.mapper")
@EnableCaching
@EnableFeignClients(basePackages = "com.decade.doj.common.client", defaultConfiguration = DefaultFeignConfig.class)
@Import({MVCConfig.class, MybatisConfig.class, IdentityInterceptor.class, AdminCheckInterceptor.class})
```

- `basePackages = "com.decade.doj.common.client"`：扫描 `common/client` 下的所有 `@FeignClient` 接口，注册为代理 Bean。
- `defaultConfiguration = DefaultFeignConfig.class`：把 `DefaultFeignConfig` 作为**全局 Feign 配置**加载（日志 + userId 透传拦截器）。
- gateway 没有 `@EnableFeignClients`（它不走 Feign，见第 5 节）。

IdentityInterceptor.preHandle (设置 userId)
↓
Controller 业务代码 (调用 Feign)
↓
RequestInterceptor (读取 userId 透传)
↓
IdentityInterceptor.afterCompletion (清理 userId)
❌ 它们不会"竞争"或"冲突"
HandlerInterceptor 是 Spring MVC 层面的拦截器，拦截的是外部 HTTP 请求。

RequestInterceptor 是 OpenFeign 层面的拦截器，拦截的是内部发起的 HTTP 请求。

它们工作的层级不同、时机不同、目的不同：

IdentityInterceptor：负责接收请求时，把 userId 从请求头存入 ThreadLocal。

RequestInterceptor：负责发出请求时，把 userId 从 ThreadLocal 取出塞进请求头。

### 3.4 `@EnableConfigurationProperties` / `@Component` — 配置属性类

`JwtProperties`、`ResourceProperties` 等在各自使用方上通过 `@EnableConfigurationProperties` 激活；`AppNameProperties` 是 `@Component`（由 `@Import` 引入才生效）。

---

## 4. Bean 注入/创建顺序（真实启动时序）

Spring 启动时的注册顺序与依赖解析，按以下阶段展开（以 problem-service 为例）：

### 阶段 0：配置源加载

1. 启动 `ProblemApplication` → 加载 `bootstrap.yaml`（连接 Nacos）→ `spring.config.import` 拉取 `shared-jwt / shared-jdbc / shared-rabbitmq / shared-redis`。
2. `application.yaml`（本地默认）+ `spring.profiles.active: common` → 引入 common 模块的 `application-common.yaml`（端口、资源路径默认值）。

### 阶段 1：配置类注册（按 @Import 顺序与自动装配）

```
ProblemApplication
├─ @Import(MVCConfig)          → 其构造需要 ResourceProperties / IdentityInterceptor / AdminCheckInterceptor
├─ @Import(MybatisConfig)      → 需要 mybatis-plus 在 classpath（@ConditionalOnClass(BaseMapper)）
├─ @Import(IdentityInterceptor)→ 需要 JwtProperties（@EnableConfigurationProperties 触发注册）
├─ @Import(AdminCheckInterceptor) → 实现 ApplicationContextAware（容器先注入 ApplicationContext，UserClient 懒加载）
├─ @EnableFeignClients(DefaultFeignConfig) → DefaultFeignConfig 需要 JwtProperties
└─ spring.factories            → CommonExceptionConfig
```

> **注意**：`@Import` 里的类是 `@Configuration` 配置类，它们的 `@Bean` 方法要到**实例化阶段**才真正创建对象。这里只完成"定义注册 + 依赖声明"。

### 阶段 2：Bean 实例化（按依赖图拓扑排序，不是声明顺序）

Spring 通过 `@RequiredArgsConstructor` 生成的**构造器注入** + `@Bean` 方法参数建立依赖边，形成有向无环图后按拓扑序创建。problem-service 的实际创建顺序（关键链）：

```
JwtProperties ──► SecurityConfig.keyPair(KeyPair) ──► JwtTool（构造器拿 KeyPair）
      │                                                     │
      │                                                     ▼
      └──────► DefaultFeignConfig ──► feignLogLevel / userInfoRequestInterceptor

ResourceProperties ──► IdentityInterceptor（构造器拿 JwtProperties）
                            │
AdminCheckInterceptor ◄─────┘（ApplicationContextAware 注入容器，UserClient 延迟 getBean）
                            │
                            ▼
                      MVCConfig（构造器拿 ResourceProperties + 两个拦截器）

MybatisConfig ──► MybatisPlusInterceptor（分页插件）

ThreadPoolConfig ──► RunCodeThreadPool / JudgingThreadPool（@EnableAsync 开启异步代理）

@MapperScan ──► 每个 Mapper 接口 → MapperFactoryBean → MapperProxy（详见第 6 节）
                     │
                     ▼
              ProblemServiceImpl（构造器注入 3 个 Mapper + 2 个 CacheManager + RabbitTemplate + ES 等）
```

### 阶段 3：Web 层装配（拦截器执行顺序）

`MVCConfig.addInterceptors` 的注册顺序即**执行顺序**：

```34:42:DOJ-BE/common/src/main/java/com/decade/doj/common/config/custom/MVCConfig.java
@Override
public void addInterceptors(InterceptorRegistry registry) {
    // 身份识别拦截器，必须先执行
    registry.addInterceptor(identityInterceptor)
            .addPathPatterns("/**")
            .excludePathPatterns("/swagger-resources/**", "/webjars/**", "/v2/**", "/swagger-ui.html/**", "/doc.html/**", "/actuator/**");

    // 管理员权限拦截器，后执行
    registry.addInterceptor(adminCheckInterceptor)
            .addPathPatterns("/**");
}
```

一次请求的拦截器链路：

```
请求进入
  → IdentityInterceptor.preHandle    把 header uid → UserContext
  → AdminCheckInterceptor.preHandle  若有 @AdminRequired → Feign 调 user-service 校验
  → Controller 业务方法
  → AdminCheckInterceptor.afterCompletion
  → IdentityInterceptor.afterCompletion  清理 ThreadLocal（UserContext.clear()）
```

### 阶段 4：Feign 代理 Bean 的就绪时机

- `AdminCheckInterceptor` 通过 `ApplicationContextAware` **懒加载** `UserClient`（首次用到管理员校验时才 `getBean`）。
- 原因：Feign 客户端 Bean 依赖 LoadBalancer + Nacos 服务发现，若在拦截器构造时就注入，会与容器初始化形成循环依赖；懒加载规避。

---

## 5. gateway 调 problem-service 是不是走 Feign 负载均衡？

**不是。** 这是个常见误区，两者是不同链路，但底层共享同一个 LoadBalancer。

### 5.1 gateway → 业务服务：Spring Cloud Gateway 路由，不是 Feign

```30:53:DOJ-BE/gateway-service/src/main/resources/application.yaml
  cloud:
    gateway:
      routes:
        - id: user
          uri: lb://user-service
          predicates:
            - Path=/user/**
        - id: problem
          uri: lb://problem-service
          predicates:
            - Path=/problem/**
        ...
```

gateway 的 `pom.xml` 依赖是 `spring-cloud-starter-gateway` + `spring-cloud-starter-loadbalancer` + `nacos-discovery`，**没有** `openfeign`，主类也没有 `@EnableFeignClients`。

请求链路：

```
浏览器 → gateway:8080（AuthGlobalFilter 校验 JWT，order=0）
       → RoutePredicate 匹配 Path=/problem/**
       → uri 以 lb:// 开头 → 触发 LoadBalancerClientFilter
       → 从 Nacos 拿 problem-service 的实例列表
       → 按默认 RoundRobin 策略选一个实例
       → 转发到该实例
```

`lb://` 前缀 + `LoadBalancerClientFilter` = **gateway 自己的负载均衡**，走的是响应式 `ReactiveLoadBalancer`（WebFlux）。

### 5.2 业务服务之间：Feign（它的负载均衡同样走 LoadBalancer）

Feign 发生在**业务服务内部互相调用**，例如：

- `AdminCheckInterceptor` 用 `UserClient` 调 `user-service`（校验管理员）；
- `ProblemServiceImpl.pageQuery` 用 `SubmissionClient` 调 `submission-service`（批量取题目状态）。

```9:17:DOJ-BE/common/src/main/java/com/decade/doj/common/client/SubmissionClient.java
@FeignClient("submission-service")
public interface SubmissionClient {

    @GetMapping("/submission/batch-status")
    R<List<ProblemStatusVO>> batchStatus(@RequestParam("problemIds") List<Long> problemIds,
                                         @RequestParam("userId") Long userId);
}
```

Feign 的负载均衡机制：

```
@FeignClient("submission-service")   ← 服务名，不写死地址
       │
       ▼
Feign 代理（FeignClientFactoryBean 创建）
       │
       ▼
LoadBalancerFeignClient —— 通过 spring-cloud-starter-loadbalancer
       │                    从 Nacos 拿 submission-service 实例列表
       ▼
RoundRobin 选一个实例 → 发起 HTTP
```

### 5.3 两者对比

| 维度         | gateway → 业务服务                                  | 业务服务 → 业务服务                               |
| ------------ | --------------------------------------------------- | ------------------------------------------------- |
| 技术         | Spring Cloud Gateway（WebFlux）                     | OpenFeign（阻塞式 HTTP）                          |
| 入口         | 路由 `uri: lb://xxx`                                | `@FeignClient("xxx")`                             |
| 负载均衡实现 | `LoadBalancerClientFilter` + `ReactiveLoadBalancer` | `LoadBalancerFeignClient` + 阻塞式 `LoadBalancer` |
| 底层服务发现 | Nacos Discovery                                     | Nacos Discovery                                   |
| 默认策略     | RoundRobin                                          | RoundRobin                                        |

> **一句话**：gateway 用的是 Gateway 自带路由 + `lb://` 负载均衡；Feign 是业务服务之间用的 RPC 客户端，它内部也接同一个 LoadBalancer。两者不是一回事，但"服务发现 + 负载均衡"的底座（nacos-discovery + spring-cloud-loadbalancer）是共享的。

---

## 6. problem-service 的 MyBatis-Plus 配置与注入原理

### 6.1 配置来源（三层）

**① Nacos `shared-jdbc.yaml` — 数据源 + 读写分离 + MP 全局配置**

```31:79:nacos-configs/shared-jdbc.yaml
spring:
  shardingsphere:
    mode:
      type: Memory            # 不连治理中心，规则每次启动重建
    datasource:
      names: master, slave
      master:
        type: com.zaxxer.hikari.HikariDataSource
        jdbc-url: jdbc:mysql://${DOJ_DB_MASTER_HOST:mysql-write}:3306/${doj.db.name}?...
        ...
      slave:
        type: com.zaxxer.hikari.HikariDataSource
        jdbc-url: jdbc:mysql://${DOJ_DB_SLAVE_HOST:mysql-read}:3306/${doj.db.name}?...
        ...
    rules:
      readwrite-splitting:
        data-sources:
          doj-rw:
            type: Static
            props:
              write-data-source-name: master
              read-data-source-names: slave
            load-balancer-name: round_robin
        load-balancers:
          round_robin:
            type: ROUND_ROBIN

mybatis-plus:
  global-config:
    db-config:
      update-strategy: not_null            # 只更新非 null 字段（避免误清空）
      id-type: auto                        # 主键自增
```

**② problem-service `application.yaml` — 库名**

```22:24:DOJ-BE/problem-service/src/main/resources/application.yaml
doj:
  db:
    name: doj_problem
```

`shared-jdbc.yaml` 里的 `${doj.db.name}` 引用它 → 拼出 `jdbc:mysql://.../doj_problem`。

**③ `bootstrap.yaml` — 拉取共享配置**

```6:11:DOJ-BE/problem-service/src/main/resources/bootstrap.yaml
  config:
    import:
      - optional:nacos:shared-jwt.yaml
      - optional:nacos:shared-jdbc.yaml
      - optional:nacos:shared-rabbitmq.yaml
      - optional:nacos:shared-redis.yaml
```

**④ common 的 `MybatisConfig` — 分页插件**

```14:23:DOJ-BE/common/src/main/java/com/decade/doj/common/config/custom/MybatisConfig.java
@Configuration
@ConditionalOnClass(BaseMapper.class)
public class MybatisConfig {
    @Bean
    @ConditionalOnMissingBean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor paginationInnerInterceptor = new PaginationInnerInterceptor(DbType.MYSQL);
        paginationInnerInterceptor.setMaxLimit(1000L);
        interceptor.addInnerInterceptor(paginationInnerInterceptor);
        return interceptor;
    }
}
```

版本：根 pom `mybatis-plus 3.5.2`，`shardingsphere-jdbc-core-spring-boot-starter 5.2.1`，MySQL 8。

### 6.2 注入原理（启动链路逐步拆解）

```
① ShardingSphere Starter 自动配置
   spring.shardingsphere.* 生效
   → 创建 master（HikariDataSource → mysql-write/doj_problem）
   → 创建 slave（HikariDataSource → mysql-read/doj_problem）
   → 注册逻辑数据源 doj-rw（读写分离规则：写→master，读→slave，轮询）
   → doj-rw 成为容器内唯一的 DataSource Bean

② MyBatis-Plus Starter 自动配置（MybatisPlusAutoConfiguration）
   → 拿到 DataSource（即 doj-rw 逻辑数据源）
   → 创建 SqlSessionFactoryBean → SqlSessionFactory
     · 注入 MybatisPlusInterceptor（分页插件来自 common 的 MybatisConfig）
     · 加载实体类注解（@TableName / @TableId / @TableField）
     · 加载 XML（classpath:mapper/ProblemMapper.xml）
   → 创建 SqlSessionTemplate（线程安全，执行 SQL 的入口）

③ @MapperScan("com.decade.doj.problem.mapper")
   → MapperScannerRegistrar 扫描 Mapper 接口
   → 每个接口注册一个 MapperFactoryBean
   → 创建时调用 sqlSessionFactory.getMapper(接口) → 生成 MapperProxy 代理对象

④ 注入到 Service
   ProblemServiceImpl extends ServiceImpl<ProblemMapper, Problem>
   @RequiredArgsConstructor + private final ProblemMapper problemMapper（来自父类 baseMapper）
   → Spring 注入 MapperProxy 代理

  @MapperScan 扫描 → MapperProxy 代理
   → 方法调用触发 MappedStatement（预编译 SQL 模板）
   → MybatisPlusInterceptor 识别 Page 参数 → 改写 SQL（加 LIMIT + 生成 COUNT）
   → ShardingSphere 解析 SQL → SELECT 路由到 slave，UPDATE 路由到 master
   → MySQL 执行 → 结果集反射回实体类
```

### 6.3 使用原理

**① 内置 CRUD（BaseMapper）**

```18:24:DOJ-BE/problem-service/src/main/java/com/decade/doj/problem/mapper/ProblemMapper.java
public interface ProblemMapper extends BaseMapper<Problem> {

    Page<Problem> selectPageWithFilters(Page<Problem> page,
                                        @Param("ids") List<Long> ids,
                                        @Param("excludeIds") List<Long> excludeIds,
                                        @Param("difficulty") String difficulty,
                                        @Param("tagNames") List<String> tagNames);

}
```

调用 `baseMapper.selectById(id)` / `insert()` / `updateById()` / `selectList(wrapper)` 时：

```
ServiceImpl/业务代码 → MapperProxy → SqlSessionTemplate → SqlSession
   → 执行 MyBatis-Plus 预生成的 SQL（根据实体类注解自动拼表名/列名）
   → 经过 MybatisPlusInterceptor（分页插件在 Page 参数时自动加 LIMIT + 生成 count 查询）
   → DataSource = doj-rw 逻辑数据源
   → ShardingSphere 解析 SQL：
        SELECT → 路由到 slave（mysql-read）
        INSERT/UPDATE/DELETE → 路由到 master（mysql-write）
```

**② 实体类注解映射**

```26:32:DOJ-BE/problem-service/src/main/java/com/decade/doj/problem/domain/po/Problem.java
@TableName(value = "problem", autoResultMap = true)
@Schema(description = "Problem对象")
public class Problem implements Serializable {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    @TableField("name")
    private String name;
    ...
    @TableField(exist = false)
    private java.util.List<String> tags;   // 非数据库字段
```

- `@TableName("problem")`：类 → 表映射；
- `@TableId(type = IdType.AUTO)`：主键策略自增（配合 Nacos `id-type: auto`）；
- `@TableField("input_style")`：驼峰属性 → 下划线列名；
- `@TableField(typeHandler = JacksonTypeHandler.class)`：JSON 字符串 ↔ List 互转；
- `@TableField(exist = false)`：`tags` / `status` 是内存计算字段，不参与 SQL。

**③ 自定义 SQL（XML）**

`selectPageWithFilters` 没有内置实现，由 XML 提供：

```33:64:DOJ-BE/problem-service/src/main/resources/mapper/ProblemMapper.xml
<select id="selectPageWithFilters" resultMap="ProblemResultMap">
    SELECT p.*
    FROM problem p
    WHERE 1 = 1
    <if test="ids != null and ids.size > 0">
        AND p.id IN (...)
    </if>
    <if test="excludeIds != null and excludeIds.size > 0">
        AND p.id NOT IN (...)
    </if>
    <if test="difficulty != null and difficulty != ''">
        AND p.difficulty = #{difficulty}
    </if>
    <if test="tagNames != null and tagNames.size > 0">
        AND EXISTS (...)
    </if>
</select>
```

- 首参 `Page<Problem>` 让分页插件自动注入 LIMIT 并改写 count；
- 动态 SQL（`<if>/<foreach>`）在加载 XML 时由 MyBatis 解析为 SqlSource，执行时按参数拼接；
- `resultMap="ProblemResultMap"` 显式指定列↔属性映射（含 typeHandler）。

**④ 典型调用（Service 层分页）**

```136:152:DOJ-BE/problem-service/src/main/java/com/decade/doj/problem/service/impl/ProblemServiceImpl.java
Page<Problem> page = query.toMpPage("id", true);
Page<Problem> result = baseMapper.selectPageWithFilters(page, ids, excludeIds,
        query.getDifficulty(), query.getTags());

List<Problem> records = result.getRecords();
if (CollUtil.isNotEmpty(records)) {
    attachTags(records);
    if (userId != null) {
        List<Long> problemIds = records.stream().map(Problem::getId).collect(Collectors.toList());
        Map<Long, Integer> statusMap = toStatusMap(submissionClient.batchStatus(problemIds, userId));
        ...
    }
}
return PageDTO.of(result);
```

- `query.toMpPage("id", true)` 把 DTO 转成 MP 的 `Page` 对象；
- `Page` 对象被分页插件识别 → 自动执行 `SELECT COUNT(*)` + 改写 `LIMIT`；
- 题目对用户的"状态"不跨库查，改由 **Feign 调 submission-service** 批量获取后在内存合并（避免 ShardingSphere 跨库限制）。

---

## 7. 排查速查表

| 现象                    | 可能原因                                               | 排查点                                                                       |
| ----------------------- | ------------------------------------------------------ | ---------------------------------------------------------------------------- |
| 全局异常不生效          | `spring.factories` 被覆盖 / 主类包不在扫描范围         | 确认 common 在 classpath，`CommonExceptionConfig` 是否被加载（日志或 debug） |
| 拦截器不执行            | 主类 `@Import` 缺少 `MVCConfig`                        | 对照第 3.1 节各服务 Import 清单                                              |
| Feign 401 / userId 丢失 | `DefaultFeignConfig` 未作为 defaultConfiguration       | 确认 `@EnableFeignClients(defaultConfiguration = ...)` 已配                  |
| gateway 路由 404        | 路由表缺条目                                           | 查 `spring.cloud.gateway.routes`，确认 `uri: lb://xxx`                       |
| 分页不生效 / 返回全量   | `MybatisConfig` 未加载（`@ConditionalOnClass` 不满足） | 确认 mybatis-plus 依赖存在 + `@Import(MybatisConfig)`                        |
| 写入后从库查不到        | 读写分离主从延迟                                       | 改主键查询强制走主库或等待复制，注意事务内读写一致性问题                     |
| 跨库 join 报错          | ShardingSphere 单数据源限制                            | 按本项目做法：拆成 Feign 跨服务查询 + 内存合并                               |
