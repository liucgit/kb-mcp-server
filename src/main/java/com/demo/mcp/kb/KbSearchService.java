package com.demo.mcp.kb;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 知识库检索服务：Apache Lucene 实现（替换旧版手写 BM25）。
 *
 * <p>★★ 为什么要换成 Lucene ★★
 * 旧版自己做了三件"看起来简单、其实全是坑"的事：中文分词（unigram+bigram 暴力切）、
 * 倒排索引（每次查询全量扫描算 TF-IDF）、BM25 打分公式（k1/b 调参、文档长度归一化）。
 * 这三件事在工业界早有成熟实现：
 * <ul>
 *   <li>分词：{@link SmartChineseAnalyzer}（中文词库 + 未登录词识别），比暴力双字切分准得多；</li>
 *   <li>索引：Lucene 倒排索引（写时建索引，查询只扫命中文档，不再全表扫描）；</li>
 *   <li>打分：{@link BM25Similarity}（Lucene 默认相似度，k1=1.2、b=0.75，可调）。</li>
 * </ul>
 * 对外行为保持不变：同样是"段落级检索、返回带标题与 docId 的文本块"，工具侧无需改动。</p>
 *
 * <p>索引放在哪？{@link ByteBuffersDirectory}（堆外内存目录）——
 * 本项目的文档是 classpath 里的 4 份 FAQ，启动时建一次索引即可，进程内检索零 IO。
 * 生产环境把这里换成 {@code FSDirectory}（本地磁盘）或 Elasticsearch/OpenSearch 客户端，
 * 上层 {@link com.demo.mcp.tools.KbTools} 完全不用改。</p>
 */
@Service
public class KbSearchService {

    private static final Logger log = LoggerFactory.getLogger(KbSearchService.class);

    /** 字段名：文档 ID / 标题 / 段落正文 */
    private static final String F_DOC_ID = "docId";
    private static final String F_TITLE = "title";
    private static final String F_TEXT = "text";

    /** 中文分析器：负责分词与小写化；索引与查询必须用同一个，否则词形对不上必然搜不到 */
    private final Analyzer analyzer = new SmartChineseAnalyzer();

    private final KnowledgeBase knowledgeBase;
    private final Directory directory = new ByteBuffersDirectory();
    private final IndexSearcher searcher;

    public KbSearchService(KnowledgeBase knowledgeBase) {
        this.knowledgeBase = knowledgeBase;
        try {
            int passages = buildIndex();
            // DirectoryReader.open 得到只读快照：文档在启动时一次性建好，无需实时刷新
            this.searcher = new IndexSearcher(DirectoryReader.open(directory));
            // 显式声明相似度算法，让"为什么这样排序"可解释（默认就是 BM25Similarity，写出来便于调参）
            this.searcher.setSimilarity(new BM25Similarity(1.2f, 0.75f));
            log.info("Lucene 索引就绪：{} 份文档 / {} 个段落，分析器={}, 相似度=BM25(k1=1.2, b=0.75)",
                    knowledgeBase.all().size(), passages, analyzer.getClass().getSimpleName());
        } catch (IOException e) {
            // 建索引失败属于启动期致命错误：宁可起不来，也不带着坏索引对外提供服务
            throw new IllegalStateException("构建 Lucene 索引失败", e);
        }
    }

    /**
     * 建索引：把每份文档切成"语义段落"，一个段落 = 一个 Lucene Document。
     *
     * <p>切分规则（比旧版"按空行硬切"多两步，都是检索质量的实战经验）：
     * <ol>
     *   <li>跳过文档首行的 {@code # 标题}：它已经存在 title 字段里，重复索引只会让噪声段落挤进结果；</li>
     *   <li>小标题（{@code ## 报销时限} 这种独占一段的行）不单独成段，而是与紧随其后的正文合并，
     *       否则用户一检索就会命中一堆只有标题的"空段落"（旧版就踩过这个坑）。</li>
     * </ol>
     * 保持"段落级"粒度的原因：返回给大模型的是可直接引用的片段，
     * 而不是整篇文档（省 token，也更容易溯源到具体条款）。</p>
     *
     * @return 索引的段落总数
     */
    private int buildIndex() throws IOException {
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
        int passages = 0;
        try (IndexWriter writer = new IndexWriter(directory, config)) {
            for (KnowledgeBase.KbDoc doc : knowledgeBase.all()) {
                for (String passage : toPassages(doc)) {
                    Document document = new Document();
                    // docId 只存不切词（精确值，用于回链与 get_document）
                    document.add(new StringField(F_DOC_ID, doc.docId(), Field.Store.YES));
                    // 标题参与检索（切词），查询时通过 boost 加权
                    document.add(new TextField(F_TITLE, doc.title(), Field.Store.YES));
                    document.add(new TextField(F_TEXT, passage, Field.Store.YES));
                    writer.addDocument(document);
                    passages++;
                }
            }
        }
        return passages;
    }

