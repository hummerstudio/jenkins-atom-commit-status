#!/usr/bin/env bash
# A local endpoint stands in for the three APIs: what matters is the URL, the body shape and the state mapping.
set -uo pipefail
cd "$(dirname "$0")/.."
export BUILD_NUMBER=9 JOB_NAME=parcel BUILD_STATUS=UNSTABLE BUILD_URL=http://jenkins/job/parcel/9/
./build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

work="$(mktemp -d)"
port=$(( 21000 + RANDOM % 9000 ))
cat >"$work/server.py" <<'PY'
import http.server, sys

class Handler(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get('content-length', 0))
        body = self.rfile.read(length).decode('utf-8')
        with open(sys.argv[1], 'a', encoding='utf-8') as log:
            log.write(f"{self.path}\t{self.headers.get('PRIVATE-TOKEN') or self.headers.get('Authorization')}\t{body}\n")
        code = 404 if 'nope' in self.path else 201
        self.send_response(code)
        self.send_header('content-length', '2')
        self.end_headers()
        self.wfile.write(b'{}')

    def log_message(self, *args):
        pass

http.server.HTTPServer(('127.0.0.1', int(sys.argv[2])), Handler).serve_forever()
PY
received="$work/received.tsv"
python3 "$work/server.py" "$received" "$port" &
server=$!
trap 'kill $server 2>/dev/null || true' EXIT
for _ in $(seq 1 60); do curl -fsS -o /dev/null "http://127.0.0.1:$port/" 2>/dev/null && break; sleep 0.2; done

# 1. GitHub: /repos/{repo}/statuses/{sha}, JSON, UNSTABLE -> failure
dir="$work/gh"; mkdir -p "$dir"
printf '{"provider": "github", "repo": "acme/parcel", "sha": "abcdef1234567890", "apiUrl": "http://127.0.0.1:%s",\n "contextName": "parcel/ci"}' "$port" >"$dir/input.json"
printf '{"token": "smoke-token"}' >"$dir/.secrets.json"
run "$dir" >"$dir/log.txt" 2>&1 || fail "github run failed: $(cat "$dir/log.txt")"
[ "$(status "$dir")" = "success" ] || fail "not a success: $(cat "$dir/output.json")"
grep -q "/repos/acme/parcel/statuses/abcdef1234567890" "$received" || fail "wrong path: $(cat "$received")"
grep -q '"state":"failure"' "$received" || fail "UNSTABLE must map to failure: $(cat "$received")"
grep -q "Bearer smoke-token" "$received" || fail "token not sent"
grep -q "smoke-token" "$dir/log.txt" && fail "the token must not be in the log"

# 2. GitLab: form body, PRIVATE-TOKEN header, UNSTABLE -> failed
dir="$work/gl"; mkdir -p "$dir"
printf '{"provider": "gitlab", "repo": "group/parcel", "sha": "deadbeef", "apiUrl": "http://127.0.0.1:%s"}' "$port" >"$dir/input.json"
printf '{"token": "gl-token"}' >"$dir/.secrets.json"
run "$dir" >/dev/null 2>&1 || fail "gitlab run failed"
grep -q "/projects/group%2Fparcel/statuses/deadbeef" "$received" || fail "gitlab path: $(tail -1 "$received")"
grep -q "state=failed" "$received" || fail "gitlab state mapping: $(tail -1 "$received")"
grep -q "gl-token" "$received" || fail "PRIVATE-TOKEN header missing"

# 3. an API error is reported, not swallowed
dir="$work/bad"; mkdir -p "$dir"
printf '{"provider": "github", "repo": "acme/nope", "sha": "abc", "apiUrl": "http://127.0.0.1:%s"}' "$port" >"$dir/input.json"
printf '{"token": "smoke-token"}' >"$dir/.secrets.json"
run "$dir" >/dev/null 2>&1 && fail "a 404 from the API must fail the atom"
grep -q "404" "$dir/output.json" || fail "the status code is not in the message: $(cat "$dir/output.json")"

# 4. missing credential is a user error with a readable message
dir="$work/notoken"; mkdir -p "$dir"
printf '{"provider": "github", "repo": "acme/parcel", "sha": "abc"}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 && fail "a missing token must fail"
grep -q "token" "$dir/output.json" || fail "message not readable"

echo "smoke ok: github path+json, gitlab path+form, api error, missing token"
