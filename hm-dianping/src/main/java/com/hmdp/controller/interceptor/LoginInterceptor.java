package com.hmdp.controller.interceptor;

import com.hmdp.entity.User;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

@Component
public class LoginInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        HttpSession session = request.getSession();
        // 1. 从session获取对象
        User user = (User) session.getAttribute("user");
        // 2. 判断用户是否存在
        if (user == null) {
            // 2.1 不存在 拦截
            response.setStatus(401);  // 返回401状态码（未授权）
            return  false;
        }
        // 2.2 存在 将用户保存到ThreadLocal中 放行
        UserHolder.saveUser(user);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 请求完成，将user信息从ThreadLocal中移除，避免内存溢出和信息泄露的问题
        UserHolder.removeUser();
    }
}
