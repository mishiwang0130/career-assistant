package com.wxy.career.service.impl;

import com.wxy.career.common.exception.BizException;
import com.wxy.career.common.result.ErrorConstant;
import com.wxy.career.config.TrainingProperties;
import com.wxy.career.mapper.TrainingPlanMapper;
import com.wxy.career.mapper.TrainingReminderMapper;
import com.wxy.career.mapper.TrainingTaskMapper;
import com.wxy.career.po.TrainingPlan;
import com.wxy.career.po.TrainingReminder;
import com.wxy.career.po.TrainingTask;
import com.wxy.career.service.TrainingReminderService;
import com.wxy.career.vo.PlannedUserBriefingVO;
import com.wxy.career.vo.PlannedUsersResultVO;
import com.wxy.career.vo.TrainingReminderRespVO;
import com.wxy.career.vo.TrainingReminderSubmitVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 训练提醒服务实现。
 *
 * <p>提醒由定时任务触发的提醒 Agent 生成，本类只负责三件事：给出「今天需要提醒谁」的简报、幂等写入当天提醒、
 * 维护未读角标。用户隔离靠所有查询都带 {@code user_id}：提醒只能读写自己的记录。
 *
 * @author wxy
 * @date 2026-10-01
 */
@Slf4j
@Service
public class TrainingReminderServiceImpl implements TrainingReminderService {

    /**
     * 日期格式。
     */
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * 日期时间格式。
     */
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * 提醒正文长度上限（字符），与提示词里的「整条不超过 60 个字」一致。
     */
    private static final int CONTENT_MAX_LENGTH = 60;

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
     * 提醒 Mapper。
     */
    @Resource
    private TrainingReminderMapper trainingReminderMapper;

    /**
     * 训练计划配置。
     */
    @Resource
    private TrainingProperties trainingProperties;

    /**
     * 统计未读提醒数。
     *
     * @param userId 用户 ID
     * @return 未读条数
     */
    @Override
    public long unreadCount(Long userId) {
        requireUserId(userId);
        return trainingReminderMapper.countUnread(userId);
    }

    /**
     * 查询某用户某天的提醒。
     *
     * @param userId 用户 ID
     * @param reminderDate 提醒日期
     * @return 提醒响应，不存在时返回 null
     */
    @Override
    public TrainingReminderRespVO findReminder(Long userId, LocalDate reminderDate) {
        requireUserId(userId);
        TrainingReminder reminder = trainingReminderMapper.selectByUserAndDate(userId, reminderDate);
        if (reminder == null) {
            return null;
        }
        TrainingReminderRespVO response = new TrainingReminderRespVO();
        response.setId(reminder.getId());
        response.setReminderDate(reminder.getReminderDate() == null
                ? null : reminder.getReminderDate().format(DATE_FORMATTER));
        response.setContent(reminder.getContent());
        response.setRead(Integer.valueOf(1).equals(reminder.getReadFlag()));
        response.setReadTime(reminder.getReadTime() == null
                ? null : reminder.getReadTime().format(DATETIME_FORMATTER));
        return response;
    }

    /**
     * 把一条提醒标记为已读。
     *
     * @param userId 用户 ID
     * @param reminderId 提醒 ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markRead(Long userId, Long reminderId) {
        requireUserId(userId);
        if (reminderId == null) {
            throw new BizException(ErrorConstant.PARAM_ERROR);
        }
        trainingReminderMapper.markRead(userId, reminderId);
    }

    /**
     * 把该用户全部未读提醒标记为已读。
     *
     * @param userId 用户 ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markAllRead(Long userId) {
        requireUserId(userId);
        trainingReminderMapper.markAllRead(userId);
    }

    /**
     * 列出今天需要提醒的用户及其训练简报。
     *
     * @return 用户简报列表
     */
    @Override
    public PlannedUsersResultVO listPlannedUsers() {
        LocalDate today = LocalDate.now();
        List<TrainingPlan> plans = trainingPlanMapper.listActivePlans(
                today, trainingProperties.getReminder().getMaxUsersPerRun());
        PlannedUsersResultVO result = new PlannedUsersResultVO();
        for (TrainingPlan plan : plans) {
            List<TrainingTask> todayTasks = trainingTaskMapper.listByPlanAndDate(
                    plan.getUserId(), plan.getId(), today);
            if (todayTasks.isEmpty()) {
                // 今天没有任务的用户不提醒，也不出现在简报里。
                continue;
            }
            PlannedUserBriefingVO briefing = new PlannedUserBriefingVO();
            briefing.setUserId(String.valueOf(plan.getUserId()));
            briefing.setTargetPosition(plan.getTargetPosition());
            briefing.setRemainingDays(remainingDays(plan.getEndDate(), today));
            for (TrainingTask task : todayTasks) {
                briefing.getTodayTasks().add(describeTask(task));
            }
            briefing.setYesterdayUnfinishedCount(trainingTaskMapper.countUnfinishedOnDate(
                    plan.getUserId(), plan.getId(), today.minusDays(1)));
            result.getUsers().add(briefing);
        }
        result.setCount(result.getUsers().size());
        result.setHasData(!result.getUsers().isEmpty());
        result.setMessage(result.getUsers().isEmpty()
                ? "今天没有需要提醒的用户。"
                : "共 " + result.getUsers().size() + " 位用户今天有训练任务。");
        log.info("每日提醒简报已准备，用户数={}，上限={}",
                result.getUsers().size(), trainingProperties.getReminder().getMaxUsersPerRun());
        return result;
    }

