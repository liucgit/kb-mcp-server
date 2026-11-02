package com.demo.mcp.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MCP HTTP+SSE 传输层的两个 HTTP 端点（协议接入点，非常薄的一层）。
 *
 * <p>时序图：
 * Client ──GET /sse──────────────────► Server
 * Client ◄──event: endpoint────────── Server   （告知 POST 地址 + sessionId）
 * Client ──POST /mcp/message?sid=xxx─► Server   （initialize / tools/list / tools/call）
 * Client ◄──202 Accepted───────────── Server
 * Client ◄──event: message(JSON-RPC)─ Server   （响应经 SSE 推回）
 * </p>
 */
@RestController
public class McpSseController {

    private static final Logger log = LoggerFactory.getLogger(McpSseController.class);

    private final McpSessionManager sessionManager;

    @Value("${mcp.sse-path:/sse}")
    private String ssePath;
    @Value("${mcp.message-path:/mcp/message}")
    private String messagePath;
    /** 服务对外的 base-url：用于给客户端拼出完整的 message 回传地址 */
    @Value("${mcp.base-url:http://localhost:8082}")
    private String baseUrl;

    /** SSE 推送线程池：避免阻塞 Tomcat 工作线程 */
    private final ExecutorService sseExecutor = Executors.newCachedThreadPool();

    public McpSseController(McpSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    /** 端点 1：建立 SSE 长连接 */
    @GetMapping(value = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sse() {
        // 0L = 永不超时（客户端断开时由容器回调清理）
        SseEmitter emitter = new SseEmitter(0L);
        String sessionId = sessionManager.createSession(emitter);
        try {
            // 立刻告知客户端：JSON-RPC 请求 POST 到哪个地址（必须带 sessionId）
            String messageUrl = baseUrl + messagePath + "?sessionId=" + sessionId;
            emitter.send(SseEmitter.event().name("endpoint").data(messageUrl));
            log.info("已下发 endpoint: {}", messageUrl);
        } catch (Exception e) {
            log.warn("发送 endpoint 事件失败: {}", e.getMessage());
        }
        return emitter;
    }

    /** 端点 2：接收 JSON-RPC 请求（响应从 SSE 通道异步推回，因此这里直接 202） */
    @PostMapping("/mcp/message")
    public ResponseEntity<Map<String, String>> message(@RequestParam String sessionId,
                                                       @RequestBody String body) {
        try {
            sessionManager.handleMessage(sessionId, body);
            return ResponseEntity.accepted().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
    }
}
