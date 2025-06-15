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

    @SuppressWarnings("unchecked")
    public void updateConfluencePage(String title, String prUrl, String author, String repoOwner, String repoName, int pullNumber) {
        try {
            // 1. Auth headers
            String auth = Base64.getEncoder().encodeToString((email + ":" + apiToken).getBytes(StandardCharsets.UTF_8));
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Basic " + auth);
            headers.setContentType(MediaType.APPLICATION_JSON);

            // 2. Get current Confluence page content
            String pageUrl = "https://" + workspace + ".atlassian.net/wiki/rest/api/content/" + pageId + "?expand=body.storage,version";
            ResponseEntity<Map> getResponse = restTemplate.exchange(pageUrl, HttpMethod.GET, new HttpEntity<>(headers), Map.class);

            Map<String, Object> body = getResponse.getBody();
            int version = (int) ((Map<String, Object>) body.get("version")).get("number");
            String titleOnPage = (String) body.get("title");
            String htmlContent = (String) ((Map<String, Object>) ((Map<String, Object>) body.get("body")).get("storage")).get("value");

            // 3. GitHub API call for PR files
            HttpHeaders gitHeaders = new HttpHeaders();
            gitHeaders.setBearerAuth(githubToken);
            gitHeaders.setAccept(List.of(MediaType.APPLICATION_JSON));

            String filesApi = "https://api.github.com/repos/" + repoOwner + "/" + repoName + "/pulls/" + pullNumber + "/files";
            ResponseEntity<List> fileResponse = restTemplate.exchange(filesApi, HttpMethod.GET, new HttpEntity<>(gitHeaders), List.class);
            List<Map<String, Object>> files = (List<Map<String, Object>>) fileResponse.getBody();

            StringBuilder codeFileSummary = new StringBuilder();
            StringBuilder configFileRows = new StringBuilder();

            for (Map<String, Object> file : files) {
                String filename = (String) file.get("filename");
                int additions = (int) file.get("additions");
                int deletions = (int) file.get("deletions");
                String summary = "(+" + additions + "/-" + deletions + ")";

                if (filename.endsWith(".properties") || filename.contains("/config/") || filename.contains("/env/")) {
                    configFileRows.append("<tr>")
                            .append("<td>").append(filename).append("</td>")
                            .append("<td>").append(title).append("</td>")
                            .append("<td>").append(summary).append("</td>")
                            .append("</tr>");
                } else {
                    codeFileSummary.append(filename).append(" ").append(summary).append("<br/>");
                }
            }

            // 4. Modify HTML using Jsoup
            Document doc = Jsoup.parse(htmlContent);
            Element bodyEl = doc.body();

            Element prTable = doc.selectFirst("table.pr-table");
            if (prTable == null) {
                prTable = bodyEl.appendElement("table").addClass("pr-table");
                Element head = prTable.appendElement("thead").appendElement("tr");
                head.appendElement("th").text("PR Title");
                head.appendElement("th").text("Author");
                head.appendElement("th").text("PR Link");
                head.appendElement("th").text("Changes");

                prTable.appendElement("tbody");
            }

            Element tbody = prTable.selectFirst("tbody");
            Element newRow = tbody.appendElement("tr");
            newRow.appendElement("td").text(title);
            newRow.appendElement("td").text(author);
            newRow.appendElement("td").appendElement("a").attr("href", prUrl).text(prUrl);

            newRow.appendElement("td").html(
                    codeFileSummary.length() > 0 ? codeFileSummary.toString() : "No code file changes"
            );

            if (configFileRows.length() > 0) {
                Element configTable = doc.selectFirst("table.config-table");
                if (configTable == null) {
                    configTable = bodyEl.appendElement("table").addClass("config-table");
                    Element header = configTable.appendElement("thead").appendElement("tr");
                    header.appendElement("th").text("File Name");
                    header.appendElement("th").text("PR Title");
                    header.appendElement("th").text("Changes Summary");

                    configTable.appendElement("tbody");
                }

                Element configBody = configTable.selectFirst("tbody");
                configBody.append(configFileRows.toString());
            }

            // 5. Final PUT request to update Confluence
            Map<String, Object> payload = Map.of(
                    "id", pageId,
                    "type", "page",
                    "title", titleOnPage,
                    "body", Map.of("storage", Map.of("value", doc.html(), "representation", "storage")),
                    "version", Map.of("number", version + 1)
            );

            ResponseEntity<String> putResponse = restTemplate.exchange(
                    "https://" + workspace + ".atlassian.net/wiki/rest/api/content/" + pageId,
                    HttpMethod.PUT,
                    new HttpEntity<>(payload, headers),
                    String.class
            );

            if (putResponse.getStatusCode().is2xxSuccessful()) {
                System.out.println("✅ Confluence page updated.");
            } else {
                System.err.println("❌ Failed to update Confluence page: " + putResponse.getBody());
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
