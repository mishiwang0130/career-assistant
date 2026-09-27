# career-assistant

只有底层架子：Maven 聚合工程 + 一个能启动的 Spring Boot 应用 + 前端脚手架。业务代码自己写。

## 结构

```
career-assistant/
├─ pom.xml            聚合父 POM：Spring Boot 3.5.16 / JDK 17 / 依赖版本统一管理
├─ common/            共享模块（占位，空 jar）
├─ backend/           后端服务：启动类 + application.yml
├─ frontend/          Vue 3 + Vite + TS + Element Plus 脚手架
├─ sql/               建库脚本
└─ mvnw / .mvn        Maven Wrapper（3.9.11）
```

## 技术版本

| 组件 | 版本 |
| --- | --- |
| JDK | 17 |
| Spring Boot | 3.5.16 |
| AgentScope Java | 2.0.3（core / harness / model-dashscope / extensions-jdbc / extensions-redis） |
| MyBatis-Plus | 3.5.17（spring-boot3-starter + jsqlparser） |
| MySQL / Redis | 8.0 / 7.4 |
| Knife4j | 4.5.0 |
| 前端 | Vue 3.5 + Vite 6 + TS 5.7 + Element Plus 2.9 |

版本都写在父 POM 的 `dependencyManagement` 里，子模块引用时不用写版本号。

## 启动

中间件（需先在本地启动 MySQL 8 和 Redis 7.4）：

```bash
mysql -uroot -p < sql/career_assistant.sql
```

后端：

```powershell
.\mvnw.cmd -pl backend -am spring-boot:run     # http://localhost:8084
```

前端：

```powershell
cd frontend
npm install
npm run dev                                    # http://localhost:5173
```

接口文档 <http://localhost:8084/doc.html>，健康检查 <http://localhost:8084/actuator/health>。

## 数据库与 Redis 配置

`backend/src/main/resources/application.yml` 里默认是 `localhost:3306` / `root/root` 和 `localhost:6379`，
都支持环境变量覆盖：`MYSQL_HOST`、`MYSQL_PORT`、`MYSQL_DATABASE`、`MYSQL_USERNAME`、`MYSQL_PASSWORD`、
`REDIS_HOST`、`REDIS_PORT`、`REDIS_DATABASE`。

## 下一步

- 业务表 DDL 追加到 `sql/career_assistant.sql`
- 业务代码按领域分包写在 `backend/src/main/java/com/wxy/career/<领域>`
- 通用代码（统一响应、异常、工具类）放 `common` 模块，在 `common/pom.xml` 里加依赖
- AgentScope 的模型/会话/智能体装配写在 `backend` 里，配置项参考 `application.yml` 末尾注释
- 完整业务设计见 `docs/求职智能助手_系统计划书_V1.md`
