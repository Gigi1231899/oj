// 统一管理项目接口
import request from '@/utils/request';
import type { executeResponseData, sidResponseData } from './type';
import type { BaseResponseData } from '@/api/base';
import { AxiosResponse } from 'axios';

enum API {
    // 提交判题（写入测试用例后异步判题，结果走 WebSocket 推送）
    PROBLEMS_VALIDATE_URL = '/sandbox/validate'
};

// 暴露请求函数
// 说明：run（/sandbox/code、/sandbox/problem）功能已下线，仅保留提交判题
export const reqProblemValidate = (data: FormData) => request.post< executeResponseData, AxiosResponse<sidResponseData>>(API.PROBLEMS_VALIDATE_URL, data);
