package com.jenkinschina.atom.commitstatus;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Report a build state back to the commit that caused it — GitHub, GitLab or Gitea.
 *
 * <p>One atom instead of three plugins: the three APIs differ in the URL and the body shape, nothing else.
 * The token is a credential, so it never appears in the job form or in the log.
 *
 * <p>1.1.0 ports what {@code pipeline-githubnotify-step} actually does (the {@code githubNotify} step): it addresses
 * the repository by {@code account} + {@code repo}, it checks the credential, the repository and the commit before
 * it writes anything, and it writes the status on the <em>commit sha the API returned</em>, not on the string it was
 * given. A status write is also checked against the object the API echoes back, because a proxy can answer 201.
 */
public class CommitStatusAtom implements AtomTask {

    private static final Pattern VAR = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_.]*)}");

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        String provider = context.getParam("provider", "github").trim().toLowerCase(Locale.ROOT);
        String apiUrl = context.getParam("apiUrl", defaultApi(provider)).trim().replaceAll("/+$", "");
        if (apiUrl.isBlank()) {
            return AtomResult.error(ErrorType.USER, 40003, "unknown provider '" + provider
                    + "'; use github, gitlab or gitea, or set apiUrl");
        }

        String account = context.getParam("account", "").trim();
        String repo = context.getParam("repo", "").trim();
        if (repo.isEmpty()) {
            repo = inferRepoFromGitUrl(context);
        }
        if (repo.contains("/")) {
            String[] parts = repo.split("/", 2);
            if (account.isEmpty()) {
                account = parts[0];
                repo = parts[1];
            } else if (account.equalsIgnoreCase(parts[0])) {
                repo = parts[1];
            } else {
                return AtomResult.error(ErrorType.USER, 40013, "repo '" + repo + "' is not under account '"
                        + account + "'; give the repository name alone, or fix the account");
            }
        }
        repo = repo.replaceAll("/+$", "");
        String fullRepo = account.isEmpty() ? repo : account + "/" + repo;
        if (repo.isBlank() || ("github".equals(provider) || "gitea".equals(provider)) && account.isBlank()) {
            return AtomResult.error(ErrorType.USER, 40001,
                    "account and repo are required (or a 'owner/name' repo, or GIT_URL to infer them from)");
        }

        String sha = context.getParam("sha", "").trim();
        if (sha.isEmpty()) {
            sha = orEmpty(context.getParam("GIT_COMMIT")).trim();
        }
        if (sha.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40001, "repo and sha (or GIT_COMMIT) are required");
        }
        String token = orEmpty(context.getSecret("token")).trim();
        if (token.isEmpty()) {
            token = orEmpty(context.getParam("token")).trim();
        }
        if (token.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40002, "bind the 'token' credential: the API rejects anonymous status");
        }

        String state = context.getParam("state", "");
        if (state.isBlank()) {
            state = fromBuildStatus(orEmpty(context.getParam("BUILD_STATUS")));
        }
        String mapped = mapState(provider, state);
        String description = expand(context, context.getParam("description", "")).trim();
        if (description.isEmpty()) {
            description = "build #" + orEmpty(context.getParam("BUILD_NUMBER")) + " " + state;
        }
        String contextName = context.getParam("contextName", "jenkins/atom");
        String targetUrl = context.getParam("targetUrl", orEmpty(context.getParam("BUILD_URL")));

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        String authorization = "gitlab".equals(provider) ? token : "Bearer " + token;

        // ---- the githubNotify preflight: credential, repository, commit. Only where the API has the same shape.
        boolean preflight = context.getBooleanParam("preflight", true)
                && ("github".equals(provider) || "gitea".equals(provider));
        String postedSha = sha;
        if (preflight) {
            Resp me = get(client, apiUrl + "/user", authorization);
            if (me.code == 401) {
                return AtomResult.error(ErrorType.USER, 40006,
                        "the supplied credentials are invalid to login (GET /user answered 401)");
            }
            if (me.code / 100 != 2) {
                return AtomResult.error(ErrorType.THIRD_PART, 40012,
                        "GET /user answered " + me.code + ": " + abbreviate(me.body));
            }

            Resp repository = get(client, apiUrl + "/repos/" + fullRepo, authorization);
            if (repository.code == 401) {
                return AtomResult.error(ErrorType.USER, 40006, "the supplied credentials are invalid to login");
            }
            if (repository.code == 404) {
                return AtomResult.error(ErrorType.USER, 40007,
                        "the specified repository does not exist for the specified account: " + fullRepo);
            }
            if (repository.code / 100 != 2) {
                return AtomResult.error(ErrorType.THIRD_PART, 40012,
                        "GET /repos/" + fullRepo + " answered " + repository.code + ": " + abbreviate(repository.body));
            }

            Resp commit = get(client, apiUrl + "/repos/" + fullRepo + "/commits/" + sha, authorization);
            if (commit.code == 401) {
                return AtomResult.error(ErrorType.USER, 40006, "the supplied credentials are invalid to login");
            }
            if (commit.code == 404 || commit.code == 422) {
                return AtomResult.error(ErrorType.USER, 40008,
                        "the specified commit does not exist in the specified repository: " + sha);
            }
            if (commit.code / 100 != 2) {
                return AtomResult.error(ErrorType.THIRD_PART, 40012,
                        "GET the commit " + sha + " answered " + commit.code + ": " + abbreviate(commit.body));
            }
            String canonical = firstString(commit.body, "sha");
            if (canonical != null && canonical.matches("[0-9a-fA-F]{7,64}")) {
                postedSha = canonical;
                if (!canonical.equalsIgnoreCase(sha)) {
                    context.log("[commit-status] " + sha + " resolved to " + canonical);
                }
            }
        }

        String body;
        String url;
        HttpRequest.Builder request;
        if ("gitlab".equals(provider)) {
            String project = URLEncoder.encode(fullRepo, StandardCharsets.UTF_8);
            url = apiUrl + "/projects/" + project + "/statuses/" + URLEncoder.encode(sha, StandardCharsets.UTF_8);
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("state", mapped);
            fields.put("name", contextName);
            fields.put("description", description);
            fields.put("target_url", targetUrl);
            body = form(fields);
            request = HttpRequest.newBuilder(URI.create(url))
                    .header("PRIVATE-TOKEN", token)
                    .header("Content-Type", "application/x-www-form-urlencoded");
        } else {
            url = apiUrl + "/repos/" + fullRepo + "/statuses/" + postedSha;
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("state", mapped);
            fields.put("context", contextName);
            fields.put("description", description);
            fields.put("target_url", targetUrl);
            body = json(fields);
            request = HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", authorization)
                    .header("Accept", "application/vnd.github+json")
                    .header("Content-Type", "application/json");
        }

        context.log("[commit-status] " + provider + " " + fullRepo + "@"
                + postedSha.substring(0, Math.min(7, postedSha.length())) + " -> " + mapped);
        Resp response;
        try {
            response = send(client, request.timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build());
        } catch (Exception e) {
            return AtomResult.error(ErrorType.THIRD_PART, 40004, "posting the status failed: " + e);
        }
        if (response.code / 100 != 2) {
            return AtomResult.error(ErrorType.THIRD_PART, 40005,
                    "the API answered " + response.code + ": " + abbreviate(response.body));
        }

        // A 201 is not evidence: GitHub/Gitea echo the created status, so make it say what we sent.
        String verified = "not-checked";
        if (!"gitlab".equals(provider) && context.getBooleanParam("verifyStatus", true)) {
            String echoed = firstString(response.body, "state");
            if (echoed == null) {
                return AtomResult.error(ErrorType.THIRD_PART, 40009,
                        "the API answered " + response.code + " without the status object: " + abbreviate(response.body));
            }
            if (!echoed.equalsIgnoreCase(mapped)) {
                return AtomResult.error(ErrorType.THIRD_PART, 40009,
                        "the API answered " + response.code + " but the status it returned is '" + echoed
                                + "', not '" + mapped + "'");
            }
            String echoedContext = firstString(response.body, "context");
            if (echoedContext != null && !echoedContext.equals(contextName)) {
                return AtomResult.error(ErrorType.THIRD_PART, 40009,
                        "the API accepted the status under context '" + echoedContext + "', not '" + contextName + "'");
            }
            verified = echoed.toLowerCase(Locale.ROOT);
        }

        AtomResult result = AtomResult.success()
                .data("status", String.valueOf(response.code))
                .data("state", mapped)
                .data("sha", postedSha)
                .data("apiUrl", url)
                .data("verified", verified);

        // ---- PR comment: not part of githubNotify (that step only writes a status), kept as a documented extension.
        String comment = expand(context, context.getParam("prComment", "")).trim();
        if (!comment.isEmpty()) {
            if ("gitlab".equals(provider)) {
                return AtomResult.error(ErrorType.USER, 40011,
                        "prComment speaks the GitHub/Gitea issues API; on GitLab use the gitlab-mr atom");
            }
            String prNumber = context.getParam("prNumber", "").trim();
            if (prNumber.isEmpty()) {
                prNumber = orEmpty(context.getParam("CHANGE_ID")).trim();
            }
            if (!prNumber.matches("[0-9]+")) {
                return AtomResult.error(ErrorType.USER, 40011,
                        "prComment needs prNumber (or CHANGE_ID); '" + prNumber + "' is not a pull request number");
            }
            String commentsUrl = apiUrl + "/repos/" + fullRepo + "/issues/" + prNumber + "/comments";
            Resp posted;
            try {
                posted = send(client, HttpRequest.newBuilder(URI.create(commentsUrl))
                        .header("Authorization", authorization)
                        .header("Accept", "application/vnd.github+json")
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(20))
                        .POST(HttpRequest.BodyPublishers.ofString(json(Map.of("body", comment)),
                                StandardCharsets.UTF_8))
                        .build());
            } catch (Exception e) {
                return AtomResult.error(ErrorType.THIRD_PART, 40010, "posting the comment failed: " + e);
            }
            if (posted.code / 100 != 2) {
                return AtomResult.error(ErrorType.THIRD_PART, 40010,
                        "the API answered " + posted.code + " for the comment: " + abbreviate(posted.body));
            }
            // Read it back: 201 + a body of its own is not proof the comment was stored as written.
            String commentId = "";
            for (int page = 1; page <= 10 && commentId.isEmpty(); page++) {
                Resp list = get(client, commentsUrl + "?per_page=100&page=" + page, authorization);
                if (list.code / 100 != 2) {
                    return AtomResult.error(ErrorType.THIRD_PART, 40010,
                            "reading the comments back answered " + list.code + ": " + abbreviate(list.body));
                }
                commentId = idOfBody(list.body, comment);
                if (firstString(list.body, "body") == null) {
                    break;
                }
                if (countOccurrences(list.body, "\"body\"") < 100) {
                    break;
                }
            }
            if (commentId.isEmpty()) {
                return AtomResult.error(ErrorType.THIRD_PART, 40010,
                        "the API answered " + posted.code + " but the pull request " + prNumber
                                + " does not list this comment; it was not stored as written");
            }
            context.log("[commit-status] comment on PR #" + prNumber + " is id " + commentId);
            result.data("commentId", commentId);
        }
        return result;
    }

    /** Two requests' worth of "what came back", so the checks read the same everywhere. */
    private record Resp(int code, String body) {
    }

    private static Resp get(HttpClient client, String url, String authorization) throws Exception {
        return send(client, HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", authorization)
                .header("Accept", "application/vnd.github+json")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build());
    }

    private static Resp send(HttpClient client, HttpRequest request) throws Exception {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Resp(response.statusCode(), response.body() == null ? "" : response.body());
    }

    private static String defaultApi(String provider) {
        return switch (provider) {
            case "github" -> "https://api.github.com";
            case "gitlab" -> "https://gitlab.com/api/v4";
            case "gitea" -> "";
            default -> "";
        };
    }

    /** Jenkins says SUCCESS/UNSTABLE/FAILURE/ABORTED; GitLab and GitHub want their own spelling. */
    private static String mapState(String provider, String state) {
        String upper = state.trim().toUpperCase(Locale.ROOT);
        boolean gitlab = "gitlab".equals(provider);
        return switch (upper) {
            case "SUCCESS", "OK", "PASSED" -> "success";
            case "UNSTABLE", "WARNING" -> gitlab ? "failed" : "failure";
            case "FAILURE", "FAILED", "ERROR" -> gitlab ? "failed" : "failure";
            case "ABORTED", "CANCELED", "CANCELLED" -> gitlab ? "canceled" : "error";
            case "PENDING", "RUNNING", "BUILDING" -> "pending";
            default -> "pending";
        };
    }

    private static String fromBuildStatus(String status) {
        return status.isBlank() ? "SUCCESS" : status;
    }

    /**
     * {@code githubNotify} infers account/repo from the multibranch source; an atom has no job tree, so it infers from
     * {@code GIT_URL}, which is what a freestyle or pipeline checkout leaves behind.
     */
    static String inferRepoFromGitUrl(AtomContext context) {
        String gitUrl = context.getParam("GIT_URL", "").trim();
        if (gitUrl.isEmpty()) {
            return "";
        }
        String path = gitUrl;
        int scheme = path.indexOf("://");
        if (scheme >= 0) {
            path = path.substring(scheme + 3);
            int slash = path.indexOf('/');
            path = slash < 0 ? "" : path.substring(slash + 1);
        } else {
            int colon = path.indexOf(':');
            int at = path.indexOf('@');
            if (colon >= 0 && at >= 0 && at < colon) {
                path = path.substring(colon + 1);
            }
        }
        path = path.replaceAll("^/+", "").replaceAll("\\.git$", "");
        String[] parts = path.split("/");
        if (parts.length < 2) {
            return "";
        }
        return parts[parts.length - 2] + "/" + parts[parts.length - 1];
    }

    /** {@code ${NAME}} is replaced by the build parameter / environment variable of that name, as Jenkins does. */
    static String expand(AtomContext context, String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        Matcher matcher = VAR.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(context.getParam(matcher.group(1), "")));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** Every string value under {@code key}, decoded — enough JSON for the three APIs we speak. */
    private static List<String> stringsOf(String json, String key) {
        List<String> values = new ArrayList<>();
        String needle = "\"" + key + "\"";
        int at = 0;
        while ((at = json.indexOf(needle, at)) >= 0) {
            int value = valueStart(json, at + needle.length());
            if (value < 0) {
                at += needle.length();
                continue;
            }
            values.add(decodeString(json, value));
            at = value + 1;
        }
        return values;
    }

    private static String firstString(String json, String key) {
        List<String> values = stringsOf(json == null ? "" : json, key);
        return values.isEmpty() ? null : values.get(0);
    }

    /** Index of the opening quote of the value that follows {@code from} (past ':' and blanks), or -1. */
    private static int valueStart(String json, int from) {
        int p = from;
        while (p < json.length() && Character.isWhitespace(json.charAt(p))) {
            p++;
        }
        if (p >= json.length() || json.charAt(p) != ':') {
            return -1;
        }
        p++;
        while (p < json.length() && Character.isWhitespace(json.charAt(p))) {
            p++;
        }
        return p < json.length() && json.charAt(p) == '"' ? p : -1;
    }

    /** The string literal starting at {@code quote}, with its escapes resolved. */
    private static String decodeString(String json, int quote) {
        StringBuilder value = new StringBuilder();
        int p = quote + 1;
        while (p < json.length()) {
            char c = json.charAt(p);
            if (c == '"') {
                break;
            }
            if (c == '\\' && p + 1 < json.length()) {
                char escape = json.charAt(p + 1);
                switch (escape) {
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'u' -> {
                        if (p + 5 < json.length()) {
                            value.append((char) Integer.parseInt(json.substring(p + 2, p + 6), 16));
                            p += 4;
                        }
                    }
                    default -> value.append(escape);
                }
                p += 2;
            } else {
                value.append(c);
                p++;
            }
        }
        return value.toString();
    }

    /** The {@code id} of the comment object whose {@code body} is exactly {@code body}, or empty. */
    private static String idOfBody(String json, String body) {
        String needle = "\"body\"";
        int at = 0;
        while ((at = json.indexOf(needle, at)) >= 0) {
            int value = valueStart(json, at + needle.length());
            if (value < 0) {
                at += needle.length();
                continue;
            }
            if (decodeString(json, value).equals(body)) {
                int objectStart = json.lastIndexOf('}', value) + 1;
                Matcher id = Pattern.compile("\"id\"\\s*:\\s*([0-9]+)").matcher(json.substring(objectStart, value));
                return id.find() ? id.group(1) : "";
            }
            at = value + 1;
        }
        return "";
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int at = 0;
        while ((at = text.indexOf(needle, at)) >= 0) {
            count++;
            at += needle.length();
        }
        return count;
    }

    private static String json(Map<String, String> fields) {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append('"').append(entry.getKey()).append("\":\"").append(escape(entry.getValue())).append('"');
        }
        return out.append('}').toString();
    }

    private static String form(Map<String, String> fields) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (!out.isEmpty()) {
                out.append('&');
            }
            out.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue() == null ? "" : entry.getValue(),
                            StandardCharsets.UTF_8));
        }
        return out.toString();
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static String abbreviate(String body) {
        String oneLine = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 200 ? oneLine.substring(0, 200) + "…" : oneLine;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
