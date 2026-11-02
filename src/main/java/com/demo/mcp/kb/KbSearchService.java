package com.demo.mcp.kb;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 知识库 BM25 检索服务（与项目一的 KeywordIndex 同思路，简化版）。
 *
 * <p>检索粒度是「段落」：把每份 FAQ 文档按空行切段、对段落建索引，
 * 返回时带上所属文档标题，方便调用方（大模型）理解上下文来源。</p>
 */
@Service
public class KbSearchService {

    private static final double K1 = 1.5;
    private static final double B = 0.75;

    /** 索引条目：一个段落 */
    private record Passage(String docId, String docTitle, String text, List<String> terms) {
    }

    private final KnowledgeBase knowledgeBase;
    private final List<Passage> passages = new ArrayList<>();

    public KbSearchService(KnowledgeBase knowledgeBase) {
        this.knowledgeBase = knowledgeBase;
        // 启动时对全部文档做"段落级"分词建索引
        for (KnowledgeBase.KbDoc doc : knowledgeBase.all()) {
            String[] blocks = doc.content().split("\n\\s*\n");
            for (String block : blocks) {
                String text = block.trim();
                if (!text.isEmpty()) {
                    passages.add(new Passage(doc.docId(), doc.title(), text, tokenize(text)));
                }
            }
        }
    }

    /**
     * 检索知识库。
     *
     * @param query 查询问题
     * @param topK  返回段落数（null 或 <=0 时默认 3）
     * @return 面向大模型阅读优化的结构化文本
     */
    public String search(String query, Integer topK) {
        int k = (topK == null || topK <= 0) ? 3 : topK;
        List<String> queryTerms = tokenize(query);
        if (queryTerms.isEmpty()) {
            return "查询为空，无结果。";
        }

        double avgLen = passages.stream().mapToInt(p -> p.terms().size()).average().orElse(1.0);
        long docCount = passages.size();

        record Scored(Passage passage, double score) {
        }
        List<Scored> scored = new ArrayList<>();
        for (Passage p : passages) {
            double s = 0;
            for (String term : queryTerms) {
                double tf = p.terms().stream().filter(t -> t.equals(term)).count();
                if (tf == 0) {
                    continue;
                }
                long df = passages.stream().filter(x -> x.terms().contains(term)).count();
                double idf = Math.log((docCount - df + 0.5) / (df + 0.5) + 1.0);
                s += idf * (tf * (K1 + 1)) / (tf + K1 * (1 - B + B * p.terms().size() / avgLen));
            }
            if (s > 0) {
                scored.add(new Scored(p, s));
            }
        }
        scored.sort((a, b) -> Double.compare(b.score(), a.score()));

        if (scored.isEmpty()) {
            return "知识库中未找到与「" + query + "」相关的内容。";
        }

        StringBuilder sb = new StringBuilder("共找到 ").append(Math.min(k, scored.size()))
                .append(" 条相关内容：\n\n");
        int rank = 1;
        for (Scored item : scored.subList(0, Math.min(k, scored.size()))) {
            Passage p = item.passage();
            sb.append(rank++).append(". 【").append(p.docTitle()).append("】(docId=").append(p.docId()).append(")\n")
                    .append(p.text()).append("\n\n");
        }
        return sb.toString();
    }

    /** 按 docId 返回全文 */
    public String getDocument(String docId) {
        KnowledgeBase.KbDoc doc = knowledgeBase.byId(docId);
        return "【" + doc.title() + "】(docId=" + doc.docId() + ")\n\n" + doc.content();
    }

    /**
     * 极简中文分词：unigram + bigram（与项目一保持同一套实现思路）。
     */
    static List<String> tokenize(String text) {
        String normalized = text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (Character.isWhitespace(c) || isPunct(c)) {
                continue;
            }
            tokens.add(String.valueOf(c));
            if (i + 1 < normalized.length()) {
                char next = normalized.charAt(i + 1);
                if (!Character.isWhitespace(next) && !isPunct(next)) {
                    tokens.add(normalized.substring(i, i + 2));
                }
            }
        }
        return tokens;
    }

    private static boolean isPunct(char c) {
        return "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~，。！？；：、（）《》【】\u201C\u201D\u2018\u2019…—·".indexOf(c) >= 0;
    }
}
