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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openrewrite.DocumentExample;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.Issue;
import org.openrewrite.SourceFile;
import org.openrewrite.java.ChangeType;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.tree.J;
import org.openrewrite.kotlin.KotlinParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.java.Assertions.mavenProject;
import static org.openrewrite.kotlin.Assertions.kotlin;

class MigrateJUnitTestCaseTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec
          .parser(JavaParser.fromJavaVersion()
            .classpathFromResources(new InMemoryExecutionContext(), "junit-4", "hamcrest-3"))
          .recipe(new MigrateJUnitTestCase());
    }

    @DocumentExample
    @Test
    void convertTestCase() {
        //language=java
        rewriteRun(
          java(
            """
              import junit.framework.TestCase;

              public class MathTest extends TestCase {
                  protected long value1;
                  protected long value2;

                  @Override
                  protected void setUp() {
                      super.setUp();
                      value1 = 2;
                      value2 = 3;
                  }

                  public void testAdd() {
                      setName("primitive test");
                      long result = value1 + value2;
                      assertEquals(5, result);
                      fail("some Failure message");
                  }

                  @Override
                  protected void tearDown() {
                      super.tearDown();
                      value1 = 0;
                      value2 = 0;
                  }
              }
              """,
            """
              import org.junit.jupiter.api.AfterEach;
              import org.junit.jupiter.api.BeforeEach;
              import org.junit.jupiter.api.Test;

              import static org.junit.jupiter.api.Assertions.assertEquals;
              import static org.junit.jupiter.api.Assertions.fail;

              public class MathTest {
                  protected long value1;
                  protected long value2;

                  @BeforeEach
                  public void setUp() {
                      value1 = 2;
                      value2 = 3;
                  }

                  @Test
                  public void testAdd() {
                      //setName("primitive test");
                      long result = value1 + value2;
                      assertEquals(5, result);
                      fail("some Failure message");
                  }

                  @AfterEach
                  public void tearDown() {
                      value1 = 0;
                      value2 = 0;
                  }
              }
              """
          )
        );
    }

    @Test
    void convertExtendedTestCase() {
        //language=java
        rewriteRun(
          java(
            """
              package com.abc;
              import junit.framework.TestCase;
              public abstract class CTest extends TestCase {
                  @Override
                  public void setUp() {}

                  @Override
                  public void tearDown() {}
              }
              """,
            """
              package com.abc;
              import org.junit.jupiter.api.AfterEach;
              import org.junit.jupiter.api.BeforeEach;

              public abstract class CTest {
                  @BeforeEach
                  public void setUp() {}

                  @AfterEach
                  public void tearDown() {}
              }
              """
          ),
          java(
            """
              package com.abc;
              import com.abc.CTest;
              import static org.junit.Assert.assertEquals;
              public class MathTest extends CTest {
                  protected long value1;
                  protected long value2;

                  @Override
                  protected void setUp() {
                      value1 = 2;
                      value2 = 3;
                  }

                  public void testAdd() {
                      long result = value1 + value2;
                      assertEquals(5, result);
                  }

                  @Override
                  protected void tearDown() {
                      value1 = 0;
                      value2 = 0;
                  }
              }
              """,
            """
              package com.abc;
              import com.abc.CTest;
              import org.junit.jupiter.api.AfterEach;
              import org.junit.jupiter.api.BeforeEach;
              import org.junit.jupiter.api.Test;

              import static org.junit.jupiter.api.Assertions.assertEquals;

              public class MathTest extends CTest {
                  protected long value1;
                  protected long value2;

                  @BeforeEach
                  @Override
                  public void setUp() {
                      value1 = 2;
                      value2 = 3;
                  }

                  @Test
                  public void testAdd() {
                      long result = value1 + value2;
                      assertEquals(5, result);
                  }

                  @AfterEach
                  @Override
                  public void tearDown() {
                      value1 = 0;
                      value2 = 0;
                  }
              }
              """
          )
        );
    }

    @Test
    void notTestCaseHasTestCaseAssertion() {
        //language=java
        rewriteRun(
          java(
            """
              import org.junit.Test;

              import static junit.framework.TestCase.assertTrue;

              class AaTest {
                  @Test
                  public void someTest() {
                      assertTrue("assert message", isSameStuff("stuff"));
                  }
                  private boolean isSameStuff(String stuff) {
                      return "stuff".equals(stuff);
                  }
              }
              """,
            """
              import org.junit.Test;

              import static org.junit.jupiter.api.Assertions.assertTrue;

              class AaTest {
                  @Test
                  public void someTest() {
                      assertTrue(isSameStuff("stuff"), "assert message");
                  }
                  private boolean isSameStuff(String stuff) {
                      return "stuff".equals(stuff);
                  }
              }
              """
          )
        );
    }

    @Test
    void notTestCaseHasAssertAssertion() {
        //language=java
        rewriteRun(
          java(
            """
              import org.junit.Test;

              import static junit.framework.Assert.assertTrue;

              class AaTest {
                  @Test
                  public void someTest() {
                      assertTrue("assert message", isSameStuff("stuff"));
                  }
                  private boolean isSameStuff(String stuff) {
                      return "stuff".equals(stuff);
                  }
              }
              """,
            """
              import org.junit.Test;

              import static org.junit.jupiter.api.Assertions.assertTrue;

              class AaTest {
                  @Test
                  public void someTest() {
                      assertTrue(isSameStuff("stuff"), "assert message");
                  }
                  private boolean isSameStuff(String stuff) {
                      return "stuff".equals(stuff);
                  }
              }
              """
          )
        );
    }

    @Test
    void notTestCase() {
        //language=java
        rewriteRun(
          java(
            """
              import org.junit.Test;
              class AaTest {
                  @Test(expected = NumberFormatException.class)
                  public void testSomeNumberStuff() {
                      Double n = Double.valueOf("a");
                  }
              }
              """
          )
        );
    }

    @Test
    void avoidDuplicateAnnotations(){
        rewriteRun(
          spec -> spec.recipes(
            new MigrateJUnitTestCase(),
            new UpdateBeforeAfterAnnotations()
          ),
          //language=java
          java(
            """
              import junit.framework.TestCase;
              import org.junit.After;
              import org.junit.Before;

              public class MathTest extends TestCase {

                  @Before
                  public void setUp() {
                  }

                  @After
                  public void tearDown() {
                  }
              }
              """,
            """
              import org.junit.jupiter.api.AfterEach;
              import org.junit.jupiter.api.BeforeEach;

              public class MathTest {

                  @BeforeEach
                  public void setUp() {
                  }

                  @AfterEach
                  public void tearDown() {
                  }
              }
              """
          )
        );
    }

    @Test
    void caseWithConstructorCallingSuperTestName() {
        //language=java
        rewriteRun(
          java(
            """
              import junit.framework.TestCase;

              public class AppTest extends TestCase {
                  public AppTest(String testName) {
                      super(testName);
                  }
              }
              """,
            """
              public class AppTest {
              }
              """
          )
        );
    }

    @Test
    void migratesRedundantConstructorsAndTheirCallers() {
        rewriteRun(
          spec -> spec.parser(KotlinParser.builder()
            .classpathFromResources(new InMemoryExecutionContext(), "junit-4")
            .dependsOn("class MixedTest(name: String) : junit.framework.TestCase(name)")),
          //language=java
          java(
            """
              import junit.framework.TestCase;

              public class DirectTest extends TestCase {
                  public DirectTest(String name) {
                      super(name);
                  }

                  public static DirectTest create() {
                      return new DirectTest("math");
                  }
              }
              """,
            """
              public class DirectTest {

                  public static DirectTest create() {
                      return new DirectTest();
                  }
              }
              """
          ),
          //language=java
          java(
            """
              import junit.framework.TestCase;

              public class NoArgumentChainTest extends TestCase {
                  public NoArgumentChainTest() {
                      this("default");
                  }

                  public NoArgumentChainTest(String name) {
                      super(name);
                  }

                  public static NoArgumentChainTest create() {
                      return new NoArgumentChainTest("test");
                  }
              }
              """,
            """
              public class NoArgumentChainTest {

                  public static NoArgumentChainTest create() {
                      return new NoArgumentChainTest();
                  }
              }
              """
          ),
          //language=java
          java(
            """
              import junit.framework.TestCase;

              public class MultipleParametersTest extends TestCase {
                  public MultipleParametersTest(String name, int unused) {
                      super(name);
                  }

                  public MultipleParametersTest(String name, int unused, long alsoUnused) {
                      this(name, unused);
                  }

                  public static MultipleParametersTest create() {
                      return new MultipleParametersTest("test", 0, 0L);
                  }
              }
              """,
            """
              public class MultipleParametersTest {

                  public static MultipleParametersTest create() {
                      return new MultipleParametersTest();
                  }
              }
              """
          ),
          //language=java
          java(
            """
              import junit.framework.TestCase;

              public class RetainedOverloadTest extends TestCase {
                  public RetainedOverloadTest() {
                      this("default", 0);
                  }

                  public RetainedOverloadTest(String name, int unused) {
                      super(name);
                  }

                  public RetainedOverloadTest(int value) {
                      this("test", value);
                      System.out.println(value);
                  }
              }
              """,
            """
              public class RetainedOverloadTest {
                  public RetainedOverloadTest() {
                  }

                  public RetainedOverloadTest(int value) {
                      this();
                      System.out.println(value);
                  }
              }
              """
          ),
          //language=java
          java(
            """
              import junit.framework.TestCase;

              public class RetainedDefaultTest extends TestCase {
                  public RetainedDefaultTest() {
                      System.out.println("default");
                  }

                  public RetainedDefaultTest(String name, int unused) {
                      super(name);
                  }

                  public static RetainedDefaultTest create() {
                      return new RetainedDefaultTest("test", 0);
                  }
              }
              """,
            """
              public class RetainedDefaultTest {
                  public RetainedDefaultTest() {
                      System.out.println("default");
                  }

                  public RetainedDefaultTest(String name, int unused) {
                  }

                  public static RetainedDefaultTest create() {
                      return new RetainedDefaultTest("test", 0);
                  }
              }
              """
          ),
          //language=java
          java(
            """
              import junit.framework.TestCase;

              public class UnsafeArgumentTest extends TestCase {
                  public UnsafeArgumentTest(String name, int unused) {
                      super(name);
                  }

                  public static UnsafeArgumentTest create() {
                      return new UnsafeArgumentTest("test", Integer.parseInt("0"));
                  }
              }
              """,
            """
              public class UnsafeArgumentTest {
                  public UnsafeArgumentTest(String name, int unused) {
                  }

                  public static UnsafeArgumentTest create() {
                      return new UnsafeArgumentTest("test", Integer.parseInt("0"));
                  }
              }
              """
          ),
          //language=java
          java(
            """
              import junit.framework.TestCase;

              public class FieldArgumentTest extends TestCase {
                  static int value;

                  public FieldArgumentTest(String name, int unused) {
                      super(name);
                  }

                  public static FieldArgumentTest create() {
                      return new FieldArgumentTest("test", value);
                  }
              }
              """,
            """
              public class FieldArgumentTest {
                  static int value;

                  public FieldArgumentTest(String name, int unused) {
                  }

                  public static FieldArgumentTest create() {
                      return new FieldArgumentTest("test", value);
                  }
              }
              """
          ),
          //language=java
          java(
            """
              import junit.framework.TestCase;

              public class UnsafeDelegationTest extends TestCase {
                  public UnsafeDelegationTest(String name, int unused) {
                      super(name);
                  }

                  public UnsafeDelegationTest() {
                      this("test", Integer.parseInt("0"));
                  }
              }
              """,
            """
              public class UnsafeDelegationTest {
                  public UnsafeDelegationTest(String name, int unused) {
                  }

                  public UnsafeDelegationTest() {
                      this("test", Integer.parseInt("0"));
                  }
              }
              """
          ),
          //language=java
          java(
            """
              import junit.framework.TestCase;

              public class CheckedConstructorTest extends TestCase {
                  public CheckedConstructorTest(String name, int unused) throws java.io.IOException {
                      super(name);
                  }

                  public static CheckedConstructorTest create() throws java.io.IOException {
                      return new CheckedConstructorTest("test", 0);
                  }
              }
              """,
            """
              public class CheckedConstructorTest {
                  public CheckedConstructorTest(String name, int unused) throws java.io.IOException {
                  }

                  public static CheckedConstructorTest create() throws java.io.IOException {
                      return new CheckedConstructorTest("test", 0);
                  }
              }
              """
          ),
          //language=java
          java(
            """
              import junit.framework.TestCase;

              public class PrivateNoArgumentTest extends TestCase {
                  private PrivateNoArgumentTest() {
                      this("test", 0);
                  }

                  public PrivateNoArgumentTest(String name, int unused) {
                      super(name);
                  }

                  public static PrivateNoArgumentTest create() {
                      return new PrivateNoArgumentTest("test", 0);
                  }
              }
              """,
            """
              public class PrivateNoArgumentTest {
                  private PrivateNoArgumentTest() {
                      this("test", 0);
                  }

                  public PrivateNoArgumentTest(String name, int unused) {
                  }

                  public static PrivateNoArgumentTest create() {
                      return new PrivateNoArgumentTest("test", 0);
                  }
              }
              """
          ),
          //language=java
          java(
            """
              import junit.framework.TestCase;

              public class AnnotatedNoArgumentTest extends TestCase {
                  @Deprecated
                  public AnnotatedNoArgumentTest() {
                      this("test", 0);
                  }

                  public AnnotatedNoArgumentTest(String name, int unused) {
                      super(name);
                  }

                  public static AnnotatedNoArgumentTest create() {
                      return new AnnotatedNoArgumentTest("test", 0);
                  }
              }
              """,
            """
              public class AnnotatedNoArgumentTest {
                  @Deprecated
                  public AnnotatedNoArgumentTest() {
                      this("test", 0);
                  }

                  public AnnotatedNoArgumentTest(String name, int unused) {
                  }

                  public static AnnotatedNoArgumentTest create() {
                      return new AnnotatedNoArgumentTest("test", 0);
                  }
              }
              """
          ),
          //language=java
          java(
            """
              import junit.framework.TestCase;

              public class PrivateAnnotatedTest extends TestCase {
                  @Deprecated
                  private PrivateAnnotatedTest(String name, int unused) {
                      super(name);
                  }

                  public static PrivateAnnotatedTest create() {
                      return new PrivateAnnotatedTest("test", 0);
                  }
              }
              """,
            """
              public class PrivateAnnotatedTest {
                  @Deprecated
                  private PrivateAnnotatedTest() {
                  }

                  public static PrivateAnnotatedTest create() {
                      return new PrivateAnnotatedTest();
                  }
              }
              """
          ),
          //language=java
          java(
            """
              import junit.framework.TestCase;

              public class NoArgumentBase extends TestCase {
                  public NoArgumentBase() {
                      super();
                  }
              }
              """,
            """
              public class NoArgumentBase {
              }
              """
          ),
          //language=java
          java(
            """
              public class NoArgumentChild extends NoArgumentBase {
                  public NoArgumentChild(String name, int unused) {
                      super();
                  }

                  public static NoArgumentChild create() {
                      return new NoArgumentChild("test", 0);
                  }
              }
              """,
            """
              public class NoArgumentChild extends NoArgumentBase {

                  public static NoArgumentChild create() {
                      return new NoArgumentChild();
                  }
              }
              """
          ),
          //language=java
          java(
            """
              import junit.framework.TestCase;

              public class VarargsConstructorTest extends TestCase {
                  public VarargsConstructorTest(String name, Object... unused) {
                      super(name);
                  }

                  public static VarargsConstructorTest create() {
                      return new VarargsConstructorTest("test", 0);
                  }
              }
              """,
            """
              public class VarargsConstructorTest {
                  public VarargsConstructorTest(String name, Object... unused) {
                  }

                  public static VarargsConstructorTest create() {
                      return new VarargsConstructorTest("test", 0);
                  }
              }
              """
          ),
          //language=java
          java(
            """
              import junit.framework.TestCase;

              public class GenericConstructorTest extends TestCase {
                  private <T> GenericConstructorTest(String name, T unused) {
                      super(name);
                  }

                  public static GenericConstructorTest create() {
                      return new GenericConstructorTest("test", "unused");
                  }
              }
              """,
            """
              public class GenericConstructorTest {
                  private <T> GenericConstructorTest(String name, T unused) {
                  }

                  public static GenericConstructorTest create() {
                      return new GenericConstructorTest("test", "unused");
                  }
              }
              """
          ),
          //language=java
          java(
            """
              import junit.framework.TestCase;

              public class GenericNameTest extends TestCase {
                  public <T> GenericNameTest(String name) {
                      super(name);
                  }

                  public static GenericNameTest create() {
                      return new GenericNameTest("test");
                  }
              }
              """,
            """
              public class GenericNameTest {

                  public static GenericNameTest create() {
                      return new GenericNameTest();
                  }
              }
              """
          ),
          //language=java
          java(
            """
              import junit.framework.TestCase;

              public class PrivateGenericNameTest extends TestCase {
                  private <T> PrivateGenericNameTest(String name) {
                      super(name);
                  }

                  public static PrivateGenericNameTest create() {
                      return new PrivateGenericNameTest("test");
                  }
              }
              """,
            """
              public class PrivateGenericNameTest {
                  private <T> PrivateGenericNameTest() {
                  }

                  public static PrivateGenericNameTest create() {
                      return new PrivateGenericNameTest();
                  }
              }
              """
          ),
          mavenProject("base-module",
            //language=java
            java(
              """
                package example;
                import junit.framework.TestCase;

                public abstract class BaseTest extends TestCase {
                    public BaseTest(String name) {
                        super(name);
                    }
                }
                """,
              """
                package example;

                public abstract class BaseTest {
                }
                """
            )
          ),
          mavenProject("dependent-module",
            //language=java
            java(
              """
                package example;

                public class MathTest extends BaseTest {
                    public MathTest(String name) {
                        super(name);
                    }
                }
                """,
              """
                package example;

                public class MathTest extends BaseTest {
                }
                """
            ),
            //language=java
            java(
              """
                package example;

                class Caller {
                    MathTest create() {
                        return new MathTest("math");
                    }
                }
                """,
              """
                package example;

                class Caller {
                    MathTest create() {
                        return new MathTest();
                    }
                }
                """
            )
          ),
          //language=java
          java(
            """
              import junit.framework.TestCase;

              public class OverloadedTest extends TestCase {
                  public OverloadedTest(String name) {
                      super(name);
                  }

                  public OverloadedTest(int value) {
                      this("math");
                      System.out.println(value);
                  }
              }
              """,
            """
              public class OverloadedTest {
                  public OverloadedTest() {
                  }

                  public OverloadedTest(int value) {
                      this();
                      System.out.println(value);
                  }
              }
              """
          ),
          //language=java
          java(
            """
              import junit.framework.TestCase;

              public class SideEffectTest extends TestCase {
                  public SideEffectTest(String name) {
                      super(name);
                  }

                  public static SideEffectTest create() {
                      return new SideEffectTest(System.getProperty("example.name"));
                  }
              }
              """,
            """
              public class SideEffectTest {
                  /*~~(JUnit Jupiter cannot resolve this String constructor parameter; migrate the test name and constructor callers manually)~~>*/public SideEffectTest(String name) {
                  }

                  public static SideEffectTest create() {
                      return new SideEffectTest(System.getProperty("example.name"));
                  }
              }
              """
          ),
          //language=java
          java(
            """
              import junit.framework.TestCase;
              import java.util.function.Function;

              public class FactoryTest extends TestCase {
                  public FactoryTest(String name) {
                      super(name);
                  }

                  public static Function<String, FactoryTest> factory() {
                      return FactoryTest::new;
                  }
              }

              class Other {
                  Other(String name) {
                  }

                  static Other create() {
                      return new Other("other");
                  }
              }
              """,
            """
              import java.util.function.Function;

              public class FactoryTest {
                  /*~~(JUnit Jupiter cannot resolve this String constructor parameter; migrate the test name and constructor callers manually)~~>*/public FactoryTest(String name) {
                  }

                  public static Function<String, FactoryTest> factory() {
                      return FactoryTest::new;
                  }
              }

              class Other {
                  Other(String name) {
                  }

                  static Other create() {
                      return new Other("other");
                  }
              }
              """
          ),
          //language=java
          java(
            """
              import junit.framework.TestCase;

              public class DefaultBehaviorTest extends TestCase {
                  public DefaultBehaviorTest(String name) {
                      super(name);
                  }

                  public DefaultBehaviorTest() {
                      System.out.println("default");
                  }

                  public static DefaultBehaviorTest create() {
                      return new DefaultBehaviorTest("math");
                  }
              }
              """,
            """
              public class DefaultBehaviorTest {
                  /*~~(JUnit Jupiter cannot resolve this String constructor parameter; migrate the test name and constructor callers manually)~~>*/public DefaultBehaviorTest(String name) {
                  }

                  public DefaultBehaviorTest() {
                      System.out.println("default");
                  }

                  public static DefaultBehaviorTest create() {
                      return new DefaultBehaviorTest("math");
                  }
              }
              """
          ),
          //language=java
          java(
            """
              import junit.framework.TestCase;

              public class MixedTest extends TestCase {
                  public MixedTest(String name) {
                      super(name);
                  }
              }
              """,
            """
              public class MixedTest {
                  /*~~(JUnit Jupiter cannot resolve this String constructor parameter; migrate the test name and constructor callers manually)~~>*/public MixedTest(String name) {
                  }
              }
              """
          ),
          //language=kotlin
          kotlin(
            """
              fun createMixedTest() = MixedTest("mixed")
              """
          ),
          mavenProject("test-module",
            //language=java
            java(
              """
                import junit.framework.TestCase;

                public class SharedTest extends TestCase {
                    public SharedTest(String name) {
                        super(name);
                    }

                    public static SharedTest create() {
                        return new SharedTest("shared");
                    }
                }
                """,
              """
                public class SharedTest {

                    public static SharedTest create() {
                        return new SharedTest();
                    }
                }
                """
            )
          ),
          mavenProject("unrelated-module",
            //language=java
            java(
              """
                public class OtherSharedTest {
                    private final String name;

                    public OtherSharedTest(String name) {
                        this.name = name;
                    }

                    public static OtherSharedTest create() {
                        return new OtherSharedTest("shared");
                    }
                }
                """,
              spec -> spec.mapBeforeRecipe(cu -> (J.CompilationUnit) new ChangeType("OtherSharedTest", "SharedTest", false)
                .getVisitor().visitNonNull(cu, new InMemoryExecutionContext()))
            )
          ),
          mavenProject("ambiguous-first-module",
            //language=java
            java(
              """
                import junit.framework.TestCase;

                public class AmbiguousTest extends TestCase {
                    public AmbiguousTest(String name) {
                        super(name);
                    }
                }
                """,
              """
                public class AmbiguousTest {
                    /*~~(JUnit Jupiter cannot resolve this String constructor parameter; migrate the test name and constructor callers manually)~~>*/public AmbiguousTest(String name) {
                    }
                }
                """
            )
          ),
          mavenProject("ambiguous-second-module",
            //language=java
            java(
              """
                public class OtherAmbiguousTest {
                    public OtherAmbiguousTest(String name) {
                    }
                }
                """,
              spec -> spec.mapBeforeRecipe(cu -> (J.CompilationUnit) new ChangeType("OtherAmbiguousTest", "AmbiguousTest", false)
                .getVisitor().visitNonNull(cu, new InMemoryExecutionContext()))
            )
          ),
          //language=java
          java(
            """
              class AmbiguousCaller {
                  AmbiguousTest create() {
                      return new AmbiguousTest("ambiguous");
                  }
              }
              """
          )
        );
    }

    @Test
    void constructorWithAdditionalStatementsIsKept() {
        //language=java
        rewriteRun(
          java(
            """
              import junit.framework.TestCase;

              public class AppTest extends TestCase {
                  private final String name;
                  public AppTest(String testName) {
                      super(testName);
                      this.name = testName;
                  }
              }
              """,
            """
              public class AppTest {
                  private final String name;
                  /*~~(JUnit Jupiter cannot resolve this String constructor parameter; migrate the test name and constructor callers manually)~~>*/public AppTest(String testName) {
                      this.name = testName;
                  }
              }
              """
          )
        );
    }

    @Test
    void migratesRetainedConstructorsWithoutChangingInitializationOrCallers() {
        rewriteRun(
          spec -> spec.parser(KotlinParser.builder()
            .classpathFromResources(new InMemoryExecutionContext(), "junit-4")
            .dependsOn("class KotlinCalledTest(name: String) : junit.framework.TestCase(name)")),
          //language=java
          java(
            """
              import junit.framework.TestCase;

              public class InitializedTest extends TestCase {
                  private boolean initialized;

                  public InitializedTest(String testName) {
                      super(testName);
                      initialized = true;
                  }

                  public void testAdd() {
                      assertTrue(initialized);
                  }
              }
              """,
            """
              import org.junit.jupiter.api.Test;

              import static org.junit.jupiter.api.Assertions.assertTrue;

              public class InitializedTest {
                  private boolean initialized;

                  public InitializedTest() {
                      initialized = true;
                  }

                  @Test
                  public void testAdd() {
                      assertTrue(initialized);
                  }
              }
              """
          ),
          //language=java
          java(
            """
              import junit.framework.TestCase;

              public class FinalFieldTest extends TestCase {
                  private final StringBuilder log;

                  public FinalFieldTest(String testName) {
                      super(testName);
                      log = new StringBuilder("constructed");
                      log.append(":initialized");
                  }

                  @Override
                  protected void setUp() {
                      log.append(":setUp");
                  }

                  public void testLog() {
                      assertEquals("constructed:initialized:setUp", log.toString());
                  }
              }
              """,
            """
              import org.junit.jupiter.api.BeforeEach;
              import org.junit.jupiter.api.Test;

              import static org.junit.jupiter.api.Assertions.assertEquals;

              public class FinalFieldTest {
                  private final StringBuilder log;

                  public FinalFieldTest() {
                      log = new StringBuilder("constructed");
                      log.append(":initialized");
                  }

                  @BeforeEach
                  public void setUp() {
                      log.append(":setUp");
                  }

                  @Test
                  public void testLog() {
                      assertEquals("constructed:initialized:setUp", log.toString());
                  }
              }
              """
          ),
          //language=java
          java(
            """
              import junit.framework.TestCase;

              public class NamedFinalFieldTest extends TestCase {
                  private final StringBuilder log;

                  public NamedFinalFieldTest(String name) {
                      super(name);
                      log = new StringBuilder("constructed:" + name);
                  }

                  public void testLog() {
                      assertTrue(log.length() > 0);
                  }
              }
              """,
            """
              import org.junit.jupiter.api.Test;

              import static org.junit.jupiter.api.Assertions.assertTrue;

              public class NamedFinalFieldTest {
                  private final StringBuilder log;

                  /*~~(JUnit Jupiter cannot resolve this String constructor parameter; migrate the test name and constructor callers manually)~~>*/public NamedFinalFieldTest(String name) {
                      log = new StringBuilder("constructed:" + name);
                  }

                  @Test
                  public void testLog() {
                      assertTrue(log.length() > 0);
                  }
              }
              """
          ),
          //language=java
          java(
            """
              import junit.framework.TestCase;

              public class CalledTest extends TestCase {
                  private final boolean initialized;

                  public CalledTest(String name) {
                      super(name);
                      initialized = true;
                  }
              }
              """,
            """
              public class CalledTest {
                  private final boolean initialized;

                  public CalledTest() {
                      initialized = true;
                  }
              }
              """
          ),
          //language=java
          java(
            """
              class TestFactory {
                  CalledTest create() {
                      return new CalledTest("testAdd");
                  }
                  CalledTest create(String name) {
                      return new CalledTest(name);
                  }
              }
              """,
            """
              class TestFactory {
                  CalledTest create() {
                      return new CalledTest();
                  }
                  CalledTest create(String name) {
                      return new CalledTest();
                  }
              }
              """
          ),
          //language=java
          java(
            """
              import junit.framework.TestCase;
              import java.util.function.Function;

              public class ReferencedTest extends TestCase {
                  private final boolean initialized;
                  static final Function<String, ReferencedTest> FACTORY = ReferencedTest::new;

                  public ReferencedTest(String name) {
                      super(name);
                      initialized = true;
                  }
              }
              """,
            """
              import java.util.function.Function;

              public class ReferencedTest {
                  private final boolean initialized;
                  static final Function<String, ReferencedTest> FACTORY = ReferencedTest::new;

                  /*~~(JUnit Jupiter cannot resolve this String constructor parameter; migrate the test name and constructor callers manually)~~>*/public ReferencedTest(String name) {
                      initialized = true;
                  }
              }
              """
          ),
          //language=java
          java(
            """
              import junit.framework.TestCase;

              public class OverloadedTest extends TestCase {
                  private final boolean initialized;

                  public OverloadedTest() {
                      initialized = false;
                  }

                  public OverloadedTest(String name) {
                      super(name);
                      initialized = true;
                  }
              }
              """,
            """
              public class OverloadedTest {
                  private final boolean initialized;

                  public OverloadedTest() {
                      initialized = false;
                  }

                  /*~~(JUnit Jupiter cannot resolve this String constructor parameter; migrate the test name and constructor callers manually)~~>*/public OverloadedTest(String name) {
                      initialized = true;
                  }
              }
              """
          ),
          //language=java
          java(
            """
              class UnrelatedTest {
                  private final boolean initialized;

                  public UnrelatedTest(String name) {
                      initialized = true;
                  }
              }
              """
          ),
          //language=java
          java(
            """
              import junit.framework.TestCase;

              public class ShadowedNameTest extends TestCase {
                  private final String name;

                  public ShadowedNameTest(String name) {
                      super(name);
                      this.name = "initialized";
                  }
              }
              """,
            """
              public class ShadowedNameTest {
                  private final String name;

                  public ShadowedNameTest() {
                      this.name = "initialized";
                  }
              }
              """
          ),
          mavenProject("standalone",
            //language=java
            java(
              """
                package example;

                import junit.framework.TestCase;

                public class SharedTest extends TestCase {
                    private final boolean initialized;

                    public SharedTest(String name) {
                        super(name);
                        initialized = true;
                    }
                }
                """,
              """
                package example;

                public class SharedTest {
                    private final boolean initialized;

                    public SharedTest() {
                        initialized = true;
                    }
                }
                """
            )
          ),
          mavenProject("unrelated",
            spec -> spec.mapBeforeRecipe(source -> (SourceFile) new ChangeType(
              "example.UnrelatedSharedTest", "example.SharedTest", false).getVisitor()
              .visitNonNull(source, new InMemoryExecutionContext())),
            //language=java
            java(
              """
                package example;

                public class UnrelatedSharedTest {
                    public UnrelatedSharedTest(String name) {
                    }
                }
                """
            ),
            //language=java
            java(
              """
                package example;

                class UnrelatedSharedTestFactory {
                    UnrelatedSharedTest create() {
                        return new UnrelatedSharedTest("testAdd");
                    }
                }
                """
            )
          ),
          mavenProject("base",
            //language=java
            java(
              """
                import junit.framework.TestCase;

                public class BaseTest extends TestCase {
                    private final boolean initialized;

                    public BaseTest(String name) {
                        super(name);
                        initialized = true;
                    }
                }
                """,
              """
                public class BaseTest {
                    private final boolean initialized;

                    public BaseTest() {
                        initialized = true;
                    }
                }
                """
            )
          ),
          mavenProject("dependent",
            //language=java
            java(
              """
                class DependentFactory {
                    BaseTest create() {
                        return new BaseTest("testAdd");
                    }
                }
                """,
              """
                class DependentFactory {
                    BaseTest create() {
                        return new BaseTest();
                    }
                }
                """
            )
          ),
          //language=kotlin
          kotlin(
            """
              import junit.framework.TestCase

              class KotlinTest(name: String) : TestCase(name) {
                  fun create() = KotlinCalledTest("testAdd")

                  fun testExample() {
                      assertTrue(true)
                  }
              }
              """
          ),
          //language=java
          java(
            """
              class DerivedTest extends CalledTest {
                  DerivedTest(String name) {
                      super(name);
                  }
              }
              """,
            """
              class DerivedTest extends CalledTest {
                  /*~~(JUnit Jupiter cannot resolve this String constructor parameter; migrate the test name and constructor callers manually)~~>*/DerivedTest(String name) {
                      super();
                  }
              }
              """
          ),
          //language=java
          java(
            """
              import junit.framework.TestCase;

              class EffectTest extends TestCase {
                  private final boolean initialized;

                  EffectTest(String name) {
                      super(name);
                      initialized = true;
                  }
              }
              """,
            """
              class EffectTest {
                  private final boolean initialized;

                  /*~~(JUnit Jupiter cannot resolve this String constructor parameter; migrate the test name and constructor callers manually)~~>*/EffectTest(String name) {
                      initialized = true;
                  }
              }
              """
          ),
          //language=java
          java(
            """
              class EffectFactory {
                  private int counter;

                  EffectTest create() {
                      return new EffectTest(nextName());
                  }

                  String nextName() {
                      return "test" + counter++;
                  }
              }
              """
          ),
          //language=java
          java(
            """
              import junit.framework.TestCase;

              public class KotlinCalledTest extends TestCase {
                  private final boolean initialized;

                  public KotlinCalledTest(String name) {
                      super(name);
                      initialized = true;
                  }
              }
              """,
            """
              public class KotlinCalledTest {
                  private final boolean initialized;

                  /*~~(JUnit Jupiter cannot resolve this String constructor parameter; migrate the test name and constructor callers manually)~~>*/public KotlinCalledTest(String name) {
                      initialized = true;
                  }
              }
              """
          ),
          mavenProject("ambiguous-first",
            //language=java
            java(
              """
                import junit.framework.TestCase;

                public class AmbiguousTest extends TestCase {
                    public AmbiguousTest(String name) {
                        super(name);
                        System.out.println("initialized");
                    }
                }
                """,
              """
                public class AmbiguousTest {
                    /*~~(JUnit Jupiter cannot resolve this String constructor parameter; migrate the test name and constructor callers manually)~~>*/public AmbiguousTest(String name) {
                        System.out.println("initialized");
                    }
                }
                """
            )
          ),
          mavenProject("ambiguous-second",
            spec -> spec.mapBeforeRecipe(source -> (SourceFile) new ChangeType(
              "AlternativeAmbiguousTest", "AmbiguousTest", false).getVisitor()
              .visitNonNull(source, new InMemoryExecutionContext())),
            //language=java
            java(
              """
                public class AlternativeAmbiguousTest {
                    public AlternativeAmbiguousTest(String name) {
                    }
                }
                """
            )
          ),
          mavenProject("ambiguous-caller",
            //language=java
            java(
              """
                class AmbiguousFactory {
                    AmbiguousTest create() {
                        return new AmbiguousTest("testAdd");
                    }
                }
                """
            )
          )
        );
    }

    @Test
    void suiteMethodIsRemoved() {
        //language=java
        rewriteRun(
          java(
            """
              import junit.framework.Test;
              import junit.framework.TestCase;
              import junit.framework.TestSuite;

              public class AppTest extends TestCase {
                  public AppTest(String testName) {
                      super(testName);
                  }
                  public static Test suite() {
                      return new TestSuite(AppTest.class);
                  }
                  public void testApp() {
                      assertTrue(true);
                  }
              }
              """,
            """
              import org.junit.jupiter.api.Test;

              import static org.junit.jupiter.api.Assertions.assertTrue;

              public class AppTest {

                  @Test
                  public void testApp() {
                      assertTrue(true);
                  }
              }
              """
          )
        );
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void migrateTestSetupFixtures(boolean compositeMigration) {
        rewriteRun(
          spec -> {
              if (compositeMigration) {
                  spec.recipeFromResources("org.openrewrite.java.testing.junit5.JUnit4to5Migration");
              }
          },
          //language=java
          java(
            """
              package fixture0;

              import junit.extensions.TestSetup;
              import junit.framework.Test;
              import junit.framework.TestCase;
              import junit.framework.TestSuite;

              class MathTest extends TestCase {
                  private static String resource;

                  public static Test suite() {
                      return new TestSetup(new TestSuite(MathTest.class)) {
                          @Override
                          protected void setUp() throws Exception {
                              setUpOnce();
                          }

                          @Override
                          protected void tearDown() throws Exception {
                              tearDownOnce();
                          }
                      };
                  }

                  static void setUpOnce() throws Exception {
                      resource = "open";
                  }

                  static void tearDownOnce() throws Exception {
                      resource = null;
                  }

                  public void testResource() {
                      assertEquals("open", resource);
                  }
              }
              """,
            """
              package fixture0;

              import org.junit.jupiter.api.AfterAll;
              import org.junit.jupiter.api.BeforeAll;
              import org.junit.jupiter.api.Test;

              import static org.junit.jupiter.api.Assertions.assertEquals;

              class MathTest {
                  private static String resource;

                  @BeforeAll
                  public static void beforeAll() throws Exception {
                      setUpOnce();
                  }

                  @AfterAll
                  public static void afterAll() throws Exception {
                      tearDownOnce();
                  }

                  static void setUpOnce() throws Exception {
                      resource = "open";
                  }

                  static void tearDownOnce() throws Exception {
                      resource = null;
                  }

                  @Test
                  public void testResource() {
                      assertEquals("open", resource);
                  }
              }
              """
          ),
          //language=java
          java(
            """
              package fixture1;

              import junit.extensions.TestSetup;
              import junit.framework.Test;
              import junit.framework.TestCase;
              import junit.framework.TestSuite;

              class MathTest extends TestCase {
                  private static String resource;
                  private static int counter;

                  public static Test suite() {
                      return new TestSetup(new TestSuite(MathTest.class)) {
                          @Override
                          protected void setUp() throws Exception {
                              resource = "open";
                              counter = 1;
                              System.setProperty("example.mode", "test");
                          }

                          @Override
                          protected void tearDown() throws Exception {
                              resource = null;
                              System.clearProperty("example.mode");
                          }
                      };
                  }

                  static void beforeAll() {}

                  static void afterAll() {}

                  @Override
                  protected void setUp() throws Exception {
                      super.setUp();
                      counter++;
                  }

                  @Override
                  protected void tearDown() throws Exception {
                      counter--;
                      super.tearDown();
                  }
              }
              """,
            """
              package fixture1;

              import org.junit.jupiter.api.AfterAll;
              import org.junit.jupiter.api.AfterEach;
              import org.junit.jupiter.api.BeforeAll;
              import org.junit.jupiter.api.BeforeEach;

              class MathTest {
                  private static String resource;
                  private static int counter;

                  @BeforeAll
                  public static void beforeAll1() throws Exception {
                      resource = "open";
                      counter = 1;
                      System.setProperty("example.mode", "test");
                  }

                  @AfterAll
                  public static void afterAll1() throws Exception {
                      resource = null;
                      System.clearProperty("example.mode");
                  }

                  static void beforeAll() {}

                  static void afterAll() {}

                  @BeforeEach
                  public void setUp() throws Exception {
                      counter++;
                  }

                  @AfterEach
                  public void tearDown() throws Exception {
                      counter--;
                  }
              }
              """
          ),
          //language=java
          java(
            """
              package fixture2;

              import junit.extensions.TestSetup;
              import junit.framework.Test;
              import junit.framework.TestCase;
              import junit.framework.TestSuite;

              class BaseTest extends TestCase {
                  protected void beforeAll() {}

                  protected static void afterAll() {}
              }

              class MathTest extends BaseTest {
                  public static Test suite() {
                      return new TestSetup(new TestSuite(MathTest.class)) {
                          @Override
                          protected void setUp() {
                              System.setProperty("example.mode", "test");
                          }

                          @Override
                          protected void tearDown() {
                              System.clearProperty("example.mode");
                          }
                      };
                  }
              }
              """,
            """
              package fixture2;

              import org.junit.jupiter.api.AfterAll;
              import org.junit.jupiter.api.BeforeAll;

              class BaseTest {
                  protected void beforeAll() {}

                  protected static void afterAll() {}
              }

              class MathTest extends BaseTest {
                  @BeforeAll
                  public static void beforeAll1() {
                      System.setProperty("example.mode", "test");
                  }

                  @AfterAll
                  public static void afterAll1() {
                      System.clearProperty("example.mode");
                  }
              }
              """
          ),
          //language=java
          java(
            """
              package fixture3;

              import junit.extensions.TestSetup;
              import junit.framework.Test;
              import junit.framework.TestCase;
              import junit.framework.TestSuite;

              class MathTest extends TestCase {
                  public static Test suite() {
                      return new TestSetup(new TestSuite(MathTest.class)) {
                          private String resource;

                          @Override
                          protected void setUp() {
                              resource = "open";
                          }
                      };
                  }
              }
              """,
            """
              package fixture3;

              import junit.extensions.TestSetup;
              import junit.framework.Test;
              import junit.framework.TestCase;
              import junit.framework.TestSuite;

              class MathTest extends TestCase {
                  /*~~(Migrate this TestSetup fixture manually to JUnit Jupiter lifecycle methods)~~>*/public static Test suite() {
                      return new TestSetup(new TestSuite(MathTest.class)) {
                          private String resource;

                          @Override
                          protected void setUp() {
                              resource = "open";
                          }
                      };
                  }
              }
              """
          ),
          //language=java
          java(
            """
              package fixture4;

              import junit.extensions.TestSetup;
              import junit.framework.Test;
              import junit.framework.TestCase;
              import junit.framework.TestSuite;

              class MathTest extends TestCase {
                  public static Test suite() {
                      return new TestSetup(new TestSuite(MathTest.class) {
                          @Override
                          public int countTestCases() {
                              return 1;
                          }
                      }) {
                          @Override
                          protected void setUp() {
                              System.setProperty("example.mode", "test");
                          }
                      };
                  }
              }
              """,
            """
              package fixture4;

              import junit.extensions.TestSetup;
              import junit.framework.Test;
              import junit.framework.TestCase;
              import junit.framework.TestSuite;

              class MathTest extends TestCase {
                  /*~~(Migrate this TestSetup fixture manually to JUnit Jupiter lifecycle methods)~~>*/public static Test suite() {
                      return new TestSetup(new TestSuite(MathTest.class) {
                          @Override
                          public int countTestCases() {
                              return 1;
                          }
                      }) {
                          @Override
                          protected void setUp() {
                              System.setProperty("example.mode", "test");
                          }
                      };
                  }
              }
              """
          ),
          //language=java
          java(
            """
              package references;

              import junit.extensions.TestSetup;
              import junit.framework.Test;
              import junit.framework.TestCase;
              import junit.framework.TestSuite;

              class InstanceReferenceTest extends TestCase {
                  public static Test suite() {
                      return new TestSetup(new TestSuite(InstanceReferenceTest.class)) {
                          @Override
                          protected void setUp() throws Exception {
                              this.toString();
                          }
                      };
                  }
              }

              class InheritedMethodReferenceTest extends TestCase {
                  public static Test suite() {
                      return new TestSetup(new TestSuite(InheritedMethodReferenceTest.class)) {
                          @Override
                          protected void setUp() throws Exception {
                              getTest();
                          }
                      };
                  }
              }

              class InheritedFieldReferenceTest extends TestCase {
                  public static Test suite() {
                      return new TestSetup(new TestSuite(InheritedFieldReferenceTest.class)) {
                          @Override
                          protected void setUp() throws Exception {
                              fTest.countTestCases();
                          }
                      };
                  }
              }

              class SuperReferenceTest extends TestCase {
                  public static Test suite() {
                      return new TestSetup(new TestSuite(SuperReferenceTest.class)) {
                          @Override
                          protected void setUp() throws Exception {
                              super.setUp();
                          }
                      };
                  }
              }
              """,
            """
              package references;

              import junit.extensions.TestSetup;
              import junit.framework.Test;
              import junit.framework.TestCase;
              import junit.framework.TestSuite;

              class InstanceReferenceTest extends TestCase {
                  /*~~(Migrate this TestSetup fixture manually to JUnit Jupiter lifecycle methods)~~>*/public static Test suite() {
                      return new TestSetup(new TestSuite(InstanceReferenceTest.class)) {
                          @Override
                          protected void setUp() throws Exception {
                              this.toString();
                          }
                      };
                  }
              }

              class InheritedMethodReferenceTest extends TestCase {
                  /*~~(Migrate this TestSetup fixture manually to JUnit Jupiter lifecycle methods)~~>*/public static Test suite() {
                      return new TestSetup(new TestSuite(InheritedMethodReferenceTest.class)) {
                          @Override
                          protected void setUp() throws Exception {
                              getTest();
                          }
                      };
                  }
              }

              class InheritedFieldReferenceTest extends TestCase {
                  /*~~(Migrate this TestSetup fixture manually to JUnit Jupiter lifecycle methods)~~>*/public static Test suite() {
                      return new TestSetup(new TestSuite(InheritedFieldReferenceTest.class)) {
                          @Override
                          protected void setUp() throws Exception {
                              fTest.countTestCases();
                          }
                      };
                  }
              }

              class SuperReferenceTest extends TestCase {
                  /*~~(Migrate this TestSetup fixture manually to JUnit Jupiter lifecycle methods)~~>*/public static Test suite() {
                      return new TestSetup(new TestSuite(SuperReferenceTest.class)) {
                          @Override
                          protected void setUp() throws Exception {
                              super.setUp();
                          }
                      };
                  }
              }
              """
          )
        );
    }

    @Test
    void leaveKotlinTestCasesUnchanged() {
        rewriteRun(
          spec -> spec.parser(KotlinParser.builder()
            .classpathFromResources(new InMemoryExecutionContext(), "junit-4", "hamcrest-3")),
          //language=kotlin
          kotlin(
            """
              import junit.framework.TestCase

              class MathTest : TestCase() {
                  override fun setUp() {
                      super.setUp()
                  }
              }
              """
          )
        );
    }

    @Test
    void removeOverrideWhenAlreadyAnnotatedWithBefore() {
        //language=java
        rewriteRun(
          java(
            """
              import junit.framework.TestCase;

              import org.junit.Before;

              public class MathTest extends TestCase {
                  protected long value1;

                  @Override
                  @Before
                  public void setUp() {
                      value1 = 2;
                  }

                  public void testAdd() {
                      assertEquals(2, value1);
                  }
              }
              """,
            """
              import org.junit.Before;
              import org.junit.jupiter.api.Test;

              import static org.junit.jupiter.api.Assertions.assertEquals;

              public class MathTest {
                  protected long value1;

                  @Before
                  public void setUp() {
                      value1 = 2;
                  }

                  @Test
                  public void testAdd() {
                      assertEquals(2, value1);
                  }
              }
              """
          )
        );
    }

    @Test
    void removeOverrideFromOtherTestCaseMethods() {
        //language=java
        rewriteRun(
          java(
            """
              import junit.framework.TestCase;

              public class MathTest extends TestCase {
                  @Override
                  public String getName() {
                      return "math";
                  }

                  @Override
                  public int countTestCases() {
                      return 1;
                  }

                  public void testAdd() {
                      assertEquals(2, 2);
                  }
              }
              """,
            """
              import org.junit.jupiter.api.Test;

              import static org.junit.jupiter.api.Assertions.assertEquals;

              public class MathTest {
                  public String getName() {
                      return "math";
                  }

                  public int countTestCases() {
                      return 1;
                  }

                  @Test
                  public void testAdd() {
                      assertEquals(2, 2);
                  }
              }
              """
          )
        );
    }

    @Test
    void retainOverrideOfObjectMethods() {
        //language=java
        rewriteRun(
          java(
            """
              import junit.framework.TestCase;

              public class MathTest extends TestCase {
                  @Override
                  public String toString() {
                      return "math";
                  }

                  @Override
                  public boolean equals(Object other) {
                      return other instanceof MathTest;
                  }

                  @Override
                  public int hashCode() {
                      return 42;
                  }

                  public void testAdd() {
                      assertEquals(2, 2);
                  }
              }
              """,
            """
              import org.junit.jupiter.api.Test;

              import static org.junit.jupiter.api.Assertions.assertEquals;

              public class MathTest {
                  @Override
                  public String toString() {
                      return "math";
                  }

                  @Override
                  public boolean equals(Object other) {
                      return other instanceof MathTest;
                  }

                  @Override
                  public int hashCode() {
                      return 42;
                  }

                  @Test
                  public void testAdd() {
                      assertEquals(2, 2);
                  }
              }
              """
          )
        );
    }

    @Test
    void retainOverrideOfInterfaceMethod() {
        //language=java
        rewriteRun(
          java(
            """
              package com.abc;
              public interface Named {
                  String describe();
              }
              """
          ),
          java(
            """
              package com.abc;
              import junit.framework.TestCase;

              public class MathTest extends TestCase implements Named {
                  @Override
                  public String describe() {
                      return "math";
                  }

                  public void testAdd() {
                      assertEquals(2, 2);
                  }
              }
              """,
            """
              package com.abc;
              import org.junit.jupiter.api.Test;

              import static org.junit.jupiter.api.Assertions.assertEquals;

              public class MathTest implements Named {
                  @Override
                  public String describe() {
                      return "math";
                  }

                  @Test
                  public void testAdd() {
                      assertEquals(2, 2);
                  }
              }
              """
          )
        );
    }

    @Test
    void retainOverrideInAnonymousClass() {
        //language=java
        rewriteRun(
          java(
            """
              import junit.framework.TestCase;

              public class MathTest extends TestCase {
                  @Override
                  public void setUp() {
                      Runnable runnable = new Runnable() {
                          @Override
                          public void run() {
                          }
                      };
                  }
              }
              """,
            """
              import org.junit.jupiter.api.BeforeEach;

              public class MathTest {
                  @BeforeEach
                  public void setUp() {
                      Runnable runnable = new Runnable() {
                          @Override
                          public void run() {
                          }
                      };
                  }
              }
              """
          )
        );
    }

    @Issue("https://github.com/openrewrite/rewrite-testing-frameworks/issues/1114")
    @Test
    void retainConstructorCalledFromTestCaseOverridingRun() {
        rewriteRun(
          //language=java
          java(
            """
              import junit.framework.TestCase;

              public class RedundantTest extends TestCase {
                  public RedundantTest(String name) {
                      super(name);
                  }
              }
              """,
            """
              public class RedundantTest {
                  /*~~(JUnit Jupiter cannot resolve this String constructor parameter; migrate the test name and constructor callers manually)~~>*/public RedundantTest(String name) {
                  }
              }
              """
          ),
          //language=java
          java(
            """
              import junit.framework.TestCase;
              import junit.framework.TestResult;

              public class CustomRunnerTest extends TestCase {
                  private final RedundantTest child = new RedundantTest("testExample");

                  @Override
                  public void run(TestResult result) {
                      super.run(result);
                  }
              }
              """
          )
        );
    }

    @Test
    void migratesRedundantAndInitializingConstructorsTogether() {
        rewriteRun(
          //language=java
          java(
            """
              import junit.framework.TestCase;

              public class RedundantTest extends TestCase {
                  public RedundantTest(String name) {
                      super(name);
                  }

                  public void testRedundant() {
                  }
              }
              """,
            """
              import org.junit.jupiter.api.Test;

              public class RedundantTest {

                  @Test
                  public void testRedundant() {
                  }
              }
              """
          ),
          //language=java
          java(
            """
              import junit.framework.TestCase;

              public class InitializedTest extends TestCase {
                  private boolean initialized;

                  public InitializedTest(String name) {
                      super(name);
                      initialized = true;
                  }

                  public void testInitialized() {
                  }
              }
              """,
            """
              import org.junit.jupiter.api.Test;

              public class InitializedTest {
                  private boolean initialized;

                  public InitializedTest() {
                      initialized = true;
                  }

                  @Test
                  public void testInitialized() {
                  }
              }
              """
          ),
          //language=java
          java(
            """
              public class Callers {
                  Object a = new RedundantTest("testRedundant");
                  Object b = new InitializedTest("testInitialized");
              }
              """,
            """
              public class Callers {
                  Object a = new RedundantTest();
                  Object b = new InitializedTest();
              }
              """
          )
        );
    }

    @Test
    void retainConstructorsAndCallersOfTestCaseWithUnsupportedTestSetup() {
        rewriteRun(
          //language=java
          java(
            """
              import junit.extensions.TestSetup;
              import junit.framework.Test;
              import junit.framework.TestCase;
              import junit.framework.TestSuite;

              public class FixtureTest extends TestCase {
                  public FixtureTest(String name) {
                      super(name);
                  }

                  public static Test suite() {
                      return new TestSetup(new TestSuite(FixtureTest.class)) {
                          private String resource;

                          @Override
                          protected void setUp() {
                              resource = "open";
                          }
                      };
                  }
              }
              """,
            """
              import junit.extensions.TestSetup;
              import junit.framework.Test;
              import junit.framework.TestCase;
              import junit.framework.TestSuite;

              public class FixtureTest extends TestCase {
                  public FixtureTest(String name) {
                      super(name);
                  }

                  /*~~(Migrate this TestSetup fixture manually to JUnit Jupiter lifecycle methods)~~>*/public static Test suite() {
                      return new TestSetup(new TestSuite(FixtureTest.class)) {
                          private String resource;

                          @Override
                          protected void setUp() {
                              resource = "open";
                          }
                      };
                  }
              }
              """
          ),
          //language=java
          java(
            """
              public class Caller {
                  Object fixture = new FixtureTest("testExample");
              }
              """
          )
        );
    }

    @Issue("https://github.com/openrewrite/rewrite-testing-frameworks/issues/1114")
    @Test
    void retainInitializedConstructorCalledFromTestCaseOverridingRun() {
        rewriteRun(
          //language=java
          java(
            """
              import junit.framework.TestCase;

              public class InitializedTest extends TestCase {
                  private final boolean initialized;

                  public InitializedTest(String name) {
                      super(name);
                      initialized = true;
                  }
              }
              """,
            """
              public class InitializedTest {
                  private final boolean initialized;

                  /*~~(JUnit Jupiter cannot resolve this String constructor parameter; migrate the test name and constructor callers manually)~~>*/public InitializedTest(String name) {
                      initialized = true;
                  }
              }
              """
          ),
          //language=java
          java(
            """
              import junit.framework.TestCase;
              import junit.framework.TestResult;

              public class CustomRunnerTest extends TestCase {
                  private final InitializedTest child = new InitializedTest("testExample");

                  @Override
                  public void run(TestResult result) {
                      super.run(result);
                  }
              }
              """
          )
        );
    }

    @Issue("https://github.com/openrewrite/rewrite-testing-frameworks/issues/1114")
    @Test
    void skipTestCaseOverridingRun() {
        //language=java
        rewriteRun(
          java(
            """
              import junit.framework.AssertionFailedError;
              import junit.framework.TestCase;
              import junit.framework.TestResult;

              public class MultiThreadedTest extends TestCase {

                  private TestResult testResult = null;

                  public MultiThreadedTest(String name) {
                      super(name);
                      testResult = new TestResult();
                  }

                  @Override
                  public void run(TestResult result) {
                      this.testResult = result;
                      super.run(result);
                  }

                  public void handleException(Throwable t) {
                      if (t instanceof AssertionFailedError) {
                          testResult.addFailure(this, (AssertionFailedError) t);
                      } else {
                          testResult.addError(this, t);
                      }
                  }

                  public void testSomething() {
                      assertEquals(2, 1 + 1);
                  }
              }
              """
          ),
          //language=java
          java(
            """
              class Caller {
                  MultiThreadedTest create() {
                      return new MultiThreadedTest("testSomething");
                  }
              }

              class RegularTest extends junit.framework.TestCase {
              }
              """,
            """
              class Caller {
                  MultiThreadedTest create() {
                      return new MultiThreadedTest("testSomething");
                  }
              }

              class RegularTest {
              }
              """
          )
        );
    }
}
