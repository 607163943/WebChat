package com.webchat.ai.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 文档检索相关的可调项，集中在 application.yaml 的 {@code webchat.rag} 段。
 *
 * <p>与 {@code AttachmentProperties} 一样是 record、不参与组件扫描，必须在
 * {@link RagConfig} 上显式 {@code @EnableConfigurationProperties}，否则注入它的地方会以
 * {@code NoSuchBeanDefinitionException} 启动失败。
 */
@ConfigurationProperties(prefix = "webchat.rag")
public record RagProperties(

        /** 一次提问最多注入几个片段 */
        @DefaultValue("5") int maxResults,

        /**
         * 相似度下限。<b>注意它不是余弦值</b>：{@code InMemoryEmbeddingStore} 过滤用的是
         * {@code RelevanceScore.fromCosineSimilarity(c) = (c + 1) / 2}，也就是把余弦从 [-1, 1]
         * 线性映射到 [0, 1]。所以这里的 0.7 实际对应余弦 0.4。
         *
         * <p>按余弦的直觉填 0.5 是个陷阱——那等于「余弦 ≥ 0」，几乎什么都放行，既会把不相关的片段
         * 塞进提示词，也会让「有文档但没检索到」那条分支永远走不到。
         */
        @DefaultValue("0.7") double minScore,

        /**
         * 单个片段的字符数上限（不是 token）。切分按「段落 → 行 → 句子 → 词 → 字符」逐级下探，
         * 所以没有换行的超长单行不会产生巨型片段。
         *
         * <p>取得比常见的 500~800 大：本模型上下文有 128K token，800 字符的片段远低于上限，
         * 只会让调用次数变多、语义变碎。1200 字符在中文约合 1800 token，检索精度与调用成本比较平衡。
         */
        @DefaultValue("1200") int chunkSize,

        /** 相邻片段的重叠字符数。按段尾的完整句子回退取，取不到整句时重叠可能为 0 */
        @DefaultValue("200") int chunkOverlap,

        /**
         * 单个文档切分后允许的最大片段数。
         *
         * <p>与 {@code maxTotalSegments} 是两回事：那条是<b>全库共享</b>的预算，撞上它说明库已被
         * 别人的文件占满，跟当前这个文件大不大没关系。这条才对应「你这个文件太大」——
         * 它必须在切分之后按本文件的片段数判，否则归因就是错的。
         *
         * <p><b>按当前参数，它是一道还没轮到上场的闸</b>：切分步长实测是 {@code chunkSize} 本身
         * （1200 字符，重叠只在句子级回退时才生效），而文本单文件上限是 1MB，于是
         * 1MB 纯 ASCII（约 105 万字符）最多切出约 874 段、1MB 中文约 350 段，都够不到 1000。
         * 它挡的是日后放宽 {@code max-text-file-size} 或调小 {@code chunk-size} 的情况——
         * 没有它，一个超大文件能一口气吃掉全库预算，而报表上会显示成「库满了」。
         */
        @DefaultValue("1000") int maxSegmentsPerDocument,

        /**
         * 整个向量库的片段总数上限，纯内存方案的兜底闸门。
         *
         * <p>每段含 1024 维向量（约 4KB）与原文，10000 段约合 60MB 堆。超过之后新文档不再索引
         * （只记一条 warn），否则长时间跑下来堆内存只增不减。
         */
        @DefaultValue("10000") int maxTotalSegments,

        /**
         * 单次检索的硬超时。
         *
         * <p>检索跑在回复的关键路径上（要先拿到片段才能组装请求），而 SSE 协议里没有心跳事件——
         * embedding 接口抖几秒，用户那边就是「一个事件都收不到」的假死。超时就当作没检索到，
         * 让对话照常进行。
         */
        @DefaultValue("3s") Duration retrievalTimeout) {
}
