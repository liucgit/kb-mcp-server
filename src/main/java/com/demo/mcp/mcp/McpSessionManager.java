package com.demo.mcp.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * MCP 会话管理 + JSON-RPC 2.0 协议处理器。
 *
 * <p>MCP 的 HTTP+SSE 传输层工作方式（协议原文 https://spec.modelcontextprotocol.io ）：
 * 1. 客户端 GET /sse 建立 SSE 长连接；
 * 2. 服务端立刻发一条 endpoint 事件，告知"请把 JSON-RPC 请求 POST 到哪个地址"；
 * 3. 客户端 POST JSON-RPC 请求到该地址（服务端回 202 即可）；
 * 4. 服务端把 JSON-RPC 响应通过 SSE 事件流推回（事件名 message）。
 * 一句话：请求走 POST、响应走 SSE 推送，两条例程靠 sessionId 关联。</p>
 */
@Component
public class McpSessionManager {

    private static final Logger log = LoggerFactory.getLogger(McpSessionManager.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final McpToolRegistry toolRegistry;

    @Value("${mcp.server.name:kb-mcp-server}")
    private String serverName;
    @Value("${mcp.server.version:1.0.0}")
    private String serverVersion;
    @Value("${mcp.server.protocol-version:2024-11-05}")
    private String protocolVersion;
    @Value("${mcp.message-path:/mcp/message}")
    private String messagePath;

    /** sessionId -> SSE 输出通道 */
    private final Map<String, SseEmitter> sessions = new ConcurrentHashMap<>();

    /**
     * SSE 心跳线程：每 5 秒向所有活跃会话发一条 SSE 注释行（": keepalive"）。
     *
     * <p>为什么需要它？MCP 客户端（如 LangChain4j 的 HttpMcpTransport）把 timeout
     * 同时用作 SSE 长连接的【读超时】——如果超过该时长没有任何字节到达，
     * 客户端会抛 SocketTimeoutException 并不断重连（表现为日志刷
     * "trying to reconnect"）。注释行是 SSE 规范的一部分，客户端会原样忽略，
     * 但任何字节到达都会重置读超时——对协议完全透明，专门用来保活。</p>
     */
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mcp-sse-heartbeat");
        t.setDaemon(true); // 守护线程：JVM 退出时不阻塞
        return t;
    });

    public McpSessionManager(McpToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /** 启动心跳（间隔必须显著小于客户端的 SSE 读超时，5 秒配 10-60 秒超时都安全） */
    @PostConstruct
    public void startHeartbeat() {
        heartbeatExecutor.scheduleAtFixedRate(this::sendHeartbeats, 5, 5, TimeUnit.SECONDS);
    }

    /** 应用关闭时停掉心跳线程 */
    @PreDestroy
    public void stopHeartbeat() {
        heartbeatExecutor.shutdownNow();
    }

    /** 逐会话发送心跳；发送失败说明连接已断，顺手清理会话 */
    private void sendHeartbeats() {
        sessions.forEach((sessionId, emitter) -> {
            try {
                emitter.send(SseEmitter.event().comment("keepalive"));
            } catch (Exception e) {
                sessions.remove(sessionId);
                log.info("心跳失败，移除会话 {}: {}", sessionId, e.getMessage());
            }
        });
    }

    // ==================== 会话管理 ====================

    /** 注册新会话并返回 sessionId（GET /sse 时调用） */
    public String createSession(SseEmitter emitter) {
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        sessions.put(sessionId, emitter);
        emitter.onCompletion(() -> sessions.remove(sessionId));
        emitter.onTimeout(() -> sessions.remove(sessionId));
        emitter.onError(e -> sessions.remove(sessionId));
        log.info("MCP 客户端接入: sessionId={}（当前在线 {} 个）", sessionId, sessions.size());
        return sessionId;
    }

    // ==================== JSON-RPC 处理 ====================

    /**
     * 处理客户端 POST 来的 JSON-RPC 请求，并把响应经 SSE 推回。
     * 对 Notification（无 id 的消息）不回包。
     */
    public void handleMessage(String sessionId, String body) {
        SseEmitter emitter = sessions.get(sessionId);
        if (emitter == null) {
            throw new IllegalArgumentException("会话不存在或已断开: " + sessionId);
        }
        try {
            JsonNode request = mapper.readTree(body);
            String method = request.path("method").asText("");
            JsonNode id = request.get("id"); // Notification 没有 id
            Object idValue = (id == null || id.isNull()) ? null : normalizeId(id);

            if (idValue == null) {
                log.info("收到 Notification: {}", method);
                return; // 通知不需要响应
            }

            ObjectNode response = mapper.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", id);
            try {
                response.set("result", handleMethod(sessionId, method, request.path("params")));
            } catch (Exception e) {
                // JSON-RPC 错误对象：code -32603 内部错误（详细原因放 message 便于客户端排查）
                ObjectNode error = response.putObject("error");
                error.put("code", -32603);
                error.put("message", e.getMessage() == null ? "internal error" : e.getMessage());
                log.warn("MCP 请求处理失败: method={} err={}", method, e.getMessage());
            }
            send(emitter, response);
        } catch (IOException e) {
            log.warn("SSE 推送失败，移除会话 {}: {}", sessionId, e.getMessage());
            sessions.remove(sessionId);
        } catch (Exception e) {
            log.error("处理 MCP 消息失败", e);
        }
    }

    /** 按 MCP 规范分发各 method */
    private JsonNode handleMethod(String sessionId, String method, JsonNode params) {
        return switch (method) {
            // 握手：告诉客户端"我是谁、我支持什么能力"
            case "initialize" -> {
                ObjectNode result = mapper.createObjectNode();
                result.put("protocolVersion", protocolVersion);
                ObjectNode capabilities = result.putObject("capabilities");
                capabilities.putObject("tools"); // 声明支持 tools 能力
                ObjectNode info = result.putObject("serverInfo");
                info.put("name", serverName);
                info.put("version", serverVersion);
                yield result;
            }
            // 能力探测（部分客户端会调用）
            case "ping" -> mapper.createObjectNode();
            // 工具清单：把工具的 name/description/inputSchema 列给大模型
            case "tools/list" -> {
                ObjectNode result = mapper.createObjectNode();
                result.set("tools", toolRegistry.tools());
                yield result;
            }
            // 工具调用：执行并包装为 content[0].text
            case "tools/call" -> {
                String toolName = params.path("name").asText();
                JsonNode args = params.path("arguments");
                String text = toolRegistry.dispatch(toolName, args);
                ObjectNode result = mapper.createObjectNode();
                ArrayNode content = result.putArray("content");
                ObjectNode item = content.addObject();
                item.put("type", "text");
                item.put("text", text);
                result.put("isError", false);
                yield result;
            }
            default -> throw new IllegalArgumentException("暂不支持的 method: " + method);
        };
    }

    /** JSON-RPC id 兼容数字与字符串 */
    private Object normalizeId(JsonNode id) {
        return id.isNumber() ? id.numberValue() : id.asText();
    }

    /** 经 SSE 推送响应（事件名固定 message；JSON 必须单行，Jackson 默认就是单行） */
    private void send(SseEmitter emitter, ObjectNode response) throws IOException {
        emitter.send(SseEmitter.event().name("message").data(mapper.writeValueAsString(response)));
    }
}
