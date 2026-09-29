package com.webchat.ai.rag;

import java.util.Locale;

/**
 * 一个附件在<b>当前进程</b>里的索引状态，是「这个文件的内容现在能不能被检索到」的唯一依据。
 *
 * <p>状态刻意不落库：{@link #READY} 的含义是「向量库里有它的片段」，而向量库是纯内存的，
 * 落库的 ready 会在重启后立刻变成一句谎话。所以进程重启后所有附件一律回到
 * {@link #UNAVAILABLE}，前端与提示词都按「本进程没见过它」处理。
 *
 * <p>失败<b>原因</b>（片段过多、队列已满、向量库预算耗尽、embedding 异常）不在这里，
 * 也不进对外状态：它们只写日志。对用户来说结果都是「上传失败」，对外区分它们没有意义，
 * 而日志里必须分得清——归因写错了，排查时就只能靠猜。
 *
 * <p>{@link #NOT_INDEXED} 与 {@link #UNAVAILABLE} 是两件事，不要合并：前者是图片／视频
 * （本来就不进向量库，永远如此），后者是文本附件但本进程没有它的记录（重启，重新上传即可恢复）。
 */
public enum IndexState {

    /** 已提交，排队中或正在索引。只有它表示「稍后再问可能就有了」 */
    PENDING,

    /** 索引完成，片段在向量库里 */
    READY,

    /** 空文件（解码后全是空白）：不进向量库，也不会再有变化 */
    EMPTY,

    /** 处理失败：切分出的片段过多、索引队列已满、向量库预算耗尽，或 embedding 调用抛异常 */
    FAILED,

    /** 本进程没有它的记录——上传发生在上一次启动。纯内存向量库重启即清空 */
    UNAVAILABLE,

    /** 图片／视频：内容随多模态消息整块发给模型，压根不进向量库 */
    NOT_INDEXED;

    /**
     * 对外的名字。JSON 里用小写下划线，与 {@code tb_message.status} 那套（completed / interrupted /
     * failed）保持一致，前端拿到的是一眼能读懂的字面量。
     */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
