#!/usr/bin/env bash
# A local endpoint stands in for the GitHub / GitLab APIs. What matters is not "exit code 0" but:
# the URL, the body shape, the preflight the githubNotify step does, and what the API actually stored.
set -uo pipefail
cd "$(dirname "$0")/.."
export BUILD_NUMBER=99 JOB_NAME=parcel BUILD_STATUS=UNSTABLE BUILD_URL=http://jenkins/job/parcel/99/
./build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

work="$(mktemp -d)"
host=127.0.0.1
port=$(( 21000 + RANDOM % 9000 ))
port2=$(( port + 1 ))
api="http://$host:$port"
api2="http://$host:$port2"

start_server() {  # start_server <log> <port> [--lose-tail]
  python3 tools/fake-github.py "$1" "$2" "${3:-}" >"$work/server-$2.log" 2>&1 &
  echo $!
}
wait_server() {  # wait_server <base>
  for _ in $(seq 1 60); do
    # /user answers 401 without a token, so probe a route that is happy anonymously
    curl -fsS -o /dev/null "$1/repos/probe/repo" 2>/dev/null && return 0
    sleep 0.2
  done
  fail "the fake API at $1 did not come up"
}

received="$work/received.tsv"; : >"$received"
server=$(start_server "$received" "$port")
trap 'kill $server 2>/dev/null || true' EXIT
wait_server "$api"

# 1. GitHub, the way githubNotify does it: account + repo, short sha resolved by the API, UNSTABLE -> failure.
dir="$work/gh"; mkdir -p "$dir"
printf '{"provider": "github", "account": "acme", "repo": "parcel", "sha": "abcdef1", "apiUrl": "%s", "contextName": "parcel/ci"}' "$api" >"$dir/input.json"
printf '{"token": "good-token"}' >"$dir/.secrets.json"
run "$dir" >"$dir/log.txt" 2>&1 || fail "github run failed: $(cat "$dir/log.txt")"
[ "$(status "$dir")" = "success" ] || fail "not a success: $(cat "$dir/output.json")"
[ "$(output "$dir" sha)" = "abcdef1234567890abcdef1234567890abcdef12" ] || fail "the status must go on the sha the API returned: $(cat "$dir/output.json")"
grep -q "/repos/acme/parcel/statuses/abcdef1234567890abcdef1234567890abcdef12" "$received" || fail "wrong status path: $(cat "$received")"
grep -q '"state":"failure"' "$received" || fail "UNSTABLE must map to failure: $(cat "$received")"
grep -q '"context":"parcel/ci"' "$received" || fail "context missing from the payload"
grep -q 'Bearer good-token' "$received" || fail "token not sent"
grep -q "good-token" "$dir/log.txt" && fail "the token must not be in the log"
[ "$(output "$dir" verified)" = "failure" ] || fail "the echo was not checked: $(cat "$dir/output.json")"
grep -q "GET	/user" "$received" || fail "the preflight must check the credential"
grep -q "GET	/repos/acme/parcel/commits/abcdef1" "$received" || fail "the preflight must check the commit"

# 2. account/repo can still be written as one 'owner/name' — same requests, nothing else.
dir="$work/split"; mkdir -p "$dir"
printf '{"provider": "github", "repo": "acme/parcel", "sha": "abcdef1", "apiUrl": "%s"}' "$api" >"$dir/input.json"
printf '{"token": "good-token"}' >"$dir/.secrets.json"
run "$dir" >/dev/null 2>&1 || fail "the owner/name form must still work"
grep -q "POST	/repos/acme/parcel/statuses/abcdef1234567890" "$received" || fail "owner/name was not split"

# 3. GitLab keeps the old shape: form body, PRIVATE-TOKEN, no preflight (that API has no /user of this kind).
dir="$work/gl"; mkdir -p "$dir"
printf '{"provider": "gitlab", "repo": "group/parcel", "sha": "deadbeef", "apiUrl": "%s"}' "$api" >"$dir/input.json"
printf '{"token": "gl-token"}' >"$dir/.secrets.json"
run "$dir" >/dev/null 2>&1 || fail "gitlab run failed"
grep -q "/projects/group%2Fparcel/statuses/deadbeef" "$received" || fail "gitlab path: $(tail -1 "$received")"
grep -q "state=failed" "$received" || fail "gitlab state mapping: $(tail -1 "$received")"
grep -q "gl-token" "$received" || fail "PRIVATE-TOKEN header missing"

