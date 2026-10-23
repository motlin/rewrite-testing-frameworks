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
import org.openrewrite.marker.SearchResult;

import java.util.HashSet;
import java.util.Set;

import static org.openrewrite.java.testing.junit5.IsNotRunByJUnit4Launcher.LAUNCHER_METHODS;

public class ModuleHasNoJUnit4Launcher extends ScanningRecipe<Set<JavaProject>> {

    @Getter
    final String displayName = "Module has no JUnit 4 launcher";

    @Getter
    final String description = "Searches for the sources of modules that do not call `JUnitCore#run(..)`, " +
            "`JUnitCore#runClasses(..)`, `Request#aClass(..)`, `Request#method(..)` or `Request#classes(..)`. " +
            "Meant as a precondition: the launcher and the classes it runs stay on JUnit 4, so their module " +
            "still needs `junit:junit` to compile.";

    @Override
    public Set<JavaProject> getInitialValue(ExecutionContext ctx) {
        return new HashSet<>();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Set<JavaProject> launching) {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                JavaProject project = cu.getMarkers().findFirst(JavaProject.class).orElse(null);
                return project == null || launching.contains(project) ? cu : super.visitCompilationUnit(cu, ctx);
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                if (LAUNCHER_METHODS.stream().anyMatch(matcher -> matcher.matches(method))) {
                    getCursor().firstEnclosingOrThrow(J.CompilationUnit.class).getMarkers()
                            .findFirst(JavaProject.class).ifPresent(launching::add);
                }
                return super.visitMethodInvocation(method, ctx);
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Set<JavaProject> launching) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree preVisit(Tree tree, ExecutionContext ctx) {
                stopAfterPreVisit();
                JavaProject project = tree.getMarkers().findFirst(JavaProject.class).orElse(null);
                return launching.contains(project) ? tree : SearchResult.found(tree);
            }
        };
    }
}
