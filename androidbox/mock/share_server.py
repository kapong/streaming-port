"""Serves the share page for design work on this Mac: the app's real page at / and the redesign mock at /new/.
/config answers like the TV does; nothing is sent anywhere. python3 -I streamport/mock/share_server.py

With --box https://192.168.1.23:9443 (port 8767), /config and /whip go to that TV instead, so the page can be tested
end to end from localhost (a secure context) without the TV's self-signed certificate in the browser."""
import http.server, json, os, ssl, sys, urllib.request

ROOT = os.path.dirname(os.path.abspath(__file__))
REAL = os.path.join(ROOT, "..", "app", "src", "main", "assets", "share")
NEW = os.path.join(ROOT, "share")
CONFIG = {"name": "NAIL'EM", "maxWidth": 1280, "maxHeight": 720, "fps": 30, "free": 3, "max": 4, "rebroadcast": True}


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


http.server.ThreadingHTTPServer(("127.0.0.1", 8767 if BOX else 8766), Handler).serve_forever()
