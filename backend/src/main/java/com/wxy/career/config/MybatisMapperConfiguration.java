package com.wxy.career.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis Mapper 扫描配置。
 *
 * @author wxy
 * @date 2026-09-27
 */
@Configuration
@MapperScan("com.wxy.career.mapper")
public class MybatisMapperConfiguration {
}
