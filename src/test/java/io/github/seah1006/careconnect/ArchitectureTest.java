package io.github.seah1006.careconnect;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.*;
import io.github.seah1006.careconnect.common.authz.AccessPolicy;
import io.github.seah1006.careconnect.common.authz.CurrentActor;
import java.util.Set;
import org.junit.jupiter.api.Tag;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

@Tag("INV-ARCH-001")
@Tag("INV-ARCH-002")
@AnalyzeClasses(packages = "io.github.seah1006.careconnect", importOptions =
        com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
    @ArchTest
    static final ArchRule INV_ARCH_002_servicesAuditParticipantAccess = classes().that().resideInAPackage("..application..")
            .should(new ArchCondition<>("depend on AccessLog when using participant repositories") {
                @Override public void check(JavaClass type, ConditionEvents events) {
                    var dependencies = type.getDirectDependenciesFromSelf();
                    boolean repository = dependencies.stream().anyMatch(d -> d.getTargetClass().getPackageName()
                            .startsWith("io.github.seah1006.careconnect.participant.repository"));
                    boolean audit = dependencies.stream().anyMatch(d -> d.getTargetClass()
                            .isEquivalentTo(io.github.seah1006.careconnect.common.audit.AccessLog.class));
                    events.add(new SimpleConditionEvent(type, !repository || audit, type.getName() + " requires AccessLog"));
                }
            });

    @ArchTest
    static final ArchRule INV_ARCH_002_webDoesNotAudit = noClasses().that().resideInAPackage("..web..")
            .should().dependOnClassesThat().resideInAPackage("..common.audit..");

    @ArchTest
    static final ArchRule webDoesNotMakeAuthorizationDecisions = noClasses().that().resideInAPackage("..web..")
            .should().dependOnClassesThat().belongToAnyOf(AccessPolicy.class, CurrentActor.class);

    @ArchTest
    static final ArchRule authorizationIsExplicitInServices = classes().that().resideInAPackage("..application..")
            .should(new ArchCondition<>("use AccessPolicy when accessing participant repositories") {
                @Override public void check(JavaClass type, ConditionEvents events) {
                    var dependencies = type.getDirectDependenciesFromSelf();
                    boolean repository = dependencies.stream().anyMatch(d -> d.getTargetClass().getPackageName()
                            .startsWith("io.github.seah1006.careconnect.participant.repository"));
                    boolean policy = dependencies.stream().anyMatch(d -> d.getTargetClass().isEquivalentTo(AccessPolicy.class));
                    events.add(new SimpleConditionEvent(type, !repository || policy,
                            type.getName() + " must depend on AccessPolicy"));
                }
            });

    @ArchTest
    static final ArchRule noSecurityAnnotations = classes().should(new ArchCondition<>("avoid security annotations") {
        private final Set<String> forbidden = Set.of(
                "org.springframework.security.access.prepost.PreAuthorize",
                "org.springframework.security.access.prepost.PostAuthorize",
                "org.springframework.security.access.annotation.Secured",
                "jakarta.annotation.security.RolesAllowed");
        @Override public void check(JavaClass type, ConditionEvents events) {
            boolean invalid = type.getAnnotations().stream().anyMatch(a -> forbidden.contains(a.getRawType().getName()))
                    || type.getMethods().stream().flatMap(m -> m.getAnnotations().stream())
                            .anyMatch(a -> forbidden.contains(a.getRawType().getName()));
            events.add(new SimpleConditionEvent(type, !invalid, type.getName() + " has a forbidden security annotation"));
        }
    });

    @ArchTest
    static final ArchRule INV_ARCH_001_web_does_not_access_repositories = noClasses()
            .that().resideInAPackage("..web..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..repository..", "org.springframework.data.repository..")
            .allowEmptyShould(true);
}
