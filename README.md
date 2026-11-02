# 知识库 MCP Server（MCP Server 开发实战）

> 转型学习项目三（项目二的配套）：把「知识库检索」能力封装为 **MCP Server**，可被智能客服 Agent（E:\Workspaces\cs-agent）、Claude Desktop、Cursor 等任意 MCP 客户端调用。

---

## 一、这个项目能帮你学会什么

- **MCP 协议本身**：本项目【零 SDK 依赖】手写实现了 MCP 的 HTTP+SSE 传输层与 JSON-RPC 2.0 消息协议——你可以逐行看到 `initialize` 握手、`tools/list` 能力发现、`tools/call` 工具调用每一条报文长什么样。市面上大多数教程用 SDK 黑盒掉这些细节，而 MCP Server 开发恰恰是面试热点。
- **工具设计**：`McpToolRegistry` 演示了如何给大模型写工具的 description 与 JSON Schema（LLM 靠说明书决定何时调用你的工具）。

## 二、MCP 协议速览（HTTP+SSE 传输）

```
MCP客户端                    本服务
   │  ①GET /sse（建立事件流）   │
   │◄──event: endpoint─────────│   ②告知 JSON-RPC 请求 POST 地址（带 sessionId）
   │  ③POST /mcp/message?sid=x │
   │◄──202 Accepted────────────│
   │◄──event: message(JSON)────│   ④JSON-RPC 响应经 SSE 推回
   │
   请求方法：initialize（握手） / tools/list（列工具） / tools/call（调工具）
```

## 三、项目结构

```
kb-mcp-server/
├── pom.xml                         # 仅 spring-boot-starter-web，零 AI 依赖
└── src/main/
    ├── resources/
    │   ├── application.yml         # 端口 8082、SSE 路径、协议版本
    │   └── docs/                   # 客服知识库 4 份 FAQ（开箱即用）
    └── java/com/demo/mcp/
        ├── KbMcpServerApplication.java
        ├── kb/
        │   ├── KnowledgeBase.java      # 文档加载（classpath:docs）
        │   └── KbSearchService.java    # BM25 段落检索（可替换为项目一的向量检索）
        ├── mcp/
        │   ├── McpToolRegistry.java    # ★ 工具定义与执行（想加工具改这里）
        │   ├── McpSessionManager.java  # ★ 会话管理 + JSON-RPC 方法分发
        │   └── McpSseController.java   # HTTP+SSE 两个端点（协议接入层）
        └── web/
            └── GlobalExceptionHandler.java
```

## 四、需要你准备的事项

- JDK 17+ 与 Maven 3.9+（或 VSCode 的 Extension Pack for Java 自动导入）。无 API Key、无数据库、无 Docker。

## 五、快速开始

```bash
cd E:\Workspaces\kb-mcp-server
mvn spring-boot:run
# 启动成功：Tomcat started on port 8082
```

### 手工验证协议（强烈推荐做一遍，体会 MCP 是怎么工作的）

```bash
# 1. 建立 SSE 连接（会返回 endpoint 事件，里面是带 sessionId 的 POST 地址）
curl -N http://localhost:8082/sse
# 输出示例: event: endpoint
#           data: http://localhost:8082/mcp/message?sessionId=abc123...
# （保持这个窗口不关，把 abc123... 换进下面第 2 步）

# 2. 新开窗口：initialize 握手
curl -X POST "http://localhost:8082/mcp/message?sessionId=abc123..." ^
  -H "Content-Type: application/json" ^
  -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"
# 第 1 个 curl 窗口会收到 event: message 的握手响应

# 3. 列出工具
curl -X POST "http://localhost:8082/mcp/message?sessionId=abc123..." ^
  -H "Content-Type: application/json" ^
  -d "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}"

# 4. 调用检索工具
curl -X POST "http://localhost:8082/mcp/message?sessionId=abc123..." ^
  -H "Content-Type: application/json" ^
  -d "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"search_knowledge_base\",\"arguments\":{\"query\":\"报销几天内提交\"}}}"
```

## 六、暴露的工具清单

| 工具名 | 参数 | 说明 |
|---|---|---|
| `search_knowledge_base` | query（必填）、topK（可选，默认 3） | BM25 段落级检索客服 FAQ，返回带文档标题与 docId 的结果 |
| `get_document` | docId（必填） | 返回文档全文（docId 来自检索结果） |

## 七、接入 Claude Desktop / Cursor（可选加分体验）

1. Claude Desktop → 设置 → 开发者 → 编辑配置 `claude_desktop_config.json`：
   目前 Claude Desktop 对"远程 SSE 服务器"的支持有限，最稳的方式是用 mcp-remote 桥接（npx）：
   ```json
   {
     "mcpServers": {
       "kb-mcp-server": {
         "command": "npx",
         "args": ["-y", "mcp-remote", "http://localhost:8082/sse"]
       }
     }
   }
   ```
   `[待核实: mcp-remote 包名与参数以 https://www.npmjs.com/package/mcp-remote 最新文档为准]`
2. Cursor → MCP 设置 → 添加 SSE 服务器，URL 填 `http://localhost:8082/sse`。
3. 重启客户端后提问"报销需要在几天内提交？"，观察它自动调用 `search_knowledge_base`。

## 八、已知限制与待核实项

- 使用的是 MCP 早期广泛支持的 **HTTP+SSE 传输**（后来被 Streamable HTTP 取代但向下兼容）。若某客户端仅支持 Streamable HTTP，可把本项目传输层替换为官方 MCP Java SDK（`io.modelcontextprotocol.sdk:mcp`）。`[待核实: 目标客户端支持的传输方式]`
- 检索是 BM25 关键词检索；升级为向量语义检索的路径：引入 langchain4j 依赖，替换 `KbSearchService` 内部实现，工具签名不变。
- 生产使用需增加：鉴权（MCP 规范的 OAuth 能力）、会话超时回收、工具调用审计日志。
