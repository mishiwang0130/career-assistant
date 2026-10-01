package com.wxy.career.service.impl;

import com.wxy.career.common.enums.TrainingPlanStatusEnum;
import com.wxy.career.common.exception.BizException;
import com.wxy.career.common.result.ErrorCode;
import com.wxy.career.common.result.ErrorConstant;
import com.wxy.career.mapper.TrainingPlanMapper;
import com.wxy.career.mapper.TrainingReminderMapper;
import com.wxy.career.po.TrainingPlan;
import com.wxy.career.po.TrainingReminder;
import com.wxy.career.service.TrainingPlanService;
import com.wxy.career.service.UserProfileService;
import com.wxy.career.vo.TrainingPlanRespVO;
import com.wxy.career.vo.TrainingReminderRespVO;
import com.wxy.career.vo.UserProfileRespVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 训练计划服务实现。
 *
 * <p>一份计划 = 一条 {@code training_plan} 记录：起止日期与每天时长来自用户表单输入，正文（一天一行「今天练什么
 * 知识点」）存在 {@code plan_content}。没有训练任务表——提醒 Agent 每天读正文推断今天要干什么。
 *
 * @author wxy
 * @date 2026-10-01
 */
@Slf4j
@Service
public class TrainingPlanServiceImpl implements TrainingPlanService {

    /**
     * 日期格式。
     */
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * 日期时间格式。
     */
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * 计划正文的最小长度：太短基本等于没写计划。
     */
    private static final int MIN_CONTENT_LENGTH = 10;

    /**
     * 运行态缓冲的存活时间（毫秒），异常路径下的兜底清理，正常路径流一结束就被取走。
     */
    private static final long BUFFER_TTL_MILLIS = 30 * 60 * 1000L;

    /**
     * 计划 Mapper。
     */
    @Resource
    private TrainingPlanMapper trainingPlanMapper;

    /**
     * 提醒 Mapper，用于在计划概览里带上今日提醒与未读角标。
     */
    @Resource
    private TrainingReminderMapper trainingReminderMapper;

    /**
     * 求职目标服务：计划必须挂在已填写的求职目标上，目标岗位取生成时的快照。
     */
    @Resource
    private UserProfileService userProfileService;

    /**
     * 计划产物运行态缓冲：键为 {@code userId/sessionId}。
     */
    private final Map<String, BufferedPlan> submittedBuffer = new ConcurrentHashMap<>();

    /**
     * 本次生成请求的输入（天数与每日时长），键为 {@code userId/sessionId}。
     */
    private final Map<String, BufferedInput> generationInputs = new ConcurrentHashMap<>();

    /**
     * 本次生成最近一次提交失败的原因，键为 {@code userId/sessionId}。
     */
    private final Map<String, BufferedFailure> submitFailures = new ConcurrentHashMap<>();

    /**
     * 查询当前用户生效中的计划。
     *
     * @param userId 用户 ID
     * @return 计划
     */
    @Override
    public TrainingPlanRespVO getCurrentPlan(Long userId) {
        requireUserId(userId);
        LocalDate today = LocalDate.now();
        TrainingPlanRespVO response = new TrainingPlanRespVO();
        response.setUnreadReminderCount(trainingReminderMapper.countUnread(userId));
        response.setTodayReminder(toReminderResp(trainingReminderMapper.selectByUserAndDate(userId, today)));
        TrainingPlan plan = trainingPlanMapper.selectActiveByUser(userId);
        if (plan == null) {
            // 没有生效计划不是错误：计划页展示空状态与生成入口。
            return response;
        }
        fillPlan(response, plan, today);
        return response;
    }

    /**
     * 判断用户当前是否有生效中的计划。
     *
     * @param userId 用户 ID
     * @return true 表示有生效计划
     */
    @Override
    public boolean hasActivePlan(Long userId) {
        requireUserId(userId);
        return trainingPlanMapper.selectActiveByUser(userId) != null;
    }

    /**
     * 记下本次生成请求的输入。
     *
     * @param userId 用户 ID
     * @param sessionId 计划 Agent 的运行标识
     * @param days 本次请求的天数
     * @param dailyMinutes 本次请求的每日时长（分钟）
     */
    @Override
    public void recordGenerationInput(Long userId, String sessionId, int days, int dailyMinutes) {
        requireUserId(userId);
        if (!StringUtils.hasText(sessionId) || days < 1 || dailyMinutes < 1) {
            throw new BizException(ErrorConstant.PARAM_ERROR);
        }
        String key = bufferKey(userId, sessionId);
        generationInputs.put(key, new BufferedInput(days, dailyMinutes, System.currentTimeMillis()));
        // 新一轮开始：清掉上一轮的产物与失败原因，避免串到本次。
        submittedBuffer.remove(key);
        submitFailures.remove(key);
        cleanExpiredBuffer();
    }

