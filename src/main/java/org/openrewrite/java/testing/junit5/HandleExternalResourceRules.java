/*
 * Copyright 2025 the original author or authors.
 * <p>
 * Licensed under the Moderne Source Available License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://docs.moderne.io/licensing/moderne-source-available-license
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.java.testing.junit5;

import lombok.Getter;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.service.AnnotationService;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.lang.String.format;
import static java.util.Comparator.comparing;

// Run this last in a declarative recipe list, so it only handles the ExternalResource rules no other recipe migrated.
// Classes that were already JUnit Jupiter tests before the migration are skipped: Jupiter never ran their rules,
// so enabling them would change behavior.
public class HandleExternalResourceRules extends ScanningRecipe<Set<String>> {

    private static final AnnotationMatcher ANY_RULE_ANNOTATION_MATCHER = new AnnotationMatcher("@org.junit.*Rule", true);
    private static final String CLASS_RULE = "org.junit.ClassRule";
    private static final String EXTERNAL_RESOURCE_RULE = "org.junit.rules.ExternalResource";
    private static final String EXTERNAL_RESOURCE_SUPPORT = "org.junit.jupiter.migrationsupport.rules.ExternalResourceSupport";
    private static final String EXTEND_WITH = "org.junit.jupiter.api.extension.ExtendWith";
    private static final AnnotationMatcher EXTEND_WITH_ANNOTATION_MATCHER = new AnnotationMatcher(format("@%s(%s.class)", EXTEND_WITH, EXTERNAL_RESOURCE_SUPPORT), true);
    private static final String JUNIT4_TEST = "org.junit.Test";
    private static final String JUPITER_PACKAGE_PREFIX = "org.junit.jupiter.";
    private static final String RULE = "org.junit.Rule";

    @Getter
    final String displayName = "Handle the usage of `ExternalResource` rule fields using `@ExtendWith(ExternalResourceSupport.class)`";

    @Getter
    final String description = "Handles the usage of `ExternalResource` rule fields by adding the `@ExtendWith(ExternalResourceSupport.class)` annotation to the test class.";

    @Override
    public Set<String> getInitialValue(ExecutionContext ctx) {
        return new HashSet<>();
    }

    /**
     * Records classes that use JUnit Jupiter annotations and no JUnit 4 {@code @Test}, before any migration step runs.
     */
    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Set<String> alreadyJupiter) {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                if (classDecl.getType() != null && usesOnlyJupiter(classDecl)) {
                    alreadyJupiter.add(classDecl.getType().getFullyQualifiedName());
                }
                return super.visitClassDeclaration(classDecl, ctx);
            }
        };
    }

    private static boolean usesOnlyJupiter(J.ClassDeclaration classDecl) {
        boolean jupiter = false;
        for (Statement statement : classDecl.getBody().getStatements()) {
            if (!(statement instanceof J.MethodDeclaration)) {
                continue;
            }
            for (J.Annotation annotation : ((J.MethodDeclaration) statement).getLeadingAnnotations()) {
                JavaType.FullyQualified type = TypeUtils.asFullyQualified(annotation.getType());
                if (type == null) {
                    continue;
                }
                if (JUNIT4_TEST.equals(type.getFullyQualifiedName())) {
                    return false;
                }
                if (type.getFullyQualifiedName().startsWith(JUPITER_PACKAGE_PREFIX)) {
                    jupiter = true;
                }
            }
        }
        return jupiter;
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Set<String> alreadyJupiter) {
        return Preconditions.check(
                Preconditions.or(new UsesType<>(CLASS_RULE, true), new UsesType<>(RULE, true)),
                new JavaIsoVisitor<ExecutionContext>() {
                    @Override
                    public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                        J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);

                        if (cd.getType() != null && alreadyJupiter.contains(cd.getType().getFullyQualifiedName())) {
                            return cd;
                        }

                        if (!new ExternalResourceRuleScanner()
                                .reduce(cd, new AtomicBoolean(), getCursor().getParentOrThrow())
                                .get()) {
                            return cd;
                        }

                        // If the class has no @ExtendWith(ExternalResourceSupport.class) annotation, add one
                        if (!service(AnnotationService.class).matches(updateCursor(cd), EXTEND_WITH_ANNOTATION_MATCHER)) {
                            maybeAddImport(EXTERNAL_RESOURCE_SUPPORT);
                            maybeAddImport(EXTEND_WITH);
                            return JavaTemplate.builder("@ExtendWith(ExternalResourceSupport.class)")
                                    .imports(EXTERNAL_RESOURCE_SUPPORT, EXTEND_WITH)
                                    .javaParser(JavaParser.fromJavaVersion()
                                            .classpathFromResources(ctx, "junit-jupiter-migrationsupport-5", "junit-jupiter-api-5"))
                                    .build()
                                    .apply(updateCursor(cd), cd.getCoordinates().addAnnotation(comparing(J.Annotation::getSimpleName)));
                        }

                        return cd;
                    }
                });
    }

    // Checks for existence of ExternalResourceRule
    private static class ExternalResourceRuleScanner extends JavaIsoVisitor<AtomicBoolean> {

        @Override
        public J.VariableDeclarations visitVariableDeclarations(
                J.VariableDeclarations variableDeclarations, AtomicBoolean hasExternalResourceRule) {
            if (!service(AnnotationService.class).matches(getCursor(), ANY_RULE_ANNOTATION_MATCHER)) {
                return variableDeclarations;
            }
            return super.visitVariableDeclarations(variableDeclarations, hasExternalResourceRule);
        }

        @Override
        public J.VariableDeclarations.NamedVariable visitVariable(
                J.VariableDeclarations.NamedVariable variable, AtomicBoolean hasExternalResourceRule) {
            if (variable.getInitializer() != null &&
                    TypeUtils.isAssignableTo(EXTERNAL_RESOURCE_RULE, variable.getInitializer().getType())) {
                hasExternalResourceRule.set(true);
            }
            return variable;
        }

        @Override
        public J.MethodDeclaration visitMethodDeclaration(
                J.MethodDeclaration methodDeclaration, AtomicBoolean hasExternalResourceRule) {
            if (service(AnnotationService.class).matches(getCursor(), ANY_RULE_ANNOTATION_MATCHER) &&
                    methodDeclaration.getMethodType() != null &&
                    TypeUtils.isAssignableTo(EXTERNAL_RESOURCE_RULE, methodDeclaration.getMethodType().getReturnType())) {
                hasExternalResourceRule.set(true);
            }
            return methodDeclaration;
        }
    }
}
