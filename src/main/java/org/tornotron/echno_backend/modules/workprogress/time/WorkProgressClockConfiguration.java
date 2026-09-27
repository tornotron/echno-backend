package org.tornotron.echno_backend.modules.workprogress.time;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The module's one idea of what day it is. An inspection date and a planned finish are site
 * dates, so "not in the future" and "has the finish passed" are judged in the sites' zone,
 * {@value #DEFAULT_ZONE} unless {@value #ZONE_PROPERTY} says otherwise, and never in the JVM's
 * zone, which is UTC in our containers (the #879 lesson).
 */
@Configuration(proxyBeanMethods = false)
public class WorkProgressClockConfiguration {

    public static final String ZONE_PROPERTY = "echno.modules.work-progress.zone";
    public static final String DEFAULT_ZONE = "Asia/Kolkata";

    @Bean(defaultCandidate = false)
    @WorkProgressClock
    public Clock workProgressClock(@Value("${" + ZONE_PROPERTY + ":" + DEFAULT_ZONE + "}") String zone) {
        return Clock.system(ZoneId.of(zone));
    }
}
