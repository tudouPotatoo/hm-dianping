package com.hmdp.controller;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.List;

@RestController
@RequestMapping("/blog")
public class BlogController {

    @Resource
    private IBlogService blogService;

    /**
     * 发布帖子
     * @param blog
     * @return
     */
    @PostMapping
    public Result saveBlog(@RequestBody Blog blog) {
        return blogService.saveBlog(blog);
    }

    @PutMapping("/like/{id}")
    public Result likeBlog(@PathVariable("id") Long id) {
        // // 修改点赞数量
        // blogService.update()
        //         .setSql("liked = liked + 1").eq("id", id).update();

        return blogService.likeBlog(id);
    }

    @GetMapping("/of/me")
    public Result queryMyBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .eq("user_id", user.getId()).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    @GetMapping("/hot")
    public Result queryHotBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogService.queryHotBlog(current);
    }

    /**
     * 根据blog的id查询blog信息
     * @param id
     * @return
     */
    @GetMapping("/{blogId}")
    public Result queryBlogById(@PathVariable("blogId") Long id) {
        return blogService.queryBlogById(id);
    }

    /**
     * 获取点赞排行榜
     * 获取点赞的前五名用户
     * @param id
     * @return
     */
    @GetMapping("/likes/{id}")
    public Result queryBlogLikes(@PathVariable Long id) {
        return blogService.queryBlogLikes(id);
    }

    /**
     * 分页查询某用户所发布的帖子
     * @param current
     * @param id
     * @return
     */
    @GetMapping("/of/user")
    public Result queryBlogByUserId(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam("id") Long id) {
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .eq("user_id", id).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    // 滚动分页，每页查询两条数据：ZREVRANGEBYSCORE key max min [WITHSCORES] [LIMIT offset count]
    // 第一页：ZREVRANGEBYSCORE key current-timestamp 0 [WITHSCORES] [LIMIT 0 2]
    // 其它页：ZREVRANGEBYSCORE key max 0 [WITHSCORES] [LIMIT offset 2]
    /**
     * 滚动分页查询关注的人发布的帖子（类似于朋友圈功能）
     * 第一次访问该方法时，前端传入的lastId值为当前时间戳，offset不会传入，因此需要给offset设置默认值0。
     * @param max score/timestamp的最大值
     * @param offset 偏移量
     * @return
     */
    @GetMapping("/of/follow")
    public Result queryFolloweeBlogByPage(@RequestParam("lastId") Long max, @RequestParam(value = "offset", defaultValue = "0") Integer offset) {
        return blogService.queryFolloweeBlogByPage(max, offset);
    }
}
