#!/usr/bin/env python3
"""Plant one bug at a time and require the integration test to go red.

A green `mvn verify` proves nothing until you have seen it fail. Each mutation is a plausible mistake -- trust the 201,
post on the sha you were given, skip a preflight, ignore what the API echoed -- and the JUnit scenario written for it
must be the one that fails. Every mutation runs in a copy of the repository, so the working tree is never touched.

The copy is built with Maven: `mvn -B -ntp -Dit.test=CommitStatusIT verify` packages target/github.jar (the same jar
`mvn -q -DskipTests package` produces) and then runs the suite against it.

Usage: python3 tools/mutations.py
"""
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile

REPO = pathlib.Path(__file__).resolve().parent.parent
ATOM = "src/main/java/com/jenkinschina/atom/github/GithubAtom.java"

# (name, old text, new text, the test that must be the one failing)
MUTATIONS = [
    (
        "trust-the-201-for-the-comment",
        "            if (commentId.isEmpty()) {",
        '            commentId = commentId.isEmpty() ? "7" : commentId;\n            if (false) {',
        "aTruncatedCommentIsRejected",
    ),
    (
        "post-on-the-sha-the-caller-gave",
        "                postedSha = canonical;",
        "                postedSha = sha;",
        "githubPreflightChecksCredentialRepositoryAndCommit",
    ),
    (
        "skip-the-commit-preflight",
        "            if (commit.code == 404 || commit.code == 422) {",
        "            if (false) {",
        "missingCommitIsRefusedWithoutPosting",
    ),
    (
        "ignore-what-the-status-echoed",
        "            if (!echoed.equalsIgnoreCase(mapped)) {",
        "            if (false) {",
        "anInconsistentEchoIsNotASuccess",
    ),
]


def maven():
    """MAVEN_HOME wins on a workstation where mvn is not on PATH."""
    home = os.environ.get("MAVEN_HOME")
    if home and os.path.exists(os.path.join(home, "bin", "mvn")):
        return os.path.join(home, "bin", "mvn")
    return shutil.which("mvn") or "mvn"


def run(name, old, new, test):
    work = pathlib.Path(tempfile.mkdtemp(prefix="mutate-" + name + "-"))
    clone = work / "repo"
    shutil.copytree(REPO, clone, ignore=shutil.ignore_patterns(".git", "dist", "target", "*.class"))
    path = clone / ATOM
    source = path.read_text(encoding="utf-8")
    if old not in source:
        print("SKIP   %-36s the pattern moved; update tools/mutations.py" % name)
        shutil.rmtree(work, ignore_errors=True)
        return None
    path.write_text(source.replace(old, new, 1), encoding="utf-8")

    result = subprocess.run([maven(), "-B", "-ntp", "-Dit.test=CommitStatusIT", "verify"],
                            cwd=clone, capture_output=True, text=True)
    output = result.stdout + result.stderr
    if result.returncode == 0:
        print("MISSED %-36s the suite stayed green with the bug in place" % name)
        shutil.rmtree(work, ignore_errors=True)
        return False

    failures = [line.strip() for line in output.splitlines()
                if line.strip().startswith("[ERROR]") and "CommitStatusIT." in line]
    caught = any(("CommitStatusIT." + test) in line for line in failures)
    if not caught:
        print("WRONG  %-36s failed somewhere else: %s"
              % (name, failures[-1][:150] if failures else output.strip().splitlines()[-1]))
        shutil.rmtree(work, ignore_errors=True)
        return False
    print("caught %-36s %s" % (name, failures[-1][:150]))
    shutil.rmtree(work, ignore_errors=True)
    return True


def main():
    if not (REPO / ATOM).is_file():
        sys.exit("no %s in %s" % (ATOM, REPO))
    print("mutations against CommitStatusIT (%d)" % len(MUTATIONS))
    results = [run(*mutation) for mutation in MUTATIONS]
    missed = [r for r in results if r is not True]
    if missed:
        sys.exit("%d mutation(s) were not caught by the test they were meant to break" % len(missed))
    print("mutations ok: %d planted bugs, all caught" % len(results))


if __name__ == "__main__":
    main()
