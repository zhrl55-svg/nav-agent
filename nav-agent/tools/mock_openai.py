"""本地 OpenAI 兼容 mock 服务，用来验证 App 的「对话大脑接本地 API」链路。

不是产品代码，只是自测工具：不连真模型，按 OpenAI 协议原样吐 SSE / JSON，
验证的是 App 这边的地址拼接、无 Key 请求、SSE 解析、ThinkFilter 剥除。

用法：
    python tools/mock_openai.py 8123
    adb reverse tcp:8123 tcp:8123
    App 里把接口地址填成 http://127.0.0.1:8123/v1
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

REPLY = "本地模型收到你的话。现在是接通了，后面就是换真模型的事。"

# 模拟「只会用文本发工具调用」的本地模型：带 tools 的那一轮吐 <tool_call> XML，
# 不带 tools 的那一轮（工具执行完）才正常回答。
TOOL_TEXT = (
    '<tool_call>{"name": "navigate", "arguments": {"action": "stop"}}</tool_call>'
)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("[mock] " + (fmt % args) + "\n")

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b"{}"
        try:
            body = json.loads(raw)
        except Exception:
            body = {}

        model = body.get("model", "?")
        stream = bool(body.get("stream"))
        tools = len(body.get("tools") or [])
        msgs = body.get("messages") or []
        sys.stderr.write(
            "[mock] path=%s model=%s stream=%s tools=%d msgs=%d auth=%s\n"
            % (self.path, model, stream, tools, len(msgs),
               "有" if self.headers.get("Authorization") else "无")
        )

        if not self.path.rstrip("/").endswith("/chat/completions"):
            self._json(404, {"error": {"message": "unknown path " + self.path}})
            return

        if not stream:
            self._json(200, {
                "id": "mock-1",
                "object": "chat.completion",
                "model": model,
                "choices": [{
                    "index": 0,
                    "message": {"role": "assistant", "content": REPLY},
                    "finish_reason": "stop",
                }],
                "usage": {"prompt_tokens": 8, "completion_tokens": 20},
            })
            return

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "keep-alive")
        self.end_headers()

        # 故意把回复切成小片，并且塞一段 <think>…</think>，验证 App 会剥掉思考内容
        body_text = TOOL_TEXT if tools > 0 else REPLY
        chunks = ["<think>这里是思考，不该被念出来</think>"] + [
            body_text[i:i + 6] for i in range(0, len(body_text), 6)
        ]
        for piece in chunks:
            data = {
                "id": "mock-1",
                "object": "chat.completion.chunk",
                "model": model,
                "choices": [{"index": 0, "delta": {"content": piece}, "finish_reason": None}],
            }
            self._sse(data)
        self._sse_raw("[DONE]")
        self.close_connection = True

    def _sse(self, obj):
        self._sse_raw(json.dumps(obj, ensure_ascii=False))

    def _sse_raw(self, text):
        self.wfile.write(("data: " + text + "\n\n").encode("utf-8"))
        self.wfile.flush()

    def _json(self, code, obj):
        payload = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8802
    # 绑 0.0.0.0：手机要能通过局域网 IP 连进来（绑 127.0.0.1 只有本机可达）
    srv = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    sys.stderr.write("[mock] listening on 0.0.0.0:%d\n" % port)
    srv.serve_forever()
