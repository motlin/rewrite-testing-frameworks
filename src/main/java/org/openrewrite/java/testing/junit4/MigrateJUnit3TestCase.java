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
package org.openrewrite.java.testing.junit4;

import lombok.Getter;
import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaSourceFile;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Statement;

import java.nio.file.Path;
import java.util.HashSet;

public class MigrateJUnit3TestCase extends ScanningRecipe<JUnit3MigrationPlan> {

    @Getter
    final String displayName = "Migrate JUnit 3 `TestCase` classes to JUnit 4";

    @Getter
    final String description = "Convert test classes that extend `junit.framework.TestCase` to JUnit 4 annotations: " +
            "`test*` methods get `@Test`, `setUp` and `tearDown` get `@Before` and `@After`, `getName()` reads a `TestName` rule, " +
            "and the `suite()` method becomes `@BeforeClass`/`@AfterClass` or `@RunWith(Suite.class)` where it has an equivalent. " +
            "The `TestCase(String)` constructor is removed together with the test name its callers pass, across source files. " +
            "Assertions move to `org.junit.Assert`. " +
            "A class that would behave differently under JUnit 4, such as one that overrides `run(TestResult)`, " +
            "reports to a `TestResult`, passes its instances around as `Test`s or needs the test name, " +
            "is left as it is, and marked with the reason. JUnit 4 keeps running those through its JUnit 3 adapter.";

    @Override
    public JUnit3MigrationPlan getInitialValue(ExecutionContext ctx) {
        return new JUnit3MigrationPlan();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(JUnit3MigrationPlan plan) {
        TreeVisitor<?, ExecutionContext> testCaseScanner = Preconditions.check(
                Preconditions.or(
                        new UsesType<>("junit.framework.TestCase", false),
                        new UsesType<>("junit.framework.Test", false),
                        new UsesType<>("junit.framework.TestSuite", false),
                        new UsesType<>("junit.textui.TestRunner", false)),
                new JUnit3Scanner(plan));
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (tree instanceof J.CompilationUnit) {
                    J.CompilationUnit cu = (J.CompilationUnit) tree;
                    for (J.ClassDeclaration cd : cu.getClasses()) {
                        recordDeclarations(cd, cu.getSourcePath());
                    }
                    return testCaseScanner.visit(tree, ctx);
                }
                if (tree instanceof JavaSourceFile) {
                    // such as Kotlin, whose constructor calls are not rewritten
                    for (JavaType.Method method : ((JavaSourceFile) tree).getTypesInUse().getUsedMethods()) {
                        if (method.isConstructor()) {
                            plan.nonJavaConstructorCallerFqns.add(method.getDeclaringType().getFullyQualifiedName());
                        }
                    }
                }
                return tree;
            }

            private void recordDeclarations(J.ClassDeclaration cd, Path sourcePath) {
                if (cd.getType() != null) {
                    plan.sourcePathsByDeclaredFqn.computeIfAbsent(cd.getType().getFullyQualifiedName(), k -> new HashSet<>()).add(sourcePath);
                }
                for (Statement s : cd.getBody().getStatements()) {
                    if (s instanceof J.ClassDeclaration) {
                        recordDeclarations((J.ClassDeclaration) s, sourcePath);
                    }
                }
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(JUnit3MigrationPlan plan) {
        plan.resolve();
        // no UsesType: callers of a constructor that loses its test name need not use anything from JUnit
        return new JUnit3TestCaseVisitor(plan);
    }
}
