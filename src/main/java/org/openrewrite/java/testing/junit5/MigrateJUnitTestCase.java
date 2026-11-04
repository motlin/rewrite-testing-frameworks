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

import lombok.Getter;
import org.jspecify.annotations.Nullable;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.ChangeType;
import org.openrewrite.java.DeleteMethodArgument;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.RemoveAnnotationVisitor;
import org.openrewrite.java.UseStaticImport;
import org.openrewrite.java.search.DeclaresMethod;
import org.openrewrite.java.search.FindAnnotations;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.Comment;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.Flag;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaSourceFile;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TextComment;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.Markers;
import org.openrewrite.marker.SearchResult;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;

public class MigrateJUnitTestCase extends ScanningRecipe<MigrateJUnitTestCase.Accumulator> {

    private static final AnnotationMatcher OVERRIDE_ANNOTATION_MATCHER = new AnnotationMatcher("@java.lang.Override");
    private static final AnnotationMatcher JUNIT_TEST_ANNOTATION_MATCHER = new AnnotationMatcher("@org.junit.Test");
    private static final AnnotationMatcher JUNIT_AFTER_ANNOTATION_MATCHER = new AnnotationMatcher("@org.junit.*After*");
    private static final AnnotationMatcher JUNIT_BEFORE_ANNOTATION_MATCHER = new AnnotationMatcher("@org.junit.*Before*");
    private static final MethodMatcher TEST_CASE_RUN_MATCHER = new MethodMatcher("junit.framework.TestCase run(junit.framework.TestResult)", true);
    private static final MethodMatcher SET_NAME_MATCHER = new MethodMatcher("junit.framework.TestCase setName(String)");
    private static final MethodMatcher SET_UP_MATCHER = new MethodMatcher("junit.framework.TestCase setUp()");
    private static final MethodMatcher TEAR_DOWN_MATCHER = new MethodMatcher("junit.framework.TestCase tearDown()");
    private static final MethodMatcher SUITE_MATCHER = new MethodMatcher("*..* suite()");

    private static boolean isSupertypeTestCase(JavaType.@Nullable FullyQualified fullyQualified) {
        if (fullyQualified == null || fullyQualified.getSupertype() == null || "java.lang.Object".equals(fullyQualified.getFullyQualifiedName())) {
            return false;
        }

        JavaType.FullyQualified fqType = TypeUtils.asFullyQualified(fullyQualified);
        if (fqType != null && "junit.framework.TestCase".equals(fqType.getFullyQualifiedName())) {
            return true;
        }
        return isSupertypeTestCase(fullyQualified.getSupertype());
    }

    @Getter
    final String displayName = "Migrate JUnit 4 `TestCase` to JUnit Jupiter";

    @Getter
    final String description = "Convert JUnit 4 `TestCase` to JUnit Jupiter.";

    static class Accumulator {
        final Map<String, Constructor> constructors = new HashMap<>();
        /**
         * Signatures declared more than once, as when two projects declare a class with the same fully qualified name,
         * whose callers cannot be told apart.
         */
        final Set<String> ambiguous = new HashSet<>();
        final Map<String, Boolean> references = new HashMap<>();
        final Set<String> nonJavaReferencedClasses = new HashSet<>();
        final Map<String, List<Constructor>> constructorsByClass = new HashMap<>();
        final Set<String> declaredClasses = new HashSet<>();
        /**
         * Class names declared more than once, as when two projects declare a class with the same fully qualified name,
         * whose callers cannot be told apart.
         */
        final Set<String> ambiguousClasses = new HashSet<>();
        /**
         * By class name, the signature of a constructor whose test name parameter could be dropped.
         */
        final Map<String, String> nameCandidates = new HashMap<>();
        /**
         * For a name candidate that extends another subclass of TestCase, the superclass whose constructor must be
         * converted too, as the candidate passes its test name on to it.
         */
        final Map<String, String> superclasses = new HashMap<>();
        /**
         * For a name candidate that extends another subclass of TestCase, the signature of the superclass constructor
         * it passes its test name to, which may instead be removed as redundant.
         */
        final Map<String, String> superConstructors = new HashMap<>();
        final Map<String, Boolean> classReferences = new HashMap<>();
        private @Nullable Set<String> redundant;
        private @Nullable Map<String, String> convertible;

        void declare(Constructor constructor) {
            if (constructors.put(constructor.signature, constructor) != null) {
                ambiguous.add(constructor.signature);
            }
            constructorsByClass.computeIfAbsent(constructor.declaringClass, ignored -> new ArrayList<>()).add(constructor);
        }

        void declareClass(String className) {
            if (!declaredClasses.add(className)) {
                ambiguousClasses.add(className);
            }
        }

        void reference(@Nullable String signature, @Nullable String className, boolean unsafe) {
            if (signature != null) {
                references.merge(signature, unsafe, Boolean::logicalOr);
            }
            if (className != null) {
                classReferences.merge(className, unsafe, Boolean::logicalOr);
            }
        }

        /**
         * The constructor signatures, by class name, whose test name parameter can be dropped along with the
         * argument at every caller.
         */
        Map<String, String> convertible() {
            if (convertible == null) {
                Set<String> redundant = redundantConstructors();
                Map<String, String> result = new HashMap<>();
                boolean changed = true;
                while (changed) {
                    changed = false;
                    for (Map.Entry<String, String> candidate : nameCandidates.entrySet()) {
                        String superclass = superclasses.get(candidate.getKey());
                        if (!result.containsKey(candidate.getKey()) &&
                            !ambiguousClasses.contains(candidate.getKey()) &&
                            !nonJavaReferencedClasses.contains(candidate.getKey()) &&
                            !classReferences.getOrDefault(candidate.getKey(), false) &&
                            (superclass == null || result.containsKey(superclass) ||
                             redundant.contains(superConstructors.get(candidate.getKey())))) {
                            result.put(candidate.getKey(), candidate.getValue());
                            changed = true;
                        }
                    }
                }
                convertible = result;
            }
            return convertible;
        }

