package com.webchat.controller;

import com.webchat.common.Result;
import com.webchat.dto.ConversationVO;
import com.webchat.dto.MessageVO;
import com.webchat.service.ConversationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "会话")
@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;

    @Operation(summary = "会话列表", description = "当前用户的全部会话，按最后活跃时间倒序，不分页、不含消息正文")
    @GetMapping
    public Result<List<ConversationVO>> list() {
        return Result.ok(conversationService.list());
    }

    @Operation(summary = "新建会话", description = "创建空白会话，标题为默认值")
    @PostMapping
    public Result<ConversationVO> create() {
        return Result.ok(conversationService.create());
    }

    @Operation(summary = "删除会话", description = "连同其全部消息一并删除。幂等，重复删除不报错")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        conversationService.delete(id);
        return Result.ok();
    }

    @Operation(summary = "会话消息列表", description = "按发送顺序返回该会话的全部消息")
    @GetMapping("/{id}/messages")
    public Result<List<MessageVO>> messages(@PathVariable Long id) {
        return Result.ok(conversationService.listMessages(id));
    }
}
