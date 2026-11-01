#!/usr/bin/env python3
"""Break the atom on purpose and check the smoke test notices.

A green smoke test means nothing until you have seen it go red. Each mutation is a plausible bug -- trust the 201,
post on the sha you were given, skip a preflight, ignore what the API echoed -- and each must be caught by the
assertion written for it. Every mutation runs in a copy of the repository, so the working tree is never touched.

Usage: python3 tools/mutations.py
"""
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
SDK_JAR = os.environ.get("SDK_JAR") or os.path.join(
    os.path.dirname(REPO), "jenkins-atom-sdk/target/jenkins-atom-sdk-0.5.1.jar")

MUTATIONS = [
    (
        "trust the 201 for the comment",
        '            if (commentId.isEmpty()) {',
        '            commentId = commentId.isEmpty() ? "7" : commentId;\n            if (false) {',
        "a silently shortened comment must fail the atom",
    ),
    (
        "post on the sha the caller gave",
        "                postedSha = canonical;",
        "                postedSha = sha;",
        "the status must go on the sha the API returned",
    ),
    (
        "skip the commit preflight",
        "            if (commit.code == 404 || commit.code == 422) {",
        "            if (false) {",
        "wrong message:",
    ),
    (
        "ignore what the status echoed",
        "            if (!echoed.equalsIgnoreCase(mapped)) {",
        "            if (false) {",
        "a 201 that echoes another state must fail",
    ),
]


def run(cwd, command, env=None):
    return subprocess.run(command, cwd=cwd, shell=True, capture_output=True, text=True, env=env)


def main():
    if not os.path.exists(SDK_JAR):
        sys.exit("no SDK jar at %s; set SDK_JAR" % SDK_JAR)
    failures = 0
    for name, old, new, expected in MUTATIONS:
        work = tempfile.mkdtemp(prefix="cs-mutant-")
        clone = os.path.join(work, "repo")
        shutil.copytree(REPO, clone, ignore=shutil.ignore_patterns(".git", "dist", "target"))
        path = os.path.join(clone, "src/main/java/com/jenkinschina/atom/commitstatus/CommitStatusAtom.java")
        source = open(path, encoding="utf-8").read()
        if old not in source:
            print("SKIP   %-36s the pattern moved; update tools/mutations.py" % name)
            failures += 1
            shutil.rmtree(work, ignore_errors=True)
            continue
        open(path, "w", encoding="utf-8").write(source.replace(old, new, 1))
        env = dict(os.environ, SDK_JAR=SDK_JAR)
        result = run(clone, "./tools/smoke.sh", env)
        output = result.stdout + result.stderr
        if result.returncode == 0:
            print("MISSED %-36s the smoke test stayed green" % name)
            failures += 1
        elif expected not in output:
            print("WRONG  %-36s failed somewhere else: %s" % (name, output.strip().splitlines()[-1:]))
            failures += 1
        else:
            print("caught %-36s -> %s" % (name, expected))
        shutil.rmtree(work, ignore_errors=True)
    if failures:
        sys.exit("%d mutation(s) were not caught by the assertion they were meant to break" % failures)
    print("mutations ok: %d planted bugs, all caught" % len(MUTATIONS))


if __name__ == "__main__":
    main()
