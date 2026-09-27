package com.wxy.career.common.mybatis;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.wxy.career.common.auth.LoginUserHolder;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 公共字段自动填充。
 */
@Component
public class AuditMetaObjectHandler implements MetaObjectHandler {

    private static final long SYSTEM_USER_ID = 0L;

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        Long userId = currentUserId();
        strictInsertFill(metaObject, "createTime", LocalDateTime.class, now);
        strictInsertFill(metaObject, "createBy", Long.class, userId);
        strictInsertFill(metaObject, "updateTime", LocalDateTime.class, now);
        strictInsertFill(metaObject, "updateBy", Long.class, userId);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
        strictUpdateFill(metaObject, "updateBy", Long.class, currentUserId());
    }

    private Long currentUserId() {
        Long userId = LoginUserHolder.getUserId();
        return userId == null ? SYSTEM_USER_ID : userId;
    }
}
