package com.wxy.career.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Skill 初始化脚本的契约测试。
 *
 * <p>真实取数需要 MySQL（测试环境不允许依赖），因此这里只固定脚本本身的约定：技能表存在、写入幂等、
 * 技能名与子 Agent 声明里的 resume-analysis 一致、正文非空且含维度与评分口径。
 * 真实环境按「重复执行脚本 → 表里只有一行 → 按名字能取到技能」的可复现步骤验收。
 *
 * @author wxy
 * @date 2026-09-29
 */
class SkillSeedScriptTest {

    /**
     * 建库脚本相对模块目录的位置。
     */
    private static final Path SCRIPT_PATH = Path.of("..", "sql", "career_assistant.sql");

    /**
     * 验证建库脚本包含 F7 的两张业务表、Quartz 托管表与计划正文口径技能。
     *
     * <p>固定四条契约：计划正文落 MySQL（`training_plan` + `training_reminder`，没有任务表）、
     * Quartz 表一次建全且脚本可重复执行（用 IF NOT EXISTS，不带 DROP）、技能写入幂等、
     * 计划 Agent 靠 get_weak_points 读 MySQL 而不是记忆库。
     *
     * @throws Exception 读取脚本失败
     */
    @Test
    void shouldSeedTrainingPlanTablesAndSkill() throws Exception {
        String script = Files.readString(SCRIPT_PATH, StandardCharsets.UTF_8);

        assertThat(script).contains("CREATE TABLE IF NOT EXISTS `training_plan`");
        assertThat(script).contains("CREATE TABLE IF NOT EXISTS `training_reminder`");
        // 没有训练任务表：计划就是一份正文（起止日期在计划表里），提醒 Agent 读正文推断今天干什么。
        assertThat(script).doesNotContain("CREATE TABLE IF NOT EXISTS `training_task`");
        assertThat(script).contains("`plan_content`");
        // 提醒幂等靠唯一键承载：同一天同一用户只保留一条。
        assertThat(script).contains("uk_training_reminder_user_date");
        // 计划页的未读角标按 (user_id, read_flag) 统计。
        assertThat(script).contains("idx_training_reminder_user_read");
        // 三张表都要带项目公共字段：Mapper 的审计填充与逻辑删除都依赖它们。
        for (String table : new String[]{"training_plan", "training_reminder"}) {
            int start = script.indexOf("CREATE TABLE IF NOT EXISTS `" + table + "`");
            assertThat(start).isGreaterThan(0);
            String tableDdl = script.substring(start, script.indexOf(") ENGINE", start));
            assertThat(tableDdl).contains(
                    "`create_time`", "`create_by`", "`update_time`", "`update_by`", "`is_delete`");
        }

        // Quartz 托管表一次建全 11 张，供会话归档总结复用；脚本可重复执行，不带 DROP。
        for (String table : new String[]{
                "QRTZ_JOB_DETAILS", "QRTZ_TRIGGERS", "QRTZ_SIMPLE_TRIGGERS", "QRTZ_CRON_TRIGGERS",
                "QRTZ_SIMPROP_TRIGGERS", "QRTZ_BLOB_TRIGGERS", "QRTZ_CALENDARS",
                "QRTZ_PAUSED_TRIGGER_GRPS", "QRTZ_FIRED_TRIGGERS", "QRTZ_SCHEDULER_STATE", "QRTZ_LOCKS"}) {
            assertThat(script).contains("CREATE TABLE IF NOT EXISTS `" + table + "`");
        }
        assertThat(script).doesNotContain("DROP TABLE IF EXISTS QRTZ_");

        // 排期口径走 MySQL 技能仓库，幂等写入且以代码为准。
        assertThat(script).contains("'training-planning'");
        assertThat(script).contains("'f7-training-planning'");
    }

    /**
     * 验证建库脚本包含幂等的 resume-analysis 技能与框架要求的技能表。
     *
     * @throws Exception 读取脚本失败
     */
    @Test
    void shouldSeedResumeAnalysisSkillIdempotently() throws Exception {
        String script = Files.readString(SCRIPT_PATH, StandardCharsets.UTF_8);

        assertThat(script).contains("CREATE TABLE IF NOT EXISTS `agentscope_skills`");
        assertThat(script).contains("CREATE TABLE IF NOT EXISTS `agentscope_skill_resources`");
        assertThat(script).contains("INSERT INTO `agentscope_skills`");
        assertThat(script).contains("'resume-analysis'");
        // 幂等写法：重复执行不产生重复数据（命中 name 唯一键时只做同值更新，不覆盖人工调整）。
        assertThat(script).contains("ON DUPLICATE KEY UPDATE");

        String skillSection = script.substring(
                script.indexOf("INSERT INTO `agentscope_skills`"),
                script.indexOf("ON DUPLICATE KEY UPDATE"));
        assertThat(skillSection).contains("诊断维度").contains("评分口径").contains("输出结构");
        assertThat(skillSection).contains("submit_resume_diagnosis");
        assertThat(skillSection.length()).isGreaterThan(500);
    }

