package io.github.yanivks_pro.durastep;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.members;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMember;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.HashSet;
import java.util.Set;

@AnalyzeClasses(
    packages = "io.github.yanivks_pro.durastep",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  static final DescribedPredicate<JavaClass> INTERNAL =
      resideInAnyPackage(
          "..store..", "..worker..", "..runtime..", "..activity..", "..payload..", "..history..");

  @ArchTest
  static final ArchRule coreDoesNotDependOnSpring =
      noClasses().should().dependOnClassesThat().resideInAPackage("org.springframework..");

  @ArchTest
  static final ArchRule runtimeIsPure =
      noClasses()
          .that()
          .resideInAPackage("..runtime..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("java.sql..", "javax.sql..", "..store..", "..worker..");

  @ArchTest
  static final ArchRule thePublicApiNeverExposesInternalTypes =
      members()
          .that()
          .arePublic()
          .and()
          .areDeclaredInClassesThat()
          .arePublic()
          .and()
          .areDeclaredInClassesThat()
          .resideInAPackage("..api..")
          .should(onlyUseApiTypesInTheirSignature());

  static ArchCondition<JavaMember> onlyUseApiTypesInTheirSignature() {
    return new ArchCondition<>("use no internal types in their signature") {
      @Override
      public void check(JavaMember member, ConditionEvents events) {
        Set<JavaClass> used = new HashSet<>();
        if (member instanceof JavaField field) {
          used.addAll(field.getType().getAllInvolvedRawTypes());
        } else if (member instanceof JavaCodeUnit code) {
          used.addAll(code.getReturnType().getAllInvolvedRawTypes());
          code.getParameterTypes().forEach(type -> used.addAll(type.getAllInvolvedRawTypes()));
        }
        used.stream()
            .filter(INTERNAL)
            .forEach(
                type ->
                    events.add(
                        SimpleConditionEvent.violated(
                            member,
                            member.getFullName()
                                + " exposes the internal type "
                                + type.getName())));
      }
    };
  }
}
