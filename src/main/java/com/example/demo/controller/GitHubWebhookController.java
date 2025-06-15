package com.example.demo.controller;

import com.example.demo.service.ConfluenceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/github")
public class GitHubWebhookController {

    private final ConfluenceService confluenceService;

    public GitHubWebhookController(ConfluenceService confluenceService) {
        this.confluenceService = confluenceService;
    }

    @PostMapping("/webhook")
    public ResponseEntity<String> handlePullRequest(@RequestBody Map<String, Object> payload) {
        String action = (String) payload.get("action");
        if (!"opened".equals(action)) {
            return ResponseEntity.ok("Ignored non-open PR event");
        }

        Map<String, Object> pr = (Map<String, Object>) payload.get("pull_request");
        String title = (String) pr.get("title");
        String url = (String) pr.get("html_url");
        String author = (String) ((Map<String, Object>) pr.get("user")).get("login");
        int prNumber = (int) pr.get("number");

        Map<String, Object> repo = (Map<String, Object>) payload.get("repository");
        String repoName = (String) repo.get("name");
        String repoOwner = (String) ((Map<String, Object>) repo.get("owner")).get("login");

        confluenceService.updateConfluencePage(title, url, author, repoOwner, repoName, prNumber);

        return ResponseEntity.ok("PR added to Confluence");
    }
}
