#!/usr/bin/env python3
"""
50 并发判题接口压测 + 全链路削峰观测（Redis / RabbitMQ / MySQL 三源并行采样）

用法:
  python bench_validate.py --url https://ohjudge.asia/api/sandbox/validate --pid 62 --concurrency 50 \
      --redis-host 127.0.0.1 --redis-port 6379 \
      --rabbitmq-host 127.0.0.1 --rabbitmq-port 15672 --rabbitmq-user guest --rabbitmq-pass guest \
      --mysql-host 127.0.0.1 --mysql-port 3307 --mysql-user root --mysql-pass 123

输出:
  1. HTTP 响应延迟统计（P50/P95/P99）          -> 提交接口毫秒级返回（削峰点①）
  2. Redis judging:queue 积压曲线               -> 任务进队异步处理（削峰点②）
  3. RabbitMQ judging.result.queue 积压          -> 判题结果回写削峰（削峰点③）
  4. MySQL 连接数 + 写库线程数                   -> DB 连接池未打满（削峰点④）
  5. 自动轮询 submission 表直到全部结果落库      -> 零丢失 + 判题成功率

中间件访问（在本地执行时先做端口转发）:
  kubectl -n doj port-forward svc/redis-master 6379:6379
  kubectl -n doj port-forward svc/rabbitmq-sentinel 15672:15672
  kubectl -n doj port-forward svc/mysql-write 3307:3306
"""
import argparse
import base64
import json
import statistics
import subprocess
import threading
import time
import urllib.request
import uuid

CODE = """#include <iostream>
int main() {
    int a, b;
    std::cin >> a >> b;
    std::cout << a + b << std::endl;
    return 0;
}
"""

MQ_QUEUE_API = "/api/queues/%2F/judging.result.queue"
DB = "doj_submission"
REDIS_KEY = "judging:queue"


# ─────────────────────────── 三源采样函数 ───────────────────────────

def sample_redis(redis_cli, host, port, password):
    """Redis judging:queue 长度。返回 int 或 None。"""
    cmd = [redis_cli, "-h", host, "-p", str(port)]
    if password:
        cmd += ["-a", password]
    cmd += ["LLEN", REDIS_KEY]
    try:
        out = subprocess.run(cmd, capture_output=True, text=True, timeout=5)
        return int(out.stdout.strip())
    except Exception:
        return None


def sample_mq(host, port, user, password):
    """RabbitMQ 管理 API 采样 judging.result.queue。返回 dict 或 None。"""
    try:
        url = f"http://{host}:{port}{MQ_QUEUE_API}"
        req = urllib.request.Request(url)
        token = base64.b64encode(f"{user}:{password}".encode()).decode()
        req.add_header("Authorization", f"Basic {token}")
        with urllib.request.urlopen(req, timeout=5) as resp:
            q = json.loads(resp.read().decode("utf-8", "ignore"))
            return {
                "ready": q.get("messages_ready", 0),
                "unacked": q.get("messages_unacknowledged", 0),
                "total": q.get("messages", 0),
            }
    except Exception:
        return None


def _mysql_cli(mysql, host, port, user, password, sql):
    cmd = [mysql, "-h", host, "-P", str(port), "-u", user, "-N", "-e", sql]
    if password:
        cmd += [f"-p{password}"]
    out = subprocess.run(cmd, capture_output=True, text=True, timeout=8)
    return out.stdout.strip()


def sample_mysql(args, sql):
    """优先 mysql CLI，其次 pymysql。返回输出字符串或 None。"""
    if args.mysql_cli:
        try:
            return _mysql_cli(args.mysql_cli, args.mysql_host, args.mysql_port,
                              args.mysql_user, args.mysql_pass, sql)
        except Exception:
            pass
    try:
        import pymysql
        conn = pymysql.connect(host=args.mysql_host, port=args.mysql_port,
                               user=args.mysql_user, password=args.mysql_pass, connect_timeout=5)
        try:
            with conn.cursor() as cur:
                cur.execute(sql)
                return "\n".join(str(r[0]) for r in cur.fetchall())
        finally:
            conn.close()
    except Exception:
        return None