    /**
     * 落库一份计划正文。
     *
     * @param userId 用户 ID
     * @param sessionId 计划 Agent 的运行标识
     * @param planContent 计划正文
     * @param adjustmentReason 调整原因
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submitPlan(Long userId, String sessionId, String planContent, String adjustmentReason) {
        requireUserId(userId);
        if (!StringUtils.hasText(sessionId)) {
            throw new BizException(ErrorConstant.PARAM_ERROR);
        }
        String key = bufferKey(userId, sessionId);
        try {
            writePlan(userId, key, planContent, adjustmentReason);
        } catch (BizException exception) {
            // 记下失败原因：生成流正常结束但没有产出时，用它给出「为什么没生成成功」的可读提示。
            submitFailures.put(key, new BufferedFailure(
                    exception.getErrorCode().getMsg(), System.currentTimeMillis()));
            throw exception;
        }
    }

    /**
     * 校验并写入计划。
     *
     * @param userId 用户 ID
     * @param key 运行态缓冲键
     * @param planContent 计划正文
     * @param adjustmentReason 调整原因
     */
    private void writePlan(Long userId, String key, String planContent, String adjustmentReason) {
        if (!StringUtils.hasText(planContent) || planContent.strip().length() < MIN_CONTENT_LENGTH) {
            throw paramError("计划正文太短，请按天写出「第 N 天：今天练什么知识点」");
        }
        BufferedInput input = generationInputs.get(key);
        if (input == null) {
            throw paramError("本次生成的输入已失效，请重新生成计划");
        }
        UserProfileRespVO profile = userProfileService.getRequiredUserProfile(userId);
        LocalDate today = LocalDate.now();
        // 重规划是覆盖生成：旧计划标记结束而不是物理删除，历史计划保留可回溯。
        int ended = trainingPlanMapper.endActiveByUser(userId);
        TrainingPlan plan = new TrainingPlan();
        plan.setUserId(userId);
        plan.setStatus(TrainingPlanStatusEnum.ACTIVE.getValue());
        plan.setTargetPosition(truncate(profile.getTargetPosition(), TrainingPlan.TARGET_POSITION_MAX_LENGTH));
        plan.setTotalDays(input.days());
        plan.setDailyMinutes(input.dailyMinutes());
        plan.setStartDate(today);
        plan.setEndDate(today.plusDays(input.days() - 1L));
        plan.setPlanContent(truncateContent(planContent));
        plan.setAdjustmentReason(truncate(adjustmentReason, TrainingPlan.ADJUSTMENT_REASON_MAX_LENGTH));
        plan.setGeneratedAt(LocalDateTime.now());
        trainingPlanMapper.insert(plan);
        log.info("训练计划已落库，userId={}，planId={}，days={}，dailyMinutes={}，contentLength={}，endedPlans={}",
                userId, plan.getId(), input.days(), input.dailyMinutes(),
                plan.getPlanContent() == null ? 0 : plan.getPlanContent().length(), ended);
        submittedBuffer.put(key, new BufferedPlan(plan.getId(), System.currentTimeMillis()));
        cleanExpiredBuffer();
    }

    /**
     * 取走本次生成落库的计划并清空缓冲。
     *
     * @param userId 用户 ID
     * @param sessionId 计划 Agent 的运行标识
     * @return 计划响应，没有提交过时返回 null
     */
    @Override
    public TrainingPlanRespVO consumeSubmittedPlan(Long userId, String sessionId) {
        if (userId == null || !StringUtils.hasText(sessionId)) {
            return null;
        }
        String key = bufferKey(userId, sessionId);
        BufferedPlan buffered = submittedBuffer.remove(key);
        // 本次运行结束：输入与失败原因都清掉，避免串到下一轮。
        generationInputs.remove(key);
        submitFailures.remove(key);
        if (buffered == null) {
            return null;
        }
        TrainingPlan plan = trainingPlanMapper.selectByIdAndUser(buffered.planId(), userId);
        if (plan == null) {
            return null;
        }
        TrainingPlanRespVO response = new TrainingPlanRespVO();
        response.setUnreadReminderCount(trainingReminderMapper.countUnread(userId));
        fillPlan(response, plan, LocalDate.now());
        return response;
    }

    /**
     * 取走本次生成最近一次提交失败的原因。
     *
     * @param userId 用户 ID
     * @param sessionId 计划 Agent 的运行标识
     * @return 失败原因，没有失败过时返回 null
     */
    @Override
    public String consumeSubmitFailure(Long userId, String sessionId) {
        if (userId == null || !StringUtils.hasText(sessionId)) {
            return null;
        }
        BufferedFailure failure = submitFailures.remove(bufferKey(userId, sessionId));
        return failure == null ? null : failure.reason();
    }

