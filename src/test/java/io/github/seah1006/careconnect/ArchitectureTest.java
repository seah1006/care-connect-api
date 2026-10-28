package io.github.seah1006.careconnect;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Tag;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@Tag("INV-ARCH-001")
@AnalyzeClasses(packages = "io.github.seah1006.careconnect", importOptions =
        com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
    @ArchTest
    static final ArchRule INV_ARCH_001_web_does_not_access_repositories = noClasses()
            .that().resideInAPackage("..web..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..repository..", "org.springframework.data.repository..")
            .allowEmptyShould(true);
}
