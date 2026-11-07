package org.tornotron.echno_backend.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.modules.documentingestion.gate.DocumentIngestionGate;

/** A controller of the module without the gate would answer while the module is meant to be off. */
@AnalyzeClasses(
        packages = "org.tornotron.echno_backend",
        importOptions = ImportOption.DoNotIncludeTests.class)
class DocumentIngestionGateRuleTest {

    @ArchTest
    static final ArchRule everyControllerOfTheModuleSitsBehindTheGate = classes()
            .that().resideInAPackage("org.tornotron.echno_backend.modules.documentingestion..")
            .and().areAnnotatedWith(RestController.class)
            .should().beAnnotatedWith(DocumentIngestionGate.class);
}
