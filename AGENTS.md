# 仓库开发规范

## 项目结构

- 根 `pom.xml`：Maven 聚合工程，统一管理 JDK 17、Spring Boot 3.5.16 等版本；子模块引用依赖不写 `version`，版本统一放在父 POM 的 `dependencyManagement`。
- `common/`：共享模块，放统一响应、全局异常、JWT、Redis、MyBatis-Plus 等公共能力，按能力分包。
- `backend/`：后端服务，启动类 `CareerAssistantApplication` 位于 `com.wxy.career`，组件扫描范围即该包。
- `frontend/`：Vue 3 + Vite + TypeScript + Element Plus 前端工程。
- `sql/career_assistant.sql`：建库与表结构脚本，新增业务表追加到该文件。

## 后端模块划分

后端采用按层分包，所有同类代码集中放在同一包下，不按业务域拆子包：

```
com.wxy.career
├─ controller/   所有 Controller
├─ service/      Service 接口
│  └─ impl/      Service 实现
├─ mapper/       MyBatis-Plus Mapper 接口
├─ po/           数据库实体类，与表一一对应
├─ vo/           请求与响应 VO
├─ job/          定时任务
├─ middleware/   AgentScope 中间件（埋点、提示词等横切技术组件）
├─ util/         工具类
└─ config/       配置类
```

- Mapper XML 放在 `backend/src/main/resources/mapper/`，该路径已在 `application.yml` 中配置。
- `middleware/` 于 M2 引入：AgentScope 的中间件属于横切技术组件，既不是配置类也不是普通工具类，单独成包便于后续模块按同一约定扩展（详见 `docs/技术约定.md`）。
- 只有 Mapper 接口允许继承 MyBatis-Plus（统一继承 `BaseMapper<T>`）；Service 接口和 Service 实现禁止继承 MyBatis-Plus 的 `IService`、`ServiceImpl` 等基类，业务逻辑手写在 Service 实现中并通过 Mapper 操作数据库。
- 数据库实体统一放 `po` 包，类名与表名对应，如 `sys_user` → `SysUser`。
- 只有被接口使用的参数或返回值才建 VO：请求参数用 `xxxReqVO`，返回值用 `xxxRespVO`，例如 `UserLoginReqVO`、`UserInfoRespVO`。
- Controller 只做参数校验和调用 Service，不写业务逻辑；事务放在 Service 层。

## 命名与编码规范

- Java 使用 4 空格缩进、UTF-8、JDK 17，使用 Lombok 简化样板代码。
- 类名 PascalCase，方法与字段 camelCase，常量 UPPER_SNAKE_CASE。
- 数据库字段 snake_case，Java 字段 camelCase，依赖 MyBatis-Plus 的 `map-underscore-to-camel-case` 自动映射。
- 前端使用 2 空格缩进、单引号，字符串末尾不加分号，TypeScript 开启 strict，不使用 `any`。

## 注释规范

- 每个类和接口都必须写 Javadoc 注释，说明职责与使用场景，并按《阿里巴巴Java开发手册》要求标注 `@author`、`@date`。
- 每个方法都必须有注释，包含用途说明；有参数、返回值或异常时补充 `@param`、`@return`、`@throws`。
- 每个类字段都必须有注释，包括 PO、VO、常量、配置项等，说明含义、取值范围、单位或约束；PO 字段注明对应数据库字段的含义，VO 字段注明业务含义。
- 字段注释写在字段上方，统一使用 `/** ... */` 风格，禁止使用行尾 `//` 注释。
- 类和方法统一使用 `/** ... */` 风格，禁止用 `//` 或 `/* */` 代替 Javadoc。
- 每一段有意义的代码都要加注释，重点解释"为什么这么做"、业务规则、边界条件和特殊处理，不要重复描述代码表面逻辑。
- 复杂分支、循环、事务、缓存、并发和第三方调用前后必须注释说明意图。
- 修改代码时同步更新对应注释，禁止保留被注释掉的废弃代码和无意义注释。
- 前端页面、组件和复杂函数需在顶部或关键逻辑处写注释说明用途，避免逐行翻译式注释。

