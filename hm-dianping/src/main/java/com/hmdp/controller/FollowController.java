package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/follow")
public class FollowController {
    @Autowired
    private IFollowService followService;

    /**
     * 查看是否关注了某个用户
     * @param id
     * @return
     */
    @GetMapping("/or/not/{id}")
    public Result hasFollow(@PathVariable Long id) {
        return followService.hasFollow(id);
    }

    /**
     * 关注/取关
     * @param id
     * @param toFollow
     * @return
     */
    @PutMapping("/{id}/{toFollow}")
    public Result follow(@PathVariable("id") Long id, @PathVariable("toFollow") Boolean toFollow) {
        return followService.follow(id, toFollow);
    }

    /**
     * 查询与用户x共同关注的用户
     * @param id 用户x的id
     * @return 共同关注的用户 List<UserDTO>
     */
    @GetMapping("/common/{id}")
    public Result commonFollowee(@PathVariable Long id) {
        return followService.commonFollowee(id);
    }
}