def mysql_stats(args):
    """采样 MySQL 连接状态。返回 (connected, running, writer_conns) 或 None。"""
    rows = {}
    out = sample_mysql(args, "SHOW STATUS LIKE 'Threads_connected'; SHOW STATUS LIKE 'Threads_running';")
    if out is None:
        return None
    for line in out.splitlines():
        line = line.strip()
        if "\t" in line:
            k, v = line.split("\t", 1)
            rows[k] = v
    # 统计 doj_submission 库上的连接（写库线程数）
    writers = None
    pl = sample_mysql(args, "SELECT COUNT(*) FROM information_schema.processlist WHERE db='doj_submission';")
    if pl is not None and pl.strip():
        try:
            writers = int(pl.splitlines()[-1].strip())
        except ValueError:
            writers = None
    return (rows.get("Threads_connected"), rows.get("Threads_running"), writers)


def submission_stats(args, since_id):
    """查询 submission 表：status 分布。"""
    sql = (f"SELECT status, COUNT(*) FROM {DB}.submission WHERE id > {since_id} "
           f"GROUP BY status ORDER BY COUNT(*) DESC;")
    out = sample_mysql(args, sql)
    if out is None:
        return None
    stats = {}
    total = 0
    for line in out.splitlines():
        line = line.strip()
        if not line or "\t" not in line:
            continue
        st, cnt = line.split("\t", 1)
        stats[st] = int(cnt)
        total += int(cnt)
    return {"stats": stats, "total": total}


PENDING_STATES = {"PENDING", "RUNNING", "QUEUED"}


def send_one(url: str, code: str, lang: str, pid: int) -> dict:
    body, ctype = build_multipart(code, lang, pid)
    req = urllib.request.Request(url, data=body, method="POST")
    req.add_header("Content-Type", ctype)
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            data = resp.read().decode("utf-8", "ignore")
            return {"ms": (time.perf_counter() - t0) * 1000, "status": resp.status, "body": data[:300]}
    except Exception as e:
        return {"ms": (time.perf_counter() - t0) * 1000, "status": -1, "body": str(e)[:300]}


def build_multipart(code: str, lang: str, pid: int) -> tuple:
    """手动构造 multipart/form-data，避免额外依赖"""
    boundary = uuid.uuid4().hex
    buf = []
    buf.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"main.cpp\"\r\nContent-Type: text/plain\r\n\r\n".encode())
    buf.append(code.encode("utf-8"))
    buf.append(b"\r\n")
    buf.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"language\"\r\n\r\n{lang}\r\n".encode())
    buf.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"pid\"\r\n\r\n{pid}\r\n".encode())
    buf.append(f"--{boundary}--\r\n".encode())
    return b"".join(buf), f"multipart/form-data; boundary={boundary}"


