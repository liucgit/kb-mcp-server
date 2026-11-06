# 企业知识库 MCP Server

把企业 FAQ 知识库封装成一个标准 **MCP Server**：检索能力以工具形式对外发布，任何支持 MCP 的客户端（智能客服 Agent、Claude Desktop、Cursor、MCP Inspector）都能直接调用，无需了解内部实现。

- 服务端不持有对话状态，也不调用大模型，只做「工具 + 检索」这一件事。
- 单端点 `/mcp`，传输方式为 Streamable HTTP，工具 Schema 由注解自动生成。
- 内置 4 份企业 FAQ 作为可直接运行的语料，零外部依赖（无数据库、无向量库、无 API Key）。

---

## 一、项目概览

| 项 | 说明 |
|---|---|
| 职责 | 企业知识库的检索服务，对外暴露 2 个 MCP 工具 |
| 对外形态 | MCP Server（Streamable HTTP，端点 `POST /mcp`） |
| 端口 | 8082 |
| 运行依赖 | JDK 17+、Maven 3.9+；无外部中间件 |
| 检索引擎 | Apache Lucene 倒排索引 + BM25（中文分词用 SmartChineseAnalyzer） |
| 代码规模 | `src/main/java` 共 487 行、7 个类 |

**核心实现索引**

| 功能 | 代码位置 |
|---|---|
| 工具声明（两个 `@Tool` 方法） | `tools/KbTools` |
| 工具发布为 MCP 工具 | `config/McpServerConfig` |
| 工具返回值处理（纯文本，不做 JSON 转义） | `config/PlainTextResultConverter` |
| 索引构建与检索 | `kb/KbSearchService` |
| 语料加载 | `kb/KnowledgeBase` |

---

## 二、技术栈

| 组件 | 版本 | 作用 |
|---|---|---|
| Spring Boot | 3.5.3 | 应用基座（Servlet 栈 / Tomcat） |
| Spring AI | 1.1.8 | MCP Server Starter：传输层、协议层、工具自动发布 |
| MCP Java SDK | 0.18.3（Spring AI 传递引入） | 协议实现 |
| Apache Lucene | 9.12.3 | 倒排索引与 BM25 打分 |
| lucene-analysis-smartcn | 9.12.3 | 中文分词（`SmartChineseAnalyzer`） |

版本集中在 `pom.xml` 的 `<properties>` 中维护。Lucene 选用 9.x：10.x 的字节码要求 Java 21，而本项目以 `--release 17` 编译。

---

## 三、系统结构

```
MCP 客户端（cs-agent / Claude Desktop / Cursor / Inspector）
        │  POST /mcp   JSON-RPC 2.0（initialize → tools/list → tools/call）
        │  Header: Mcp-Session-Id
        ▼
┌─────────────────────────────────────────────────────────┐
│ Spring AI MCP Server（自动装配）                          │
│  · 协议解析、会话管理、工具发现                            │
│  · ToolCallbackProvider：把 @Tool 方法转为 MCP 工具定义    │
└──────────────────────────┬──────────────────────────────┘
                           ▼
                     KbTools（2 个工具）
                           │
                           ▼
              KbSearchService（Lucene IndexSearcher）
                           │
              ┌────────────┴────────────┐
              ▼                         ▼
       SmartChineseAnalyzer        BM25Similarity
              │                         │
              └────────► ByteBuffersDirectory（启动时建索引）
```

协议层由框架承担，业务侧只有「工具方法」和「检索实现」两层。

---

## 四、目录结构

```
kb-mcp-server/
├── pom.xml
├── .vscode/launch.json                  # 启动配置（Streamable HTTP）
└── src/main/
    ├── resources/
    │   ├── application.yml              # spring.ai.mcp.server.*（STREAMABLE，端点 /mcp）
    │   └── docs/                        # 内置语料：4 份 FAQ
    │       ├── kb-账号与登录.md
    │       ├── kb-报销与差旅.md
    │       ├── kb-网络与权限.md
    │       └── kb-办公设备.md
    └── java/com/demo/mcp/
        ├── KbMcpServerApplication.java
        ├── config/
        │   ├── McpServerConfig.java          # ToolCallbackProvider：把 @Tool 方法发布为工具
        │   └── PlainTextResultConverter.java # 工具结果原样返回，避免被 JSON 转义
        ├── tools/
        │   └── KbTools.java                  # 工具定义入口（新增工具改这里）
        ├── kb/
        │   ├── KnowledgeBase.java            # 加载 classpath:docs 下的文档
        │   └── KbSearchService.java          # 索引构建 + 段落级检索
        └── web/
            └── GlobalExceptionHandler.java
```

