package io.github.seah1006.careconnect;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import io.github.seah1006.careconnect.common.tenancy.OrgOwned;
import jakarta.persistence.Entity;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.TenantId;
import org.junit.jupiter.api.Tag;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

@Tag("INV-TEN-002")
@AnalyzeClasses(packages = "io.github.seah1006.careconnect", importOptions =
        com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests.class)
class TenantArchitectureTest {
    @ArchTest
    static final ArchRule ownedEntitiesHaveTenantId = classes().that().implement(OrgOwned.class)
            .should(new ArchCondition<>("have a UUID orgId annotated with @TenantId") {
                @Override public void check(JavaClass type, ConditionEvents events) {
                    boolean valid = type.getAllFields().stream().anyMatch(f -> f.getName().equals("orgId")
                            && f.getRawType().isEquivalentTo(UUID.class) && f.isAnnotatedWith(TenantId.class));
                    events.add(new SimpleConditionEvent(type, valid, type.getName() + " requires @TenantId UUID orgId"));
                }
            });

    @ArchTest
    static final ArchRule entitiesAreExplicitlyClassified = classes().that().areAnnotatedWith(Entity.class)
            .should(new ArchCondition<>("be OrgOwned or a listed global identity entity") {
                private final Set<String> allowed = Set.of(
                        "io.github.seah1006.careconnect.identity.domain.Organization",
                        "io.github.seah1006.careconnect.identity.domain.StaffUser",
                        "io.github.seah1006.careconnect.identity.domain.RefreshToken");
                @Override public void check(JavaClass type, ConditionEvents events) {
                    events.add(new SimpleConditionEvent(type, type.isAssignableTo(OrgOwned.class)
                            || allowed.contains(type.getName()), type.getName() + " requires tenant classification"));
                }
            });
}
