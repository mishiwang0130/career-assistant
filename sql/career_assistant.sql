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
    `evaluation_json` LONGTEXT    DEFAULT NULL COMMENT '评分子 Agent 的结构化结论JSON：评分、答对的点、缺失点、错误点、表达问题、建议、知识点、一句话点评、标准答案',
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

-- F5 存量环境同步：补上评分结论列（面试结果的逐题明细与标准答案存在这一列）。
-- 先查 information_schema 再执行，重复跑脚本不会因「列已存在」报错。
SET @add_evaluation_json := (
    SELECT IF(COUNT(*) = 0,
              'ALTER TABLE `interview_qa` ADD COLUMN `evaluation_json` LONGTEXT DEFAULT NULL COMMENT ''评分子 Agent 的结构化结论JSON：评分、答对的点、缺失点、错误点、表达问题、建议、知识点、一句话点评、标准答案''',
              'SELECT ''interview_qa.evaluation_json already exists''')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'interview_qa' AND column_name = 'evaluation_json');
PREPARE add_evaluation_json_stmt FROM @add_evaluation_json;
EXECUTE add_evaluation_json_stmt;
DEALLOCATE PREPARE add_evaluation_json_stmt;

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
   wrongPoints、expressionIssues、suggestions、knowledgePoints、comment、referenceAnswer，字段齐全。
2. 提交后正文只回一句「评分完成」：结论属于内部信息，不要输出 JSON、字段清单、分数或点评正文。
3. referenceAnswer 是这道题的标准答案，面试结束后用户会拿它对答案：写成可直接对照的要点与结论（一般 3-6 条），
   不写评分过程，也不要用「他应该提到」这类评价口吻。

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

-- ==================== F6 面试点评与报告 ====================

