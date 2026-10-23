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
import org.openrewrite.ExecutionContext;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.SearchResult;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class IsNotRunByJUnit4Launcher extends ScanningRecipe<Set<String>> {

    static final List<MethodMatcher> LAUNCHER_METHODS = Arrays.asList(
            new MethodMatcher("org.junit.runner.JUnitCore run(..)"),
            new MethodMatcher("org.junit.runner.JUnitCore runClasses(..)"),
            new MethodMatcher("org.junit.runner.Request aClass(..)"),
            new MethodMatcher("org.junit.runner.Request method(..)"),
            new MethodMatcher("org.junit.runner.Request classes(..)"));

    @Getter
    final String displayName = "Is not run by a JUnit 4 launcher";

    @Getter
    final String description = "Searches for the sources that do not declare a class passed as a class literal to " +
            "`JUnitCore#run(..)`, `JUnitCore#runClasses(..)`, `Request#aClass(..)`, `Request#method(..)` or " +
            "`Request#classes(..)`. Meant as a precondition: JUnit 4 finds no tests in such a class once it is " +
            "migrated to JUnit Jupiter, so it silently stops running. Classes launched from shell scripts or " +
            "other non-Java launchers are not detected.";

    @Override
    public Set<String> getInitialValue(ExecutionContext ctx) {
        return new HashSet<>();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Set<String> launched) {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
                if (LAUNCHER_METHODS.stream().anyMatch(matcher -> matcher.matches(m))) {
                    for (Expression argument : m.getArguments()) {
                        new JavaIsoVisitor<Set<String>>() {
                            @Override
                            public J.FieldAccess visitFieldAccess(J.FieldAccess fieldAccess, Set<String> classes) {
                                JavaType.FullyQualified type = TypeUtils.asFullyQualified(fieldAccess.getTarget().getType());
                                if ("class".equals(fieldAccess.getSimpleName()) && type != null) {
                                    classes.add(type.getFullyQualifiedName());
                                }
                                return super.visitFieldAccess(fieldAccess, classes);
                            }
                        }.reduce(argument, launched);
                    }
                }
                return m;
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Set<String> launched) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree preVisit(Tree tree, ExecutionContext ctx) {
                stopAfterPreVisit();
                return tree instanceof J.CompilationUnit && declaresAny((J.CompilationUnit) tree, launched) ?
                        tree : SearchResult.found(tree);
            }
        };
    }

    private static boolean declaresAny(J.CompilationUnit cu, Set<String> launched) {
        return !launched.isEmpty() && new JavaIsoVisitor<AtomicBoolean>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, AtomicBoolean declares) {
                if (classDecl.getType() != null && launched.contains(classDecl.getType().getFullyQualifiedName())) {
                    declares.set(true);
                }
                return super.visitClassDeclaration(classDecl, declares);
            }
        }.reduce(cu, new AtomicBoolean()).get();
    }
}
