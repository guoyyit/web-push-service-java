package com.github.guoyyit.webpushservicejava.config;

import com.github.guoyyit.webpushservicejava.vapid.VapidAuthInterceptor;
import com.github.guoyyit.webpushservicejava.vapid.VapidService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final VapidService vapidService;

    public WebConfig(VapidService vapidService) {
        this.vapidService = vapidService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new VapidAuthInterceptor(vapidService))
                .addPathPatterns(
                        "/api/subscriptions/*/push",
                        "/api/subscriptions/*/dispatch-tick",
                        "/api/vapid/rotate");
    }
}
