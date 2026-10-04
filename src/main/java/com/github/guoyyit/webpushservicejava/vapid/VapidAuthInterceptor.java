package com.github.guoyyit.webpushservicejava.vapid;

import com.github.guoyyit.webpushservicejava.web.ServiceException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.HandlerInterceptor;

public class VapidAuthInterceptor implements HandlerInterceptor {

    private final VapidService vapidService;

    public VapidAuthInterceptor(VapidService vapidService) {
        this.vapidService = vapidService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || authorization.isBlank()) {
            authorization = request.getParameter("authorization");
        }
        String audience = resolveAudience(request);
        vapidService.verify(authorization, audience);
        return true;
    }

    private String resolveAudience(HttpServletRequest request) {
        String forwardedProto = request.getHeader("X-Forwarded-Proto");
        String scheme = forwardedProto != null && !forwardedProto.isBlank()
                ? forwardedProto.trim() : request.getScheme();
        String host = request.getServerName();
        int port = request.getServerPort();
        boolean defaultPort = ("http".equals(scheme) && port == 80)
                || ("https".equals(scheme) && port == 443);
        return scheme + "://" + host + (defaultPort ? "" : ":" + port);
    }
}
