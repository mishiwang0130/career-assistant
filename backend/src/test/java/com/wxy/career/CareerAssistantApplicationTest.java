package com.wxy.career;

import com.wxy.career.config.TrainingQuartzConfiguration;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.quartz.QuartzAutoConfiguration;
import org.springframework.context.annotation.Bean;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 启动类装配约定测试。
 *
 * <p>固定一条踩过的坑：F7 引入 quartz 依赖后，Spring Boot 的 {@code QuartzAutoConfiguration} 会被触发，
 * 它注册的 bean 名与本项目手工装配的调度器同为 {@code quartzScheduler}，默认不允许覆盖会导致应用启动失败
 * （报错形如「A bean with that name has already been defined ... overriding is disabled」）。
 * 因此启动类必须显式排除该自动配置，调度器只由 {@code TrainingQuartzConfiguration} 提供。
 *
 * @author wxy
 * @date 2026-10-01
 */
class CareerAssistantApplicationTest {

    /**
     * 验证启动类排除了 Quartz 自动配置。
     */
    @Test
    void shouldExcludeQuartzAutoConfiguration() {
        SpringBootApplication annotation =
                CareerAssistantApplication.class.getAnnotation(SpringBootApplication.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.exclude()).contains(QuartzAutoConfiguration.class);
    }

    /**
     * 验证调度器由本项目的配置类提供，且 bean 名就是 {@code quartzScheduler}——这正是需要排除自动配置的原因。
     */
    @Test
    void shouldProvideSchedulerBeanFromTrainingConfiguration() {
        boolean provided = Arrays.stream(TrainingQuartzConfiguration.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Bean.class))
                .anyMatch(this::isQuartzSchedulerBean);

        assertThat(provided).isTrue();
    }

    /**
     * 判断某个方法是否就是本项目声明的 Quartz 调度器 bean。
     *
     * @param method 配置类上的方法
     * @return true 表示该方法声明了 {@code quartzScheduler}
     */
    private boolean isQuartzSchedulerBean(Method method) {
        return "quartzScheduler".equals(method.getName())
                && Scheduler.class.equals(method.getReturnType());
    }
}
