package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
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
        Long userId = user.getId();
        // 2. 查询该用户是否已经点过赞
        String key = BLOG_LIKED_KEY + blog.getId();
        Boolean liked = redisTemplate.opsForSet().isMember(key, userId.toString());
        // 3. 设置blog的isLiked属性值
        blog.setIsLike(liked.booleanValue());
    }

    /**
     * 给帖子点赞
     * 1. 查询当前用户信息
     * 2. 查询该用户是否已经点过赞
     * 3. 已经点过赞
     *      3.1 点赞数量-1
     *      3.2 将用户id从set中移除
     * 4. 未点过赞
     *      4.1 点赞数量+1
     *      4.2 将用户id加入set
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
        Boolean liked = redisTemplate.opsForSet().isMember(key, userId.toString());
        if (BooleanUtil.isTrue(liked)) {
            // 3. 已经点过赞
            // 3.1 点赞数量-1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            // 3.2 将用户id从set中移除
            if (isSuccess) {
                redisTemplate.opsForSet().remove(key, userId.toString());
            }
        } else {
            // 4. 未点过赞
            // 4.1 点赞数量+1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            // 4.2 将用户id加入set
            if (isSuccess) {
                redisTemplate.opsForSet().add(key, userId.toString());
            }
        }
        return Result.ok();
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
