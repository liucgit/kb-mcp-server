package com.demo.mcp.config;

import org.springframework.ai.tool.execution.ToolCallResultConverter;

import java.lang.reflect.Type;

/**
 * 纯文本结果转换器：工具方法返回的字符串原样交给模型，不做 JSON 序列化。
 *
 * <p>为什么需要它？Spring AI 默认的结果转换器会把返回值 JSON 序列化 ——
 * 字符串会被包成 {@code "……"} 并转义换行（变成 {@code \"共找到 3 条……\n\n"}）。
 * 对 LLM 来说这多了一层噪音，且与旧版 MCP Server 的返回格式不一致。
 * 本项目的工具本来就返回"给模型读的文本"，所以直接 toString()。</p>
 *
 * <p>框架要求转换器有无参构造（@Tool 的 resultConverter 是 Class 字面量，
 * 由框架反射实例化），所以这里保持一个公开的默认构造函数。</p>
 */
public class PlainTextResultConverter implements ToolCallResultConverter {

    @Override
    public String convert(Object result, Type returnType) {
        return result == null ? "" : result.toString();
    }
}
