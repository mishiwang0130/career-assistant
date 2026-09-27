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
├─ util/         工具类
└─ config/       配置类
```

- Mapper XML 放在 `backend/src/main/resources/mapper/`，该路径已在 `application.yml` 中配置。
- 数据库实体统一放 `po` 包，类名与表名对应，如 `sys_user` → `SysUser`。
- 只有被接口使用的参数或返回值才建 VO：请求参数用 `xxxReqVO`，返回值用 `xxxRespVO`，例如 `UserLoginReqVO`、`UserInfoRespVO`。
- Controller 只做参数校验和调用 Service，不写业务逻辑；事务放在 Service 层。

## 命名与编码规范

- Java 使用 4 空格缩进、UTF-8、JDK 17，使用 Lombok 简化样板代码。
- 类名 PascalCase，方法与字段 camelCase，常量 UPPER_SNAKE_CASE。
- 数据库字段 snake_case，Java 字段 camelCase，依赖 MyBatis-Plus 的 `map-underscore-to-camel-case` 自动映射。
- 前端使用 2 空格缩进、单引号，字符串末尾不加分号，TypeScript 开启 strict，不使用 `any`。

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

- 所有功能开发和缺陷修复都必须从目标分支（通常是 `main`）新开分支进行，禁止直接在 `main` 上修改后提交。
- 分支命名格式为 `<原分支>-{feature|bugfix}/<年月日>-<分支功能名>`，其中 `年月日` 为创建分支当天的 8 位日期，功能名使用简短中文或 kebab-case 英文。
- `feature` 用于新功能，`bugfix` 用于缺陷修复。
- 示例：

```
main-feature/20260927-用户注册
main-feature/20260927-user-register
main-bugfix/20260927-fix-login-token
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
- 至少一名 reviewer 通过后再合并，禁止直接向主分支提交未经评审的代码。

## 安全与配置

- 不要提交密钥。`application.yml` 中的默认值仅用于本地开发。
- 通过环境变量覆盖配置：`MYSQL_HOST`、`MYSQL_PORT`、`MYSQL_DATABASE`、`MYSQL_USERNAME`、`MYSQL_PASSWORD`、`REDIS_HOST`、`REDIS_PORT`、`REDIS_DATABASE`。
- 本地私有配置放已忽略的 `application-*.local.yml`；`DASHSCOPE_API_KEY` 等密钥只放环境变量。
