"""
DOJ 编码助手 Agent — FastAPI + ReAct Loop + SSE 流式输出.

启动: uv run uvicorn agent:app --port 8765
前端通过 Spring Boot ChatController 代理转发请求到此服务。
"""

import ast
import json
import os
import re
from string import Template
from typing import Optional

import redis.asyncio as aioredis
from redis.asyncio.sentinel import Sentinel
import uvicorn
from dotenv import load_dotenv
from fastapi import FastAPI
from openai import AsyncOpenAI
from pydantic import BaseModel
from sse_starlette.sse import EventSourceResponse

from prompt_template import react_system_prompt_template
from tools.oj_tools import OJTools

load_dotenv()


# ─── 请求模型 ─────────────────────────────────────────────────────
class ChatRequest(BaseModel):
    """前端 → Spring Boot → Python Agent 的请求体"""
    session_id: str = "default"            # 会话 ID，用于多轮对话历史
    message: str                           # 用户输入的问题
    problem_id: Optional[int] = None       # 当前题目 ID（可选，用于上下文注入）
    code: Optional[str] = None             # 用户编辑器中的代码（可选）
    access_token: str = ""                 # 用户的 JWT token，Agent 以此身份调 OJ API


# ─── Redis 多轮对话历史存储 ───────────────────────────────────────
# 设计说明：对话历史按 session_id 存储在 Redis 中，TTL 30 分钟。
# Redis 不可用时静默降级（返回空历史），不阻塞对话。
# 连接方式：与 Java 服务一致走 Sentinel 哨兵模式。K8s 中由 Helm 注入
# DOJ_REDIS_SENTINEL_NODES / DOJ_REDIS_MASTER_NAME；本地开发可设置
# 这两个环境变量指向本地哨兵（或单机 Redis 用哨兵包装，见 README）。

SESSION_TTL = 1800  # 30 分钟

SENTINEL_NODES = os.getenv("DOJ_REDIS_SENTINEL_NODES", "redis-sentinel:26379")
MASTER_NAME = os.getenv("DOJ_REDIS_MASTER_NAME", "mymaster")


async def _redis():
    """创建异步 Redis 连接（通过 Sentinel 动态发现 master，与 Java 服务一致）"""
    nodes = [tuple(n.strip().split(":")) for n in SENTINEL_NODES.split(",") if n.strip()]
    sentinel = Sentinel(nodes, socket_timeout=2, decode_responses=True)
    return sentinel.master_for(
        MASTER_NAME,
        redis_class=aioredis.Redis,
        decode_responses=True,
    )


async def load_history(session_id: str) -> list[dict]:
    """从 Redis 加载会话历史，失败时返回空列表"""
    try:
        r = await _redis()
        try:
            raw = await r.get(f"agent:session:{session_id}")
            return json.loads(raw) if raw else []
        finally:
            await r.aclose()
    except Exception:
        return []


async def save_history(session_id: str, history: list[dict]):
    """将会话历史存入 Redis，失败时静默跳过"""
    try:
        r = await _redis()
        try:
            await r.setex(
                f"agent:session:{session_id}",
                SESSION_TTL,
                json.dumps(history, ensure_ascii=False),
            )
        finally:
            await r.aclose()
    except Exception:
        pass


# ─── FastAPI 应用 ──────────────────────────────────────────────────
app = FastAPI(title="DOJ Agent")


@app.get("/health")
async def health():
    """K8s 健康检查端点"""
    return {"status": "ok"}


@app.post("/chat/stream")
async def chat_stream(req: ChatRequest):
    """
    流式对话端点（SSE）。

    事件格式（每条一行）：
      event: <type>\\n
      data: <json>\\n\\n

    事件类型：
      - text:        LLM 流式输出的文本片段（打字机效果）
      - tool_call:   Agent 决定调用工具 {tool, input}
      - tool_result: 工具执行返回结果 {tool, output}
      - final:       最终答案 {text}
      - error:       错误信息 {data}
    """
    agent = CodeAssistantAgent(
        session_id=req.session_id,
        access_token=req.access_token,
        problem_id=req.problem_id,
        user_code=req.code,
    )

    async def event_generator():
        """异步生成器：遍历 ReAct 循环的每一步，转为 SSE dict"""
        async for event in agent.run(req.message):
            yield {"event": event["type"], "data": json.dumps(event, ensure_ascii=False)}

    return EventSourceResponse(event_generator())


