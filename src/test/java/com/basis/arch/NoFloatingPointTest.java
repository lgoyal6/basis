package com.basis.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Set;

/**
 * Binary floating point is banned everywhere in basis.
 *
 * <p>A ledger that ever touches {@code double} has already lost: 0.1 + 0.2 is not 0.3,
 * so a position built from fractional shares stops reconciling against the broker for
 * reasons no one can find. Money is integer minor units and quantities are
 * {@link java.math.BigDecimal}, and this rule is what keeps it that way.
 *
 * <p>The rule covers more than declarations. It also fails a call to any method that
 * takes or returns a double or a float, which is what catches the genuinely dangerous
 * cases: {@code BigDecimal.valueOf(double)}, {@code BigDecimal.doubleValue()} and
 * {@code Math.random()} would each pass a signature-only check while quietly
 * introducing binary rounding into a cost basis.
 *
 * <p>Widened from the domain to every package once reconciliation arrived. Deciding
 * whether two share counts differ by exactly 4 to 1 is precisely the kind of arithmetic
 * that looks fine in floating point until a position is large enough for it not to be.
 */
@AnalyzeClasses(packages = "com.basis", importOptions = ImportOption.DoNotIncludeTests.class)
class NoFloatingPointTest {

    private static final Set<String> BANNED = Set.of(
            "double", "float", "double[]", "float[]",
            "java.lang.Double", "java.lang.Float",
            "java.util.OptionalDouble", "java.util.stream.DoubleStream");

    @ArchTest
    static final ArchRule no_class_declares_a_double_or_float =
            classes().should(new ArchCondition<>("declare no double or float") {
                @Override
                public void check(JavaClass clazz, ConditionEvents events) {
                    for (JavaField field : clazz.getFields()) {
                        reportIfBanned(events, field.getRawType().getName(),
                                "field " + field.getFullName());
                    }
                    for (JavaMethod method : clazz.getMethods()) {
                        reportIfBanned(events, method.getRawReturnType().getName(),
                                "return type of " + method.getFullName());
                        reportParameters(events, method);
                    }
                    for (JavaCodeUnit constructor : clazz.getConstructors()) {
                        reportParameters(events, constructor);
                    }
                    events.add(SimpleConditionEvent.satisfied(clazz,
                            clazz.getName() + " declares no double or float"));
                }
            });

    @ArchTest
    static final ArchRule no_class_calls_floating_point_arithmetic =
            classes().should(new ArchCondition<>("never call a method that takes or returns double or float") {
                @Override
                public void check(JavaClass clazz, ConditionEvents events) {
                    for (JavaMethodCall call : clazz.getMethodCallsFromSelf()) {
                        reportIfBanned(events, call.getTarget().getRawReturnType().getName(),
                                "call to " + call.getTarget().getFullName() + " returning");
                        for (JavaClass parameter : call.getTarget().getRawParameterTypes()) {
                            reportIfBanned(events, parameter.getName(),
                                    "call to " + call.getTarget().getFullName() + " taking");
                        }
                    }
                    for (JavaConstructorCall call : clazz.getConstructorCallsFromSelf()) {
                        for (JavaClass parameter : call.getTarget().getRawParameterTypes()) {
                            reportIfBanned(events, parameter.getName(),
                                    "call to " + call.getTarget().getFullName() + " taking");
                        }
                    }
                    events.add(SimpleConditionEvent.satisfied(clazz,
                            clazz.getName() + " calls no floating point arithmetic"));
                }
            });

    private static void reportParameters(ConditionEvents events, JavaCodeUnit codeUnit) {
        for (JavaClass parameter : codeUnit.getRawParameterTypes()) {
            reportIfBanned(events, parameter.getName(), "parameter of " + codeUnit.getFullName());
        }
    }

    private static void reportIfBanned(ConditionEvents events, String typeName, String where) {
        if (BANNED.contains(typeName)) {
            events.add(SimpleConditionEvent.violated(where,
                    where + " uses banned floating point type " + typeName));
        }
    }
}
