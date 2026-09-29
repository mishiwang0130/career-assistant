-- 建库脚本：表结构由你自己按业务补充
CREATE DATABASE IF NOT EXISTS `career_assistant`
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

USE `career_assistant`;

-- 系统用户表
CREATE TABLE IF NOT EXISTS `sys_user` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `username`    VARCHAR(50)  NOT NULL COMMENT '用户名',
    `password`    VARCHAR(100) NOT NULL COMMENT 'BCrypt 密码密文',
    `nickname`    VARCHAR(50)  NOT NULL COMMENT '昵称',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0-禁用，1-启用',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `create_by`   BIGINT       NOT NULL DEFAULT 0 COMMENT '创建人ID，0表示系统或未登录',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `update_by`   BIGINT       NOT NULL DEFAULT 0 COMMENT '更新人ID，0表示系统或未登录',
    `is_delete`   TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sys_user_username` (`username`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
  COMMENT = '系统用户表';

-- Access Token 记录表：JWT 本身不下库，只保存 jti 并校验有效状态
CREATE TABLE IF NOT EXISTS `sys_token` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id`     BIGINT      NOT NULL COMMENT '用户ID',
    `jti`         VARCHAR(64) NOT NULL COMMENT 'JWT 唯一标识',
    `expires_at`  DATETIME    NOT NULL COMMENT 'Access Token 过期时间',
    `revoked`     TINYINT     NOT NULL DEFAULT 0 COMMENT '是否撤销：0-否，1-是',
    `revoked_at`  DATETIME    DEFAULT NULL COMMENT '撤销时间',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `create_by`   BIGINT      NOT NULL DEFAULT 0 COMMENT '创建人ID，0表示系统或未登录',
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `update_by`   BIGINT      NOT NULL DEFAULT 0 COMMENT '更新人ID，0表示系统或未登录',
    `is_delete`   TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sys_token_jti` (`jti`),
    KEY `idx_sys_token_user_id` (`user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
  COMMENT = 'Access Token 记录表';

