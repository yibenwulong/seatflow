# SeatFlow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将自有航空订票工程演进为演出选座后端，交付可复现业务、并发测试、恢复验证和 GitHub 开源仓库。

**Architecture:** 场次座位表由 MySQL 事务裁决，订单头、明细、预占和 Outbox 同事务提交。Redis 只缓存查询结果；支付适配器、退款任务、Outbox 投递和到期扫描通过稳定 ID 和条件状态迁移实现可重试处理。

**Tech Stack:** Java 17、Spring Boot 3.5.13、Maven、Spring JDBC 显式 SQL、MySQL、数据库迁移、Redis、RabbitMQ、JUnit、Mockito、HTTP 集成测试、Docker Compose、GitHub Actions。

**Spec:** `docs/superpowers/specs/2026-10-15-seatflow-design.md`

## Global Constraints

- 新工程根目录 `seatflow/`，Java 根包 `io.github.yibenwulong.seatflow`；所有以下路径相对此目录。
- Java 17、Spring Boot 3.5.13；采用缓存中可用的 Maven 3.9.16，Wrapper 指向相同版本。
- 默认仓库名 `seatflow`，目标账号 `yibenwulong`，MIT 许可证；用户已确认原代码开源权利。
- MySQL 最终裁决，Redis 仅查询缓存；不采用航空跨航段模型或独立数量二次扣减。
- EXPLICIT 每单 1 至 4 座，可分散；ADJACENT 为 2 至 4 座且同排、同区块、同票价区域、位置连续。
- 一单一场次；请求幂等键 `(userId, requestId)`，载荷摘要不一致返回 409。
- expireAt 为 createdAt 加 15 分钟与开演时间较早者；支付处理时 now 严格小于 expireAt 才可成交。
- Redis 座位图 TTL 为 5 秒；到期扫描间隔 5 秒；业务时间统一注入 Clock。
- Outbox 领取租约 30 秒，最多自动尝试 10 次，退避从 1 秒指数增长至 60 秒上限。
- 金额 BigDecimal；JSON Long ID 输出字符串；普通退款为开演前整单全额退款。
- Mock 支付默认启用；沙箱仅在明确配置下启用；不可提交旧 JWT CSV、个人地址、真实秘密和旧 Git 历史。
- 不可将 H2 或 Mock 测试称为真实 MySQL/Redis/MQ 集成测试；缺环境必须报告未运行。

## Review Focus

1. 截止前、恰好截止和截止后回调；扫描未执行时也应正确处理迟到收款。
2. 请求幂等键相同但载荷不同，以及两个并发相同请求的结果收敛。
3. 外部退款成功而本地事务失败，重试不得再次产生资金副作用或再次释放座位。
4. 发布成功但 Outbox 状态未保存，以及领取者进程退出后租约重领。
5. 场次布局快照、EXPLICIT 分散选座、ADJACENT 过道和禁售座打断连续性。

## 文件与责任边界

工程配置：`pom.xml`、`mvnw*`、`.mvn/wrapper/`、`.gitignore`、`.dockerignore`、`src/main/resources/application*.yaml`。

基础包：`common/ApiError.java`、`common/ApiExceptionHandler.java`、`common/ClockConfig.java`、`common/IdJsonConfig.java`、`SeatFlowApplication.java`。

数据：`src/main/resources/db/migration/`；`catalog/CatalogRepository.java`、`catalog/CatalogService.java`、`catalog/CatalogController.java`；领域记录类型按业务包独立。

选座：`seating/AdjacentSeatAllocator.java`、`seating/SeatMapService.java`、`seating/SeatMapCache.java`、`seating/SeatView.java`。

订单：`booking/OrderRepository.java`、`booking/OrderService.java`、`booking/OrderController.java`、`booking/CreateOrderRequest.java`、`booking/OrderView.java`、`booking/OrderExpiryJob.java`。

身份：`identity/AccountRepository.java`、`identity/AuthService.java`、`identity/AuthController.java`、`identity/AuthenticationFilter.java`、`identity/CurrentUser.java`、`identity/SecurityConfig.java`。

支付：`payment/PaymentGateway.java`、`payment/MockPaymentGateway.java`、`payment/AlipaySandboxGateway.java`、`payment/PaymentService.java`、`payment/RefundService.java`、`payment/RefundWorker.java`、支付 Controller 和请求/结果记录。