    /**
     * 幂等写入当天提醒。
     *
     * @param submitVO 单条提醒
     * @return true 表示已写入或更新，false 表示按口径跳过
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean saveReminder(TrainingReminderSubmitVO submitVO) {
        if (submitVO == null || !StringUtils.hasText(submitVO.getUserId())) {
            throw new BizException(ErrorConstant.PARAM_ERROR);
        }
        Long targetUserId = parseUserId(submitVO.getUserId());
        String content = normalizeContent(submitVO.getContent());
        LocalDate today = LocalDate.now();
        TrainingPlan plan = trainingPlanMapper.selectActiveByUser(targetUserId);
        if (plan == null || plan.getEndDate() == null || plan.getEndDate().isBefore(today)) {
            // 没有生效计划或计划已结束：跳过，不写库（提示词也要求这种情况不生成提醒）。
            log.info("跳过提醒写入：目标用户没有生效中的计划，userId={}", targetUserId);
            return false;
        }
        if (trainingTaskMapper.listByPlanAndDate(targetUserId, plan.getId(), today).isEmpty()) {
            log.info("跳过提醒写入：目标用户今天没有训练任务，userId={}", targetUserId);
            return false;
        }
        trainingReminderMapper.upsertDailyReminder(targetUserId, plan.getId(), today, content);
        log.info("训练提醒已落库，userId={}，planId={}，date={}", targetUserId, plan.getId(), today);
        return true;
    }

    /**
     * 把任务描述成提醒文案里可用的一句话。
     *
     * @param task 训练任务
     * @return 形如「Redis 分布式锁补齐（八股，30 分钟）」的描述
     */
    private String describeTask(TrainingTask task) {
        StringBuilder text = new StringBuilder();
        text.append(task.getTopic() == null ? "训练任务" : task.getTopic());
        text.append("（");
        text.append(StringUtils.hasText(task.getQuestionType()) ? task.getQuestionType() : "综合");
        text.append("，").append(task.getDurationMinutes() == null ? 0 : task.getDurationMinutes()).append(" 分钟）");
        if (task.getKnowledgePoint() != null && !task.getKnowledgePoint().isBlank()
                && !task.getKnowledgePoint().equals(task.getTopic())) {
            text.append("，对应知识点：").append(task.getKnowledgePoint());
        }
        return text.toString();
    }

    /**
     * 规范化提醒正文：折叠空白、截断到长度上限。
     *
     * @param content 原始正文
     * @return 规范化后的正文
     */
    private String normalizeContent(String content) {
        if (!StringUtils.hasText(content)) {
            throw new BizException(ErrorConstant.PARAM_ERROR);
        }
        String normalized = content.replaceAll("\\s+", " ").trim();
        if (normalized.length() > CONTENT_MAX_LENGTH) {
            // 超长视为模型没按要求写：直接截断，保证入库内容不变形。
            normalized = normalized.substring(0, CONTENT_MAX_LENGTH);
        }
        return normalized;
    }

    /**
     * 解析目标用户 ID。
     *
     * @param userId 用户 ID 字符串
     * @return 用户 ID
     */
    private Long parseUserId(String userId) {
        try {
            return Long.valueOf(userId.trim());
        } catch (NumberFormatException exception) {
            throw new BizException(ErrorConstant.PARAM_ERROR);
        }
    }

    /**
     * 算剩余天数：截止日期含当天。
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
     * 校验用户 ID。
     *
     * @param userId 用户 ID
     */
    private void requireUserId(Long userId) {
        if (userId == null) {
            throw new BizException(ErrorConstant.UNAUTHORIZED);
        }
    }
}