-- Refresh Token 记录表：只保存 SHA-256 摘要，不保存明文
CREATE TABLE IF NOT EXISTS `sys_refresh_token` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id`     BIGINT      NOT NULL COMMENT '用户ID',
    `access_jti`  VARCHAR(64) NOT NULL COMMENT '关联的 Access Token jti',
    `token_hash`  CHAR(64)    NOT NULL COMMENT 'Refresh Token 的 SHA-256 十六进制摘要',
    `expires_at`  DATETIME    NOT NULL COMMENT 'Refresh Token 过期时间',
    `revoked`     TINYINT     NOT NULL DEFAULT 0 COMMENT '是否撤销：0-否，1-是',
    `revoked_at`  DATETIME    DEFAULT NULL COMMENT '撤销时间',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `create_by`   BIGINT      NOT NULL DEFAULT 0 COMMENT '创建人ID，0表示系统或未登录',
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `update_by`   BIGINT      NOT NULL DEFAULT 0 COMMENT '更新人ID，0表示系统或未登录',
    `is_delete`   TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sys_refresh_token_hash` (`token_hash`),
    KEY `idx_sys_refresh_token_user_id` (`user_id`),
    KEY `idx_sys_refresh_token_access_jti` (`access_jti`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
  COMMENT = 'Refresh Token 记录表';

-- 通用助手消息表：会话消息落库用于历史分页，Agent 会话状态本身保存在 Redis
-- 会话ID 直接引用 chat_session.id（M16 起），不再由前端生成字符串 ID
CREATE TABLE IF NOT EXISTS `assistant_message` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id`     BIGINT       NOT NULL COMMENT '用户ID',
    `session_id`  BIGINT       NOT NULL COMMENT '会话ID，关联 chat_session.id',
    `role`        VARCHAR(16)  NOT NULL COMMENT '角色：USER/ASSISTANT/SYSTEM',
    `content`     TEXT         NOT NULL COMMENT '消息内容',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `create_by`   BIGINT       NOT NULL DEFAULT 0 COMMENT '创建人ID，0表示系统或未登录',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `update_by`   BIGINT       NOT NULL DEFAULT 0 COMMENT '更新人ID，0表示系统或未登录',
    `is_delete`   TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    PRIMARY KEY (`id`),
    KEY `idx_assistant_message_user_session_id` (`user_id`, `session_id`, `id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
  COMMENT = '通用助手消息表';

-- 简历表：保存简历元数据、MinIO 对象 key 与解析后的原文
CREATE TABLE IF NOT EXISTS `resume` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id`      BIGINT       NOT NULL COMMENT '用户ID',
    `title`        VARCHAR(100) NOT NULL COMMENT '简历标题',
    `source_type`  VARCHAR(16)  NOT NULL COMMENT '来源类型：UPLOAD/MANUAL',
    `file_name`    VARCHAR(255) DEFAULT NULL COMMENT '安全化后的原始文件名，仅用于展示',
    `object_key`   VARCHAR(512) DEFAULT NULL COMMENT 'MinIO对象key，不存物理地址',
    `file_size`    BIGINT       DEFAULT NULL COMMENT '文件字节数',
    `file_ext`     VARCHAR(16)  DEFAULT NULL COMMENT '小写扩展名，不含点号',
    `raw_text`     MEDIUMTEXT   DEFAULT NULL COMMENT '解析或填写后的简历正文',
    `parse_status` VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT '解析状态：PENDING/SUCCESS/FAILED',
    `parse_error`  VARCHAR(500) DEFAULT NULL COMMENT '解析失败原因，已截断',
    `is_default`   TINYINT      NOT NULL DEFAULT 0 COMMENT '是否默认：0-否，1-是',
    `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `create_by`    BIGINT       NOT NULL DEFAULT 0 COMMENT '创建人ID，0表示系统或未登录',
    `update_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `update_by`    BIGINT       NOT NULL DEFAULT 0 COMMENT '更新人ID，0表示系统或未登录',
    `is_delete`    TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    PRIMARY KEY (`id`),
    KEY `idx_resume_user_id` (`user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
  COMMENT = '简历表';

-- ===== M16 前端壳与会话中心 =====

-- 会话元数据表：保存会话标题、场景与活跃时间，支撑左侧会话列表；Agent 会话状态仍保存在 Redis
CREATE TABLE IF NOT EXISTS `chat_session` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID，即会话标识',
    `user_id`         BIGINT       NOT NULL COMMENT '用户ID',
    `scene`           VARCHAR(32)  NOT NULL COMMENT '会话场景：ASSISTANT-通用助手，INTERVIEW-模拟面试',
    `title`           VARCHAR(100) NOT NULL COMMENT '会话标题',
    `last_message_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近一条用户消息时间',
    `status`          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '会话状态：ACTIVE-正常，预留归档',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `create_by`       BIGINT       NOT NULL DEFAULT 0 COMMENT '创建人ID，0表示系统或未登录',
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `update_by`       BIGINT       NOT NULL DEFAULT 0 COMMENT '更新人ID，0表示系统或未登录',
    `is_delete`       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    PRIMARY KEY (`id`),
    KEY `idx_chat_session_user_last` (`user_id`, `last_message_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
  COMMENT = '会话元数据表';

-- M16 存量环境同步：会话 ID 由字符串改为 chat_session.id（历史消息数据已清空，不做数据迁移）
-- 新建库无需执行：上面的建表语句已经使用 BIGINT，本语句重复执行结果一致
ALTER TABLE `assistant_message` MODIFY COLUMN `session_id` BIGINT NOT NULL COMMENT '会话ID，关联 chat_session.id';

-- ===== F4 求职目标 =====

-- 求职目标表：一人一份，user_id 唯一；供 F5 模拟面试出题与 F7 训练计划读取
CREATE TABLE IF NOT EXISTS `user_profile` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id`         BIGINT       NOT NULL COMMENT '用户ID，一人一份',
    `target_position` VARCHAR(100) NOT NULL COMMENT '目标岗位',
    `work_years`      INT          NOT NULL DEFAULT 0 COMMENT '当前工作年限（年），0表示应届或不足一年',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `create_by`       BIGINT       NOT NULL DEFAULT 0 COMMENT '创建人ID，0表示系统或未登录',
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `update_by`       BIGINT       NOT NULL DEFAULT 0 COMMENT '更新人ID，0表示系统或未登录',
    `is_delete`       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_profile_user_id` (`user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
  COMMENT = '求职目标表';

-- ===== F2 简历优化 =====

-- Skill（业务规则）表：由框架 agentscope-extensions-skill-mysql-repository 的 MysqlSkillRepository 管理，
-- 表结构与该扩展自带建表语句保持一致（框架托管表，不套用项目公共字段约定）。应用启动时会 IF NOT EXISTS 自动建表，
-- 这里显式建一遍，保证只用本脚本初始化数据库的环境也能直接跑。
CREATE TABLE IF NOT EXISTS `agentscope_skills` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `name`          VARCHAR(255) NOT NULL COMMENT '技能名，唯一',
    `description`   TEXT         NOT NULL COMMENT '技能描述，注入提示词供模型判断何时加载',
    `skill_content` LONGTEXT     NOT NULL COMMENT '技能正文（Markdown）',
    `source`        VARCHAR(255) NOT NULL COMMENT '技能来源标识',
    `metadata_json` LONGTEXT     DEFAULT NULL COMMENT '扩展元数据 JSON，框架按需读写',
    `created_at`    TIMESTAMP    DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`    TIMESTAMP    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agentscope_skills_name` (`name`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'AgentScope 技能表';

-- 技能资源表：技能附带的额外文件，当前技能正文自带内容，不写资源行；框架会自动创建，这里保持一致。
CREATE TABLE IF NOT EXISTS `agentscope_skill_resources` (
    `id`               BIGINT       NOT NULL COMMENT '技能ID，关联 agentscope_skills.id',
    `resource_path`    VARCHAR(500) NOT NULL COMMENT '资源相对路径',
    `resource_content` LONGTEXT     NOT NULL COMMENT '资源内容',
    `created_at`       TIMESTAMP    DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`       TIMESTAMP    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`, `resource_path`),
    CONSTRAINT `fk_agentscope_skill_resources_skill_id`
        FOREIGN KEY (`id`) REFERENCES `agentscope_skills` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'AgentScope 技能资源表';

-- 幂等写入 F2 的默认 Skill：resume-analysis（简历分析规范）。
-- 重复执行不产生重复数据，也不覆盖人工在表里临时调整过的规则（命中唯一键时只做同值更新）。
INSERT INTO `agentscope_skills` (`name`, `description`, `skill_content`, `source`)
VALUES ('resume-analysis',
        '简历分析规范：诊断维度定义、评分口径与输出结构，简历分析子 Agent 用它统一诊断口径',
        '你正在执行「简历分析规范」。只依据工具读到的简历正文与用户求职目标执行，不脑补用户没写过的经历。

# 一、诊断维度（每次都要给出，至少 4 项）
1. 结构与排版：模块顺序、信息密度、层级是否一眼看懂，是否能在 10 秒内抓到关键信息。
2. 内容完整度：教育、工作/实习、项目、技能、求职意向等关键信息是否齐备，时间线是否连续无断档。
3. 成果与量化：经历描述是否写出动作、对象、结果与影响，是否有可验证的数字或范围。
4. 表达专业性：动词是否具体、术语使用是否正确、是否有空话套话与重复表述。
5. 与目标岗位契合度：项目与技能是否围绕目标岗位展开，是否缺该岗位最看重的关键词。
（可结合简历情况在第 5 项之外自行补充维度，但维度名要具体，不要写成「其他」。）

# 二、评分口径
1. 每个维度独立打 0-100 的整数分，写明一句话理由，理由必须指向简历里的具体位置。
2. 综合得分 = 各维度得分按权重加权后四舍五入：结构与排版 15%、内容完整度 25%、成果与量化 30%、
   表达专业性 15%、与目标岗位契合度 15%；自行补充的维度按剩余权重折算。
3. 分档参考：90 以上可直接投递；75-89 小改即可；60-74 需要重写关键段落；60 以下建议重做结构。

# 三、输出结构（正文按此顺序，用 Markdown 小标题分隔）
1. 综合得分：分数 + 一句话说明扣分主要来自哪里。
2. 维度评分：每项写「维度名 + 分数 + 一句话理由」。
3. 问题清单：每条写「问题 + 出现在哪里 + 为什么是问题 + 怎么改」，按严重程度从高到低排序。
4. 亮点：值得保留的写法，说明为什么好。
5. 优化建议：按优先级排列，能直接照着改。
6. 优化后的简历正文：保留原有结构与全部真实经历，只改表达与组织方式。
7. 可能被追问的项目点：3-5 个面试官最可能追问的点。

# 四、底线
1. 简历里没写过的公司、项目、数字、时间一律不许补；原文缺失但影响判断的信息标注「原文未提及」并说明建议补充什么。
2. 优化后的正文只能重组、改写、强化已有内容，不得新增经历与成果。
3. 只评价简历的写法，不评价用户本人。
4. 结构化结论必须通过 submit_resume_diagnosis 工具提交一次，字段口径与正文保持一致。',
        'f2-resume-optimize')
ON DUPLICATE KEY UPDATE `name` = `name`;

-- ===== F5 模拟面试 =====

-- 面试问答表：一轮问答一条，主问题与它的追问都记（round_no 区分）。题目、回答、题型、难度、
-- 判定结果与随后的流程动作一并留存：整场面试的进度与当前难度由这些行回放得出，F6 的点评、
-- 错题清单与掌握度也以本表为数据来源。
CREATE TABLE IF NOT EXISTS `interview_qa` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id`        BIGINT       NOT NULL COMMENT '用户ID',
    `session_id`     BIGINT       NOT NULL COMMENT '面试会话ID，关联 chat_session.id',
    `question_index` INT          NOT NULL COMMENT '主问题序号，从1开始且不超过配置题量',
    `round_no`       INT          NOT NULL DEFAULT 1 COMMENT '同一主问题下的轮次：1-主问题，2-追问',
    `question_type`  VARCHAR(16)  NOT NULL COMMENT '题型：BASIC-八股，PROJECT-项目，COMPREHENSIVE-综合',
    `difficulty`     INT          NOT NULL COMMENT '本题难度等级1-5，起始难度由工作年限决定且全场只升不降',
    `question`       TEXT         NOT NULL COMMENT '题目正文',
    `answer`         TEXT         NOT NULL COMMENT '用户本题的回答',
    `outcome`        VARCHAR(16)  NOT NULL COMMENT '本题判定：CORRECT-答到要点，PARTIAL-有遗漏，WRONG-不会或答错',
    `judgement`      VARCHAR(500) DEFAULT NULL COMMENT '判定要点，来自评分子 Agent 的结论摘要，已截断',
    `next_action`    VARCHAR(16)  NOT NULL COMMENT '本回合之后的流程动作：FOLLOW_UP-追问，NEXT_QUESTION-换题，FINISHED-结束',
    `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `create_by`      BIGINT       NOT NULL DEFAULT 0 COMMENT '创建人ID，0表示系统或未登录',
    `update_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `update_by`      BIGINT       NOT NULL DEFAULT 0 COMMENT '更新人ID，0表示系统或未登录',
    `is_delete`      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    PRIMARY KEY (`id`),
    KEY `idx_interview_qa_user_session` (`user_id`, `session_id`, `id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
  COMMENT = '面试问答表';

-- 幂等写入 F5 的默认 Skill：interview-questioning（出题与追问换题规范）。
-- 重复执行不产生重复数据，也不覆盖人工在表里临时调整过的规则（命中唯一键时只做同值更新）。
INSERT INTO `agentscope_skills` (`name`, `description`, `skill_content`, `source`)
VALUES ('interview-questioning',
        '模拟面试出题规范：八股/项目/综合三类题型的配比、1-5 级难度阶梯、追问与换题的判定条件（含答错就记错题换题）',
        '你正在执行「模拟面试出题规范」。这场面试的目标岗位、工作年限与当前进度由系统给出，本规范只定义怎么出题、怎么追问、怎么换题。

# 一、三类题型配比
1. 八股题（BASIC）：岗位相关的原理、机制与常见考点，考「知不知道、清不清楚」。
2. 项目题（PROJECT）：围绕用户简历里的项目经历提问，考「做没做过、想没想清楚」，只能依据读到的简历内容。
3. 综合题（COMPREHENSIVE）：场景设计、权衡取舍、沟通协作一类没有唯一答案的问题，考「思路与判断」。
4. 参考配比 4:2:2（八股 4 : 项目 2 : 综合 2，以 8 题为例），系统会在每道题给出建议题型；
   目标岗位偏工程实践时可以适当加大项目题与综合题比重，但三类题都要出现。

# 二、难度阶梯（1-5 级）
1. 起始难度由工作年限决定；难度只升不降，起始难度就是全场下限。
2. 答到要点：难度上调一级（上限 5 级）。
3. 答得有遗漏：难度持平。
4. 完全不会或答错：难度持平（不下调）。
5. 同一难度下题目深浅按「是什么 → 为什么 → 怎么权衡、出问题怎么办」递进。

# 三、追问、换题与结束
1. 答到要点：就回答里的关键点追问一层，追问要针对他刚说的那句话，用户答完再进入下一题。
2. 答得有遗漏：只追问遗漏的那一点，补齐后进入下一题。
3. 完全不会或答错：这道题记为错题，**不再围绕这道题涉及的知识点追问**（错题不纠缠），
   直接换一道新题，难度持平。
4. 追问只问一层，不连环追问；追问不占题量。
5. 每道主问题最多追问一次；题量走满或用户主动要求结束时收尾，不再出题。

# 四、出题底线
1. 题目必须贴合目标岗位与工作年限，不出与岗位无关的题。
2. 一次只问一道题，问完停下等回答；不替用户作答，不一次抛出多道题。
3. 不编造用户没写过的项目、公司、数字；读不到简历时出通用的岗位题。
4. 题面要能听懂：先给场景，再问问题，避免堆砌术语。',
        'f5-interview')
ON DUPLICATE KEY UPDATE `name` = `name`;

-- 幂等写入 F5 的默认 Skill：answer-evaluation（评分子 Agent 的判定口径）。
INSERT INTO `agentscope_skills` (`name`, `description`, `skill_content`, `source`)
VALUES ('answer-evaluation',
        '面试评分规范：五个判定维度的口径、outcome 三档判定标准与知识点掌握度的计算口径',
        '你正在执行「面试评分规范」。题目、用户回答、涉及知识点、目标岗位与工作年限由系统给全，你只按下面的口径判定。

# 一、五个判定维度
1. 要点覆盖：是否答到这道题最关键的两三个点。
2. 准确性：是否有事实性错误、概念混淆或想当然。
3. 深度：是否说到机制、边界与取舍，还是只停在名词解释。
4. 表达结构：是否先给结论再给依据，条理是否清楚。
5. 举例与落地：是否结合真实经历或具体场景说明，而不是空谈。

# 二、outcome 判定口径（三选一，必须给一个）
1. CORRECT 答到要点：关键点基本覆盖，可以有小瑕疵。
2. PARTIAL 答得有遗漏：方向对，但明显少了一到两个关键点，或深度不够。
3. WRONG 完全不会或答错：说不了解、没做过，答非所问，或关键结论说错。
用户明确说「不了解」「不会」「没做过」时一律记 WRONG，不要把沉默或含糊当作 PARTIAL。

# 三、掌握度计算口径（本批只落判定结果，掌握度由后续批次统计）
1. 单题证据只能给出 0-100 的参考分，不能凭一道题判定某个知识点已经掌握。
2. 知识点按题目涉及的概念粒度标注，粒度到「Redis 分布式锁」「MySQL 索引最左前缀」这一级，
   不要写成「后端基础」这种过粗的粒度。
3. 同一知识点累计答错时掌握度下调，答对时缓慢回升，结合近期多次证据综合判定。

# 四、提交方式（本批只落判定结果，掌握度由后续批次统计）
1. 把上面各项结论用 submit_answer_evaluation 工具提交一次：outcome、score、correctPoints、missingPoints、
   wrongPoints、expressionIssues、suggestions、knowledgePoints、comment，字段齐全。
2. 提交后正文只回一句「评分完成」：结论属于内部信息，不要输出 JSON、字段清单、分数或点评正文。

# 五、底线
1. 只依据题目与用户这道题的回答，用户没说的内容不算说过，也不脑补「他可能懂」。
2. 不编造参考答案的出处，不引用用户没提到的经历或项目。
3. 对事不对人：指出问题要说清依据，不做人格评价，也不给空洞的鼓励话术。',
        'f5-interview')
ON DUPLICATE KEY UPDATE `name` = `name`;

-- ==================== F3 岗位匹配 ====================
-- 幂等写入 F3 的默认 Skill：job-match（岗位匹配规范）。
-- 重复执行不产生重复数据，也不覆盖人工在表里临时调整过的规则（命中唯一键时只做同值更新）。
-- F3 不新建表、不写技能资源行，复用 F2 已建好的 agentscope_skills / agentscope_skill_resources。
INSERT INTO `agentscope_skills` (`name`, `description`, `skill_content`, `source`)
VALUES ('job-match',
        '岗位匹配规范：匹配维度定义、命中与缺失关键词的判定口径、匹配度计算与输出结构，岗位匹配子 Agent 用它统一比对口径',
        '你正在执行「岗位匹配规范」。只依据工具读到的简历正文与用户当场粘贴的 JD 执行，不脑补用户没写过的经历，也不给 JD 加戏。

# 一、匹配维度（每次都要给出，至少 4 项）
1. 硬性条件：学历、专业、年限、证书、地点等 JD 写死的门槛，逐条比对是否满足。
2. 技能栈重叠：JD 要求的编程语言、框架、中间件、工具，简历里是否有对应经历与使用深度。
3. 经验年限与层级：年限是否落在 JD 区间内，职级、带人经验、独立负责的模块是否对得上。
4. 行业与业务域：行业背景、业务场景（例如电商、金融、toB）与 JD 所在领域是否匹配。
5. 成果深度：JD 关注的能力（高并发、性能优化、稳定性、数据量级）在简历里是否有拿得出手的结果。
（可结合 JD 补充维度，但维度名要具体，不要写成「其他」。）

# 二、关键词判定口径（每条 JD 要求只落一种结论）
1. 命中关键词：简历里有明确的技能名、项目描述或成果作为依据，并写清依据出现在哪一段。
2. 写了但不够突出：简历提到了相关技术或经历，但没有说清做了什么、做到什么程度、拿到什么结果。
3. 完全没有：通读全文找不到任何依据，不许因为「差不多应该有」就判命中。
4. 判定前必须读完整份简历；只读了一部分时不要给出缺失关键词的判断。

# 三、匹配度计算口径
1. 每个维度独立打 0-100 的整数分，写明一句话理由，理由必须指向简历与 JD 的具体位置。
2. 匹配度 = 各维度得分加权后四舍五入：硬性条件 25%、技能栈重叠 30%、经验年限与层级 15%、
   行业与业务域 10%、成果深度 20%；自行补充的维度按剩余权重折算。
3. 分档参考：85 以上可以直接投；70-84 补短板再投；55-69 有明显缺口；55 以下建议换个方向或先补基础。

# 四、输出结构（正文按此顺序，用 Markdown 小标题分隔）
1. 匹配度：分数 + 一句话说明主要差距。
2. 维度评分：维度名 + 分数 + 一句话理由。
3. 命中关键词：逐条对应到简历里的依据。
4. 缺失关键词：区分「写了但不够突出」与「完全没有」。
5. 差距补齐建议：按优先级排列，区分短期能补与需要真的去学，末尾给出最该改的三处具体句子。

# 五、底线
1. 只依据这份简历和这段 JD：JD 里没写的要求不许加戏，简历里没写的经历、数字、时间不许补。
2. 不编造岗位信息、公司信息、薪资数据；不确定就说不确定。
3. 不评价用户本人，也不替用户决定要不要投这家公司。
4. 不通篇诊断简历的写法（那是另一项分析），只回答匹配与差距。
5. 差距补齐建议只给方向、学习路径与句式模板；措辞示范必须写成「如果你确实做过 X，可以这样写：……」，
   不得替用户编造简历里没有的数字、指标、项目或时间（例如凭空写出「可用性从 98.5% 提升到 99.95%」）。',
        'f3-job-match')
ON DUPLICATE KEY UPDATE `name` = `name`;