消息：`messaging/OutboxRepository.java`、`messaging/OutboxPublisher.java`、`messaging/OrderEventConsumer.java`、`messaging/RabbitConfig.java`。

测试与工具：`src/test/java/.../`、`src/test/resources/`、`tools/demo.*`、`tools/check-order.*`、`docs/testing/`、`.github/workflows/ci.yml`。

## Task 1 可运行工程 数据迁移和演示夹具

**Files:** 工程配置、SeatFlowApplication、common 包、迁移、catalog 包；测试 `SchemaMigrationIT`、`CatalogApiTest`。

**Interfaces:**

- 产生 `Clock` Bean，默认 UTC；测试可替换。
- 产生 `CatalogService.listShows(String city, LocalDate date)`、`listSessions(long showId)`、`getSession(long sessionId)`。
- `SessionSeat` 快照含 sessionId、seatId、rowNo、seatNo、position、blockId、priceZone、price、status、ownerOrderId、holdExpiresAt。
- 数据库迁移建完整约束：session-seat 唯一、用户请求幂等键唯一、订单明细座位唯一、商户支付请求号及网关交易号唯一、退款号/eventId 唯一。

- [ ] 写迁移与接口测试：从空数据库迁移成功；同一迁移重跑无重复种子；未来两场次复用同厅座位但库存独立。
- [ ] 运行测试，记录缺失工程或表结构的预期失败。
- [ ] 创建工程、迁移及本地演示夹具；用 JDBC 显式映射关键字段。演示布局含至少两排、两个过道区块及一处禁售座，区域票价可区分。
- [ ] 运行 `mvn test` 和具备真实依赖时的 `mvn -Pintegration verify`；真实环境测试明确标识为 IT。
- [ ] 小步提交工程和迁移，不包含原工程文件与秘密。

## Task 2 登录 权限和接口错误契约

**Files:** identity 包、common 错误处理；测试 `AuthServiceTest`、`AuthorizationApiTest`。

**Interfaces:**

- `AuthService.login(String username, String password) -> LoginResult`。
- `CurrentUser(long id, String username, Role role)` 由验证后的身份构造，业务不信任请求体 userId。
- `requireUser()` 与 `requireAdmin()` 为 Controller 入口提供可信上下文。
- 统一错误体 `code,message,requestId`，状态码按设计；令牌与密码不写日志。

- [ ] 先写正确/错误密码、缺失/过期令牌、USER 调用 ADMIN API、查询其他用户订单的失败测试。
- [ ] 运行测试确认缺失实现导致失败。
- [ ] 实现 BCrypt、令牌签发验证及角色保护；local/test 生成临时密钥，其他配置缺密钥拒绝启动；开发种子账号仅 local/test 建立。
- [ ] 验证所有身份用例及 JSON ID 为字符串。
- [ ] 提交身份与错误契约。

## Task 3 连座算法和场次座位图

**Files:** AdjacentSeatAllocator、SeatView、SeatMapService、SeatMapCache；测试 `AdjacentSeatAllocatorTest`、`SeatMapCacheIT`。

**Interfaces:**

- `AdjacentSeatAllocator.candidates(List<SeatView> seats, int count, String priceZone) -> List<List<Long>>`，返回稳定排序的合法 seatId 组合。
- `SeatMapService.getSeatMap(long sessionId) -> SeatMapView`，含布局、状态、价格和 asOf。
- `SeatMapCache.get(long sessionId)`、`put(SeatMapView view)`、`evict(long sessionId)`；Redis 失败不影响数据库裁决。

- [ ] 写 2/3/4 座、跨过道、跨排、缺座、禁售、已占座、无组合、乱序输入和非法 count 的测试；枚举小布局与独立简单判定器比较。
- [ ] 运行算法测试确认失败。
- [ ] 实现纯函数候选分配和缓存适配；缓存 TTL 5 秒；布局使用 SessionSeat 完整快照。
- [ ] 使用真实 Redis 验证缓存命中、过期、失效与故障回源；改变 HallSeat 后已有 SessionSeat 布局不变。
- [ ] 提交选座和缓存功能。

## Task 4 原子建单 幂等与释放