    /**
     * 验证建库脚本包含 F5 的面试问答表与两个幂等技能。
     *
     * <p>真实取数同样需要 MySQL，因此这里只固定脚本契约：面试问答表带账号与会话索引、两个技能都写成幂等插入、
     * 内容覆盖三类题型配比 / 难度阶梯 / 追问换题（含答错记错题）与三档判定。
     *
     * @throws Exception 读取脚本失败
     */
    @Test
    void shouldSeedInterviewSkillsIdempotently() throws Exception {
        String script = Files.readString(SCRIPT_PATH, StandardCharsets.UTF_8);

        assertThat(script).contains("CREATE TABLE IF NOT EXISTS `interview_qa`");
        assertThat(script).contains("idx_interview_qa_user_session");
        assertThat(script).contains("'interview-questioning'");
        assertThat(script).contains("'answer-evaluation'");
        // 面试结果的逐题明细与标准答案存在这一列，技能也要求评分子 Agent 给出 referenceAnswer。
        assertThat(script).contains("evaluation_json");
        assertThat(script).contains("referenceAnswer");

        int questioning = script.indexOf("'interview-questioning'");
        String questioningSection = script.substring(
                questioning, script.indexOf("ON DUPLICATE KEY UPDATE", questioning));
        assertThat(questioningSection).contains("4:2:2").contains("难度阶梯")
                .contains("追问").contains("换题").contains("错题");
        assertThat(questioningSection.length()).isGreaterThan(500);

        int evaluation = script.indexOf("'answer-evaluation'");
        String evaluationSection = script.substring(
                evaluation, script.indexOf("ON DUPLICATE KEY UPDATE", evaluation));
        assertThat(evaluationSection).contains("CORRECT").contains("PARTIAL").contains("WRONG")
                .contains("掌握度");
        assertThat(evaluationSection.length()).isGreaterThan(500);
    }

    /**
     * 验证建库脚本幂等追加了 F3 的 job-match 技能，并且没有重复建表。
     *
     * <p>F3 复用 F2 建好的技能表，脚本里只允许出现一行技能写入：表结构重复建会掩盖框架托管表的
     * 真实结构，写入不幂等则每次执行都会多出一条规则。
     *
     * @throws Exception 读取脚本失败
     */
    @Test
    void shouldSeedJobMatchSkillIdempotently() throws Exception {
        String script = Files.readString(SCRIPT_PATH, StandardCharsets.UTF_8);

        assertThat(script).contains("'job-match'");
        assertThat(script).contains("'f3-job-match'");

        // F3 段从自己的分隔注释起，到本段的幂等收尾止：段内只写技能行，不重复建表。
        int sectionStart = script.indexOf("F3 岗位匹配");
        // F6 起脚本末尾会继续追加后续模块的段，这里把范围收在 F3 段内，避免把 F6 的建表算进来。
        int nextSectionStart = script.indexOf("F6 面试点评与报告");
        int sectionEnd = nextSectionStart > sectionStart
                ? nextSectionStart : script.lastIndexOf("ON DUPLICATE KEY UPDATE");
        assertThat(sectionStart).isGreaterThan(0);
        assertThat(sectionEnd).isGreaterThan(sectionStart);

        String jobMatchSection = script.substring(sectionStart, sectionEnd);
        assertThat(jobMatchSection).doesNotContain("CREATE TABLE");
        assertThat(jobMatchSection)
                .contains("匹配维度")
                .contains("命中关键词")
                .contains("缺失关键词")
                .contains("匹配度")
                .contains("输出结构")
                .contains("底线")
                // 差距补齐建议最容易滑向「替用户编数字」，这条底线必须写进技能正文。
                .contains("如果你确实做过");
        assertThat(jobMatchSection.length()).isGreaterThan(500);
    }

