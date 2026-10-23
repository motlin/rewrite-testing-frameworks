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
import org.openrewrite.Recipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.marker.SearchResult;

import java.util.concurrent.atomic.AtomicBoolean;

public class HasNoUnmigratableEnclosedClass extends Recipe {

    @Getter
    final String displayName = "Has no `@RunWith(Enclosed.class)` class that cannot be migrated automatically";

    @Getter
    final String description = "Searches for the sources that do not declare a `@RunWith(Enclosed.class)` class with " +
            "both per-test and class-level setup. Meant as a precondition: such a class stays on JUnit 4, so the rest " +
            "of its source must not be migrated to JUnit Jupiter either.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree preVisit(Tree tree, ExecutionContext ctx) {
                stopAfterPreVisit();
                return tree instanceof J.CompilationUnit && declaresUnmigratableEnclosedClass((J.CompilationUnit) tree) ?
                        tree : SearchResult.found(tree);
            }
        };
    }

    private static boolean declaresUnmigratableEnclosedClass(J.CompilationUnit cu) {
        return new JavaIsoVisitor<AtomicBoolean>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, AtomicBoolean declares) {
                if (EnclosedToNested.isUnmigratable(classDecl)) {
                    declares.set(true);
                }
                return super.visitClassDeclaration(classDecl, declares);
            }
        }.reduce(cu, new AtomicBoolean()).get();
    }
}
