#!/usr/bin/env python3
"""A fake GitHub / Gitea / GitLab API for the smoke test.

It is deliberately strict about what it accepts, and it records every request, so the smoke test can check the URL and
the payload the atom actually sent -- not just that the atom exited 0. Two modes make the failure paths real:

  --lose-tail   store only the first ten characters of a comment (a proxy that truncates); the API still answers 201
  account liar  echo back a status whose state is not the one that was posted

Usage: fake-github.py <request-log> <port> [--lose-tail]
"""
import http.server
import json
import sys

LOG, PORT = sys.argv[1], int(sys.argv[2])
LOSE_TAIL = len(sys.argv) > 3 and sys.argv[3] == "--lose-tail"
COMMENTS = {}
GOOD_TOKEN = "good-token"
# a short sha that the API resolves to a full one: the atom must use what the API returns
SHAS = {"abcdef1": "abcdef1234567890abcdef1234567890abcdef12"}


class Handler(http.server.BaseHTTPRequestHandler):
    def _log(self, body):
        auth = self.headers.get("Authorization") or self.headers.get("PRIVATE-TOKEN") or "-"
        with open(LOG, "a", encoding="utf-8") as log:
            log.write("%s\t%s\t%s\t%s\n" % (self.command, self.path, auth, body))

    def _send(self, code, payload):
        raw = json.dumps(payload).encode()
        self.send_response(code)
        self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        self._log("")
        path = self.path.split("?")[0]
        parts = [p for p in path.split("/") if p]
        if path == "/user":
            if self.headers.get("Authorization") == "Bearer " + GOOD_TOKEN:
                return self._send(200, {"login": "ci"})
            return self._send(401, {"message": "Bad credentials"})
        if len(parts) == 3 and parts[0] == "repos":
            if parts[2] == "nope":
                return self._send(404, {"message": "Not Found"})
            return self._send(200, {"full_name": parts[1] + "/" + parts[2]})
        if len(parts) == 5 and parts[0] == "repos" and parts[3] == "commits":
            if parts[4].startswith("dead"):
                return self._send(404, {"message": "No commit found"})
            return self._send(200, {"sha": SHAS.get(parts[4], parts[4])})
        if len(parts) == 6 and parts[0] == "repos" and parts[3] == "issues" and parts[5] == "comments":
            return self._send(200, COMMENTS.get(parts[4], []))
        return self._send(404, {"message": "Not Found"})

    def do_POST(self):
        length = int(self.headers.get("content-length", 0))
        body = self.rfile.read(length).decode("utf-8")
        self._log(body)
        parts = [p for p in self.path.split("?")[0].split("/") if p]
        if len(parts) == 5 and parts[0] == "repos" and parts[3] == "statuses":
            sent = json.loads(body)
            echoed = dict(sent)
            if parts[1] == "liar":
                echoed["state"] = "pending"
            return self._send(201, echoed)
        if len(parts) == 6 and parts[0] == "repos" and parts[3] == "issues" and parts[5] == "comments":
            sent = json.loads(body)
            text = sent["body"][:10] if LOSE_TAIL else sent["body"]
            item = {"id": 7, "body": text, "user": {"login": "ci"}}
            COMMENTS.setdefault(parts[4], []).append(item)
            return self._send(201, item)
        if len(parts) == 4 and parts[0] == "projects" and parts[2] == "statuses":
            return self._send(201, {"id": 1})
        return self._send(404, {"message": "Not Found"})

    def log_message(self, *args):
        pass


# threads: the atom keeps its connection alive between requests, and one blocked accept would hang the run
http.server.ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
