/*
 * Copyright 2024 the original author or authors.
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

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.RemoveAnnotationVisitor;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.SearchResult;

import java.util.Arrays;
import java.util.List;

import static java.util.Collections.emptyList;

@Value
@EqualsAndHashCode(callSuper = false)
public class EnclosedToNested extends Recipe {
    static final String ENCLOSED = "org.junit.experimental.runners.Enclosed";
    static final String MANUAL_MIGRATION_MESSAGE = "Neither @Nested nor independent classes preserve both the per-test and the class-level setup of this Enclosed class; migrate it manually";
    private static final String RUN_WITH = "org.junit.runner.RunWith";
    private static final String RUN_WITH_ENCLOSED = String.format("@%s(%s.class)", RUN_WITH, ENCLOSED);
    private static final AnnotationMatcher RUN_WITH_ENCLOSED_MATCHER = new AnnotationMatcher(RUN_WITH_ENCLOSED);
    private static final List<String> PER_TEST_LIFECYCLE_ANNOTATIONS = Arrays.asList(
            "org.junit.Before",
            "org.junit.After",
            "org.junit.jupiter.api.BeforeEach",
            "org.junit.jupiter.api.AfterEach");
    private static final List<String> CLASS_LEVEL_LIFECYCLE_ANNOTATIONS = Arrays.asList(
            "org.junit.BeforeClass",
            "org.junit.AfterClass",
            "org.junit.ClassRule",
            "org.junit.jupiter.api.BeforeAll",
            "org.junit.jupiter.api.AfterAll");
    private static final List<String> PER_INSTANCE_MEMBER_ANNOTATIONS = Arrays.asList(
            "org.junit.Rule",
            "org.junit.jupiter.api.io.TempDir",
            "org.junit.jupiter.api.extension.RegisterExtension");

    String displayName = "JUnit 4 `@RunWith(Enclosed.class)` to JUnit Jupiter `@Nested`";

    String description = "Replaces the `Enclosed` runner with `@Nested` on its inner test classes, preserving independent static test classes.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(new UsesType<>(ENCLOSED, false), new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);
                if (!isEnclosed(cd)) {
                    return cd;
                }
                if (isUnmigratable(cd)) {
                    return SearchResult.found(cd, MANUAL_MIGRATION_MESSAGE);
                }
                boolean hasPerTestSetup = hasPerTestSetup(cd);
                maybeRemoveImport(ENCLOSED);
                maybeRemoveImport(RUN_WITH);
                cd = (J.ClassDeclaration) new RemoveAnnotationVisitor(RUN_WITH_ENCLOSED_MATCHER)
                        .visitNonNull(cd, ctx, getCursor().getParentTreeCursor());
                if (hasPerTestSetup) {
                    // The Enclosed runner never applied the outer class's per-test lifecycle methods or
                    // instance rules to the inner classes' tests, but @Nested classes would inherit them.
                    return cd;
                }
                cd = cd.withBody((J.Block) new AddMissingNested.AddNestedAnnotationVisitor()
                        .visitNonNull(cd.getBody(), ctx, updateCursor(cd)));
                maybeAddImport("org.junit.jupiter.api.Nested");
                return cd;
            }
        });
    }

    static boolean isEnclosed(J.ClassDeclaration cd) {
        return cd.getLeadingAnnotations().stream().anyMatch(RUN_WITH_ENCLOSED_MATCHER::matches);
    }

    /**
     * An Enclosed class with both per-test and class-level setup cannot be migrated: {@code @Nested} classes would
     * inherit the per-test setup, while independent classes would lose the class-level setup that the Enclosed suite
     * ran around them.
     */
    static boolean isUnmigratable(J.ClassDeclaration cd) {
        return isEnclosed(cd) && hasPerTestSetup(cd) && hasClassLevelLifecycleMember(cd);
    }

    private static boolean hasPerTestSetup(J.ClassDeclaration cd) {
        return hasPerTestLifecycleMethod(cd) || hasInstanceRule(cd);
    }

    private static boolean hasPerTestLifecycleMethod(J.ClassDeclaration cd) {
        return cd.getBody().getStatements().stream()
                .filter(J.MethodDeclaration.class::isInstance)
                .map(J.MethodDeclaration.class::cast)
                .flatMap(method -> method.getLeadingAnnotations().stream())
                .anyMatch(annotation -> PER_TEST_LIFECYCLE_ANNOTATIONS.stream()
                        .anyMatch(type -> TypeUtils.isOfClassType(annotation.getType(), type)));
    }

    private static boolean hasClassLevelLifecycleMember(J.ClassDeclaration cd) {
        return cd.getBody().getStatements().stream()
                .flatMap(statement -> memberAnnotations(statement).stream())
                .anyMatch(annotation -> CLASS_LEVEL_LIFECYCLE_ANNOTATIONS.stream()
                        .anyMatch(type -> TypeUtils.isOfClassType(annotation.getType(), type)));
    }

    private static boolean hasInstanceRule(J.ClassDeclaration cd) {
        return cd.getBody().getStatements().stream()
                .flatMap(statement -> instanceMemberAnnotations(statement).stream())
                .anyMatch(annotation -> PER_INSTANCE_MEMBER_ANNOTATIONS.stream()
                        .anyMatch(type -> TypeUtils.isOfClassType(annotation.getType(), type)));
    }

    private static List<J.Annotation> memberAnnotations(Statement statement) {
        if (statement instanceof J.VariableDeclarations) {
            return ((J.VariableDeclarations) statement).getLeadingAnnotations();
        }
        if (statement instanceof J.MethodDeclaration) {
            return ((J.MethodDeclaration) statement).getLeadingAnnotations();
        }
        return emptyList();
    }

    private static List<J.Annotation> instanceMemberAnnotations(Statement statement) {
        if (statement instanceof J.VariableDeclarations) {
            J.VariableDeclarations field = (J.VariableDeclarations) statement;
            return field.hasModifier(J.Modifier.Type.Static) ? emptyList() : field.getLeadingAnnotations();
        }
        if (statement instanceof J.MethodDeclaration) {
            J.MethodDeclaration method = (J.MethodDeclaration) statement;
            return method.hasModifier(J.Modifier.Type.Static) ? emptyList() : method.getLeadingAnnotations();
        }
        return emptyList();
    }
}