        Set<String> redundantConstructors() {
            if (redundant != null) {
                return redundant;
            }
            Set<String> retained = new HashSet<>(ambiguous);
            for (Map.Entry<String, Boolean> reference : references.entrySet()) {
                if (reference.getValue()) {
                    retained.add(reference.getKey());
                }
            }
            for (Constructor constructor : constructors.values()) {
                if (nonJavaReferencedClasses.contains(constructor.declaringClass)) {
                    retained.add(constructor.signature);
                }
            }
            Set<String> redundant = new HashSet<>();
            boolean changed = true;
            while (changed) {
                changed = false;
                for (Constructor declaration : constructors.values()) {
                    if (declaration.removable && !retained.contains(declaration.signature) &&
                        !redundant.contains(declaration.signature) && hasRedundantBody(declaration, redundant)) {
                        changed |= redundant.add(declaration.signature);
                    }
                }
            }
            changed = true;
            while (changed) {
                Set<String> rejected = new HashSet<>();
                for (String signature : redundant) {
                    Constructor declaration = constructors.get(signature);
                    List<Constructor> siblings = constructorsByClass.get(declaration.declaringClass);
                    long redundantCount = siblings.stream()
                            .filter(constructor -> redundant.contains(constructor.signature))
                            .count();
                    boolean retainedNoArgumentConstructor = siblings.stream().anyMatch(constructor ->
                            constructor.noArguments && !redundant.contains(constructor.signature));
                    boolean incompatibleConstructors = redundantCount > 1 && siblings.stream()
                            .filter(constructor -> redundant.contains(constructor.signature))
                            .anyMatch(constructor -> constructor.annotated || !constructor.visibility.equals(declaration.visibility));
                    if (retainedNoArgumentConstructor || incompatibleConstructors || !hasRedundantBody(declaration, redundant)) {
                        rejected.add(signature);
                    }
                }
                changed = redundant.removeAll(rejected);
            }
            this.redundant = redundant;
            return redundant;
        }

        private static boolean hasRedundantBody(Constructor declaration, Set<String> redundant) {
            switch (declaration.body) {
                case EMPTY:
                    return true;
                case SAFE_DELEGATION:
                    return declaration.delegatesToTestCase || redundant.contains(declaration.delegationSignature);
                default:
                    return false;
            }
        }
    }

    private enum ConstructorBody {
        NONE, EMPTY, SAFE_DELEGATION, OTHER
    }

    /**
     * What the redundancy analysis needs to know about a constructor declaration, kept as plain values
     * so that the accumulator holds no trees.
     */
    static class Constructor {
        final String signature;
        final String declaringClass;
        /**
         * Declared in a TestCase subclass nested only in other TestCase subclasses, as the migration does not visit others,
         * with a signature that could be dropped: no thrown exceptions, varargs, or generic parameters.
         */
        final boolean removable;
        final boolean noArguments;
        final boolean annotated;
        final Set<J.Modifier.Type> visibility;
        final ConstructorBody body;
        /**
         * For a body that is a single constructor invocation with safe arguments, whether it calls a TestCase constructor.
         */
        final boolean delegatesToTestCase;
        /**
         * For a body that is a single constructor invocation with safe arguments, the signature of the invoked constructor.
         */
        final @Nullable String delegationSignature;

        Constructor(J.MethodDeclaration method, JavaType.Method type, boolean migrated) {
            signature = MethodMatcher.methodPattern(type);
            declaringClass = type.getDeclaringType().getFullyQualifiedName();
            removable = migrated && type.getThrownExceptions().isEmpty() &&
                        !type.hasFlags(Flag.Varargs) &&
                        type.getParameterTypes().stream().noneMatch(MigrateJUnitTestCase::hasGenericParameter);
            noArguments = type.getParameterTypes().isEmpty();
            annotated = !method.getLeadingAnnotations().isEmpty();
            visibility = EnumSet.noneOf(J.Modifier.Type.class);
            for (J.Modifier.Type modifier : asList(J.Modifier.Type.Public, J.Modifier.Type.Protected, J.Modifier.Type.Private)) {
                if (method.hasModifier(modifier)) {
                    visibility.add(modifier);
                }
            }
            List<Statement> statements = method.getBody() == null ? null : method.getBody().getStatements();
            if (statements == null) {
                body = ConstructorBody.NONE;
                delegatesToTestCase = false;
                delegationSignature = null;
            } else if (statements.isEmpty()) {
                body = ConstructorBody.EMPTY;
                delegatesToTestCase = false;
                delegationSignature = null;
            } else if (statements.size() == 1 && statements.get(0) instanceof J.MethodInvocation &&
                       safeArguments(((J.MethodInvocation) statements.get(0)).getArguments())) {
                J.MethodInvocation delegation = (J.MethodInvocation) statements.get(0);
                body = ConstructorBody.SAFE_DELEGATION;
                delegatesToTestCase = TestCaseVisitor.TEST_CASE_SUPER_MATCHER.matches(delegation);
                delegationSignature = constructorSignature(delegation.getMethodType());
            } else {
                body = ConstructorBody.OTHER;
                delegatesToTestCase = false;
                delegationSignature = null;
            }
        }
    }