    /**
     * 验证建库脚本幂等追加了 F6 的两张表与两个技能，并且逐题点评不建独立表。
     *
     * <p>真实取数需要 MySQL，因此这里只固定脚本契约：掌握度表与报告表带账号维度索引、
     * 两个技能写成幂等插入、掌握度口径的具体数值写进技能正文，且脚本里没有 `interview_evaluation` 表
     * （逐题点评的权威数据仍是 `interview_qa.evaluation_json`）。
     *
     * @throws Exception 读取脚本失败
     */
    @Test
    void shouldSeedInterviewReportTablesAndSkillsIdempotently() throws Exception {
        String script = Files.readString(SCRIPT_PATH, StandardCharsets.UTF_8);

        assertThat(script).contains("CREATE TABLE IF NOT EXISTS `knowledge_mastery`");
        assertThat(script).contains("uk_knowledge_mastery_user_point");
        assertThat(script).contains("CREATE TABLE IF NOT EXISTS `interview_report`");
        assertThat(script).contains("uk_interview_report_user_session");
        assertThat(script).doesNotContain("CREATE TABLE IF NOT EXISTS `interview_evaluation`");

        int sectionStart = script.indexOf("F6 面试点评与报告");
        assertThat(sectionStart).isGreaterThan(0);

        int mastery = script.indexOf("'mastery-evaluation'");
        String masterySection = script.substring(mastery, script.indexOf("ON DUPLICATE KEY UPDATE", mastery));
        assertThat(masterySection)
                .contains("90 天")
                .contains("半衰期")
                .contains("中性先验")
                .contains("薄弱");

        int report = script.indexOf("'interview-report'");
        String reportSection = script.substring(report, script.indexOf("ON DUPLICATE KEY UPDATE", report));
        assertThat(reportSection)
                .contains("面试总结")
                .contains("亮点")
                .contains("下一步建议")
                .contains("submit_interview_report");
        assertThat(reportSection.length()).isGreaterThan(500);
    }

    /**
     * 验证建库脚本幂等追加了 F9 的讲解规范技能，且 F9 段只写技能行。
     *
     * <p>真实取数同样需要 MySQL，因此这里只固定脚本契约：技能名与助手装配里的 tutoring 一致、
     * 用幂等写法写入、正文覆盖「先定位 / 再类比 / 再举例 / 一道练习 / 怎么算答得好」与跨会话「接着讲」，
     * 且段内不重复建技能表（表由 F2 段建立，重复建会掩盖框架托管表的真实结构）。
     *
     * @throws Exception 读取脚本失败
     */
    @Test
    void shouldSeedTutoringSkillIdempotently() throws Exception {
        String script = Files.readString(SCRIPT_PATH, StandardCharsets.UTF_8);

        assertThat(script).contains("'tutoring'");
        assertThat(script).contains("'f9-tutoring'");

        // F9 是独立分隔段：截到下一个模块分隔段（F7）或脚本结尾，就是本模块的全部改动范围。
        int sectionStart = script.indexOf("F9 专项辅导");
        assertThat(sectionStart).isGreaterThan(0);
        int nextSection = script.indexOf("F7 训练计划", sectionStart);
        String tutoringSection = nextSection > sectionStart
                ? script.substring(sectionStart, nextSection)
                : script.substring(sectionStart);

        assertThat(tutoringSection).doesNotContain("CREATE TABLE");
        assertThat(tutoringSection)
                .contains("先定位")
                .contains("再类比")
                .contains("再举例")
                .contains("一道练习")
                .contains("怎么算答得好")
                .contains("接着讲")
                .contains("ON DUPLICATE KEY UPDATE");
        assertThat(tutoringSection.length()).isGreaterThan(500);
    }

    /**
     * 验证建库脚本带上了会话归档标记三列，且存量环境同步可以重复执行。
     *
     * <p>归档状态是「记忆写入的唯一路径」的进度记录：没有这三列，定时任务每次都会重复归档同一场会话。
     * 新建库由 chat_session 建表语句带出；存量库用 information_schema 判断后再 ALTER，
     * 保证整个脚本重复执行不会因「列已存在」报错。
     *
     * @throws Exception 读取脚本失败
     */
    @Test
    void shouldSeedSessionArchiveColumnsIdempotently() throws Exception {
        String script = Files.readString(SCRIPT_PATH, StandardCharsets.UTF_8);

        int start = script.indexOf("CREATE TABLE IF NOT EXISTS `chat_session`");
        assertThat(start).isGreaterThan(0);
        String tableDdl = script.substring(start, script.indexOf(") ENGINE", start));
        assertThat(tableDdl).contains("`archive_status`", "`archive_time`", "`archive_attempts`");
        assertThat(tableDdl).contains("PENDING").contains("DONE").contains("FAILED");

        // 存量环境同步：先查 information_schema，再决定是否 ALTER。
        assertThat(script).contains("information_schema.COLUMNS");
        assertThat(script).contains("PREPARE archive_column_stmt");
        assertThat(script).contains("DEALLOCATE PREPARE archive_column_stmt");
    }
}
