package cn.graduation.cabinet;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import static cn.graduation.cabinet.Models.*;

/** 管理操作必须携带管理员令牌；小程序不能直接调用取放商品接口。 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final AuthService auth;
    public WebConfig(AuthService auth) { this.auth=auth; }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest req,HttpServletResponse res,Object handler) {
                Principal p=auth.authenticate(req.getHeader("Authorization"));
                if(req.getRequestURI().startsWith("/api/admin/") && !p.admin())
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN,"需要管理员权限");
                req.setAttribute("principal",p);
                return true;
            }
        }).addPathPatterns("/api/**").excludePathPatterns("/api/auth/login");
    }
}
