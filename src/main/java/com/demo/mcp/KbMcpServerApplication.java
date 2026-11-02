package com.demo.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 知识库 MCP Server 启动类。
 *
 * <p>MCP 是什么？一句话：给大模型应用提供的"USB 接口标准"——
 * 任何工具/数据源只要实现 MCP 协议，就能被 Claude、Cursor、以及任何
 * 支持 MCP 的 Agent 框架即插即用地调用。</p>
 *
 * <p>本项目把「知识库检索」封装成两个 MCP 工具：
 * - search_knowledge_base(query, topK)：关键词检索知识库，返回相关片段
 * - get_document(docId)：按文档 ID 返回全文
 * 配合 E:\Workspaces\cs-agent（智能客服 Agent）食用：cs-agent 通过 MCP
 * 客户端接入本服务，把"查知识库"变成自己的一个工具能力。</p>
 */
@SpringBootApplication
public class KbMcpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(KbMcpServerApplication.class, args);
    }
}
