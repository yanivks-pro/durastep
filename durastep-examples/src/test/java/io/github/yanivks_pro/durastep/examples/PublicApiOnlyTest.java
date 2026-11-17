package io.github.yanivks_pro.durastep.examples;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class PublicApiOnlyTest {

  @Test
  void examplesUseOnlyThePublicApi() {
    JavaClasses examples =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.github.yanivks_pro.durastep.examples");

    noClasses()
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "io.github.yanivks_pro.durastep.runtime..",
            "io.github.yanivks_pro.durastep.store..",
            "io.github.yanivks_pro.durastep.worker..",
            "io.github.yanivks_pro.durastep.activity..",
            "io.github.yanivks_pro.durastep.payload..")
        .check(examples);
  }
}
