-- 会话属性表名对齐 Spring Session JDBC 的运行时拼写（cifix-20261106）。
--
-- 背景：spring-session-jdbc 4.x 按配置拼接属性表名：spring.session.jdbc.table-name + 固定
-- 大写后缀 "_ATTRIBUTES"（后缀不可配置）。本仓库 table-name=spring_session，运行时实际
-- 访问 spring_session_ATTRIBUTES；而 V1__baseline.sql 建表为全小写 spring_session_attributes
-- （V1 注释「官方 schema 表名小写」不准确——官方 schema-mysql.sql 是 SPRING_SESSION_ATTRIBUTES
-- 全大写后缀拼写）。Windows 开发机 lower_case_table_names=1 大小写不敏感，差异被掩盖；
-- CI（Ubuntu + mysql:8.4 容器，Linux 默认 lower_case_table_names=0）下表名大小写敏感，
-- 属性表读写全部「Table doesn't exist」：登录/注册在请求收尾保存会话失败（经 ERROR
-- dispatch 被 RestAuthenticationEntryPoint 收敛为 40100「请先登录」），按用户吊销会话
-- （findByPrincipalName）直接抛 BadSqlGrammar（AuthFlow / UserAdminLock 并发用例失败）。
--
-- 不能直接 RENAME TABLE 旧名 TO 新名：lower_case_table_names=1 的 Windows 上目标名小写后
-- 与源表同名，MySQL 报 1050 already exists（本地实测）。先改到临时名再改到运行时精确名，
-- lctn=0 与 lctn=1 两种取值均可执行；RENAME 单语句原子完成，结构与索引不变。
-- 手写 SQL（PartitionMaintenanceJob 孤儿清理、集成测试清理）已同步改用精确拼写。
RENAME TABLE spring_session_attributes TO spring_session_attributes_case_fix,
             spring_session_attributes_case_fix TO spring_session_ATTRIBUTES;
