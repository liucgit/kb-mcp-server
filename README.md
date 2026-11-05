# 知识库 MCP Server（生产主流写法版）

> 转型学习项目三（项目二的配套）：把「知识库检索」能力封装为 **MCP Server**，可被智能客服 Agent（E:\Workspaces\cs-agent）、Claude Desktop、Cursor、MCP Inspector 等任意 MCP 客户端调用。

**这一版和之前的区别**：旧版为了"看清协议长什么样"，手写了 JSON-RPC 2.0 报文、SSE 传输、工具注册表。现在换成工业界的标准做法——协议层交给官方生态，自己只写业务工具。手写的价值在"读过一遍协议"，生产的价值在"不重复造轮子"。

---

## 一、技术栈与版本

| 组件 | 版本 | 作用 |
|---|---|---|
| Spring Boot | 3.5.3 | 应用基座（Servlet 栈 / Tomcat） |
| Spring AI | 1.1.8 | MCP Server Starter：传输层 + 协议层 + 工具自动发布 |
| MCP Java SDK | 0.18.3（由 Spring AI 传递引入） | 官方协议实现 |
| Apache Lucene | 9.12.3 | 全文检索：倒排索引 + BM25 打分 |
| Lucene SmartCN | 9.12.3 | 中文分词（`SmartChineseAnalyzer`） |

版本对应关系写死在 `pom.xml` 的 `<properties>` 里，升级只改一处。
选 Lucene 9.x 而不是 10.x 的原因：10.x 要求 Java 21，本项目以 `--release 17` 编译。

## 二、这次改造替换掉了什么

| 旧做法（手写） | 新做法（标准实现） |
|---|---|
| `McpSseController` 手写 `/sse` + `/mcp/message` 两个端点 | Spring AI MCP Server Starter 自动装配 |
| `McpSessionManager` 手写 JSON-RPC 2.0 分发（initialize / tools/list / tools/call） | `McpSyncServer`（官方 SDK） |
| `McpToolRegistry` 手写工具 JSON Schema 与 dispatch switch | `@Tool` 注解 + `ToolCallbackProvider`，启动时反射生成 Schema |
| `McpSessionManager` 手写 SSE 心跳线程保活 | `keep-alive-interval` 配置项 |
| `KbSearchService` 手写分词 + 倒排 + BM25 公式 | Lucene `IndexSearcher` + `BM25Similarity` + `SmartChineseAnalyzer` |

净效果：协议层三个类（`McpSseController`、`McpSessionManager`、`McpToolRegistry`，合计约 400 行）**全部删除**，取而代之的是 `McpServerConfig`（49 行）+ `PlainTextResultConverter`（24 行）+ `KbTools`（78 行）。检索层行数基本持平（旧 125 行 → 新 208 行），但多出来的主要是注释，实际逻辑从"自己分词 + 自己建倒排 + 自己算 BM25"变成"调 Lucene 的 API"。整个 `src/main/java` 现在共 487 行。

## 三、MCP 传输：Streamable HTTP 与 SSE

MCP 规范的传输方式演进：

```
2024-11-05    HTTP+SSE：GET /sse 建流 → 服务端下发 endpoint → POST 请求 → 响应经 SSE 推回
              （两条例程、需要 sessionId 关联，规范已标记为"向后兼容保留"）

2025-03-26+   Streamable HTTP：单个 /mcp 端点，POST 直接拿到响应（需要推流时同一条连接转 SSE）
              （一个端点搞定请求/响应/流式，主流客户端已普遍支持）
```

本项目**只提供 Streamable HTTP**（端点 `/mcp`）。旧的 HTTP+SSE 双端点方案官方已标记为"向后兼容保留"，本项目不再保留兼容 profile —— 客户端和服务器统一走同一种传输，链路只有一条，排查时不用再问"你到底连的是 /sse 还是 /mcp"。

## 四、项目结构

```
kb-mcp-server/
├── pom.xml                              # Boot 3.5.3 + Spring AI 1.1.8 + Lucene 9.12.3
├── .vscode/launch.json                  # 单个启动配置：Streamable HTTP
└── src/main/
    ├── resources/
    │   ├── application.yml              # spring.ai.mcp.server.*（STREAMABLE，端点 /mcp）
    │   ├── docs/                        # 客服知识库 4 份 FAQ（开箱即用）
    │   └── static/favicon.ico
    └── java/com/demo/mcp/
        ├── KbMcpServerApplication.java
        ├── config/
        │   ├── McpServerConfig.java          # ★ 把 @Tool 方法暴露为 MCP 工具（就这一个 @Bean）
        │   └── PlainTextResultConverter.java # 工具返回值原样交给模型，不做 JSON 转义
        ├── tools/
        │   └── KbTools.java                  # ★ 工具定义：两个 @Tool 方法（想加工具改这里）
        ├── kb/
        │   ├── KnowledgeBase.java            # 文档加载（classpath:docs）
        │   └── KbSearchService.java          # Lucene 索引与检索
        └── web/
            └── GlobalExceptionHandler.java
```

## 五、需要你准备的事项

- JDK 17+ 与 Maven 3.9+。无 API Key、无数据库、无 Docker、无需向量库。
- 首次启动会下载 Spring AI / Lucene 依赖（约 60–90 MB），需要网络。

## 六、快速开始

