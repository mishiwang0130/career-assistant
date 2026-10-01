package com.wxy.career.service.impl;

import com.wxy.career.common.enums.TrainingPlanStatusEnum;
import com.wxy.career.common.exception.BizException;
import com.wxy.career.common.result.ErrorCode;
import com.wxy.career.common.result.ErrorConstant;
import com.wxy.career.config.TrainingProperties;
import com.wxy.career.mapper.TrainingPlanMapper;
import com.wxy.career.mapper.TrainingReminderMapper;
import com.wxy.career.mapper.TrainingTaskMapper;
import com.wxy.career.po.TrainingPlan;
import com.wxy.career.po.TrainingReminder;
import com.wxy.career.po.TrainingTask;
import com.wxy.career.service.TrainingPlanService;
import com.wxy.career.service.UserProfileService;
import com.wxy.career.util.TrainingPlanAllocator;
import com.wxy.career.vo.TrainingDayRespVO;
import com.wxy.career.vo.TrainingPlanRespVO;
import com.wxy.career.vo.TrainingPlanSubmitVO;
import com.wxy.career.vo.TrainingReminderRespVO;
import com.wxy.career.vo.TrainingTaskRespVO;
import com.wxy.career.vo.TrainingTaskSubmitVO;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 训练计划服务实现。
 *
 * <p>计划正文只落 MySQL；「还有几天」只在生成时输入一次，落到计划的截止日期，页面上的剩余天数由它实时算出。
 * 提交结论按 {@code userId + sessionId} 暂存一份运行态缓冲（与 F2 的简历诊断同一套做法），生成流结束时取走并
 * 下发给前端。
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
     * 计划结论缓冲的存活时间（毫秒），异常路径下的兜底清理，正常路径流一结束就被取走。
     */
    private static final long BUFFER_TTL_MILLIS = 30 * 60 * 1000L;

    /**
     * 计划概要落库的最大长度，超出截断。
     */
    private static final int SUMMARY_MAX_LENGTH = 2000;

    /**
     * 调整原因落库的最大长度，超出截断。
     */
    private static final int ADJUSTMENT_REASON_MAX_LENGTH = 500;

    /**
     * 训练主题落库的最大长度，超出截断。
     */
    private static final int TOPIC_MAX_LENGTH = 200;

    /**
     * 题型落库的最大长度，超出截断。
     */
    private static final int QUESTION_TYPE_MAX_LENGTH = 32;

    /**
     * 知识点落库的最大长度，超出截断。
     */
    private static final int KNOWLEDGE_POINT_MAX_LENGTH = 200;

    /**
     * 难度最小值。
     */
    private static final int MIN_DIFFICULTY = 1;

    /**
     * 难度最大值。
     */
    private static final int MAX_DIFFICULTY = 5;

    /**
     * 模型没给难度时的缺省值（中等难度）。
     */
    private static final int DEFAULT_DIFFICULTY = 3;

    /**
     * 计划 Mapper。
     */
    @Resource
    private TrainingPlanMapper trainingPlanMapper;

    /**
     * 任务 Mapper。
     */
    @Resource
    private TrainingTaskMapper trainingTaskMapper;

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
     * 训练计划配置。
     */
    @Resource
    private TrainingProperties trainingProperties;

    /**
     * 计划结论运行态缓冲：键为 {@code userId/sessionId}。
     */
    private final Map<String, BufferedPlan> submittedBuffer = new ConcurrentHashMap<>();

    /**
     * 本次生成请求的输入（天数与每日时长），键为 {@code userId/sessionId}。
     *
     * <p>用户输入的周期是权威值：模型提交的同名字段填错也不影响落库。
     */
    private final Map<String, BufferedInput> generationInputs = new ConcurrentHashMap<>();

    /**
     * 本次生成最近一次提交失败的原因，键为 {@code userId/sessionId}，供生成流结束时的失败提示使用。
     */
    private final Map<String, BufferedFailure> submitFailures = new ConcurrentHashMap<>();

    /**
     * 查询当前用户生效中的计划。
     *
     * @param userId 用户 ID
     * @return 计划概览
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
        fillPlan(response, plan, trainingTaskMapper.listByPlan(userId, plan.getId()), today);
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
        // 新一轮开始，清掉上一轮的失败原因，避免失败提示串到本次。
        submitFailures.remove(key);
        cleanExpiredBuffer();
    }

    /**
     * 勾选或取消勾选一条训练任务。
     *
     * @param userId 用户 ID
     * @param taskId 任务 ID
     * @param finished 目标状态
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void finishTask(Long userId, Long taskId, boolean finished) {
        requireUserId(userId);
        if (taskId == null) {
            throw new BizException(ErrorConstant.PARAM_ERROR);
        }
        TrainingTask task = trainingTaskMapper.selectByIdAndUser(taskId, userId);
        if (task == null) {
            // 任务不存在、已删除或跨账号：统一表现为找不到，不暴露资源是否存在。
            throw new BizException(ErrorConstant.CHAT_SESSION_NOT_FOUND);
        }
        TrainingPlan plan = trainingPlanMapper.selectByIdAndUser(task.getPlanId(), userId);
        if (plan == null || !TrainingPlanStatusEnum.ACTIVE.getValue().equals(plan.getStatus())) {
            // 任务属于已被重规划替换的旧计划：提示刷新，避免在失效计划上继续勾选。
            throw new BizException(ErrorConstant.TRAINING_TASK_NOT_FOUND);
        }
        // 幂等：重复提交同一状态只更新一次，结果一致。
        trainingTaskMapper.updateFinished(taskId, userId, finished ? 1 : 0);
    }

    /**
     * 落库一份计划结论。
     *
     * @param userId 用户 ID
     * @param sessionId 计划 Agent 的运行标识
     * @param submitVO 计划结论
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submitPlan(Long userId, String sessionId, TrainingPlanSubmitVO submitVO) {
        requireUserId(userId);
        if (!StringUtils.hasText(sessionId)) {
            throw new BizException(ErrorConstant.PARAM_ERROR);
        }
        String key = bufferKey(userId, sessionId);
        try {
            submitPlanInternal(userId, key, sessionId, submitVO);
        } catch (BizException exception) {
            // 记下失败原因：生成流正常结束但没有产出时，用它给出「为什么没生成成功」的可读提示。
            submitFailures.put(key, new BufferedFailure(
                    exception.getErrorCode().getMsg(), System.currentTimeMillis()));
            throw exception;
        }
    }

    /**
     * 落库一份计划结论（提交工具的实现路径）。
     *
     * @param userId 用户 ID
     * @param key 运行态缓冲键
     * @param sessionId 计划 Agent 的运行标识
     * @param submitVO 计划结论
     */
    private void submitPlanInternal(Long userId, String key, String sessionId, TrainingPlanSubmitVO submitVO) {
        // 天数与每日时长以用户本次请求为准：模型提交的同名字段只做对照，填错也不改变用户输入的周期。
        BufferedInput input = resolveGenerationInput(key, submitVO);
        int days = input.days();
        int dailyMinutes = input.dailyMinutes();
        validateSubmit(submitVO, days, dailyMinutes);
        UserProfileRespVO profile = userProfileService.getRequiredUserProfile(userId);
        LocalDate today = LocalDate.now();
        LocalDate endDate = today.plusDays(days - 1L);

        // 重规划是覆盖生成：旧计划标记结束而不是物理删除，历史任务保留可回溯。
        int ended = trainingPlanMapper.endActiveByUser(userId);
        TrainingPlan plan = new TrainingPlan();
        plan.setUserId(userId);
        plan.setStatus(TrainingPlanStatusEnum.ACTIVE.getValue());
        plan.setTargetPosition(truncate(profile.getTargetPosition(), TOPIC_MAX_LENGTH));
            plan.setTotalDays(days);
        plan.setDailyMinutes(dailyMinutes);
        plan.setStartDate(today);
        plan.setEndDate(endDate);
        plan.setPlanSummary(truncate(submitVO.getSummary(), SUMMARY_MAX_LENGTH));
        plan.setAdjustmentReason(truncate(submitVO.getAdjustmentReason(), ADJUSTMENT_REASON_MAX_LENGTH));
        plan.setGeneratedAt(LocalDateTime.now());
        trainingPlanMapper.insert(plan);

        int sortOrder = 1;
        for (TrainingTaskSubmitVO taskSubmit : submitVO.getTasks()) {
            TrainingTask task = new TrainingTask();
            task.setUserId(userId);
            task.setPlanId(plan.getId());
            task.setDayIndex(taskSubmit.getDayIndex());
            task.setTaskDate(today.plusDays(taskSubmit.getDayIndex() - 1L));
            task.setTopic(truncate(taskSubmit.getTopic(), TOPIC_MAX_LENGTH));
            task.setQuestionType(truncate(taskSubmit.getQuestionType(), QUESTION_TYPE_MAX_LENGTH));
            task.setDifficulty(normalizeDifficulty(taskSubmit.getDifficulty()));
            task.setDurationMinutes(taskSubmit.getDurationMinutes());
            task.setKnowledgePoint(truncate(taskSubmit.getKnowledgePoint(), KNOWLEDGE_POINT_MAX_LENGTH));
            task.setSortOrder(sortOrder++);
            task.setFinished(0);
            trainingTaskMapper.insert(task);
        }
        log.info("训练计划已落库，userId={}，planId={}，days={}，dailyMinutes={}，taskCount={}，endedPlans={}",
                userId, plan.getId(), days, dailyMinutes, submitVO.getTasks().size(), ended);
        submittedBuffer.put(key, new BufferedPlan(plan.getId(), System.currentTimeMillis()));
        cleanExpiredBuffer();
    }

    /**
     * 解析本次生成的天数与每日时长。
     *
     * <p>优先用生成开始时登记的请求输入；没有登记（例如直接调用服务层的场景）时退回模型提交的字段，
     * 两者都不可用时按参数错误拒绝。
     *
     * @param key 运行态缓冲键
     * @param submitVO 计划结论
     * @return 生效的输入
     */
    private BufferedInput resolveGenerationInput(String key, TrainingPlanSubmitVO submitVO) {
        BufferedInput recorded = generationInputs.get(key);
        if (recorded != null) {
            return recorded;
        }
        if (submitVO == null || submitVO.getDays() == null || submitVO.getDailyMinutes() == null) {
            throw new BizException(new ErrorCode(ErrorConstant.PARAM_ERROR.getCode(),
                    "计划缺少天数或每天时长"));
        }
        return new BufferedInput(submitVO.getDays(), submitVO.getDailyMinutes(), System.currentTimeMillis());
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
        fillPlan(response, plan, trainingTaskMapper.listByPlan(userId, plan.getId()), LocalDate.now());
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
     * 校验提交的计划结论是否可用。
     *
     * <p>校验口径与分配器一致：任务必须覆盖每一天（任务数超上限时按上限取前 N 天）；当天任务时长合计不能超过
     * 每日时长。**失败原因写进异常消息**：工具会把它转述给模型（让它自己改正），生成流结束时的提示也用它说明
     * 「为什么没生成成功」，而不是笼统的「参数错误」。
     *
     * @param submitVO 计划结论
     * @param days 本次请求的天数（以请求为准）
     * @param dailyMinutes 本次请求的每日时长（以请求为准）
     */
    private void validateSubmit(TrainingPlanSubmitVO submitVO, int days, int dailyMinutes) {
        if (submitVO == null) {
            throw paramError("计划内容为空，请重试");
        }
        TrainingProperties.Plan planConfig = trainingProperties.getPlan();
        List<TrainingTaskSubmitVO> tasks = submitVO.getTasks();
        if (tasks == null || tasks.isEmpty()) {
            throw paramError("计划里没有任何任务");
        }
        if (tasks.size() > planConfig.getMaxTasks()) {
            throw paramError("计划的任务条数（" + tasks.size() + "）超过上限 "
                    + planConfig.getMaxTasks() + " 条");
        }
        List<TrainingPlanAllocator.DaySlot> slots =
                TrainingPlanAllocator.allocate(days, dailyMinutes, planConfig.getMaxTasks());
        int scheduledDays = slots.size();
        Map<Integer, Integer> minutesByDay = new LinkedHashMap<>();
        Map<Integer, Integer> tasksByDay = new LinkedHashMap<>();
        for (int index = 0; index < tasks.size(); index++) {
            TrainingTaskSubmitVO task = tasks.get(index);
            if (task == null || task.getDayIndex() == null || task.getDayIndex() < 1
                    || task.getDayIndex() > scheduledDays) {
                throw paramError("第 " + (index + 1) + " 条任务缺少天数，或天数超出 1-" + scheduledDays + " 天");
            }
            if (task.getDurationMinutes() == null || task.getDurationMinutes() < 1) {
                throw paramError("第 " + (index + 1) + " 条任务缺少时长，或时长不是正整数分钟");
            }
            if (!StringUtils.hasText(task.getTopic()) || !StringUtils.hasText(task.getQuestionType())) {
                throw paramError("第 " + (index + 1) + " 条任务缺少主题或题型");
            }
            minutesByDay.merge(task.getDayIndex(), task.getDurationMinutes(), Integer::sum);
            tasksByDay.merge(task.getDayIndex(), 1, Integer::sum);
        }
        for (TrainingPlanAllocator.DaySlot slot : slots) {
            if (tasksByDay.getOrDefault(slot.getDayIndex(), 0) < 1) {
                throw paramError("第 " + slot.getDayIndex() + " 天没有安排任务");
            }
            if (minutesByDay.getOrDefault(slot.getDayIndex(), 0) > dailyMinutes) {
                throw paramError("第 " + slot.getDayIndex() + " 天的任务时长合计 "
                        + minutesByDay.get(slot.getDayIndex()) + " 分钟，超过每天 " + dailyMinutes + " 分钟");
            }
        }
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
     * 把难度归一到 1-5：模型给空值或越界值时不整单拒绝，按就近取值落库（展示用的等级，不影响计划结构）。
     *
     * @param difficulty 模型给的难度，可为空
     * @return 1-5 的难度
     */
    private int normalizeDifficulty(Integer difficulty) {
        if (difficulty == null) {
            return DEFAULT_DIFFICULTY;
        }
        return Math.max(MIN_DIFFICULTY, Math.min(MAX_DIFFICULTY, difficulty));
    }

    /**
     * 填充计划响应：概览字段 + 按天任务 + 剩余天数。
     *
     * @param response 待填充的响应
     * @param plan 计划记录
     * @param tasks 任务列表
     * @param today 今天
     */
    private void fillPlan(TrainingPlanRespVO response, TrainingPlan plan, List<TrainingTask> tasks, LocalDate today) {
        response.setHasPlan(Boolean.TRUE);
        response.setPlanId(plan.getId());
        response.setStatus(plan.getStatus());
        response.setTargetPosition(plan.getTargetPosition());
        response.setStartDate(formatDate(plan.getStartDate()));
        response.setEndDate(formatDate(plan.getEndDate()));
        response.setTotalDays(plan.getTotalDays());
        response.setDailyMinutes(plan.getDailyMinutes());
        response.setRemainingDays(remainingDays(plan.getEndDate(), today));
        response.setSummary(plan.getPlanSummary());
        response.setAdjustmentReason(plan.getAdjustmentReason());
        response.setGeneratedAt(plan.getGeneratedAt() == null
                ? null : plan.getGeneratedAt().format(DATETIME_FORMATTER));
        response.setDays(groupByDay(tasks));
    }

    /**
     * 把任务按天分组，并算出每天的时长合计与完成数。
     *
     * @param tasks 任务列表（已按天与顺序排列）
     * @return 按天分组的清单
     */
    private List<TrainingDayRespVO> groupByDay(List<TrainingTask> tasks) {
        Map<Integer, TrainingDayRespVO> days = new LinkedHashMap<>();
        for (TrainingTask task : tasks) {
            TrainingDayRespVO day = days.computeIfAbsent(task.getDayIndex(), dayIndex -> {
                TrainingDayRespVO created = new TrainingDayRespVO();
                created.setDayIndex(dayIndex);
                created.setTaskDate(formatDate(task.getTaskDate()));
                created.setTotalMinutes(0);
                created.setFinishedCount(0);
                return created;
            });
            day.getTasks().add(toTaskResp(task));
            day.setTotalMinutes(day.getTotalMinutes() + (task.getDurationMinutes() == null
                    ? 0 : task.getDurationMinutes()));
            if (Integer.valueOf(1).equals(task.getFinished())) {
                day.setFinishedCount(day.getFinishedCount() + 1);
            }
        }
        return new ArrayList<>(days.values());
    }

    /**
     * 任务记录转响应。
     *
     * @param task 任务记录
     * @return 任务响应
     */
    private TrainingTaskRespVO toTaskResp(TrainingTask task) {
        TrainingTaskRespVO response = new TrainingTaskRespVO();
        response.setId(task.getId());
        response.setPlanId(task.getPlanId());
        response.setDayIndex(task.getDayIndex());
        response.setTaskDate(formatDate(task.getTaskDate()));
        response.setTopic(task.getTopic());
        response.setQuestionType(task.getQuestionType());
        response.setDifficulty(task.getDifficulty());
        response.setDurationMinutes(task.getDurationMinutes());
        response.setKnowledgePoint(task.getKnowledgePoint());
        response.setSortOrder(task.getSortOrder());
        response.setFinished(Integer.valueOf(1).equals(task.getFinished()));
        response.setFinishTime(task.getFinishTime() == null
                ? null : task.getFinishTime().format(DATETIME_FORMATTER));
        return response;
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
     * 清理过期缓冲，避免异常路径把结论长期留在内存里。
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
