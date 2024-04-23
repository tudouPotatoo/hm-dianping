package com.hmdp.dto;

import lombok.Data;

/**
 * 用户的基本信息
 */
@Data
public class UserDTO {
    private Long id;
    private String nickName;
    private String icon;
}
