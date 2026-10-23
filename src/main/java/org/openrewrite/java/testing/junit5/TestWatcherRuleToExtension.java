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
import org.openrewrite.*;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.*;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Collections.emptyList;
import static java.util.Collections.reverse;

public class TestWatcherRuleToExtension extends Recipe {

    private static final String JUNIT4_TEST_WATCHER = "org.junit.rules.TestWatcher";
    private static final String JUPITER_TEST_WATCHER = "org.junit.jupiter.api.extension.TestWatcher";
    private static final String DESCRIPTION = "org.junit.runner.Description";
    private static final String EXTENSION_CONTEXT = "org.junit.jupiter.api.extension.ExtensionContext";
    private static final String ASSUMPTION_VIOLATED = "org.junit.AssumptionViolatedException";
    private static final String INTERNAL_ASSUMPTION_VIOLATED = "org.junit.internal.AssumptionViolatedException";
    private static final String BEFORE_EACH_CALLBACK = "org.junit.jupiter.api.extension.BeforeEachCallback";
    private static final String AFTER_EACH_CALLBACK = "org.junit.jupiter.api.extension.AfterEachCallback";
    private static final String REGISTER_EXTENSION = "org.junit.jupiter.api.extension.RegisterExtension";
    private static final String RULE = "org.junit.Rule";

    private static final AnnotationMatcher RULE_MATCHER = new AnnotationMatcher("@" + RULE);
    private static final AnnotationMatcher CLASS_RULE_MATCHER = new AnnotationMatcher("@org.junit.ClassRule");

    private static final MethodMatcher SUCCEEDED = hook("succeeded(" + DESCRIPTION + ")");
    private static final MethodMatcher FAILED = hook("failed(Throwable, " + DESCRIPTION + ")");
    private static final MethodMatcher SKIPPED = hook("skipped(" + ASSUMPTION_VIOLATED + ", " + DESCRIPTION + ")");
    private static final MethodMatcher SKIPPED_INTERNAL = hook("skipped(" + INTERNAL_ASSUMPTION_VIOLATED + ", " + DESCRIPTION + ")");
    private static final MethodMatcher STARTING = hook("starting(" + DESCRIPTION + ")");
    private static final MethodMatcher FINISHED = hook("finished(" + DESCRIPTION + ")");
    private static final MethodMatcher APPLY = hook("apply(..)");

    private static final MethodMatcher GET_METHOD_NAME = new MethodMatcher(DESCRIPTION + " getMethodName()");
    private static final MethodMatcher GET_CLASS_NAME = new MethodMatcher(DESCRIPTION + " getClassName()");
    private static final MethodMatcher GET_TEST_CLASS = new MethodMatcher(DESCRIPTION + " getTestClass()");
    private static final MethodMatcher GET_DISPLAY_NAME = new MethodMatcher(DESCRIPTION + " getDisplayName()");

    private static MethodMatcher hook(String signature) {
        return new MethodMatcher(JUNIT4_TEST_WATCHER + " " + signature, true);
    }

    @Getter
    final String displayName = "JUnit 4 `TestWatcher` `@Rule` to JUnit Jupiter `TestWatcher` extension";

