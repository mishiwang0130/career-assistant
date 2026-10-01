package com.wxy.career.common.result;

/**
 * 统一错误码常量。
 *
 * @author wxy
 * @date 2026-09-27
 */
public final class ErrorConstant {

    /**
     * 成功。
     */
    public static final ErrorCode SUCCESS = new ErrorCode(200, "成功");

    /**
     * 参数错误。
     */
    public static final ErrorCode PARAM_ERROR = new ErrorCode(400, "参数错误");

    /**
     * 未登录或登录已过期。
     */
    public static final ErrorCode UNAUTHORIZED = new ErrorCode(401, "未登录或登录已过期");

    /**
     * 无权限。
     */
    public static final ErrorCode FORBIDDEN = new ErrorCode(403, "无权限");

    /**
     * 资源不存在。
     */
    public static final ErrorCode NOT_FOUND = new ErrorCode(404, "资源不存在");

    /**
     * 系统异常。
     */
    public static final ErrorCode SYSTEM_ERROR = new ErrorCode(500, "系统异常");

    /**
     * 用户名已存在。
     */
    public static final ErrorCode USERNAME_ALREADY_EXISTS = new ErrorCode(1001, "用户名已存在");

    /**
     * 用户名或密码错误。
     */
    public static final ErrorCode USERNAME_OR_PASSWORD_ERROR = new ErrorCode(1002, "用户名或密码错误");

    /**
     * 账号已禁用。
     */
    public static final ErrorCode ACCOUNT_DISABLED = new ErrorCode(1003, "账号已禁用");

    /**
     * 刷新令牌无效或已过期。
     */
    public static final ErrorCode REFRESH_TOKEN_INVALID = new ErrorCode(1004, "刷新令牌无效或已过期");

    /**
     * 目标会话已有请求在处理中。
     */
    public static final ErrorCode SESSION_BUSY = new ErrorCode(1050, "会话正在处理中，请稍后再试");

    /**
     * 会话不存在，同时覆盖跨账号访问与已删除会话。
     */
    public static final ErrorCode CHAT_SESSION_NOT_FOUND = new ErrorCode(1051, "会话不存在");

    /**
     * 会话场景未登记或尚未开放。
     */
    public static final ErrorCode CHAT_SCENE_UNSUPPORTED = new ErrorCode(1052, "会话场景不支持");

    /**
     * 求职目标未填写。
     */
    public static final ErrorCode USER_PROFILE_REQUIRED = new ErrorCode(1101, "求职目标未填写");

    /**
     * 简历不存在。
     */
    public static final ErrorCode RESUME_NOT_FOUND = new ErrorCode(1201, "简历不存在");

    /**
     * 文件类型不支持。
     */
    public static final ErrorCode FILE_TYPE_UNSUPPORTED = new ErrorCode(1202, "文件类型不支持");

    /**
     * 文件过大。
     */
    public static final ErrorCode FILE_TOO_LARGE = new ErrorCode(1203, "文件过大");

    /**
     * 文件内容为空。
     */
    public static final ErrorCode FILE_EMPTY = new ErrorCode(1204, "文件内容为空");

    /**
     * 文件内容与扩展名不匹配。
     */
    public static final ErrorCode FILE_CONTENT_INVALID = new ErrorCode(1205, "文件内容与扩展名不匹配");

    /**
     * 文件存储失败。
     */
    public static final ErrorCode FILE_STORAGE_ERROR = new ErrorCode(1206, "文件存储失败");

    /**
     * 简历正文为空或还在解析中。
     *
     * <p>F2 简历优化引入：诊断必须有可用正文，上传解析失败或解析中都不能作为诊断输入。
     */
    public static final ErrorCode RESUME_CONTENT_UNAVAILABLE = new ErrorCode(1301, "简历正文为空或还在解析中");

    /**
     * 没有可诊断的简历。
     *
     * <p>F2 简历优化引入：既未指定简历，用户也没有任何简历（或没有默认简历）时无法确定诊断对象。
     */
    public static final ErrorCode RESUME_DIAGNOSIS_TARGET_MISSING = new ErrorCode(1302, "没有可诊断的简历");

    /**
     * 本场面试已结束。
     *
     * <p>F5 模拟面试引入（1500-1599 段）：题量走满或用户主动结束后，该会话不再接受新的作答，
     * 需要新开一场面试。
     */
    public static final ErrorCode INTERVIEW_FINISHED = new ErrorCode(1501, "本场面试已结束");

    /**
     * 会话不是模拟面试。
     *
     * <p>F5 模拟面试引入：面试状态接口只能作用于 INTERVIEW 场景的会话，传助手会话按本码拒绝。
     */
    public static final ErrorCode INTERVIEW_SCENE_MISMATCH = new ErrorCode(1502, "该会话不是模拟面试");

    /**
     * 面试尚未结束，报告暂不可用。
     *
     * <p>F6 面试点评与报告引入（1600-1699 段）：报告只在面试走到结束条件后才生成，没结束就请求报告或
     * 重试时按本码拒绝。
     */
    public static final ErrorCode INTERVIEW_REPORT_NOT_READY = new ErrorCode(1601, "面试尚未结束，报告暂不可用");

    /**
     * 报告正在生成中。
     *
     * <p>F6 面试点评与报告引入：后台子 Agent 正在生成报告，重复点重试时按本码提示稍后再试。
     */
    public static final ErrorCode INTERVIEW_REPORT_GENERATING = new ErrorCode(1602, "报告正在生成中，请稍后再试");

    /**
     * 没有生效中的训练计划。
     *
     * <p>F7 训练计划引入（1700-1799 段）：用户还没生成过计划、计划已结束或按计划 ID 查不到本人计划时按本码拒绝；
     * 跨账号访问他人资源仍统一走 1051。
     */
    public static final ErrorCode TRAINING_PLAN_NOT_FOUND = new ErrorCode(1701, "没有生效中的训练计划");

    /**
     * 训练任务不存在。
     *
     * <p>F7 训练计划引入：勾选的任务不属于当前用户或已被重规划替换时按本码拒绝。
     */
    public static final ErrorCode TRAINING_TASK_NOT_FOUND = new ErrorCode(1702, "训练任务不存在");

    /**
     * 计划正在生成中。
     *
     * <p>F7 训练计划引入：同一用户已有一次生成在跑，重复触发时按本码提示稍后再试，避免两轮生成互相覆盖。
     */
    public static final ErrorCode TRAINING_PLAN_GENERATING = new ErrorCode(1703, "计划正在生成中，请稍后再试");

    /**
     * 待确认的生成已失效。
     *
     * <p>F7 训练计划引入：覆盖确认有有效期，用户确认时已经拿不到待确认状态（超时、已被处理或应用重启）时按本码
     * 提示重新生成，避免用一份过期的计划覆盖当前计划。
     */
    public static final ErrorCode TRAINING_PLAN_CONFIRM_EXPIRED = new ErrorCode(1704, "待确认的计划已失效，请重新生成");

    /**
     * 工具类禁止实例化。
     */
    private ErrorConstant() {
    }
}