    /**
     * 把一篇文档切成段落列表：纯标题段落与后续正文合并。
     *
     * @param doc 知识库文档
     * @return 段落文本列表
     */
    private List<String> toPassages(KnowledgeBase.KbDoc doc) {
        String content = doc.content().trim();
        // 文档第一行就是 "# 客服知识库：xxx FAQ"，与 title 字段重复，切分时跳过
        String docTitleLine = content.lines().findFirst().orElse("").trim();

        List<String> passages = new ArrayList<>();
        String pendingHeading = null;
        for (String block : content.split("\\n\\s*\\n")) {
            String text = block.trim();
            if (text.isEmpty() || text.equals(docTitleLine)) {
                continue;
            }
            String firstLine = text.lines().findFirst().orElse("").trim();
            boolean headingOnly = text.lines().count() == 1 && firstLine.startsWith("#");
            if (headingOnly) {
                // 小标题先暂存，等正文来了再合并
                String heading = firstLine.replaceAll("^#+\\s*", "");
                pendingHeading = (pendingHeading == null) ? heading : pendingHeading + " · " + heading;
                continue;
            }
            passages.add(pendingHeading == null ? text : "【" + pendingHeading + "】\n" + text);
            pendingHeading = null;
        }
        if (pendingHeading != null) {
            passages.add("【" + pendingHeading + "】");
        }
        return passages;
    }

    /**
     * 检索知识库。
     *
     * @param query 查询问题
     * @param topK  返回段落数（null 或小于等于 0 时默认 3）
     * @return 面向大模型阅读优化的结构化文本（格式与旧版完全一致）
     */
    public String search(String query, Integer topK) {
        int k = (topK == null || topK <= 0) ? 3 : topK;
        if (query == null || query.isBlank()) {
            return "查询为空，无结果。";
        }

        Query parsed;
        try {
            // QueryParser 不是线程安全的：每次查询新建一个（构造开销极小），
            // 同时避免 MCP 并发调用时互相污染状态
            MultiFieldQueryParser parser = new MultiFieldQueryParser(
                    new String[]{F_TEXT, F_TITLE}, analyzer,
                    Map.of(F_TEXT, 1.0f, F_TITLE, 2.0f));
            // OR：任一关键词命中即进入候选，由 BM25 负责排序（与旧版"命中即计分"的语义一致）
            parser.setDefaultOperator(QueryParser.Operator.OR);
            // escape：把用户输入里的 + - : ( ) 等特殊字符转义，防止查询语法错误
            parsed = parser.parse(QueryParser.escape(query.trim()));
        } catch (ParseException e) {
            log.warn("查询解析失败，按无结果处理: query={}, err={}", query, e.getMessage());
            return "知识库中未找到与「" + query + "」相关的内容。";
        }

        try {
            TopDocs topDocs = searcher.search(parsed, k);
            if (topDocs.scoreDocs.length == 0) {
                return "知识库中未找到与「" + query + "」相关的内容。";
            }
            StringBuilder sb = new StringBuilder("共找到 ")
                    .append(topDocs.scoreDocs.length).append(" 条相关内容：\n\n");
            int rank = 1;
            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document hit = searcher.storedFields().document(scoreDoc.doc);
                sb.append(rank++).append(". 【").append(hit.get(F_TITLE)).append("】(docId=")
                        .append(hit.get(F_DOC_ID)).append(")\n")
                        .append(hit.get(F_TEXT)).append("\n\n");
            }
            return sb.toString();
        } catch (IOException e) {
            log.error("Lucene 检索失败", e);
            return "检索服务暂时不可用，请稍后重试。";
        }
    }

    /** 按 docId 返回全文 */
    public String getDocument(String docId) {
        KnowledgeBase.KbDoc doc = knowledgeBase.byId(docId);
        return "【" + doc.title() + "】(docId=" + doc.docId() + ")\n\n" + doc.content();
    }
}
