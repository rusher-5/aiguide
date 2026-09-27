# -*- coding: utf-8 -*-
"""
AI 导览平台 A/B 压测脚本：Java 版 (D:\\aiguide, :5001) vs Python 版 (D:\\文件\\PythonProject1, :5000)

用法：
  py bench_ab.py ping                      # 框架基线：静态路由 QPS 对比（无需 LLM Key，随时可跑）
  py bench_ab.py chat --q "灵山大佛多高"    # 全链路：同步问答 P50/P95 总耗时（两边都需配好 DEEPSEEK_API_KEY）
  py bench_ab.py chat --q "..." --stream   # 全链路：SSE 流式首包(TTFB)耗时对比
  可选参数：--n 20（请求数） --c 4（并发数） --java http://localhost:5001 --py http://localhost:5000

输出统一为控制台表格，便于留存对比结果。
"""
import argparse
import json
import statistics
import sys
import time
import urllib.request
import urllib.error
from concurrent.futures import ThreadPoolExecutor

JAVA_DEFAULT = 'http://localhost:5001'
PY_DEFAULT = 'http://localhost:5000'


def http_json(url, payload=None, timeout=120):
    """POST JSON（payload=None 时 GET），返回 (状态码, 耗时秒, 首包耗时秒, 响应文本截断)。"""
    start = time.perf_counter()
    first = None
    try:
        if payload is None:
            req = urllib.request.Request(url)
        else:
            req = urllib.request.Request(
                url, data=json.dumps(payload, ensure_ascii=False).encode('utf-8'),
                headers={'Content-Type': 'application/json'}, method='POST')
        resp = urllib.request.urlopen(req, timeout=timeout)
        body = resp.read()  # 对流式接口：read() 完成即总耗时；首包用底层 socket 计时
        elapsed = time.perf_counter() - start
        return resp.status, elapsed, first, body.decode('utf-8', errors='replace')[:200]
    except urllib.error.HTTPError as e:
        return e.code, time.perf_counter() - start, None, e.read().decode('utf-8', errors='replace')[:200]
    except Exception as e:  # noqa: BLE001
        return -1, time.perf_counter() - start, None, str(e)[:200]


def http_stream(url, payload, timeout=180):
    """POST SSE 流式接口：返回 (状态码, TTFB, 总耗时, 首个事件类型)。TTFB=读到第一字节的耗时。"""
    start = time.perf_counter()
    try:
        req = urllib.request.Request(
            url, data=json.dumps(payload, ensure_ascii=False).encode('utf-8'),
            headers={'Content-Type': 'application/json', 'Accept': 'text/event-stream'},
            method='POST')
        resp = urllib.request.urlopen(req, timeout=timeout)
        ttfb = None
        first_event = ''
        while True:
            chunk = resp.read(256)
            if not chunk:
                break
            if ttfb is None:
                ttfb = time.perf_counter() - start
                text = chunk.decode('utf-8', errors='replace')
                for line in text.splitlines():
                    if line.startswith('event:'):
                        first_event = line.split(':', 1)[1].strip()
                        break
                break  # TTFB 模式只测首包，避免跑完整条 LLM+TTS 流
        total = time.perf_counter() - start
        resp.close()
        return resp.status, ttfb, total, first_event
    except urllib.error.HTTPError as e:
        return e.code, None, time.perf_counter() - start, e.read().decode('utf-8', errors='replace')[:120]
    except Exception as e:  # noqa: BLE001
        return -1, None, time.perf_counter() - start, str(e)[:120]


def pct(data, p):
    if not data:
        return float('nan')
    data = sorted(data)
    idx = min(len(data) - 1, int(len(data) * p / 100))
    return data[idx]


def bench_concurrent(fn, n, c):
    """并发执行 n 次 fn()，返回结果列表。"""
    with ThreadPoolExecutor(max_workers=c) as pool:
        return list(pool.map(lambda _: fn(), range(n)))


def report(name, results, field_idx, field_name, wall):
    ok = [r for r in results if r[0] == 200 and r[field_idx] is not None]
    lat = [r[field_idx] for r in ok]
    if not lat:
        print(f'  {name:<8} 全部失败（{len(results)} 请求）——检查目标服务是否在运行')
        return
    qps = len(ok) / wall if wall > 0 else float('nan')
    print(f'  {name:<8} 成功 {len(ok)}/{len(results)}  '
          f'{field_name} P50={pct(lat, 50)*1000:7.1f}ms  P95={pct(lat, 95)*1000:7.1f}ms  '
          f'MAX={max(lat)*1000:7.1f}ms  吞吐={qps:6.1f} req/s')


def run_ping(args):
    print(f'== 框架基线（GET 探活路由，{args.n} 请求 x {args.c} 并发）==')
    for name, base in (('Java', args.java), ('Python', args.py)):
        url = base + ('/api/chat/status' if name == 'Java' else '/')
        start = time.perf_counter()
        results = bench_concurrent(lambda: http_json(url, timeout=10), args.n, args.c)
        wall = time.perf_counter() - start
        report(name, results, 1, '延迟', wall)


def run_chat(args):
    payload = {'question': args.q}
    if args.stream:
        print(f'== 全链路 SSE 首包 TTFB（{args.n} 请求 x {args.c} 并发）==')
        for name, base in (('Java', args.java), ('Python', args.py)):
            url = base + ('/api/chat/stream' if name == 'Java' else '/chat/stream')
            results = bench_concurrent(lambda: http_stream(url, payload), args.n, args.c)
            ok = [r for r in results if r[0] == 200 and r[1] is not None]
            ttfbs = [r[1] for r in ok]
            if ttfbs:
                print(f'  {name:<8} 成功 {len(ok)}/{len(results)}  '
                      f'TTFB P50={pct(ttfbs, 50)*1000:7.1f}ms  P95={pct(ttfbs, 95)*1000:7.1f}ms  '
                      f'首事件={[r[3] for r in ok[:1]]}')
            else:
                print(f'  {name:<8} 全部失败（示例: {results[0]}）——检查 DEEPSEEK_API_KEY / 服务状态')
    else:
        print(f'== 全链路同步问答（{args.n} 请求 x {args.c} 并发）==')
        for name, base in (('Java', args.java), ('Python', args.py)):
            url = base + ('/api/chat' if name == 'Java' else '/chat')
            start = time.perf_counter()
            results = bench_concurrent(lambda: http_json(url, payload), args.n, args.c)
            wall = time.perf_counter() - start
            report(name, results, 1, '总耗时', wall)


def main():
    ap = argparse.ArgumentParser(description='AI 导览平台 Java/Python A/B 压测')
    ap.add_argument('mode', choices=['ping', 'chat'])
    ap.add_argument('--q', default='灵山大佛有多高？门票多少钱？', help='测试问题')
    ap.add_argument('--stream', action='store_true', help='chat 模式下测 SSE TTFB')
    ap.add_argument('--n', type=int, default=20, help='总请求数')
    ap.add_argument('--c', type=int, default=4, help='并发数')
    ap.add_argument('--java', default=JAVA_DEFAULT)
    ap.add_argument('--py', default=PY_DEFAULT)
    args = ap.parse_args()

    if sys.platform == 'win32':
        sys.stdout.reconfigure(encoding='utf-8')  # noqa: PLW1503

    print(f'目标: Java={args.java}  Python={args.py}')
    if args.mode == 'ping':
        run_ping(args)
    else:
        run_chat(args)


if __name__ == '__main__':
    main()
