package com.webchat.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.webchat.common.BizException;
import com.webchat.common.ResultCode;
import com.webchat.config.CurrentUserProvider;
import com.webchat.dto.AttachmentVO;
import com.webchat.dto.ConversationVO;
import com.webchat.dto.MessageVO;
import com.webchat.entity.Conversation;
import com.webchat.entity.Message;
import com.webchat.mapper.ConversationMapper;
import com.webchat.mapper.MessageMapper;
import com.webchat.service.AttachmentService;
import com.webchat.service.ConversationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ConversationServiceImpl implements ConversationService {

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final AttachmentService attachmentService;
    private final CurrentUserProvider currentUserProvider;

    @Override
    public List<ConversationVO> list() {
        long userId = currentUserProvider.userId();
        List<Conversation> rows = conversationMapper.selectList(
                Wrappers.<Conversation>lambdaQuery()
                        .eq(Conversation::getUserId, userId)
                        .orderByDesc(Conversation::getUpdateTime)
                        // 同一秒内更新的会话靠 id 兜底，保证列表顺序稳定
                        .orderByDesc(Conversation::getId));
        return rows.stream().map(ConversationServiceImpl::toVO).toList();
    }

    @Override
    public ConversationVO create() {
        Conversation conversation = new Conversation();
        conversation.setUserId(currentUserProvider.userId());
        conversation.setTitle(DEFAULT_TITLE);
        conversationMapper.insert(conversation);

        // create_time / update_time 由数据库默认值填充，插入后回查一次才有值
        return toVO(conversationMapper.selectById(conversation.getId()));
    }

    @Override
    @Transactional
    public void delete(Long id) {
        long userId = currentUserProvider.userId();
        // 先判归属再动手：消息的删除条件只有 conversation_id，
        // 若不先确认会话属于当前用户，就可能删掉别人的消息。
        Conversation owned = conversationMapper.selectOne(
                Wrappers.<Conversation>lambdaQuery()
                        .eq(Conversation::getId, id)
                        .eq(Conversation::getUserId, userId));
        if (owned == null) {
            // 幂等：不存在或不属于当前用户，都当作已删除，不报错
            return;
        }
        messageMapper.delete(Wrappers.<Message>lambdaQuery().eq(Message::getConversationId, id));
        conversationMapper.deleteById(id);
    }

    @Override
    public List<MessageVO> listMessages(Long id) {
        requireOwned(id);
        List<Message> rows = messageMapper.selectList(
                Wrappers.<Message>lambdaQuery()
                        .eq(Message::getConversationId, id)
                        .orderByAsc(Message::getId));
        // 一次批量查回全部附件再按 message_id 分组，避免每条消息查一次。
        // 少了这一步，「只发附件不打字」的消息重新拉取时就只剩一个带内边距的空气泡
        Map<Long, List<AttachmentVO>> attachments = attachmentService.listByMessageIds(
                rows.stream().map(Message::getId).toList());
        return rows.stream()
                .map(m -> new MessageVO(m.getId(), m.getRole(), m.getContent(), m.getCreateTime(),
                        attachments.getOrDefault(m.getId(), List.of())))
                .toList();
    }

    @Override
    public Conversation requireOwned(Long id) {
        Conversation conversation = conversationMapper.selectOne(
                Wrappers.<Conversation>lambdaQuery()
                        .eq(Conversation::getId, id)
                        .eq(Conversation::getUserId, currentUserProvider.userId()));
        if (conversation == null) {
            throw new BizException(ResultCode.NOT_FOUND);
        }
        return conversation;
    }

    private static ConversationVO toVO(Conversation conversation) {
        return new ConversationVO(conversation.getId(), conversation.getTitle(), conversation.getUpdateTime());
    }
}
