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
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.J;
import org.openrewrite.marker.SearchResult;

public class FindUnmigratableEnclosedClasses extends Recipe {

    @Getter
    final String displayName = "Find `@RunWith(Enclosed.class)` classes that cannot be migrated automatically";

    @Getter
    final String description = "Marks `@RunWith(Enclosed.class)` classes that have both per-test setup (`@Before`, " +
            "`@After` or an instance `@Rule`) and class-level setup (`@BeforeClass`, `@AfterClass` or a `@ClassRule`). " +
            "`@Nested` classes would inherit the per-test setup, while independent classes would lose the class-level " +
            "setup, so such a class must be migrated manually.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(new UsesType<>(EnclosedToNested.ENCLOSED, false), new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);
                return EnclosedToNested.isUnmigratable(cd) ? SearchResult.found(cd, EnclosedToNested.MANUAL_MIGRATION_MESSAGE) : cd;
            }
        });
    }
}
