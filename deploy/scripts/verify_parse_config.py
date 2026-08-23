# -*- coding: utf-8 -*-
"""验证新的 parse_config 防御逻辑：畸形行必须给出清晰报错，正常行正常工作"""
import sys

SRC = r'''def parse_config(s):
    items = []
    rounds = 20
    for idx, raw in enumerate(s.strip().split('\n'), 1):
        line = raw.strip()
        if not line or line.startswith('#'):
            continue
        if line.startswith('rounds'):
            pr = line.split()
            if len(pr) < 2:
                raise ValueError(f"配置第 {idx} 行缺少 rounds 次数: '{line}'，期望格式: rounds 次数")
            rounds = int(pr[1])
            continue
        parts = line.split()
        name = parts[0]
        def need(n, fmt):
            if len(parts) < n:
                raise ValueError(f"配置第 {idx} 行字段不足: '{line}'，期望格式: {fmt}")
        sort_order = None
        if len(parts) > 1 and parts[-1] in ('asc', 'desc'):
            sort_order = parts[-1]
            parts = parts[:-1]
        if len(parts) < 2:
            raise ValueError(f"配置第 {idx} 行无法识别类型: '{line}'，期望格式: 名称 类型 [范围]")
        tk = parts[1]
        if tk == 'string':
            need(4, '名称 string 最小长度 最大长度 [charset]')
            charset = parts[4] if len(parts) > 4 else 'mixed'
            items.append({'name':name,'type':'string','min':int(parts[2]),'max':int(parts[3]),'charset':charset})
        elif tk == 'char':
            charset = parts[2] if len(parts) > 2 else 'mixed'
            items.append({'name':name,'type':'char','charset':charset})
        elif tk == 'bool':
            items.append({'name':name,'type':'bool'})
        elif tk == 'double':
            need(4, '名称 double 最小 最大')
            items.append({'name':name,'type':'double','min':parts[2],'max':parts[3]})
        elif tk == 'long':
            need(4, '名称 long 最小 最大')
            items.append({'name':name,'type':'long','min':parts[2],'max':parts[3]})
        elif tk == 'int':
            need(4, '名称 int 最小 最大')
            items.append({'name':name,'type':'int','min':parts[2],'max':parts[3]})
        elif tk.startswith('int['):
            need(4, '名称 int[n] 最小 最大 [asc|desc]')
            size_var = tk[4:-1]
            it = {'name':name,'type':'int[]','size':size_var,'min':int(parts[2]),'max':int(parts[3])}
            if sort_order: it['sort'] = sort_order
            items.append(it)
        elif tk.startswith('string['):
            need(4, '名称 string[n] 最小长度 最大长度 [charset] [asc|desc]')
            size_var = tk[7:-1]
            charset = parts[4] if len(parts) > 4 else 'mixed'
            it = {'name':name,'type':'string[]','size':size_var,'min':int(parts[2]),'max':int(parts[3]),'charset':charset}
            if sort_order: it['sort'] = sort_order
            items.append(it)
        elif tk.startswith('char['):
            need(2, '名称 char[n] [charset] [asc|desc]')
            size_var = tk[5:-1]
            charset = parts[2] if len(parts) > 2 else 'mixed'
            it = {'name':name,'type':'char[]','size':size_var,'charset':charset}
            if sort_order: it['sort'] = sort_order
            items.append(it)
        elif tk.startswith('bool['):
            need(2, '名称 bool[n] [asc|desc]')
            size_var = tk[5:-1]
            it = {'name':name,'type':'bool[]','size':size_var}
            if sort_order: it['sort'] = sort_order
            items.append(it)
        elif tk.startswith('double['):
            need(4, '名称 double[n] 最小 最大 [asc|desc]')
            size_var = tk[7:-1]
            it = {'name':name,'type':'double[]','size':size_var,'min':int(parts[2]),'max':int(parts[3])}
            if sort_order: it['sort'] = sort_order
            items.append(it)
        elif tk.startswith('long['):
            need(4, '名称 long[n] 最小 最大 [asc|desc]')
            size_var = tk[5:-1]
            it = {'name':name,'type':'long[]','size':size_var,'min':int(parts[2]),'max':int(parts[3])}
            if sort_order: it['sort'] = sort_order
            items.append(it)
        else:
            need(3, '名称 最小 最大（旧格式，隐式 int）')
            items.append({'name':name,'type':'int','min':parts[1],'max':parts[2]})
    return items, rounds
'''

ns = {}
exec(SRC, ns)
parse_config = ns['parse_config']

# ── 正常配置（模拟题目样例）──
good = [
    "a long -1000000000 1000000000\nb long -1000000000 1000000000\nrounds 5",
    "n int 1 1000\narr int[n] -1000000 1000000 asc\nrounds 5",
    "s string 1 1000 lower\nc char lower\nrounds 5",
    "x double 0 100\ny bool\nz int 1 100 desc",
]
for g in good:
    items, r = parse_config(g)
    print(f"OK   rounds={r} items={items}")

# ── 畸形配置：必须报清晰错误，不能 IndexError ──
bad = [
    ("s string", "string 缺范围"),
    ("s string 1", "string 缺 max"),
    ("abc", "单字段无法识别"),
    ("rounds", "rounds 缺次数"),
    ("a", "空类型"),
    ("a int 1", "int 缺 max"),
    ("arr int[n] 1 100", "数组缺 max"),
    ("a long 1 1000000000 2000000000 extra extra", "多余字段应正常（宽松）"),
]
for line, desc in bad:
    try:
        items, r = parse_config(line)
        print(f"PASS? {desc}: 未报错 items={items}  ← 多余字段宽松解析，OK")
    except ValueError as e:
        print(f"GOOD  {desc}: {e}")
    except IndexError as e:
        print(f"FAIL  {desc}: 仍然 IndexError: {e}")

# ── 空配置 ──
items, r = parse_config("")
print("EMPTY:", items, r)
print("\nALL DONE")
