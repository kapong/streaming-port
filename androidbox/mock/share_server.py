"""Serves the share page for design work on this Mac: the app's real page at / and the redesign mock at /new/.
/config answers like the TV does; nothing is sent anywhere. python3 -I streamport/mock/share_server.py

The watch page (the TV serves it over plain http on port 9080) is at http://127.0.0.1:8769/, with a made-up
/watch/status; /whep answers 503 (no TV here), or 403 with --watch-off, which also turns watching off in /config
and /watch/status. On a real TV no proxy is needed: http://BOX_IP:9080/ has no certificate.

With --box https://BOX_IP:9443 (port 8767), /config and /whip go to that TV instead, so the page can be tested
end to end from localhost (a secure context) without the TV's self-signed certificate in the browser."""
import http.server, json, os, ssl, sys, threading, urllib.request

ROOT = os.path.dirname(os.path.abspath(__file__))
REAL = os.path.join(ROOT, "..", "app", "src", "main", "assets", "share")
NEW = os.path.join(ROOT, "share")
WATCH = os.path.join(ROOT, "..", "app", "src", "main", "assets", "watch")
WATCHING = "--watch-off" not in sys.argv
CONFIG = {"name": "Meeting Room", "maxWidth": 1280, "maxHeight": 720, "fps": 30, "free": 3, "max": 4,
          "watch": WATCHING, "watchPort": 8769}
STATUS = {"name": "Meeting Room", "viewers": 3 if WATCHING else 0, "max": 20, "enabled": WATCHING, "sharePort": 8766}


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


class Handler(http.server.SimpleHTTPRequestHandler):
    def do_POST(self):
        if BOX and self.path.startswith("/whip"):
            return to_box(self, "POST")
        self.send_error(404)

    def do_DELETE(self):
        if BOX and self.path.startswith("/whip"):
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
        if BOX and self.path.split("?", 1)[0] == "/config":
            return to_box(self, "GET")
        if self.path.split("?", 1)[0] == "/config":
            body = json.dumps(CONFIG).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        super().do_GET()


def answer(handler, status, data, kind):
    handler.send_response(status)
    handler.send_header("Content-Type", kind)
    handler.send_header("Content-Length", str(len(data)))
    handler.end_headers()
    handler.wfile.write(data)


class WatchHandler(http.server.SimpleHTTPRequestHandler):
    def translate_path(self, path):
        path = path.split("?", 1)[0]
        if path == "/outfit.ttf":
            return os.path.join(ROOT, "outfit.ttf")
        if path == "/share.css":
            return os.path.join(REAL, "share.css")
        return os.path.join(WATCH, path.lstrip("/") or "index.html")

    def do_GET(self):
        if self.path.split("?", 1)[0] == "/watch/status":
            return answer(self, 200, json.dumps(STATUS).encode(), "application/json")
        super().do_GET()

    def do_POST(self):
        if self.path == "/whep":
            self.rfile.read(int(self.headers.get("Content-Length") or 0))
            if not WATCHING:
                return answer(self, 403, b"Watching is turned off on this TV", "text/plain")
            return answer(self, 503, b"No TV behind this mock", "text/plain")
        self.send_error(404)


threading.Thread(target=http.server.ThreadingHTTPServer(("127.0.0.1", 8769), WatchHandler).serve_forever,
                 daemon=True).start()
http.server.ThreadingHTTPServer(("127.0.0.1", 8767 if BOX else 8766), Handler).serve_forever()
