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
import java.util.Locale;
import java.util.Map;

/**
 * Report a build state back to the commit that caused it — GitHub, GitLab or Gitea.
 *
 * <p>One atom instead of three plugins: the three APIs differ in the URL and the body shape, nothing else.
 * The token is a credential, so it never appears in the job form or in the log.
 */
public class CommitStatusAtom implements AtomTask {

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        String provider = context.getParam("provider", "github").trim().toLowerCase(Locale.ROOT);
        String repo = context.getParam("repo", "");
        String sha = context.getParam("sha", "");
        if (sha.isBlank()) {
            sha = orEmpty(context.getParam("GIT_COMMIT"));
        }
        String token = orEmpty(context.getSecret("token"));
        if (token.isBlank()) {
            token = orEmpty(context.getParam("token"));
        }
        if (repo.isBlank() || sha.isBlank()) {
            return AtomResult.error(ErrorType.USER, 40001, "repo and sha (or GIT_COMMIT) are required");
        }
        if (token.isBlank()) {
            return AtomResult.error(ErrorType.USER, 40002, "bind the 'token' credential: the API rejects anonymous status");
        }

        String state = context.getParam("state", "");
        if (state.isBlank()) {
            state = fromBuildStatus(orEmpty(context.getParam("BUILD_STATUS")));
        }
        String mapped = mapState(provider, state);
        String description = context.getParam("description", "");
        if (description.isBlank()) {
            description = "build #" + orEmpty(context.getParam("BUILD_NUMBER")) + " " + state;
        }
        String contextName = context.getParam("contextName", "jenkins/atom");
        String targetUrl = context.getParam("targetUrl", orEmpty(context.getParam("BUILD_URL")));

        String apiUrl = context.getParam("apiUrl", defaultApi(provider));
        if (apiUrl.isBlank()) {
            return AtomResult.error(ErrorType.USER, 40003, "unknown provider '" + provider
                    + "'; use github, gitlab or gitea, or set apiUrl");
        }
        apiUrl = apiUrl.replaceAll("/+$", "");
        String body;
        String url;
        HttpRequest.Builder request;
        switch (provider) {
            case "gitlab" -> {
                String project = URLEncoder.encode(repo, StandardCharsets.UTF_8);
                url = apiUrl + "/projects/" + project + "/statuses/"
                        + URLEncoder.encode(sha, StandardCharsets.UTF_8);
                body = form(Map.of(
                        "state", mapped,
                        "name", contextName,
                        "description", description,
                        "target_url", targetUrl));
                request = HttpRequest.newBuilder(URI.create(url))
                        .header("PRIVATE-TOKEN", token)
                        .header("Content-Type", "application/x-www-form-urlencoded");
            }
            default -> {
                // github and gitea share the shape
                url = apiUrl + "/repos/" + repo + "/statuses/" + sha;
                body = json(Map.of(
                        "state", mapped,
                        "context", contextName,
                        "description", description,
                        "target_url", targetUrl));
                request = HttpRequest.newBuilder(URI.create(url))
                        .header("Authorization", "Bearer " + token)
                        .header("Accept", "application/vnd.github+json")
                        .header("Content-Type", "application/json");
            }
        }

        context.log("[commit-status] " + provider + " " + repo + "@" + sha.substring(0, Math.min(7, sha.length()))
                + " -> " + mapped);
        HttpResponse<String> response;
        try {
            response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
                    .send(request.timeout(Duration.ofSeconds(20))
                            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                            .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return AtomResult.error(ErrorType.THIRD_PART, 40004, "posting the status failed: " + e);
        }

        int code = response.statusCode();
        if (code / 100 != 2) {
            return AtomResult.error(ErrorType.THIRD_PART, 40005,
                    "the API answered " + code + ": " + abbreviate(response.body()));
        }
        return AtomResult.success()
                .data("status", String.valueOf(code))
                .data("state", mapped)
                .data("apiUrl", url);
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
            default -> gitlab ? "pending" : "pending";
        };
    }

    private static String fromBuildStatus(String status) {
        return status.isBlank() ? "SUCCESS" : status;
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