**Files:** booking 包、OutboxRepository 基础写入接口；测试 `OrderServiceIT`、`SeatCompetitionIT`、`ReleaseReplayIT`。

**Interfaces:**

- `CreateOrderRequest(String requestId, long sessionId, SelectionMode mode, List<Long> seatIds, Integer count, String priceZone)`。
- `OrderService.create(long userId, CreateOrderRequest request) -> OrderView`。
- `OrderService.cancel(long userId, long orderId) -> OrderView`。
- `OrderService.expire(long orderId, Instant now) -> boolean`；已处理返回无变化。
- `OrderRepository.lockOrder(long orderId)`，所有同订单状态流程先锁订单再处理座位。
- `OutboxRepository.append(String type, long aggregateId, String payload)` 必须复用调用方事务。

- [ ] 写 EXPLICIT 1/4 座和分散座成功、非法/重复/跨场次座位拒绝、ADJACENT 不跨过道的测试。
- [ ] 写 32 用户同座竞争、重叠连座、同 requestId 并发复用结果、相同 requestId 不同载荷 409、失败无部分占用的真实数据库测试。
- [ ] 运行失败测试，确认尚未实现核心语义。
- [ ] 实现订单头、明细、HELD 归属与 Outbox 同事务；所有座位更新核对期望行数；自动模式最多三个独立事务重试，指定模式冲突直接失败。
- [ ] 实现按 ownerOrderId 与 HELD 条件释放；expireAt 取 15 分钟或开演时间较早值。
- [ ] 用可变 Clock 验证截止前与截止时释放；R1 释放后 R2 复用，再重放 R1 不改变 R2；取消重放无副作用。
- [ ] 运行订单、算法和权限回归，提交。

## Task 5 模拟支付 沙箱边界和可恢复退款

**Files:** payment 包、订单支付状态迁移、PaymentRecord/RefundRecord 仓储；测试 `PaymentStateIT`、`RefundRecoveryIT`、`PaymentAuthorizationTest`。

**Interfaces:**

- `PaymentGateway.create(PaymentIntent intent) -> GatewayPayment`。
- `PaymentGateway.verifyNotification(NotificationInput input) -> VerifiedPayment`。
- `PaymentGateway.refund(RefundInstruction instruction) -> GatewayRefund`；支持用稳定请求号查询原结果。
- `PaymentService.create(long userId, long orderId) -> PaymentView`，复用每订单稳定商户支付请求号。
- `PaymentService.confirm(VerifiedPayment payment) -> void`，订单行锁内依据 Clock 裁决。
- `RefundService.request(long userId, long orderId) -> RefundView`。
- `RefundService.process(long refundId) -> void`，网关调用与本地事务分开；NORMAL 与两类资金补偿分开收尾。

- [ ] 写本人支付、他人拒绝、金额/商户不匹配、重复通知、第二笔不同交易号收款测试。
- [ ] 写截止前、恰好截止、截止后及过期未扫描的支付测试；用屏障构造支付赢和取消赢两种时序。
- [ ] 写退款网关成功后本地提交失败的恢复测试：外部成功事实保留、refundId 不变、只释放一次；迟到退款不释放新订单座位。
- [ ] 运行失败测试，再实现独立于订单事务的 Fake 网关事实、支付校验、持久退款任务与短事务收尾。
- [ ] 实现可选支付宝沙箱适配；无配置默认不装配，验证通知签名、商户和金额；未实际联调应明确报告。
- [ ] 执行资金、权限及订单回归并提交。

## Task 6 Outbox RabbitMQ 到期扫描和故障恢复

**Files:** messaging 包、OrderExpiryJob、RefundWorker；测试 `OutboxDeliveryIT`、`ExpiryFallbackIT`、`MessageReplayIT`。

**Interfaces:**

- `OutboxRepository.claim(Instant now, Duration lease, int limit)`、`markSent(eventId)`、`markFailed(eventId, nextAttemptAt, reason)`。
- `OutboxPublisher.publishBatch()` 只在确认且未 return 后标记成功。
- `OrderEventConsumer.handle(OrderEvent event)` 对数据库副作用与去重记录同事务。
- `OrderExpiryJob.runOnce(Instant now)` 调统一 expire 服务；`RefundWorker.runOnce(Instant now)` 恢复待处理退款。

