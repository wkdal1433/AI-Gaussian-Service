package com.example.file_upload_service.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/viewer/status")
public class ViewerController {

    private final ConcurrentHashMap<String, String> viewerUrlMap;

    // ViewerController가 FileUploadController의 URL 맵을 참조
    public ViewerController(FileUploadController fileUploadController) {
        this.viewerUrlMap = fileUploadController.getViewerUrlMap();
    }

    @GetMapping("/{clientId}")
    public ResponseEntity<?> getViewerStatus(@PathVariable String clientId) {
        String viewerUrl = viewerUrlMap.get(clientId);
        if (viewerUrl == null) {
            return ResponseEntity.ok(new ViewerStatusResponse(null));
        }
        return ResponseEntity.ok(new ViewerStatusResponse(viewerUrl));
    }

    public static class ViewerStatusResponse {
        private final String viewerUrl;

        public ViewerStatusResponse(String viewerUrl) {
            this.viewerUrl = viewerUrl;
        }

        public String getViewerUrl() {
            return viewerUrl;
        }
    }
}
