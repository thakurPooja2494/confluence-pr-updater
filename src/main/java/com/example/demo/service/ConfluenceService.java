package com.example.demo.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;



import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;



import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class ConfluenceService {

    @Value("${confluence.email}")
    private String email;
    @Value("${confluence.api.token}")
    private String apiToken;
    @Value("${confluence.cookie}")
    private String cookie;
    @Value("${confluence.workspace}")
    private String workspace;
    @Value("${confluence.page.id}")
    private String pageId;
    @Value("${github.token}")
    private String githubToken;

    private final RestTemplate restTemplate = new RestTemplate();

    public void updatePullRequestInConfluence(String title, String prUrl, String author,
                                              String repoOwner, String repoName, int prNumber,
                                              boolean isMerged, boolean isClosed) {
        String status = isMerged ? "Merged" : (isClosed ? "Closed" : "In Review");
        processConfluenceUpdate(title, prUrl, author, repoOwner, repoName, prNumber, status, !isClosed);
    }

    public void removePullRequestFromConfluence(String prUrl) {
        processConfluenceUpdate(null, prUrl, null, null, null, 0, null, false);
    }

    @SuppressWarnings("unchecked")
    private void processConfluenceUpdate(String title, String prUrl, String author,
                                         String repoOwner, String repoName, int prNumber,
                                         String status, boolean includePropertyChanges) {
        try {
            String auth = Base64.getEncoder()
                    .encodeToString((email + ":" + apiToken).getBytes(StandardCharsets.UTF_8));
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Basic " + auth);
            headers.setContentType(MediaType.APPLICATION_JSON);

            String pageUrl = String.format("https://%s.atlassian.net/wiki/rest/api/content/%s?expand=body.storage,version", workspace, pageId);
            ResponseEntity<Map> getRes = restTemplate.exchange(pageUrl, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            Map<String, Object> body = getRes.getBody();
            int version = ((Number) ((Map<String, Object>) body.get("version")).get("number")).intValue();
            String titleOnPage = (String) body.get("title");
            String html = (String) ((Map<?, ?>) ((Map<?, ?>) body.get("body")).get("storage")).get("value");

            Document doc = Jsoup.parse(html);
            Element bodyEl = doc.body();

            // PR Table
            Element prTable = ensurePrTableExists(doc, bodyEl);
            Element prTbody = prTable.selectFirst("tbody");
            Element existing = prTbody.select("tr").stream().filter(r -> r.selectFirst("td a").attr("href").equals(prUrl)).findFirst().orElse(null);

            String prDescription = "";
            String commitMsg = "";

            if (repoOwner != null && repoName != null && prNumber > 0) {
                HttpHeaders ghHeaders = new HttpHeaders();
                ghHeaders.setBearerAuth(githubToken);
                ghHeaders.setAccept(List.of(MediaType.APPLICATION_JSON));

                Map<String, Object> prDetails = restTemplate.exchange(
                        String.format("https://api.github.com/repos/%s/%s/pulls/%d", repoOwner, repoName, prNumber),
                        HttpMethod.GET, new HttpEntity<>(ghHeaders), Map.class).getBody();

                if (prDetails != null) {
                    prDescription = (String) prDetails.getOrDefault("body", "");
                }

                List<Map<String, Object>> commits = restTemplate.exchange(
                        String.format("https://api.github.com/repos/%s/%s/pulls/%d/commits", repoOwner, repoName, prNumber),
                        HttpMethod.GET, new HttpEntity<>(ghHeaders), List.class).getBody();
                if (commits != null && !commits.isEmpty()) {
                    Map<String, Object> lastCommit = (Map<String, Object>) commits.get(commits.size() - 1).get("commit");
                    commitMsg = (String) lastCommit.get("message");
                }
            }

            if (status == null) {
                if (existing != null) existing.remove();
            } else {
                if (existing != null) {
                    existing.select("td").get(0).text(repoName);
                    existing.select("td").get(1).text(commitMsg);
                    existing.select("td").get(2).selectFirst("a").text(prUrl);
                    existing.select("td").get(3).text(prDescription);
                    existing.select("td").get(4).text(status);
                } else {
                    Element row = prTbody.appendElement("tr");
                    row.appendElement("td").text(repoName);
                    row.appendElement("td").text(commitMsg);
                    row.appendElement("td").appendElement("a").attr("href", prUrl).text(prUrl);
                    row.appendElement("td").text(prDescription);
                    row.appendElement("td").text(status);
                }
            }

            // Properties Table
            if (includePropertyChanges) {
                String api = String.format("https://api.github.com/repos/%s/%s/pulls/%d/files", repoOwner, repoName, prNumber);
                HttpHeaders gh = new HttpHeaders();
                gh.setBearerAuth(githubToken);
                gh.setAccept(List.of(MediaType.APPLICATION_JSON));
                List<Map<String, Object>> files = restTemplate.exchange(api, HttpMethod.GET, new HttpEntity<>(gh), List.class).getBody();

                for (Map<String, Object> file : files) {
                    String filename = file.get("filename").toString();
                    if (!filename.endsWith(".properties")) continue;

                    String patch = (String) file.get("patch");
                    if (patch == null) continue;

                    for (String line : patch.split("\n")) {
                        if ((line.startsWith("+") || line.startsWith("-")) && !line.startsWith("+++") && !line.startsWith("---")) {
                            String cleanLine = line.substring(1).trim();
                            if (!cleanLine.contains("=")) continue;
                            String[] kv = cleanLine.split("=", 2);
                            if (kv.length < 2) continue;

                            if (doc.select("h2:contains(Config Property File Changes)").isEmpty()) {
                                bodyEl.appendElement("h2").text("Config Property File Changes");
                            }
                            Element propTable = ensurePropsTableExists(doc, bodyEl);
                            Element tb = propTable.selectFirst("tbody");
                            Element row = tb.appendElement("tr");
                            row.appendElement("td").text(repoName);
                            row.appendElement("td").text(commitMsg);
                            row.appendElement("td").text(filename);
                            row.appendElement("td").text(kv[0].trim());
                            row.appendElement("td").text(kv[1].trim());
                        }
                    }
                }
            }

            Map<String, Object> payload = Map.of(
                    "id", pageId,
                    "type", "page",
                    "title", titleOnPage,
                    "body", Map.of("storage", Map.of("value", doc.html(), "representation", "storage")),
                    "version", Map.of("number", version + 1)
            );
            restTemplate.exchange(pageUrl, HttpMethod.PUT, new HttpEntity<>(payload, headers), String.class);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private Element ensurePrTableExists(Document doc, Element bodyEl) {
        return doc.select("table.pr-table").stream().findFirst().orElseGet(() -> {
            bodyEl.appendElement("h2").text("Pull Request(s)-");
            Element t = bodyEl.appendElement("table").addClass("pr-table");
            Element head = t.appendElement("thead").appendElement("tr");
            head.appendElement("th").text("Module/Application");
            head.appendElement("th").text("Feature");
            head.appendElement("th").text("PR Link");
            head.appendElement("th").text("Remarks");
            head.appendElement("th").text("Status");
            t.appendElement("tbody");
            return t;
        });
    }

    private Element ensurePropsTableExists(Document doc, Element bodyEl) {
        return doc.select("table.props-table").stream().findFirst().orElseGet(() -> {
            Element t = bodyEl.appendElement("table").addClass("props-table");
            Element head = t.appendElement("thead").appendElement("tr");
            head.appendElement("th").text("Module/Application");
            head.appendElement("th").text("Feature");
            head.appendElement("th").text("Property File");
            head.appendElement("th").text("CCM Key");
            head.appendElement("th").text("CCM Value");
            t.appendElement("tbody");
            return t;
        });
    }
}
//@Service
//public class ConfluenceService {
//
//    @Value("${confluence.email}")
//    private String email;
//    @Value("${confluence.api.token}")
//    private String apiToken;
//
//    @Value("${confluence.workspace}")
//    private String workspace;
//    @Value("${confluence.page.id}")
//    private String pageId;
//
//    @Value("${github.token}")
//    private String githubToken;
//
//    private final RestTemplate restTemplate = new RestTemplate();
//
//    public void updatePullRequestInConfluence(String title, String prUrl, String author,
//                                              String repoOwner, String repoName, int prNumber,
//                                              boolean isMerged, boolean isClosed) {
//        String status = isMerged ? "Merged" : (isClosed ? "Closed" : "In Review");
//        processConfluenceUpdate(title, prUrl, author, repoOwner, repoName, prNumber, status, !isClosed);
//    }
//
//    public void removePullRequestFromConfluence(String prUrl) {
//        processConfluenceUpdate(null, prUrl, null, null, null, 0, null, false);
//    }
//
//    @SuppressWarnings("unchecked")
//    private void processConfluenceUpdate(String title, String prUrl, String author,
//                                         String repoOwner, String repoName, int prNumber,
//                                         String status, boolean includePropertyChanges) {
//        try {
//            String auth = Base64.getEncoder()
//                    .encodeToString((email + ":" + apiToken)
//                            .getBytes(StandardCharsets.UTF_8));
//            HttpHeaders headers = new HttpHeaders();
//            headers.set("Authorization", "Basic " + auth);
//            headers.setContentType(MediaType.APPLICATION_JSON);
//
//            String pageUrl = String.format("https://%s.atlassian.net/wiki/rest/api/content/%s?expand=body.storage,version", workspace, pageId);
//            ResponseEntity<Map> getRes = restTemplate.exchange(pageUrl, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
//            Map<String, Object> body = getRes.getBody();
//            Number versionNumber = (Number) ((Map<String, Object>) body.get("version")).get("number");
//            int version = versionNumber.intValue();
//
//            String titleOnPage = (String) body.get("title");
//            String html = (String)((Map<?,?>)((Map<?,?>)body.get("body")).get("storage")).get("value");
//
//            Document doc = Jsoup.parse(html);
//            Element bodyEl = doc.body();
//
//            // Insert PR Title if not exists
//            if (doc.select("h2:contains(Pull Request(s)-)").isEmpty()) {
//                bodyEl.appendElement("h2").text("Pull Request(s)-");
//            }
//
//            // Fetch PR description and commit message
//            String description = "";
//            String commitMessage = "";
//            if (repoOwner != null && repoName != null && prNumber > 0) {
//                HttpHeaders ghHeaders = new HttpHeaders();
//                ghHeaders.setBearerAuth(githubToken);
//                ghHeaders.setAccept(List.of(MediaType.APPLICATION_JSON));
//
//                Map<String, Object> prData = restTemplate.exchange(
//                        String.format("https://api.github.com/repos/%s/%s/pulls/%d", repoOwner, repoName, prNumber),
//                        HttpMethod.GET, new HttpEntity<>(ghHeaders), Map.class).getBody();
//
//                if (prData != null) {
//                    description = (String) prData.getOrDefault("body", "");
//                }
//
//                List<Map<String, Object>> commits = restTemplate.exchange(
//                        String.format("https://api.github.com/repos/%s/%s/pulls/%d/commits", repoOwner, repoName, prNumber),
//                        HttpMethod.GET, new HttpEntity<>(ghHeaders), List.class).getBody();
//                if (commits != null && !commits.isEmpty()) {
//                    commitMessage = ((Map<String, Object>) commits.get(commits.size() - 1).get("commit")).get("message").toString();
//                }
//            }
//
//            // PR Table
//            Element prTable = ensurePrTableExists(doc, bodyEl);
//            Element prTbody = prTable.selectFirst("tbody");
//            Element existing = prTbody.select("tr")
//                    .stream()
//                    .filter(r -> r.selectFirst("td a").attr("href").equals(prUrl))
//                    .findFirst().orElse(null);
//
//            if (status == null) {
//                if (existing != null) existing.remove();
//            } else {
//                String statusColor = "In Review".equals(status) ? "blue" : "green";
//                if (existing != null) {
//                    existing.select("td").get(0).text(repoName);
//                    existing.select("td").get(1).text(commitMessage);
//                    existing.select("td").get(2).selectFirst("a").text(prUrl);
//                    existing.select("td").get(3).text(description);
//                    existing.select("td").get(4).html("<span style='color: " + statusColor + "; font-weight: bold;'>" + status + "</span>");
//                } else {
//                    Element row = prTbody.appendElement("tr");
//                    row.appendElement("td").text(repoName);
//                    row.appendElement("td").text(commitMessage);
//                    row.appendElement("td").appendElement("a").attr("href", prUrl).text(prUrl);
//                    row.appendElement("td").text(description);
//                    row.appendElement("td").html("<span style='color: " + statusColor + "; font-weight: bold;'>" + status + "</span>");
//                }
//            }
//
//            // Check for property file changes in PR
//            if (includePropertyChanges) {
//                String api = String.format("https://api.github.com/repos/%s/%s/pulls/%d/files", repoOwner, repoName, prNumber);
//                HttpHeaders gh = new HttpHeaders();
//                gh.setBearerAuth(githubToken);
//                gh.setAccept(List.of(MediaType.APPLICATION_JSON));
//                List<Map<String, Object>> files = restTemplate.exchange(api, HttpMethod.GET, new HttpEntity<>(gh), List.class).getBody();
//
//                for (Map<String, Object> file : files) {
//                    String filename = file.get("filename").toString();
//                    if (filename.endsWith(".properties")) {
//                        String patch = (String) file.get("patch");
//                        if (patch == null) continue;
//
//                        StringBuilder changes = new StringBuilder();
//                        for (String line : patch.split("\n")) {
//                            if ((line.startsWith("+") || line.startsWith("-")) && !line.startsWith("+++") && !line.startsWith("---")) {
//                                changes.append(line).append("<br>");
//                            }
//                        }
//
//                        if (!changes.isEmpty()) {
//                            if (doc.select("h2:contains(Config Property File Changes)").isEmpty()) {
//                                bodyEl.appendElement("h2").text("Config Property File Changes");
//                            }
//                            Element propTable = ensurePropsTableExists(doc, bodyEl);
//                            Element tb = propTable.selectFirst("tbody");
//                            Element row = tb.appendElement("tr");
//                            row.appendElement("td").text(title);
//                            row.appendElement("td").appendElement("a").attr("href", prUrl).text(prUrl);
//                            row.appendElement("td").html(changes.toString());
//                        }
//                    }
//                }
//            }
//
//            // Send updated content
//            Map<String, Object> payload = Map.of(
//                    "id", pageId,
//                    "type", "page",
//                    "title", titleOnPage,
//                    "body", Map.of("storage", Map.of("value", doc.html(), "representation", "storage")),
//                    "version", Map.of("number", version + 1)
//            );
//            restTemplate.exchange(pageUrl, HttpMethod.PUT, new HttpEntity<>(payload, headers), String.class);
//
//        } catch(Exception e) {
//            e.printStackTrace();
//        }
//    }
//
//    private Element ensurePrTableExists(Document doc, Element bodyEl) {
//        return doc.select("table.pr-table").stream().findFirst().orElseGet(() -> {
//            Element t = bodyEl.appendElement("table").addClass("pr-table");
//            Element head = t.appendElement("thead").appendElement("tr");
//            head.appendElement("th").text("Module/Application");
//            head.appendElement("th").text("Feature");
//            head.appendElement("th").text("PR Link");
//            head.appendElement("th").text("Remarks");
//            head.appendElement("th").text("Status");
//            t.appendElement("tbody");
//            return t;
//        });
//    }
//
//    private Element ensurePropsTableExists(Document doc, Element bodyEl) {
//        return doc.select("table.props-table").stream().findFirst().orElseGet(() -> {
//            Element t = bodyEl.appendElement("table").addClass("props-table");
//            Element head = t.appendElement("thead").appendElement("tr");
//            head.appendElement("th").text("PR Title");
//            head.appendElement("th").text("PR Link");
//            head.appendElement("th").text("Changes");
//            t.appendElement("tbody");
//            return t;
//        });
//    }
//}


//@Service
//public class ConfluenceService {
//
//    @Value("${confluence.email}")
//    private String email;
//    @Value("${confluence.api.token}")
//    private String apiToken;
//    @Value("${confluence.workspace}")
//    private String workspace;
//    @Value("${confluence.page.id}")
//    private String pageId;
//
//    @Value("${github.token}")
//    private String githubToken;
//
//    private final RestTemplate restTemplate = new RestTemplate();
//
//    public void updatePullRequestInConfluence(String title, String prUrl, String author,
//                                              String repoOwner, String repoName, int prNumber,
//                                              boolean isMerged, boolean isClosed) {
//        String status = isMerged ? "Merged" : (isClosed ? "Closed" : "In Review");
//        processConfluenceUpdate(title, prUrl, author, repoOwner, repoName, prNumber, status, !isClosed);
//    }
//
//    public void removePullRequestFromConfluence(String prUrl) {
//        processConfluenceUpdate(null, prUrl, null, null, null, 0, null, false);
//    }
//
//    @SuppressWarnings("unchecked")
//    private void processConfluenceUpdate(String title, String prUrl, String author,
//                                         String repoOwner, String repoName, int prNumber,
//                                         String status, boolean includePropertyChanges) {
//        try {
//            String auth = Base64.getEncoder()
//                    .encodeToString((email + ":" + apiToken)
//                            .getBytes(StandardCharsets.UTF_8));
//            HttpHeaders headers = new HttpHeaders();
//            headers.set("Authorization", "Basic " + auth);
//            headers.setContentType(MediaType.APPLICATION_JSON);
//
//            String pageUrl = String.format("https://%s.atlassian.net/wiki/rest/api/content/%s?expand=body.storage,version", workspace, pageId);
//            ResponseEntity<Map> getRes = restTemplate.exchange(pageUrl, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
//            Map<String, Object> body = getRes.getBody();
//            Number versionNumber = (Number) ((Map<String, Object>) body.get("version")).get("number");
//            int version = versionNumber.intValue();
//
//            String titleOnPage = (String) body.get("title");
//            String html = (String)((Map<?,?>)((Map<?,?>)body.get("body")).get("storage")).get("value");
//
//            Document doc = Jsoup.parse(html);
//            Element bodyEl = doc.body();
//
//            // Insert PR Title if not exists update branch
//            if (doc.select("h2:contains(Pull Requests Summary)").isEmpty()) {
//                bodyEl.appendElement("h2").text("Pull Requests Summary");
//            }
//
//            // PR Table
//            Element prTable = ensurePrTableExists(doc, bodyEl);
//            Element prTbody = prTable.selectFirst("tbody");
//            Element existing = prTbody.select("tr")
//                    .stream()
//                    .filter(r -> r.selectFirst("td a").attr("href").equals(prUrl))
//                    .findFirst().orElse(null);
//
//            if (status == null) {
//                if (existing != null) existing.remove();
//            } else {
//                if (existing != null) {
//                    existing.select("td").get(0).text(title);
//                    existing.select("td").get(1).text(author);
//                    existing.select("td").get(2).selectFirst("a").text(prUrl);
//                    existing.select("td").get(3).text(status);
//                } else {
//                    Element row = prTbody.appendElement("tr");
//                    row.appendElement("td").text(title);
//                    row.appendElement("td").text(author);
//                    row.appendElement("td").appendElement("a").attr("href", prUrl).text(prUrl);
//                    row.appendElement("td").text(status);
//                }
//            }
//
//            // Check for property file changes in PR
//            if (includePropertyChanges) {
//                String api = String.format("https://api.github.com/repos/%s/%s/pulls/%d/files", repoOwner, repoName, prNumber);
//                HttpHeaders gh = new HttpHeaders();
//                gh.setBearerAuth(githubToken);
//                gh.setAccept(List.of(MediaType.APPLICATION_JSON));
//                List<Map<String, Object>> files = restTemplate.exchange(api, HttpMethod.GET, new HttpEntity<>(gh), List.class).getBody();
//
//                for (Map<String, Object> file : files) {
//                    String filename = file.get("filename").toString();
//                    if (filename.endsWith(".properties")) {
//                        String patch = (String) file.get("patch");
//                        if (patch == null) continue;
//
//                        StringBuilder changes = new StringBuilder();
//                        for (String line : patch.split("\n")) {
//                            if ((line.startsWith("+") || line.startsWith("-")) && !line.startsWith("+++") && !line.startsWith("---")) {
//                                changes.append(line).append("<br>");
//                            }
//                        }
//
//                        if (!changes.isEmpty()) {
//                            if (doc.select("h2:contains(Config Property File Changes)").isEmpty()) {
//                                bodyEl.appendElement("h2").text("Config Property File Changes");
//                            }
//                            Element propTable = ensurePropsTableExists(doc, bodyEl);
//                            Element tb = propTable.selectFirst("tbody");
//                            Element row = tb.appendElement("tr");
//                            row.appendElement("td").text(title);
//                            row.appendElement("td").appendElement("a").attr("href", prUrl).text(prUrl);
//                            row.appendElement("td").html(changes.toString());
//                        }
//                    }
//                }
//            }
//
//            // Send updated content
//            Map<String, Object> payload = Map.of(
//                    "id", pageId,
//                    "type", "page",
//                    "title", titleOnPage,
//                    "body", Map.of("storage", Map.of("value", doc.html(), "representation", "storage")),
//                    "version", Map.of("number", version + 1)
//            );
//            restTemplate.exchange(pageUrl, HttpMethod.PUT, new HttpEntity<>(payload, headers), String.class);
//
//        } catch(Exception e) {
//            e.printStackTrace();
//        }
//    }
//
//    private Element ensurePrTableExists(Document doc, Element bodyEl) {
//        return doc.select("table.pr-table").stream().findFirst().orElseGet(() -> {
//            Element t = bodyEl.appendElement("table").addClass("pr-table");
//            Element head = t.appendElement("thead").appendElement("tr");
//            head.appendElement("th").text("PR Title");
//            head.appendElement("th").text("Author");
//            head.appendElement("th").text("PR Link");
//            head.appendElement("th").text("Status");
//            t.appendElement("tbody");
//            return t;
//        });
//    }
//
//    private Element ensurePropsTableExists(Document doc, Element bodyEl) {
//        return doc.select("table.props-table").stream().findFirst().orElseGet(() -> {
//            Element t = bodyEl.appendElement("table").addClass("props-table");
//            Element head = t.appendElement("thead").appendElement("tr");
//            head.appendElement("th").text("PR Title");
//            head.appendElement("th").text("PR Link");
//            head.appendElement("th").text("Changes");
//            t.appendElement("tbody");
//            return t;
//        });
//    }
//}
