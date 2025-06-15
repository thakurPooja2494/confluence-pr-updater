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

            switch (action) {
                case "opened":
                case "reopened":
                    logger.info("PR opened or reopened: marking as In Review");
                    confluenceService.updatePullRequestInConfluence(title, url, author, repoOwner, repoName, prNumber, false, false);
                    break;

                case "closed":
                    boolean merged = (Boolean) pr.get("merged");
                    if (merged) {
                        logger.info("PR merged: updating status to Merged");
                        confluenceService.updatePullRequestInConfluence(title, url, author, repoOwner, repoName, prNumber, true, true);
                    } else {
                        logger.info("PR closed without merge: removing from Confluence");
                        confluenceService.removePullRequestFromConfluence(url);
                    }
                    break;

                default:
                    logger.info("Ignoring unsupported PR action: {}", action);
            }

            return ResponseEntity.ok("Processed GitHub webhook for action: " + action);
        } catch (Exception e) {
            logger.error("Failed to process PR webhook", e);
            return ResponseEntity.status(500).body("Webhook processing failed");
        }
    }
}
