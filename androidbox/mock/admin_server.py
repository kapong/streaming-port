"""Serves the admin page on this Mac with a made-up TV behind it (4 senders), for design work and screenshots.
Nothing reaches a TV. python3 -I androidbox/mock/admin_server.py, then open http://127.0.0.1:8768"""
import http.server, json, os

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "assets", "admin")
STATE = {
    "name": "Meeting Room", "boxName": "Meeting Room", "canRenameBox": True, "miracast": True, "layout": "dynamic",
    "primary": 1, "max": 4, "maxStreams": 4, "maxStreamsLimit": 8, "resolution": "720p",
    "maxStreamsFor": {"720p": 8, "1080p": 4, "1440p": 2, "4k": 1},
    "rebroadcast": {"enabled": True, "live": True, "mirroring": True, "url": "udp://@239.255.42.42:5004"},
    "senders": [
        {"id": 1, "kind": "AirPlay", "name": "Weekly meeting", "model": "Mac14,2", "address": "192.168.1.31",
         "width": 1280, "height": 720, "waiting": False, "quality": "good", "fps": 30, "mbps": 2.1, "jitterMs": 4},
        {"id": 2, "kind": "Browser", "name": "Code review", "model": "Chrome", "address": "192.168.1.44",
         "width": 1280, "height": 720, "waiting": False, "quality": "good", "fps": 30, "mbps": 1.4, "jitterMs": 6},
        {"id": 3, "kind": "AirPlay", "name": "iPhone", "model": "iPhone16,1", "address": "192.168.1.52",
         "width": 590, "height": 1280, "waiting": False, "quality": "fair", "fps": 24, "mbps": 0.9, "jitterMs": 18},
        {"id": 4, "kind": "Browser", "name": "Dashboard", "model": "Safari", "address": "192.168.1.60",
         "width": 1280, "height": 720, "waiting": False, "quality": "good", "fps": 30, "mbps": 0.6, "jitterMs": 5},
    ],
}


class Handler(http.server.SimpleHTTPRequestHandler):
    def translate_path(self, path):
        return os.path.join(ROOT, path.split("?", 1)[0].lstrip("/") or "index.html")

    def answer(self, body):
        data = json.dumps(body).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path.startswith("/api/"):
            return self.answer(STATE)
        super().do_GET()

    def do_POST(self):
        self.rfile.read(int(self.headers.get("Content-Length") or 0))
        self.answer({})     # every change "works" and nothing changes


http.server.ThreadingHTTPServer(("127.0.0.1", 8768), Handler).serve_forever()
