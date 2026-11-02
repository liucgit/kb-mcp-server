package com.demo.mcp.mcp;

import com.demo.mcp.kb.KbSearchService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具注册表：定义本服务暴露给大模型客户端的「工具清单」并负责执行。
 *
 * <p>这是 MCP Server 的核心资产。每个工具三要素：
 * 1. name        —— 客户端调用时的方法名
 * 2. description —— 给大模型看的"说明书"（LLM 靠它决定何时调用，务必写清楚）
 * 3. inputSchema —— JSON Schema 描述参数（MCP 规范要求）
 *
 * <p>想新增工具？在 tools() 加一个定义、在 dispatch() 加一个分支即可。</p>
 */
@Component
public class McpToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(McpToolRegistry.class);

    private final KbSearchService kbSearchService;
    private final ObjectMapper mapper = new ObjectMapper();

    public McpToolRegistry(KbSearchService kbSearchService) {
        this.kbSearchService = kbSearchService;
    }

    /** 构造 tools/list 的返回体 */
    public ArrayNode tools() {
        ArrayNode tools = mapper.createArrayNode();

        ObjectNode search = mapper.createObjectNode();
        search.put("name", "search_knowledge_base");
        search.put("description",
                "在企业知识库（客服 FAQ：账号登录、报销、办公设备、网络权限等）中检索，"
                        + "回答员工咨询前应先调用本工具。返回按相关度排序的段落，含所属文档标题与 docId。");
        search.set("inputSchema", objectSchema(
                Map.of(
                        "query", stringProp("检索关键词或完整问题，例如：报销需要在几天内提交"),
                        "topK", intProp("返回条数，默认 3，可不传")),
                List.of("query")));
        tools.add(search);

        ObjectNode getDoc = mapper.createObjectNode();
        getDoc.put("name", "get_document");
        getDoc.put("description",
                "按 docId 返回知识库文档全文。当 search_knowledge_base 返回的片段不够细时，"
                        + "可先用其结果中的 docId 调用本工具获取完整上下文。");
        getDoc.set("inputSchema", objectSchema(
                Map.of("docId", stringProp("文档 ID，来自 search_knowledge_base 返回结果")),
                List.of("docId")));
        tools.add(getDoc);

        return tools;
    }

    /**
     * 执行 tools/call 调用。
     *
     * @param toolName 工具名
     * @param args     参数对象（MCP 规范里是 JSON object）
     * @return 文本结果（会包在 CallToolResult.content[0].text 里返回）
     */
    public String dispatch(String toolName, JsonNode args) {
        log.info("执行工具: {} args={}", toolName, args);
        return switch (toolName) {
            case "search_knowledge_base" -> {
                String query = requireText(args, "query");
                Integer topK = args.hasNonNull("topK") ? args.get("topK").asInt() : null;
                yield kbSearchService.search(query, topK);
            }
            case "get_document" -> kbSearchService.getDocument(requireText(args, "docId"));
            default -> throw new IllegalArgumentException("未知工具: " + toolName);
        };
    }

    // ==================== JSON Schema 构造辅助 ====================

    private ObjectNode objectSchema(Map<String, ObjectNode> properties, List<String> required) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        properties.forEach(props::set);
        ArrayNode req = schema.putArray("required");
        required.forEach(req::add);
        return schema;
    }

    private ObjectNode stringProp(String description) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "string");
        node.put("description", description);
        return node;
    }

    private ObjectNode intProp(String description) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "integer");
        node.put("description", description);
        return node;
    }

    private String requireText(JsonNode args, String field) {
        if (args == null || !args.hasNonNull(field) || args.get(field).asText().isBlank()) {
            throw new IllegalArgumentException("缺少必填参数: " + field);
        }
        return args.get(field).asText();
    }
}
