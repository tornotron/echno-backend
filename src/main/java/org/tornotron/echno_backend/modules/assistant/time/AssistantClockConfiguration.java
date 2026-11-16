package org.tornotron.echno_backend.modules.assistant.time;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.tornotron.echno_backend.modules.assistant.AssistantModuleEnabled;

/**
 * The module's one idea of what day it is. "This week" and "overdue" are judged on a site's
 * date, so they are read in the sites' zone, {@value #DEFAULT_ZONE} unless
 * {@value #ZONE_PROPERTY} says otherwise, and not in the JVM's zone, which is UTC in our
 * containers (#879 is the same mistake made in another module).
 *
 * <p>The clock is not a default candidate, so it reaches only the code that asks for
 * {@link AssistantClock} and never an unqualified {@code Clock} elsewhere.
 */
@Configuration(proxyBeanMethods = false)
@AssistantModuleEnabled
public class AssistantClockConfiguration {

    public static final String ZONE_PROPERTY = "echno.modules.assistant.zone";
    public static final String DEFAULT_ZONE = "Asia/Kolkata";
    public static final String ZONE_PLACEHOLDER = "${" + ZONE_PROPERTY + ":" + DEFAULT_ZONE + "}";

    @Bean(defaultCandidate = false)
    @AssistantClock
    public Clock assistantClock(@Value(ZONE_PLACEHOLDER) String zone) {
        return Clock.system(ZoneId.of(zone));
    }
}
