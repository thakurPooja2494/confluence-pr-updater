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

    private final RestTemplate restTemplate = new RestTemplate();

    // ✅ Public method used by controller
    public void updatePullRequestInConfluence(String title,
                                              String url,
                                              String author,
                                              String repoOwner,
                                              String repoName,
                                              int prNumber,
                                              boolean isMerged,
                                              boolean isClosed) {
        String status;

        if (isMerged) {
            status = "Merged";
        } else if (isClosed) {
            status = "Closed";
        } else {
            status = "In Review";
        }

        updatePullRequestInConfluence(title, url, author, status, false);
    }

    // ✅ Used when PR is closed but not merged
    public void removePullRequestFromConfluence(String prUrl) {
        updatePullRequestInConfluence(null, prUrl, null, null, true);
    }

    // 🔒 Core logic for updating/removing PR rows in Confluence table
    private void updatePullRequestInConfluence(String title, String prUrl, String author, String status, boolean removeOnly) {
        try {
            String auth = Base64.getEncoder().encodeToString((email + ":" + apiToken).getBytes(StandardCharsets.UTF_8));
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Basic " + auth);
            headers.setContentType(MediaType.APPLICATION_JSON);

            String pageUrl = "https://" + workspace + ".atlassian.net/wiki/rest/api/content/" + pageId + "?expand=body.storage,version";
            ResponseEntity<Map> getResponse = restTemplate.exchange(pageUrl, HttpMethod.GET, new HttpEntity<>(headers), Map.class);

            Map<String, Object> body = getResponse.getBody();
            int version = (int) ((Map<String, Object>) body.get("version")).get("number");
            String titleOnPage = (String) body.get("title");
            String htmlContent = (String) ((Map<String, Object>) ((Map<String, Object>) body.get("body")).get("storage")).get("value");

            Document doc = Jsoup.parse(htmlContent);
            Element bodyEl = doc.body();

            Element prTable = doc.select("table").stream()
                    .filter(table -> {
                        Element th = table.selectFirst("th");
                        return th != null && th.text().equalsIgnoreCase("PR Title");
                    })
                    .findFirst()
                    .orElse(null);

            if (prTable == null) {
                prTable = bodyEl.appendElement("table").addClass("pr-table");
                Element head = prTable.appendElement("thead").appendElement("tr");
                head.appendElement("th").text("PR Title");
                head.appendElement("th").text("Author");
                head.appendElement("th").text("PR Link");
                head.appendElement("th").text("Status");
                prTable.appendElement("tbody");
            }

            Element tbody = prTable.selectFirst("tbody");
            if (tbody == null) {
                tbody = prTable.appendElement("tbody");
            }

            Element existingRow = null;
            for (Element row : tbody.select("tr")) {
                Element link = row.select("td a").first();
                if (link != null && prUrl.equals(link.attr("href"))) {
                    existingRow = row;
                    break;
                }
            }

            if (removeOnly) {
                if (existingRow != null) {
                    existingRow.remove();
                }
            } else {
                if (existingRow != null) {
                    existingRow.select("td").get(0).text(title);
                    existingRow.select("td").get(1).text(author);
                    existingRow.select("td").get(2).select("a").first().attr("href", prUrl).text(prUrl);
                    existingRow.select("td").get(3).text(status);
                } else {
                    Element newRow = tbody.appendElement("tr");
                    newRow.appendElement("td").text(title);
                    newRow.appendElement("td").text(author);
                    newRow.appendElement("td").appendElement("a").attr("href", prUrl).text(prUrl);
                    newRow.appendElement("td").text(status);
                }
            }

            Map<String, Object> updatePayload = Map.of(
                    "id", pageId,
                    "type", "page",
                    "title", titleOnPage,
                    "body", Map.of("storage", Map.of("value", doc.html(), "representation", "storage")),
                    "version", Map.of("number", version + 1)
            );

            restTemplate.exchange(
                    "https://" + workspace + ".atlassian.net/wiki/rest/api/content/" + pageId,
                    HttpMethod.PUT,
                    new HttpEntity<>(updatePayload, headers),
                    String.class
            );

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
