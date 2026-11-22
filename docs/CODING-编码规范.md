# Echo 编码规范

## 一、总则

注释服务于代码，解释代码无法表达的设计决策与业务含义。规范的目标是让读者在没有作者讲解的情况下理解一段代码为什么这样写。

四条底线：

- 简洁：注释长度与信息量匹配，单块注释控制在 5 行以内。
- 一致：同类代码使用相同的注释风格。
- 同步：代码修改时同步更新注释，注释与实现始终一致。
- 纯文本：注释只使用中文与必要的英文术语，不使用图形符号。

## 二、语言与命名

注释使用中文，专业术语保留英文（ID、API、JSON、Token）。命名规则：

| 元素 | 规则 | 示例 |
|---|---|---|
| 类 / 接口 | 大驼峰，名词或名词短语 | `TicketOrderServiceImpl`、`SeckillQueueConsumer` |
| 方法 | 小驼峰，动词开头 | `seckillTicket`、`queryMyOrders`、`enrichOrderRecords` |
| 变量 | 小驼峰，含义完整 | `currentRoleLevel`、`retryCount` |
| 常量 | 全大写下划线分隔 | `QUEUE_MAX_SIZE`、`SECKILL_ORDER_TOPIC` |
| 布尔 | `is` / `has` / `can` 开头 | `isSuperAdmin`、`hasMore`、`canRefund` |
| 包 | 全小写，单数 | `com.echo.controller`、`com.echo.aspect` |

常量集中在 `com.echo.constants`（业务文案）、`com.echo.utils.RedisConstants`（键名）、`com.echo.utils.MqConstants`（主题与标签），业务代码通过常量引用，不写裸字符串。

## 三、文件级注释

类、接口、枚举使用块注释，第一行是功能描述：

```java
/**
 * 演出实体类
 *
 * @author tulip
 */
public class Show {
```

实体类注释说明对应表名与用途，控制器注释说明接口范围，枚举注释说明取值含义。

## 四、字段注释

简单字段用单行注释：

```java
/** 用户昵称 */
private String nickName;

/** 创建时间 */
private LocalDateTime createTime;
```

需要解释单位、枚举取值或格式的字段用块注释：

```java
/**
 * 支付金额（单位：分）
 */
private Long payValue;

/**
 * 状态：0=下架, 1=上架, 2=已结束
 */
private Integer status;
```

状态类字段的取值与 `com.echo.enums` 下的枚举保持一致，枚举新增取值时同步更新字段注释与数据库字段注释。

## 五、方法注释

公共方法使用块注释，说明功能、参数与返回值，行数随复杂度在 2 到 4 行之间：

```java
/**
 * 查询用户详情并装配扩展信息
 *
 * @param userId 用户 ID
 * @return 用户详情，用户不存在时返回失败结果
 */
public Result<?> getUserInfoById(Long userId) {
```

私有方法使用单行注释概括意图：

```java
// 装配订单展示字段，合并用户、票种、演出信息
private List<Map<String, Object>> enrichOrderRecords(List<TicketOrder> orders) {
```

方法体内部的分支说明同样用单行注释，写在分支上方，说明「为什么」而不是复述「做了什么」。

## 六、常量与枚举注释

```java
/** 手机号不能为空 */
public static final String PHONE_EMPTY = "手机号不能为空";

/** 上架状态 */
ONLINE(1, "上架");
```

消息文案常量的注释写清使用场景，键名常量的注释写清数据结构（String / Set / ZSet）与过期策略。

## 七、分层要点

| 层 | 类注释 | 成员注释 |
|---|---|---|
| `entity` | 对应表名与业务对象 | 字段业务含义、枚举取值、单位 |
| `dto` | 用途与来源 | 校验规则、必填项 |
| `constants` | 所属模块 | 文案的使用位置 |
| `config` | 配置功能与生效条件 | Bean 的作用 |
| `controller` | 接口范围与路径前缀 | 接口功能，配套 `@Operation` |
| `service` / `impl` | 业务边界 | 关键分支的决策依据 |
| `aspect` | 切面职责与织入顺序 | 拦截与放行条件 |
| `task` / `mq` | 调度周期或订阅关系 | 幂等策略与失败处理 |

控制器方法的描述优先写在 `@Operation(summary = "...")` 中，Swagger 与接口文档共用这一份文案。

## 八、书写要求

- 注释与代码保持同一粒度：复杂的算法与状态迁移需要说明，直接赋值与循环不需要。
- 单块注释控制在 5 行以内，超出时把细节移入方法命名或抽取方法。
- 注释里给出取值范围、单位、边界条件，替代举例说明。
- 修改逻辑时同步修改注释；删除逻辑时同步删除注释。
- 通过重命名与拆分方法表达意图，注释用于补充意图之外的约束条件。

## 九、版本

| 版本 | 日期 | 修改内容 | 作者 |
|---|---|---|---|
| 2.0 | 2026-10-24 | 精简注释规范，强调简洁性 | tulip |
| 3.0 | 2026-11-10 | 补充命名规则与分层要点，统一为编码规范 | Echo Team |
