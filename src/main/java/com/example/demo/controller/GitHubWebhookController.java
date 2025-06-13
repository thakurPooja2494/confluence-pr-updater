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
    public ResponseEntity<String> handlePullRequest(@RequestBody Map<String, Object> payload,
                                                    @RequestHeader("X-GitHub-Event") String event) {
        if ("ping".equals(event)) {
            System.out.println("📡 Received ping from GitHub.");
            return ResponseEntity.ok("Ping received");
        }

        if (!"pull_request".equals(event)) {
            return ResponseEntity.ok("Event ignored: " + event);
        }

        String action = (String) payload.get("action");
        if (!"opened".equals(action)) {
            return ResponseEntity.ok("Ignored non-open PR event");
        }

        Map<String, Object> pr = (Map<String, Object>) payload.get("pull_request");
        String title = (String) pr.get("title");
        String url = (String) pr.get("html_url");
        String author = (String) ((Map<String, Object>) pr.get("user")).get("login");

        confluenceService.updateConfluencePage(title, url, author);

        return ResponseEntity.ok("PR added to Confluence");
    }

}
