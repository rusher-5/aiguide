# -*- coding: utf-8 -*-
"""
真实用户级 E2E 测试（CDP 直驱版，无 webdriver 依赖）：
直接通过 Edge DevTools 协议驱动本机 Edge，浏览器内执行的是页面真实的 JS——
SSE 解析、fetch、DOM 渲染全部走生产代码路径，可捕获"脚本测接口通、浏览器卡死"类问题。

覆盖场景（游客端 → 短链平台 → 管理后台 全链路）：
  A. 游客端：首页/Live2D/静态资源/LLM探活/数字人配置/猜你想问秒回/LLM流式问答/
     TTS语音推送与下载/短链卡片渲染/多轮对话/控制台无错误
  B. 短链链路：g1 302 跳转目标可达（同时计入点击漏斗）
  C. 管理后台：Basic 鉴权(错误密码401)/后台页面/知识库上传-列表-删除(完整CRUD)/
     快问快答列表/景点短链映射/交互记录落库(推荐环节)/数据大屏漏斗闭环(点击环节)

用法：py e2e_user_test.py [--url http://localhost:5001] [--headed]
"""
import argparse
import http.client
import json
import subprocess
import sys
import time
import urllib.request

import websocket

EDGE = r'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe'
DEBUG_PORT = 9333
SHORT_LINK_HOST = ('localhost', 8080)
PASS, FAIL = 'PASS', 'FAIL'
results = []


def check(name, ok, detail=''):
    results.append((name, PASS if ok else FAIL, detail))
    print(f'[{PASS if ok else FAIL}] {name}' + (f'  -- {detail}' if detail else ''))


class Cdp:
    def __init__(self, ws_url):
        self.ws = websocket.create_connection(ws_url, timeout=30)
        self.msg_id = 0
        self.console_errors = []

    def send(self, method, params=None):
        self.msg_id += 1
        self.ws.send(json.dumps({'id': self.msg_id, 'method': method, 'params': params or {}}))
        while True:
            msg = json.loads(self.ws.recv())
            if msg.get('id') == self.msg_id:
                if 'error' in msg:
                    raise RuntimeError(f'CDP {method}: {msg["error"]}')
                return msg.get('result', {})
            m, p = msg.get('method', ''), msg.get('params', {})
            if m == 'Runtime.exceptionThrown':
                self.console_errors.append(str(p.get('exceptionDetails', {}).get('text', '')))
            elif m == 'Runtime.consoleAPICalled' and p.get('type') == 'error':
                self.console_errors.append(' '.join(str(a.get('value', '')) for a in p.get('args', [])))
            elif m == 'Log.entryAdded' and p.get('entry', {}).get('level') == 'error':
                self.console_errors.append(p['entry'].get('text', '') + ' ' + p['entry'].get('url', ''))

    def eval_js(self, expr, await_promise=False):
        r = self.send('Runtime.evaluate', {
            'expression': expr, 'returnByValue': True, 'awaitPromise': await_promise})
        if r.get('exceptionDetails'):
            raise RuntimeError(r['exceptionDetails'].get('text', 'js error'))
        return r.get('result', {}).get('value')

    def close(self):
        try:
            self.ws.close()
        except Exception:
            pass


# ---------- 浏览器内辅助：同源带 Basic 鉴权的 fetch ----------
ADMIN_AUTH_JS = "btoa('admin:admin123')"


def admin_fetch_js(path, method='GET', body_json=None, auth='admin:admin123'):
    """生成一段在页面上下文执行的 fetch JS（await Promise 返回 {status, body}）。"""
    body_part = f"body: JSON.stringify({json.dumps(body_json, ensure_ascii=False)})," if body_json else ''
    return (
        f"fetch({json.dumps(path)}, {{method: {json.dumps(method)}, "
        f"headers: {{Authorization: 'Basic ' + btoa({json.dumps(auth)})}}, {body_part}}})"
        ".then(async r => ({status: r.status, body: await r.text()}))")


