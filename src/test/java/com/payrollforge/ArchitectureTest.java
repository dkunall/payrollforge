package com.payrollforge;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

@AnalyzeClasses(packages = "com.payrollforge", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule domainIsPlainJava = noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta..", "java.sql..",
                    "com.fasterxml.jackson..", "..application..", "..adapter..", "software.amazon..", "com.lowagie..");

    @ArchTest
    static final ArchRule applicationDoesNotReachIntoAdapters = noClasses().that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAPackage("..adapter..");

    @ArchTest
    static final ArchRule applicationDoesNotTouchInfrastructure = noClasses().that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..",
                    "org.springframework.web..", "java.sql..", "org.springframework.kafka..", "software.amazon..",
                    "com.lowagie..");

    /** A module's core sees only itself and shared plumbing; other modules are reached through its own ports. */
    @ArchTest
    static final ArchRule payrollCoreIsSelfContained = moduleCore("payroll");
    @ArchTest
    static final ArchRule leaveCoreIsSelfContained = moduleCore("leave");
    @ArchTest
    static final ArchRule attendanceCoreIsSelfContained = moduleCore("attendance");
    @ArchTest
    static final ArchRule reimbursementCoreIsSelfContained = moduleCore("reimbursement");
    @ArchTest
    static final ArchRule employeeCoreIsSelfContained = moduleCore("employee");
    @ArchTest
    static final ArchRule reportsCoreIsSelfContained = moduleCore("reports");

    private static ArchRule moduleCore(String m) {
        return classes().that().resideInAnyPackage("com.payrollforge." + m + ".domain..",
                        "com.payrollforge." + m + ".application..")
                .should().onlyDependOnClassesThat().resideInAnyPackage("com.payrollforge." + m + "..",
                        "com.payrollforge.shared..", "com.payrollforge.outbox.application..",
                        "com.payrollforge.org.application..", "com.payrollforge.org.domain..",
                        "com.payrollforge.workflow.application..", "java..", "org.springframework..",
                        "io.micrometer..", "org.slf4j..", "jakarta..", "com.fasterxml.jackson..", "")
                .because("cross-module calls go through the caller's own port and an adapter");
    }

    /** Bank and PAN plaintext only exist inside the employee module and the payroll adapter that writes bank files. */
    @ArchTest
    static final ArchRule cipherStaysInEmployee = noClasses().that().resideOutsideOfPackages(
                    "com.payrollforge.employee..", "com.payrollforge.shared..")
            .should().dependOnClassesThat().haveSimpleName("FieldCipher");

    @ArchTest
    static final ArchRule modulesHaveNoCycles = slices().matching("com.payrollforge.(*).application..")
            .should().beFreeOfCycles();

    @ArchTest
    static final ArchRule controllersLiveInAdapters = classes().that()
            .areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
            .should().resideInAPackage("..adapter..").andShould().notBePublic();
}
