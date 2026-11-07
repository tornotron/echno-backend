package org.tornotron.echno_backend.modules.access;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.regex.Pattern;
import org.hibernate.annotations.Immutable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;

/**
 * {@code access_change_log} only ever gets an insert: its repository has no inherited delete or
 * batch write and declares nothing that changes a row, the entity is immutable to Hibernate, and
 * no query anywhere in the application updates or deletes it.
 */
@AnalyzeClasses(
        packages = "org.tornotron.echno_backend",
        importOptions = ImportOption.DoNotIncludeTests.class)
class AccessChangeLogAppendOnlyTest {

    private static final String REPOSITORY = "AccessChangeLogRepository";

    private static final Pattern WRITES_THE_LOG = Pattern.compile(
            "(?is).*\\b(update|delete(\\s+from)?)\\s+(AccessChangeLog|access_change_log)\\b.*");

    @ArchTest
    static final ArchRule theRepositoryInheritsNoDeleteOrBatchWrite = noClasses()
            .that().haveSimpleName(REPOSITORY)
            .should().beAssignableTo(CrudRepository.class);

    @ArchTest
    static final ArchRule theRepositoryDeclaresOnlySaveAndReads = methods()
            .that().areDeclaredInClassesThat().haveSimpleName(REPOSITORY)
            .should().haveNameMatching("save|find.*")
            .andShould().notBeAnnotatedWith(Modifying.class);

    @ArchTest
    static final ArchRule theEntityIsImmutableToHibernate = classes()
            .that().haveSimpleName("AccessChangeLog")
            .should().beAnnotatedWith(Immutable.class);

    @ArchTest
    static final ArchRule noQueryUpdatesOrDeletesTheLog = noMethods()
            .that().areAnnotatedWith(Query.class)
            .should(new ArchCondition<JavaMethod>("update or delete access_change_log") {
                @Override
                public void check(JavaMethod method, ConditionEvents events) {
                    String sql = method.getAnnotationOfType(Query.class).value();
                    events.add(new SimpleConditionEvent(method, WRITES_THE_LOG.matcher(sql).matches(),
                            method.getFullName() + " runs " + sql));
                }
            });
}