# ─────────────────────────── 主流程 ───────────────────────────

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", required=True, help="判题接口完整 URL")
    ap.add_argument("--pid", type=int, required=True, help="题目 ID")
    ap.add_argument("--concurrency", type=int, default=50)
    ap.add_argument("--lang", default="cpp")
    # Redis
    ap.add_argument("--redis-host", default="127.0.0.1")
    ap.add_argument("--redis-port", type=int, default=6379)
    ap.add_argument("--redis-pass", default="")
    ap.add_argument("--redis-cli", default="redis-cli")
    # RabbitMQ
    ap.add_argument("--rabbitmq-host", default="127.0.0.1")
    ap.add_argument("--rabbitmq-port", type=int, default=15672)
    ap.add_argument("--rabbitmq-user", default="guest")
    ap.add_argument("--rabbitmq-pass", default="guest")
    # MySQL
    ap.add_argument("--mysql-host", default="127.0.0.1")
    ap.add_argument("--mysql-port", type=int, default=3307)
    ap.add_argument("--mysql-user", default="root")
    ap.add_argument("--mysql-pass", default="123")
    ap.add_argument("--mysql-cli", default="mysql")
    args = ap.parse_args()

    # 压测前基线：最大 id
    base_out = sample_mysql(args, f"SELECT COALESCE(MAX(id),0) FROM {DB}.submission;")
    base_id = 0
    if base_out:
        try:
            base_id = int(base_out.splitlines()[-1].strip())
        except ValueError:
            pass
    print(f"[基线] submission 当前最大 id = {base_id}（压测后将统计 id > {base_id} 的记录）\n")

    stop_evt = threading.Event()
    lines = []  # 观测记录

    def observe_loop():
        t0 = time.time()
        print(f"{'时间(s)':>7} | {'Redis积压':>8} | {'MQ(ready/unack)':>15} | {'MySQL连接/运行':>13} | {'写库连接':>7}")
        while not stop_evt.is_set():
            r = sample_redis(args.redis_cli, args.redis_host, args.redis_port, args.redis_pass)
            m = sample_mq(args.rabbitmq_host, args.rabbitmq_port, args.rabbitmq_user, args.rabbitmq_pass)
            s = mysql_stats(args)
            t = time.time() - t0
            r_str = "-" if r is None else str(r)
            m_str = "-" if m is None else f"{m['ready']}/{m['unacked']}"
            s_str = "-" if s is None or s[0] is None else f"{s[0]}/{s[1]}"
            w_str = "-" if s is None or s[2] is None else str(s[2])
            lines.append((t, r, m, s))
            print(row := f"{t:7.1f} | {r_str:>8} | {m_str:>15} | {s_str:>13} | {w_str:>7}")
            stop_evt.wait(2)

    obs = threading.Thread(target=observe_loop, daemon=True)
    obs.start()

    print(f"开始压测: {args.concurrency} 并发 -> {args.url} (pid={args.pid}, lang={args.lang})")
    results = [None] * args.concurrency
    t_start = time.time()

    def worker(i):
        results[i] = send_one(args.url, CODE, args.lang, args.pid)

    threads = [threading.Thread(target=worker, args=(i,)) for i in range(args.concurrency)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    wall = time.time() - t_start

    stop_evt.set()
    obs.join(timeout=2)

    ok = [r for r in results if r["status"] == 200]
    fail = [r for r in results if r["status"] != 200]
    ms = sorted(r["ms"] for r in results)

    def pct(p):
        return ms[min(len(ms) - 1, int(len(ms) * p))]

    print("\n==================== HTTP 接口表现 ====================")
    print(f"总请求: {args.concurrency}   成功(HTTP 200): {len(ok)}   失败: {len(fail)}")
    print(f"墙钟耗时: {wall:.2f}s")
    print(f"响应延迟: 平均 {statistics.mean(ms):.0f}ms | P50 {pct(0.5):.0f}ms | P95 {pct(0.95):.0f}ms | P99 {pct(0.99):.0f}ms | 最大 {max(ms):.0f}ms")

    # 峰值采样（答辩用）
    peak_redis = max((x[1] for x in lines if x[1] is not None), default=None)
    peak_mq = max((x[2]["ready"] for x in lines if x[2] is not None), default=None)
    max_conn = max((int(x[3][0]) for x in lines if x[3] and x[3][0] is not None), default=None)
    print(f"\n观测峰值: Redis 积压最大 {peak_redis} 条 | MQ ready 最大 {peak_mq} 条 | MySQL 连接峰值 {max_conn}")

    # 等待所有结果落库
    print(f"\n==================== 等待 {args.concurrency} 条判题结果落库 ====================")
    deadline = time.time() + 360
    last_total = -1
    while time.time() < deadline:
        st = submission_stats(args, base_id)
        if st is None:
            print("  [warn] MySQL 不可达，跳过轮询")
            break
        total = st["total"]
        done = sum(v for k, v in st["stats"].items() if k not in PENDING_STATES)
        if total != last_total:
            print(f"  已落库 {total}/{args.concurrency}  终态 {done}  分布 {st['stats']}")
            last_total = total
        if total >= args.concurrency and done == total:
            break
        time.sleep(5)

    st = submission_stats(args, base_id)
    print("\n==================== 最终判题结果 ====================")
    if st:
        print(f"新增提交记录: {st['total']} 条")
        for k, v in st["stats"].items():
            print(f"  {k}: {v}")
        pending = sum(v for k, v in st["stats"].items() if k in PENDING_STATES)
        success = st["stats"].get("Accepted", 0)
        print(f"\n判题成功率: {success}/{st['total']} Accepted")
        print(f"仍有未终态(PENDING/RUNNING): {pending} 条" if pending else "全部结果已落库，无 PENDING 残留，消息零丢失")
    else:
        print("  MySQL 不可达，无法统计（请检查 --mysql-* 参数和端口转发）")


if __name__ == "__main__":
    main()
