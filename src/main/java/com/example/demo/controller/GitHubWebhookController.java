package com.example.demo.controller;

import com.example.demo.service.ConfluenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/github")
public class GitHubWebhookController {

    private static final Logger logger = LoggerFactory.getLogger(GitHubWebhookController.class);
    private final ConfluenceService confluenceService;

    public GitHubWebhookController(ConfluenceService confluenceService) {
        this.confluenceService = confluenceService;
    }

    @PostMapping("/webhook")
    public ResponseEntity<String> handlePullRequest(@RequestBody Map<String, Object> payload) {
        String action = (String) payload.get("action");
        logger.info("Received GitHub PR webhook with action: {}", action);

        try {
            Map<String, Object> pr = (Map<String, Object>) payload.get("pull_request");
            String title = (String) pr.get("title");
            String url = (String) pr.get("html_url");
            String author = (String) ((Map<String, Object>) pr.get("user")).get("login");
            int prNumber = (int) pr.get("number");

            Map<String, Object> repo = (Map<String, Object>) payload.get("repository");
            String repoName = (String) repo.get("name");
            String repoOwner = (String) ((Map<String, Object>) repo.get("owner")).get("login");

            logger.info("Processing PR #{}: '{}' by {} in repo {}/{}", prNumber, title, author, repoOwner, repoName);

            // Handle PR states
            if ("opened".equals(action)) {
                confluenceService.updatePullRequestInConfluence(title, url, author, repoOwner, repoName, prNumber, false, false);
            } else if ("closed".equals(action)) {
                boolean merged = (Boolean) pr.get("merged");
                if (merged) {
                    confluenceService.updatePullRequestInConfluence(title, url, author, repoOwner, repoName, prNumber, true, true);
                } else {
                    // PR was closed without merge (cancelled)
                    confluenceService.updatePullRequestInConfluence(title, url, author, repoOwner, repoName, prNumber, false, true);
                }
            } else {
                logger.info("No Confluence update required for action: {}", action);
            }

            return ResponseEntity.ok("Webhook processed");
        } catch (Exception e) {
            logger.error("Error processing webhook", e);
            return ResponseEntity.status(500).body("Internal Server Error");
        }
    }
}
