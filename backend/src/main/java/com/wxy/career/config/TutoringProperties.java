package com.wxy.career.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * 专项辅导配置。
 *
 * <p>F9 只暴露一个可调参数：读薄弱点工具单次返回的条数上限。上限存在的理由是掌握度表按知识点一行累积，
 * 长期使用后条数会变多，而本项目**不启用框架的大结果卸载**（卸载要落工作区、还要靠读文件工具取回），
 * 因此由工具自己控制返回长度，避免长列表撑爆模型上下文。
 *
 * @author wxy
 * @date 2026-10-01
 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "app.tutoring")
public class TutoringProperties {

    /**
     * 单次返回的薄弱点条数上限，默认 20。
     *
     * <p>取 20 的理由：一个用户的知识点通常是个位数到十几条，20 条足够覆盖全部薄弱点；再放大只会把
     * 上下文让给低频知识点，反而稀释了模型对最该补的那几条的注意力。
     */
    @Min(value = 1, message = "薄弱点返回条数上限至少为 1")
    @Max(value = 50, message = "薄弱点返回条数上限最多为 50")
    private int maxWeakPoints = 20;
}
