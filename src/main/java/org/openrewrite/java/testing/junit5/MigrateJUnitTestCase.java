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
import org.openrewrite.java.DeleteMethodArgument;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.RemoveAnnotationVisitor;
import org.openrewrite.java.UseStaticImport;
import org.openrewrite.java.marker.JavaProject;
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
import org.openrewrite.java.tree.TypeTree;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.Markers;
import org.openrewrite.marker.SearchResult;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;

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
        final Map<@Nullable JavaProject, ProjectState> projects = new HashMap<>();
        final Set<UUID> skippedCompilationUnits = new HashSet<>();
        final Set<UUID> unsupportedTestSetupCompilationUnits = new HashSet<>();
        private @Nullable Map<ProjectState, Set<String>> redundant;

        private @Nullable ProjectState constructorOwner(ProjectState project, String signature) {
            if (project.constructors.containsKey(signature)) {
                return project;
            }
            ProjectState owner = null;
            for (ProjectState candidate : projects.values()) {
                if (candidate.constructors.containsKey(signature)) {
                    if (owner != null) {
                        return null;
                    }
                    owner = candidate;
                }
            }
            return owner;
        }

        private @Nullable ProjectState classOwner(ProjectState project, String className) {
            if (project.declaredClasses.contains(className)) {
                return project;
            }
            ProjectState owner = null;
            for (ProjectState candidate : projects.values()) {
                if (!candidate.declaredClasses.contains(className)) {
                    continue;
                }
                if (owner != null) {
                    return null;
                }
                owner = candidate;
            }
            return owner;
        }

        private boolean canConvert(ProjectState project, String className) {
            if (!project.nameCandidates.containsKey(className)) {
                return false;
            }
            for (ProjectState caller : projects.values()) {
                Boolean unsafe = caller.nameReferences.get(className);
                if (unsafe == null) {
                    continue;
                }
                ProjectState owner = classOwner(caller, className);
                if (owner == null || owner == project && unsafe) {
                    return false;
                }
            }
            return true;
        }

        Map<ProjectState, Set<String>> redundantConstructors() {
            if (redundant != null) {
                return redundant;
            }
            Map<ProjectState, Set<String>> retained = new HashMap<>();
            Map<ProjectState, Set<String>> redundant = new HashMap<>();
            for (ProjectState project : projects.values()) {
                retained.put(project, new HashSet<>());
                redundant.put(project, new HashSet<>());
            }
            for (ProjectState project : projects.values()) {
                for (Map.Entry<String, Boolean> reference : project.references.entrySet()) {
                    ProjectState owner = constructorOwner(project, reference.getKey());
                    if (owner == null) {
                        // A caller outside projects declaring the same signature has ambiguous ownership.
                        for (ProjectState candidate : projects.values()) {
                            if (candidate.constructors.containsKey(reference.getKey())) {
                                retained.get(candidate).add(reference.getKey());
                            }
                        }
                    } else if (reference.getValue()) {
                        retained.get(owner).add(reference.getKey());
                    }
                }
            }
            for (ProjectState project : projects.values()) {
                for (ProjectState candidate : projects.values()) {
                    for (Map.Entry<String, Constructor> entry : candidate.constructors.entrySet()) {
                        if (project.nonJavaReferencedClasses.contains(entry.getValue().declaringClass)) {
                            ProjectState owner = constructorOwner(project, entry.getKey());
                            if (owner == null || owner == candidate) {
                                retained.get(candidate).add(entry.getKey());
                            }
                        }
                    }
                }
            }
            boolean changed = true;
            while (changed) {
                changed = false;
                for (ProjectState project : projects.values()) {
                    for (Map.Entry<String, Constructor> entry : project.constructors.entrySet()) {
                        Constructor declaration = entry.getValue();
                        if (!declaration.removable || retained.get(project).contains(entry.getKey()) ||
                            redundant.get(project).contains(entry.getKey())) {
                            continue;
                        }
                        if (hasRedundantBody(project, declaration, redundant)) {
                            changed |= redundant.get(project).add(entry.getKey());
                        }
                    }
                }
            }
            changed = true;
            while (changed) {
                changed = false;
                Map<ProjectState, Set<String>> removed = new HashMap<>();
                for (ProjectState project : projects.values()) {
                    Set<String> signatures = redundant.get(project);
                    Set<String> rejected = new HashSet<>();
                    removed.put(project, rejected);
                    for (String signature : signatures) {
                        Constructor declaration = project.constructors.get(signature);
                        List<Constructor> constructors = project.constructorsByClass.get(declaration.declaringClass);
                        long redundantCount = constructors.stream()
                                .filter(constructor -> signatures.contains(constructor.signature))
                                .count();
                        boolean retainedNoArgumentConstructor = constructors.stream().anyMatch(constructor ->
                                constructor.noArguments && !signatures.contains(constructor.signature));
                        boolean incompatibleConstructors = redundantCount > 1 && constructors.stream()
                                .filter(constructor -> signatures.contains(constructor.signature))
                                .anyMatch(constructor -> constructor.annotated || !constructor.visibility.equals(declaration.visibility));
                        if (retainedNoArgumentConstructor || incompatibleConstructors ||
                            !hasRedundantBody(project, declaration, redundant)) {
                            rejected.add(signature);
                        }
                    }
                }
                for (Map.Entry<ProjectState, Set<String>> entry : removed.entrySet()) {
                    changed |= redundant.get(entry.getKey()).removeAll(entry.getValue());
                }
            }
            this.redundant = redundant;
            return redundant;
        }

        private boolean hasRedundantBody(ProjectState project, Constructor declaration,
                                         Map<ProjectState, Set<String>> redundant) {
            switch (declaration.body) {
                case EMPTY:
                    return true;
                case SAFE_DELEGATION:
                    if (declaration.delegatesToTestCase) {
                        return true;
                    }
                    String signature = declaration.delegationSignature;
                    ProjectState owner = signature == null ? null : constructorOwner(project, signature);
                    return owner != null && redundant.get(owner).contains(signature);
                default:
                    return false;
            }
        }
    }

    static class ProjectState {
        final Map<String, Constructor> constructors = new HashMap<>();
        final Map<String, Boolean> references = new HashMap<>();
        final Set<String> nonJavaReferencedClasses = new HashSet<>();
        final Map<String, List<Constructor>> constructorsByClass = new HashMap<>();
        // Retained single-String-parameter constructors whose name parameter can be dropped, keyed by class
        final Set<String> declaredClasses = new HashSet<>();
        final Map<String, String> nameCandidates = new HashMap<>();
        final Map<String, Boolean> nameReferences = new HashMap<>();
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
         * Declared in a TestCase subclass with a signature that could be dropped: no thrown exceptions, varargs, or generic parameters.
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

        Constructor(J.MethodDeclaration method, JavaType.Method type) {
            signature = MethodMatcher.methodPattern(type);
            declaringClass = type.getDeclaringType().getFullyQualifiedName();
            removable = isSupertypeTestCase(type.getDeclaringType()) && type.getThrownExceptions().isEmpty() &&
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

    private static boolean canRemoveName(J.MethodDeclaration method, J.ClassDeclaration owner, Cursor cursor) {
        if (method.getBody() == null || method.getParameters().size() != 1 ||
            !(method.getParameters().get(0) instanceof J.VariableDeclarations) ||
            !TypeUtils.isString(((J.VariableDeclarations) method.getParameters().get(0)).getType()) ||
            ((J.VariableDeclarations) method.getParameters().get(0)).getVarargs() != null ||
            owner.getExtends() == null || !TypeUtils.isOfClassType(owner.getExtends().getType(), "junit.framework.TestCase") ||
            owner.getBody().getStatements().stream().filter(J.MethodDeclaration.class::isInstance)
                    .map(J.MethodDeclaration.class::cast).filter(J.MethodDeclaration::isConstructor).count() != 1 ||
            method.getBody().getStatements().stream().allMatch(statement -> statement instanceof J.MethodInvocation &&
                    TestCaseVisitor.TEST_CASE_SUPER_MATCHER.matches((J.MethodInvocation) statement))) {
            return false;
        }
        J.VariableDeclarations.NamedVariable nameParameter =
                ((J.VariableDeclarations) method.getParameters().get(0)).getVariables().get(0);
        JavaType.@Nullable Variable nameVariable = nameParameter.getVariableType();
        AtomicBoolean nameUsed = new AtomicBoolean(nameVariable == null);
        new JavaIsoVisitor<AtomicBoolean>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation invocation, AtomicBoolean used) {
                if (TestCaseVisitor.TEST_CASE_SUPER_MATCHER.matches(invocation)) {
                    return invocation;
                }
                return super.visitMethodInvocation(invocation, used);
            }

            @Override
            public J.Identifier visitIdentifier(J.Identifier identifier, AtomicBoolean used) {
                if (identifier.getFieldType() == null ?
                        nameParameter.getSimpleName().equals(identifier.getSimpleName()) :
                        identifier.getFieldType().equals(nameVariable)) {
                    used.set(true);
                }
                return identifier;
            }
        }.visitNonNull(method.getBody(), nameUsed, cursor);
        return !nameUsed.get();
    }

    private JavaIsoVisitor<ExecutionContext> nameScanner(Accumulator acc) {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                acc.projects.computeIfAbsent(cu.getMarkers().findFirst(JavaProject.class).orElse(null), key -> new ProjectState());
                // Preserve the upstream exclusion before considering constructors or rewriting their callers.
                if (new DeclaresMethod<>(TEST_CASE_RUN_MATCHER).visit(cu, ctx) != cu) {
                    acc.skippedCompilationUnits.add(cu.getId());
                }
                return super.visitCompilationUnit(cu, ctx);
            }

            private ProjectState project() {
                @Nullable JavaProject marker = getCursor().firstEnclosingOrThrow(JavaSourceFile.class)
                        .getMarkers().findFirst(JavaProject.class).orElse(null);
                return acc.projects.computeIfAbsent(marker, key -> new ProjectState());
            }

            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDeclaration, ExecutionContext ctx) {
                if (classDeclaration.getType() != null) {
                    project().declaredClasses.add(classDeclaration.getType().getFullyQualifiedName());
                }
                return super.visitClassDeclaration(classDeclaration, ctx);
            }

            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                JavaType.Method type = method.getMethodType();
                if (method.isConstructor() && type != null &&
                    !acc.skippedCompilationUnits.contains(getCursor().firstEnclosingOrThrow(JavaSourceFile.class).getId()) &&
                    getCursor().firstEnclosingOrThrow(JavaSourceFile.class) instanceof J.CompilationUnit &&
                    getCursor().getPathAsStream().filter(J.ClassDeclaration.class::isInstance)
                            .map(J.ClassDeclaration.class::cast).allMatch(owner -> isSupertypeTestCase(owner.getType())) &&
                    canRemoveName(method, getCursor().firstEnclosingOrThrow(J.ClassDeclaration.class), getCursor())) {
                    project().nameCandidates.put(type.getDeclaringType().getFullyQualifiedName(), MethodMatcher.methodPattern(type));
                }
                return super.visitMethodDeclaration(method, ctx);
            }

            @Override
            public J.NewClass visitNewClass(J.NewClass newClass, ExecutionContext ctx) {
                recordConstructor(newClass.getConstructorType(), newClass.getArguments(), true);
                return super.visitNewClass(newClass, ctx);
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                recordConstructor(method.getMethodType(), method.getArguments(), true);
                return super.visitMethodInvocation(method, ctx);
            }

            @Override
            public J.MemberReference visitMemberReference(J.MemberReference memberReference, ExecutionContext ctx) {
                recordConstructor(memberReference.getMethodType(), emptyList(), false);
                return super.visitMemberReference(memberReference, ctx);
            }

            private void recordConstructor(JavaType.@Nullable Method method, List<Expression> arguments, boolean ordinaryCall) {
                if (method == null || !method.isConstructor()) {
                    return;
                }
                boolean unsafe = !ordinaryCall ||
                        acc.skippedCompilationUnits.contains(getCursor().firstEnclosingOrThrow(JavaSourceFile.class).getId()) ||
                        !(getCursor().firstEnclosingOrThrow(JavaSourceFile.class) instanceof J.CompilationUnit) ||
                        arguments.stream().anyMatch(argument -> !(argument instanceof J.Empty || argument instanceof J.Literal ||
                                argument instanceof J.Identifier && ((J.Identifier) argument).getFieldType() != null &&
                                ((J.Identifier) argument).getFieldType().getOwner() instanceof JavaType.Method));
                project().nameReferences.merge(method.getDeclaringType().getFullyQualifiedName(), unsafe, Boolean::logicalOr);
            }
        };
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
                @Nullable JavaProject project = tree.getMarkers().findFirst(JavaProject.class).orElse(null);
                ProjectState state = acc.projects.computeIfAbsent(project, ignored -> new ProjectState());
                if (tree instanceof J.CompilationUnit) {
                    // Preserve upstream's exclusion throughout constructor analysis and caller rewriting.
                    boolean skipped = new DeclaresMethod<>(TEST_CASE_RUN_MATCHER).visit(tree, ctx) != tree;
                    if (skipped) {
                        acc.skippedCompilationUnits.add(tree.getId());
                    }
                    // A fixture that is only marked for manual migration leaves its TestCase untouched,
                    // so its constructors must keep their signatures and callers must keep their arguments
                    UnsupportedTestSetupMarker marker = new UnsupportedTestSetupMarker();
                    marker.visit(tree, ctx, getCursor().getParentOrThrow());
                    if (marker.unsupported) {
                        skipped = true;
                        acc.skippedCompilationUnits.add(tree.getId());
                        acc.unsupportedTestSetupCompilationUnits.add(tree.getId());
                    }
                    nameScanner(acc).visit(tree, ctx, getCursor().getParentOrThrow());
                    return constructorScanner(state, skipped).visitNonNull(tree, ctx, getCursor().getParentOrThrow());
                }
                for (JavaType.Method method : ((JavaSourceFile) tree).getTypesInUse().getUsedMethods()) {
                    if (method.isConstructor()) {
                        state.nonJavaReferencedClasses.add(method.getDeclaringType().getFullyQualifiedName());
                        state.nameReferences.merge(method.getDeclaringType().getFullyQualifiedName(), true, Boolean::logicalOr);
                    }
                }
                return tree;
            }
        };
    }

    private JavaIsoVisitor<ExecutionContext> constructorScanner(ProjectState project, boolean skipped) {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                JavaType.Method type = method.getMethodType();
                if (method.isConstructor() && type != null) {
                    Constructor constructor = new Constructor(method, type);
                    project.constructors.put(constructor.signature, constructor);
                    if (skipped) {
                        project.references.put(constructor.signature, true);
                    }
                    project.constructorsByClass.computeIfAbsent(constructor.declaringClass, ignored -> new ArrayList<>())
                            .add(constructor);
                }
                return super.visitMethodDeclaration(method, ctx);
            }

            @Override
            public J.NewClass visitNewClass(J.NewClass newClass, ExecutionContext ctx) {
                JavaType.Method type = newClass.getConstructorType();
                if (type != null) {
                    project.references.merge(MethodMatcher.methodPattern(type), skipped || !safeArguments(newClass.getArguments()), Boolean::logicalOr);
                }
                return super.visitNewClass(newClass, ctx);
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                JavaType.Method type = method.getMethodType();
                if (type != null && type.isConstructor()) {
                    project.references.merge(MethodMatcher.methodPattern(type), skipped || !safeArguments(method.getArguments()), Boolean::logicalOr);
                }
                return super.visitMethodInvocation(method, ctx);
            }

            @Override
            public J.MemberReference visitMemberReference(J.MemberReference memberRef, ExecutionContext ctx) {
                JavaType.Method type = memberRef.getMethodType();
                if (type != null && type.isConstructor()) {
                    project.references.put(MethodMatcher.methodPattern(type), true);
                }
                return super.visitMemberReference(memberRef, ctx);
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        Map<ProjectState, Set<String>> redundantByProject = acc.redundantConstructors();
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                @Nullable JavaProject marker = cu.getMarkers().findFirst(JavaProject.class).orElse(null);
                ProjectState project = acc.projects.get(marker);
                if (acc.unsupportedTestSetupCompilationUnits.contains(cu.getId()) && project != null) {
                    // Only record the manual-migration marker; leave constructors and their callers alone
                    return (J.CompilationUnit) migrationVisitor(emptySet(), acc, project).visitNonNull(cu, ctx, getCursor().getParentOrThrow());
                }
                if (acc.skippedCompilationUnits.contains(cu.getId())) {
                    return cu;
                }
                Set<String> redundant = new HashSet<>();
                if (project != null) {
                    for (Map.Entry<ProjectState, Set<String>> entry : redundantByProject.entrySet()) {
                        for (String signature : entry.getValue()) {
                            if (acc.constructorOwner(project, signature) == entry.getKey()) {
                                redundant.add(signature);
                            }
                        }
                    }
                }
                J.CompilationUnit c = redundant.isEmpty() ? cu :
                        (J.CompilationUnit) constructorVisitor(redundant).visitNonNull(cu, ctx, getCursor().getParentOrThrow());
                if (project == null) {
                    return c;
                }
                for (String className : project.nameReferences.keySet()) {
                    ProjectState owner = acc.classOwner(project, className);
                    if (owner == null || !acc.canConvert(owner, className)) {
                        continue;
                    }
                    c = (J.CompilationUnit) new DeleteMethodArgument(owner.nameCandidates.get(className), 0).getVisitor()
                            .visitNonNull(c, ctx, getCursor().getParentOrThrow());
                }
                return (J.CompilationUnit) migrationVisitor(redundant, acc, project).visitNonNull(c, ctx, getCursor().getParentOrThrow());
            }
        };
    }

    private JavaIsoVisitor<ExecutionContext> constructorVisitor(Set<String> redundant) {
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
                if (type == null || type.getParameterTypes().isEmpty() || !redundant.contains(MethodMatcher.methodPattern(type))) {
                    return n;
                }
                return n.withArguments(emptyList())
                        .withConstructorType(type.withParameterTypes(emptyList()).withParameterNames(emptyList()));
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
                JavaType.Method type = m.getMethodType();
                if (type == null || type.getParameterTypes().isEmpty() || !redundant.contains(MethodMatcher.methodPattern(type))) {
                    return m;
                }
                return m.withArguments(emptyList())
                        .withMethodType(type.withParameterTypes(emptyList()).withParameterNames(emptyList()));
            }
        };
    }

    private TreeVisitor<?, ExecutionContext> migrationVisitor(Set<String> redundant, Accumulator acc, ProjectState project) {
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
                        UnsupportedTestSetupMarker marker = new UnsupportedTestSetupMarker();
                        J.CompilationUnit checked = (J.CompilationUnit) marker.visitNonNull(cu, ctx, getCursor().getParentTreeCursor());
                        if (marker.unsupported) {
                            return checked;
                        }
                        J.CompilationUnit c = super.visitCompilationUnit(cu, ctx);
                        doAfterVisit(new TestCaseVisitor(redundant, acc, project));
                        // Retarget JUnit 3 static assertions to org.junit.Assert; other references such as TestCase.class
                        // keep their meaning
                        doAfterVisit(new JUnit3AssertionsToAssert());
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

    /**
     * Moves static assertion calls, static imports, and `extends Assert` from the JUnit 3 `junit.framework.TestCase`
     * and `junit.framework.Assert` to `org.junit.Assert`, leaving every other reference to those types alone.
     */
    private static class JUnit3AssertionsToAssert extends JavaIsoVisitor<ExecutionContext> {
        private static final List<String> JUNIT3_ASSERTION_TYPES = asList("junit.framework.TestCase", "junit.framework.Assert");
        private static final String ASSERT = "org.junit.Assert";
        private static final JavaType.FullyQualified ASSERT_TYPE = JavaType.ShallowClass.build(ASSERT);

        @Override
        public J.Import visitImport(J.Import anImport, ExecutionContext ctx) {
            J.Import i = super.visitImport(anImport, ctx);
            if (i.isStatic() && JUNIT3_ASSERTION_TYPES.contains(i.getTypeName())) {
                return i.withQualid(TypeTree.build(ASSERT + "." + i.getQualid().getSimpleName())
                        .withPrefix(i.getQualid().getPrefix()));
            }
            return i;
        }

        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
            J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);
            if (cd.getExtends() != null && TypeUtils.isOfClassType(cd.getExtends().getType(), "junit.framework.Assert")) {
                cd = cd.withExtends(null);
                maybeRemoveImport("junit.framework.Assert");
            }
            // Later recipes would otherwise see the inherited JUnit 3 assertions as methods that shadow the Jupiter ones
            return cd.getType() instanceof JavaType.Class ? cd.withType(withoutJUnit3Ancestors(cd.getType())) : cd;
        }

        private static JavaType.@Nullable FullyQualified withoutJUnit3Ancestors(JavaType.@Nullable FullyQualified type) {
            if (type == null) {
                return null;
            }
            if (type.getFullyQualifiedName().startsWith("junit.framework.")) {
                return withoutJUnit3Ancestors(type.getSupertype());
            }
            if (!(type instanceof JavaType.Class)) {
                return type;
            }
            JavaType.FullyQualified supertype = type.getSupertype();
            JavaType.FullyQualified stripped = withoutJUnit3Ancestors(supertype);
            return stripped == supertype ? type : ((JavaType.Class) type).withSupertype(stripped);
        }

        @Override
        public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
            J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
            JavaType.Method type = m.getMethodType();
            String declaringType = junit3AssertionType(type == null ? null : type.getDeclaringType());
            if (type == null || declaringType == null || !type.hasFlags(Flag.Static)) {
                return m;
            }
            JavaType.Method retargeted = type.withDeclaringType(ASSERT_TYPE);
            m = m.withMethodType(retargeted).withName(m.getName().withType(retargeted));
            String selectType = m.getSelect() == null ? null : junit3AssertionType(m.getSelect().getType());
            if (selectType != null) {
                m = m.withSelect(new J.Identifier(Tree.randomId(), m.getSelect().getPrefix(), Markers.EMPTY, emptyList(),
                        "Assert", ASSERT_TYPE, null));
                maybeAddImport(ASSERT);
                maybeRemoveImport(selectType);
            }
            maybeRemoveImport(declaringType);
            return m;
        }

        private static @Nullable String junit3AssertionType(@Nullable JavaType type) {
            for (String candidate : JUNIT3_ASSERTION_TYPES) {
                if (TypeUtils.isOfClassType(type, candidate)) {
                    return candidate;
                }
            }
            return null;
        }
    }

    /**
     * Marks `suite()` methods wrapping a `TestSetup` fixture that cannot be turned into Jupiter lifecycle methods.
     */
    private static class UnsupportedTestSetupMarker extends JavaIsoVisitor<ExecutionContext> {
        boolean unsupported;

        @Override
        public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
            J.ClassDeclaration owner = getCursor().firstEnclosing(J.ClassDeclaration.class);
            if (owner == null || !isSupertypeTestCase(owner.getType()) || !isSuite(method) ||
                !containsTestSetup(method, getCursor().getParentTreeCursor()) || testSetupLifecycleMethods(method, owner, getCursor()) != null) {
                return super.visitMethodDeclaration(method, ctx);
            }
            unsupported = true;
            return SearchResult.found(method, "Migrate this TestSetup fixture manually to JUnit Jupiter lifecycle methods");
        }
    }

    private static boolean isSuite(J.MethodDeclaration method) {
        return method.hasModifier(J.Modifier.Type.Static) &&
               SUITE_MATCHER.matches(method.getMethodType()) &&
               (TypeUtils.isOfClassType(method.getMethodType().getReturnType(), "junit.framework.Test") ||
                TypeUtils.isOfClassType(method.getMethodType().getReturnType(), "junit.framework.TestSuite"));
    }

    private static boolean containsTestSetup(J.MethodDeclaration method, Cursor parent) {
        boolean[] found = {false};
        new JavaIsoVisitor<boolean[]>() {
            @Override
            public J.NewClass visitNewClass(J.NewClass newClass, boolean[] result) {
                if (TypeUtils.isAssignableTo("junit.extensions.TestSetup", newClass.getType())) {
                    result[0] = true;
                }
                return super.visitNewClass(newClass, result);
            }
        }.visitNonNull(method, found, parent);
        return found[0];
    }

    private static @Nullable List<J.MethodDeclaration> testSetupLifecycleMethods(J.MethodDeclaration suite, J.ClassDeclaration owner, Cursor suiteCursor) {
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
        Cursor returnCursor = new Cursor(new Cursor(suiteCursor, suite.getBody()), returned);
        Cursor decoratorBodyCursor = new Cursor(new Cursor(returnCursor, decorator), decorator.getBody());
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
            boolean[] instanceReference = {false};
            new JavaIsoVisitor<boolean[]>() {
                @Override
                public J.Identifier visitIdentifier(J.Identifier identifier, boolean[] result) {
                    JavaType.Variable field = identifier.getFieldType();
                    if ("this".equals(identifier.getSimpleName()) || "super".equals(identifier.getSimpleName()) ||
                        field != null && field.getOwner() instanceof JavaType.FullyQualified && !field.hasFlags(Flag.Static) &&
                        TypeUtils.isAssignableTo(((JavaType.FullyQualified) field.getOwner()).getFullyQualifiedName(), decorator.getType())) {
                        result[0] = true;
                    }
                    return super.visitIdentifier(identifier, result);
                }

                @Override
                public J.MethodInvocation visitMethodInvocation(J.MethodInvocation invocation, boolean[] result) {
                    JavaType.Method type = invocation.getMethodType();
                    if (invocation.getSelect() == null && (type == null ||
                        !type.hasFlags(Flag.Static))) {
                        result[0] = true;
                    }
                    return super.visitMethodInvocation(invocation, result);
                }
            }.visitNonNull(method.getBody(), instanceReference, new Cursor(decoratorBodyCursor, method));
            if (instanceReference[0]) {
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
        private final Accumulator acc;
        private final ProjectState project;

        TestCaseVisitor(Set<String> redundant, Accumulator acc, ProjectState project) {
            this.redundant = redundant;
            this.acc = acc;
            this.project = project;
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
                        List<J.MethodDeclaration> lifecycleMethods = testSetupLifecycleMethods((J.MethodDeclaration) statement, classDecl,
                                new Cursor(new Cursor(getCursor(), classDecl.getBody()), statement));
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
                                    .contextSensitive()
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

            if (md.isConstructor() && md.getParameters().size() == 1 &&
                md.getParameters().get(0) instanceof J.VariableDeclarations &&
                TypeUtils.isString(((J.VariableDeclarations) md.getParameters().get(0)).getType())) {
                if (md.getMethodType() != null &&
                    acc.canConvert(project, md.getMethodType().getDeclaringType().getFullyQualifiedName())) {
                    md = md.withParameters(emptyList())
                            .withMethodType(md.getMethodType().withParameterNames(emptyList()).withParameterTypes(emptyList()));
                } else {
                    md = SearchResult.found(md, "JUnit Jupiter cannot resolve this String constructor parameter; migrate the test name and constructor callers manually");
                }
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

        @Override
        public  J.@Nullable MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
            // If the class no longer extends TestCase there should no longer be calls to TestCase.super()
            if (TEST_CASE_SUPER_MATCHER.matches(method)) {
                return null;
            }
            return super.visitMethodInvocation(method, ctx);
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
