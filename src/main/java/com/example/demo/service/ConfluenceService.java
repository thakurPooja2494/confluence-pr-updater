// ConfluenceService.java
package com.example.demo.service;

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
        processConfluenceUpdate(title, prUrl, author, repoOwner, repoName, status, prNumber, !isClosed);
    }

    public void removePullRequestFromConfluence(String prUrl) {
        processConfluenceUpdate(null, prUrl, null, null, null, null, -1, false);
    }

    @SuppressWarnings("unchecked")
    private void processConfluenceUpdate(String title, String prUrl, String author,
                                         String repoOwner, String repoName, String status,
                                         int prNumber, boolean includePropertyChanges) {
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
            Number versionNumber = (Number) ((Map<String, Object>) body.get("version")).get("number");
            int version = versionNumber.intValue();

            String titleOnPage = (String) body.get("title");
            String html = (String)((Map<?,?>)((Map<?,?>)body.get("body")).get("storage")).get("value");

            Document doc = Jsoup.parse(html);
            Element bodyEl = doc.body();

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
                    existing.select("td").get(0).text(title);
                    existing.select("td").get(1).text(author);
                    existing.select("td").get(2).selectFirst("a").text(prUrl);
                    existing.select("td").get(3).text(status);
                } else {
                    Element row = prTbody.appendElement("tr");
                    row.appendElement("td").text(title);
                    row.appendElement("td").text(author);
                    row.appendElement("td").appendElement("a").attr("href", prUrl).text(prUrl);
                    row.appendElement("td").text(status);
                }
            }

            // Check for property file changes in PR
            if (includePropertyChanges && prNumber > 0) {
                String api = String.format("https://api.github.com/repos/%s/%s/pulls/%d/files", repoOwner, repoName, prNumber);
                HttpHeaders gh = new HttpHeaders();
                gh.setBearerAuth(githubToken);
                gh.setAccept(List.of(MediaType.APPLICATION_JSON));
                List<Map<String, Object>> files = restTemplate.exchange(api, HttpMethod.GET, new HttpEntity<>(gh), List.class).getBody();

                boolean hasProps = files.stream().anyMatch(f -> f.get("filename").toString().endsWith(".properties"));
                if (hasProps) {
                    Element propTable = ensurePropsTableExists(doc, bodyEl);
                    Element tb = propTable.selectFirst("tbody");
                    Element row = tb.appendElement("tr");
                    row.appendElement("td").text(title);
                    row.appendElement("td").appendElement("a").attr("href", prUrl).text(prUrl);
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
            Element t = bodyEl.appendElement("table").addClass("pr-table");
            Element head = t.appendElement("thead").appendElement("tr");
            head.appendElement("th").text("PR Title");
            head.appendElement("th").text("Author");
            head.appendElement("th").text("PR Link");
            head.appendElement("th").text("Status");
            t.appendElement("tbody");
            return t;
        });
    }

    private Element ensurePropsTableExists(Document doc, Element bodyEl) {
        return doc.select("table.props-table").stream().findFirst().orElseGet(() -> {
            Element t = bodyEl.appendElement("table").addClass("props-table");
            Element head = t.appendElement("thead").appendElement("tr");
            head.appendElement("th").text("PR Title");
            head.appendElement("th").text("PR Link");
            t.appendElement("tbody");
            return t;
        });
    }
}
