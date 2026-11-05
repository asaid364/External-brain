# LifeRecord 局域网同步协议

本目录定义 protocol1 / data schema4 JSON协议，包括目标定义、用户里程碑声明与完整周期分析；同意扩大范围后才双向交换。协议端点、类型、字段、身份和错误见[`protocol-v1.md`](protocol-v1.md)，DTO由`packages/shared-core/.../SyncWire.kt`唯一实现。协议不传SQLite文件、AI Key/配置、本机外发/自动许可、生成运行或服务冷却状态；实际验收边界见工程状态。

## 固定约束

- 时间携带原当地日期、IANA时区、原UTC offset与epoch；导入不因接收设备时钟不同重算。
- 记录、同一共享草稿、日报候选、独立答案/跳过、稳定历史、生命事件及purge fence使用稳定身份；重放幂等。
- 双向实体causal heads与提交游标分离；迟到/未见分支不由时钟胜出。冲突需人工解决，彻删吸收且不可恢复。
- 初次配对与两端发送/接收同意分开确认；确认双方同意前状态不泄露内容统计，正文/删除fence不传输。
- AI Key/同意永不跨设备；Windows只手动生成，Android保留既有自动设置。镜像删除不是备份。

## 传输摘要

发送端在短数据库事务固定游标与当前全量必要实体状态，将一致快照写入`sync_exports`/`sync_export_parts`。完整迁移使用`full=true`，即使throughSeq为0也不能省略数据。JSON UTF-8 NDJSON被切成≤256KiB任意字节分片，再逐片Base64，禁止错误解码字节边界；part/有序hash集合用SHA-256，manifest digest采用固定字段编码。

接收端先校验并持久存储全部part，再在单事务合并业务态/接收游标/`sync_applied_exports`回执，事务提交后ACK。相同exportId+hash可回复原ACK，不同hash、游标缺口、数据库/空间不匹配、未知实体schema整批拒绝。发送端仅验证ACK后更新peer游标；purge使旧正文导出失效，收到fence后旧快照不复活。

配对层采用选定私有接口的HTTPS、自签TLS证书SHA256 pin、默认hostname校验、bearer只置Authorization header；mDNS只是地址提示。没有wildcard监听、系统证书信任、HTTP代理、跨重定向凭据、CORS或防火墙自动更改。

协议源码及本轮分层实测边界见[项目门禁/工程状态](../../docs/engineering/status.md)与[安卓实现验收](../../apps/android/README.md#本轮验证及边界)。Android真正物理LAN/mDNS/WorkManager验收尚未宣称通过。