    @Getter
    final String description = "Converts JUnit 4 `TestWatcher` subclasses to JUnit Jupiter `TestWatcher` extensions, " +
            "and registers `@Rule` fields of that type with `@RegisterExtension`. " +
            "`starting` and `finished` become `BeforeEachCallback` and `AfterEachCallback` on named subclasses. " +
            "Files are left unchanged when a watcher overrides `apply`, passes its `Description` elsewhere, " +
            "is used as a `@ClassRule` or inside a `RuleChain`, or is an anonymous class overriding `starting` or `finished`.";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(
                Preconditions.or(new UsesType<>(JUNIT4_TEST_WATCHER, false), new UsesType<>(RULE, false)),
                new JavaIsoVisitor<ExecutionContext>() {
                    @Override
                    public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                        if (new UsesType<>("org.junit.rules.RuleChain", false).visit(cu, ctx) != cu) {
                            return cu;
                        }
                        AtomicBoolean blocked = new AtomicBoolean();
                        new BlockerScanner().visit(cu, blocked);
                        if (blocked.get()) {
                            return cu;
                        }
                        J.CompilationUnit c = (J.CompilationUnit) new ConversionVisitor().visitNonNull(cu, ctx, getCursor().getParentOrThrow());
                        if (c != cu) {
                            doAfterVisit(new ChangeType(DESCRIPTION, EXTENSION_CONTEXT, true).getVisitor());
                            doAfterVisit(new ChangeType(ASSUMPTION_VIOLATED, "java.lang.Throwable", true).getVisitor());
                            doAfterVisit(new ChangeType(INTERNAL_ASSUMPTION_VIOLATED, "java.lang.Throwable", true).getVisitor());
                            doAfterVisit(new ChangeType(JUNIT4_TEST_WATCHER, JUPITER_TEST_WATCHER, true).getVisitor());
                            maybeAddImport(JUPITER_TEST_WATCHER);
                            maybeAddImport(BEFORE_EACH_CALLBACK);
                            maybeAddImport(AFTER_EACH_CALLBACK);
                            maybeAddImport(REGISTER_EXTENSION);
                            maybeRemoveImport(RULE);
                        }
                        return c;
                    }
                });
    }

    private static boolean isWatcherBody(@Nullable Object tree) {
        if (tree instanceof J.ClassDeclaration) {
            TypeTree extendings = ((J.ClassDeclaration) tree).getExtends();
            return extendings != null && TypeUtils.isOfClassType(extendings.getType(), JUNIT4_TEST_WATCHER);
        }
        if (tree instanceof J.NewClass) {
            J.NewClass nc = (J.NewClass) tree;
            return nc.getBody() != null && nc.getClazz() != null &&
                    TypeUtils.isOfClassType(nc.getClazz().getType(), JUNIT4_TEST_WATCHER);
        }
        return false;
    }

    /**
     * The class declaration or anonymous class whose body directly declares the method at the cursor.
     */
    private static @Nullable Object enclosingBodyOwner(Cursor methodCursor) {
        Cursor block = methodCursor.getParentTreeCursor();
        return block.getValue() instanceof J.Block ? block.getParentTreeCursor().getValue() : null;
    }

    private static boolean isHook(J.MethodDeclaration md) {
        JavaType.Method type = md.getMethodType();
        return SUCCEEDED.matches(type) || FAILED.matches(type) || SKIPPED.matches(type) ||
                SKIPPED_INTERNAL.matches(type) || STARTING.matches(type) || FINISHED.matches(type);
    }

    private static boolean isMappedDescriptionCall(J.MethodInvocation mi) {
        return GET_METHOD_NAME.matches(mi) || GET_CLASS_NAME.matches(mi) ||
                GET_TEST_CLASS.matches(mi) || GET_DISPLAY_NAME.matches(mi);
    }

    private static boolean isTestWatcherRule(J.VariableDeclarations vd, AnnotationMatcher ruleMatcher) {
        return TypeUtils.isAssignableTo(JUNIT4_TEST_WATCHER, vd.getType()) &&
                vd.getLeadingAnnotations().stream().anyMatch(ruleMatcher::matches);
    }

    /**
     * Flags any construct the conversion cannot carry over faithfully.
     */
    private static class BlockerScanner extends JavaIsoVisitor<AtomicBoolean> {
        @Override
        public J.Import visitImport(J.Import _import, AtomicBoolean blocked) {
            return _import;
        }

        @Override
        public J.NewClass visitNewClass(J.NewClass newClass, AtomicBoolean blocked) {
            if (isWatcherBody(newClass)) {
                Cursor parent = getCursor().getParentTreeCursor();
                Cursor declarations = parent.getValue() instanceof J.VariableDeclarations.NamedVariable ?
                        parent.getParentTreeCursor() : null;
                if (declarations == null || !(declarations.getValue() instanceof J.VariableDeclarations) ||
                        !isTestWatcherRule(declarations.getValue(), RULE_MATCHER)) {
                    blocked.set(true);
                }
            }
            return super.visitNewClass(newClass, blocked);
        }

        @Override
        public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations multiVariable, AtomicBoolean blocked) {
            if (isTestWatcherRule(multiVariable, CLASS_RULE_MATCHER)) {
                blocked.set(true);
            }
            Cursor owner = getCursor().getParentTreeCursor();
            if (owner.getValue() instanceof J.MethodDeclaration && isHook(owner.getValue()) &&
                    isWatcherBody(enclosingBodyOwner(owner))) {
                // A hook parameter: its type expression is rewritten by `ChangeType`
                return multiVariable;
            }
            return super.visitVariableDeclarations(multiVariable, blocked);
        }

        @Override
        public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, AtomicBoolean blocked) {
            Object owner = enclosingBodyOwner(getCursor());
            if (isWatcherBody(owner)) {
                if (APPLY.matches(method.getMethodType())) {
                    blocked.set(true);
                }
                if (owner instanceof J.NewClass &&
                        (STARTING.matches(method.getMethodType()) || FINISHED.matches(method.getMethodType()))) {
                    blocked.set(true);
                }
            }
            if (method.getMethodType() != null &&
                    TypeUtils.isAssignableTo(JUNIT4_TEST_WATCHER, method.getMethodType().getReturnType()) &&
                    method.getLeadingAnnotations().stream().anyMatch(a -> RULE_MATCHER.matches(a) || CLASS_RULE_MATCHER.matches(a))) {
                blocked.set(true);
            }
            return super.visitMethodDeclaration(method, blocked);
        }

        @Override
        public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, AtomicBoolean blocked) {
            if (method.getSelect() instanceof J.Identifier && isMappedDescriptionCall(method) && insideHook()) {
                return method.withArguments(ListUtils.map(method.getArguments(), a -> (Expression) visit(a, blocked)));
            }
            if (method.getSelect() instanceof J.Identifier &&
                    "super".equals(((J.Identifier) method.getSelect()).getSimpleName()) && insideHook()) {
                blocked.set(true);
            }
            return super.visitMethodInvocation(method, blocked);
        }

        @Override
        public J.Identifier visitIdentifier(J.Identifier identifier, AtomicBoolean blocked) {
            if (TypeUtils.isOfClassType(identifier.getType(), DESCRIPTION)) {
                blocked.set(true);
            } else if ((TypeUtils.isOfClassType(identifier.getType(), ASSUMPTION_VIOLATED) ||
                    TypeUtils.isOfClassType(identifier.getType(), INTERNAL_ASSUMPTION_VIOLATED)) &&
                    (identifier.getFieldType() == null || !insideHook())) {
                blocked.set(true);
            }
            return identifier;
        }

        private boolean insideHook() {
            Cursor methodCursor = getCursor().dropParentUntil(t -> t instanceof J.MethodDeclaration || t == Cursor.ROOT_VALUE);
            return methodCursor.getValue() instanceof J.MethodDeclaration && isHook(methodCursor.getValue()) &&
                    isWatcherBody(enclosingBodyOwner(methodCursor));
        }
    }

    private static class ConversionVisitor extends JavaIsoVisitor<ExecutionContext> {
        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
            J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);
            if (!isWatcherBody(classDecl)) {
                return cd;
            }
            List<TypeTree> interfaces = new ArrayList<>();
            if (cd.getImplements() != null) {
                interfaces.addAll(cd.getImplements());
            }
            interfaces.add(identifier(JUPITER_TEST_WATCHER, interfaces.isEmpty() ? Space.EMPTY : Space.SINGLE_SPACE));
            boolean starting = false;
            boolean finished = false;
            for (Statement statement : classDecl.getBody().getStatements()) {
                if (statement instanceof J.MethodDeclaration) {
                    starting |= STARTING.matches(((J.MethodDeclaration) statement).getMethodType());
                    finished |= FINISHED.matches(((J.MethodDeclaration) statement).getMethodType());
                }
            }
            if (starting) {
                interfaces.add(identifier(BEFORE_EACH_CALLBACK, Space.SINGLE_SPACE));
            }
            if (finished) {
                interfaces.add(identifier(AFTER_EACH_CALLBACK, Space.SINGLE_SPACE));
            }
            return cd.withExtends(null).withImplements(interfaces);
        }

        @Override
        public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
            J.MethodDeclaration md = super.visitMethodDeclaration(method, ctx);
            if (!isHook(method) || !isWatcherBody(enclosingBodyOwner(getCursor()))) {
                return md;
            }
            JavaType.Method type = method.getMethodType();
            String newName;
            boolean swapParameters = false;
            if (SUCCEEDED.matches(type)) {
                newName = "testSuccessful";
            } else if (FAILED.matches(type)) {
                newName = "testFailed";
                swapParameters = true;
            } else if (STARTING.matches(type)) {
                newName = "beforeEach";
            } else if (FINISHED.matches(type)) {
                newName = "afterEach";
            } else {
                newName = "testAborted";
                swapParameters = true;
            }
            md = md.withName(md.getName().withSimpleName(newName));
            if (type != null) {
                md = md.withMethodType(type.withName(newName));
                md = md.withName(md.getName().withType(md.getMethodType()));
            }
            md = md.withModifiers(ListUtils.map(md.getModifiers(), m -> m.getType() == J.Modifier.Type.Protected ?
                    m.withType(J.Modifier.Type.Public).withKeyword("public") : m));
            if (swapParameters) {
                List<Statement> params = md.getParameters();
                Statement first = params.get(0);
                Statement second = params.get(1);
                List<Statement> swapped = new ArrayList<>(2);
                swapped.add(second.withPrefix(first.getPrefix()));
                swapped.add(first.withPrefix(second.getPrefix()));
                md = md.withParameters(swapped);
                JavaType.Method mt = md.getMethodType();
                if (mt != null && mt.getParameterTypes().size() == 2) {
                    List<JavaType> types = new ArrayList<>(mt.getParameterTypes());
                    List<String> names = new ArrayList<>(mt.getParameterNames());
                    reverse(types);
                    reverse(names);
                    md = md.withMethodType(mt.withParameterTypes(types).withParameterNames(names));
                }
            }
            return md;
        }

        @Override
        public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
            J.MethodInvocation mi = super.visitMethodInvocation(method, ctx);
            String replacement;
            if (GET_METHOD_NAME.matches(mi)) {
                replacement = "#{any(" + EXTENSION_CONTEXT + ")}.getRequiredTestMethod().getName()";
            } else if (GET_CLASS_NAME.matches(mi)) {
                replacement = "#{any(" + EXTENSION_CONTEXT + ")}.getRequiredTestClass().getName()";
            } else if (GET_TEST_CLASS.matches(mi)) {
                replacement = "#{any(" + EXTENSION_CONTEXT + ")}.getRequiredTestClass()";
            } else {
                return mi;
            }
            return JavaTemplate.builder(replacement)
                    .javaParser(JavaParser.fromJavaVersion().classpathFromResources(ctx, "junit-jupiter-api-5"))
                    .build()
                    .apply(getCursor(), mi.getCoordinates().replace(), mi.getSelect());
        }

        @Override
        public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations multiVariable, ExecutionContext ctx) {
            J.VariableDeclarations vd = super.visitVariableDeclarations(multiVariable, ctx);
            if (!isTestWatcherRule(multiVariable, RULE_MATCHER)) {
                return vd;
            }
            return vd.withLeadingAnnotations(ListUtils.map(vd.getLeadingAnnotations(), a -> RULE_MATCHER.matches(a) ?
                    a.withAnnotationType(identifier(REGISTER_EXTENSION, a.getAnnotationType().getPrefix())) : a));
        }

        private static J.Identifier identifier(String fullyQualifiedName, Space prefix) {
            return new J.Identifier(
                    Tree.randomId(),
                    prefix,
                    Markers.EMPTY,
                    emptyList(),
                    fullyQualifiedName.substring(fullyQualifiedName.lastIndexOf('.') + 1),
                    JavaType.ShallowClass.build(fullyQualifiedName),
                    null);
        }
    }
}
