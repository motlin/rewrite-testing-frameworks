/*
 * Copyright 2026 the original author or authors.
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
import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.SearchResult;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class FindLeftoverJUnit4Rules extends Recipe {

    private static final AnnotationMatcher RULE = new AnnotationMatcher("@org.junit.Rule");
    private static final AnnotationMatcher CLASS_RULE = new AnnotationMatcher("@org.junit.ClassRule");
    private static final AnnotationMatcher JUPITER_TEST = new AnnotationMatcher("@org.junit.jupiter.api.Test");

    private static final String MIGRATION_SUPPORT = "org.junit.jupiter.migrationsupport.rules.";
    private static final String ENABLE_RULE_MIGRATION_SUPPORT = "org.junit.jupiter.migrationsupport.rules.EnableRuleMigrationSupport";

    /**
     * Each migration support extension, and the rule type whose rules it still runs.
     */
    private static final Map<String, String> SUPPORTED_RULES = new HashMap<>();

    static {
        SUPPORTED_RULES.put(MIGRATION_SUPPORT + "ExternalResourceSupport", "org.junit.rules.ExternalResource");
        SUPPORTED_RULES.put(MIGRATION_SUPPORT + "VerifierSupport", "org.junit.rules.Verifier");
        SUPPORTED_RULES.put(MIGRATION_SUPPORT + "ExpectedExceptionSupport", "org.junit.rules.ExpectedException");
    }

    @Getter
    final String displayName = "Find JUnit 4 rules left behind after migrating to JUnit Jupiter";

    @Getter
    final String description = "Marks `@Rule` and `@ClassRule` fields and methods that remain in classes using " +
            "JUnit Jupiter's `@Test`. JUnit Jupiter ignores these rules, so they silently stop running " +
            "and must be migrated to extensions manually. " +
            "Rules still run by a `junit-jupiter-migrationsupport` extension on the class are not marked.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(
                Preconditions.or(new UsesType<>("org.junit.Rule", false), new UsesType<>("org.junit.ClassRule", false)),
                new JavaIsoVisitor<ExecutionContext>() {
                    @Override
                    public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                        J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);
                        if (!hasJupiterTest(cd.getBody().getStatements())) {
                            return cd;
                        }
                        Set<String> supported = supportedRuleTypes(cd.getLeadingAnnotations());
                        return cd.withBody(cd.getBody().withStatements(ListUtils.map(cd.getBody().getStatements(), statement -> {
                            if (statement instanceof J.VariableDeclarations) {
                                J.VariableDeclarations vd = (J.VariableDeclarations) statement;
                                return isLeftoverRule(vd.getLeadingAnnotations(), vd.getType(), supported) ? mark(vd, vd.getType()) : vd;
                            }
                            if (statement instanceof J.MethodDeclaration) {
                                J.MethodDeclaration md = (J.MethodDeclaration) statement;
                                JavaType returnType = md.getMethodType() == null ? null : md.getMethodType().getReturnType();
                                return isLeftoverRule(md.getLeadingAnnotations(), returnType, supported) ? mark(md, returnType) : md;
                            }
                            return statement;
                        })));
                    }
                });
    }

    private static boolean hasJupiterTest(List<Statement> statements) {
        for (Statement statement : statements) {
            if (statement instanceof J.MethodDeclaration &&
                    ((J.MethodDeclaration) statement).getLeadingAnnotations().stream().anyMatch(JUPITER_TEST::matches)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The rule types that a migration support extension on the class still runs.
     */
    private static Set<String> supportedRuleTypes(List<J.Annotation> classAnnotations) {
        Set<String> supported = new HashSet<>();
        for (J.Annotation annotation : classAnnotations) {
            if (TypeUtils.isOfClassType(annotation.getType(), ENABLE_RULE_MIGRATION_SUPPORT)) {
                supported.addAll(SUPPORTED_RULES.values());
            }
            new JavaIsoVisitor<Set<String>>() {
                @Override
                public J.Identifier visitIdentifier(J.Identifier identifier, Set<String> s) {
                    JavaType.FullyQualified fq = TypeUtils.asFullyQualified(identifier.getType());
                    if (fq != null && SUPPORTED_RULES.containsKey(fq.getFullyQualifiedName())) {
                        s.add(SUPPORTED_RULES.get(fq.getFullyQualifiedName()));
                    }
                    return identifier;
                }
            }.visit(annotation, supported);
        }
        return supported;
    }

    private static boolean isLeftoverRule(List<J.Annotation> annotations, @Nullable JavaType ruleType, Set<String> supported) {
        if (annotations.stream().noneMatch(a -> RULE.matches(a) || CLASS_RULE.matches(a))) {
            return false;
        }
        for (String supportedType : supported) {
            if (TypeUtils.isAssignableTo(supportedType, ruleType)) {
                return false;
            }
        }
        return true;
    }

    private static <T extends J> T mark(T tree, @Nullable JavaType ruleType) {
        JavaType.FullyQualified fq = TypeUtils.asFullyQualified(ruleType);
        String name = fq == null ? "rule" : "rule `" + fq.getClassName() + "`";
        return SearchResult.found(tree, "JUnit Jupiter does not run JUnit 4 " + name + "; migrate it to an extension manually");
    }
}