def admin_fetch(cdp, path, method='GET', body_json=None, auth='admin:admin123'):
    r = cdp.eval_js(admin_fetch_js(path, method, body_json, auth), await_promise=True)
    try:
        r['json'] = json.loads(r['body'])
    except Exception:
        r['json'] = None
    return r


def wait_status(cdp, needles, timeout_s, extra_js=None):
    """轮询 #status 出现关键词，可附带额外条件。"""
    t0 = time.time()
    while time.time() - t0 < timeout_s:
        s = cdp.eval_js('document.getElementById("status").textContent')
        ok = any(n in s for n in needles)
        if ok and extra_js:
            ok = cdp.eval_js(extra_js)
        if ok:
            return True, time.time() - t0, s
        time.sleep(1)
    return False, time.time() - t0, cdp.eval_js('document.getElementById("status").textContent')


def ask_in_page(cdp, question):
    cdp.eval_js(
        f'document.getElementById("inputText").value = {json.dumps(question, ensure_ascii=False)};'
        'document.getElementById("sendBtn").click();')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--url', default='http://localhost:5001')
    ap.add_argument('--question', default='介绍一下祥符禅寺的历史由来')  # 避开快问快答关键词，确保走 LLM 链路
    ap.add_argument('--headed', action='store_true')
    args = ap.parse_args()
    if sys.platform == 'win32':
        sys.stdout.reconfigure(encoding='utf-8')  # noqa: PLW1503

    user_data = r'C:\Users\25724\AppData\Local\Temp\deveco\edge-e2e-profile'
    cmd = [EDGE, f'--remote-debugging-port={DEBUG_PORT}', f'--user-data-dir={user_data}',
           '--remote-allow-origins=*', '--no-first-run', '--mute-audio', '--window-size=1280,900']
    if not args.headed:
        cmd.append('--headless=new')
    proc = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    cdp = None
    try:
        # 等调试端口就绪并开标签页
        target = None
        for _ in range(30):
            try:
                req = urllib.request.Request(
                    f'http://127.0.0.1:{DEBUG_PORT}/json/new?{args.url}', method='PUT')
                target = json.loads(urllib.request.urlopen(req, timeout=3).read())
                break
            except Exception:
                time.sleep(1)
        if not target:
            print('无法连接 Edge 调试端口')
            sys.exit(2)

        cdp = Cdp(target['webSocketDebuggerUrl'])
        cdp.send('Runtime.enable')
        cdp.send('Log.enable')
        time.sleep(3)  # 等首页 JS 与静态资源加载

        # ==================== A. 游客端 ====================
        # ---- 1. 首页 ----
        title = cdp.eval_js('document.title')
        check('1.首页可访问', 'AI 智能导游' in (title or ''), f'title={title}')

        # ---- 2. Live2D（轮询等待模型加载）----
        canvas_js = '!!document.querySelector("#live2d-container canvas")'
        up = False
        t0 = time.time()
        while time.time() - t0 < 20:
            if cdp.eval_js(canvas_js):
                up = True
                break
            time.sleep(1)
        st = cdp.eval_js('document.getElementById("status").textContent')
        check('2.Live2D画布已创建', up, f'{time.time()-t0:.1f}s, status={st}')
        check('2.1 Live2D未报初始化失败', '失败' not in st, f'status={st}')

        # ---- 3. 静态资源无 4xx/5xx（favicon 浏览器自动请求，单独过滤）----
        assets = cdp.eval_js(
            'JSON.stringify(performance.getEntriesByType("resource")'
            '.filter(r => r.responseStatus >= 400 && !r.name.includes("favicon"))'
            '.map(r => r.name + " " + r.responseStatus))')
        bad = json.loads(assets)
        check('3.静态资源无4xx/5xx', not bad, '; '.join(bad[:4]))

        # ---- 4. LLM 探活 ----
        llm = cdp.eval_js(
            "fetch('/api/chat/status').then(r => r.json())", await_promise=True)
        llm_ok = llm and llm.get('code') == 0 and llm.get('data', {}).get('llmConfigured')
        check('4.LLM配置探活', llm_ok, json.dumps(llm, ensure_ascii=False)[:80])

        # ---- 5. 数字人配置（游客端只读）----
        cfg = cdp.eval_js("fetch('/api/config').then(r => r.json())", await_promise=True)
        cfg_ok = cfg and cfg.get('code') == 0 and isinstance(cfg.get('data'), dict)
        check('5.数字人配置接口', cfg_ok, json.dumps(cfg, ensure_ascii=False)[:80])

        # ---- 6. 猜你想问 chips 渲染 ----
        chips = cdp.eval_js('document.querySelectorAll(".chip").length')
        check('6.猜你想问chips已渲染', chips and chips >= 3, f'{chips} 个')

        # ---- 7. 点击 chip → 秒回（命中预制答案，不调 LLM）----
        if chips:
            cdp.eval_js('document.querySelector(".chip").click()')
            quick_done, cost, s = wait_status(cdp, ['秒回'], 30)
            check('7.点击chip秒回', quick_done, f'{cost:.1f}s（含打字机动画）, status={s}')
            badge = cdp.eval_js(
                'document.querySelector(".quick-badge") ? document.querySelector(".quick-badge").textContent : ""')
            check('7.1秒回徽标', '秒回' in badge, badge)

        # ---- 8. 拦截 TTS 音频 URL（在任何提问前打补丁）----
        cdp.eval_js(
            'window.__audioUrls = [];'
            'const __origEnqueue = window.enqueueAudio;'
            'window.enqueueAudio = function(seq, url) { window.__audioUrls.push(url); return __origEnqueue(seq, url); };')

        # ---- 9. LLM 流式问答（真实 sendText→fetch→SSE 解析链路）----
        ask_in_page(cdp, args.question)
        appeared = False
        t0 = time.time()
        while time.time() - t0 < 60:
            txt = cdp.eval_js(
                '(() => { const ms = document.querySelectorAll(".msg-row.ai .bubble");'
                'return ms.length ? ms[ms.length-1].textContent.trim() : ""; })()')
            if txt and len(txt) > 5:
                appeared = True
                break
            time.sleep(1)
        check('9.流式文字已出现', appeared,
              '60s内无回答' if not appeared else f'{time.time()-t0:.1f}s 出现')

        done, cost, s = wait_status(cdp, ['回复完成', '秒回'], 120)
        check('10.回答完成', done, f'{cost:.1f}s, status={s}')

        final = cdp.eval_js(
            '(() => { const ms = document.querySelectorAll(".msg-row.ai .bubble");'
            'return ms.length ? ms[ms.length-1].textContent : ""; })()')
        check('11.回答非报错文案', ('失败' not in final) and ('出错了' not in final) and len(final) > 20,
              final[:60].replace('\n', ' '))

        # ---- 12. TTS 语音链路：audio 事件推送 + 音频可下载 ----
        n_audio = cdp.eval_js('window.__audioUrls.length') or 0
        first_audio_ok = '首音' in s
        check('12.TTS音频事件已推送', n_audio > 0 and first_audio_ok,
              f'{n_audio} 段音频, 状态栏{"含" if first_audio_ok else "不含"}首音计时')
        if n_audio:
            audio_info = cdp.eval_js(
                "fetch(window.__audioUrls[0]).then(async r =>"
                " ({status: r.status, type: r.headers.get('content-type'),"
                "  size: (await r.arrayBuffer()).byteLength}))", await_promise=True)
            a_ok = (audio_info and audio_info.get('status') == 200
                    and (audio_info.get('type') or '').startswith('audio')
                    and audio_info.get('size', 0) > 1000)
            check('12.1音频可下载', a_ok, json.dumps(audio_info))

        # ---- 13. 短链卡片（问含"大佛"的问题 → 应下发景点短链卡片）----
        ask_in_page(cdp, '灵山大佛值得看吗')
        done2, cost2, s2 = wait_status(cdp, ['回复完成', '秒回'], 120)
        check('13.大佛问题回答完成', done2, f'{cost2:.1f}s, status={s2}')
        card = cdp.eval_js(
            '(() => { const cs = document.querySelectorAll(".link-card");'
            'return cs.length ? JSON.stringify({href: cs[cs.length-1].href,'
            ' degraded: cs[cs.length-1].className.includes("degraded"), text: cs[cs.length-1].textContent}) : ""; })()')
        check('14.短链卡片已渲染', bool(card), card[:100] if card else '无 .link-card')
        if card:
            c = json.loads(card)
            check('14.1卡片指向短链平台g1', c['href'].endswith('/g1'), c['href'])
            check('14.2卡片未降级', not c['degraded'],
                  '降级为长链' if c['degraded'] else '短链正常')

        # ---- 15. 多轮对话（带 history 二次提问）----
        ask_in_page(cdp, '那从上海怎么过去？')
        done3, cost3, s3 = wait_status(
            cdp, ['回复完成', '秒回'], 120,
            extra_js='document.querySelectorAll(".msg-row.ai").length >= 3')
        check('15.多轮对话正常', done3, f'{cost3:.1f}s, status={s3}')

        # ---- 16. 浏览器控制台无严重错误 ----
        real_errors = [e for e in cdp.console_errors if 'favicon' not in e]
        check('16.控制台无JS错误', not real_errors, '; '.join(real_errors[:3])[:150])

        # ==================== B. 短链链路 ====================
        # ---- 17. g1 302 跳转目标可达（本次请求同时计入点击漏斗）----
        conn = http.client.HTTPConnection(SHORT_LINK_HOST[0], SHORT_LINK_HOST[1], timeout=10)
        conn.request('GET', '/g1')
        resp = conn.getresponse()
        loc = resp.getheader('Location') or ''
        g1_status = resp.status
        resp.read()
        conn.close()
        check('17.短链g1正常302', g1_status == 302 and loc.startswith('https://'),
              f'status={g1_status}, location={loc[:60]}')

        # ==================== C. 管理后台 ====================
        # ---- 18. 鉴权：错误密码 401（Python 侧测，避免浏览器弹原生认证框挂起 fetch）----
        import base64
        conn401 = http.client.HTTPConnection('localhost', 5001, timeout=10)
        conn401.request('GET', '/api/admin/dashboard',
                        headers={'Authorization': 'Basic ' + base64.b64encode(b'admin:wrongpass').decode()})
        resp401 = conn401.getresponse()
        s401 = resp401.status
        resp401.read()
        conn401.close()
        check('18.错误密码被拒(401)', s401 == 401, f'status={s401}')

        r = admin_fetch(cdp, '/api/admin/dashboard')
        dash = r.get('json') or {}
        d = dash.get('data') or {}
        check('19.数据大屏接口', r['status'] == 200 and dash.get('code') == 0,
              f"total={d.get('total')}, today={d.get('today')}")
        check('19.1问答量≥本轮对话数(4)', (d.get('total') or 0) >= 4, f"total={d.get('total')}")

        # ---- 20. 管理后台页面（带鉴权可获取）----
        page = cdp.eval_js(
            "fetch('/admin', {headers: {Authorization: 'Basic ' + " + ADMIN_AUTH_JS + "}})"
            ".then(async r => ({status: r.status, body: (await r.text()).length}))", await_promise=True)
        check('20.管理后台页面可获取', page and page['status'] == 200 and page['body'] > 5000,
              f"status={page['status']}, {page['body']} 字节")

        # ---- 21. 知识库完整 CRUD：上传 → 列表可见 → 删除 → 消失 ----
        upload = cdp.eval_js(
            "(() => { const fd = new FormData();"
            " fd.append('file', new File(['灵山大佛，坐落于无锡马山。E2E测试知识文档，用于验证上传与删除链路。'],"
            " 'e2e_test_knowledge.txt', {type: 'text/plain'}));"
            " return fetch('/api/admin/knowledge/upload',"
            "   {method: 'POST', headers: {Authorization: 'Basic ' + " + ADMIN_AUTH_JS + "}, body: fd})"
            "  .then(async r => ({status: r.status, body: await r.text()})); })()", await_promise=True)
        up_json = None
        try:
            up_json = json.loads(upload['body'])
        except Exception:
            pass
        new_id = (up_json or {}).get('data', {}).get('id')
        check('21.知识库上传', upload['status'] == 200 and new_id, upload['body'][:80])

        kl = admin_fetch(cdp, '/api/admin/knowledge/list')
        kl_items = ((kl.get('json') or {}).get('data')) or []
        names = json.dumps(kl_items, ensure_ascii=False)
        check('21.1知识列表含新文档', 'e2e_test_knowledge.txt' in names, f'{len(kl_items)} 篇')

        if new_id:
            dl = admin_fetch(cdp, f'/api/admin/knowledge/{new_id}', method='DELETE')
            kl2 = admin_fetch(cdp, '/api/admin/knowledge/list')
            gone = 'e2e_test_knowledge.txt' not in json.dumps((kl2.get('json') or {}).get('data') or [],
                                                              ensure_ascii=False)
            check('21.2知识删除生效', dl['status'] == 200 and gone,
                  f'delete={dl["status"]}, 列表已{"清" if gone else "仍存在"}')

        # ---- 22. 快问快答列表（chips 的数据源）----
        qa = admin_fetch(cdp, '/api/admin/quick-qa/list')
        qa_items = ((qa.get('json') or {}).get('data')) or []
        check('22.快问快答列表', qa['status'] == 200 and len(qa_items) >= 1,
              f'{len(qa_items)} 条')

        # ---- 23. 景点短链映射 ----
        sl = admin_fetch(cdp, '/api/admin/scene/list')
        sl_items = ((sl.get('json') or {}).get('data')) or []
        sl_str = json.dumps(sl_items, ensure_ascii=False)
        check('23.景点短链映射', sl['status'] == 200 and '灵山大佛' in sl_str and 'g1' in sl_str,
              f'{len(sl_items)} 个景点')

        # ---- 24. 交互记录落库（推荐环节：问题 + links_json）----
        it = admin_fetch(cdp, '/api/admin/interactions?page=1&size=20')
        items = (((it.get('json') or {}).get('data')) or {}).get('items') or []
        hit = None
        for row in items:
            if '灵山大佛值得看吗' in json.dumps(row, ensure_ascii=False):
                hit = row
                break
        hit_ok = hit is not None and 'g1' in json.dumps(hit, ensure_ascii=False)
        check('24.交互记录含大佛提问且推荐落库', hit_ok,
              '未找到记录' if not hit else f"links已落库: {str(hit.get('linksJson') or hit.get('links_json') or '')[:60]}")

        # ---- 25. 漏斗闭环：点击 g1 后大屏 sceneStats PV 增长（MQ 异步统计，轮询等待）----
        pv_ok, pv_detail = False, '未取到 sceneStats'
        t0 = time.time()
        while time.time() - t0 < 30:
            r2 = admin_fetch(cdp, '/api/admin/dashboard')
            stats = ((r2.get('json') or {}).get('data') or {}).get('sceneStats') or []
            row = next((x for x in stats if x.get('scene') == '灵山大佛'), None)
            if row and (row.get('pv') or 0) >= 1:
                pv_ok = True
                pv_detail = f"pv={row['pv']}, uv={row['uv']}, todayPv={row['todayPv']}"
                break
            pv_detail = f"当前: {json.dumps(row, ensure_ascii=False) if row else stats}"
            time.sleep(3)
        check('25.短链点击漏斗(PV≥1)', pv_ok, pv_detail)

    finally:
        if cdp:
            cdp.close()
        proc.terminate()

    failed = [r for r in results if r[1] == FAIL]
    print(f'\n===== 结果: {len(results) - len(failed)}/{len(results)} 通过 =====')
    if failed:
        for name, _, detail in failed:
            print(f'  失败: {name} {detail}')
        sys.exit(1)


if __name__ == '__main__':
    main()