## Spring 依赖注入与事务规范

- Bean 注入统一使用 `@Resource`（`jakarta.annotation.Resource`），禁止使用 `@Autowired`；优先按字段名匹配，字段名与 Bean 名保持一致。
- 所有涉及数据库写操作（新增、修改、删除、批量导入等）的 Service 方法必须加事务注解，并显式指定回滚范围：

```java
@Resource
private UserMapper userMapper;

@Transactional(rollbackFor = Exception.class)
public Long createUser(UserCreateReqVO reqVO) {
    // 写库逻辑
}
```

- 事务注解只加在 Spring 管理的 public 方法上，放在 Service 实现层，不加在 Controller、Mapper 或私有方法上；类内自调用不会开启事务，需要拆到独立 Service。
- 单条查询不需要事务；需要一致性的多次查询可加 `@Transactional(readOnly = true)`。
- 未在本文件明确写到的开发细节，一律参照《阿里巴巴Java开发手册》执行，例如禁止魔法值、避免 `Executors` 创建线程池、`equals` 用常量或确定非空对象调用、POJO 布尔字段不加 `is` 前缀等。

## 接口响应与异常约定

- 所有接口统一返回 `Result<T>`，字段固定为 `code`、`msg`、`data`。
- 成功和业务异常（`BizException`）统一返回 HTTP 200，失败语义全部由自定义 `code` 表达；禁止用 HTTP 400、403 等状态表示业务失败。
- 只有鉴权失败返回 HTTP 401：未登录、Access Token 失效、Refresh Token 失效，前端据此自动刷新 token 或跳转登录页。
- 参数校验失败返回 400，路由资源不存在返回 404，未捕获的系统异常返回 500。
- 业务错误统一通过 `throw new BizException(ErrorConstant.XXX)` 抛出，错误码只在 `ErrorConstant` 中新增，Controller 不得拼装错误响应。
- 完整接口约定以 `docs/技术约定.md` 为准，新增或修改接口时必须同步更新该文档。

## 前端规范

- 目录：`src/api/` 接口请求、`src/views/` 页面、`src/components/` 通用组件、`src/stores/` Pinia 状态、`src/router/` 路由、`src/utils/` 工具函数、`src/types/` 类型定义。
- 组件文件用 PascalCase（`UserCard.vue`），组合式函数用 `useXxx`（`useUserStore.ts`），其余文件 camelCase。
- API 按业务拆分（`src/api/user.ts`），函数名使用 `getUserList`、`createUser` 等动词开头的 camelCase；接口类型沿用后端 `XxxReqVO` / `XxxRespVO` 命名。
- 路由 path 用 kebab-case（`/user/list`），路由 name 用 PascalCase（`UserList`）。
- 统一通过 `src/api/request.ts` 封装的 axios 实例发请求，接口地址以 `/api` 开头，由 Vite 代理到 `8084`。
- 使用 `@/` 别名指向 `src/`；通用组件不得反向依赖具体页面。
- UI 统一使用 Element Plus；跨页面共享状态放 Pinia，组件内部状态留在组件内。

## 构建与运行命令

- `.\mvnw.cmd clean install`：从根目录构建全部模块。
- `.\mvnw.cmd -pl backend -am spring-boot:run`：启动后端，地址 `http://localhost:8084`。
- `.\mvnw.cmd test`：运行 Maven 测试（当前尚无测试代码）。
- `mysql -uroot -p < sql/career_assistant.sql`：初始化数据库。
- `cd frontend; npm install; npm run dev`：启动前端，地址 `http://localhost:5173`。
- `npm run build`：执行 `vue-tsc` 类型检查并打包。

## 测试规范

- 当前仓库尚未提交测试代码，也没有覆盖率门槛。
- 后端测试放在 `backend/src/test/java`，包结构与主代码一致，类名以 `Test` 结尾；引入首个测试时补充 `spring-boot-starter-test` 依赖。
- 前端测试使用 Vitest，文件名以 `.spec.ts` 结尾，与源码同目录或放入 `__tests__`。
- 提交前至少执行 `.\mvnw.cmd test` 和 `cd frontend; npm run build`，并保证 `/actuator/health` 正常。

