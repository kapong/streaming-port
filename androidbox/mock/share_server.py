"""Serves the share page for design work on this Mac: the app's real page at / and the redesign mock at /new/.
/config answers like the TV does; nothing is sent anywhere. python3 -I streamport/mock/share_server.py

Watching the TV on the same page: a made-up /watch/status, and /whep answers 503 (no TV here), or 403 with
--watch-off, which also turns watching off in /config and /watch/status.

With --box https://BOX_IP:9443 (port 8767), /config, /watch/status, /whip and /whep go to that TV instead, so the
page can be tested
end to end from localhost (a secure context) without the TV's self-signed certificate in the browser."""
import http.server, json, os, ssl, sys, urllib.request

ROOT = os.path.dirname(os.path.abspath(__file__))
REAL = os.path.join(ROOT, "..", "app", "src", "main", "assets", "share")
NEW = os.path.join(ROOT, "share")
WATCHING = "--watch-off" not in sys.argv
CONFIG = {"name": "Meeting Room", "maxWidth": 1280, "maxHeight": 720, "fps": 30, "free": 3, "max": 4,
          "watch": WATCHING}
STATUS = {"viewers": 3 if WATCHING else 0, "max": 20, "enabled": WATCHING}


BOX = sys.argv[sys.argv.index("--box") + 1] if "--box" in sys.argv else None


def to_box(handler, method):
    """Forwards one request to the TV (its certificate is self-signed: not checked, LAN test only)."""
    length = int(handler.headers.get("Content-Length") or 0)
    body = handler.rfile.read(length) if length else None
    req = urllib.request.Request(BOX + handler.path, data=body, method=method)
    if handler.headers.get("Content-Type"):
        req.add_header("Content-Type", handler.headers["Content-Type"])
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    try:
        r = urllib.request.urlopen(req, context=ctx, timeout=15)
    except urllib.error.HTTPError as e:
        r = e
    data = r.read()
    handler.send_response(r.status if hasattr(r, "status") else r.code)
    for k in ("Content-Type", "Location"):
        if r.headers.get(k):
            handler.send_header(k, r.headers[k])
    handler.send_header("Content-Length", str(len(data)))
    handler.end_headers()
    handler.wfile.write(data)


def answer(handler, status, data, kind, headers=()):
    handler.send_response(status)
    handler.send_header("Content-Type", kind)
    for k, v in headers:
        handler.send_header(k, v)
    handler.send_header("Content-Length", str(len(data)))
    handler.end_headers()
    handler.wfile.write(data)


class Handler(http.server.SimpleHTTPRequestHandler):
    def do_POST(self):
        if BOX and self.path.startswith(("/whip", "/whep")):
            return to_box(self, "POST")
        if self.path == "/whep":
            self.rfile.read(int(self.headers.get("Content-Length") or 0))
            if not WATCHING:
                return answer(self, 403, b"Watching is turned off on this TV", "text/plain")
            return answer(self, 503, b"No TV behind this mock", "text/plain", [("Retry-After", "5")])
        self.send_error(404)

    def do_DELETE(self):
        if BOX and self.path.startswith(("/whip", "/whep")):
            return to_box(self, "DELETE")
        self.send_error(404)

    def translate_path(self, path):
        path = path.split("?", 1)[0]
        if path == "/outfit.ttf":
            return os.path.join(ROOT, "outfit.ttf")
        if path.startswith("/new/"):
            return os.path.join(NEW, path[len("/new/"):] or "index.html")
        return os.path.join(REAL, path.lstrip("/") or "index.html")

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if BOX and path in ("/config", "/watch/status"):
            return to_box(self, "GET")
        if path == "/config":
            return answer(self, 200, json.dumps(CONFIG).encode(), "application/json")
        if path == "/watch/status":
            return answer(self, 200, json.dumps(STATUS).encode(), "application/json")
        super().do_GET()


http.server.ThreadingHTTPServer(("127.0.0.1", 8767 if BOX else 8766), Handler).serve_forever()
