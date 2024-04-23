package com.hmdp.config;

import com.hmdp.controller.interceptor.LoginInterceptor;
import com.hmdp.controller.interceptor.RefreshTokenInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Autowired
    private LoginInterceptor loginInterceptor;

    @Autowired
    private RefreshTokenInterceptor refreshTokenInterceptor;

    /**
     * 添加、配置拦截器
     * order表示拦截器的访问次序 order越小 越先访问
     * @param registry
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 为后面测试方便，先将所有类都进行排除
        registry.addInterceptor(loginInterceptor)
                .excludePathPatterns(
                        "/user/code",
                        "/user/login",
                        "/blog/hot",
                        "/shop/**",
                        "/shop-type/**",
                        "/voucher/**",
                        "/upload/**"
                ).order(1);
        registry.addInterceptor(refreshTokenInterceptor).order(0);
    }
}
