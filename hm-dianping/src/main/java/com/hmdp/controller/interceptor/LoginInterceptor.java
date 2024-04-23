package com.hmdp.controller.interceptor;

import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Component
public class LoginInterceptor implements HandlerInterceptor {

    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * redis实现手机登录的代码
     * 1. 从请求头获取token
     * 2. 根据token去redis中查询用户数据user
     * 3. 判断用户是否存在
     *  3.1 如果user不存在 --> 拦截
     *  3.2 如果user存在 -->
     *          将用户保存到ThreadLocal中
     *          刷新token的有效期
     *          放行
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        UserDTO userDto = UserHolder.getUser();
        if (userDto == null) {
            response.setStatus(401);
            return false;
        }
        return true;
    }
}
