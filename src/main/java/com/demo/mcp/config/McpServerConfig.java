package com.demo.mcp.config;

import com.demo.mcp.tools.KbTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP Server 装配层（现在只剩这一个类，约 15 行）。
 *
 * <p>★★ 工具是怎么"自动发现"的 ★★
 * spring-ai-starter-mcp-server-webmvc 的自动装配里有一条固定链路：
 * <pre>
 *   你声明的 ToolCallbackProvider Bean
 *        ↓  ToolCallbackConverterAutoConfiguration 收集（同时也会收集裸 ToolCallback、List&lt;ToolCallback&gt;）
 *   List&lt;McpServerFeatures.SyncToolSpecification&gt;
 *        ↓  McpServerAutoConfiguration 消费
 *   McpSyncServer（协议层：initialize / tools/list / tools/call 全由它实现）
 *        ↓  Streamable HTTP 传输（spring.ai.mcp.server.streamable-http.mcp-endpoint）
 *   客户端 POST http://localhost:8082/mcp
 * </pre>
 * 所以"注册工具"这件事，本质上就是让容器里出现一个 ToolCallbackProvider ——
 * 扫描 @Tool、生成 JSON Schema、把调用转发到方法，都由框架完成。</p>
 *
 * <p>与旧版的对照：
 * <ul>
 *   <li>旧：McpToolRegistry 手工拼 tools/list 的 JSON、手工写 dispatch 的 switch 分支；</li>
 *   <li>新：新增工具 = 在 KbTools 里加一个 @Tool 方法，其它一个字都不用改。</li>
 * </ul>
 * </p>
 */
@Configuration
public class McpServerConfig {

    /**
     * 把 {@link KbTools} 上的 @Tool 方法暴露为 MCP 工具。
     *
     * <p>不需要再声明工具名与描述：注解里已经有了。
     * 如果想再挂一组工具（比如工单工具），再加一个 ToolCallbackProvider Bean 即可，
     * 框架会把所有 Provider 的工具合并成一份清单。</p>
     */
    @Bean
    public ToolCallbackProvider kbToolCallbackProvider(KbTools kbTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(kbTools)
                .build();
    }
}
