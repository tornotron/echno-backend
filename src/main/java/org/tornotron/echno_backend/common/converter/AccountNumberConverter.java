package org.tornotron.echno_backend.common.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.configuration.EncryptionService;

@Converter(autoApply = false)
@Component
public class AccountNumberConverter implements AttributeConverter<String, String> {

    private static EncryptionService encryptionService;

    public void setEncryptionService(EncryptionService encryptionService) {
        AccountNumberConverter.encryptionService = encryptionService;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if(attribute == null) return null;
        return encryptionService.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if(dbData == null) return null;
        return encryptionService.decrypt(dbData);
    }
}

