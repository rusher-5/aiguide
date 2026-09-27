package com.guide.web.controller;

import com.guide.common.result.Result;
import com.guide.common.result.ErrorCode;
import com.guide.service.tts.AudioRegistry;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 音频接口：/api/audio/{id}。
 * id 为服务端生成的 UUID 且白名单校验，从根上杜绝路径穿越（Python 版需要
 * secure_filename + commonpath 双保险，Java 版以内存注册表 + 格式校验收敛为一层）。
 */
@RestController
@RequestMapping("/api/audio")
public class AudioController {

    private final AudioRegistry audioRegistry;

    public AudioController(AudioRegistry audioRegistry) {
        this.audioRegistry = audioRegistry;
    }

    @GetMapping("/{id}")
    public ResponseEntity<byte[]> audio(@PathVariable("id") String id) {
        byte[] data = audioRegistry.get(id);
        if (data == null || data.length == 0) {
            return ResponseEntity.status(404)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(("{\"code\":" + ErrorCode.NOT_FOUND.getCode() + ",\"message\":\"音频不存在或已过期\",\"data\":null}")
                            .getBytes());
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "audio/mpeg")
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=1800")
                .body(data);
    }
}
