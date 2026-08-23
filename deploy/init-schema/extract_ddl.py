#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从 docs/SQL/ojdatabases.sql（mysqldump 导出）提取纯 DDL，
输出到 deploy/init-schema/03-init-tables.sql。

剔除内容：
  - mysqldump 头部注释块与 SET 语句（/*!...*/）
  - 所有 INSERT INTO / LOCK TABLES / UNLOCK TABLES / ALTER ... DISABLE KEYS
  - nacos 库全部内容（Nacos 启动后自动建自己的表）

用法（在仓库根目录执行）：
    python deploy/init-schema/extract_ddl.py
"""

from pathlib import Path

SRC = Path(__file__).resolve().parents[2] / "docs" / "SQL" / "ojdatabases.sql"
OUT = Path(__file__).resolve().parent / "03-init-tables.sql"

HEADER = """\
-- ═══════════════════════════════════════════════════════════════
-- D-OnlineJudge 业务库表结构（纯 DDL，无数据）
-- ═══════════════════════════════════════════════════════════════
-- 来源：docs/SQL/ojdatabases.sql（mysqldump 提取）
-- 重新生成：python deploy/init-schema/extract_ddl.py
--
-- 说明：
--   - 只含表结构，不含任何 INSERT 数据 → 生产从空表开始
--   - 已剔除 nacos 库（Nacos 启动后自动创建自己的表）
--   - 文件名前缀 03 保证在 01(复制用户) / 02(建库) 之后执行
--   - 本文件体积 >1MB 时不能进 ConfigMap，必须走 init-schema 镜像
-- ═══════════════════════════════════════════════════════════════

"""


def main() -> None:
    lines = SRC.read_text(encoding="utf-8").splitlines()
    out: list[str] = []
    i, n = 0, len(lines)

    while i < n:
        stripped = lines[i].strip()

        # nacos 库：直接截断（Nacos 自己建表）
        if stripped.startswith("CREATE DATABASE") and "`nacos`" in stripped:
            break

        # 注释、mysqldump 环境 SET 语句：整行跳过
        if not stripped or stripped.startswith("--") or stripped.startswith("/*!"):
            i += 1
            continue

        # 库级语句 / 删表：单行保留
        if (
            stripped.startswith("CREATE DATABASE")
            or stripped.startswith("USE `")
            or stripped.startswith("DROP TABLE IF EXISTS")
        ):
            out.append(lines[i])
            i += 1
            continue

        # CREATE TABLE：收集到分号为止（跨多行）
        if stripped.startswith("CREATE TABLE"):
            buf = [lines[i]]
            i += 1
            while i < n and not lines[i].rstrip().endswith(";"):
                buf.append(lines[i])
                i += 1
            if i < n:
                buf.append(lines[i])
                i += 1
            out.append("\n".join(buf))
            continue

        # 其余（INSERT / LOCK / UNLOCK / ALTER ... KEYS 等数据段）：跳过
        i += 1

    OUT.write_text(HEADER + "\n".join(out) + "\n", encoding="utf-8")
    print(f"OK -> {OUT} ({len(out)} lines)")


if __name__ == "__main__":
    main()
