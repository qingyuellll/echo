package com.echo.config;

import com.echo.utils.AdminRoleInterceptor;
import com.echo.utils.CsrfTokenUtil;
import com.echo.utils.LoginInterceptor;
import com.echo.utils.RefreshTokenInterceptor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.*;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Configuration
@EnableConfigurationProperties(MvConfig.MvcProperties.class)
public class MvConfig implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CsrfInterceptor csrfInterceptor;

    @Resource
    private CsrfTokenUtil csrfTokenUtil;

    @Resource
    private RefreshTokenInterceptor refreshTokenInterceptor;

    @Resource
    private MvcProperties mvcProperties;

    @Value("${cors.allowed-origins:http://localhost:5173,http://localhost:3000,http://127.0.0.1:5173}")
    private List<String> corsAllowedOrigins;

    @Value("${csrf.enabled:true}")
    private boolean csrfEnabled = true;

    /** 图片资源根目录（由配置项提供，部署环境通过 echo.upload.image-root 指定） */
    @Value("${echo.upload.image-root}")
    private String imageRoot;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = imageRoot.endsWith("/") ? imageRoot : imageRoot + "/";
        log.info("[静态资源] /ssn/imgs/** 映射到 {}", location);

        registry.addResourceHandler("/ssn/imgs/**")
                .addResourceLocations("file:" + location)
                .setCachePeriod(3600)
                .resourceChain(true);
    }

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.setPathMatcher(new org.springframework.util.AntPathMatcher());
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        List<String> fullyPublic = mvcProperties.getFullyPublic();
        List<String> authOptional = mvcProperties.getAuthOptional();
        List<String> specialLogin = mvcProperties.getSpecialLoginPaths();
        String adminLogoutPath = mvcProperties.getAdminLogoutPath();
        List<String> bootstrapPaths = mvcProperties.getBootstrapPaths();

        List<String> loginExcludes = new ArrayList<>(fullyPublic);
        loginExcludes.addAll(authOptional);
        // 引导入口不依赖登录态，授权由引导令牌完成
        loginExcludes.addAll(bootstrapPaths);

        String[] loginExcludeArr = loginExcludes.toArray(new String[0]);
        String[] refreshExcludeArr = fullyPublic.toArray(new String[0]);

        registry.addInterceptor(refreshTokenInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(refreshExcludeArr)
                .order(0);

        List<String> adminRoleExcludes = new ArrayList<>(
                Arrays.asList("/admin/login", "/admin/code", adminLogoutPath));
        adminRoleExcludes.addAll(bootstrapPaths);

        AdminRoleInterceptor adminRoleInterceptor = new AdminRoleInterceptor();
        registry.addInterceptor(adminRoleInterceptor)
                .addPathPatterns("/admin/**")
                .excludePathPatterns(adminRoleExcludes.toArray(new String[0]))
                .order(1);
        log.info("[MvcConfig] AdminRoleInterceptor 已注册 - /admin/** 强制管理员身份校验");

        registry.addInterceptor(new LoginInterceptor())
                .addPathPatterns(specialLogin.toArray(new String[0]))
                .order(2);

        registry.addInterceptor(new LoginInterceptor())
                .addPathPatterns("/**")
                .excludePathPatterns(loginExcludeArr)
                .order(3);

        if (csrfEnabled) {
            List<String> csrfExcludes = new ArrayList<>(fullyPublic);
            csrfExcludes.addAll(Arrays.asList(
                    "/user/login", "/user/code",
                    "/admin/login", "/admin/code"
            ));
            csrfExcludes.addAll(bootstrapPaths);

            registry.addInterceptor(csrfInterceptor)
                    .addPathPatterns("/**")
                    .excludePathPatterns(csrfExcludes.toArray(new String[0]))
                    .order(4);
            log.info("[MvcConfig] CSRF 防护已启用 - 接口数:{}，排除数:{}", csrfExcludes.size(), csrfExcludes.size());
        } else {
            log.info("[MvcConfig] CSRF 防护已禁用（csrf.enabled=false）");
        }
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = corsAllowedOrigins.toArray(new String[0]);
        log.info("[CORS配置] 允许的跨域来源:{}", Arrays.toString(origins));

        registry.addMapping("/ssn/imgs/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "HEAD", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }

    @Data
    @ConfigurationProperties(prefix = "echo.mvc")
    public static class MvcProperties {

        private List<String> fullyPublic = new ArrayList<>();

        private List<String> authOptional = new ArrayList<>();

        private String adminLogoutPath = "/admin/logout";

        private List<String> specialLoginPaths = new ArrayList<>();

        /**
         * 引导入口：不校验登录态、不要求管理员身份、不校验 CSRF，
         * 授权由各自的引导令牌完成（如 X-Bootstrap-Token）
         */
        private List<String> bootstrapPaths = new ArrayList<>(
                java.util.Collections.singletonList("/admin/init-password"));
    }
}
