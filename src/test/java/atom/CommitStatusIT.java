package atom;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.Json;
import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The commit-status atom against a local stand-in for the GitHub / GitLab APIs.
 *
 * <p>These are the 15 scenarios {@code tools/smoke.sh} ran. What matters is not "exit code 0" but the requests the atom
 * actually made: the githubNotify preflight (credential, repository, commit), the sha the API resolved, the body shape
 * each provider wants, and the state the server stored. {@code tools/fake-github.py} is that stand-in; it records every
 * request as {@code METHOD \t path \t auth \t body} and the assertions here read that log field by field.
 */
class CommitStatusIT {

    /** What the fake API resolves the short sha {@code abcdef1} to. */
    private static final String CANONICAL = "abcdef1234567890abcdef1234567890abcdef12";

    private static final Path MODULE = Path.of(System.getProperty("atom.module", System.getProperty("user.dir")));
    private static final Path SCRATCH = Path.of(System.getProperty("atom.scratch", MODULE.resolve("target").toString()));

    private final List<Process> servers = new ArrayList<>();
    private final List<Path> scratch = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (Process server : servers) {
            if (server.isAlive()) {
                server.destroy();
            }
        }
        servers.clear();
        for (Path directory : scratch) {
            try (var walk = Files.walk(directory)) {
                walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(java.io.File::delete);
            } catch (IOException ignored) {
                // a leaked temp directory is not a test result
            }
        }
        scratch.clear();
    }

    // ------------------------------------------------------------------ 1: the githubNotify preflight

    @Test
    @DisplayName("GitHub: credential + repo + commit checked first, short sha resolved by the API, UNSTABLE -> failure, token only in the header")
    void githubPreflightChecksCredentialRepositoryAndCommit() throws Exception {
        Api api = api();
        AtomResult run = github(api, "acme", "parcel", "abcdef1")
                .input("contextName", "parcel/ci")
                .run();

        run.assertOk();
        assertEquals("success", run.status(), () -> run.toString());
        assertEquals(CANONICAL, run.field("sha"), "the status must go on the sha the API returned");
        assertEquals("failure", run.field("state"), "UNSTABLE must map to failure");
        assertEquals("failure", run.field("verified"), "the echo was not checked");

        List<Request> log = api.requests();
        assertEquals(List.of("GET /user", "GET /repos/acme/parcel", "GET /repos/acme/parcel/commits/abcdef1",
                        "POST /repos/acme/parcel/statuses/" + CANONICAL),
                labels(log), () -> "wrong requests: " + labels(log));

        Request status = find(log, "POST", "/repos/acme/parcel/statuses/" + CANONICAL);
        assertEquals("Bearer good-token", status.auth(), "token not sent: " + status);
        assertJson(status, "state", "failure");
        assertJson(status, "context", "parcel/ci");

        assertEquals("Bearer good-token", find(log, "GET", "/user").auth());
        assertEquals("Bearer good-token", find(log, "GET", "/repos/acme/parcel/commits/abcdef1").auth());

        assertFalse(run.console().contains("good-token"), "the token must not be in the log:\n" + run.console());
        assertFalse(run.file("output.json").contains("good-token"), "the token must not be in output.json");
    }

    // ------------------------------------------------------------------ 2: owner/name

    @Test
    @DisplayName("repo written as 'owner/name' is split into account + repo: the same requests, nothing else")
    void ownerNameRepoIsSplitIntoAccountAndRepo() throws Exception {
        Api api = api();
        AtomResult run = base(api, "good-token")
                .input("provider", "github")
                .input("repo", "acme/parcel")
                .input("sha", "abcdef1")
                .run();

        run.assertOk();
        assertEquals(CANONICAL, run.field("sha"));
        assertEquals(List.of("GET /user", "GET /repos/acme/parcel", "GET /repos/acme/parcel/commits/abcdef1",
                        "POST /repos/acme/parcel/statuses/" + CANONICAL),
                labels(api.requests()), "owner/name was not split");
    }

    // ------------------------------------------------------------------ 3: GitLab

    @Test
    @DisplayName("GitLab keeps the old shape: form body, PRIVATE-TOKEN, project path encoded, state=failed, no preflight")
    void gitlabPostsAFormBodyWithPrivateToken() throws Exception {
        Api api = api();
        AtomResult run = gitlab(api, "group/parcel", "deadbeef").run();

        run.assertOk();
        List<Request> log = api.requests();
        assertEquals(List.of("POST /projects/group%2Fparcel/statuses/deadbeef"), labels(log),
                "GitLab has no /user preflight of this kind");

        Request status = find(log, "POST", "/projects/group%2Fparcel/statuses/deadbeef");
        assertEquals("gl-token", status.auth(), "PRIVATE-TOKEN header missing: " + status);
        Map<String, String> form = form(status.body());
        assertEquals("failed", form.get("state"), "GitLab state mapping: " + status.body());
        assertEquals("jenkins/atom", form.get("name"));
        assertEquals("build #99 UNSTABLE", form.get("description"));
    }

    // ------------------------------------------------------------------ 4-8: refusals before the write

    @Test
    @DisplayName("no credential: a user error 40002, before any request")
    void missingTokenIsAUserError() throws Exception {
        Api api = api();
        AtomResult run = environment(api)
                .input("provider", "github")
                .input("repo", "acme/parcel")
                .input("sha", "abcdef1")
                .run();

        run.assertRefused();
        assertEquals("40002", run.field("errorCode"), "a missing token must be a user error");
        assertTrue(run.field("message").toLowerCase(Locale.ROOT).contains("token"),
                "the message must say what is missing: " + run.field("message"));
        assertEquals(0, api.requests().size(), "a missing token must be refused before any request");
    }

    @Test
    @DisplayName("rejected credential: 40006 'invalid to login', not 'the API answered 401'")
    void rejectedCredentialHasItsOwnMessage() throws Exception {
        Api api = api();
        AtomResult run = base(api, "bad")
                .input("provider", "github")
                .input("account", "acme")
                .input("repo", "parcel")
                .input("sha", "abcdef1")
                .run();

        run.assertRefused();
        assertTrue(run.field("message").contains("invalid to login"),
                "wrong message: " + run.field("message"));
        assertEquals("40006", run.field("errorCode"));
    }

    @Test
    @DisplayName("a repository that does not exist: 40007, and no status is written")
    void missingRepositoryIsNotAStatusFailure() throws Exception {
        Api api = api();
        AtomResult run = github(api, "acme", "nope", "abcdef1").run();

        run.assertRefused();
        assertTrue(run.field("message").contains("does not exist for the specified account"),
                "wrong message: " + run.field("message"));
        assertEquals("40007", run.field("errorCode"));
        assertTrue(api.requests().stream().noneMatch(request -> request.method().equals("POST")),
                "a missing repository must not get a status: " + api.requests());
    }

    @Test
    @DisplayName("a commit that is not in the repository: 40008, and no status is posted for it")
    void missingCommitIsRefusedWithoutPosting() throws Exception {
        Api api = api();
        AtomResult run = github(api, "acme", "parcel", "deadbeef").run();

        run.assertRefused();
        assertTrue(run.field("message").contains("does not exist in the specified repository"),
                "wrong message: " + run.field("message"));
        assertEquals("40008", run.field("errorCode"));
        assertTrue(api.requests().stream().noneMatch(request -> request.method().equals("POST")),
                "a status was posted for a commit that does not exist: " + api.requests());
    }

    @Test
    @DisplayName("201 with a body that echoes another state is not a success: 40009")
    void anInconsistentEchoIsNotASuccess() throws Exception {
        Api api = api();
        AtomResult run = github(api, "liar", "parcel", "abcdef1").run();

        run.assertRefused();
        assertEquals("40009", run.field("errorCode"),
                "a 201 that echoes another state must fail: " + run.field("message"));
        assertTrue(run.field("message").contains("pending"),
                "the echoed state is not in the message: " + run.field("message"));
    }

    // ------------------------------------------------------------------ 9-10: the PR comment, posted and read back

    @Test
    @DisplayName("PR comment: posted with ${VAR} expanded, read back from the list, commentId from what the API stored")
    void pullRequestCommentIsPostedAndReadBack() throws Exception {
        Api api = api();
        AtomResult run = github(api, "acme", "parcel", "abcdef1")
                .input("prNumber", "12")
                .input("prComment", "packaged build ${BUILD_NUMBER} on ${JOB_NAME}")
                .run();

        run.assertOk();
        assertEquals("success", run.status());
        assertEquals("7", run.field("commentId"), "comment id not read back");

        List<Request> log = api.requests();
        Request posted = find(log, "POST", "/repos/acme/parcel/issues/12/comments");
        assertJson(posted, "body", "packaged build 99 on parcel");

        Request readBack = log.stream()
                .filter(request -> request.method().equals("GET")
                        && request.path().startsWith("/repos/acme/parcel/issues/12/comments?"))
                .findFirst().orElseThrow(() -> new AssertionError("the comment must be read back: " + labels(log)));
        assertTrue(readBack.path().contains("per_page=100"),
                "the read-back did not ask for the page: " + readBack.path());
    }

    @Test
    @DisplayName("201 but the server kept only part of the comment: the read-back catches it, 40010 'not stored as written'")
    void aTruncatedCommentIsRejected() throws Exception {
        Api lossy = api("--lose-tail");
        AtomResult run = github(lossy, "acme", "parcel", "abcdef1")
                .input("prNumber", "12")
                .input("prComment", "packaged build ${BUILD_NUMBER} on ${JOB_NAME}")
                .run();

        run.assertRefused();
        assertEquals("40010", run.field("errorCode"), "a silently shortened comment must fail the atom");
        assertTrue(run.field("message").contains("not stored as written"),
                "wrong message: " + run.field("message"));
        assertJson(find(lossy.requests(), "POST", "/repos/acme/parcel/issues/12/comments"),
                "body", "packaged build 99 on parcel");
    }

    // ------------------------------------------------------------------ 11-12: the comment's refusals

    @Test
    @DisplayName("prComment on GitLab is refused up front: 40011, the API there is notes")
    void prCommentOnGitlabIsRefused() throws Exception {
        Api api = api();
        AtomResult run = gitlab(api, "group/parcel", "deadbeef")
                .input("prComment", "hi")
                .run();

        run.assertRefused();
        assertEquals("40011", run.field("errorCode"),
                "prComment on gitlab must be refused: " + run.field("message"));
        assertTrue(run.field("message").contains("issues API"), "wrong message: " + run.field("message"));
    }

    @Test
    @DisplayName("a non-numeric pull request number is a user error")
    void aNonNumericPullRequestNumberIsAUserError() throws Exception {
        Api api = api();
        AtomResult run = github(api, "acme", "parcel", "abcdef1")
                .input("prNumber", "main")
                .input("prComment", "hi")
                .run();

        run.assertRefused();
        assertEquals("40011", run.field("errorCode"),
                "a non-numeric PR must be refused: " + run.field("message"));
        assertTrue(run.field("message").contains("not a pull request number"),
                "wrong message: " + run.field("message"));
    }

    // ------------------------------------------------------------------ 13-15: the escape hatch, GIT_URL, an API error

    @Test
    @DisplayName("preflight=false: one POST and no GET at all")
    void preflightFalseSkipsTheChecks() throws Exception {
        Api api = api();
        AtomResult run = github(api, "acme", "parcel", "abcdef1")
                .input("preflight", "false")
                .run();

        run.assertOk();
        List<Request> log = api.requests();
        assertEquals(List.of("POST /repos/acme/parcel/statuses/abcdef1"), labels(log),
                "preflight=false must not make a preflight request");
        assertEquals("failure", run.field("state"));
        assertFalse(run.console().contains("GET"), "preflight=false still made a GET:\n" + run.console());
    }

    @Test
    @DisplayName("with no repo at all, GIT_URL is used the way the step infers it from the checkout")
    void repositoryIsInferredFromGitUrl() throws Exception {
        Api api = api();
        AtomResult run = base(api, "good-token")
                .input("provider", "github")
                .input("sha", "abcdef1")
                .input("GIT_URL", "git@github.com:acme/parcel.git")
                .run();

        run.assertOk();
        assertEquals(List.of("GET /user", "GET /repos/acme/parcel", "GET /repos/acme/parcel/commits/abcdef1",
                        "POST /repos/acme/parcel/statuses/" + CANONICAL),
                labels(api.requests()), "GIT_URL was not used");
    }

    @Test
    @DisplayName("an API error on the status write is reported with its code, not swallowed")
    void anApiErrorIsReportedWithItsStatusCode() throws Exception {
        Api api = api();
        AtomResult run = gitlab(api, "group/parcel", "deadbeef")
                .input("apiUrl", api.base() + "/nope")
                .run();

        run.assertRefused();
        assertEquals("40005", run.field("errorCode"),
                "a 404 from the API must fail the atom: " + run.field("message"));
        assertTrue(run.field("message").contains("404"),
                "the status code is not in the message: " + run.field("message"));
    }

    // ------------------------------------------------------------------ the harness

    /** The build environment the runner puts in {@code input.json}, plus the API's base URL. */
    private Atom environment(Api api) throws IOException {
        return Atom.of()
                .input("BUILD_NUMBER", "99")
                .input("JOB_NAME", "parcel")
                .input("BUILD_STATUS", "UNSTABLE")
                .input("BUILD_URL", "http://jenkins/job/parcel/99/")
                .input("apiUrl", api.base());
    }

    /** The build environment and the {@code token} credential. */
    private Atom base(Api api, String token) throws IOException {
        return environment(api).file(".secrets.json", "{\"token\": \"" + token + "\"}");
    }

    /** One GitHub case: the good credential, the build environment, and the repository to report on. */
    private Atom github(Api api, String account, String repo, String sha) throws IOException {
        return base(api, "good-token")
                .input("provider", "github")
                .input("account", account)
                .input("repo", repo)
                .input("sha", sha);
    }

    /** One GitLab case: its own credential and a project path. */
    private Atom gitlab(Api api, String repo, String sha) throws IOException {
        return base(api, "gl-token")
                .input("provider", "gitlab")
                .input("repo", repo)
                .input("sha", sha);
    }

    /** One fake API: its process, the request log it appends to, and the base URL it listens on. */
    private final class Api {
        private final Process process;
        private final Path log;
        private final int port;

        private Api(Process process, Path log, int port) {
            this.process = process;
            this.log = log;
            this.port = port;
        }

        private String base() {
            return "http://127.0.0.1:" + port;
        }

        /** Every request the atom made, in order, as {@code METHOD \t path \t auth \t body}. */
        private List<Request> requests() throws IOException {
            List<Request> requests = new ArrayList<>();
            for (String line : Files.readAllLines(log, UTF_8)) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] fields = line.split("\t", 4);
                requests.add(new Request(fields[0], fields[1],
                        fields.length > 2 ? fields[2] : "",
                        fields.length > 3 ? fields[3] : ""));
            }
            return requests;
        }
    }

    /** One line of the fake API's log: what was asked for, with which credential, and the body it carried. */
    private record Request(String method, String path, String auth, String body) {

        private String label() {
            return method + " " + path;
        }

        @Override
        public String toString() {
            return label() + " auth=" + auth + " body=" + body;
        }
    }

    /**
     * Starts {@code tools/fake-github.py} on a free port and waits until it answers.
     *
     * <p>The port is picked by closing a {@code ServerSocket(0)}, so another process can still take it between the
     * pick and the bind: a start that dies, or does not answer, is retried on a fresh port instead of failing a
     * scenario that has nothing to do with it.
     */
    private Api api(String... flags) throws Exception {
        IllegalStateException last = new IllegalStateException("the fake API never came up");
        for (int attempt = 1; attempt <= 3; attempt++) {
            int port = freePort();
            Path directory = Files.createTempDirectory(SCRATCH, "commit-status-api-");
            scratch.add(directory);
            Path log = directory.resolve("received.tsv");
            Files.createFile(log);

            List<String> command = new ArrayList<>(List.of("python3", "tools/fake-github.py",
                    log.toString(), String.valueOf(port)));
            command.addAll(List.of(flags));
            Process process = new ProcessBuilder(command).directory(MODULE.toFile())
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            servers.add(process);

            if (awaitServing(process, port)) {
                // the probe is this test's own request: forget it, the log is the atom's alone
                Files.writeString(log, "");
                return new Api(process, log, port);
            }
            last = new IllegalStateException("the fake API on port " + port + " did not come up (attempt " + attempt + ")");
            process.destroy();
        }
        throw last;
    }

    private static boolean awaitServing(Process process, int port) throws InterruptedException {
        // /user answers 401 without a token, so probe a route that is happy anonymously
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).build();
        HttpRequest probe = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/repos/probe/repo"))
                .GET().build();
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline && process.isAlive()) {
            try {
                if (http.send(probe, HttpResponse.BodyHandlers.discarding()).statusCode() == 200) {
                    return true;
                }
            } catch (IOException notYet) {
                // the socket is not open yet
            }
            Thread.sleep(50);
        }
        return false;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static List<String> labels(List<Request> log) {
        return log.stream().map(Request::label).toList();
    }

    private static Request find(List<Request> log, String method, String path) {
        return log.stream()
                .filter(request -> request.method().equals(method) && request.path().equals(path))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + method + " " + path + " in the API log: " + labels(log)));
    }

    /** A field of the JSON body the atom sent, compared by value and not by substring. */
    private static void assertJson(Request request, String field, String expected) {
        Object value = Json.parseObject(request.body()).get(field);
        assertEquals(expected, value == null ? null : String.valueOf(value),
                "field '" + field + "' of the " + request.label() + " body: " + request.body());
    }

    /** An {@code x-www-form-urlencoded} body, decoded field by field. */
    private static Map<String, String> form(String body) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            String[] keyValue = pair.split("=", 2);
            fields.put(URLDecoder.decode(keyValue[0], UTF_8),
                    keyValue.length > 1 ? URLDecoder.decode(keyValue[1], UTF_8) : "");
        }
        return fields;
    }
}
