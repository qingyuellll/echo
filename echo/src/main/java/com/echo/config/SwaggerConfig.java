package com.echo.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;


@Slf4j
@Configuration
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true", matchIfMissing = true)
public class SwaggerConfig {

    private static final String DESCRIPTION_FILE_PATH = "swagger/api-description.html";

    @Bean
    public OpenAPI customOpenAPI() {
        String description = loadDescription();

        return new OpenAPI()
                .info(new Info()
                        .title("Echo 票务平台 API 文档")
                        .description(description)
                        .version("2.0 (Strict Mode)")
                        .contact(new Contact()
                                .name("Echo 开发团队")
                                .url("https://github.com/your-repo")
                                .email("dev@echo.com"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0"))
                );
    }

    private String loadDescription() {
        try {
            ClassPathResource resource = new ClassPathResource(DESCRIPTION_FILE_PATH);
            if (resource.exists()) {
                byte[] bytes = resource.getInputStream().readAllBytes();
                return new String(bytes, StandardCharsets.UTF_8);
            } else {
                log.warn("[Swagger] 描述文件未找到: {}，使用默认描述", DESCRIPTION_FILE_PATH);
                return getDefaultDescription();
            }
        } catch (IOException e) {
            log.error("[Swagger] 读取描述文件失败: {}", e.getMessage(), e);
            return getDefaultDescription();
        }
    }

    private String getDefaultDescription() {
        return """
            <h3>Echo 票务平台 API 文档</h3>
            <p>详细字段规范请参考 <code>resources/swagger/api-description.html</code></p>
            <p><b>提示：</b>如需更新文档内容，请编辑上述 HTML 文件，无需重启服务（热部署环境）。</p>
            """;
    }
}
