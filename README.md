# SeatFlow

演出选座 REST 后端，用可重复测试展示事务、并发、幂等与故障恢复。Java 17 / Spring Boot 3.5.13 / MySQL / Redis / RabbitMQ；默认支付为本地 Mock。

MySQL 决定座位归属，Redis 只缓存座位图。支持指定 1–4 座、同排同区块同票价区的 2–4 连座、15 分钟支付期限、取消、到期回收及开演前整单退款。订单、明细、座位预占和 Outbox 在一个事务提交。

## 本地运行

需要 Docker Compose、Python 3.13。首次启动会创建明确标注的虚构演示数据。

```bash
cp .env.example .env
docker compose config --quiet
docker compose up --build --wait --wait-timeout 300
python tools/demo.py
```

PowerShell 复制命令为 `Copy-Item .env.example .env`。访问 `http://localhost:8080/api/v1/shows`。演示用户 `demo`、管理员 `admin` 的示例密码均为 `demo-seatflow`。演示脚本实际调用登录、场次、座位图、两连座下单、Mock 完成、订单查询和退款接口，并等待退款完成；它不输出令牌。每次演示使用新的 requestId，正常退款后座位可复用。

`docker compose down` 保留数据。仅在要清除当前演示数据时使用 `docker compose down --volumes`；不要在需要保留订单的环境执行。切换生产配置应使用全新数据库，不能继续使用演示库。

Compose 的 `local` profile 会生成启动期临时签名密钥，重启后原令牌失效。非开发环境必须提供 `SEATFLOW_AUTH_SIGNING_KEY`，值为至少 32 字节随机材料的 Base64，并自行创建账号。`.env.example` 的口令仅供可丢弃的本地演示，不是生产默认凭证。数据库与消息端口默认不映射到宿主机。

无 Docker 时可使用 Java 17 与独立 MySQL、Redis、RabbitMQ；环境变量见 [运行说明](docs/running.md)。Wrapper 固定 Maven 3.9.16，Windows 使用 `mvnw.cmd`，Linux/macOS 使用 `./mvnw`。

## 测试与诊断

```bash
./mvnw test
python -m pip install -r tools/requirements.txt
python -m unittest discover -s tools/tests -v
# 按 docs/testing/README.md 配置真实依赖环境变量后：
./mvnw -Pintegration verify
python -m tools.mysql_smoke
```

快速测试中的 H2 和 Mock 网关不代表真实 MySQL 或支付宝联调。真实依赖集成测试缺配置会失败，不静默跳过。CI 分别执行快速测试、真实三依赖集成测试和干净 Compose 启动/API 演示，归档报告与日志。

只读检查当前部署中的一个订单：

```bash
python tools/consistency.py ORDER_ID
```

先配置六个 `SEATFLOW_CHECK_MYSQL_*` 变量，详见 [检查器](docs/testing/consistency.md)。它输出订单、明细、当前座位所有者、收款与退款进度的同一数据库快照；返回码为 0（一致）、1（业务不一致或订单不存在）、2（配置或数据库读取失败）。它不会改写或修复数据。

## 设计与证据

- [API](docs/api.md)、[架构与边界](docs/architecture.md)、[数据库](docs/database.md)、[身份认证](docs/authentication.md)、[订座事务](docs/booking.md)
- [测试入口与验证状态](docs/testing/README.md)、[三项并发/恢复实验](docs/testing/experiments.md)、[开发缺陷复盘与测试开发面试说明](docs/testing/engineering.md)
- [已批准设计与计划](docs/superpowers/README.md)、[来源与许可](docs/provenance.md)

已保留的真实 MySQL 竞争观察包括：32 用户抢一座，1 成功、31 冲突，数据库只有 1 单/1 明细/1 占座；24 个重叠连座请求，4 成功、20 冲突，4 单完整占有 10 座。原始计数见 [JSON](docs/testing/booking-observations.json)。这些是正确性实验，不是吞吐或延迟基准。

当前交付阶段已验证 Java 快速测试、检查器固定案例和原生 MySQL 检查器 smoke。此工作站没有 Docker/RabbitMQ；完整支付演示和消息/容器验收待合并已审阅运行时代码及 CI 实际执行，状态详见测试文档。项目不声称生产高可用、支付宝沙箱联调或未经实测的 QPS。

Copyright © 2026 yibenwulong。采用 [MIT License](LICENSE)。
