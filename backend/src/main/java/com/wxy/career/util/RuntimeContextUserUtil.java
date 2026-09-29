package com.wxy.career.util;

import com.wxy.career.common.exception.BizException;
import com.wxy.career.common.result.ErrorConstant;
import io.agentscope.core.agent.RuntimeContext;
import lombok.extern.slf4j.Slf4j;

/**
 * 运行时上下文里的用户身份解析工具。
 *
 * <p>工具拿用户身份一律走 {@code RuntimeContext.userId}，绝不接受模型传入的 userId：
 * 模型只能看到参数，无法伪造登录态。缺失或非法时按未登录处理，不返回任何用户数据。
 *
 * @author wxy
 * @date 2026-09-29
 */
@Slf4j
public final class RuntimeContextUserUtil {

    /**
     * 工具类禁止实例化。
     */
    private RuntimeContextUserUtil() {
    }

    /**
     * 从运行时上下文解析当前用户 ID。
     *
     * @param runtimeContext 运行时上下文
     * @return 用户 ID
     */
    public static Long requireUserId(RuntimeContext runtimeContext) {
        if (runtimeContext == null || runtimeContext.getUserId() == null) {
            throw new BizException(ErrorConstant.UNAUTHORIZED);
        }
        try {
            return Long.valueOf(runtimeContext.getUserId());
        } catch (NumberFormatException exception) {
            log.warn("运行时上下文中的用户标识非法，userId={}", runtimeContext.getUserId());
            throw new BizException(ErrorConstant.UNAUTHORIZED);
        }
    }
}
