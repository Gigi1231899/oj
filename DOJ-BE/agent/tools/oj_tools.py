"""
OJ 系统工具集 — 通过 HTTP 调用 Spring Boot Gateway 的 REST API。

所有请求携带用户的 JWT access token（由前端传入），以用户身份操作。
Agent 通过 ReAct 循环自动决定何时调用这些工具。
"""

import httpx


class OJTools:
    """OJ API 客户端，封装三个核心工具"""

    def __init__(self, gateway_url: str, access_token: str):
        self.base = gateway_url.rstrip("/")
        # 异步 HTTP 客户端，所有请求自动带 Authorization header
        self.client = httpx.AsyncClient(
            headers={"Authorization": access_token},
            timeout=30,
        )

    async def get_problem(self, problem_id: int):
        """
        查询题目详情。

        参数: problem_id — 题目 ID
        返回: 题目名称、描述（截取前500字）、输入输出格式、难度、时间/内存限制
        API:  GET /problem/{id}
        """
        r = await self.client.get(f"{self.base}/problem/{problem_id}")
        r.raise_for_status()
        data = r.json()
        if data.get("code") != 200:
            return f"查询题目失败: {data.get('msg', '未知错误')}"
        p = data["data"]
        return (
            f"题目 #{p['id']} {p['name']} ({p['difficulty']})\n"
            f"描述: {p['description'][:500]}\n"
            f"输入: {p['inputStyle']}\n"
            f"输出: {p['outputStyle']}\n"
            f"时间限制: {p['timeLimit']} ms, 内存限制: {p['memoryLimit']} MB"
        )

    async def get_submission(self, submission_id: int):
        """
        查询提交详情（含代码和判题结果）。

        参数: submission_id — 提交记录 ID
        返回: 状态、耗时、内存、代码、错误信息
        API:  GET /submission/page?submissionId={id}
        """
        r = await self.client.get(
            f"{self.base}/submission/page",
            params={"submissionId": submission_id},
        )
        r.raise_for_status()
        data = r.json()
        if data.get("code") != 200:
            return f"查询提交失败: {data.get('msg', '未知错误')}"
        page = data["data"]
        if not page or not page.get("list"):
            return f"未找到提交 #{submission_id}"
        s = page["list"][0]

        t = s.get('time')
        m = s.get('memory')
        code = s.get('code') or '(未提供代码)'
        if len(code) > 2000:
            code = code[:2000] + "\n... (代码已截断)"

        # 耗时行：null 时不展示（Checker 模式 / CE 等无耗时数据）
        time_line = f"耗时: {t * 1000:.2f} ms\n" if t is not None and t > 0 else ""
        mem_line = f"内存: {m:.2f} KB\n" if m is not None and m > 0 else ""

        return (
            f"提交 #{s['id']}\n"
            f"题目: {s.get('problemName', '未知')} (ID: {s.get('problemId', '?')})\n"
            f"语言: {s.get('language', '未知')}\n"
            f"状态: {s.get('status', '未知')}\n"
            f"{time_line}"
            f"{mem_line}"
            f"信息: {s.get('message', '(空)')}\n"
            f"代码:\n```\n{code}\n```"
        )

    async def submit_and_judge(self, language: str, code: str, problem_id: int):
        """
        提交代码到判题系统并等待结果返回。

        参数:
            language   — 编程语言（cpp / java / python）
            code       — 完整源代码
            problem_id — 题目 ID

        返回: 判题结果（状态、耗时、内存、错误信息）

        实现细节:
            1. POST /sandbox/validate 提交代码 → 获取 submission_id
            2. 轮询 GET /submission/page?submissionId=X（每秒一次，最多15次）
            3. 检测到最终状态（AC/WA/TLE/MLE/RE/CE）后返回结果
        """
        import asyncio

        # Step 1: 提交代码（multipart/form-data）
        files = {"file": ("Main." + self._ext(language), code.encode(), "text/plain")}
        form = {"language": language, "pid": str(problem_id)}
        r = await self.client.post(f"{self.base}/sandbox/validate", files=files, data=form)
        r.raise_for_status()
        data = r.json()
        if data.get("code") != 200:
            return f"提交失败: {data.get('msg', '未知错误')}"
        submission_id = data["data"]

        # Step 2: 轮询等待判题完成（最多 15 秒）
        for _ in range(15):
            await asyncio.sleep(1)
            result = await self.get_submission(submission_id)
            # 检测到最终状态则立即返回
            if any(kw in result for kw in ["Wrong Answer", "Accepted", "Exceeded", "Error"]):
                return result

        # 超时兜底：返回最新状态（可能仍在 PENDING）
        return await self.get_submission(submission_id)

    def _ext(self, lang: str) -> str:
        """语言名 → 文件扩展名"""
        return {"cpp": "cpp", "java": "java", "python": "py"}.get(lang, "txt")

    async def close(self):
        """关闭 HTTP 客户端连接"""
        await self.client.aclose()
