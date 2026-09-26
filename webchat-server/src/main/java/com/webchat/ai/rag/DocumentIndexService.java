package com.webchat.ai.rag;

import com.webchat.ai.Prompt;
import com.webchat.entity.Attachment;
import com.webchat.service.AttachmentTypePolicy;
import com.webchat.service.PlainTextDecoder;
import com.webchat.storage.AttachmentStorage;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 文档的向量化与检索，是 RAG 的唯一入口。
 *
 * <p><b>依赖的是 {@link AttachmentStorage} 而不是 {@code AttachmentService}</b>：后者要在删除附件时
 * 反过来调 {@link #forget} 回收向量，互相注入就成环，而 Spring Boot 默认禁止循环引用、启动直接失败。
 * 本类只需要「按对象键读出字节」，用 storage 反而更贴合它真正需要的东西。这条边不能改。
 *
 * <p>索引是<b>异步</b>的：上传接口返回时索引多半还没跑完，用户在这期间打字，通常能在点发送之前完成。
 * 万一没跑完，检索会命中 {@link #anyUnavailable} 那条分支，如实告诉模型「文件还在处理中」，
 * 而不是让它凭常识硬答。
 *
 * <p>纯内存，重启即失效——已绑定的附件仍在库里，但向量没了，要重新上传才有检索。
 */
@Slf4j
@Service
public class DocumentIndexService {

    /** 线程池 bean 名。按类型注入 Executor 是歧义的（Web 自动配置也会提供），必须点名 */
    public static final String EXECUTOR_BEAN = "documentIndexExecutor";

    /** 片段元数据：所属附件 id。检索时按它过滤，删除附件时按它回收 */
    private static final String ATTACHMENT_ID_KEY = "attachmentId";

    /** 片段元数据：原始文件名，用于在提示词里标注片段来源 */
    private static final String FILE_NAME_KEY = "fileName";

    /**
     * 查询侧的元数据类型标记。
     *
     * <p>{@code QwenEmbeddingModel} 会读这个键（值是字符串 {@code "query"}）来决定用
     * {@code TextType.QUERY} 还是 {@code DOCUMENT} 嵌入——非对称检索要用它，否则问题与文档都按
     * 「文档」嵌入，召回质量会打折。别把这个键挪作他用，它会被模型抢去解释。
     */
    private static final String TYPE_KEY = "type";
    private static final String TYPE_QUERY = "query";

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> store;
    private final AttachmentStorage storage;
    private final AttachmentTypePolicy typePolicy;
    private final RagProperties properties;
    private final Executor executor;
    private final DocumentSplitter splitter;

    /** 附件 id → 它切出来的片段 id。既用于精确回收，存在与否也就是「已索引」的判据 */
    private final Map<Long, List<String>> segmentsByAttachment = new ConcurrentHashMap<>();

    /** 已提交但还没跑完的附件，用于识别「文件还在处理中」 */
    private final Set<Long> pending = ConcurrentHashMap.newKeySet();

    /**
     * 索引跑完之前就被删掉的附件。
     *
     * <p>没有它就会漏：{@link #forget} 只能删「已经记下的」片段 id，若附件在索引途中被删，forget
     * 找不到任何东西，而索引线程随后才把片段写进去——这些片段再也没人认领，永久留在库里。
     */
    private final Set<Long> forgotten = ConcurrentHashMap.newKeySet();

    private final AtomicInteger totalSegments = new AtomicInteger();

    /**
     * 手工写构造器而不是用 {@code @RequiredArgsConstructor}：{@code executor} 必须带
     * {@code @Qualifier}，而 Lombok 默认不会把限定符复制到构造参数上。
     */
    public DocumentIndexService(EmbeddingModel embeddingModel,
                                EmbeddingStore<TextSegment> store,
                                AttachmentStorage storage,
                                AttachmentTypePolicy typePolicy,
                                RagProperties properties,
                                @Qualifier(EXECUTOR_BEAN) Executor executor) {
        this.embeddingModel = embeddingModel;
        this.store = store;
        this.storage = storage;
        this.typePolicy = typePolicy;
        this.properties = properties;
        this.executor = executor;
        this.splitter = DocumentSplitters.recursive(properties.chunkSize(), properties.chunkOverlap());
    }

    /**
     * 提交一个附件去索引，立即返回。
     *
     * <p>只处理文本附件：图片与视频是 base64 塞进多模态消息的，不进向量库。
     *
     * <p>整个方法不抛异常——它是上传成功之后的收尾动作，没有任何理由让一次已经落盘、已经入库的上传
     * 因为索引挂掉而变成失败。
     */
    public void submit(Attachment attachment) {
        if (attachment == null || attachment.getId() == null || !typePolicy.isText(attachment.getMimeType())) {
            return;
        }
        long attachmentId = attachment.getId();
        // add 是原子的：重复提交同一个附件时只有第一次会往下走
        if (segmentsByAttachment.containsKey(attachmentId) || !pending.add(attachmentId)) {
            return;
        }
        try {
            executor.execute(() -> index(attachment));
        } catch (RuntimeException e) {
            pending.remove(attachmentId);
            log.warn("提交文档索引失败：id={}", attachmentId, e);
        }
    }

    /**
     * 回收一个附件的全部片段。
     *
     * <p>先登记再删：索引线程可能正跑着，登记之后它会在收尾时把刚写进去的片段一并撤掉
     * （见 {@link #index}）。
     */
    public void forget(long attachmentId) {
        forgotten.add(attachmentId);
        List<String> segmentIds = segmentsByAttachment.remove(attachmentId);
        if (segmentIds != null) {
            store.removeAll(segmentIds);
            release(segmentIds.size());
            log.debug("已回收附件向量：id={}, 片段数={}", attachmentId, segmentIds.size());
        }
    }

    /** 批量回收，会话被删除时一次清掉它的全部文本附件 */
    public void forgetAll(Collection<Long> attachmentIds) {
        if (attachmentIds == null) {
            return;
        }
        attachmentIds.forEach(this::forget);
    }

    /**
     * 为一次提问取回可直接注入提示词的文档片段。
     *
     * <p>三种结果都是「字符串」而不是异常：检索不到不该让对话失败，模型没有资料也该照常回答。
     *
     * @return 拼好的片段文本；没有可检索的文档、或提问为空时返回空串
     */
    public String knowledgeFor(String query, List<Long> attachmentIds) {
        // 提问为空（只传了文件没打字）时不检索：拿空串去嵌入既是白跑一次网络调用，
        // 取回的结果也是噪声。此时模型只会看到「用户上传了文件：x.txt」那行说明
        if (attachmentIds == null || attachmentIds.isEmpty() || query == null || query.isBlank()) {
            return "";
        }
        List<TextSegment> segments = retrieve(query, attachmentIds);
        if (!segments.isEmpty()) {
            log.debug("检索命中 {} 个片段，候选附件 {} 个", segments.size(), attachmentIds.size());
            return format(segments);
        }
        // 有文档却零命中。两种可能：确实不相关，或者文件还排在索引队列里（上传后立刻提问必然如此）。
        // 什么都不说的话，模型会凭常识硬答，用户没法分辨它到底读没读文件
        return anyUnavailable(attachmentIds) ? Prompt.KNOWLEDGE_PENDING_HINT : Prompt.KNOWLEDGE_EMPTY_HINT;
    }

    /** 这批附件里有没有还不能检索的（仍在索引中，或压根没索引成功） */
    public boolean anyUnavailable(Collection<Long> attachmentIds) {
        return attachmentIds.stream()
                .anyMatch(id -> pending.contains(id) || !segmentsByAttachment.containsKey(id));
    }

    private List<TextSegment> retrieve(String query, List<Long> attachmentIds) {
        // 空列表不能传给 isIn：它的构造器里有 ensureNotEmpty，会直接抛 IllegalArgumentException，
        // 而异常会被上层的降级逻辑吞掉——表现是每轮对话白抛一次、白调一次 embedding
        if (attachmentIds.isEmpty()) {
            return List.of();
        }
        Embedding queryEmbedding = embeddingModel
                .embed(TextSegment.from(query, Metadata.from(TYPE_KEY, TYPE_QUERY)))
                .content();
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(properties.maxResults())
                // 阈值刻意不交给 store 过滤（0 表示不过滤，余弦的最小可能值对应的就是 0）：
                // 先按相似度取前 N 条，再在这里裁定，这样即使一条都没留下，也能把「最像的那条有多像」
                // 记进日志。交给 store 滤掉的话，阈值偏高时表现是 RAG 静默失效，没有任何可排查的线索
                .minScore(0.0)
                .filter(MetadataFilterBuilder.metadataKey(ATTACHMENT_ID_KEY).isIn(attachmentIds))
                .build();
        List<EmbeddingMatch<TextSegment>> matches = store.search(request).matches();
        List<TextSegment> kept = matches.stream()
                .filter(match -> match.score() >= properties.minScore())
                .map(match -> match.embedded())
                .filter(Objects::nonNull)
                .toList();
        if (log.isDebugEnabled()) {
            // 调阈值就靠这条：分数是 RelevanceScore，等于 (余弦 + 1) / 2
            log.debug("文档检索：候选 {} 条，最高分 {}，阈值 {}，保留 {} 条",
                    matches.size(),
                    matches.isEmpty() ? "无" : String.format("%.3f", matches.get(0).score()),
                    properties.minScore(), kept.size());
        }
        return kept;
    }

    private void index(Attachment attachment) {
        long attachmentId = attachment.getId();
        int reserved = 0;
        boolean kept = false;
        try {
            if (forgotten.contains(attachmentId)) {
                // 排队期间就被删了，不用白跑一次 embedding
                return;
            }
            List<TextSegment> segments = split(attachment);
            if (segments.isEmpty()) {
                log.info("附件没有可索引的文本内容：id={}, 文件名={}", attachmentId, attachment.getOriginalName());
                return;
            }
            reserved = reserve(segments.size());
            if (reserved == 0) {
                return;
            }
            // QwenEmbeddingModel 内部已按 10 条一批自己切分，这里不必再分
            List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
            List<String> segmentIds = store.addAll(embeddings, segments);
            if (forgotten.contains(attachmentId)) {
                // 索引跑的这段时间里附件被删了。刚写进去的片段必须自己撤掉，否则永久泄漏
                store.removeAll(segmentIds);
                log.debug("附件在索引途中被删除，已撤回片段：id={}, 片段数={}", attachmentId, segmentIds.size());
                return;
            }
            segmentsByAttachment.put(attachmentId, segmentIds);
            kept = true;
            log.info("已索引附件：id={}, 文件名={}, 片段数={}", attachmentId, attachment.getOriginalName(), segmentIds.size());
        } catch (Exception e) {
            log.warn("索引附件失败，该文件本轮不参与检索：id={}, 文件名={}",
                    attachmentId, attachment.getOriginalName(), e);
        } finally {
            if (reserved > 0 && !kept) {
                release(reserved);
            }
            pending.remove(attachmentId);
        }
    }

    /** 读字节 → 解码 → 切分。元数据写在 {@link Document} 上，切分时会复制到每个片段 */
    private List<TextSegment> split(Attachment attachment) {
        byte[] content = storage.read(attachment.getObjectKey());
        // 上传时已经解过一次，解不出来早就被拒了；走到这里说明文件在落盘之后被改过
        String text = PlainTextDecoder.decode(content)
                .orElseThrow(() -> new IllegalStateException("附件内容已不是可解码的文本：" + attachment.getObjectKey()));
        Metadata metadata = Metadata.from(Map.of(
                ATTACHMENT_ID_KEY, attachment.getId(),
                FILE_NAME_KEY, attachment.getOriginalName()));
        // 切分器按「段落 → 行 → 句子 → 词 → 字符」逐级下探，最后一级是逐字符切，
        // 所以没有换行的超长单行也会被切到 chunkSize 以内，不会产生巨型片段
        return splitter.split(Document.from(text, metadata)).stream()
                .filter(segment -> !segment.text().isBlank())
                .toList();
    }

    /**
     * 预留容量。
     *
     * @return 实际预留的片段数；0 表示已超上限、本次不索引
     */
    private int reserve(int segments) {
        int limit = properties.maxTotalSegments();
        while (true) {
            int current = totalSegments.get();
            if (current + segments > limit) {
                log.warn("向量库已达上限 {} 段，本次不再索引（重启或删除附件后恢复）", limit);
                return 0;
            }
            if (totalSegments.compareAndSet(current, current + segments)) {
                return segments;
            }
        }
    }

    private void release(int segments) {
        totalSegments.addAndGet(-segments);
    }

    private static String format(List<TextSegment> segments) {
        StringBuilder block = new StringBuilder();
        for (int index = 0; index < segments.size(); index++) {
            TextSegment segment = segments.get(index);
            block.append("【片段 ").append(index + 1)
                    .append("｜来源：").append(segment.metadata().getString(FILE_NAME_KEY)).append("】\n")
                    .append(segment.text()).append("\n\n");
        }
        return Prompt.KNOWLEDGE_PROMPT_TEMPLATE.formatted(block.toString().strip());
    }
}
