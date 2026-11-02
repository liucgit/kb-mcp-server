package com.demo.mcp.kb;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 内置知识库文档仓库：从 classpath:docs 加载客服 FAQ 文档。
 *
 * <p>与项目一保持一致的设计：数据内置、开箱即用。
 * 生产中这里应替换为真正的文档存储（数据库/对象存储/项目一的向量库）。</p>
 */
@Component
public class KnowledgeBase {

    private static final String DOCS_PATTERN = "classpath:docs/*.md";

    /** 知识库文档 */
    public record KbDoc(String docId, String title, String content) {
    }

    private final List<KbDoc> docs = new ArrayList<>();

    /** 应用启动时加载全部文档（构造时执行） */
    public KnowledgeBase() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            for (Resource resource : resolver.getResources(DOCS_PATTERN)) {
                String filename = resource.getFilename();
                if (filename == null) {
                    continue;
                }
                String docId = filename.substring(0, filename.lastIndexOf('.'));
                String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                // 取第一行 # 标题作为展示标题
                String title = content.lines().findFirst().orElse(docId).replaceAll("^#+\\s*", "");
                docs.add(new KbDoc(docId, title, content));
            }
        } catch (IOException e) {
            throw new IllegalStateException("加载知识库文档失败: " + DOCS_PATTERN, e);
        }
    }

    public List<KbDoc> all() {
        return List.copyOf(docs);
    }

    /** 按文档 ID 取全文 */
    public KbDoc byId(String docId) {
        return docs.stream()
                .filter(d -> d.docId().equals(docId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("文档不存在: " + docId + "，可用 docId: "
                        + docs.stream().map(KbDoc::docId).toList()));
    }
}
