package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IFollowService extends IService<Follow> {

    Result hasFollow(Long id);

    Result follow(Long id, Boolean toFollow);

    Result commonFollowee(Long id);
}
