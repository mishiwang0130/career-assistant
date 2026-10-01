package com.wxy.career;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.quartz.QuartzAutoConfiguration;

/**
 * 启动类。
 *
 * <p>扫描范围：{@code com.wxy.career}，业务代码按 Controller、Service、Mapper 等层级分包。
 *
 * <p>显式排除 Spring Boot 的 Quartz 自动配置：F7 引入 quartz 依赖后，类路径上凑齐了自动配置的触发条件，
 * 它会注册一个名为 {@code quartzScheduler} 的 bean，与本项目手工装配的调度器同名冲突（默认不允许覆盖，
 * 启动直接失败）。本项目的调度器统一由 {@code TrainingQuartzConfiguration} 装配，因此这里排除自动配置。
 *
 * @author wxy
 * @date 2026-09-27
 */
@SpringBootApplication(exclude = QuartzAutoConfiguration.class)
public class CareerAssistantApplication {

    /**
     * 启动 Spring Boot 应用。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(CareerAssistantApplication.class, args);
    }
}