- [ ] 写发布后 markSent 失败重投、领取者崩溃租约到期重领、不可路由 return、重复 eventId、重试耗尽保留 FAILED 的测试。
- [ ] 写 MQ 不可用但 Clock 推进后扫描仍取消并释放的测试。
- [ ] 运行失败测试，再实现 30 秒租约、10 次退避重试、确认和 return 处理、缓存失效和到期提示消费。
- [ ] 验证消息处理异常不会先提交去重记录；模拟网关退款补尾可重复运行。
- [ ] 真 RabbitMQ 集成与全部订单测试通过后提交；Broker TTL 测试独立标注实测条件。

## Task 7 演示 诊断工具 文档与 CI

**Files:** Dockerfile、compose.yaml、.env.example、tools/、docs/testing/、README、LICENSE、.github/workflows/ci.yml。

**Interfaces:**

- 演示脚本完成登录、场次座位查询、两连座下单、模拟付款、查询和退款。
- 一致性检查工具接受 orderId，输出订单、明细、座位归属及资金进度的 JSON 检查结果；只检查当前部署环境。
- `mvn test` 为快速测试；`mvn -Pintegration verify` 为真实依赖集成入口；无依赖明确失败，不静默跳过。

- [ ] 先为检查工具写正常/部分座位不一致/旧所有者/待退款的固定数据测试。
- [ ] 实现工具、隔离夹具和可控故障入口；故障入口只存在 test 配置，不能作为公开管理接口。
- [ ] 编写多阶段镜像、四服务 Compose 健康检查与演示数据；固定实际验证的镜像版本。
- [ ] CI 分别执行快速与真实集成测试，归档测试报告和失败日志；核心分支覆盖作为改进依据，不编造覆盖率。
- [ ] 编写 API、架构边界、三专项的复现及结果、来源说明和 MIT；不声称未测的 QPS、生产高可用或沙箱联调。
- [ ] 在具备 Docker 的环境运行干净启动与演示；本机缺环境时在报告中明确，CI 验证后再完成对应验收。
- [ ] 提交开源运行及质量工程资料。

## Task 8 发布准备 审查与 GitHub 验证

**Files:** 新工程实际拟发布的全部文件；发布说明。

- [ ] 检查编译、快速测试、真实依赖测试、三专项与演示证据，修复实际失败。
- [ ] 用独立代码审阅检查事务、所有权、资金、权限、时序和恢复；处理必须修复项并运行相关回归。
- [ ] 扫描拟提交文件与打包范围：无旧令牌 CSV、个人配置、构建目录、临时产物、秘密及无关文档。
- [ ] 建立新项目独立 Git 历史；只包含 seatflow 新工程及设计/实施材料。Git 元数据写入若被环境限制，按实际权限处理并说明。
- [ ] 创建并发布 `yibenwulong/seatflow` 公共仓库；已有用户授权发布，无需重复询问是否公开。若连接器不能建仓库，优先使用可用的登录浏览器或正规 CLI 授权方式，不提取或猜测凭证。
- [ ] 若发布条件需要用户补充操作，先完成本地代码、测试和审查，再说明具体缺口；不把未发布说成已完成。
- [ ] 核验远端文件、默认分支、README 与 CI 结果，交付 GitHub URL、运行说明及真实测试结论。

## 执行依赖和可并行部分

Task 1 定义公共记录、SQL 和迁移后，Task 2 的身份层与 Task 3 的纯选座算法可由独立任务并行。Task 4 依赖数据、身份和选座契约。Task 5、Task 6 可在 Task 4 状态与 Outbox 接口稳定后分别推进，再联合验证。Task 7 的文档和容器资料可随接口稳定逐步完善；Task 8 必须在全项目验收后执行。

每个实现任务先写能说明需求的失败测试，再实现最小代码，运行相关测试并审查。只有实际通过的步骤才勾选；组件缺失、网络限制和未执行项都保留明确状态。

## 计划自审

- 设计的领域、选座、状态、权限、支付、恢复、开源要求均有对应任务。
- Review Focus 的五类边界分别在 Task 3 至 Task 6 有明确测试。
- 接口命名统一；数据库最终裁决不依赖 Redis 可用性。
- 计划没有将模拟验证、H2 测试或源码推断代替真实中间件证据。
- 当前仅完成设计和计划，尚未创建业务代码、执行上述测试或发布仓库。