# ─── ReAct Agent 核心 ─────────────────────────────────────────────
class CodeAssistantAgent:
    """
    ReAct (Reasoning + Acting) Agent 实现。

    工作流程：
      1. 加载会话历史（Redis）
      2. 构建 system prompt + 注入上下文（题目ID、用户代码）
      3. 进入循环（最多 MAX_ITERATIONS 轮）：
         a. 流式调用 LLM，实时推送文本片段
         b. 检测 <final_answer> → 对话结束，回传答案
         c. 检测 <action>   → 解析工具名和参数，执行工具
         d. 将 <observation> 注入消息列表，继续下一轮推理
      4. 保存会话历史到 Redis
    """
    MAX_ITERATIONS = 8  # 最大推理步数，防止无限循环耗尽 token

    def __init__(
        self,
        session_id: str,
        access_token: str,
        problem_id: Optional[int] = None,
        user_code: Optional[str] = None,
    ):
        self.session_id = session_id
        self.access_token = access_token
        self.problem_id = problem_id
        self.user_code = user_code

        # 异步 LLM 客户端（DeepSeek API，兼容 OpenAI SDK）
        self.llm = AsyncOpenAI(
            base_url="https://api.deepseek.com/v1",
            api_key=os.getenv("DEEPSEEK_API_KEY"),
        )

        # OJ 工具集（通过 HTTP 调 Spring Boot 网关）
        self.oj = OJTools(
            gateway_url=os.getenv("DOJ_GATEWAY_URL", "http://gateway:8080"),
            access_token=access_token,
        )

    async def run(self, user_msg: str):
        """
        ReAct 主循环 — 异步生成器。

        每步 yield 一个事件 dict：
          {type: "text"|"tool_call"|"tool_result"|"final"|"error", ...}
        """
        # 1. 加载历史消息
        messages = await load_history(self.session_id)

        # 2. 注入 system prompt（首次对话时放在最前面）
        system_prompt = self._build_system_prompt()
        if messages:
            messages.insert(0, {"role": "system", "content": system_prompt})
        else:
            messages = [{"role": "system", "content": system_prompt}]

        # 3. 构建用户消息上下文（题目ID + 代码 + 问题）
        context = f"<question>{user_msg}</question>"
        if self.problem_id:
            context += f"\n当前题目 ID: {self.problem_id}"
        if self.user_code:
            context += f"\n用户当前编辑器中的代码:\n```\n{self.user_code}\n```"
        messages.append({"role": "user", "content": context})

        # 4. ReAct 循环
        for _ in range(self.MAX_ITERATIONS):
            # 4a. 流式调用 LLM，收集完整回复
            content = ""
            async for event in self._call_llm_stream(messages):
                if event["type"] == "error":
                    yield event
                    return
                if event["type"] == "text":
                    content += event["content"]
                    yield event

            if not content:
                yield {"type": "error", "data": "LLM 返回为空"}
                return

            # 4b. 将 LLM 回复加入消息历史
            messages.append({"role": "assistant", "content": content})

            # 4c. 检测 <final_answer> → 对话结束
            fa = re.search(r"<final_answer>(.*?)</final_answer>", content, re.DOTALL)
            if fa:
                yield {"type": "final", "text": fa.group(1).strip()}
                break

            # 4d. 检测 <action> → 解析工具调用
            action = re.search(r"<action>(.*?)</action>", content, re.DOTALL)
            if not action:
                yield {"type": "error", "data": "Agent 未输出有效 <action>"}
                return

            tool_name, args = self._parse_action(action.group(1))
            yield {
                "type": "tool_call",
                "tool": tool_name,
                "input": str(args)[:200],
            }

            # 4e. 执行工具并推送结果
            observation = await self._execute_tool(tool_name, args)
            yield {
                "type": "tool_result",
                "tool": tool_name,
                "output": str(observation)[:800],
            }

            # 4f. 将工具观察结果注入对话，继续下一轮推理
            messages.append({"role": "user", "content": f"<observation>{observation}</observation>"})
        else:
            # 循环耗尽（达到 MAX_ITERATIONS）→ 优雅降级
            yield {"type": "final", "text": "分析步骤过多，请简化问题后重试。"}

        # 5. 持久化历史
        await save_history(self.session_id, messages)
        await self.oj.close()

    async def _call_llm_stream(self, messages):
        """流式调用 DeepSeek API，逐 chunk yield 文本事件"""
        try:
            stream = await self.llm.chat.completions.create(
                model="deepseek-chat",
                messages=messages,
                stream=True,
            )
            async for chunk in stream:
                delta = chunk.choices[0].delta
                if delta.content:
                    yield {"type": "text", "content": delta.content}
        except Exception as e:
            yield {"type": "error", "data": f"LLM 调用失败: {e}"}

    async def _execute_tool(self, name: str, args: list) -> str:
        """根据工具名分发执行，统一异常处理"""
        try:
            if name == "get_problem":
                return await self.oj.get_problem(int(args[0]))
            elif name == "get_submission":
                return await self.oj.get_submission(int(args[0]))
            elif name == "submit_and_judge":
                return await self.oj.submit_and_judge(
                    language=str(args[0]), code=str(args[1]), problem_id=int(args[2])
                )
            return f"未知工具: {name}"
        except Exception as e:
            return f"工具执行错误: {e}"

    def _build_system_prompt(self) -> str:
        """构建 system prompt：替换工具列表、操作系统等占位符"""
        tool_list = (
            "- get_problem(problem_id: int): 查询题目详情\n"
            "- get_submission(submission_id: int): 查询提交判题结果\n"
            "- submit_and_judge(language: str, code: str, problem_id: int): 提交代码并获取判题结果"
        )
        return Template(react_system_prompt_template).substitute(
            tool_list=tool_list,
            operating_system="Linux",
            file_list="(Agent 在线模式，无本地文件)",
        )

    # ─── LLM 输出解析 ──────────────────────────────────────────────
    # 大模型输出格式：
    #   <action>tool_name("arg1", arg2)</action>
    #   <action>write_to_file("/tmp/test.txt", "a\\nb\\nc")</action>
    #
    # 下面的解析器将这种字符串转为 (tool_name, [arg1, arg2])

    def _parse_action(self, code_str: str):
        """从 action 标签内容中提取函数名和参数列表"""
        m = re.match(r"(\w+)\((.*)\)", code_str, re.DOTALL)
        if not m:
            raise ValueError(f"无法解析 action: {code_str}")
        return m.group(1), self._parse_args(m.group(2).strip())

    def _parse_args(self, s: str) -> list:
        """
        手动解析参数列表，不依赖 eval。

        支持：字符串（单引号/双引号）、数字、嵌套括号、逗号参数分隔。
        例如: '"hello", 123, ("nested",)'
        """
        args = []
        cur = ""
        in_str = False       # 是否在字符串字面量内
        q = None             # 当前字符串的引号类型 ' 或 "
        i = 0
        depth = 0            # 括号嵌套深度

        while i < len(s):
            ch = s[i]

            if not in_str:
                # 不在字符串内 → 正常解析
                if ch in ('"', "'"):
                    in_str = True
                    q = ch
                    cur += ch
                elif ch == "(":
                    depth += 1
                    cur += ch
                elif ch == ")":
                    depth -= 1
                    cur += ch
                elif ch == "," and depth == 0:
                    # 顶层逗号 → 参数分隔符
                    args.append(self._cast(cur.strip()))
                    cur = ""
                else:
                    cur += ch
            else:
                # 在字符串内 → 全部原样保留
                cur += ch
                if ch == q and (i == 0 or s[i - 1] != "\\"):
                    # 非转义的匹配引号 → 字符串结束
                    in_str = False
                    q = None
            i += 1

        if cur.strip():
            args.append(self._cast(cur.strip()))
        return args

    @staticmethod
    def _cast(s: str):
        """
        将参数字符串转为 Python 类型。
        字符串 → 去外层引号 + 处理转义
        数字/布尔 → ast.literal_eval
        其他 → 保留原字符串
        """
        s = s.strip()
        # 字符串字面量：去掉外层引号，处理常见转义
        if (s.startswith('"') and s.endswith('"')) or (s.startswith("'") and s.endswith("'")):
            inner = s[1:-1]
            for a, b in [('\\"', '"'), ("\\'", "'"), ("\\n", "\n"), ("\\t", "\t")]:
                inner = inner.replace(a, b)
            return inner
        # 数字/布尔/None 等 Python 字面量
        try:
            return ast.literal_eval(s)
        except (SyntaxError, ValueError):
            return s


# ─── 命令行入口 ───────────────────────────────────────────────────
if __name__ == "__main__":
    uvicorn.run("agent:app", host="0.0.0.0", port=8765, reload=True)
