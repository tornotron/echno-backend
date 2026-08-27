package org.tornotron.echno_backend.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.chat.dto.SendMessageDto;
import org.tornotron.echno_backend.chat.realtime.ChatStreamService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The multipart send takes its payload as the JSON string part of the request and deserializes
 * it by hand, so the {@code @NotBlank} on {@link SendMessageDto#getContent()} never fired there,
 * while the endpoint documented a 400 for blank content. The JSON send beside it binds a
 * {@code @Valid @RequestBody} and has always been checked; these pin that the two now agree.
 */
@ExtendWith(MockitoExtension.class)
class ChatMessageValidationTest {

    private static ValidatorFactory factory;

    @Mock
    private ChatService chatService;
    @Mock
    private ChatStreamService chatStreamService;

    private ChatControllerWeb controller;

    @BeforeEach
    void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        Validator validator = factory.getValidator();
        controller = new ChatControllerWeb(chatService, new ObjectMapper(), chatStreamService,
                validator);
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) {
            factory.close();
        }
    }

    @Test
    void sendMessageWithAttachments_rejectsBlankContent_withoutSendingAnything() throws Exception {
        assertThatThrownBy(() -> controller.sendMessageWithAttachments(
                7L, "{\"content\":\"   \"}", List.of()))
                .isInstanceOf(ConstraintViolationException.class);

        verify(chatService, never()).sendMessage(ArgumentMatchers.anyLong(),
                ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    void sendMessageWithAttachments_rejectsAbsentContent() throws Exception {
        assertThatThrownBy(() -> controller.sendMessageWithAttachments(
                7L, "{\"replyToId\":1198}", List.of()))
                .isInstanceOf(ConstraintViolationException.class);

        verify(chatService, never()).sendMessage(ArgumentMatchers.anyLong(),
                ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    void sendMessageWithAttachments_passesRealContentThrough() throws Exception {
        controller.sendMessageWithAttachments(
                7L, "{\"content\":\"Concrete pour on block C is done.\",\"replyToId\":1198}",
                List.of());

        verify(chatService).sendMessage(7L, "Concrete pour on block C is done.", 1198L, List.of());
    }
}