    /**
     * 填充计划响应：概览字段 + 正文 + 实时剩余天数。
     *
     * @param response 待填充的响应
     * @param plan 计划记录
     * @param today 今天
     */
    private void fillPlan(TrainingPlanRespVO response, TrainingPlan plan, LocalDate today) {
        response.setHasPlan(Boolean.TRUE);
        response.setPlanId(plan.getId());
        response.setStatus(plan.getStatus());
        response.setTargetPosition(plan.getTargetPosition());
        response.setStartDate(formatDate(plan.getStartDate()));
        response.setEndDate(formatDate(plan.getEndDate()));
        response.setTotalDays(plan.getTotalDays());
        response.setDailyMinutes(plan.getDailyMinutes());
        response.setRemainingDays(remainingDays(plan.getEndDate(), today));
        response.setPlanContent(plan.getPlanContent());
        response.setAdjustmentReason(plan.getAdjustmentReason());
        response.setGeneratedAt(plan.getGeneratedAt() == null
                ? null : plan.getGeneratedAt().format(DATETIME_FORMATTER));
    }

    /**
     * 提醒记录转响应。
     *
     * @param reminder 提醒记录，可为 null
     * @return 提醒响应，入参为空时返回 null
     */
    private TrainingReminderRespVO toReminderResp(TrainingReminder reminder) {
        if (reminder == null) {
            return null;
        }
        TrainingReminderRespVO response = new TrainingReminderRespVO();
        response.setId(reminder.getId());
        response.setReminderDate(formatDate(reminder.getReminderDate()));
        response.setContent(reminder.getContent());
        response.setRead(Integer.valueOf(1).equals(reminder.getReadFlag()));
        response.setReadTime(reminder.getReadTime() == null
                ? null : reminder.getReadTime().format(DATETIME_FORMATTER));
        return response;
    }

    /**
     * 算剩余天数：截止日期含当天，按自然日实时计算；已过期返回 0。
     *
     * @param endDate 截止日期
     * @param today 今天
     * @return 剩余天数
     */
    private int remainingDays(LocalDate endDate, LocalDate today) {
        if (endDate == null) {
            return 0;
        }
        long remaining = ChronoUnit.DAYS.between(today, endDate) + 1;
        return remaining < 0 ? 0 : (int) remaining;
    }

    /**
     * 构造带可读原因的 400 业务异常。
     *
     * @param reason 失败原因
     * @return 业务异常
     */
    private BizException paramError(String reason) {
        return new BizException(new ErrorCode(ErrorConstant.PARAM_ERROR.getCode(), reason));
    }

    /**
     * 格式化日期。
     *
     * @param date 日期
     * @return 日期字符串，入参为空时返回 null
     */
    private String formatDate(LocalDate date) {
        return date == null ? null : date.format(DATE_FORMATTER);
    }

    /**
     * 截断超长文本。
     *
     * @param value 原文本
     * @param maxLength 最大长度
     * @return 截断后的文本，入参为空时返回 null
     */
    private String truncate(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    /**
     * 截断超长正文（保留 Markdown 原文，仅做长度保护）。
     *
     * @param content 正文
     * @return 截断后的正文
     */
    private String truncateContent(String content) {
        String trimmed = content.strip();
        return trimmed.length() <= TrainingPlan.PLAN_CONTENT_MAX_LENGTH
                ? trimmed : trimmed.substring(0, TrainingPlan.PLAN_CONTENT_MAX_LENGTH);
    }

    /**
     * 构造缓冲键。
     *
     * @param userId 用户 ID
     * @param sessionId 运行标识
     * @return 缓冲键
     */
    private String bufferKey(Long userId, String sessionId) {
        return userId + "/" + sessionId;
    }

    /**
     * 清理过期缓冲，避免异常路径把产物长期留在内存里。
     */
    private void cleanExpiredBuffer() {
        long now = System.currentTimeMillis();
        submittedBuffer.entrySet().removeIf(entry -> now - entry.getValue().createdAt() > BUFFER_TTL_MILLIS);
        generationInputs.entrySet().removeIf(entry -> now - entry.getValue().createdAt() > BUFFER_TTL_MILLIS);
        submitFailures.entrySet().removeIf(entry -> now - entry.getValue().createdAt() > BUFFER_TTL_MILLIS);
    }

    /**
     * 校验用户 ID。
     *
     * @param userId 用户 ID
     */
    private void requireUserId(Long userId) {
        if (userId == null) {
            throw new BizException(ErrorConstant.UNAUTHORIZED);
        }
    }

    /**
     * 运行态缓冲中的计划。
     *
     * @param planId 计划 ID
     * @param createdAt 写入时间（毫秒）
     */
    private record BufferedPlan(Long planId, long createdAt) {
    }

    /**
     * 本次生成请求的输入。
     *
     * @param days 天数
     * @param dailyMinutes 每日时长（分钟）
     * @param createdAt 写入时间（毫秒）
     */
    private record BufferedInput(int days, int dailyMinutes, long createdAt) {
    }

    /**
     * 本次生成最近一次提交失败的原因。
     *
     * @param reason 失败原因
     * @param createdAt 写入时间（毫秒）
     */
    private record BufferedFailure(String reason, long createdAt) {
    }
}
