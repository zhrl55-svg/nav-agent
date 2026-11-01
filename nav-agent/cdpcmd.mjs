// 通用 CDP 命令执行器
// 用法: node cdpcmd.mjs '[{"eval":"document.title"},{"method":"Input.dispatchMouseEvent","params":{...}}]'
const port = process.env.CDP_PORT || 9222;
const steps = JSON.parse(process.argv[2] || '[]');

const targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
const page = targets.find((t) => t.type === 'page');
if (!page) {
  console.error('no page target');
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

for (const step of steps) {
  if (step.eval) {
    const res = await send('Runtime.evaluate', {
      expression: step.eval,
      returnByValue: true,
      awaitPromise: true,
    });
    const v = res.result?.result?.value;
    if (v !== undefined) console.log(typeof v === 'string' ? v : JSON.stringify(v));
    if (res.result?.exceptionDetails) {
      console.log('EXCEPTION:', JSON.stringify(res.result.exceptionDetails).slice(0, 300));
    }
  } else if (step.method) {
    const res = await send(step.method, step.params || {});
    if (res.error) console.log('CDP-ERROR:', JSON.stringify(res.error).slice(0, 200));
  } else if (step.screenshot) {
    const res = await send('Page.captureScreenshot', { format: 'png' });
    if (res.result?.data) {
      const { writeFileSync } = await import('node:fs');
      writeFileSync(step.screenshot, Buffer.from(res.result.data, 'base64'));
      console.log('saved', step.screenshot);
    } else {
      console.log('SCREENSHOT-FAIL', JSON.stringify(res).slice(0, 200));
    }
  } else if (step.clickSel || step.clickText) {
    // 用可信鼠标事件点击元素中心（antd 等库只认 isTrusted 事件）
    const findExpr = step.clickSel
      ? `document.querySelector(${JSON.stringify(step.clickSel)})`
      : `[...document.querySelectorAll('div,span,li,a,button,label')].find(e=>e.innerText.trim()===${JSON.stringify(step.clickText)}&&e.getBoundingClientRect().width>0)`;
    const r = await send('Runtime.evaluate', {
      expression: `(()=>{const e=${findExpr};if(!e)return null;const b=e.getBoundingClientRect();return {x:Math.round(b.x+b.width/2),y:Math.round(b.y+b.height/2)};})()`,
      returnByValue: true,
    });
    const pos = r.result?.result?.value;
    if (!pos) {
      console.log('CLICK-MISS:', step.clickSel || step.clickText);
      continue;
    }
    for (const type of ['mousePressed', 'mouseReleased']) {
      await send('Input.dispatchMouseEvent', {
        type,
        x: pos.x,
        y: pos.y,
        button: 'left',
        clickCount: 1,
      });
    }
    console.log('clicked', step.clickSel || step.clickText, 'at', pos.x, pos.y);
  } else if (step.sleep) {
    await new Promise((r) => setTimeout(r, step.sleep));
  }
}

ws.close();
