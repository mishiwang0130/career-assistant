package com.wxy.career;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 启动类。
 *
 * <p>扫描范围：{@code com.wxy.career}。业务代码按领域分包写在同级目录下即可。
 */
@SpringBootApplication
public class CareerAssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(CareerAssistantApplication.class, args);
    }
}
