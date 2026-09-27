package com.webchat.ai;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话记忆的存放处：进程内、按会话 id 分槽，与框架自带的 {@code InMemoryChatMemoryStore} 同构。
 *
 * <p>它<b>不是</b>持久层——真源始终是 {@code tb_message}，{@link ChatStreamService} 在每轮
 * {@code prepare} 时用库里的历史覆盖整个槽位（{@link #seed}），所以槽位里的内容只在「一轮之内」有意义：
 * 模型看到的上下文＝库里记着的东西，重启、切会话、重新生成、中断都不会让两者分叉。
 *
 * <p>既然每轮都覆盖，为什么不干脆用默认的内存实现？因为要有一个地方能<b>主动写入</b>：
 * 框架只在调用时读记忆，没有任何口子能让调用方塞历史进去。
 *
 * <p>返回的列表是可变的、且就是槽位里那一份（与框架自带实现一致）：{@code MessageWindowChatMemory.add}
 * 拿到它之后原地追加、再 {@code updateMessages} 写回。
 */
@Component
public class ConversationMemoryStore implements ChatMemoryStore {

    private final Map<Object, List<ChatMessage>> messagesByMemoryId = new ConcurrentHashMap<>();

    /**
     * 用这一轮的上下文覆盖整个槽位。
     *
     * <p>放进去的是<b>已经渲染好的</b>消息（含历史附件说明与按预算挑好的媒体），
     * 不是库里的行——渲染按轮进行，见 {@link ChatMessageAssembler#renderMemory}。
     */
    public void seed(Object memoryId, List<ChatMessage> messages) {
        messagesByMemoryId.put(memoryId, new ArrayList<>(messages));
    }

    /** 会话被删除时调用，免得它的历史留在堆里 */
    @Override
    public void deleteMessages(Object memoryId) {
        messagesByMemoryId.remove(memoryId);
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        return messagesByMemoryId.computeIfAbsent(memoryId, id -> new ArrayList<>());
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        messagesByMemoryId.put(memoryId, new ArrayList<>(messages));
    }
}