    private static @Nullable String constructorSignature(JavaType.@Nullable Method method) {
        return method == null ? null : MethodMatcher.methodPattern(method);
    }

    private static @Nullable String constructorSignature(J.NewClass newClass) {
        JavaType.Method type = newClass.getConstructorType();
        if (type == null) {
            return null;
        }
        // An anonymous class gets its own constructor, which passes its arguments to the superclass constructor
        JavaType.FullyQualified supertype = type.getDeclaringType().getSupertype();
        return newClass.getBody() == null || supertype == null ? MethodMatcher.methodPattern(type) :
                MethodMatcher.methodPattern(type.withDeclaringType(supertype));
    }

    private static boolean hasGenericParameter(JavaType type) {
        if (type instanceof JavaType.GenericTypeVariable) {
            return true;
        }
        if (type instanceof JavaType.Array) {
            return hasGenericParameter(((JavaType.Array) type).getElemType());
        }
        return type instanceof JavaType.Parameterized &&
               ((JavaType.Parameterized) type).getTypeParameters().stream().anyMatch(MigrateJUnitTestCase::hasGenericParameter);
    }

    private static boolean safeArguments(List<Expression> arguments) {
        return arguments.stream().allMatch(argument -> argument instanceof J.Empty || argument instanceof J.Literal ||
                argument instanceof J.Identifier && ((J.Identifier) argument).getFieldType() != null &&
                ((J.Identifier) argument).getFieldType().getOwner() instanceof JavaType.Method);
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree preVisit(Tree tree, ExecutionContext ctx) {
                stopAfterPreVisit();
                if (!(tree instanceof JavaSourceFile)) {
                    return tree;
                }
                if (tree instanceof J.CompilationUnit) {
                    // A TestCase left untouched keeps its constructors, so their callers must keep their arguments
                    boolean skipped = new DeclaresMethod<>(TEST_CASE_RUN_MATCHER).visit(tree, ctx) != tree ||
                                      hasUnsupportedTestSetup(tree, getCursor().getParentOrThrow());
                    return constructorScanner(acc, skipped).visitNonNull(tree, ctx, getCursor().getParentOrThrow());
                }
                for (JavaType.Method method : ((JavaSourceFile) tree).getTypesInUse().getUsedMethods()) {
                    if (method.isConstructor()) {
                        acc.nonJavaReferencedClasses.add(method.getDeclaringType().getFullyQualifiedName());
                    }
                }
                return tree;
            }
        };
    }

    private JavaIsoVisitor<ExecutionContext> constructorScanner(Accumulator acc, boolean skipped) {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDeclaration, ExecutionContext ctx) {
                if (classDeclaration.getType() != null) {
                    acc.declareClass(classDeclaration.getType().getFullyQualifiedName());
                }
                return super.visitClassDeclaration(classDeclaration, ctx);
            }

            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                JavaType.Method type = method.getMethodType();
                if (method.isConstructor() && type != null) {
                    boolean migrated = getCursor().getPathAsStream()
                            .filter(J.ClassDeclaration.class::isInstance)
                            .allMatch(c -> isSupertypeTestCase(((J.ClassDeclaration) c).getType()));
                    Constructor constructor = new Constructor(method, type, migrated);
                    acc.declare(constructor);
                    if (skipped) {
                        acc.references.put(constructor.signature, true);
                    } else if (migrated && canRemoveName(method, getCursor().firstEnclosingOrThrow(J.ClassDeclaration.class), getCursor())) {
                        acc.nameCandidates.put(constructor.declaringClass, constructor.signature);
                        JavaType.FullyQualified supertype = type.getDeclaringType().getSupertype();
                        if (supertype != null && !"junit.framework.TestCase".equals(supertype.getFullyQualifiedName())) {
                            acc.superclasses.put(constructor.declaringClass, supertype.getFullyQualifiedName());
                            for (Statement statement : method.getBody().getStatements()) {
                                if (statement instanceof J.MethodInvocation && "super".equals(((J.MethodInvocation) statement).getSimpleName())) {
                                    String superConstructor = constructorSignature(((J.MethodInvocation) statement).getMethodType());
                                    if (superConstructor != null) {
                                        acc.superConstructors.put(constructor.declaringClass, superConstructor);
                                    }
                                }
                            }
                        }
                    }
                }
                return super.visitMethodDeclaration(method, ctx);
            }

            @Override
            public J.NewClass visitNewClass(J.NewClass newClass, ExecutionContext ctx) {
                acc.reference(constructorSignature(newClass), instantiatedClass(newClass), skipped || !safeArguments(newClass.getArguments()));
                return super.visitNewClass(newClass, ctx);
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                JavaType.Method type = method.getMethodType();
                if (type != null && type.isConstructor()) {
                    acc.reference(MethodMatcher.methodPattern(type), constructedClass(type), skipped || !safeArguments(method.getArguments()));
                }
                return super.visitMethodInvocation(method, ctx);
            }

            @Override
            public J.MemberReference visitMemberReference(J.MemberReference memberRef, ExecutionContext ctx) {
                JavaType.Method type = memberRef.getMethodType();
                if (type != null && type.isConstructor()) {
                    acc.reference(MethodMatcher.methodPattern(type), constructedClass(type), true);
                }
                return super.visitMemberReference(memberRef, ctx);
            }
        };
    }

    private static @Nullable String constructedClass(JavaType.@Nullable Method method) {
        return method == null || !method.isConstructor() ? null : method.getDeclaringType().getFullyQualifiedName();
    }

    /**
     * The class whose constructor a {@code new} expression invokes, which for an anonymous class is its superclass.
     */
    private static @Nullable String instantiatedClass(J.NewClass newClass) {
        JavaType.FullyQualified supertype = newClass.getConstructorType() == null ? null :
                newClass.getConstructorType().getDeclaringType().getSupertype();
        return newClass.getBody() == null || supertype == null ?
                constructedClass(newClass.getConstructorType()) : supertype.getFullyQualifiedName();
    }

    private static boolean canRemoveName(J.MethodDeclaration method, J.ClassDeclaration owner, Cursor cursor) {
        if (method.getBody() == null || method.getParameters().size() != 1 ||
            !(method.getParameters().get(0) instanceof J.VariableDeclarations) ||
            !TypeUtils.isString(((J.VariableDeclarations) method.getParameters().get(0)).getType()) ||
            ((J.VariableDeclarations) method.getParameters().get(0)).getVarargs() != null ||
            owner.getExtends() == null ||
            owner.getBody().getStatements().stream().filter(J.MethodDeclaration.class::isInstance)
                    .map(J.MethodDeclaration.class::cast).filter(J.MethodDeclaration::isConstructor).count() != 1 ||
            method.getBody().getStatements().stream().allMatch(statement -> statement instanceof J.MethodInvocation &&
                    TestCaseVisitor.TEST_CASE_SUPER_MATCHER.matches((J.MethodInvocation) statement))) {
            return false;
        }
        J.VariableDeclarations.NamedVariable nameParameter =
                ((J.VariableDeclarations) method.getParameters().get(0)).getVariables().get(0);
        JavaType.@Nullable Variable nameVariable = nameParameter.getVariableType();
        return !new JavaIsoVisitor<AtomicBoolean>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation invocation, AtomicBoolean used) {
                // The name passed on to a superclass constructor goes away along with that constructor's parameter
                if (TestCaseVisitor.TEST_CASE_SUPER_MATCHER.matches(invocation) || "super".equals(invocation.getSimpleName()) &&
                    invocation.getArguments().size() == 1 && invocation.getArguments().get(0) instanceof J.Identifier &&
                    isName((J.Identifier) invocation.getArguments().get(0))) {
                    return invocation;
                }
                return super.visitMethodInvocation(invocation, used);
            }

            @Override
            public J.Identifier visitIdentifier(J.Identifier identifier, AtomicBoolean used) {
                if (isName(identifier)) {
                    used.set(true);
                }
                return identifier;
            }

            private boolean isName(J.Identifier identifier) {
                return identifier.getFieldType() == null ?
                        nameParameter.getSimpleName().equals(identifier.getSimpleName()) :
                        identifier.getFieldType().equals(nameVariable);
            }
        }.reduce(method.getBody(), new AtomicBoolean(nameVariable == null), cursor).get();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        Set<String> redundant = acc.redundantConstructors();
        Map<String, String> convertible = acc.convertible();
        // A TestCase overriding run(TestResult) participates in the JUnit 3 execution model,
        // which has no Jupiter equivalent, so leave it untouched rather than half-migrate it
        return Preconditions.check(Preconditions.not(new DeclaresMethod<>(TEST_CASE_RUN_MATCHER)), new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                J.CompilationUnit c = redundant.isEmpty() && convertible.isEmpty() ? cu :
                        (J.CompilationUnit) constructorVisitor(redundant, convertible).visitNonNull(cu, ctx, getCursor().getParentOrThrow());
                for (String signature : convertible.values()) {
                    c = (J.CompilationUnit) new DeleteMethodArgument(signature, 0).getVisitor()
                            .visitNonNull(c, ctx, getCursor().getParentOrThrow());
                }
                return (J.CompilationUnit) migrationVisitor(redundant, convertible).visitNonNull(c, ctx, getCursor().getParentOrThrow());
            }
        });
    }

    private JavaIsoVisitor<ExecutionContext> constructorVisitor(Set<String> redundant, Map<String, String> convertible) {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                if (method.getBody() == null || !redundant.contains(constructorSignature(method.getMethodType()))) {
                    return super.visitMethodDeclaration(method, ctx);
                }
                return method.withBody(method.getBody().withStatements(emptyList()));
            }

            @Override
            public J.NewClass visitNewClass(J.NewClass newClass, ExecutionContext ctx) {
                J.NewClass n = super.visitNewClass(newClass, ctx);
                JavaType.Method type = n.getConstructorType();
                // DeleteMethodArgument does not match an anonymous class, which has a constructor of its own
                boolean convertedAnonymousClass = n.getBody() != null && type != null &&
                        type.getParameterTypes().size() == 1 && convertible.containsKey(instantiatedClass(n));
                if (!convertedAnonymousClass && (type == null || type.getParameterTypes().isEmpty() || !redundant.contains(constructorSignature(n)))) {
                    return n;
                }
                return n.withArguments(emptyList())
                        .withConstructorType(type.withParameterTypes(emptyList()).withParameterNames(emptyList()));
            }

            @Override
            public J.@Nullable MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
                JavaType.Method type = m.getMethodType();
                if (type == null || type.getParameterTypes().isEmpty() || !redundant.contains(MethodMatcher.methodPattern(type))) {
                    return m;
                }
                if ("super".equals(m.getSimpleName())) {
                    return null;
                }
                return m.withArguments(emptyList())
                        .withMethodType(type.withParameterTypes(emptyList()).withParameterNames(emptyList()));
            }
        };
    }

    /**
     * Whether a suite() wraps a TestSetup fixture that cannot be converted to lifecycle methods, which leaves its
     * TestCase untouched.
     */
    private static boolean isUnsupportedTestSetup(J.MethodDeclaration method, Cursor cursor) {
        J.ClassDeclaration owner = cursor.firstEnclosing(J.ClassDeclaration.class);
        return owner != null && isSupertypeTestCase(owner.getType()) && isSuite(method) &&
               containsTestSetup(method) && testSetupLifecycleMethods(method, owner) == null;
    }

    private static boolean hasUnsupportedTestSetup(Tree tree, Cursor parent) {
        return new JavaIsoVisitor<AtomicBoolean>() {
            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, AtomicBoolean found) {
                if (isUnsupportedTestSetup(method, getCursor())) {
                    found.set(true);
                }
                return super.visitMethodDeclaration(method, found);
            }
        }.reduce(tree, new AtomicBoolean(), parent).get();
    }

    private TreeVisitor<?, ExecutionContext> migrationVisitor(Set<String> redundant, Map<String, String> convertible) {
        return Preconditions.check(Preconditions.or(
                        new UsesType<>("junit.framework.TestCase", false),
                        new UsesType<>("junit.framework.Assert", false)
                ),
                new JavaIsoVisitor<ExecutionContext>() {
                    @Override
                    public boolean isAcceptable(SourceFile sourceFile, ExecutionContext ctx) {
                        return sourceFile instanceof J.CompilationUnit && super.isAcceptable(sourceFile, ctx);
                    }

                    @Override
                    public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                        boolean[] unsupported = {false};
                        J.CompilationUnit checked = (J.CompilationUnit) new JavaIsoVisitor<ExecutionContext>() {
                            @Override
                            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                                J.ClassDeclaration owner = getCursor().firstEnclosing(J.ClassDeclaration.class);
                                if (owner == null || !isSupertypeTestCase(owner.getType()) || !isSuite(method) ||
                                    !containsTestSetup(method) || testSetupLifecycleMethods(method, owner) != null) {
                                    return super.visitMethodDeclaration(method, ctx);
                                }
                                unsupported[0] = true;
                                return SearchResult.found(method, "Migrate this TestSetup fixture manually to JUnit Jupiter lifecycle methods");
                            }
                        }.visitNonNull(cu, ctx, getCursor().getParentTreeCursor());
                        if (unsupported[0]) {
                            return checked;
                        }
                        J.CompilationUnit c = super.visitCompilationUnit(cu, ctx);
                        doAfterVisit(new TestCaseVisitor(redundant, convertible));
                        // ChangeType for org.junit.Assert method invocations because TestCase extends org.junit.Assert
                        doAfterVisit(new ChangeType("junit.framework.TestCase", "org.junit.Assert", true).getVisitor());
                        doAfterVisit(new ChangeType("junit.framework.Assert", "org.junit.Assert", true).getVisitor());
                        doAfterVisit(new AssertToAssertions.AssertToAssertionsVisitor());
                        doAfterVisit(new UseStaticImport("org.junit.jupiter.api.Assertions assert*(..)").getVisitor());
                        doAfterVisit(new UseStaticImport("org.junit.jupiter.api.Assertions fail*(..)").getVisitor());
                        return c;
                    }

                    @Override
                    public J.Block visitBlock(J.Block block, ExecutionContext ctx) {
                        J.Block b = super.visitBlock(block, ctx);
                        // setName has no Jupiter equivalent, so the call is left behind as a comment
                        List<Statement> statements = new ArrayList<>(b.getStatements());
                        Space end = b.getEnd();
                        for (int i = statements.size() - 1; i >= 0; i--) {
                            Statement statement = statements.get(i);
                            if (statement instanceof J.MethodInvocation && SET_NAME_MATCHER.matches((J.MethodInvocation) statement)) {
                                statements.remove(i);
                                if (i < statements.size()) {
                                    statements.set(i, statements.get(i).withPrefix(commentOut(statement, statements.get(i).getPrefix())));
                                } else {
                                    end = commentOut(statement, end);
                                }
                            }
                        }
                        return statements.size() == b.getStatements().size() ? b : b.withStatements(statements).withEnd(end);
                    }

                    private Space commentOut(Statement statement, Space next) {
                        List<Comment> comments = new ArrayList<>(statement.getPrefix().getComments());
                        comments.add(new TextComment(false, statement.withPrefix(Space.EMPTY).printTrimmed(getCursor()) + ";",
                                next.getWhitespace(), Markers.EMPTY));
                        comments.addAll(next.getComments());
                        return statement.getPrefix().withComments(comments);
                    }

                    @Override
                    public J.@Nullable MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                        J.MethodInvocation mi = super.visitMethodInvocation(method, ctx);
                        // setUp and tearDown will be invoked via Before and After annotations
                        if (SET_UP_MATCHER.matches(mi) || TEAR_DOWN_MATCHER.matches(mi)) {
                            return null;
                        }
                        return mi;
                    }
                });
    }

    private static boolean isSuite(J.MethodDeclaration method) {
        return method.hasModifier(J.Modifier.Type.Static) &&
               SUITE_MATCHER.matches(method.getMethodType()) &&
               (TypeUtils.isOfClassType(method.getMethodType().getReturnType(), "junit.framework.Test") ||
                TypeUtils.isOfClassType(method.getMethodType().getReturnType(), "junit.framework.TestSuite"));
    }

    private static boolean containsTestSetup(J.MethodDeclaration method) {
        return new JavaIsoVisitor<AtomicBoolean>() {
            @Override
            public J.NewClass visitNewClass(J.NewClass newClass, AtomicBoolean found) {
                if (TypeUtils.isAssignableTo("junit.extensions.TestSetup", newClass.getType())) {
                    found.set(true);
                }
                return super.visitNewClass(newClass, found);
            }
        }.reduce(method, new AtomicBoolean()).get();
    }

    private static @Nullable List<J.MethodDeclaration> testSetupLifecycleMethods(J.MethodDeclaration suite, J.ClassDeclaration owner) {
        if (suite.getMethodType() == null || !suite.getMethodType().getParameterTypes().isEmpty() ||
            suite.getTypeParameters() != null && !suite.getTypeParameters().isEmpty() ||
            suite.getBody() == null || suite.getBody().getStatements().size() != 1 ||
            !(suite.getBody().getStatements().get(0) instanceof J.Return)) {
            return null;
        }
        J.Return returned = (J.Return) suite.getBody().getStatements().get(0);
        if (!(returned.getExpression() instanceof J.NewClass)) {
            return null;
        }
        J.NewClass decorator = (J.NewClass) returned.getExpression();
        if (decorator.getClazz() == null || !TypeUtils.isOfClassType(decorator.getClazz().getType(), "junit.extensions.TestSetup") ||
            decorator.getBody() == null || decorator.getArguments().size() != 1 ||
            !(decorator.getArguments().get(0) instanceof J.NewClass)) {
            return null;
        }
        J.NewClass wrapped = (J.NewClass) decorator.getArguments().get(0);
        if (wrapped.getBody() != null || !TypeUtils.isOfClassType(wrapped.getType(), "junit.framework.TestSuite") ||
            wrapped.getArguments().size() != 1 || !(wrapped.getArguments().get(0) instanceof J.FieldAccess)) {
            return null;
        }
        J.FieldAccess testClass = (J.FieldAccess) wrapped.getArguments().get(0);
        if (!"class".equals(testClass.getSimpleName()) || !TypeUtils.isOfType(testClass.getTarget().getType(), owner.getType())) {
            return null;
        }
        List<J.MethodDeclaration> methods = new ArrayList<>();
        for (Statement statement : decorator.getBody().getStatements()) {
            if (!(statement instanceof J.MethodDeclaration)) {
                return null;
            }
            J.MethodDeclaration method = (J.MethodDeclaration) statement;
            if (!("setUp".equals(method.getSimpleName()) || "tearDown".equals(method.getSimpleName())) ||
                method.getBody() == null || method.getMethodType() == null ||
                method.hasModifier(J.Modifier.Type.Synchronized) ||
                method.getLeadingAnnotations().stream().anyMatch(annotation -> !OVERRIDE_ANNOTATION_MATCHER.matches(annotation)) ||
                !method.getMethodType().getParameterTypes().isEmpty()) {
                return null;
            }
            boolean instanceReference = new JavaIsoVisitor<AtomicBoolean>() {
                @Override
                public J.Identifier visitIdentifier(J.Identifier identifier, AtomicBoolean found) {
                    JavaType.Variable field = identifier.getFieldType();
                    if ("this".equals(identifier.getSimpleName()) || "super".equals(identifier.getSimpleName()) ||
                        field != null && field.getOwner() instanceof JavaType.FullyQualified && !field.hasFlags(Flag.Static) &&
                        TypeUtils.isAssignableTo(((JavaType.FullyQualified) field.getOwner()).getFullyQualifiedName(), decorator.getType())) {
                        found.set(true);
                    }
                    return super.visitIdentifier(identifier, found);
                }

                @Override
                public J.MethodInvocation visitMethodInvocation(J.MethodInvocation invocation, AtomicBoolean found) {
                    JavaType.Method type = invocation.getMethodType();
                    if (invocation.getSelect() == null && (type == null || !type.hasFlags(Flag.Static))) {
                        found.set(true);
                    }
                    return super.visitMethodInvocation(invocation, found);
                }
            }.reduce(method.getBody(), new AtomicBoolean()).get();
            if (instanceReference) {
                return null;
            }
            methods.add(method);
        }
        return methods;
    }

    private static class TestCaseVisitor extends JavaIsoVisitor<ExecutionContext> {
        private static final MethodMatcher TEST_CASE_SUPER_MATCHER = new MethodMatcher("junit.framework.TestCase <constructor>(..)");
        private static final Set<String> SUPERTYPES_REMOVED_BY_MIGRATION = new HashSet<>(asList(
                "junit.framework.TestCase", "junit.framework.Assert", "junit.framework.Test"));

        private final Set<String> redundant;
        private final Map<String, String> convertible;

        TestCaseVisitor(Set<String> redundant, Map<String, String> convertible) {
            this.redundant = redundant;
            this.convertible = convertible;
        }

        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
            if (!isSupertypeTestCase(classDecl.getType())) {
                return classDecl;
            }
            Set<String> methodNames = new HashSet<>();
            if (classDecl.getType() != null) {
                classDecl.getType().getVisibleMethods().forEachRemaining(method -> methodNames.add(method.getName()));
            }
            for (Statement statement : classDecl.getBody().getStatements()) {
                if (statement instanceof J.MethodDeclaration) {
                    methodNames.add(((J.MethodDeclaration) statement).getSimpleName());
                }
            }
            J.ClassDeclaration withFixtures = classDecl.withBody(classDecl.getBody().withStatements(
                    ListUtils.flatMap(classDecl.getBody().getStatements(), statement -> {
                        if (!(statement instanceof J.MethodDeclaration) || !isSuite((J.MethodDeclaration) statement)) {
                            return statement;
                        }
                        List<J.MethodDeclaration> lifecycleMethods = testSetupLifecycleMethods((J.MethodDeclaration) statement, classDecl);
                        if (lifecycleMethods == null) {
                            return statement;
                        }
                        maybeRemoveImport("junit.extensions.TestSetup");
                        maybeRemoveImport("junit.framework.Test");
                        maybeRemoveImport("junit.framework.TestSuite");
                        return ListUtils.mapFirst(ListUtils.map(lifecycleMethods, method -> {
                            boolean setup = "setUp".equals(method.getSimpleName());
                            String baseName = setup ? "beforeAll" : "afterAll";
                            String name = baseName;
                            for (int suffix = 1; !methodNames.add(name); suffix++) {
                                name = baseName + suffix;
                            }
                            JavaType.Method methodType = method.getMethodType();
                            if (methodType != null) {
                                methodType = methodType.withName(name).withDeclaringType(classDecl.getType())
                                        .withFlags(EnumSet.of(Flag.Public, Flag.Static));
                            }
                            String annotation = setup ? "BeforeAll" : "AfterAll";
                            Cursor bodyCursor = new Cursor(getCursor(), classDecl.getBody());
                            J.MethodDeclaration lifecycle = JavaTemplate.builder("@" + annotation + " public static void " + name + "() {}")
                                    .javaParser(JavaParser.fromJavaVersion().classpathFromResources(ctx, "junit-jupiter-api-5"))
                                    .imports("org.junit.jupiter.api." + annotation).build()
                                    .apply(new Cursor(bodyCursor, method), method.getCoordinates().replace());
                            lifecycle = lifecycle.withBody(method.getBody()).withThrows(method.getThrows())
                                    .withPrefix(method.getPrefix()).withMethodType(methodType)
                                    .withName(lifecycle.getName().withType(methodType));
                            maybeAddImport("org.junit.jupiter.api." + annotation);
                            return maybeAutoFormat(method, lifecycle, ctx, bodyCursor);
                        }), first -> first.withPrefix(statement.getPrefix()));
                    })));
            J.ClassDeclaration cd = super.visitClassDeclaration(withFixtures, ctx);
            if (cd.getExtends() != null && cd.getExtends().getType() != null) {
                JavaType.FullyQualified fullQualifiedExtension = TypeUtils.asFullyQualified(cd.getExtends().getType());
                if (fullQualifiedExtension != null && "junit.framework.TestCase".equals(fullQualifiedExtension.getFullyQualifiedName())) {
                    cd = cd.withExtends(null);
                }
            }
            maybeRemoveImport("junit.framework.TestCase");
            return cd;
        }

        @Override
        public J.@Nullable MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
            J.MethodDeclaration md = super.visitMethodDeclaration(method, ctx);
            updateCursor(md);

            JavaType.Method constructorType = md.getMethodType();
            if (md.isConstructor() && constructorType != null && redundant.contains(MethodMatcher.methodPattern(constructorType))) {
                J.ClassDeclaration owner = getCursor().firstEnclosingOrThrow(J.ClassDeclaration.class);
                J.MethodDeclaration noArgumentConstructor = owner.getBody().getStatements().stream()
                        .filter(J.MethodDeclaration.class::isInstance)
                        .map(J.MethodDeclaration.class::cast)
                        .filter(other -> other.isConstructor() && redundant.contains(constructorSignature(other.getMethodType())))
                        .min(Comparator.comparingInt(other -> other.getMethodType() == null ? Integer.MAX_VALUE : other.getMethodType().getParameterTypes().size()))
                        .orElse(md);
                if (!md.getId().equals(noArgumentConstructor.getId())) {
                    return null;
                }
                boolean hasOtherConstructors = owner.getBody().getStatements().stream()
                        .filter(J.MethodDeclaration.class::isInstance)
                        .map(J.MethodDeclaration.class::cast)
                        .anyMatch(other -> other.isConstructor() && !redundant.contains(constructorSignature(other.getMethodType())));
                if (!hasOtherConstructors && md.hasModifier(J.Modifier.Type.Public) && md.getLeadingAnnotations().isEmpty()) {
                    return null;
                }
                md = md.withParameters(emptyList())
                        .withMethodType(constructorType.withParameterTypes(emptyList()).withParameterNames(emptyList()));
            }

            if (isStringConstructor(md) && md.getMethodType() != null &&
                convertible.containsKey(md.getMethodType().getDeclaringType().getFullyQualifiedName())) {
                md = md.withParameters(emptyList())
                        .withMethodType(md.getMethodType().withParameterNames(emptyList()).withParameterTypes(emptyList()));
                if (md.getBody() != null && md.getBody().getStatements().isEmpty() && md.getLeadingAnnotations().isEmpty()) {
                    return null;
                }
            }

            if (isStringConstructor(md) && md.getBody() != null && !md.getBody().getStatements().isEmpty()) {
                md = SearchResult.found(md, "JUnit Jupiter cannot resolve this String constructor parameter; migrate the test name and constructor callers manually");
            } else if (onlyConstructorNeedsArguments(md)) {
                md = SearchResult.found(md, "JUnit Jupiter cannot instantiate a test class whose only constructor takes a String; migrate the remaining constructor callers manually");
            }

            // Remove suite() methods that return junit.framework.Test or TestSuite
            if (isSuite(md)) {
                maybeRemoveImport("junit.framework.Test");
                maybeRemoveImport("junit.framework.TestSuite");
                return null;
            }

            if (md.getSimpleName().startsWith("test") && md.getLeadingAnnotations().stream().noneMatch(JUNIT_TEST_ANNOTATION_MATCHER::matches)) {
                md = updateMethodDeclarationAnnotationAndModifier(md, "@Test", "org.junit.jupiter.api.Test", ctx);
            } else if ("setUp".equals(md.getSimpleName()) && md.getLeadingAnnotations().stream().noneMatch(JUNIT_BEFORE_ANNOTATION_MATCHER::matches)) {
                md = updateMethodDeclarationAnnotationAndModifier(md, "@BeforeEach", "org.junit.jupiter.api.BeforeEach", ctx);
            } else if ("tearDown".equals(md.getSimpleName()) && md.getLeadingAnnotations().stream().noneMatch(JUNIT_AFTER_ANNOTATION_MATCHER::matches)) {
                md = updateMethodDeclarationAnnotationAndModifier(md, "@AfterEach", "org.junit.jupiter.api.AfterEach", ctx);
            }
            return maybeRemoveOverrideAnnotation(md, ctx);
        }

        private static boolean isStringConstructor(J.MethodDeclaration md) {
            return md.isConstructor() && md.getParameters().size() == 1 &&
                   md.getParameters().get(0) instanceof J.VariableDeclarations &&
                   TypeUtils.isString(((J.VariableDeclarations) md.getParameters().get(0)).getType());
        }

        /**
         * A constructor kept only for callers that could not be rewritten, left as the only constructor of a test class
         * JUnit Jupiter instantiates, so that every test in it would fail to run.
         */
        private boolean onlyConstructorNeedsArguments(J.MethodDeclaration md) {
            if (!md.isConstructor() || md.getBody() == null ||
                md.getParameters().stream().noneMatch(J.VariableDeclarations.class::isInstance)) {
                return false;
            }
            List<Statement> statements = md.getBody().getStatements();
            boolean delegatesOnly = statements.isEmpty() || statements.size() == 1 &&
                    statements.get(0) instanceof J.MethodInvocation &&
                    "super".equals(((J.MethodInvocation) statements.get(0)).getSimpleName());
            J.ClassDeclaration owner = getCursor().firstEnclosingOrThrow(J.ClassDeclaration.class);
            return delegatesOnly && !owner.hasModifier(J.Modifier.Type.Abstract) &&
                   owner.getBody().getStatements().stream()
                           .filter(J.MethodDeclaration.class::isInstance)
                           .filter(other -> ((J.MethodDeclaration) other).isConstructor())
                           .count() == 1;
        }

        @Override
        public  J.@Nullable MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
            // If the class no longer extends TestCase there should no longer be calls to TestCase.super()
            if (TEST_CASE_SUPER_MATCHER.matches(method) || "super".equals(method.getSimpleName()) &&
                method.getMethodType() != null && convertible.containsKey(method.getMethodType().getDeclaringType().getFullyQualifiedName()) &&
                isConvertedConstructor(getCursor().firstEnclosing(J.MethodDeclaration.class))) {
                return null;
            }
            return super.visitMethodInvocation(method, ctx);
        }

        private boolean isConvertedConstructor(J.@Nullable MethodDeclaration method) {
            return method != null && method.isConstructor() && method.getMethodType() != null &&
                   convertible.containsKey(method.getMethodType().getDeclaringType().getFullyQualifiedName());
        }

        private J.MethodDeclaration updateMethodDeclarationAnnotationAndModifier(J.MethodDeclaration methodDeclaration, String annotation, String fullyQualifiedAnnotation, ExecutionContext ctx) {
            J.MethodDeclaration md = methodDeclaration;
            if (FindAnnotations.find(methodDeclaration.withBody(null), "@" + fullyQualifiedAnnotation).isEmpty()) {
                md = JavaTemplate.builder(annotation)
                        .javaParser(JavaParser.fromJavaVersion()
                                .classpathFromResources(ctx, "junit-jupiter-api-5"))
                        .imports(fullyQualifiedAnnotation).build()
                        .apply(getCursor(), methodDeclaration.getCoordinates().addAnnotation(Comparator.comparing(J.Annotation::getSimpleName)));
                md = maybeAddPublicModifier(md);
                maybeAddImport(fullyQualifiedAnnotation);
            }
            return md;
        }

        private J.MethodDeclaration maybeAddPublicModifier(J.MethodDeclaration md) {
            List<J.Modifier> modifiers = ListUtils.map(md.getModifiers(), modifier -> {
                if (modifier.getType() == J.Modifier.Type.Protected) {
                    return modifier.withType(J.Modifier.Type.Public);
                }
                return modifier;
            });
            return md.withModifiers(modifiers);
        }

        private J.MethodDeclaration maybeRemoveOverrideAnnotation(J.MethodDeclaration md, ExecutionContext ctx) {
            JavaType.Method methodType = md.getMethodType();
            if (methodType == null ||
                md.getLeadingAnnotations().stream().noneMatch(OVERRIDE_ANNOTATION_MATCHER::matches) ||
                stillOverridesAfterMigration(methodType)) {
                return md;
            }
            J.MethodDeclaration withoutBody = (J.MethodDeclaration) new RemoveAnnotationVisitor(OVERRIDE_ANNOTATION_MATCHER)
                    .visitNonNull(md.withBody(null), ctx, getCursor().getParentOrThrow());
            return withoutBody.withBody(md.getBody());
        }

        private static boolean stillOverridesAfterMigration(JavaType.Method method) {
            Optional<JavaType.Method> overridden = TypeUtils.findOverriddenMethod(method);
            while (overridden.isPresent()) {
                if (!SUPERTYPES_REMOVED_BY_MIGRATION.contains(overridden.get().getDeclaringType().getFullyQualifiedName())) {
                    return true;
                }
                overridden = TypeUtils.findOverriddenMethod(overridden.get());
            }
            return false;
        }
    }
}
