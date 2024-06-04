package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.INBOX_KEY;


@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Autowired
    private IFollowService followService;

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

    /**
     * 发布帖子
     * 1. 获取当前用户信息
     * 2. 设置帖子的userId
     * 3. 将帖子存入数据库
     * 4. 查询当前用户的所有粉丝
     * 5. 将帖子id存入到所有粉丝的收件箱，score为当前时间
     * @param blog
     * @return
     */
    @Override
    public Result saveBlog(Blog blog) {
        // 1. 获取当前用户信息
        UserDTO user = UserHolder.getUser();
        // 2. 设置帖子的userId
        blog.setUserId(user.getId());
        // 3. 将帖子存入数据库
        boolean isSuccess = save(blog);
        if (!isSuccess) {
            return Result.fail("发布帖子失败！");
        }
        // 4. 查询当前用户的所有粉丝
        List<Long> followerUserIds = followService.query()
                .eq("follow_user_id", user.getId())
                .list()
                .stream()
                .map(follow -> follow.getUserId())
                .collect(Collectors.toList());
        for (Long followerUserId : followerUserIds) {
            String inboxKey = INBOX_KEY + followerUserId;
            // 5. 将帖子id存入到所有粉丝的收件箱，score为当前时间
            redisTemplate.opsForZSet().add(inboxKey, blog.getId().toString(), System.currentTimeMillis());
        }
        return Result.ok();
    }

    // 滚动分页，每页查询两条数据：ZREVRANGEBYSCORE key max min [WITHSCORES] [LIMIT offset count]
    // 第一页：ZREVRANGEBYSCORE key current-timestamp 0 [WITHSCORES] [LIMIT 0 2]
    // 其它页：ZREVRANGEBYSCORE key max 0 [WITHSCORES] [LIMIT offset 2]
    /**
     * 滚动分页查询关注的人发布的帖子（类似于朋友圈功能）
     * 1. 获取当前用户id
     * 2. 拼接当前用户的收件箱zset key
     * 3. 执行ZREVRANGEBYSCORE key max min [WITHSCORES] [LIMIT offset count]，分页获取2条帖子
     * 4. 获取获取到的帖子中的最小时间戳minTime
     * 5. 获取偏移量offset：获取到的帖子中时间戳=minTime的个数
     * 6. 将List<Blog>、offset、minTime封装为一个ScrollResult对象
     * 7. 返回结果
     * @param max score/timestamp的最大值
     * @param offset 偏移量
     * @return
     */
    @Override
    public Result queryFolloweeBlogByPage(Long max, Integer offset) {
        // 1. 获取当前用户id
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();
        // 2. 拼接当前用户的收件箱zset key
        String key = INBOX_KEY + userId;
        // 3. 执行ZREVRANGEBYSCORE key max min [WITHSCORES] [LIMIT offset count]，分页获取2条帖子
        Set<ZSetOperations.TypedTuple<String>> typedTuples = redisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        List<Blog> blogs = new ArrayList<>(typedTuples.size());
        Long minTime = 0L;
        offset = 1;
        // 遍历元组 元组包含value和score：value-帖子id, score-帖子时间戳
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            // 获取帖子id
            String blogId = tuple.getValue();
            // 获取帖子时间戳
            Long timestamp = Long.valueOf(tuple.getScore().longValue());
            // 根据id获取帖子
            Blog blog = getById(blogId);
            // 完善帖子的用户
            queryBlogUser(blog);
            // 完善当前用户对帖子点赞状态
            queryBlogLiked(blog);
            // 将贴子加入blogs列表中
            blogs.add(blog);
            // 更新minTime和offset
            // 5. 获取偏移量offset：获取到的帖子中时间戳=minTime的个数
            if (!minTime.equals(timestamp)) {
                offset = 1;
            } else {
                offset++;
            }
            // 4. 获取获取到的帖子中的最小时间戳minTime
            minTime = timestamp;
        }
        // 6. 将List<Blog>、offset、minTime封装为一个ScrollResult对象
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setMinTime(minTime);
        scrollResult.setOffset(offset);
        // 7. 返回结果
        return Result.ok(scrollResult);
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
