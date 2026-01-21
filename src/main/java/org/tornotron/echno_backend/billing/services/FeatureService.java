package org.tornotron.echno_backend.billing.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.billing.Feature;
import org.tornotron.echno_backend.billing.dto.FeatureCreateDto;
import org.tornotron.echno_backend.billing.enums.FeatureType;
import org.tornotron.echno_backend.billing.repositories.FeatureRepository;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class FeatureService {

    private final FeatureRepository featureRepository;

    public List<Feature> getAllActiveFeatures() {
        return featureRepository.findByIsActiveTrueOrderByCategory();
    }

    public List<Feature> getAllFeatures() {
        return featureRepository.findAll();
    }

    public Feature getFeatureById(Long id) {
        return featureRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Feature not found with id: " + id));
    }

    public Feature getFeatureByCode(String code) {
        return featureRepository.findByCodeAndIsActiveTrue(code)
                .orElseThrow(() -> new IllegalArgumentException("Feature not found with code: " + code));
    }

    @Transactional
    public Feature createFeature(FeatureCreateDto dto) {
        Feature feature = Feature.builder()
                .code(dto.getCode())
                .name(dto.getName())
                .description(dto.getDescription())
                .featureType(FeatureType.valueOf(dto.getFeatureType()))
                .category(dto.getCategory())
                .build();

        feature = featureRepository.save(feature);
        log.info("Created feature: {}", feature.getCode());
        return feature;
    }

    @Transactional
    public Feature updateFeature(Long id, FeatureCreateDto dto) {
        Feature feature = getFeatureById(id);

        feature.setCode(dto.getCode());
        feature.setName(dto.getName());
        feature.setDescription(dto.getDescription());
        feature.setFeatureType(FeatureType.valueOf(dto.getFeatureType()));
        feature.setCategory(dto.getCategory());

        feature = featureRepository.save(feature);
        log.info("Updated feature: {}", feature.getCode());
        return feature;
    }

    @Transactional
    public void deactivateFeature(Long id) {
        Feature feature = getFeatureById(id);
        feature.setIsActive(false);
        featureRepository.save(feature);
        log.info("Deactivated feature: {}", feature.getCode());
    }

    @Transactional
    public void activateFeature(Long id) {
        Feature feature = featureRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Feature not found with id: " + id));
        feature.setIsActive(true);
        featureRepository.save(feature);
        log.info("Activated feature: {}", feature.getCode());
    }
}
