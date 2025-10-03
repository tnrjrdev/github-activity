package com.task.ghactivity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.task.ghactivity.util.Ansi;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

@Component
public class GhCliRunner implements CommandLineRunner {

    private static final String API = "https://api.github.com/users/%s/events?per_page=100";
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().build();

    @Override
    public void run(String... args) throws Exception {
        if (args.length == 0) {
            printUsage();
            System.exit(64); // EX_USAGE
        }

        // very light arg parsing
        String username = null;
        int limit = 20;
        boolean noColor = false;
        int timeoutSec = 15;
        String token = System.getenv("GITHUB_TOKEN");

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--limit" -> {
                    if (i + 1 >= args.length) { error("missing value for --limit"); return; }
                    limit = Math.max(1, Math.min(100, Integer.parseInt(args[++i])));
                }
                case "--timeout" -> {
                    if (i + 1 >= args.length) { error("missing value for --timeout"); return; }
                    timeoutSec = Integer.parseInt(args[++i]);
                }
                case "--no-color" -> noColor = true;
                case "--token" -> {
                    if (i + 1 >= args.length) { error("missing value for --token"); return; }
                    token = args[++i];
                }
                default -> {
                    if (a.startsWith("-")) {
                        error("unknown option: " + a);
                        return;
                    } else if (username == null) {
                        username = a;
                    }
                }
            }
        }

        if (username == null || username.isBlank()) {
            error("username is required");
            return;
        }

        boolean useColor = System.console() != null && !noColor && System.getenv("NO_COLOR") == null;

        String url = API.formatted(username);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "github-activity-cli/1.0 (+https://github.com/)");
        if (token != null && !token.isBlank()) {
            b.header("Authorization", "Bearer " + token)
             .header("X-GitHub-Api-Version", "2022-11-28");
        }
        HttpRequest req = b.build();

        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            errln(color("Error: ", Ansi.RED, useColor) + "Network error: " + e.getMessage());
            System.exit(2);
            return;
        }

        int status = resp.statusCode();
        String body = resp.body();

        if (status >= 400) {
            String msg = "HTTP " + status + " from GitHub API.";
            try {
                JsonNode err = mapper.readTree(body);
                if (err.has("message")) {
                    msg += " " + err.get("message").asText();
                }
            } catch (Exception ignore) {}
            errln(color("Error: ", Ansi.RED, useColor) + msg);
            if (status == 404) {
                errln("Tip: check if the username is correct.");
            } else if (status == 401) {
                errln("Tip: If using a token, ensure it is valid.");
            } else if (status == 403) {
                errln("Tip: Provide a token via --token or GITHUB_TOKEN to raise rate limits.");
            }
            System.exit(1);
            return;
        }

        JsonNode events;
        try {
            events = mapper.readTree(body);
            if (!events.isArray() || events.isEmpty()) {
                outln("No recent public activity found.");
                System.exit(0);
                return;
            }
        } catch (Exception e) {
            errln(color("Error: ", Ansi.RED, useColor) + "Failed to parse API response: " + e.getMessage());
            System.exit(3);
            return;
        }

        int count = 0;
        for (JsonNode ev : events) {
            outln(describeEvent(ev, useColor));
            count++;
            if (count >= limit) break;
        }
    }

    private String describeEvent(JsonNode ev, boolean useColor) {
        String type = text(ev, "type").orElse("Event");
        String repo = ev.has("repo") && ev.get("repo").has("name") ? ev.get("repo").get("name").asText() : "unknown/repo";
        String created = text(ev, "created_at").map(ts -> {
            try { return TS_FMT.format(Instant.parse(ts)); } catch (Exception e) { return ts; }
        }).orElse("");

        JsonNode payload = ev.has("payload") ? ev.get("payload") : null;

        switch (type) {
            case "PushEvent" -> {
                int commits = 0;
                if (payload != null && payload.has("commits") && payload.get("commits").isArray()) {
                    commits = payload.get("commits").size();
                }
                return "- Pushed " + color(String.valueOf(commits), Ansi.CYAN, useColor)
                        + " commit" + (commits == 1 ? "" : "s") + " to "
                        + color(repo, Ansi.BOLD, useColor) + " (" + color(created, Ansi.DIM, useColor) + ")";
            }
            case "IssuesEvent" -> {
                String action = payload != null && payload.has("action") ? payload.get("action").asText() : "acted on";
                String num = payload != null && payload.has("issue") && payload.get("issue").has("number")
                        ? "#" + payload.get("issue").get("number").asText() : "#?";
                return "- " + cap(action) + " issue " + color(num, Ansi.CYAN, useColor)
                        + " in " + color(repo, Ansi.BOLD, useColor) + " (" + color(created, Ansi.DIM, useColor) + ")";
            }
            case "IssueCommentEvent" -> {
                String action = payload != null && payload.has("action") ? payload.get("action").asText() : "commented";
                String num = payload != null && payload.has("issue") && payload.get("issue").has("number")
                        ? "#" + payload.get("issue").get("number").asText() : "#?";
                return "- " + cap(action) + " on issue " + color(num, Ansi.CYAN, useColor)
                        + " in " + color(repo, Ansi.BOLD, useColor) + " (" + color(created, Ansi.DIM, useColor) + ")";
            }
            case "PullRequestEvent" -> {
                String action = payload != null && payload.has("action") ? payload.get("action").asText() : "acted on";
                boolean merged = payload != null && payload.has("pull_request")
                        && payload.get("pull_request").has("merged") && payload.get("pull_request").get("merged").asBoolean(false);
                String num = payload != null && payload.has("pull_request") && payload.get("pull_request").has("number")
                        ? "#" + payload.get("pull_request").get("number").asText() : "#?";
                if (merged && "closed".equals(action)) action = "merged";
                return "- " + cap(action) + " pull request " + color(num, Ansi.CYAN, useColor)
                        + " in " + color(repo, Ansi.BOLD, useColor) + " (" + color(created, Ansi.DIM, useColor) + ")";
            }
            case "PullRequestReviewEvent" -> {
                String action = payload != null && payload.has("action") ? payload.get("action").asText() : "reviewed";
                String num = payload != null && payload.has("pull_request") && payload.get("pull_request").has("number")
                        ? "#" + payload.get("pull_request").get("number").asText() : "#?";
                return "- " + cap(action) + " PR " + color(num, Ansi.CYAN, useColor)
                        + " in " + color(repo, Ansi.BOLD, useColor) + " (" + color(created, Ansi.DIM, useColor) + ")";
            }
            case "PullRequestReviewCommentEvent" -> {
                String num = payload != null && payload.has("pull_request") && payload.get("pull_request").has("number")
                        ? "#" + payload.get("pull_request").get("number").asText() : "#?";
                return "- Commented on PR " + color(num, Ansi.CYAN, useColor)
                        + " in " + color(repo, Ansi.BOLD, useColor) + " (" + color(created, Ansi.DIM, useColor) + ")";
            }
            case "WatchEvent" -> {
                return "- Starred " + color(repo, Ansi.BOLD, useColor)
                        + " (" + color(created, Ansi.DIM, useColor) + ")";
            }
            case "CreateEvent" -> {
                String refType = payload != null && payload.has("ref_type") ? payload.get("ref_type").asText() : "thing";
                String ref = payload != null && payload.has("ref") && !payload.get("ref").isNull()
                        ? payload.get("ref").asText() : repo;
                return "- Created " + color(refType, Ansi.GREEN, useColor) + " "
                        + color(ref, Ansi.BOLD, useColor) + " in " + color(repo, Ansi.BOLD, useColor)
                        + " (" + color(created, Ansi.DIM, useColor) + ")";
            }
            case "DeleteEvent" -> {
                String refType = payload != null && payload.has("ref_type") ? payload.get("ref_type").asText() : "thing";
                String ref = payload != null && payload.has("ref") && !payload.get("ref").isNull()
                        ? payload.get("ref").asText() : "";
                String target = (refType + " " + ref).trim();
                return "- Deleted " + color(target, Ansi.YELLOW, useColor)
                        + " in " + color(repo, Ansi.BOLD, useColor) + " (" + color(created, Ansi.DIM, useColor) + ")";
            }
            case "ForkEvent" -> {
                String forkee = payload != null && payload.has("forkee") && payload.get("forkee").has("full_name")
                        ? payload.get("forkee").get("full_name").asText() : "a fork";
                return "- Forked " + color(repo, Ansi.BOLD, useColor) + " to "
                        + color(forkee, Ansi.BOLD, useColor) + " (" + color(created, Ansi.DIM, useColor) + ")";
            }
            case "ReleaseEvent" -> {
                String action = payload != null && payload.has("action") ? payload.get("action").asText() : "published";
                String tag = payload != null && payload.has("release") && payload.get("release").has("tag_name")
                        ? payload.get("release").get("tag_name").asText() : "a release";
                return "- " + cap(action) + " " + color(tag, Ansi.CYAN, useColor) + " in "
                        + color(repo, Ansi.BOLD, useColor) + " (" + color(created, Ansi.DIM, useColor) + ")";
            }
            case "PublicEvent" -> {
                return "- Open-sourced " + color(repo, Ansi.BOLD, useColor)
                        + " (" + color(created, Ansi.DIM, useColor) + ")";
            }
            case "MemberEvent" -> {
                String action = payload != null && payload.has("action") ? payload.get("action").asText() : "changed";
                String member = payload != null && payload.has("member") && payload.get("member").has("login")
                        ? payload.get("member").get("login").asText() : "a member";
                return "- " + cap(action) + " collaborator " + color(member, Ansi.CYAN, useColor)
                        + " in " + color(repo, Ansi.BOLD, useColor) + " (" + color(created, Ansi.DIM, useColor) + ")";
            }
            case "GollumEvent" -> {
                return "- Updated wiki in " + color(repo, Ansi.BOLD, useColor)
                        + " (" + color(created, Ansi.DIM, useColor) + ")";
            }
            case "CommitCommentEvent" -> {
                return "- Commented on a commit in " + color(repo, Ansi.BOLD, useColor)
                        + " (" + color(created, Ansi.DIM, useColor) + ")";
            }
            default -> {
                return "- " + type + " in " + color(repo, Ansi.BOLD, useColor)
                        + " (" + color(created, Ansi.DIM, useColor) + ")";
            }
        }
    }

    private static Optional<String> text(JsonNode node, String field) {
        if (node != null && node.has(field) && !node.get(field).isNull()) {
            return Optional.of(node.get(field).asText());
        }
        return Optional.empty();
    }

    private static void printUsage() {
        String usage =
                "GitHub Activity CLI (Spring Boot, no external HTTP libs)\n" +
                        "\n" +
                        "Uso:\n" +
                        "  java -jar github-activity-*.jar <username> [--limit N] [--token TOKEN] [--timeout SECONDS] [--no-color]\n" +
                        "\n" +
                        "Exemplos:\n" +
                        "  java -jar github-activity.jar octocat\n" +
                        "  java -jar github-activity.jar tn-junior --limit 15\n" +
                        "  GITHUB_TOKEN=ghp_xxx java -jar github-activity.jar octocat\n";
        System.out.println(usage);
    }


    private static String color(String s, String ansi, boolean useColor) {
        return useColor ? (ansi + s + Ansi.RESET) : s;
    }

    private static void errln(String s) { System.err.println(s); }
    private static void outln(String s) { System.out.println(s); }
    private static void error(String msg) {
        errln("Error: " + msg);
        printUsage();
        System.exit(64);
    }

    private static String cap(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
