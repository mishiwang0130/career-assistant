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
CREATE TABLE IF NOT EXISTS `assistant_message` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id`     BIGINT       NOT NULL COMMENT '用户ID',
    `session_id`  VARCHAR(64)  NOT NULL COMMENT '会话ID',
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
