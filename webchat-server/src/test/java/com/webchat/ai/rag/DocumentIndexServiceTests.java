package com.webchat.ai.rag;

import com.webchat.ai.Prompt;
import com.webchat.entity.Attachment;
import com.webchat.service.AttachmentTypePolicy;
import com.webchat.storage.AttachmentStorage;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.comparison.IsIn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 文档索引与检索的单元测试。
 *
 * <p>用 {@code Runnable::run} 当执行器，把异步索引变成确定性的同步调用；需要「还在排队」这个状态时
 * 换成一个只收不跑的执行器。全程不联网、不消耗 API 额度。
 *
 * <p>几处最容易静默出错的地方都被钉住了：媒体不该进向量库、空 id 列表不能传给 {@code isIn}
 * （它的构造器里是 ensureNotEmpty，空集合直接抛，而异常会被上层的降级逻辑吞掉，
 * 表现是每轮对话白抛一次）、以及附件在索引途中被删时那些刚写进去的片段要自己撤回。
 */
@ExtendWith(MockitoExtension.class)
class DocumentIndexServiceTests {

    private static final long ATTACHMENT_ID = 100L;
    private static final String OBJECT_KEY = "2026/09/23/100.txt";

    @Mock
    private EmbeddingModel embeddingModel;
    @Mock
    private EmbeddingStore<TextSegment> store;
    @Mock
    private AttachmentStorage storage;

    /** 用真实实现：类型判断正是这条链路的入口条件，mock 掉等于把要测的东西测没了 */
    private final AttachmentTypePolicy typePolicy = new AttachmentTypePolicy();

    private final List<Runnable> queued = new ArrayList<>();

    private DocumentIndexService service;

    @BeforeEach
    void setUp() {
        service = serviceWith(defaultProperties(), Runnable::run);
    }

    private static RagProperties defaultProperties() {
        return new RagProperties(5, 0.7, 1200, 200, 10000, Duration.ofSeconds(3));
    }

    private DocumentIndexService serviceWith(RagProperties properties, Executor executor) {
        return new DocumentIndexService(embeddingModel, store, storage, typePolicy, properties, executor);
    }

    private static Attachment attachment(long id, String mimeType) {
        Attachment attachment = new Attachment();
        attachment.setId(id);
        attachment.setMimeType(mimeType);
        attachment.setObjectKey("2026/09/23/" + id + ".txt");
        attachment.setOriginalName("纪要-" + id + ".txt");
        return attachment;
    }

    private static Embedding embedding() {
        return Embedding.from(new float[]{0.1f, 0.2f});
    }

    private void storageReturns(String text) {
        when(storage.read(any())).thenReturn(text.getBytes(StandardCharsets.UTF_8));
    }

    private void modelReturnsOneEmbeddingPerSegment() {
        when(embeddingModel.embedAll(anyList())).thenAnswer(invocation -> {
            List<TextSegment> segments = invocation.getArgument(0);
            return Response.from(segments.stream().map(segment -> embedding()).toList());
        });
    }

    // ---------------------------------------------------------------- 索引

