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
import org.openrewrite.java.marker.JavaProject;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.marker.SearchResult;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static java.util.Collections.emptySet;
import static org.openrewrite.java.testing.junit5.HasNoUnmigratableEnclosedClass.declaresUnmigratableEnclosedClass;
import static org.openrewrite.java.testing.junit5.IsNotRunByJUnit4Launcher.collectLaunchedClasses;
import static org.openrewrite.java.testing.junit5.IsNotRunByJUnit4Launcher.isLauncherCall;

public class ModuleKeepsNoJUnit4Classes extends ScanningRecipe<ModuleKeepsNoJUnit4Classes.Accumulator> {

    @Getter
    final String displayName = "Module keeps no class on JUnit 4";

    @Getter
    final String description = "Searches for the sources of modules where the JUnit 4 to JUnit Jupiter migration leaves " +
            "no class on JUnit 4. A module keeps JUnit 4 classes when it calls `JUnitCore#run(..)`, " +
            "`JUnitCore#runClasses(..)`, `Request#aClass(..)`, `Request#method(..)` or `Request#classes(..)`, " +
            "declares a class passed to such a call, or declares a `@RunWith(Enclosed.class)` class with both " +
            "per-test and class-level setup. Meant as a precondition: those modules still need `junit:junit` to compile.";

    public static class Accumulator {
        final Set<JavaProject> keeping = new HashSet<>();
        final Set<String> launched = new HashSet<>();
        final Map<String, Set<JavaProject>> declaringProjects = new HashMap<>();
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new JavaIsoVisitor<ExecutionContext>() {
            private JavaProject project;

            @Override
            public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                project = cu.getMarkers().findFirst(JavaProject.class).orElse(null);
                if (project == null) {
                    return cu;
                }
                if (declaresUnmigratableEnclosedClass(cu)) {
                    acc.keeping.add(project);
                }
                return super.visitCompilationUnit(cu, ctx);
            }

            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                JavaType.FullyQualified type = classDecl.getType();
                if (type != null) {
                    acc.declaringProjects.computeIfAbsent(type.getFullyQualifiedName(), k -> new HashSet<>())
                            .add(project);
                }
                return super.visitClassDeclaration(classDecl, ctx);
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                if (isLauncherCall(method)) {
                    acc.keeping.add(project);
                    collectLaunchedClasses(method, acc.launched);
                }
                return super.visitMethodInvocation(method, ctx);
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        Set<JavaProject> keeping = new HashSet<>(acc.keeping);
        for (String launchedClass : acc.launched) {
            keeping.addAll(acc.declaringProjects.getOrDefault(launchedClass, emptySet()));
        }
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree preVisit(Tree tree, ExecutionContext ctx) {
                stopAfterPreVisit();
                JavaProject project = tree.getMarkers().findFirst(JavaProject.class).orElse(null);
                return keeping.contains(project) ? tree : SearchResult.found(tree);
            }
        };
    }
}