# 4. GitHub with no credential: a user error, before any request.
dir="$work/notoken"; mkdir -p "$dir"
printf '{"provider": "github", "repo": "acme/parcel", "sha": "abcdef1", "apiUrl": "%s"}' "$api" >"$dir/input.json"
run "$dir" >/dev/null 2>&1 && fail "a missing token must fail"
grep -q '"errorCode":40002' "$dir/output.json" || fail "not a user error: $(cat "$dir/output.json")"
grep -q "token" "$dir/output.json" || fail "message not readable"

# 5. A rejected credential is its own message, not "the API answered 401" (that is what githubNotify says).
dir="$work/badtoken"; mkdir -p "$dir"
printf '{"provider": "github", "repo": "acme/parcel", "sha": "abcdef1", "apiUrl": "%s"}' "$api" >"$dir/input.json"
printf '{"token": "bad"}' >"$dir/.secrets.json"
run "$dir" >/dev/null 2>&1 && fail "a rejected credential must fail"
grep -q "invalid to login" "$dir/output.json" || fail "wrong message: $(cat "$dir/output.json")"
grep -q '"errorCode":40006' "$dir/output.json" || fail "wrong code: $(cat "$dir/output.json")"

# 6. A repository that does not exist must not be reported as a status failure.
dir="$work/norepo"; mkdir -p "$dir"
printf '{"provider": "github", "account": "acme", "repo": "nope", "sha": "abcdef1", "apiUrl": "%s"}' "$api" >"$dir/input.json"
printf '{"token": "good-token"}' >"$dir/.secrets.json"
run "$dir" >/dev/null 2>&1 && fail "a 404 repository must fail"
grep -q "does not exist for the specified account" "$dir/output.json" || fail "wrong message: $(cat "$dir/output.json")"
grep -q '"errorCode":40007' "$dir/output.json" || fail "wrong code: $(cat "$dir/output.json")"

# 7. A commit that is not in the repository: same idea, and no status is posted for it.
before=$(grep -c "POST" "$received")
dir="$work/nocommit"; mkdir -p "$dir"
printf '{"provider": "github", "account": "acme", "repo": "parcel", "sha": "deadbeef", "apiUrl": "%s"}' "$api" >"$dir/input.json"
printf '{"token": "good-token"}' >"$dir/.secrets.json"
run "$dir" >/dev/null 2>&1 && fail "a 404 commit must fail"
grep -q "does not exist in the specified repository" "$dir/output.json" || fail "wrong message: $(cat "$dir/output.json")"
grep -q '"errorCode":40008' "$dir/output.json" || fail "wrong code: $(cat "$dir/output.json")"
[ "$(grep -c "POST" "$received")" = "$before" ] || fail "a status was posted for a commit that does not exist"

# 8. 201 with a body that says something else is not a success.
dir="$work/liar"; mkdir -p "$dir"
printf '{"provider": "github", "account": "liar", "repo": "parcel", "sha": "abcdef1", "apiUrl": "%s"}' "$api" >"$dir/input.json"
printf '{"token": "good-token"}' >"$dir/.secrets.json"
run "$dir" >/dev/null 2>&1 && fail "a 201 that echoes another state must fail"
grep -q '"errorCode":40009' "$dir/output.json" || fail "wrong code: $(cat "$dir/output.json")"

# 9. A PR comment: posted, then read back from the list, and the id comes from what the API stored.
dir="$work/comment"; mkdir -p "$dir"
printf '{"provider": "github", "account": "acme", "repo": "parcel", "sha": "abcdef1", "prNumber": "12", "prComment": "packaged build ${BUILD_NUMBER} on ${JOB_NAME}", "apiUrl": "%s"}' "$api" >"$dir/input.json"
printf '{"token": "good-token"}' >"$dir/.secrets.json"
run "$dir" >"$dir/log.txt" 2>&1 || fail "comment run failed: $(cat "$dir/log.txt")"
[ "$(status "$dir")" = "success" ] || fail "comment: not a success: $(cat "$dir/output.json")"
[ "$(output "$dir" commentId)" = "7" ] || fail "comment id not read back: $(cat "$dir/output.json")"
grep -q "POST	/repos/acme/parcel/issues/12/comments" "$received" || fail "comment posted to the wrong place"
grep -q '"body":"packaged build 99 on parcel"' "$received" || fail "the comment body was not expanded/sent: $(tail -2 "$received")"
grep -q "GET	/repos/acme/parcel/issues/12/comments" "$received" || fail "the comment must be read back"

