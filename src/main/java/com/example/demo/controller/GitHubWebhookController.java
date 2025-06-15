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
        logger.info("Received webhook with action: {}", action);

        if (!"opened".equals(action)) {
            logger.info("Ignored event with action: {}", action);
            return ResponseEntity.ok("Ignored non-open PR event");
        }

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

            confluenceService.updateConfluencePage(title, url, author, repoOwner, repoName, prNumber);

            logger.info("Successfully updated Confluence page for PR #{}", prNumber);
            return ResponseEntity.ok("PR added to Confluence");
        } catch (Exception e) {
            logger.error("Error processing webhook payload", e);
            return ResponseEntity.status(500).body("Internal Server Error");
        }
    }
}
