package com.wxy.career.common.mybatis;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.wxy.career.common.auth.LoginUserHolder;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 公共字段自动填充。
 *
 * @author wxy
 * @date 2026-09-27
 */
@Component
public class AuditMetaObjectHandler implements MetaObjectHandler {

    /**
     * 系统或未登录场景使用的默认用户 ID。
     */
    private static final long SYSTEM_USER_ID = 0L;

    /**
     * 新增时填充创建和更新审计字段。
     *
     * @param metaObject 数据库实体元对象
     */
    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        Long userId = currentUserId();
        strictInsertFill(metaObject, "createTime", LocalDateTime.class, now);
        strictInsertFill(metaObject, "createBy", Long.class, userId);
        strictInsertFill(metaObject, "updateTime", LocalDateTime.class, now);
        strictInsertFill(metaObject, "updateBy", Long.class, userId);
    }

    /**
     * 更新时填充更新审计字段。
     *
     * @param metaObject 数据库实体元对象
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
        strictUpdateFill(metaObject, "updateBy", Long.class, currentUserId());
    }

    /**
     * 获取当前操作的审计用户 ID。
     *
     * @return 当前用户 ID，未登录时为 0
     */
    private Long currentUserId() {
        Long userId = LoginUserHolder.getUserId();
        return userId == null ? SYSTEM_USER_ID : userId;
    }
}