# 10. 201 but the server kept only part of the comment: the read-back is the only thing that catches it.
received2="$work/lost.tsv"; : >"$received2"
server2=$(start_server "$received2" "$port2" --lose-tail)
wait_server "$api2"
dir="$work/losttail"; mkdir -p "$dir"
printf '{"provider": "github", "account": "acme", "repo": "parcel", "sha": "abcdef1", "prNumber": "12", "prComment": "packaged build ${BUILD_NUMBER} on ${JOB_NAME}", "apiUrl": "%s"}' "$api2" >"$dir/input.json"
printf '{"token": "good-token"}' >"$dir/.secrets.json"
run "$dir" >/dev/null 2>&1 && fail "a silently shortened comment must fail the atom"
grep -q '"errorCode":40010' "$dir/output.json" || fail "wrong code: $(cat "$dir/output.json")"
grep -q "not stored as written" "$dir/output.json" || fail "wrong message: $(cat "$dir/output.json")"
kill $server2 2>/dev/null || true

# 11. prComment on GitLab is refused up front: that API is notes, not issue comments.
dir="$work/glcomment"; mkdir -p "$dir"
printf '{"provider": "gitlab", "repo": "group/parcel", "sha": "deadbeef", "prComment": "hi", "apiUrl": "%s"}' "$api" >"$dir/input.json"
printf '{"token": "gl-token"}' >"$dir/.secrets.json"
run "$dir" >/dev/null 2>&1 && fail "prComment on gitlab must be refused"
grep -q '"errorCode":40011' "$dir/output.json" || fail "wrong code: $(cat "$dir/output.json")"

# 12. A non-numeric pull request number is a user error before any request.
dir="$work/badpr"; mkdir -p "$dir"
printf '{"provider": "github", "account": "acme", "repo": "parcel", "sha": "abcdef1", "prNumber": "main", "prComment": "hi", "apiUrl": "%s"}' "$api" >"$dir/input.json"
printf '{"token": "good-token"}' >"$dir/.secrets.json"
run "$dir" >/dev/null 2>&1 && fail "a non-numeric PR must be refused"

# 13. preflight=false: one POST, no GET (an escape hatch for APIs that do not serve /user).
dir="$work/nopreflight"; mkdir -p "$dir"
printf '{"provider": "github", "account": "acme", "repo": "parcel", "sha": "abcdef1", "preflight": "false", "apiUrl": "%s"}' "$api" >"$dir/input.json"
printf '{"token": "good-token"}' >"$dir/.secrets.json"
run "$dir" >"$dir/log.txt" 2>&1 || fail "preflight=false must still post the status: $(cat "$dir/log.txt")"
grep -q "GET" "$dir/log.txt" && fail "preflight=false still made a GET: $(cat "$dir/log.txt")"

# 14. With no repo at all, GIT_URL is used the way the step infers it from the checkout.
dir="$work/giturl"; mkdir -p "$dir"
printf '{"provider": "github", "sha": "abcdef1", "apiUrl": "%s"}' "$api" >"$dir/input.json"
printf '{"token": "good-token"}' >"$dir/.secrets.json"
run "$dir" GIT_URL=git@github.com:acme/parcel.git >/dev/null 2>&1 || fail "GIT_URL inference failed: $(cat "$dir/output.json")"
grep -q "POST	/repos/acme/parcel/statuses/abcdef1234567890" "$received" || fail "GIT_URL was not used"

# 15. An API error on the status write is reported with the code, not swallowed.
dir="$work/bad"; mkdir -p "$dir"
printf '{"provider": "gitlab", "repo": "group/parcel", "sha": "deadbeef", "apiUrl": "%s/nope"}' "$api" >"$dir/input.json"
printf '{"token": "gl-token"}' >"$dir/.secrets.json"
run "$dir" >/dev/null 2>&1 && fail "a 404 from the API must fail the atom"
grep -q "404" "$dir/output.json" || fail "the status code is not in the message: $(cat "$dir/output.json")"

echo "smoke ok: preflight (credential/repo/commit) + canonical sha, owner/name split, gitlab form, echo check, PR comment posted+read back, shortened comment rejected, GIT_URL inference"
