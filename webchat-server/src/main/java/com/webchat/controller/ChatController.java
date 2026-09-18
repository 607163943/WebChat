package com.webchat.controller;

import com.webchat.ai.ChatContext;
import com.webchat.ai.ChatEvent;
import com.webchat.ai.ChatStreamService;
import com.webchat.dto.SendMessageRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@Tag(name = "聊天")
@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
public class ChatController {

    private final ChatStreamService chatStreamService;

    @Operation(summary = "发送消息（SSE 流式）", description = """
            返回 text/event-stream，不套 Result 信封。
            事件协议：若干 delta → done →（仅新会话）title；生成失败则以 error 结束且本次回复不落库。
            每条事件都必须带 event 名，事件体均为 JSON。""")
    @ApiResponse(responseCode = "200", description = "SSE 事件流",
            content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE))
    @PostMapping("/{id}/messages")
    public Flux<ServerSentEvent<Object>> send(@PathVariable Long id,
                                              @RequestBody SendMessageRequest request) {
        ChatContext context = chatStreamService.prepare(id, request.content());
        return toSseStream(context);
    }

    @Operation(summary = "重新生成（SSE 流式）", description = """
            删除该会话最后一条助手回复，用其前面那条用户消息重新生成——用户消息不会重复插入。
            事件协议与「发送消息」完全一致。""")
    @ApiResponse(responseCode = "200", description = "SSE 事件流",
            content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE))
    @PostMapping("/{id}/regenerate")
    public Flux<ServerSentEvent<Object>> regenerate(@PathVariable Long id) {
        ChatContext context = chatStreamService.prepareRegenerate(id);
        return toSseStream(context);
    }

    private Flux<ServerSentEvent<Object>> toSseStream(ChatContext context) {
        return chatStreamService.stream(context).map(ChatController::toServerSentEvent);
    }

    /**
     * 事件名必须逐个显式指定。
     *
     * <p>前端所用客户端在 {@code event:} 字段缺失时会把事件类型置为<b>空字符串</b>
     * （而不是 SSE 规范里的 {@code "message"}），只写 data 会让前端分不出 delta 与 done。
     */
    private static ServerSentEvent<Object> toServerSentEvent(ChatEvent event) {
        return switch (event) {
            case ChatEvent.Delta delta -> ServerSentEvent.builder()
                    .event("delta")
                    .data(new DeltaPayload(delta.content()))
                    .build();
            case ChatEvent.Done done -> ServerSentEvent.builder()
                    .event("done")
                    .data(new DonePayload(done.messageId()))
                    .build();
            case ChatEvent.Title title -> ServerSentEvent.builder()
                    .event("title")
                    .data(new TitlePayload(title.title()))
                    .build();
            case ChatEvent.Failed failed -> ServerSentEvent.builder()
                    .event("error")
                    .data(new ErrorPayload(failed.message()))
                    .build();
        };
    }

    record DeltaPayload(String content) {
    }

    record DonePayload(Long messageId) {
    }

    record TitlePayload(String title) {
    }

    record ErrorPayload(String message) {
    }
}
