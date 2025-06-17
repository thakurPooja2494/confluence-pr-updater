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

@Service
public class ConfluenceService {

    @Value("${confluence.email}")
    private String email;
    @Value("${confluence.api.token}")
    private String apiToken;
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
        processConfluenceUpdate(title, prUrl, repoOwner, repoName, prNumber, status, !isClosed);
    }

    public void removePullRequestFromConfluence(String prUrl) {
        processConfluenceUpdate(null, prUrl, null, null, 0, null, false);
    }

    @SuppressWarnings("unchecked")
    private void processConfluenceUpdate(String title, String prUrl, String repoOwner,
                                         String repoName, int prNumber, String status,
                                         boolean includePropertyChanges) {
        try {
            String auth = Base64.getEncoder()
                    .encodeToString((email + ":" + apiToken)
                            .getBytes(StandardCharsets.UTF_8));
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Basic " + auth);
            headers.setContentType(MediaType.APPLICATION_JSON);

            String pageUrl = String.format("https://%s.atlassian.net/wiki/rest/api/content/%s?expand=body.storage,version", workspace, pageId);
            ResponseEntity<Map> getRes = restTemplate.exchange(pageUrl, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            Map<String, Object> body = getRes.getBody();
            int version = ((Number) ((Map<String, Object>) body.get("version")).get("number")).intValue();
            String titleOnPage = (String) body.get("title");
            String html = (String)((Map<?,?>)((Map<?,?>)body.get("body")).get("storage")).get("value");

            Document doc = Jsoup.parse(html);
            Element bodyEl = doc.body();

            // Fetch PR description and last commit message
            String description = "";
            String commitMessage = "";
            if (repoOwner != null && repoName != null && prNumber > 0) {
                HttpHeaders ghHeaders = new HttpHeaders();
                ghHeaders.setBearerAuth(githubToken);
                ghHeaders.setAccept(List.of(MediaType.APPLICATION_JSON));

                Map<String, Object> prData = restTemplate.exchange(
                        String.format("https://api.github.com/repos/%s/%s/pulls/%d", repoOwner, repoName, prNumber),
                        HttpMethod.GET, new HttpEntity<>(ghHeaders), Map.class).getBody();

                if (prData != null) {
                    description = (String) prData.getOrDefault("body", "");
                }

                List<Map<String, Object>> commits = restTemplate.exchange(
                        String.format("https://api.github.com/repos/%s/%s/pulls/%d/commits", repoOwner, repoName, prNumber),
                        HttpMethod.GET, new HttpEntity<>(ghHeaders), List.class).getBody();
                if (commits != null && !commits.isEmpty()) {
                    commitMessage = ((Map<String, Object>) commits.get(commits.size() - 1).get("commit")).get("message").toString();
                }
            }

            // PR Table
            Element prTable = ensurePrTableExists(doc, bodyEl);
            Element prTbody = prTable.selectFirst("tbody");
            Element existing = prTbody.select("tr")
                    .stream()
                    .filter(r -> r.selectFirst("td a").attr("href").equals(prUrl))
                    .findFirst().orElse(null);

            if (status == null) {
                if (existing != null) existing.remove();
            } else {
                if (existing != null) {
                    existing.select("td").get(0).text(repoName);
                    existing.select("td").get(1).text(description);
                    existing.select("td").get(2).selectFirst("a").text(prUrl);
                    existing.select("td").get(3).text(commitMessage);
                    existing.select("td").get(4).text(status);
                } else {
                    Element row = prTbody.appendElement("tr");
                    row.appendElement("td").text(repoName);
                    row.appendElement("td").text(description);
                    row.appendElement("td").appendElement("a").attr("href", prUrl).text(prUrl);
                    row.appendElement("td").text(commitMessage);
                    row.appendElement("td").text(status);
                }
            }

            // Send updated content
            Map<String, Object> payload = Map.of(
                    "id", pageId,
                    "type", "page",
                    "title", titleOnPage,
                    "body", Map.of("storage", Map.of("value", doc.html(), "representation", "storage")),
                    "version", Map.of("number", version + 1)
            );
            restTemplate.exchange(pageUrl, HttpMethod.PUT, new HttpEntity<>(payload, headers), String.class);

        } catch(Exception e) {
            e.printStackTrace();
        }
    }

    private Element ensurePrTableExists(Document doc, Element bodyEl) {
        return doc.select("table.pr-table").stream().findFirst().orElseGet(() -> {
            bodyEl.appendElement("h2").text("Pull Requests Overview");
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
}
