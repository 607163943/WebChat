package com.webchat.storage;

/**
 * 附件字节的存放处。
 *
 * <p>抽成接口是因为「日后换对象存储」是这个功能的既定预期（换存储时 {@code object_key}
 * 就是迁移的抓手），而不是为了现在就有多种实现——实现只有 {@link LocalAttachmentStorage} 一个，
 * 形态与 {@code CurrentUserProvider} / {@code FixedCurrentUserProvider} 那对一致。
 */
public interface AttachmentStorage {

    /** 按对象键写入内容，同键重复写入视为覆盖 */
    void store(String objectKey, byte[] content);

    /**
     * 按对象键读取内容。
     *
     * @throws AttachmentStorageException 对象不存在或读不出来
     */
    byte[] read(String objectKey);

    /**
     * 按对象键删除内容。<b>幂等</b>：对象本来就不存在也算成功。
     *
     * <p>这条约定是清理任务能收敛的前提——「删对象成功、删行前进程挂掉」或对象被运维手工删掉，
     * 都会让下一次删除落到「文件不存在」上；若把它当失败，retry_count 会一路涨到阈值，
     * 然后每轮扫描都告警，而那条行永远清不掉。
     *
     * @throws AttachmentStorageException 真正的 IO 失败，交由调用方累加 retry_count
     */
    void delete(String objectKey);
}
