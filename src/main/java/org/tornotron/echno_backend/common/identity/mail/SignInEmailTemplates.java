package org.tornotron.echno_backend.common.identity.mail;

import org.springframework.lang.Nullable;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.tornotron.echno_backend.common.identity.SignInEmails.Kind;
import org.tornotron.echno_backend.common.mail.MailMessage;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The three sign-in emails, each as HTML and as plain text, from the templates under
 * {@code src/main/resources/mail}. An engine of its own, so these templates are never reachable as a
 * web view and the application's view settings do not apply to them.
 *
 * <p>Every value is escaped in the HTML part, since an organization or person name is typed by a user.
 * Times are written in UTC, because the email cannot know the reader's zone.
 */
public class SignInEmailTemplates {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm 'UTC'", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    /**
     * What one email says.
     *
     * @param to           the recipient.
     * @param secret       the link, or for a verification the code.
     * @param expiresAt    when it stops working, if known.
     * @param organization the organization an invitation is to, if known.
     * @param inviter      who sent an invitation, if anyone did.
     */
    public record Values(String to, String secret, @Nullable Instant expiresAt, @Nullable String organization,
                         @Nullable String inviter) {

        @Override
        public String toString() {
            return "Values[to=" + to + "]";
        }
    }

    /** Spring's engine, so expressions are SpEL, as everywhere else in the application. */
    private final SpringTemplateEngine engine = new SpringTemplateEngine();
    private final String product;

    public SignInEmailTemplates(String product) {
        this.product = product;
        engine.addTemplateResolver(resolver(TemplateMode.HTML, "*.html", 1));
        engine.addTemplateResolver(resolver(TemplateMode.TEXT, "*.txt", 2));
    }

    public MailMessage render(Kind kind, Values values) {
        String name = switch (kind) {
            case INVITATION -> "invitation";
            case PASSWORD_RESET -> "password-reset";
            case EMAIL_VERIFICATION -> "email-verification";
        };
        String subject = subject(kind, values);
        Context context = new Context(Locale.ENGLISH);
        context.setVariable("product", product);
        context.setVariable("subject", subject);
        context.setVariable("email", values.to());
        context.setVariable("organization", blankToNull(values.organization()));
        context.setVariable("inviter", blankToNull(values.inviter()));
        context.setVariable("expires", values.expiresAt() == null ? null : WHEN.format(values.expiresAt()));
        context.setVariable("link", kind == Kind.EMAIL_VERIFICATION ? null : values.secret());
        context.setVariable("code", kind == Kind.EMAIL_VERIFICATION ? values.secret() : null);
        context.setVariable("footer", footer(kind));
        context.setVariable("body", name + ".html");
        String html = engine.process("layout.html", context);
        String text = engine.process(name + ".txt", context);
        return new MailMessage(values.to(), subject, html, text);
    }

    private String subject(Kind kind, Values values) {
        String organization = blankToNull(values.organization());
        return switch (kind) {
            case INVITATION -> organization == null
                    ? "You are invited to " + product
                    : "Join " + organization + " on " + product;
            case PASSWORD_RESET -> "Reset your " + product + " password";
            case EMAIL_VERIFICATION -> "Your " + product + " verification code";
        };
    }

    private static String footer(Kind kind) {
        return switch (kind) {
            case INVITATION -> "If you were not expecting an invitation, you can ignore this email.";
            case PASSWORD_RESET -> "If you did not ask for this, you can ignore this email and your password stays the same.";
            case EMAIL_VERIFICATION -> "If you did not try to sign in, you can ignore this email.";
        };
    }

    private static ClassLoaderTemplateResolver resolver(TemplateMode mode, String pattern, int order) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("mail/");
        resolver.setTemplateMode(mode);
        resolver.setResolvablePatterns(java.util.Set.of(pattern));
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resolver.setCacheable(true);
        resolver.setOrder(order);
        return resolver;
    }

    @Nullable
    private static String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
