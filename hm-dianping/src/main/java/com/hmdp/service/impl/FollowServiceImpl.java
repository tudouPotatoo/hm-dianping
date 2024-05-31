package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.USER_FOLLOWEE;

@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Autowired
    private UserServiceImpl userService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * 查看当前登陆用户是否关注了某个用户
     * 1. 获取当前登陆用户
     * 2. 判断当前登陆用户是否关注了该用户
     * 3. 返回关注结果
     * @param id 用户的id
     * @return
     */
    @Override
    public Result hasFollow(Long id) {
        // UserDTO user = UserHolder.getUser();
        // Follow followData = query().eq("user_id", user.getId()).eq("follow_user_id", id).one();
        // return Result.ok(followData != null);

        // 1. 获取当前登陆用户
        UserDTO user = UserHolder.getUser();
        // 2. 判断当前登陆用户是否关注了该用户
        String key = USER_FOLLOWEE + user.getId();
        Boolean hasFollowed = redisTemplate.opsForSet().isMember(key, id.toString());
        // 3. 返回关注结果
        return Result.ok(hasFollowed.booleanValue());
    }

    /**
     * 关注/取关
     * 1. 根据toFollow判断是关注还是取关操作
     * 1.1 关注 --> 则创建一条关注数据插入到tb_follow表
     *              将要关注的用户id加入到当前登陆用户的关注列表set中
     * 1.2 取关 --> 从tb_follow表中删除该条数据
     *              将要取关的用户id从当前登陆用户的关注列表set中移除
     * 2. 返回结果
     * @param id 用户的id
     * @param toFollow 关注/取关 true-关注; false-取关;
     * @return
     */
    @Override
    public Result follow(Long id, Boolean toFollow) {
        UserDTO user = UserHolder.getUser();
        // 1. 根据toFollow判断是关注还是取关操作
        if (toFollow) {
            // 1.1 关注 --> 则创建一条关注数据插入到tb_follow表
            Follow follow = new Follow();
            follow.setUserId(user.getId());
            follow.setFollowUserId(id);
            follow.setCreateTime(LocalDateTime.now());
            boolean isSuccess = save(follow);
            // 将要关注的用户id加入到当前登陆用户的关注列表set中
            if (isSuccess) {
                String key = USER_FOLLOWEE + user.getId();
                redisTemplate.opsForSet().add(key, id.toString());
            }
        } else {
            // 1.2 取关 --> 从tb_follow表中删除该条数据
            boolean isSuccess = remove(new QueryWrapper<Follow>().eq("user_id", user.getId()).eq("follow_user_id", id));
            // 将要取关的用户id从当前登陆用户的关注列表set中移除
            if (isSuccess) {
                String key = USER_FOLLOWEE + user.getId();
                redisTemplate.opsForSet().remove(key, id.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 查询与用户x共同关注的用户
     * 1. 获取当前用户
     * 2. 拼接当前用户关注用户set的key
     * 3. 拼接用户x关注用户set的key
     * 4. 查询交集 即为共同关注用户的id
     * 5. 根据id查询User，并将User转化为UserDTO对象
     * 6. 返回共同关注UserDTO列表
     * @param id 用户x的id
     * @return 共同关注的用户的UserDTO
     */
    @Override
    public Result commonFollowee(Long id) {
        // 1. 获取当前用户id
        Long userId = UserHolder.getUser().getId();
        // 2. 拼接当前用户关注用户set的key
        String keyA = USER_FOLLOWEE + userId;
        // 3. 拼接用户x关注用户set的key
        String keyB = USER_FOLLOWEE + id;
        // 4. 查询交集 即为共同关注用户的id
        Set<String> commonFolloweeIdSet = redisTemplate.opsForSet().intersect(keyA, keyB);
        if (commonFolloweeIdSet == null || commonFolloweeIdSet.isEmpty()) {
            // 无交集
            return Result.ok(Collections.emptyList());
        }
        List<Integer> commonFolloweeIds = commonFolloweeIdSet
                .stream()
                .map(Integer::valueOf)
                .collect(Collectors.toList());
        // 5. 根据id查询User，并将User转化为UserDTO对象
        List<UserDTO> userDTOS = userService.listByIds(commonFolloweeIds)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        // 6. 返回共同关注UserDTO列表
        return Result.ok(userDTOS);
    }
}