-- 知识点掌握度表：F6 的权威掌握度数据。一个用户一个知识点一行，按「近期多次证据 + 时间衰减 + 中性先验」
-- 计算（口径见 docs/技术约定.md 的「面试点评与报告（F6）」章节与 mastery-evaluation 技能），
-- 由 Java 纯函数每回合重算后 upsert；F7 的训练计划从这里读薄弱点。
CREATE TABLE IF NOT EXISTS `knowledge_mastery` (
    `id`                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id`            BIGINT       NOT NULL COMMENT '用户ID',
    `knowledge_point`    VARCHAR(200) NOT NULL COMMENT '知识点名称，粒度到「Redis 分布式锁」这一级',
    `mastery_score`      INT          NOT NULL COMMENT '掌握度0-100，按近期多次证据与时间衰减加权得出',
    `mastery_level`      VARCHAR(16)  NOT NULL COMMENT '掌握度等级：WEAK/NEEDS_WORK/BASIC/PROFICIENT/MASTERED',
    `weak`               TINYINT      NOT NULL DEFAULT 0 COMMENT '是否薄弱点：0-否，1-是',
    `evidence_count`     INT          NOT NULL DEFAULT 0 COMMENT '参与计算的证据条数（不含中性先验）',
    `last_session_id`    BIGINT       DEFAULT NULL COMMENT '最近一次证据所在的面试会话ID',
    `last_outcome`       VARCHAR(16)  DEFAULT NULL COMMENT '最近一次证据的判定：CORRECT/PARTIAL/WRONG',
    `last_evidence_time` DATETIME     DEFAULT NULL COMMENT '最近一次证据的时间',
    `create_time`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `create_by`          BIGINT       NOT NULL DEFAULT 0 COMMENT '创建人ID，0表示系统或未登录',
    `update_time`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `update_by`          BIGINT       NOT NULL DEFAULT 0 COMMENT '更新人ID，0表示系统或未登录',
    `is_delete`          TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_knowledge_mastery_user_point` (`user_id`, `knowledge_point`),
    KEY `idx_knowledge_mastery_user_weak` (`user_id`, `weak`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
  COMMENT = '知识点掌握度表';

-- 面试报告表：一个面试会话一行（user_id + session_id 唯一）。状态只有生成中/已完成/生成失败三态，
-- 报告由后台子 Agent（report-writer，timeout_seconds=0）生成后经 submit_interview_report 回写；
-- 错题清单、薄弱点清单、掌握度不落本表，读取时由 interview_qa 与 knowledge_mastery 现算。
CREATE TABLE IF NOT EXISTS `interview_report` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id`       BIGINT       NOT NULL COMMENT '用户ID',
    `session_id`    BIGINT       NOT NULL COMMENT '面试会话ID，关联 chat_session.id',
    `status`        VARCHAR(16)  NOT NULL COMMENT '生成状态：GENERATING-生成中，SUCCEEDED-已完成，FAILED-生成失败',
    `attempt`       INT          NOT NULL DEFAULT 0 COMMENT '生成尝试次数，重试时递增',
    `summary`       TEXT         DEFAULT NULL COMMENT '面试总结正文（报告子 Agent 产出）',
    `report_json`   LONGTEXT     DEFAULT NULL COMMENT '报告结构化结论JSON：亮点与下一步建议等',
    `error_message` VARCHAR(500) DEFAULT NULL COMMENT '失败原因，成功时置空',
    `finish_time`   DATETIME     DEFAULT NULL COMMENT '生成完成时间',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `create_by`     BIGINT       NOT NULL DEFAULT 0 COMMENT '创建人ID，0表示系统或未登录',
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `update_by`     BIGINT       NOT NULL DEFAULT 0 COMMENT '更新人ID，0表示系统或未登录',
    `is_delete`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_interview_report_user_session` (`user_id`, `session_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
  COMMENT = '面试报告表';

-- 幂等写入 F6 的默认 Skill：mastery-evaluation（掌握度与薄弱点判定口径）。
-- 重复执行不产生重复数据，也不覆盖人工在表里临时调整过的规则（命中唯一键时只做同值更新）。
INSERT INTO `agentscope_skills` (`name`, `description`, `skill_content`, `source`)
VALUES ('mastery-evaluation',
        '掌握度与薄弱点判定口径：证据来源与单条证据分、90 天窗口与最近 8 条、半衰期 30 天的时间衰减、中性先验、五级分档与薄弱点判定',
        '你正在执行「掌握度与薄弱点判定口径」。这套口径同时写在代码里，代码是权威实现，本规范用于让报告解释清楚分数是怎么来的。

# 一、证据与单条证据分
1. 证据来自每场面试的逐题点评（interview_qa）：一行点评按它自己声明的知识点展开，同一行对同一知识点只算一条证据。
2. 单条证据分：点评给了 0-100 的参考分就用参考分；没给则按判定映射：答到要点 90、有遗漏 65、不会或答错 20。

# 二、窗口与权重
1. 只看最近 90 天内的证据，每个知识点最多取最近 8 条（按时间倒序）。
2. 权重按时间衰减：0.5 的「距今天数 ÷ 30」次方，也就是半衰期 30 天——越近的证据越算数。
3. 额外加一条分数 60、权重 1.0 的中性先验，代表「还没形成判断」：这样答错一两道题不会把掌握度直接打到最低。

# 三、掌握度与等级
1. 掌握度 = 所有证据「权重 × 分数」之和 ÷ 权重之和，四舍五入取 0-100 的整数。
2. 等级分档：低于 30 薄弱、30-59 待补强、60-74 基本掌握、75-89 熟练、90 及以上精通。
3. 薄弱点：窗口内最新一条证据是「不会或答错」，或者掌握度低于 60，二者满足其一即算薄弱点。

# 四、底线
1. 掌握度是多次证据综合的结果，不因为一道题的判定就跳到最低或最高。
2. 只依据 interview_qa 里的知识点与判定，不替用户推断他没答到过的知识点。
3. 解释掌握度时只说结论与依据，不输出公式细节、内部字段名或 JSON。',
        'f6-interview-report')
ON DUPLICATE KEY UPDATE `name` = `name`;

-- 幂等写入 F6 的默认 Skill：interview-report（面试报告写作规范）。
INSERT INTO `agentscope_skills` (`name`, `description`, `skill_content`, `source`)
VALUES ('interview-report',
        '面试报告写作规范：报告结构（面试总结、亮点、下一步建议）、写作底线与提交方式，报告子 Agent 用它统一产出',
        '你正在执行「面试报告写作规范」。这场面试的逐题判定、错题清单、薄弱点清单与知识点掌握度已经由系统整理好并放在给你的材料里，你只负责把它们写成一份用户能直接照着行动的面试报告。

# 一、报告结构（按此顺序产出）
1. 面试总结：3-5 句话，先说整体表现（答得最好的部分），再说主要差距，最后给一句整体判断；不要罗列题目。
2. 亮点：从材料里挑出真实的 2-3 个优势，每条一句话，指明是哪道题或哪个知识点体现的。
3. 下一步建议：按优先级给 3-5 条，每条写清「补什么知识点 + 具体怎么补（复习方向或练习方式）」，优先覆盖薄弱点清单里最靠前的知识点。

# 二、写作底线
1. 只依据给你的材料：题目、判定、错题、薄弱点、掌握度。不编造用户没说过的经历、项目、数字，也不脑补他「其实会」。
2. 不提分数公式、不贴评分 JSON、不输出字段清单、不讲内部过程，也不提任何工具、技能或子角色。
3. 对事不对人：指出差距时说清依据，不做人格评价，不用空洞的鼓励话术。
4. 语言直白：先给结论再给依据，句子短，不用「接下来我将」「首先我们需要」这类铺垫。

# 三、提交方式
1. 把结论用 submit_interview_report 工具提交一次：summary（面试总结正文）、highlights（亮点清单）、
   suggestions（下一步建议清单）、sessionId（材料里给出的会话 ID，原样填）。
2. 提交后正文只回一句「报告完成」：报告内容属于结构化产物，不要复述、不要输出清单。
3. 只提交一次；工具返回失败时按提示修正字段后重试，不要重复提交多份。',
        'f6-interview-report')
ON DUPLICATE KEY UPDATE `name` = `name`;

-- F6 修复（2026-10-01）：面试收尾必须走工具。
-- 真实环境出现过面试官直接说「本场面试到此结束」但没调用 record_interview_answer 的情况，
-- 平台因此收不到结束信号：这一轮不落库、面试永远结束不了，逐题点评与报告都出不来。
-- 这里用 UPDATE 追加约束而不是改上面的 INSERT：初始化脚本命中唯一键时只做同值更新，改正文不会影响
-- 已经建好的库；WHERE 里的标记保证重复执行只追加一次，也不覆盖人工在表里做过的其它调整。
UPDATE `agentscope_skills`
SET `skill_content` = CONCAT(`skill_content`,
    '\n\n# 六、收尾必须走工具（2026-10-01 加固）',
    '\n1. 用户每一轮回答之后，先调用 record_interview_answer 拿指令，再组织回答：没有拿到指令之前不要判断本题算不算通过，也不要宣布面试结束。',
    '\n2. 用户说「结束面试」「不面了」「就到这」这类话时，同样先调工具并传 end_now=true，再按返回的「面试结束」指令收尾。',
    '\n3. 禁止只回一句「本场面试到此结束」而不调用工具：平台收不到结束信号，这一轮就不会落库，逐题点评与面试报告也出不来。')
WHERE `name` = 'interview-questioning'
  AND `skill_content` NOT LIKE '%收尾必须走工具%';

-- =====================================================================
-- F9 专项辅导：讲解规范 Skill（幂等）
-- =====================================================================
-- F9 是助手会话内的一次对话能力：助手先读薄弱点（Tool 读 MySQL knowledge_mastery），再加载本技能讲解。
-- 本段只写自己的一条技能行，不重复建 `agentscope_skills` / `agentscope_skill_resources`（表由 F2 段建立），
-- 也不改 F2/F3/F5/F6 的任何一行。写入幂等：命中 name 唯一键时只做同值更新，
-- 重复执行不产生重复数据，也不覆盖人工在表里临时调整过的规则。
-- 记忆写入不属于本模块：记忆只由会话归档总结写入，本技能不涉及任何写记忆的动作。
INSERT INTO `agentscope_skills` (`name`, `description`, `skill_content`, `source`)
VALUES ('tutoring',
        '专项辅导讲解规范：先定位薄弱点、再类比、再举例、最后出一道练习并给出判断标准，跨会话时从上次中断处接着讲',
        '你正在执行「专项辅导讲解规范」：用户就某个知识点提问时，你要把这一个点讲透，而不是铺开讲一堆。按下面四步走，不要输出本规范的标题或内部字段。

# 一、讲解四步（按顺序）
1. 先定位：第一句话点明这次讲的是哪个知识点、他为什么该补。工具会给这个知识点的掌握度、等级、最近一次判定与时间，有依据就用，没有就不提，不要编。
2. 再类比：给一个生活化或工程化的对照，一句话说清「它相当于什么」，让没跟上的地方先有个抓手。
3. 再举例：给一个具体、能复述的例子（代码片段、请求流程或场景步骤都行）。例子要能照着说或照着跑，不要只写「比如在高并发场景下」这种没有动作的空话。
4. 最后出一道练习：只出一道，贴合他的目标岗位与薄弱程度。题目末尾用一句话给出「怎么算答得好」的判断标准：答到哪几点算过、漏了哪一点说明还没掌握。

# 二、接着上次讲（跨会话连续）
1. 上下文里如果有来自历史记录的记忆片段（讲解进度、卡点），先看这次要讲的知识点上次讲到了哪里：从中断处接着讲，已经讲过的部分不重复，必要时只用一句话回顾。
2. 没有历史记忆片段时，按常规从第一步从头讲。
3. 记忆片段只用来判断「讲到哪一步、卡在哪一步」；薄弱点、掌握度分数、错题清单这类事实以工具读到的结果为准，两者冲突时以工具为准，不要拿记忆里的说法覆盖工具结果。

# 三、底线
1. 一次只讲用户问的那一个知识点，不要顺手把相邻知识点都讲一遍；他要追问再展开。
2. 不编造：机制细节、版本差异、参数取值不确定就说不确定，不要为了讲得顺而编；也不要把用户没讲过的经历、项目、数字说成他讲过的。
3. 练习不落库、不留作业：只在对话里给出，用户做完就地讲评；不要承诺「记到你的错题本/训练计划里」。
4. 不替用户沉淀讲法偏好：他说了「先讲原理再举例」这次照做即可，不要声称记住了他的偏好，也不要把偏好写进任何地方。
5. 用户可见的正文里不出现工具名、技能名、内部字段或分数公式，也不写「接下来我将」「已加载」「已读取」这类过程话术。',
        'f9-tutoring')
ON DUPLICATE KEY UPDATE `name` = `name`;

-- =====================================================================
-- F7 训练计划（第 5 批）：训练计划 / 训练提醒 + Quartz 存储 + 排期口径技能
-- =====================================================================
-- 本段只追加自己的表、框架托管表与技能行，不改 F2/F3/F5/F6/F9 的任何一行。
-- 四条业务规则（写死）：
--   1. 计划正文存 MySQL，不写服务器工作区：多用户共用一个工作区会互相覆盖，Plan Mode 只借「只读规划 + 人工确认」语义；
--   2. 计划只有一条正文记录（plan_content，一天一行「第 N 天：今天练什么知识点」），没有训练任务表；
--   3. 「还有几天」是生成计划时的一次性输入，存进 training_plan.end_date，页面剩余天数由它实时算出；
--   4. 每日提醒写入 training_reminder，靠 (user_id, reminder_date) 唯一键保证同一天同一用户只有一条。

-- 训练计划表：一个用户同一时刻只有一份生效计划；重规划是覆盖生成，旧计划标记 ENDED 保留（不物理删除）。
CREATE TABLE IF NOT EXISTS `training_plan` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id`           BIGINT       NOT NULL COMMENT '用户ID',
    `status`            VARCHAR(16)  NOT NULL COMMENT '计划状态：ACTIVE-生效中，ENDED-已结束（被重规划替换）',
    `target_position`   VARCHAR(200) DEFAULT NULL COMMENT '生成时的目标岗位快照，页面概览展示这一份',
    `total_days`        INT          NOT NULL COMMENT '计划总天数，即生成时输入的「还有几天」',
    `daily_minutes`     INT          NOT NULL COMMENT '每天可练时长（分钟）',
    `start_date`        DATE         NOT NULL COMMENT '计划开始日期（生成当天）',
    `end_date`          DATE         NOT NULL COMMENT '计划截止日期，由「还有几天」一次算出',
    `plan_content`      LONGTEXT     DEFAULT NULL COMMENT '计划正文：按天一句话概括当天练什么知识点（Markdown）',
    `adjustment_reason` VARCHAR(500) DEFAULT NULL COMMENT '调整原因，重新规划时写清依据；首次生成为空',
    `generated_at`      DATETIME     DEFAULT NULL COMMENT '生成时间',
    `create_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `create_by`         BIGINT       NOT NULL DEFAULT 0 COMMENT '创建人ID，0表示系统或未登录',
    `update_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `update_by`         BIGINT       NOT NULL DEFAULT 0 COMMENT '更新人ID，0表示系统或未登录',
    `is_delete`         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    PRIMARY KEY (`id`),
    KEY `idx_training_plan_user_status` (`user_id`, `status`),
    KEY `idx_training_plan_user_start` (`user_id`, `start_date`),
    KEY `idx_training_plan_status_end` (`status`, `end_date`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
  COMMENT = '训练计划表';

-- 训练提醒表：每个用户每天最多一条，唯一键承载幂等；提醒只做站内展示。
CREATE TABLE IF NOT EXISTS `training_reminder` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id`       BIGINT       NOT NULL COMMENT '用户ID',
    `plan_id`       BIGINT       DEFAULT NULL COMMENT '生成提醒时该用户的生效计划ID',
    `reminder_date` DATE         NOT NULL COMMENT '提醒日期',
    `content`       VARCHAR(200) NOT NULL COMMENT '提醒正文，整条不超过 60 字',
    `read_flag`     TINYINT      NOT NULL DEFAULT 0 COMMENT '是否已读：0-未读，1-已读',
    `read_time`     DATETIME     DEFAULT NULL COMMENT '已读时间，未读时为空',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `create_by`     BIGINT       NOT NULL DEFAULT 0 COMMENT '创建人ID，0表示系统或未登录',
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `update_by`     BIGINT       NOT NULL DEFAULT 0 COMMENT '更新人ID，0表示系统或未登录',
    `is_delete`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_training_reminder_user_date` (`user_id`, `reminder_date`),
    KEY `idx_training_reminder_user_read` (`user_id`, `read_flag`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
  COMMENT = '训练提醒表';

-- ---------------------------------------------------------------------
-- Quartz 调度存储（框架托管表，不套项目公共字段约定）
-- ---------------------------------------------------------------------
-- 来源：Quartz 2.5.2 自带的 tables_mysql_innodb.sql（把 DROP 去掉、改成 CREATE TABLE IF NOT EXISTS，
-- 二级索引内联进建表语句，保证脚本可重复执行）。这里一次建全 11 张表：
-- 「会话归档总结」模块后续复用同一套 Quartz 存储加自己的 job，不需要再改本段。
-- 调度参数见 application*.yml 的 app.training.quartz.*（JobStoreTX + tablePrefix=QRTZ_ + 与业务共库）。
CREATE TABLE IF NOT EXISTS `QRTZ_JOB_DETAILS` (
    `SCHED_NAME`        VARCHAR(120) NOT NULL,
    `JOB_NAME`          VARCHAR(190) NOT NULL,
    `JOB_GROUP`         VARCHAR(190) NOT NULL,
    `DESCRIPTION`       VARCHAR(250) NULL,
    `JOB_CLASS_NAME`    VARCHAR(250) NOT NULL,
    `IS_DURABLE`        VARCHAR(1)   NOT NULL,
    `IS_NONCONCURRENT`  VARCHAR(1)   NOT NULL,
    `IS_UPDATE_DATA`    VARCHAR(1)   NOT NULL,
    `REQUESTS_RECOVERY` VARCHAR(1)   NOT NULL,
    `JOB_DATA`          BLOB         NULL,
    PRIMARY KEY (`SCHED_NAME`, `JOB_NAME`, `JOB_GROUP`),
    KEY `IDX_QRTZ_J_REQ_RECOVERY` (`SCHED_NAME`, `REQUESTS_RECOVERY`),
    KEY `IDX_QRTZ_J_GRP` (`SCHED_NAME`, `JOB_GROUP`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
  COMMENT = 'Quartz 任务明细表（框架托管）';

CREATE TABLE IF NOT EXISTS `QRTZ_TRIGGERS` (
    `SCHED_NAME`     VARCHAR(120) NOT NULL,
    `TRIGGER_NAME`   VARCHAR(190) NOT NULL,
    `TRIGGER_GROUP`  VARCHAR(190) NOT NULL,
    `JOB_NAME`       VARCHAR(190) NOT NULL,
    `JOB_GROUP`      VARCHAR(190) NOT NULL,
    `DESCRIPTION`    VARCHAR(250) NULL,
    `NEXT_FIRE_TIME` BIGINT       NULL,
    `PREV_FIRE_TIME` BIGINT       NULL,
    `PRIORITY`       INT          NULL,
    `TRIGGER_STATE`  VARCHAR(16)  NOT NULL,
    `TRIGGER_TYPE`   VARCHAR(8)   NOT NULL,
    `START_TIME`     BIGINT       NOT NULL,
    `END_TIME`       BIGINT       NULL,
    `CALENDAR_NAME`  VARCHAR(190) NULL,
    `MISFIRE_INSTR`  SMALLINT     NULL,
    `JOB_DATA`       BLOB         NULL,
    PRIMARY KEY (`SCHED_NAME`, `TRIGGER_NAME`, `TRIGGER_GROUP`),
    KEY `IDX_QRTZ_T_J` (`SCHED_NAME`, `JOB_NAME`, `JOB_GROUP`),
    KEY `IDX_QRTZ_T_JG` (`SCHED_NAME`, `JOB_GROUP`),
    KEY `IDX_QRTZ_T_C` (`SCHED_NAME`, `CALENDAR_NAME`),
    KEY `IDX_QRTZ_T_G` (`SCHED_NAME`, `TRIGGER_GROUP`),
    KEY `IDX_QRTZ_T_STATE` (`SCHED_NAME`, `TRIGGER_STATE`),
    KEY `IDX_QRTZ_T_N_STATE` (`SCHED_NAME`, `TRIGGER_NAME`, `TRIGGER_GROUP`, `TRIGGER_STATE`),
    KEY `IDX_QRTZ_T_N_G_STATE` (`SCHED_NAME`, `TRIGGER_GROUP`, `TRIGGER_STATE`),
    KEY `IDX_QRTZ_T_NEXT_FIRE_TIME` (`SCHED_NAME`, `NEXT_FIRE_TIME`),
    KEY `IDX_QRTZ_T_NFT_ST` (`SCHED_NAME`, `TRIGGER_STATE`, `NEXT_FIRE_TIME`),
    KEY `IDX_QRTZ_T_NFT_MISFIRE` (`SCHED_NAME`, `MISFIRE_INSTR`, `NEXT_FIRE_TIME`),
    KEY `IDX_QRTZ_T_NFT_ST_MISFIRE` (`SCHED_NAME`, `MISFIRE_INSTR`, `NEXT_FIRE_TIME`, `TRIGGER_STATE`),
    KEY `IDX_QRTZ_T_NFT_ST_MISFIRE_GRP` (`SCHED_NAME`, `MISFIRE_INSTR`, `NEXT_FIRE_TIME`, `TRIGGER_GROUP`, `TRIGGER_STATE`),
    CONSTRAINT `FK_QRTZ_TRIGGERS_QRTZ_JOB_DETAILS`
        FOREIGN KEY (`SCHED_NAME`, `JOB_NAME`, `JOB_GROUP`)
            REFERENCES `QRTZ_JOB_DETAILS` (`SCHED_NAME`, `JOB_NAME`, `JOB_GROUP`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
  COMMENT = 'Quartz 触发器表（框架托管）';

CREATE TABLE IF NOT EXISTS `QRTZ_SIMPLE_TRIGGERS` (
    `SCHED_NAME`      VARCHAR(120) NOT NULL,
    `TRIGGER_NAME`    VARCHAR(190) NOT NULL,
    `TRIGGER_GROUP`   VARCHAR(190) NOT NULL,
    `REPEAT_COUNT`    BIGINT       NOT NULL,
    `REPEAT_INTERVAL` BIGINT       NOT NULL,
    `TIMES_TRIGGERED` BIGINT       NOT NULL,
    PRIMARY KEY (`SCHED_NAME`, `TRIGGER_NAME`, `TRIGGER_GROUP`),
    CONSTRAINT `FK_QRTZ_SIMPLE_TRIGGERS_QRTZ_TRIGGERS`
        FOREIGN KEY (`SCHED_NAME`, `TRIGGER_NAME`, `TRIGGER_GROUP`)
            REFERENCES `QRTZ_TRIGGERS` (`SCHED_NAME`, `TRIGGER_NAME`, `TRIGGER_GROUP`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
  COMMENT = 'Quartz 简单触发器表（框架托管）';

CREATE TABLE IF NOT EXISTS `QRTZ_CRON_TRIGGERS` (
    `SCHED_NAME`      VARCHAR(120) NOT NULL,
    `TRIGGER_NAME`    VARCHAR(190) NOT NULL,
    `TRIGGER_GROUP`   VARCHAR(190) NOT NULL,
    `CRON_EXPRESSION` VARCHAR(120) NOT NULL,
    `TIME_ZONE_ID`    VARCHAR(80)  NULL,
    PRIMARY KEY (`SCHED_NAME`, `TRIGGER_NAME`, `TRIGGER_GROUP`),
    CONSTRAINT `FK_QRTZ_CRON_TRIGGERS_QRTZ_TRIGGERS`
        FOREIGN KEY (`SCHED_NAME`, `TRIGGER_NAME`, `TRIGGER_GROUP`)
            REFERENCES `QRTZ_TRIGGERS` (`SCHED_NAME`, `TRIGGER_NAME`, `TRIGGER_GROUP`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
  COMMENT = 'Quartz CRON 触发器表（框架托管）';

CREATE TABLE IF NOT EXISTS `QRTZ_SIMPROP_TRIGGERS` (
    `SCHED_NAME`    VARCHAR(120) NOT NULL,
    `TRIGGER_NAME`  VARCHAR(190) NOT NULL,
    `TRIGGER_GROUP` VARCHAR(190) NOT NULL,
    `STR_PROP_1`    VARCHAR(512) NULL,
    `STR_PROP_2`    VARCHAR(512) NULL,
    `STR_PROP_3`    VARCHAR(512) NULL,
    `INT_PROP_1`    INT          NULL,
    `INT_PROP_2`    INT          NULL,
    `LONG_PROP_1`   BIGINT       NULL,
    `LONG_PROP_2`   BIGINT       NULL,
    `DEC_PROP_1`    NUMERIC(13, 4) NULL,
    `DEC_PROP_2`    NUMERIC(13, 4) NULL,
    `BOOL_PROP_1`   VARCHAR(1)   NULL,
    `BOOL_PROP_2`   VARCHAR(1)   NULL,
    PRIMARY KEY (`SCHED_NAME`, `TRIGGER_NAME`, `TRIGGER_GROUP`),
    CONSTRAINT `FK_QRTZ_SIMPROP_TRIGGERS_QRTZ_TRIGGERS`
        FOREIGN KEY (`SCHED_NAME`, `TRIGGER_NAME`, `TRIGGER_GROUP`)
            REFERENCES `QRTZ_TRIGGERS` (`SCHED_NAME`, `TRIGGER_NAME`, `TRIGGER_GROUP`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
  COMMENT = 'Quartz 扩展属性触发器表（框架托管）';

CREATE TABLE IF NOT EXISTS `QRTZ_BLOB_TRIGGERS` (
    `SCHED_NAME`    VARCHAR(120) NOT NULL,
    `TRIGGER_NAME`  VARCHAR(190) NOT NULL,
    `TRIGGER_GROUP` VARCHAR(190) NOT NULL,
    `BLOB_DATA`     BLOB         NULL,
    PRIMARY KEY (`SCHED_NAME`, `TRIGGER_NAME`, `TRIGGER_GROUP`),
    KEY `IDX_QRTZ_BT_TRIGGER` (`SCHED_NAME`, `TRIGGER_NAME`, `TRIGGER_GROUP`),
    CONSTRAINT `FK_QRTZ_BLOB_TRIGGERS_QRTZ_TRIGGERS`
        FOREIGN KEY (`SCHED_NAME`, `TRIGGER_NAME`, `TRIGGER_GROUP`)
            REFERENCES `QRTZ_TRIGGERS` (`SCHED_NAME`, `TRIGGER_NAME`, `TRIGGER_GROUP`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
  COMMENT = 'Quartz BLOB 触发器表（框架托管）';

CREATE TABLE IF NOT EXISTS `QRTZ_CALENDARS` (
    `SCHED_NAME`    VARCHAR(120) NOT NULL,
    `CALENDAR_NAME` VARCHAR(190) NOT NULL,
    `CALENDAR`      BLOB         NOT NULL,
    PRIMARY KEY (`SCHED_NAME`, `CALENDAR_NAME`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
  COMMENT = 'Quartz 日历表（框架托管）';

CREATE TABLE IF NOT EXISTS `QRTZ_PAUSED_TRIGGER_GRPS` (
    `SCHED_NAME`    VARCHAR(120) NOT NULL,
    `TRIGGER_GROUP` VARCHAR(190) NOT NULL,
    PRIMARY KEY (`SCHED_NAME`, `TRIGGER_GROUP`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
  COMMENT = 'Quartz 已暂停触发组表（框架托管）';

CREATE TABLE IF NOT EXISTS `QRTZ_FIRED_TRIGGERS` (
    `SCHED_NAME`        VARCHAR(120) NOT NULL,
    `ENTRY_ID`          VARCHAR(95)  NOT NULL,
    `TRIGGER_NAME`      VARCHAR(190) NOT NULL,
    `TRIGGER_GROUP`     VARCHAR(190) NOT NULL,
    `INSTANCE_NAME`     VARCHAR(190) NOT NULL,
    `FIRED_TIME`        BIGINT       NOT NULL,
    `SCHED_TIME`        BIGINT       NOT NULL,
    `PRIORITY`          INT          NOT NULL,
    `STATE`             VARCHAR(16)  NOT NULL,
    `JOB_NAME`          VARCHAR(190) NULL,
    `JOB_GROUP`         VARCHAR(190) NULL,
    `IS_NONCONCURRENT`  VARCHAR(1)   NULL,
    `REQUESTS_RECOVERY` VARCHAR(1)   NULL,
    PRIMARY KEY (`SCHED_NAME`, `ENTRY_ID`),
    KEY `IDX_QRTZ_FT_TRIG_INST_NAME` (`SCHED_NAME`, `INSTANCE_NAME`),
    KEY `IDX_QRTZ_FT_INST_JOB_REQ_RCVRY` (`SCHED_NAME`, `INSTANCE_NAME`, `REQUESTS_RECOVERY`),
    KEY `IDX_QRTZ_FT_J_G` (`SCHED_NAME`, `JOB_NAME`, `JOB_GROUP`),
    KEY `IDX_QRTZ_FT_JG` (`SCHED_NAME`, `JOB_GROUP`),
    KEY `IDX_QRTZ_FT_T_G` (`SCHED_NAME`, `TRIGGER_NAME`, `TRIGGER_GROUP`),
    KEY `IDX_QRTZ_FT_TG` (`SCHED_NAME`, `TRIGGER_GROUP`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
  COMMENT = 'Quartz 已触发触发器表（框架托管）';

CREATE TABLE IF NOT EXISTS `QRTZ_SCHEDULER_STATE` (
    `SCHED_NAME`        VARCHAR(120) NOT NULL,
    `INSTANCE_NAME`     VARCHAR(190) NOT NULL,
    `LAST_CHECKIN_TIME` BIGINT       NOT NULL,
    `CHECKIN_INTERVAL`  BIGINT       NOT NULL,
    PRIMARY KEY (`SCHED_NAME`, `INSTANCE_NAME`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
  COMMENT = 'Quartz 调度器状态表（框架托管）';

CREATE TABLE IF NOT EXISTS `QRTZ_LOCKS` (
    `SCHED_NAME` VARCHAR(120) NOT NULL,
    `LOCK_NAME`  VARCHAR(40)  NOT NULL,
    PRIMARY KEY (`SCHED_NAME`, `LOCK_NAME`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
  COMMENT = 'Quartz 锁表（框架托管）';

-- 幂等写入 F7 的默认 Skill：training-planning（计划正文写法与排期口径）。
-- 重复执行不产生重复数据，也不覆盖人工在表里临时调整过的规则（命中唯一键时只做同值更新）。
INSERT INTO `agentscope_skills` (`name`, `description`, `skill_content`, `source`)
VALUES ('training-planning',
        '训练计划排期口径：按天用一句话概括当天练什么知识点，薄弱点优先、计划正文尽量短',
        '你正在执行「训练计划排期口径」：把用户给的「还有几天、每天能练多久」翻译成一份简短的按天计划正文。口径如下，不要输出本规范的标题或内部字段。\n\n# 一、正文怎么写（核心：短）\n1. 一天一行，格式固定成「第 N 天：<今天练什么>」。正文里**只写每天做什么，一句话概括**，例如：\n   第 1 天：Redis 分布式锁——能讲清加锁、续期、释放三步\n   第 2 天：JVM 内存模型——能画出堆/栈/方法区并解释对象分配\n2. 不要写题型、难度分档、时长分钟数、字段清单或表格：每天的时长由系统按用户输入展示，正文只回答「今天干什么」。\n3. 整份正文尽量短：除了每天那几行，最多再写一句总起（例如「这次优先补三个薄弱点，每天一小时」），不要展开讲知识点内容。\n4. 正文用 Markdown，每天一行；不要输出工具名、内部字段名，也不写「接下来我将」「已加载」这类过程话术。\n\n# 二、每天安排什么（薄弱点优先）\n1. 先用只读工具读该用户当前的薄弱点与掌握度：掌握度低或最近一次判定答错的知识点排在前面。\n2. 知识点名称尽量沿用工具返回的原始名称，方便用户回看时对得上。\n3. 掌握度已经不错的主题只在最后安排一天快速回顾，不重复堆。\n4. 没有薄弱点记录时按目标岗位的常见考点安排，并说明「还没有练习记录，本计划以目标岗位为准」，不要编造薄弱点。\n5. 天数多于薄弱点数量时，靠后的天安排复习或相近主题的延伸，不要为了凑天数编造无关内容。\n\n# 三、重新规划怎么办\n1. 说清依据：哪几个知识点成了新的薄弱点、哪些已经掌握、还剩多少天——一句话写在正文开头。\n2. 仍要补的知识点继续排，只调整顺序与详略；不要推倒重来。\n\n# 四、底线\n1. 不编造用户的经历、可用时长与练习记录；用户说几天就几天。\n2. 不承诺「包过」「必中」，也不写刷题量承诺。',
        'f7-training-planning')
ON DUPLICATE KEY UPDATE `name` = `name`;
