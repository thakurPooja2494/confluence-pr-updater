package com.example.demo.service;

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

    public void updateConfluencePage(String title, String prUrl, String author) {
        try {
            String auth = Base64.getEncoder().encodeToString((email + ":" + apiToken).getBytes(StandardCharsets.UTF_8));
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Basic " + auth);
            headers.setContentType(MediaType.APPLICATION_JSON);

            String urlGet = "https://" + workspace + ".atlassian.net/wiki/rest/api/content/" + pageId + "?expand=body.storage,version";
            HttpEntity<Void> getEntity = new HttpEntity<>(headers);
            ResponseEntity<Map> getResponse = restTemplate.exchange(urlGet, HttpMethod.GET, getEntity, Map.class);

            if (!getResponse.getStatusCode().is2xxSuccessful()) {
                System.err.println("Failed to fetch Confluence page content");
                return;
            }

            Map<String, Object> body = getResponse.getBody();
            Map<String, Object> version = (Map<String, Object>) body.get("version");
            int versionNumber = (int) version.get("number");
            Map<String, Object> bodyStorage = (Map<String, Object>) ((Map<String, Object>) body.get("body")).get("storage");
            String oldContent = (String) bodyStorage.get("value");

            String newEntry = "<p><b>" + title + "</b><br/>Author: " + author + "<br/>Link: <a href='" + prUrl + "'>" + prUrl + "</a></p>";
            String updatedContent = oldContent + newEntry;

            Map<String, Object> updatePayload = Map.of(
                    "id", pageId,
                    "type", "page",
                    "title", body.get("title"),
                    "body", Map.of("storage", Map.of("value", updatedContent, "representation", "storage")),
                    "version", Map.of("number", versionNumber + 1)
            );

            HttpEntity<Map<String, Object>> putEntity = new HttpEntity<>(updatePayload, headers);
            String urlPut = "https://" + workspace + ".atlassian.net/wiki/rest/api/content/" + pageId;
            ResponseEntity<String> putResponse = restTemplate.exchange(urlPut, HttpMethod.PUT, putEntity, String.class);

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
