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

import java.util.ArrayList;
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
 * 万一没跑完，检索会如实告诉模型「文件还在处理中」，而不是让它凭常识硬答。
 *
 * <p>每个附件在 {@link #entries} 里有一格 {@link IndexState}，它是「这个文件的内容现在能不能检索到」
 * 的唯一依据，同时供三处使用：检索零命中时决定对模型说什么（{@code knowledgeFor}）、
 * 前端轮询看要不要提示用户（{@code stateOf}）、以及回收时找回自己的片段 id。
 *
 * <p>纯内存，重启即失效——已绑定的附件仍在库里，但向量没了，也没有任何记录说它们存在过，
 * 于是全部落到 {@link IndexState#UNAVAILABLE}，要重新上传才有检索。
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
     * 嵌入侧的元数据类型标记，两侧都要写。
     *
     * <p>{@code QwenEmbeddingModel} 会读这个键来决定用 {@code TextType.QUERY} 还是
     * {@code DOCUMENT} 嵌入——非对称检索要用它，否则问题与文档都按「文档」嵌入，召回质量会打折。
     * 别把这个键挪作他用，它会被模型抢去解释。
     *
     * <p><b>文档片段也必须带上它</b>，虽然非对称检索只关心查询侧：那个模型在调用之后会按这个键
     * 排一次序，而 {@code Metadata.getString} 对缺失的键返回 null，{@code Comparator.comparing}
     * 拿到 null 直接抛 NPE——于是「切出两段以上的文档」全都索引失败，只有单段的小文件能侥幸通过
     * （一个元素的数组不比较，所以从来没暴露）。值就是 {@code TextType} 的小写名。
     */
    private static final String TYPE_KEY = "type";
    private static final String TYPE_QUERY = "query";
    private static final String TYPE_DOCUMENT = "document";

    /** 零命中提示里最多逐个列几个文件名，其余的折成一句「另有 N 个」 */
    private static final int MAX_HINT_FILES = 3;

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> store;
    private final AttachmentStorage storage;
    private final AttachmentTypePolicy typePolicy;
    private final RagProperties properties;
    private final Executor executor;
    private final DocumentSplitter splitter;

    /**
     * 附件 id → 它这一格的状态：终态、文件名（提示词里要用）、以及切出来的片段 id。
     *
     * <p>片段 id 存在终态里而不是单独一张表，是为了让「有状态」与「有片段」不可能脱钩——
     * 拆成两张表的话，迟早出现「状态说就绪、片段表里却是空的」，而回收正是照着片段 id 删的。
     *
     * <p>没有这一格 = 本进程没见过它，即 {@link IndexState#UNAVAILABLE}。三个终态都写在这里，
     * 「没有记录」于是只剩一种含义，不会再像以前那样把索引失败与重启混为一谈。
     */
    private final Map<Long, IndexEntry> entries = new ConcurrentHashMap<>();

    /**
     * 索引跑完之前就被删掉的附件。
     *
     * <p>没有它就会漏：{@link #forget} 只能删「已经记下的」片段 id，若附件在索引途中被删，forget
     * 找不到任何东西，而索引线程随后才把片段写进去——这些片段再也没人认领，永久留在库里。
     * 它也是唯一还需要单独存在的一份状态，因为 {@link #forget} 与索引线程是靠它来<span>争</span>
     * 那一格的归属（见 {@link #index}）。
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
     * <p>空文件在这里就判掉、不进队列——它没有任何可检索的内容，占一个队列位只是白跑一趟，
     * 而队列是<b>串行</b>的（见 {@code RagConfig}），占位会拖慢排在它后面的文件。
     *
     * <p>整个方法不抛异常——它是上传成功之后的收尾动作，没有任何理由让一次已经落盘、已经入库的上传
     * 因为索引挂掉而变成失败。失败一律记进 {@link #entries}，由前端轮询或下一轮提问去取。
     */
    public void submit(Attachment attachment) {
        if (attachment == null || attachment.getId() == null || !typePolicy.isText(attachment.getMimeType())) {
            return;
        }
        long attachmentId = attachment.getId();
        IndexState initial = isBlankContent(attachment) ? IndexState.EMPTY : IndexState.PENDING;
        // putIfAbsent 既是去重也是幂等：已经有记录（终态也算）就不再重来一遍，
        // 否则「索引失败」会在每次提交时重试，而失败件多半是永久失败（文件已被删、内容超限）
        if (entries.putIfAbsent(attachmentId, IndexEntry.of(initial, attachment)) != null) {
            return;
        }
        if (initial == IndexState.EMPTY) {
            log.info("附件没有可索引的文本内容（空文件）：id={}, 文件名={}", attachmentId, attachment.getOriginalName());
            return;
        }
        try {
            executor.execute(() -> index(attachment));
        } catch (RuntimeException e) {
            // 队列已满（RagConfig 的拒绝策略抛出来的）。必须就地落一个终态：留在 PENDING 的话，
            // 这个附件此后每一轮都会对模型说「仍在处理中」，而它永远不会被处理
            entries.put(attachmentId, IndexEntry.of(IndexState.FAILED, attachment));
            log.warn("文档索引提交失败（队列已满），该文件不参与检索：id={}, 文件名={}",
                    attachmentId, attachment.getOriginalName(), e);
        }
    }

    /** 这个附件此刻的索引状态。没有记录即 {@link IndexState#UNAVAILABLE} */
    public IndexState stateOf(long attachmentId) {
        IndexEntry entry = entries.get(attachmentId);
        return entry == null ? IndexState.UNAVAILABLE : entry.state();
    }

    /**
     * 回收一个附件的全部片段。
     *
     * <p>先登记再删：索引线程可能正跑着，登记之后它会在收尾时把刚写进去的片段一并撤掉
     * （见 {@link #index}）。
     */
    public void forget(long attachmentId) {
        forgotten.add(attachmentId);
        IndexEntry removed = entries.remove(attachmentId);
        if (removed != null && !removed.segmentIds().isEmpty()) {
            store.removeAll(removed.segmentIds());
            release(removed.segmentIds().size());
            log.debug("已回收附件向量：id={}, 片段数={}", attachmentId, removed.segmentIds().size());
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
        // 有文档却零命中。可能是确实不相关，也可能是文件还排在索引队列里／索引失败／内容已随重启丢失。
        // 什么都不说的话，模型会凭常识硬答，用户没法分辨它到底读没读文件
        return unavailableHint(attachmentIds);
    }

    /**
     * 零命中时按<b>每个附件自己的状态</b>说明原因。
     *
     * <p>这里原先是「这批附件里有没有没索引好的」三选一的一整句，而那个判据把索引失败、空文件、
     * 重启后向量清空全部算了进去——于是一次重启之后，每个带过文本附件的会话都会对模型说
     * 「文件仍在处理中」，而它永远不会变成可检索。现在只有真的在排队才那么说。
     */
    private String unavailableHint(List<Long> attachmentIds) {
        boolean pending = false;
        boolean lost = false;
        List<String> files = new ArrayList<>();
        for (Long attachmentId : attachmentIds) {
            IndexEntry entry = entries.get(attachmentId);
            if (entry == null) {
                // 本进程没有它的记录 = 上传发生在上一次启动，向量已随重启清空
                lost = true;
                continue;
            }
            switch (entry.state()) {
                case PENDING -> pending = true;
                case EMPTY -> files.add(Prompt.KNOWLEDGE_EMPTY_FILE_HINT.formatted(entry.fileName()));
                case FAILED -> files.add(Prompt.KNOWLEDGE_FAILED_FILE_HINT.formatted(entry.fileName()));
                // READY 却零命中就是「不相关」，没有额外要说的；
                // NOT_INDEXED 不会走到这里——检索范围里只有文本附件
                default -> {
                }
            }
        }
        List<String> parts = new ArrayList<>(3);
        if (pending) {
            parts.add(Prompt.KNOWLEDGE_PENDING_HINT);
        }
        if (lost) {
            parts.add(Prompt.KNOWLEDGE_LOST_HINT);
        }
        if (!files.isEmpty()) {
            parts.add(joinedFiles(files));
        }
        // 全都就绪却零命中：确实不相关，而不是「还没有」
        return parts.isEmpty() ? Prompt.KNOWLEDGE_EMPTY_HINT : String.join("", parts);
    }

    /** 文件一多，逐个列名字会把提示词越撑越长；列前几个再报一个总数，够模型判断了 */
    private static String joinedFiles(List<String> notes) {
        if (notes.size() <= MAX_HINT_FILES) {
            return String.join("", notes);
        }
        return String.join("", notes.subList(0, MAX_HINT_FILES))
                + Prompt.KNOWLEDGE_MORE_FILES_HINT.formatted(notes.size() - MAX_HINT_FILES);
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

    /**
     * 索引一个附件，跑在索引线程池上。
     *
     * <p>四条失败路径（切分后为空、片段过多、向量库预算耗尽、embedding 或存储抛异常）都要落一个
     * <b>终态</b>。停在 PENDING 就等于对模型说「再等等」，而等待不会有结果。
     */
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
                // submit 时已经按「解码后是否全空白」挡过一道，这里是兜底：切分器的过滤条件
                // （丢掉全空白的片段）与那个判据毕竟不是同一段代码
                mark(attachmentId, IndexState.EMPTY, attachment);
                log.info("附件没有可索引的文本内容：id={}, 文件名={}", attachmentId, attachment.getOriginalName());
                return;
            }
            int perDocumentLimit = properties.maxSegmentsPerDocument();
            if (segments.size() > perDocumentLimit) {
                mark(attachmentId, IndexState.FAILED, attachment);
                log.warn("附件切分出的片段过多（{} 段，单文件上限 {}），不索引：id={}, 文件名={}",
                        segments.size(), perDocumentLimit, attachmentId, attachment.getOriginalName());
                return;
            }
            reserved = reserve(segments.size());
            if (reserved == 0) {
                mark(attachmentId, IndexState.FAILED, attachment);
                log.warn("向量库片段总数已达上限 {}，本次不索引（重启或删除附件后恢复）：id={}, 文件名={}, 需要 {} 段",
                        properties.maxTotalSegments(), attachmentId, attachment.getOriginalName(), segments.size());
                return;
            }
            // QwenEmbeddingModel 内部已按 10 条一批自己切分，这里不必再分
            List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
            List<String> segmentIds = store.addAll(embeddings, segments);
            // 与 forget 争这一格：两边都走 map 的同一个 key，谁先谁后由它定序，谁都不会漏删或多删
            IndexEntry settled = entries.compute(attachmentId, (key, current) ->
                    forgotten.contains(key) ? null : IndexEntry.ready(attachment, segmentIds));
            if (settled == null) {
                // 索引跑的这段时间里附件被删了。刚写进去的片段必须自己撤掉，否则永久泄漏。
                // 配额不能在这里释放：kept 仍是 false，由 finally 统一释放
                store.removeAll(segmentIds);
                log.debug("附件在索引途中被删除，已撤回片段：id={}, 片段数={}", attachmentId, segmentIds.size());
                return;
            }
            kept = true;
            log.info("已索引附件：id={}, 文件名={}, 片段数={}", attachmentId, attachment.getOriginalName(), segmentIds.size());
        } catch (Exception e) {
            mark(attachmentId, IndexState.FAILED, attachment);
            log.warn("索引附件失败，该文件本轮不参与检索：id={}, 文件名={}",
                    attachmentId, attachment.getOriginalName(), e);
        } finally {
            if (reserved > 0 && !kept) {
                release(reserved);
            }
            // 期间被删掉的话不留记录：它已经不存在了，任何状态都只会误导
            if (forgotten.contains(attachmentId)) {
                entries.remove(attachmentId);
            }
        }
    }

    /** 落一个终态。期间被删则不留记录（与 {@link #index} 的收尾同一个判据） */
    private void mark(long attachmentId, IndexState state, Attachment attachment) {
        entries.compute(attachmentId, (key, current) ->
                forgotten.contains(key) ? null : IndexEntry.of(state, attachment));
    }

    /**
     * 上传后、入队前的空文件预检。
     *
     * <p>读不出来或解不开一律当作「不是空文件」，交给索引线程去报错——那里有一条完整的失败记录
     * 与堆栈，而这里只是个省一次排队的优化，不该抢它的活。
     */
    private boolean isBlankContent(Attachment attachment) {
        try {
            return PlainTextDecoder.decode(storage.read(attachment.getObjectKey()))
                    .map(String::isBlank)
                    .orElse(false);
        } catch (RuntimeException e) {
            return false;
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
                FILE_NAME_KEY, attachment.getOriginalName(),
                // 不能省：少这个键时「切出两段以上」的文档会在 embedding 那一步抛 NPE（见 TYPE_KEY）
                TYPE_KEY, TYPE_DOCUMENT));
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

    /** 一个附件的那一格：状态、文件名（提示词要用）、以及可检索的片段 id（就绪时才有） */
    private record IndexEntry(IndexState state, String fileName, List<String> segmentIds) {

        static IndexEntry of(IndexState state, Attachment attachment) {
            return new IndexEntry(state, attachment.getOriginalName(), List.of());
        }

        static IndexEntry ready(Attachment attachment, List<String> segmentIds) {
            return new IndexEntry(IndexState.READY, attachment.getOriginalName(), List.copyOf(segmentIds));
        }
    }
}
