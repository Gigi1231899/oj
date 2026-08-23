import { ElNotification } from 'element-plus';
import router from '@/router';
import { h } from 'vue';

let socket: WebSocket | null = null;
let activeCallback: ((result: any) => void) | null = null;
// 记录所有正在等待判题结果的 submissionId，用于断线后重连时重新订阅
let pendingSubscriptions = new Set<number>();
let reconnectTimer: number | null = null;
let reconnectAttempts = 0;

const renderResultMessage = (result: any) => {
    const isNormal = result.status === 'Accepted' || result.status === 'Wrong Answer';
    const time = result.time != null && result.time >= 0 ? `${(result.time * 1000).toFixed(2)} ms` : null;
    const memory = result.memory != null && result.memory >= 0 ? `${result.memory.toFixed(2)} KB` : null;

    const children = [
        h('p', { style: { margin: '0 0 5px 0' } }, [
            h('strong', null, '状态: '),
            h('span', { style: { fontWeight: 'bold', color: result.status === 'Accepted' ? '#67c23a' : '#f56c6c' } }, ` ${result.status}`)
        ]),
    ];
    if (isNormal && time) {
        children.push(h('p', { style: { margin: '0 0 5px 0' } }, [
            h('strong', null, '耗时: '),
            h('span', null, time)
        ]));
    }
    if (isNormal && memory) {
        children.push(h('p', { style: { margin: '0 0 5px 0' } }, [
            h('strong', null, '内存: '),
            h('span', null, memory)
        ]));
    }
    if (result.message && result.status !== 'Accepted') {
        children.push(h('div', null, [
            h('strong', null, '信息: '),
            h('pre', { style: { marginTop: '5px', padding: '5px', background: '#f5f5f5', border: '1px solid #e3e3e3', borderRadius: '4px', whiteSpace: 'pre-wrap', wordBreak: 'break-all' } }, result.message)
        ]));
    }
    return h('div', null, children);
};

const connectWebSocket = () => {
    if (socket && socket.readyState === WebSocket.OPEN) {
        return;
    }
    if (socket && socket.readyState === WebSocket.CONNECTING) {
        return;
    }

    const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws';
    const wsUrl = `${protocol}://${window.location.host}/ws/submission`;

    socket = new WebSocket(wsUrl);

    socket.onopen = () => {
        console.log('WebSocket 连接已建立。');
        reconnectAttempts = 0;
        // 重连成功后，重新订阅所有还在等待结果的 submissionId
        pendingSubscriptions.forEach((id) => {
            socket?.send(JSON.stringify({ submissionId: id }));
            console.log(`重连后重新订阅提交 ID: ${id}`);
        });
    };

    socket.onmessage = (event) => {
        try {
            const result = JSON.parse(event.data);
            console.log('收到判题结果:', result);

            // 结果已到，从等待集合中移除（后端推送的是 Submission 对象，id 即 submissionId）
            if (result.id != null) {
                pendingSubscriptions.delete(Number(result.id));
            }

            if (activeCallback) {
                activeCallback(result);
            } else {
                ElNotification({
                    title: `提交 #${result.id} 已完成`,
                    message: renderResultMessage(result),
                    type: result.status === 'Accepted' ? 'success' : 'error',
                    duration: 15000,
                    onClick: () => {
                        router.push(`/status?submissionId=${result.id}`);
                    },
                    position: 'bottom-right',
                });
            }

        } catch (e) {
            console.error('处理 WebSocket 消息失败:', e);
        }
    };

    socket.onclose = (event) => {
        console.log('WebSocket 连接已关闭:', event);
        socket = null;
        // 还有结果在等 → 指数退避自动重连（1s/2s/4s...封顶 10s）
        scheduleReconnect();
    };

    socket.onerror = (error) => {
        console.error('WebSocket 发生错误:', error);
    };
};

// 断线后指数退避重连；没有等待中的订阅则不重连（避免页面闲着重连）
const scheduleReconnect = () => {
    if (pendingSubscriptions.size === 0) {
        return;
    }
    if (reconnectTimer !== null) {
        return;
    }
    const delay = Math.min(1000 * Math.pow(2, reconnectAttempts), 10000);
    reconnectAttempts++;
    reconnectTimer = window.setTimeout(() => {
        reconnectTimer = null;
        console.log('WebSocket 断线重连...');
        connectWebSocket();
    }, delay);
};

const subscribeToSubmission = (submissionId: number, callback?: (result: any) => void) => {
    if (callback) {
        activeCallback = callback;
    }
    // 登记为等待中的订阅（收到结果或推送完成后由调用方决定移除时机）
    pendingSubscriptions.add(submissionId);
    const trySubscribe = () => {
        if (socket && socket.readyState === WebSocket.OPEN) {
            console.log(`订阅提交 ID: ${submissionId}`);
            socket.send(JSON.stringify({ submissionId }));
        } else if (socket && socket.readyState === WebSocket.CONNECTING) {
            console.log('WebSocket 正在连接，稍后重试订阅...');
            setTimeout(trySubscribe, 500);
        } else {
            console.log('WebSocket 未连接，正在尝试重新连接...');
            connectWebSocket();
            setTimeout(trySubscribe, 500);
        }
    };
    trySubscribe();
};

export const useWebSocket = () => {
    if (!socket) {
        connectWebSocket();
    }

    return {
        subscribeToSubmission
    };
};
