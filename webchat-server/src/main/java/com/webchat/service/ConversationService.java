package com.webchat.service;

import com.webchat.dto.ConversationVO;
import com.webchat.dto.MessageVO;
import com.webchat.entity.Conversation;

import java.util.List;

public interface ConversationService {

    /** 新建会话时的默认标题；该会话首条用户消息发出后，会被模型生成的标题替换 */
    String DEFAULT_TITLE = "新对话";

    /** 当前用户的全部会话，按最后活跃时间倒序。不分页。 */
    List<ConversationVO> list();

    /** 新建空白会话，返回带 id 的完整行 */
    ConversationVO create();

    /** 删除会话及其全部消息。幂等：会话不存在或不属于当前用户都当作已删除。 */
    void delete(Long id);

    /** 会话的全部消息，按 id 升序（即发送顺序） */
    List<MessageVO> listMessages(Long id);

    /** 取出会话并校验归属，不存在或不属于当前用户时抛 404 */
    Conversation requireOwned(Long id);
}
