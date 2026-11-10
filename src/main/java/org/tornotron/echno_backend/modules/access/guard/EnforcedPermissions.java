package org.tornotron.echno_backend.modules.access.guard;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.ChecksAccess;

/**
 * The permissions that mean something today, derived at boot from the {@code @access.can} guards
 * the application's beans carry and the {@link ChecksAccess} lists of those that check one in code. A permission no guard checks would do nothing if granted, so the
 * catalog marks it as not enforced and the screen offers no choice for it.
 *
 * <p>Reading annotations never stops the application: a guard this cannot read is logged and
 * counts as not enforced, which is the safe answer, because a permission marked that way is only
 * not offered on the screen. {@code AccessGuardLiteralTest} fails the build on a malformed guard.
 */
@Component
@Slf4j
public class EnforcedPermissions implements SmartInitializingSingleton {

    private static final String APPLICATION_ROOT = "org.tornotron.echno_backend.";

    private final ListableBeanFactory beans;
    private volatile Set<AccessPermission> enforced = Set.of();

    public EnforcedPermissions(ListableBeanFactory beans) {
        this.beans = beans;
    }

    @Override
    public void afterSingletonsInstantiated() {
        Set<AccessPermission> found = new LinkedHashSet<>();
        for (String name : beans.getBeanDefinitionNames()) {
            Class<?> type = beans.getType(name, false);
            if (type == null) {
                continue;
            }
            Class<?> userClass = ClassUtils.getUserClass(type);
            if (!userClass.getName().startsWith(APPLICATION_ROOT)) {
                continue;
            }
            try {
                found.addAll(declaredOn(userClass));
                expressionsOn(userClass).forEach(expression -> {
                    AccessGuardExpressions.Parsed parsed = AccessGuardExpressions.parse(expression);
                    found.addAll(parsed.permissions());
                    parsed.malformed().forEach(call -> log.error("Malformed guard on {}: {}", userClass.getName(), call));
                });
            } catch (RuntimeException | LinkageError e) {
                log.warn("Could not read the guards on {}", userClass.getName(), e);
            }
        }
        enforced = Set.copyOf(found);
    }

    /** Whether some {@code @access.can} guard checks this permission. */
    public boolean isEnforced(AccessPermission permission) {
        return enforced.contains(permission);
    }

    /** Every permission some {@code @access.can} guard checks. */
    public Set<AccessPermission> all() {
        return enforced;
    }

    /** The permissions a class checks in its own code, as its {@link ChecksAccess} lists them. */
    static Set<AccessPermission> declaredOn(Class<?> type) {
        ChecksAccess checks = AnnotatedElementUtils.findMergedAnnotation(type, ChecksAccess.class);
        if (checks == null) {
            return Set.of();
        }
        Set<AccessPermission> permissions = new LinkedHashSet<>();
        for (String key : checks.value()) {
            permissions.add(AccessPermission.of(key));
        }
        return permissions;
    }

    /** The {@code @PreAuthorize} expressions on a class and on its methods. */
    static List<String> expressionsOn(Class<?> type) {
        List<String> expressions = new ArrayList<>();
        PreAuthorize onClass = AnnotatedElementUtils.findMergedAnnotation(type, PreAuthorize.class);
        if (onClass != null) {
            expressions.add(onClass.value());
        }
        for (Method method : ReflectionUtils.getUniqueDeclaredMethods(type)) {
            PreAuthorize onMethod = AnnotatedElementUtils.findMergedAnnotation(method, PreAuthorize.class);
            if (onMethod != null) {
                expressions.add(onMethod.value());
            }
        }
        return expressions;
    }
}