---

## 五、MCP 传输与会话

采用 MCP 规范中的 **Streamable HTTP**：客户端与服务端只协商一个端点 `/mcp`。

| 行为 | 实现方式 |
|---|---|
| 请求 / 响应 | `POST /mcp` 承载 JSON-RPC 报文，响应可为普通 JSON，也可在同一连接上以 SSE 流式返回 |
| 会话标识 | 服务端在 `initialize` 响应头返回 `Mcp-Session-Id`，后续请求携带该头 |
| 主动推送 | `GET /mcp` 建立 SSE 流（仅客户端需要时才建立） |
| 结束会话 | `DELETE /mcp` |
| 健康检查 | 客户端发 MCP `ping`；服务端**未开启** keep-alive 定时推送（客户端不维持长流时 ping 无接收方，会话活性由每次请求与客户端 ping 保证） |

`application.yml` 中的关键配置：

| 配置项 | 值 | 说明 |
|---|---|---|
| `spring.ai.mcp.server.name` / `.version` | `kb-mcp-server` / `1.0.0` | 握手时返回的服务标识 |
| `spring.ai.mcp.server.type` | `SYNC` | 工具方法同步返回 |
| `spring.ai.mcp.server.protocol` | `STREAMABLE` | 传输协议 |
| `spring.ai.mcp.server.streamable-http.mcp-endpoint` | `/mcp` | 端点路径 |
| `spring.ai.mcp.server.request-timeout` | `30s` | 单次工具调用超时 |
| `spring.ai.mcp.server.instructions` | 服务说明 | `initialize` 时下发给客户端，相当于整个 Server 的提示词 |

---

## 六、对外工具

| 工具名 | 参数 | 返回 | 说明 |
|---|---|---|---|
| `search_knowledge_base` | `query`（必填）、`topK`（可选，默认 3） | 命中段落列表，含 `docId` / `title` / 正文片段 | 段落级 BM25 检索，供模型作答时引用 |
| `get_document` | `docId`（必填） | 文档全文 | 片段不足以回答时取全文，`docId` 来自检索结果 |

工具定义在 `KbTools` 中，两个要点：

- **名称与 Java 方法解耦**：`@Tool(name = "search_knowledge_base")` 对外用下划线命名，Java 方法用驼峰，调用方看到的名称稳定，不随内部重构变化。
- **参数描述即文档**：`@ToolParam(description = ...)` 会进入生成的 JSON Schema，模型据此判断是否传参、怎么传。`topK` 标为 `required = false`，可不传。
- **返回纯文本**：MCP 工具返回字符串时，默认转换器会把它 JSON 序列化成带引号、转义换行的形式，模型读起来是「一坨字符串」。`PlainTextResultConverter` 让文本原样返回。

---

## 七、检索实现

### 索引结构

启动时把 4 份 FAQ 按空行切段，**一段 = 一个 Lucene `Document`**：

| 字段 | 索引方式 | 用途 |
|---|---|---|
| `docId` | 不分词（StringField） | 精确回链到文档 |
| `title` | 分词（TextField） | 查询时权重 ×2，让标题命中更靠前 |
| `text` | 分词（TextField，存原文） | 正文检索与片段返回 |

### 切分细节

- 文档首行是标题，与 `title` 字段重复，切段时跳过。
- `## 小标题` 不单独成段，与紧随其后的正文合并——否则检索会命中大量只有标题、没有内容的空段落。

### 分析与打分

- 分析器 `SmartChineseAnalyzer`：中文分词 + 停用词处理；**索引与查询必须使用同一个分析器**，否则词项对不上。
- 查询解析：`MultiFieldQueryParser` 同时搜 `title`（权重 2.0）与 `text`（权重 1.0），默认 OR 语义；用户输入先 `QueryParser.escape` 再解析，避免 `:`、`[` 等字符被当作查询语法。
- 相似度 `BM25Similarity(k1 = 1.2, b = 0.75)`：Lucene 默认参数，显式声明便于后续调参。

### 存储与扩展点

`ByteBuffersDirectory`（堆内内存目录）在启动时建一次索引，检索零磁盘 IO，适合语料固定的读多写少场景。若需要持久化或增量更新，替换为 `FSDirectory` 即可，改动范围仅限 `KbSearchService`，工具定义与调用方不受影响。

---

## 八、运行

```bash
cd E:\Workspaces\kb-mcp-server
mvn clean package
java -jar target\kb-mcp-server-1.0.0.jar
```

