package org.tornotron.echno_backend.modules.assistant.ingest;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.modules.assistant.AssistantModuleEnabled;
import org.tornotron.echno_backend.modules.assistant.AssistantProperties;

/**
 * Ensures that the {@link Chunker} bean definition specifies {@link AssistantProperties}
 * as its constructor argument so that Spring always selects {@link Chunker#Chunker(AssistantProperties)}
 * and never falls back to looking for a default no-argument constructor.
 */
@Component
@AssistantModuleEnabled
public class ChunkerBeanFactoryPostProcessor implements BeanFactoryPostProcessor {

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        if (beanFactory.containsBeanDefinition("chunker")) {
            BeanDefinition bd = beanFactory.getBeanDefinition("chunker");
            ConstructorArgumentValues cav = bd.getConstructorArgumentValues();
            if (cav == null || cav.isEmpty()) {
                bd.getConstructorArgumentValues().addIndexedArgumentValue(0, new RuntimeBeanReference(AssistantProperties.class));
            }
        }
    }
}
