package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * 发送验证码
     * 1. 校验手机号是否合法
     *    1.1 不合法 返回错误信息
     *    1.2 合法   继续往下
     * 2. 生成验证码
     * 3. 将该验证码保存到session/redis
     * 4. 将验证码返回给客户端
     * @param phone
     * @param session
     * @return
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1. 校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2. 如果不是合法手机号，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        // 3. 如果是合法手机号，生成验证码
        String code = RandomUtil.randomNumbers(6);

        // // 4. 保存验证码到session
        // session.setAttribute("code", code);

        // 4. 保存验证码到redis中
        // key为电话号码；value为对应的验证码
        ValueOperations<String, String> opsForValue = redisTemplate.opsForValue();
        // 为key设置对应的业务前缀 防止其它业务存储的key也是手机号码时 存在冲突 导致信息覆盖
        // 设置key的有效时间2mins 防止存储时间过长 对内存造成负担
        opsForValue.set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

        // 5. 发送验证码 （这里为了省事 模拟发送 实际打印到日志）
        log.debug("发送验证码成功，验证码：{}", code);

        // 返回ok
        return Result.ok();
    }

    /**
     * 用session实现收集登陆的版本
     * 用户登陆
     * 1. 校验手机号是否合法
     *    1.1 不合法 返回报错信息
     *    1.2 合法 继续往下
     * 2. 校验code是否正确
     *    2.1 从session中取出缓存的code cacheCode
     *    2.2 如果cacheCode为空 返回报错信息
     *    2.2 将session中的code与用户的code进行对比
     *    2.3 code不正确 返回报错信息
     *    2.4 code正确 继续往下
     * 3. 根据手机号查询用户信息
     *      3.1 用户信息不存在 进行注册 创建新用户 并存入数据库 继续往下
     *      3.2 用户信息存在 继续往下
     * 4. 将用户信息存储到session中
     * 5. 返回登陆成功
     * @param loginForm 登陆参数（包含电话号码+验证码/电话号码+密码 现在只处理了[电话号码+验证码]一种逻辑）
     * @param session 用于将用户信息存储到session中
     * @return
     */
    /**
     * 用redis实现收集登陆的版本
     * 用户登陆
     * 1. 校验手机号是否合法
     *    1.1 不合法 返回报错信息
     *    1.2 合法 继续往下
     * 2. 校验code是否正确
     *    2.1 根据phone从redis中取出缓存的code cacheCode
     *    2.2 如果cacheCode为空 返回报错信息
     *    2.2 将cacheCode和用户的code进行对比
     *    2.3 code不正确 返回报错信息
     *    2.4 code正确 继续往下
     * 3. 根据手机号查询用户信息
     *      3.1 用户信息不存在 进行注册 创建新用户 并存入数据库 继续往下
     *      3.2 用户信息存在 继续往下
     * 4. 将用户信息存储到redis中
     *      4.1 生成token
     *      4.2 将用户信息存储到redis中 key为token
     * 5. 将token返回给客户端
     * 6. 返回登陆成功
     * @param loginForm 登陆参数（包含电话号码+验证码/电话号码+密码 现在只处理了[电话号码+验证码]一种逻辑）
     * @param session 用于将用户信息存储到session中
     * @return
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1. 校验手机号是否合法
        if (RegexUtils.isPhoneInvalid(loginForm.getPhone())) {
            // 如果不是合法手机号，返回错误信息
            return Result.fail("手机号格式错误！");
        }

        // 2. 校验code是否正确
        // [废弃]session中存储的真正的code
        // [废弃]Object cacheCode = session.getAttribute("code");

        // 2. 校验code是否正确
        // 根据phone从redis中取出缓存的code cacheCode
        String cacheCode = redisTemplate.opsForValue().get(LOGIN_CODE_KEY + loginForm.getPhone());
        // 用户填写的code
        String code = loginForm.getCode();
        // 如果session中根本没存code 或者 用户填写错误
        if (cacheCode == null || !cacheCode.equals(code)) {
            return Result.fail("验证码错误！");
        }

        // 3. 根据手机号查询用户信息
        User user = query().eq("phone", loginForm.getPhone()).one();
        if (user == null) {
            // 4. 如果用户不存在 创建用户 用户存入数据库
            user = new User();
            user.setPhone(loginForm.getPhone());
            user.setNickName("黑马点评__" + RandomUtil.randomString(10));
            save(user);
        }

        // 4. [废弃]保存用户信息到session中
        // session.setAttribute("user", user);
        // session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));

        // 4. 将用户信息存储到redis中
        // 4.1 生成token
        String token = UUID.randomUUID().toString(true);
        String tokenKey = LOGIN_USER_KEY + token;
        // 4.2 将用户信息存储到redis中 key为token
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // 将userDTO对象转化为Map
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        // 将对象信息存储到redis中
        HashOperations<String, Object, Object> opsForHash = redisTemplate.opsForHash();
        opsForHash.putAll(tokenKey, userMap);
        // 设置过期时间
        redisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);

        // 5. 将token返回给客户端
        return Result.ok(token);
    }

    /**
     * 用户签到
     * 1. 获取用户信息
     * 2. 获取当天日期
     * 3. 拼接用户签到表key
     * 4. 获取当天是该月的第几天
     * 5. 签到
     * 6. 返回结果
     * @return
     */
    @Override
    public Result sign() {
        // 1. 获取用户信息
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();
        // 2. 获取当天日期
        LocalDateTime now = LocalDateTime.now();
        String date = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        // 3. 拼接用户签到表key
        String key = USER_SIGN_KEY + userId + date;
        // 4. 获取当天是该月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5. 签到【天数是从1开始，BitMap下标是从0开始，因此offset值为天数-1】
        redisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        // 6. 返回结果
        return Result.ok();
    }

    /**
     * 计算用户从当前时间往前，在本月的连续签到天数
     * 1. 获取用户信息
     * 2. 获取当天日期
     * 3. 拼接用户本月签到表key
     * 4. 获取当天是该月的第几天
     * 5. 获取从1号到今天的所有签到数据
     * 6. 遍历统计连续签到天数
     *    6.1 和1相与 得到对应这一天的签到情况
     *    6.2 已签到 连续签到天数+1，右移1位
     *    6.3 未签到 结束循环
     * 7. 返回连续签到天数
     * @return
     */
    @Override
    public Result signCount() {
        // 1. 获取用户信息
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();
        // 2. 获取当天日期
        LocalDateTime now = LocalDateTime.now();
        String date = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        // 3. 拼接用户签到表key
        String key = USER_SIGN_KEY + userId + date;
        // 4. 获取当天是该月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5. 获取从1号到今天的所有签到数据
        // BITFIELD key GET u[dayOfMonth] 0
        List<Long> list = redisTemplate.opsForValue()
                .bitField(key,
                        BitFieldSubCommands.create().get(
                                        BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth))
                                .valueAt(0));
        // 没有签到数据 直接返回0
        if (list == null || list.isEmpty()) {
            return Result.ok(0);
        }
        Long signVal = list.get(0);
        // 6. 遍历统计连续签到天数
        int count = 0;
        while (true) {
            // 6.1 和1相与 得到对应这一天的签到情况
            if ((signVal & 1) == 1) {
                // 6.2 已签到 连续签到天数+1，右移1位
                count++;
                signVal = signVal >>> 1;
            } else {
                // 6.3 未签到 结束循环
                break;
            }
        }
        // 7. 返回连续签到天数
        return Result.ok(count);
    }
}