## 分支创建规范

- `dev` 是测试环境，始终保持最新代码；`main` 是正式环境，只存放经过测试、可以发布的代码。
- 所有功能开发和缺陷修复都必须从 `dev` 新开分支进行，完成并自测后合并回 `dev`，禁止直接在 `dev` 或 `main` 上修改后提交。
- 发布时由 `dev` 合并到 `main`，合并前确认 `dev` 已通过测试。
- 分支命名格式为 `<原分支>-{feature|bugfix}/<年月日>-<分支功能名>`，其中 `年月日` 为创建分支当天的 8 位日期，功能名必须使用简短的 kebab-case 英文，禁止出现中文。
- `feature` 用于新功能，`bugfix` 用于缺陷修复。
- 示例：

```
dev-feature/20260927-user-register
dev-bugfix/20260927-fix-login-token
```

- 分支合并后及时删除，避免残留过期分支。

## Git 提交规范

采用 Conventional Commits，格式为 `<type>(<scope>): <描述>`：

- `type`：`feat` 新功能、`fix` 修复、`refactor` 重构、`docs` 文档、`style` 格式调整、`test` 测试、`chore` 构建或依赖、`perf` 性能优化。
- `scope`：模块名，如 `backend`、`frontend`、`common`、`sql`；影响多个模块时可省略。
- 描述使用中文或英文均可，动词开头、简洁明确，不超过 50 字，句末不加句号。
- 一次提交只做一件事，不把格式化、重构和功能混在一起；禁止提交 `target/`、`node_modules/`、`dist/` 和任何密钥。
- 示例：`feat(backend): 新增用户注册接口`、`fix(frontend): 修复登录页 token 未持久化`、`docs: 补充接口说明`。

## Pull Request 要求

- 说明改动范围、实现思路和影响面，并关联相关 issue。
- 列出验证命令与结果；涉及数据库或配置变更时明确说明。
- 前端页面改动附截图，接口改动附请求与响应示例。
- 功能分支至少一名 reviewer 通过后合并回 `dev`；`main` 只接受来自 `dev` 的发布合并。

## 配置与环境隔离

- Spring 配置必须按 Profile 隔离，禁止把数据库、Redis、JWT、模型密钥等环境相关配置集中写在 `application.yml`。
- 配置文件划分固定为：
  - `application.yml`：只放跨环境公共的非敏感配置，默认激活 `local`。
  - `application-local.yml`：本地开发环境，可使用本地明文默认值。
  - `application-dev.yml`：测试环境，可使用测试环境明文配置，便于部署。
  - `application-prod.yml`：生产环境，数据库、Redis、JWT、模型密钥全部强制从环境变量读取。
- 切换环境统一使用 `SPRING_PROFILES_ACTIVE`，例如 `$env:SPRING_PROFILES_ACTIVE = "dev"`。
- 新增配置项必须在 `local`、`dev`、`prod` 对应文件中同步补齐，并按环境敏感度决定是否允许明文。
- `DASHSCOPE_API_KEY` 等密钥在任何环境都不得写入 YAML，只能从环境变量读取；本地私有覆盖放已忽略的 `application-*.local.yml`。
- 环境隔离的完整表格与变量清单以 `docs/技术约定.md` 的「环境配置隔离」章节为准，修改后需同步更新该文档。

## 安全与配置

- 不要提交密钥。`application.yml` 中的默认值仅用于本地开发。
- 通过环境变量覆盖配置：`MYSQL_HOST`、`MYSQL_PORT`、`MYSQL_DATABASE`、`MYSQL_USERNAME`、`MYSQL_PASSWORD`、`REDIS_HOST`、`REDIS_PORT`、`REDIS_DATABASE`。
- 本地私有配置放已忽略的 `application-*.local.yml`；`DASHSCOPE_API_KEY` 等密钥只放环境变量。
