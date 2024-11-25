package com.example.file_upload_service.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;
import java.net.UnknownHostException;

@RestController
@RequestMapping("/api")
public class IpController {

    @Value("${server.port}") // 서버 포트를 가져옴
    private String serverPort;

    @GetMapping("/server-url")
    public ResponseEntity<String> getServerUrl() {
        try {
            InetAddress localHost = InetAddress.getLocalHost();
            String serverUrl = "http://" + localHost.getHostAddress() + ":" + serverPort;
            return ResponseEntity.ok(serverUrl);
        } catch (UnknownHostException e) {
            return ResponseEntity.status(500).body("Unable to retrieve server URL.");
        }
    }
}

