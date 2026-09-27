package com.wxy.career;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 启动类。
 *
 * <p>扫描范围：{@code com.wxy.career}，业务代码按 Controller、Service、Mapper 等层级分包。
 *
 * @author wxy
 * @date 2026-09-27
 */
@SpringBootApplication
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
