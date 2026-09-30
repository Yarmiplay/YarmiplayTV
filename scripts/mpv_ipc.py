#!/usr/bin/env python3
"""Drive a desktop mpv instance (e.g. the one launched by desktop Syncplay) over its JSON IPC pipe.

Usage:
  python scripts/mpv_ipc.py [--pipe NAME] get time-pos
  python scripts/mpv_ipc.py pause | play | seek 300 | get pause

If --pipe is omitted, the pipe is taken from the running mpv.exe command line
(--input-ipc-server=...), which is how desktop Syncplay starts mpv.
"""
import argparse
import json
import re
import subprocess
import sys


def find_pipe() -> str:
    out = subprocess.run(
        ["powershell", "-NoProfile", "-Command",
         "(Get-CimInstance Win32_Process -Filter \"Name='mpv.exe'\").CommandLine"],
        capture_output=True, text=True, check=False).stdout
    m = re.search(r"--input-ipc-server=(\S+)", out)
    if not m:
        sys.exit("no mpv.exe with --input-ipc-server found")
    return m.group(1)


def send(pipe: str, command: list):
    with open(pipe, "r+b", buffering=0) as f:
        f.write((json.dumps({"command": command, "request_id": 1}) + "\n").encode())
        while True:
            line = f.readline()
            if not line:
                return None
            msg = json.loads(line)
            if msg.get("request_id") == 1:
                return msg


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pipe")
    ap.add_argument("action", choices=["get", "set", "pause", "play", "seek"])
    ap.add_argument("args", nargs="*")
    a = ap.parse_args()
    pipe = a.pipe or find_pipe()
    if a.action == "get":
        cmd = ["get_property", a.args[0]]
    elif a.action == "set":
        cmd = ["set_property", a.args[0], json.loads(a.args[1])]
    elif a.action == "pause":
        cmd = ["set_property", "pause", True]
    elif a.action == "play":
        cmd = ["set_property", "pause", False]
    else:
        cmd = ["seek", float(a.args[0]), "absolute"]
    reply = send(pipe, cmd)
    print(json.dumps(reply.get("data") if reply else None))
    if reply and reply.get("error") != "success":
        sys.exit(reply.get("error"))


if __name__ == "__main__":
    main()
