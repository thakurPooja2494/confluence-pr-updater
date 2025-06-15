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
                                              String repoOwner, String repoName, int pullNumber,
                                              boolean isMerged, boolean isClosed) {

        try {
            String auth = Base64.getEncoder().encodeToString((email + ":" + apiToken).getBytes(StandardCharsets.UTF_8));
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Basic " + auth);
            headers.setContentType(MediaType.APPLICATION_JSON);

            // Fetch page
            String pageUrl = "https://" + workspace + ".atlassian.net/wiki/rest/api/content/" + pageId + "?expand=body.storage,version";
            ResponseEntity<Map> response = restTemplate.exchange(pageUrl, HttpMethod.GET, new HttpEntity<>(headers), Map.class);

            Map<String, Object> pageData = response.getBody();
            int version = (int) ((Map<String, Object>) pageData.get("version")).get("number");
            String titleOnPage = (String) pageData.get("title");
            String htmlContent = (String) ((Map<String, Object>) ((Map<String, Object>) pageData.get("body")).get("storage")).get("value");

            Document doc = Jsoup.parse(htmlContent);
            Element body = doc.body();

            Element prTable = findOrCreatePrTable(doc, body);

            Element tbody = prTable.selectFirst("tbody");
            boolean found = false;

            for (Element row : tbody.select("tr")) {
                Element linkCell = row.select("td").get(2);
                if (linkCell.selectFirst("a").attr("href").equals(prUrl)) {
                    found = true;
                    if (isClosed && !isMerged) {
                        row.remove(); // PR was cancelled
                    } else {
                        row.select("td").get(4).text(isMerged ? "Merged" : "In Review");
                    }
                    break;
                }
            }

            if (!found && !isClosed) {
                // New PR
                Element newRow = tbody.appendElement("tr");
                newRow.appendElement("td").text(title);
                newRow.appendElement("td").text(author);
                newRow.appendElement("td").appendElement("a").attr("href", prUrl).text(prUrl);
                newRow.appendElement("td").text("NA"); // Optional column for changes
                newRow.appendElement("td").text("In Review");
            }

            // Update page
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

    private Element findOrCreatePrTable(Document doc, Element body) {
        Element prTable = doc.select("table").stream()
                .filter(t -> {
                    Element th = t.selectFirst("th");
                    return th != null && th.text().equalsIgnoreCase("PR Title");
                })
                .findFirst().orElse(null);

        if (prTable == null) {
            prTable = body.appendElement("table").addClass("pr-table");
            Element thead = prTable.appendElement("thead").appendElement("tr");
            thead.appendElement("th").text("PR Title");
            thead.appendElement("th").text("Author");
            thead.appendElement("th").text("PR Link");
            thead.appendElement("th").text("Changes");
            thead.appendElement("th").text("Status");
            prTable.appendElement("tbody");
        }

        return prTable;
    }
}
