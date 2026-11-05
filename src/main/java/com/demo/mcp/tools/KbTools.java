package com.demo.mcp.tools;

import com.demo.mcp.config.PlainTextResultConverter;
import com.demo.mcp.kb.KbSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 知识库工具集 —— MCP 工具的"业务侧"实现。
 *
 * <p>★★ 和旧版最大的区别（面试可以讲的一段）★★
 * 旧版要手写三样东西：工具清单（JSON Schema）、tools/call 分发分支、JSON-RPC 响应包装。
 * 现在只需要写一个普通 Spring Bean 的方法 + {@code @Tool} 注解：
 * <ol>
 *   <li>启动时：{@code McpServerConfig} 把这个 Bean 包装成 {@code ToolCallbackProvider}，
 *       Spring AI 反射读取方法签名，自动生成 MCP 规范要求的 name / description / inputSchema；</li>
 *   <li>客户端调 {@code tools/list}：McpSyncServer 直接把这些描述返回，不用你拼 JSON；</li>
 *   <li>客户端调 {@code tools/call}：框架按工具名找到对应方法、把 arguments 反序列化成
 *       方法参数（含类型转换）、执行、把返回值包成 content[0].text 回传。</li>
 * </ol>
 * 协议层（JSON-RPC 2.0 报文、会话、SSE/Streamable 传输）全部由官方实现兜住。</p>
 *
 * <p>写 @Tool 的三个实战要点：
 * <ul>
 *   <li>description 是给 LLM 看的 API 文档，决定"何时调用/怎么填参"，要写清适用与不适用场景；</li>
 *   <li>返回值是喂回模型的文本，要精炼、自解释（本类返回带 docId 的结果，方便模型二次取全文）；</li>
 *   <li>方法名与 @Tool 的 name 分离：Java 侧用驼峰，MCP 侧用下划线（沿用旧版工具名，保证调用方零改动）。</li>
 * </ul>
 * </p>
 */
@Component
public class KbTools {

    private static final Logger log = LoggerFactory.getLogger(KbTools.class);

    private final KbSearchService kbSearchService;

    public KbTools(KbSearchService kbSearchService) {
        this.kbSearchService = kbSearchService;
    }

    /**
     * 检索知识库。
     *
     * @param query 检索关键词或完整问题
     * @param topK  返回条数，null/小于等于 0 时按 3 条处理
     * @return 面向大模型阅读优化的结构化文本（含文档标题与 docId）
     */
    @Tool(name = "search_knowledge_base",
            description = "在企业知识库（客服 FAQ：账号登录、报销、办公设备、网络权限等）中检索，"
                    + "回答员工咨询前应先调用本工具。返回按相关度排序的段落，含所属文档标题与 docId。",
            resultConverter = PlainTextResultConverter.class)
    public String searchKnowledgeBase(
            @ToolParam(description = "检索关键词或完整问题，例如：报销需要在几天内提交") String query,
            @ToolParam(required = false, description = "返回条数，默认 3，可不传") Integer topK) {
        log.info("[工具调用] search_knowledge_base: query={}, topK={}", query, topK);
        return kbSearchService.search(query, topK);
    }

    /**
     * 按 docId 取文档全文。
     *
     * @param docId 文档 ID（来自 search_knowledge_base 的返回结果）
     * @return 文档标题 + 全文
     */
    @Tool(name = "get_document",
            description = "按 docId 返回知识库文档全文。当 search_knowledge_base 返回的片段不够细时，"
                    + "可先用其结果中的 docId 调用本工具获取完整上下文。",
            resultConverter = PlainTextResultConverter.class)
    public String getDocument(
            @ToolParam(description = "文档 ID，来自 search_knowledge_base 返回结果") String docId) {
        log.info("[工具调用] get_document: docId={}", docId);
        return kbSearchService.getDocument(docId);
    }
}
