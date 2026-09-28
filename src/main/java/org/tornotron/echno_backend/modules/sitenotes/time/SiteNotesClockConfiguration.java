package org.tornotron.echno_backend.modules.sitenotes.time;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The module's one idea of what day it is.
 *
 * <p>A note's date and the reminder's "yesterday" are site dates, so both are read in the sites'
 * zone, {@value #DEFAULT_ZONE} unless {@value #ZONE_PROPERTY} says otherwise. Reading them with
 * {@code LocalDate.now()} uses the JVM's zone, which is UTC in our containers: between 00:00 and
 * 05:30 IST a note dated tomorrow would be judged against the UTC date and refused, and the
 * service and the reminder could disagree about which day it was. This mirrors the fix made for
 * the same bug in Toolbox Talks (#879); see {@code ToolboxTalksClockConfiguration}. The reminder's
 * cron is evaluated in the same zone, through {@link #ZONE_PLACEHOLDER}.
 *
 * <p>The clock is not a default candidate, so it is injected only where {@link SiteNotesClock}
 * asks for it and never into an unqualified {@code Clock} elsewhere.
 */
@Configuration(proxyBeanMethods = false)
public class SiteNotesClockConfiguration {

    public static final String ZONE_PROPERTY = "echno.modules.site-notes.zone";
    public static final String DEFAULT_ZONE = "Asia/Kolkata";
    public static final String ZONE_PLACEHOLDER = "${" + ZONE_PROPERTY + ":" + DEFAULT_ZONE + "}";

    @Bean(defaultCandidate = false)
    @SiteNotesClock
    public Clock siteNotesClock(@Value(ZONE_PLACEHOLDER) String zone) {
        return Clock.system(ZoneId.of(zone));
    }
}
