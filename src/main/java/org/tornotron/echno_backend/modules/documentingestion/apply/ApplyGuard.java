package org.tornotron.echno_backend.modules.documentingestion.apply;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.context.expression.BeanFactoryResolver;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.expression.SecurityExpressionRoot;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Evaluates a target's {@code @PreAuthorize} expression for the signed-in caller, so applying a value
 * asks exactly what writing it through the target's own endpoint would ask. The same beans resolve
 * ({@code @access}), and the same roots ({@code hasRole}, {@code isAuthenticated}).
 */
@Component
@PreAuthorize("isAuthenticated()")
public class ApplyGuard {

    private final BeanFactory beanFactory;
    private final ExpressionParser parser = new SpelExpressionParser();

    public ApplyGuard(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    public void require(String expression) {
        Authentication authentication = requireSignedIn();
        StandardEvaluationContext context = new StandardEvaluationContext(new SecurityExpressionRoot(authentication) {
        });
        context.setBeanResolver(new BeanFactoryResolver(beanFactory));
        Boolean allowed = parser.parseExpression(expression).getValue(context, Boolean.class);
        if (!Boolean.TRUE.equals(allowed)) {
            throw new AccessDeniedException("Your role cannot write to this record type");
        }
    }

    /** Apply runs only in a signed-in person's request, never in a job. */
    public static Authentication requireSignedIn() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new AuthenticationCredentialsNotFoundException("Applying values needs a signed-in person");
        }
        return authentication;
    }
}
