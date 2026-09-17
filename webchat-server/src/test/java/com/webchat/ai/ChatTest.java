package com.webchat.ai;

import dev.langchain4j.community.model.dashscope.QwenChatModel;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class ChatTest {
    @Autowired
    private QwenChatModel chatModel;

    @Test
    void testChat() {
        UserMessage userMessage = UserMessage.from("你好");
        ChatResponse chatResponse = chatModel.chat(userMessage);
        String text = chatResponse.aiMessage().text();
        System.out.println("AI回复：" + text);
    }
}
