package com.hmdp.controller.interceptor;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.apache.logging.log4j.util.Strings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class RefreshTokenInterceptor implements HandlerInterceptor {

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
        /**
         * session实现手机登录的代码
         */
        // HttpSession session = request.getSession();
        // // 1. 从session获取对象
        // UserDTO user = (UserDTO) session.getAttribute("user");
        // // 2. 判断用户是否存在
        // if (user == null) {
        //     // 2.1 不存在 拦截
        //     response.setStatus(401);  // 返回401状态码（未授权）
        //     return  false;
        // }
        // // 2.2 存在 将用户保存到ThreadLocal中 放行
        // UserHolder.saveUser(user);
        // return true;
        // 1. 从请求头获取token
        String token = request.getHeader("authorization");
        // 如果token为空 直接到下一个拦截器
        if (Strings.isBlank(token)) {
            return true;
        }

        // 2. 根据token去redis中查询用户数据user
        String tokenKey = RedisConstants.LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = redisTemplate.opsForHash().entries(tokenKey);

        // 3. 判断用户是否存在
        if (userMap == null || userMap.isEmpty()) {
            // 如果用户不存在 直接到下一个拦截器
            return  true;
        }

        // 3.2 如果user存在
        // 将userMap转化为UserDTO对象
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        // 将用户保存到ThreadLocal中
        UserHolder.saveUser(userDTO);
        // 刷新token的有效期 30分钟
        redisTemplate.expire(tokenKey, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

        // 放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 请求完成，将user信息从ThreadLocal中移除，避免内存溢出和信息泄露的问题
        UserHolder.removeUser();
    }
}