    @Test
    @DisplayName("文本附件会被切分并写进向量库，元数据带上附件 id 与文件名")
    @SuppressWarnings("unchecked")
    void indexesTextAttachment() {
        storageReturns("第一段。第二段。");
        modelReturnsOneEmbeddingPerSegment();
        when(store.addAll(anyList(), anyList())).thenReturn(List.of("seg-1"));

        service.submit(attachment(ATTACHMENT_ID, "text/plain"));

        ArgumentCaptor<List<TextSegment>> captor = ArgumentCaptor.forClass(List.class);
        verify(store).addAll(anyList(), captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        Metadata metadata = captor.getValue().get(0).metadata();
        assertThat(metadata.getLong("attachmentId")).isEqualTo(ATTACHMENT_ID);
        assertThat(metadata.getString("fileName")).isEqualTo("纪要-100.txt");
    }

    @Test
    @DisplayName("图片与视频不进向量库：它们是 base64 塞进多模态消息的")
    void ignoresMedia() {
        service.submit(attachment(ATTACHMENT_ID, "image/png"));

        verifyNoInteractions(storage, embeddingModel, store);
    }

    @Test
    @DisplayName("同一个附件重复提交只索引一次")
    void indexesAnAttachmentOnlyOnce() {
        storageReturns("正文");
        modelReturnsOneEmbeddingPerSegment();
        when(store.addAll(anyList(), anyList())).thenReturn(List.of("seg-1"));

        service.submit(attachment(ATTACHMENT_ID, "text/plain"));
        service.submit(attachment(ATTACHMENT_ID, "text/plain"));

        verify(storage).read(any());
    }

    @Test
    @DisplayName("索引失败只记日志：它跑在上传成功之后，没有任何理由让上传跟着失败")
    void swallowsIndexingFailure() {
        storageReturns("正文");
        when(embeddingModel.embedAll(anyList())).thenThrow(new RuntimeException("embedding 服务不可用"));

        service.submit(attachment(ATTACHMENT_ID, "text/plain"));

        // 不抛异常即为通过；顺带确认它确实试着索引过
        verify(embeddingModel).embedAll(anyList());
    }

    @Test
    @DisplayName("内容全是空白就不写库，也不调 embedding")
    void skipsBlankContent() {
        storageReturns("   \n\n  ");

        service.submit(attachment(ATTACHMENT_ID, "text/plain"));

        verifyNoInteractions(embeddingModel);
        verify(store, never()).addAll(anyList(), anyList());
    }

    @Test
    @DisplayName("向量库到上限后不再索引新文档，只记一条 warn")
    void stopsIndexingOnceStoreIsFull() {
        DocumentIndexService tiny = serviceWith(
                new RagProperties(5, 0.7, 1200, 200, 1, Duration.ofSeconds(3)), Runnable::run);
        storageReturns("正文");
        modelReturnsOneEmbeddingPerSegment();
        when(store.addAll(anyList(), anyList())).thenReturn(List.of("seg-1"));

        tiny.submit(attachment(ATTACHMENT_ID, "text/plain"));
        tiny.submit(attachment(101L, "text/plain"));

        // 第一份占满了那 1 个名额，第二份连 embedding 都不该调
        verify(embeddingModel).embedAll(anyList());
    }

    // ---------------------------------------------------------------- 回收

    @Test
    @DisplayName("删除附件时按片段 id 精确回收，并释放配额")
    void forgetRemovesSegments() {
        storageReturns("正文");
        modelReturnsOneEmbeddingPerSegment();
        when(store.addAll(anyList(), anyList())).thenReturn(List.of("seg-1"));
        service.submit(attachment(ATTACHMENT_ID, "text/plain"));

        service.forget(ATTACHMENT_ID);

        verify(store).removeAll(List.of("seg-1"));
    }

    @Test
    @DisplayName("索引途中被删：刚写进去的片段要自己撤回，否则永久留在库里没人认领")
    void retractsSegmentsWhenDeletedMidIndexing() {
        storageReturns("正文");
        when(embeddingModel.embedAll(anyList())).thenAnswer(invocation -> {
            // 模拟「embedding 还在跑的时候，用户把这个附件删了」
            service.forget(ATTACHMENT_ID);
            List<TextSegment> segments = invocation.getArgument(0);
            return Response.from(segments.stream().map(segment -> embedding()).toList());
        });
        when(store.addAll(anyList(), anyList())).thenReturn(List.of("seg-1"));

        service.submit(attachment(ATTACHMENT_ID, "text/plain"));

        verify(store).removeAll(List.of("seg-1"));
    }

    @Test
    @DisplayName("排队期间就被删掉：连 embedding 都不必白跑一次")
    void skipsIndexingWhenDeletedBeforeItStarts() {
        service = serviceWith(defaultProperties(), queued::add);
        service.forget(ATTACHMENT_ID);

        service.submit(attachment(ATTACHMENT_ID, "text/plain"));
        queued.forEach(Runnable::run);

        verifyNoInteractions(embeddingModel, storage);
    }

    // ---------------------------------------------------------------- 检索

    @Test
    @DisplayName("没有可检索的文档、或提问为空时不调模型：空 id 列表传给 isIn 会直接抛")
    void skipsRetrievalWhenThereIsNothingToSearch() {
        assertThat(service.knowledgeFor("讲了什么", List.of())).isEmpty();
        assertThat(service.knowledgeFor("   ", List.of(ATTACHMENT_ID))).isEmpty();

        verifyNoInteractions(embeddingModel);
    }

    @Test
    @DisplayName("命中片段时带上来源文件名，并按会话内的附件过滤向量")
    void returnsKnowledgeWithSourceLabels() {
        when(embeddingModel.embed(any(TextSegment.class))).thenReturn(Response.from(embedding()));
        when(store.search(any(EmbeddingSearchRequest.class))).thenReturn(new EmbeddingSearchResult<>(
                List.of(new EmbeddingMatch<>(0.9, "seg-1", embedding(),
                        TextSegment.from("季度目标是营收翻倍", Metadata.from("fileName", "纪要.txt"))))));

        String knowledge = service.knowledgeFor("讲了什么", List.of(ATTACHMENT_ID, 101L));

        assertThat(knowledge).contains("纪要.txt").contains("季度目标是营收翻倍");

        ArgumentCaptor<EmbeddingSearchRequest> captor = ArgumentCaptor.forClass(EmbeddingSearchRequest.class);
        verify(store).search(captor.capture());
        assertThat(captor.getValue().maxResults()).isEqualTo(5);
        assertThat(captor.getValue().filter()).isInstanceOf(IsIn.class);
    }

    @Test
    @DisplayName("零命中但文档已就绪：如实说没检索到，别让模型凭常识硬答")
    void reportsNoMatchWhenDocumentsAreReady() {
        // 先把附件索引进去，才能与「还在排队」那种情况区分开
        storageReturns("正文");
        modelReturnsOneEmbeddingPerSegment();
        when(store.addAll(anyList(), anyList())).thenReturn(List.of("seg-1"));
        service.submit(attachment(ATTACHMENT_ID, "text/plain"));

        when(embeddingModel.embed(any(TextSegment.class))).thenReturn(Response.from(embedding()));
        when(store.search(any(EmbeddingSearchRequest.class)))
                .thenReturn(new EmbeddingSearchResult<>(List.of()));

        assertThat(service.knowledgeFor("讲了什么", List.of(ATTACHMENT_ID)))
                .isEqualTo(Prompt.KNOWLEDGE_EMPTY_HINT);
    }

    @Test
    @DisplayName("分数低于阈值就丢掉：宁可说没检索到，也不要把不相关的片段塞进提示词")
    void dropsMatchesBelowThreshold() {
        storageReturns("正文");
        modelReturnsOneEmbeddingPerSegment();
        when(store.addAll(anyList(), anyList())).thenReturn(List.of("seg-1"));
        service.submit(attachment(ATTACHMENT_ID, "text/plain"));

        when(embeddingModel.embed(any(TextSegment.class))).thenReturn(Response.from(embedding()));
        when(store.search(any(EmbeddingSearchRequest.class))).thenReturn(new EmbeddingSearchResult<>(
                List.of(new EmbeddingMatch<>(0.5, "seg-1", embedding(),
                        TextSegment.from("完全不相干的内容", Metadata.from("fileName", "纪要.txt"))))));

        // 0.5 换算成余弦是 0，低于默认阈值 0.7
        assertThat(service.knowledgeFor("讲了什么", List.of(ATTACHMENT_ID)))
                .isEqualTo(Prompt.KNOWLEDGE_EMPTY_HINT);
    }

    @Test
    @DisplayName("零命中且文件还排在索引队列里：明说在处理中，用户才知道稍后再问")
    void reportsPendingWhenDocumentsAreStillBeingIndexed() {
        DocumentIndexService deferring = serviceWith(defaultProperties(), queued::add);
        deferring.submit(attachment(ATTACHMENT_ID, "text/plain"));
        when(embeddingModel.embed(any(TextSegment.class))).thenReturn(Response.from(embedding()));
        when(store.search(any(EmbeddingSearchRequest.class)))
                .thenReturn(new EmbeddingSearchResult<>(List.of()));

        assertThat(deferring.knowledgeFor("讲了什么", List.of(ATTACHMENT_ID)))
                .isEqualTo(Prompt.KNOWLEDGE_PENDING_HINT);
    }
}
