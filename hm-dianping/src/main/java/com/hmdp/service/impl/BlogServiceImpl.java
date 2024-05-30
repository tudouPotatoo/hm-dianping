package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;


@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
            queryBlogUser(blog);
            queryBlogLiked(blog);
        });
        return Result.ok(records);
    }

    /**
     * 查看当前用户对帖子的点赞状态
     * 如果已点赞，则blog对象的isLike值为true
     * 如果未点赞，则blog对象的isLike值为false
     * @param blog
     */
    private void queryBlogLiked(Blog blog) {
        // 1. 查询当前用户信息
        UserDTO user = UserHolder.getUser();
        // 如果用户未登录 则无需显示点赞状态
        if (user == null) {
            return;
        }
        Long userId = user.getId();
        // 2. 查询该用户是否已经点过赞
        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = redisTemplate.opsForZSet().score(key, userId.toString());
        // 3. 设置blog的isLiked属性值
        blog.setIsLike(score != null);
    }

    /**
     * 使用zset来存储点赞用户id
     * 给帖子点赞
     * 1. 查询当前用户信息
     * 2. 查询该用户是否已经点过赞
     * 3. 已经点过赞
     *      3.1 点赞数量-1
     *      3.2 将用户id从zset中移除
     * 4. 未点过赞
     *      4.1 点赞数量+1
     *      4.2 将用户id加入zset
     * @param id blog的id
     * @return
     */
    @Override
    public Result likeBlog(Long id) {
        // 1. 查询当前用户信息
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();
        // 2. 查询该用户是否已经点过赞
        String key = BLOG_LIKED_KEY + id;
        Double score = redisTemplate.opsForZSet().score(key, userId.toString());
        if (score != null) {
            // 3. 已经点过赞
            // 3.1 点赞数量-1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            // 3.2 将用户id从set中移除
            if (isSuccess) {
                redisTemplate.opsForZSet().remove(key, userId.toString());
            }
        } else {
            // 4. 未点过赞
            // 4.1 点赞数量+1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            // 4.2 将用户id加入set
            if (isSuccess) {
                redisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        }
        return Result.ok();
    }

    // /**
    //  *（Deprecated）使用set来存储点赞用户id
    //  * 给帖子点赞
    //  * 1. 查询当前用户信息
    //  * 2. 查询该用户是否已经点过赞
    //  * 3. 已经点过赞
    //  *      3.1 点赞数量-1
    //  *      3.2 将用户id从set中移除
    //  * 4. 未点过赞
    //  *      4.1 点赞数量+1
    //  *      4.2 将用户id加入set
    //  * @param id blog的id
    //  * @return
    //  */
    // @Override
    // public Result likeBlog(Long id) {
    //     // 1. 查询当前用户信息
    //     UserDTO user = UserHolder.getUser();
    //     Long userId = user.getId();
    //     // 2. 查询该用户是否已经点过赞
    //     String key = BLOG_LIKED_KEY + id;
    //     Boolean liked = redisTemplate.opsForSet().isMember(key, userId.toString());
    //     if (BooleanUtil.isTrue(liked)) {
    //         // 3. 已经点过赞
    //         // 3.1 点赞数量-1
    //         boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
    //         // 3.2 将用户id从set中移除
    //         if (isSuccess) {
    //             redisTemplate.opsForSet().remove(key, userId.toString());
    //         }
    //     } else {
    //         // 4. 未点过赞
    //         // 4.1 点赞数量+1
    //         boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
    //         // 4.2 将用户id加入set
    //         if (isSuccess) {
    //             redisTemplate.opsForSet().add(key, userId.toString());
    //         }
    //     }
    //     return Result.ok();
    // }

    /**
     * 获取点赞排行榜
     * 获取前五个点赞的用户
     * 1. 拼接key（存储帖子点赞的用户）
     * 2. 查询前五个点赞的用户的id
     * 3. 根据id查询用户，并将用户从User类型转化为UserDTO类型
     * 4. 返回
     * @param id
     * @return
     */
    @Override
    public Result queryBlogLikes(Long id) {
        // 1. 拼接key（存储帖子点赞的用户）
        String key = BLOG_LIKED_KEY + id;
        //2. 查询前五个点赞的用户的id ZRANGE key 0 4
        Set<String> idStrs = redisTemplate.opsForZSet().range(key, 0, 4);
        List<Long> ids = idStrs.stream().map(Long::valueOf).collect(Collectors.toList());

        // 3. 根据id查询用户，并将用户从User类型转化为UserDTO类型
        String top5 = StrUtil.join(",", ids);
        // select * from user where id in (id1, id2, id3) order by field(id, id1, id2, id3)
        // 如果是select * from user where id in (id1, id2, id3) 那id的顺序不一定是id1, id2, id3，而是按照id在数据库表中的顺序
        // 想要规定结果id的顺序必须是id1 -> id2 -> id3 就可以在后面添加order by field(id, id1, id2, id3)，来规定其输出的结果顺序
        List<UserDTO> userDTOS = userService.query()
                .in("id", ids)
                .last("ORDER BY FIELD(id," + top5 + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        // 4. 返回
        return Result.ok(userDTOS);
    }

    @Override
    public Result queryBlogById(Long id) {
        // 1. 查询blog信息
        Blog blog = getById(id);
        // 2. 查询blog对应的用户信息
        queryBlogUser(blog);
        // 3. 查询当前用户对blog的点赞状态 并设置blog的isLike属性
        queryBlogLiked(blog);
        return Result.ok(blog);
    }

    /**
     * 查询博客的作者信息
     * @param blog
     */
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
