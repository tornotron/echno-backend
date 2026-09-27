package org.tornotron.echno_backend.wbs;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * The schedule's idea of what day it is. A planned finish is a site date, so "has it passed" is
 * judged in the sites' zone, {@value #DEFAULT_ZONE} unless {@value #ZONE_PROPERTY} says
 * otherwise, and not in the JVM's zone, which is UTC in our containers.
 */
@Configuration(proxyBeanMethods = false)
public class WbsScheduleClockConfiguration {

    public static final String ZONE_PROPERTY = "echno.wbs.zone";
    public static final String DEFAULT_ZONE = "Asia/Kolkata";

    @Bean(defaultCandidate = false)
    @WbsScheduleClock
    public Clock wbsScheduleClock(@Value("${" + ZONE_PROPERTY + ":" + DEFAULT_ZONE + "}") String zone) {
        return Clock.system(ZoneId.of(zone));
    }
}
