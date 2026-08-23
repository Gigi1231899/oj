import { BaseResponse } from '@/api/base'

export type executeMessage = {
    exitValue: number,
    status: string,
    message: string,
    time: number,
    memory: number,
};

export type executeResponseData = BaseResponse & {
    data: executeMessage,
};

export type sidResponseData = BaseResponse & {
    data: number,
};