```bash
cd E:\Workspaces\kb-mcp-server
mvn clean package
java -jar target\kb-mcp-server-1.0.0.jar
```

启动日志里应该看到这两行（有它们就说明工具发布成功）：

```
Lucene 索引就绪：4 份文档 / 36 个段落，分析器=SmartChineseAnalyzer, 相似度=BM25(k1=1.2, b=0.75)
Registered tools: 2
```

### 验证一：Streamable HTTP（默认，Git Bash / WSL 下执行）

```bash
# 1) initialize —— 注意把响应头里的 Mcp-Session-Id 记下来
curl -i -X POST http://localhost:8082/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"1.0.0"}}}'

# 2) 通知服务端初始化完成（202 无响应体即为成功）
curl -X POST http://localhost:8082/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "Mcp-Session-Id: <上一步的 session id>" \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'

# 3) 列工具（应看到 search_knowledge_base / get_document 及自动生成的 inputSchema）
curl -X POST http://localhost:8082/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "Mcp-Session-Id: <session id>" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'

# 4) 调工具
curl -X POST http://localhost:8082/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "Mcp-Session-Id: <session id>" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"search_knowledge_base","arguments":{"query":"报销需要在几天内提交","topK":3}}}'
```

Windows CMD 里手写 JSON 很容易被引号坑，建议用 Git Bash，或者直接用下面的可视化工具。

### 验证二：MCP Inspector（官方调试器，最直观）

```bash
npx @modelcontextprotocol/inspector
# 浏览器里 Transport 选 "Streamable HTTP"，URL 填 http://localhost:8082/mcp
# 连上后可以直接点 Connect → List Tools → 填参数调用，报文与响应一目了然
```

`[待核实: @modelcontextprotocol/inspector 的包名与用法以 https://github.com/modelcontextprotocol/inspector 最新说明为准]`

## 七、暴露的工具清单

| 工具名 | 参数 | 说明 |
|---|---|---|
| `search_knowledge_base` | `query`（必填）、`topK`（可选，默认 3） | Lucene BM25 段落级检索，返回带文档标题与 docId 的片段 |
| `get_document` | `docId`（必填） | 返回文档全文（docId 来自检索结果） |

这两个名字与旧版完全一致，所以 cs-agent 侧不需要任何改动。

## 八、检索是怎么做的（Lucene）

- **索引**：启动时把 4 份 FAQ 按空行切成段落，一个段落 = 一个 Lucene `Document`，字段 `docId`（不分词，精确回链）、`title`（分词，查询时权重 ×2）、`text`（分词，存原文）。
- **切分细节**：文档首行标题跳过（与 `title` 字段重复）；`## 小标题` 不单独成段，与紧随的正文合并——否则检索会命中一堆只有标题的空段落。
- **分析器**：`SmartChineseAnalyzer`（中文分词 + 停用词），索引与查询必须同一个分析器。
- **打分**：`BM25Similarity(k1=1.2, b=0.75)`，Lucene 默认相似度，显式写出便于调参。
- **存储**：`ByteBuffersDirectory`（堆内内存目录），进程启动建一次索引，检索零 IO。
- **升级路径**：换成 `FSDirectory`（磁盘）或 Elasticsearch/OpenSearch 客户端时，只需改 `KbSearchService` 内部，`KbTools` 不动。

## 九、接入 Claude Desktop / Cursor

1. **Cursor**：Settings → MCP → Add new MCP server，URL 填 `http://localhost:8082/mcp`，类型选 Streamable HTTP。
2. **Claude Desktop**（仅支持本地 stdio 启动的服务器）：用 `mcp-remote` 桥接：
   ```json
   {
     "mcpServers": {
       "kb-mcp-server": {
         "command": "npx",
         "args": ["-y", "mcp-remote", "http://localhost:8082/mcp"]
       }
     }
   }
   ```
   `[待核实: mcp-remote 是否已支持 Streamable HTTP；若只支持 SSE，则需把 mcp-remote 升级到支持新版传输的版本，本项目不再提供 SSE 端点]`
3. 重启客户端后提问"报销需要在几天内提交？"，观察它自动调用 `search_knowledge_base`。

## 十、与 cs-agent 的联调

两端都只走 Streamable HTTP，**不需要任何 profile 参数**：

| 侧 | 配置 |
|---|---|
| kb-mcp-server | `mvn spring-boot:run`（默认，端点 `/mcp`） |
| cs-agent | `application.yml` 里 `app.mcp.url: http://localhost:8082/mcp` |

cs-agent 侧用的传输是 LangChain4j 的 `StreamableHttpMcpTransport`（1.23.0 起提供）。旧版本只有 `HttpMcpTransport`（SSE），那条路已经废弃——不是"配置切换"，是代码层换实现。

## 十一、已知限制

- 检索是关键词 BM25，不理解同义词（问"上班时间"未必命中"考勤作息"）。要语义召回请接向量检索（项目一的做法），工具签名不用变。
- 知识库文档是内置的 classpath 文件，改文档要重新打包；生产应改为外部存储 + 增量索引。
- 无鉴权：MCP 规范支持 OAuth 2.1，公网暴露前必须补上，否则等于把知识库裸奔在公网上。
- 启动日志里可能出现 `SyncMcpToolProvider : No tool methods found in the provided tool objects: []` 这条 WARN，来自 MCP **客户端**侧自动装配（本项目没用到客户端），不影响服务端工具发布，可忽略。
