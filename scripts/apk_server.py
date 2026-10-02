#!/usr/bin/env python3
"""
Serves the SyncplayTV APK on the local network so a real TV, phone or tablet can download and install it.

  python scripts/apk_server.py [--apk app/build/outputs/apk/debug/app-debug.apk] [--port 8080]

On the TV, open http://<this-pc>:<port>/ in a browser or the "Downloader" app,
or enter http://<this-pc>:<port>/a to download the APK directly. On a phone or tablet,
open the page in the browser. The page is the same one GitHub Pages shows (scripts/download_site.py).
Standard library only.
"""
import argparse
import datetime
import os
import socket
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from download_site import Download, by_platform, render_page

APK_MIME = "application/vnd.android.package-archive"
DOWNLOAD_NAME = "SyncplayTV.apk"


def lan_addresses():
    addrs = []
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("10.255.255.255", 1))  # no packet is sent; picks the outgoing interface
            addrs.append(s.getsockname()[0])
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if ip not in addrs and not ip.startswith("127.") and not ip.startswith("169.254."):
                addrs.append(ip)
    except OSError:
        pass
    return addrs or ["127.0.0.1"]


def make_handler(apk_path, version):
    class Handler(BaseHTTPRequestHandler):
        server_version = "SyncplayTV-APK"

        def do_HEAD(self):
            self.route(send_body=False)

        def do_GET(self):
            self.route(send_body=True)

        def route(self, send_body):
            path = self.path.split("?", 1)[0].rstrip("/") or "/"
            if path in ("/a", "/apk", "/" + DOWNLOAD_NAME.lower(), "/" + DOWNLOAD_NAME, "/syncplaytv.apk"):
                self.send_apk(send_body)
            elif path == "/":
                self.send_page(send_body)
            else:
                self.send_error(404)

        def send_apk(self, send_body):
            if not os.path.isfile(apk_path):
                self.send_error(503, "APK not built yet")
                return
            size = os.path.getsize(apk_path)
            self.send_response(200)
            self.send_header("Content-Type", APK_MIME)
            self.send_header("Content-Length", str(size))
            self.send_header("Content-Disposition", f'attachment; filename="{DOWNLOAD_NAME}"')
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            if send_body:
                with open(apk_path, "rb") as f:
                    while chunk := f.read(256 * 1024):
                        self.wfile.write(chunk)
                print(f"  -> sent APK ({size / 1e6:.1f} MB) to {self.client_address[0]}", flush=True)

        def send_page(self, send_body):
            downloads, built = {}, "not yet"
            if os.path.isfile(apk_path):
                st = os.stat(apk_path)
                built = datetime.datetime.fromtimestamp(st.st_mtime).strftime("%Y-%m-%d %H:%M")
                downloads = by_platform({".apk": Download("/" + DOWNLOAD_NAME, "APK", st.st_size)})
            host = self.headers.get("Host")
            short_link = f"http://{host}/a" if host else None
            body = render_page(downloads, version, built, short_link=short_link).encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            if send_body:
                self.wfile.write(body)

        def log_message(self, fmt, *args):
            print(f"[{self.log_date_time_string()}] {self.client_address[0]} {fmt % args}", flush=True)

    return Handler


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", default=os.path.join(root, "app", "build", "outputs", "apk", "debug", "app-debug.apk"))
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--version", default="debug")
    a = ap.parse_args()
    if not os.path.isfile(a.apk):
        print(f"warning: {a.apk} does not exist yet (build with ./gradlew :app:assembleDebug)", file=sys.stderr)
    # On Windows SO_REUSEADDR lets a second server bind a port that's already in use, and the old one keeps
    # answering; without it a stale server makes this fail loudly instead.
    ThreadingHTTPServer.allow_reuse_address = sys.platform != "win32"
    try:
        server = ThreadingHTTPServer(("0.0.0.0", a.port), make_handler(a.apk, a.version))
    except OSError as e:
        sys.exit(f"cannot listen on port {a.port}: {e} (try --port 8090)")
    print("SyncplayTV APK server running. On the TV, phone or tablet open one of:")
    for i, ip in enumerate(lan_addresses()):
        note = "   <- most likely your LAN" if i == 0 else ""
        print(f"  http://{ip}:{a.port}/   (page)   http://{ip}:{a.port}/a   (direct download){note}")
    print("Ctrl+C to stop.", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
