package org.tornotron.echno_backend.modules.inspections.web;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import org.tornotron.echno_backend.modules.inspections.api.IntakeRejectedException;
import org.tornotron.echno_backend.modules.inspections.dtos.IntakeBatchRequest;
import org.tornotron.echno_backend.modules.inspections.observation.IntakeRejectedCodes;

/**
 * Refuses a batch body over 2 MB before it is parsed. The declared length catches an honest client
 * at once; a chunked body declares none, so it is read up to the limit first.
 */
@ControllerAdvice(assignableTypes = ObservationIntakeController.class)
public class IntakeBatchBodyLimit extends RequestBodyAdviceAdapter {

    static final int MAX_BYTES = 2 * 1024 * 1024;

    @Override
    public boolean supports(MethodParameter parameter, Type targetType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return IntakeBatchRequest.class.equals(targetType);
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage message, MethodParameter parameter, Type targetType,
                                           Class<? extends HttpMessageConverter<?>> converterType) throws IOException {
        if (message.getHeaders().getContentLength() > MAX_BYTES) {
            throw tooLarge();
        }
        byte[] body = message.getBody().readNBytes(MAX_BYTES + 1);
        if (body.length > MAX_BYTES) {
            throw tooLarge();
        }
        return new HttpInputMessage() {
            @Override
            public InputStream getBody() {
                return new ByteArrayInputStream(body);
            }

            @Override
            public HttpHeaders getHeaders() {
                return message.getHeaders();
            }
        };
    }

    private static IntakeRejectedException tooLarge() {
        return new IntakeRejectedException(IntakeRejectedCodes.VALIDATION, "items", "A batch body is limited to 2 MB");
    }
}