启动后应看到：

```
Lucene 索引就绪：4 份文档 / 16 个段落，分析器=SmartChineseAnalyzer, 相似度=BM25(k1=1.2, b=0.75)
Registered tools: 2
Tomcat started on port 8082
```

`Registered tools: 2` 说明工具已成功发布；数量不对时检查 `McpServerConfig` 是否被扫描到。

### 验证一：curl（Git Bash / WSL）

```bash
# 1) initialize —— 记下响应头里的 Mcp-Session-Id
curl -i -X POST http://localhost:8082/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"1.0.0"}}}'

# 2) 声明初始化完成（202、无响应体即成功）
curl -X POST http://localhost:8082/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "Mcp-Session-Id: <上一步的 session id>" \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'

# 3) 列工具
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

Windows CMD 下 JSON 引号容易被转义，建议用 Git Bash，或改用下面的可视化工具。

### 验证二：MCP Inspector

```bash
npx @modelcontextprotocol/inspector                  # 打开 Web 调试界面
npx @modelcontextprotocol/inspector --cli            # 或脚本化 CLI，便于 CI
```

Transport 选 **Streamable HTTP**，URL 填 `http://localhost:8082/mcp`，即可 Connect → List Tools → 填参调用，原始报文与响应都能直接看到。

---

## 九、客户端接入

### Cursor

Settings → MCP → Add new MCP server，类型选 Streamable HTTP，URL 填 `http://localhost:8082/mcp`。

### Claude Desktop

Claude Desktop 只支持 stdio 启动的服务端，用 `mcp-remote` 做桥接：

```json
{
  "mcpServers": {
    "kb-mcp-server": {
      "command": "npx",
      "args": ["-y", "mcp-remote", "http://localhost:8082/mcp", "--transport", "http-only"]
    }
  }
}
```

`--transport http-only` 强制走 Streamable HTTP；不指定时默认 `http-first`（先试 HTTP，404 才回退 SSE）。

### 智能客服 Agent（cs-agent）

`cs-agent` 的 `application.yml` 中 `app.mcp.url: http://localhost:8082/mcp`，客户端使用 LangChain4j 的 `StreamableHttpMcpTransport`。两端都用单端点，不需要额外的 profile 或兼容开关。

---

## 十、设计取舍

| 决策 | 理由 |
|---|---|
| 协议层用 Spring AI Starter，不自行实现 | 协议版本、会话头、流式响应等细节持续变化，由官方实现承担可避免业务代码跟着规范改；业务侧只依赖 `@Tool` 注解 |
| 工具用注解声明 | Schema 由反射生成，参数描述与代码同处一处，改签名不会漏改文档 |
| 只保留 Streamable HTTP | 一条链路排查成本最低，客户端与服务端不需协商传输方式 |
| 检索用 Lucene 而非向量库 | 语料是企业制度类短文本，关键词精确匹配（如「7 个工作日」「maxPoolSize」）比语义泛化更重要；同时检索完全本地，无需 Embedding 服务 |
| 索引建在内存 | 语料小、读多写少，启动建一次即可，避免引入外部存储 |

---

## 十一、已知限制与改进方向

- **检索是关键词 BM25**，不理解同义词：问「上班时间」未必命中「考勤作息」。需要语义召回时可并联向量检索，工具签名不变。
- **语料内置在 classpath**：改文档要重新打包。生产应改为外部存储 + 增量索引（配合 `FSDirectory`）。
- **无鉴权**：MCP 规范支持 OAuth 2.1，公网暴露前必须接入，否则知识库对外完全开放。
- **单机内存索引**：多实例部署时各实例各自建索引，语料一致但无法共享增量更新。
- 启动日志可能出现 `SyncMcpToolProvider : No tool methods found in the provided tool objects: []`，来自 MCP 客户端侧自动装配（本项目未使用客户端功能），不影响服务端工具发布。

---

## 十二、项目组合中的位置

| 项目 | 角色 | 端口 |
|---|---|---|
| `kb-rag-system` | RAG 引擎：文档入库、混合检索、引用溯源、效果评测 | 8080 |
| `cs-agent` | 智能客服 Agent：对话、记忆、工具调用 | 8081 |
| `kb-mcp-server` | 知识库 MCP Server：把检索能力标准化对外发布 | 8082 |

三者可独立运行：`kb-mcp-server` 不依赖大模型与数据库，`cs-agent` 不启动 MCP Server 时仍可完成工单类对话（知识库工具缺席），`kb-rag-system` 与另两者无代码耦合。
