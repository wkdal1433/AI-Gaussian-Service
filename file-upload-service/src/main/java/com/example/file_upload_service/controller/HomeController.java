package com.example.file_upload_service.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    @GetMapping("/")
    public String index() {
        // templates/index.html 파일을 렌더링
        return "index";
    }
}
