package com.decade.doj.common.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 题目提交状态（跨服务传递）
 * status: 0=未开始 1=已解决 2=尝试中
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProblemStatusVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long problemId;

    private Integer status;
}
