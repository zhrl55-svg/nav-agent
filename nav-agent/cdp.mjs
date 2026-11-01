// 通过 CDP 在已启动的 Chrome 上执行 JS 表达式
// 用法: node cdp.mjs <表达式> [端口]
const port = process.argv[3] || 9222;
const expr = process.argv[2] || 'document.title';

const targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
const page = targets.find((t) => t.type === 'page');
if (!page) {
  console.error('没有找到页面目标');
  process.exit(1);
}

const ws = new WebSocket(page.webSocketDebuggerUrl);
let seq = 0;
const pending = new Map();

ws.addEventListener('message', (ev) => {
  const msg = JSON.parse(ev.data);
  if (msg.id && pending.has(msg.id)) {
    pending.get(msg.id)(msg);
    pending.delete(msg.id);
  }
});

const send = (method, params = {}) =>
  new Promise((resolve) => {
    const id = ++seq;
    pending.set(id, resolve);
    ws.send(JSON.stringify({ id, method, params }));
  });

await new Promise((r) => ws.addEventListener('open', r));

const res = await send('Runtime.evaluate', {
  expression: expr,
  returnByValue: true,
  awaitPromise: true,
});

const out = res.result?.result?.value ?? res.result ?? res;
console.log(typeof out === 'string' ? out : JSON.stringify(out, null, 2));
ws.close();
