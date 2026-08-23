package com.decade.doj.sandbox.controller;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.decade.doj.common.utils.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/chat")
@Tag(name = "AI 助手")
@Slf4j
public class ChatController {

    @Value("${doj.agent.url:http://agent:8765}")
    private String agentUrl;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    @PostMapping("/stream")
    @Operation(summary = "流式 AI 对话（SSE 代理转发到 Python Agent）")
    public SseEmitter chatStream(@RequestBody JSONObject body,
            @RequestHeader("Authorization") String authHeader) {
        SseEmitter emitter = new SseEmitter(120_000L);

        // 注入 access token 到 body，Agent 用此身份调 OJ API
        String token = authHeader.startsWith("Bearer ")
                ? authHeader.substring(7) : authHeader;
        body.put("access_token", token);

        Long userId;
        userId = UserContext.getCurrentUser();
        log.info("Agent chat — user: {}, body keys: {}", userId, body.keySet());

        executor.execute(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) URI.create(agentUrl + "/chat/stream").toURL().openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(120_000);

                String payload = body.toJSONString();
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload.getBytes(StandardCharsets.UTF_8));
                }

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("event:") || line.startsWith("data:")) {
                            emitter.send(SseEmitter.event()
                                    .name(line.startsWith("event:") ? line.substring(6).trim() : null)
                                    .data(line.startsWith("data:") ? line.substring(5).trim() : line));
                        }
                    }
                }

                emitter.complete();
            } catch (Exception e) {
                log.error("Agent 代理转发失败", e);
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data(JSON.toJSONString(
                                    java.util.Map.of("type", "error", "data", "Agent 服务不可用: " + e.getMessage()))));
                } catch (Exception ignored2) {
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }
}
