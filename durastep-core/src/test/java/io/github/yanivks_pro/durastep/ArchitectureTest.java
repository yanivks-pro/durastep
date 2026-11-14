package io.github.yanivks_pro.durastep;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
    packages = "io.github.yanivks_pro.durastep",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

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
}
