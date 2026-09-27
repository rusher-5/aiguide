package com.guide.web.controller;

import com.guide.service.rag.RagService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 页面路由：游客端 / 与管理后台 /admin。
 * 页面文件置于 classpath:/pages/，由 Controller 读出返回——
 * 保证 /admin 的鉴权挑战在页面层面即生效（静态资源直连会绕过拦截器）。
 */
@RestController
public class PageController {

    private final RagService ragService;

    private String indexHtml;
    private String adminHtml;

    public PageController(RagService ragService) {
        this.ragService = ragService;
    }

    @PostConstruct
    public void init() throws IOException {
        indexHtml = readPage("pages/index.html");
        adminHtml = readPage("pages/admin.html");
        // jieba 词典预热：把分词器初始化耗时挪出首个请求
        ragService.warmUp();
    }

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> index() {
        return html(indexHtml);
    }

    /** 管理后台页面：拦截器先鉴权（401 + Basic 挑战），通过后返回页面 */
    @GetMapping(value = "/admin", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> admin() {
        return html(adminHtml);
    }

    private static ResponseEntity<String> html(String content) {
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("text/html;charset=UTF-8"))
                .body(content);
    }

    private static String readPage(String path) throws IOException {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        }
    }
}
