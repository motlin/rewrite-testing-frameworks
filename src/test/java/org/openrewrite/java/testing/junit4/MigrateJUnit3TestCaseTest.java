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

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.java.ChangeType;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.tree.J;
import org.openrewrite.kotlin.KotlinParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.java.Assertions.mavenProject;
import static org.openrewrite.kotlin.Assertions.kotlin;

class MigrateJUnit3TestCaseTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.parser(JavaParser.fromJavaVersion().classpathFromResources(new InMemoryExecutionContext(), "junit-4"))
          .recipe(new MigrateJUnit3TestCase());
    }

    @DocumentExample
    @Test
    void lifecycleTestsAndAssertions() {
        rewriteRun(
          //language=java
          java(
            """
              import junit.framework.TestCase;

              public class MathTest extends TestCase {
                  protected long value1;

                  @Override
                  protected void setUp() throws Exception {
                      super.setUp();
                      value1 = 2;
                  }

                  public void testAdd() {
                      assertEquals(4, value1 + 2);
                      assertEquals(1.5, 3.0 / 2);
                  }

                  void testNotPublic() {
                  }

                  @Override
                  protected void tearDown() {
                      super.tearDown();
                      value1 = 0;
                  }
              }
              """,
            """
              import org.junit.After;
              import org.junit.Before;
              import org.junit.Test;

              import static org.junit.Assert.assertEquals;

              public class MathTest {
                  protected long value1;

                  @Before
                  public void setUp() throws Exception {
                      value1 = 2;
                  }

                  @Test
                  public void testAdd() {
                      assertEquals(4, value1 + 2);
                      assertEquals(1.5, 3.0 / 2, 0.0);
                  }

                  void testNotPublic() {
                  }

                  @After
                  public void tearDown() {
                      value1 = 0;
                  }
              }
              """
          )
        );
    }

    @Test
    void annotationAfterModifierIsNotAddedTwice() {
        rewriteRun(
          //language=java
          java(
            """
              import junit.framework.TestCase;
              import org.junit.Test;

              public class MathTest extends TestCase {
                  public @Test void testAdd() {
                      assertEquals(4, 2 + 2);
                  }
              }
              """,
            """
              import org.junit.Test;

              import static org.junit.Assert.assertEquals;

              public class MathTest {
                  public @Test void testAdd() {
                      assertEquals(4, 2 + 2);
                  }
              }
              """
          )
        );
    }

    @Test
    void subclassKeepsSuperCallsToMigratedBase() {
        rewriteRun(
          //language=java
          java(
            """
              import junit.framework.TestCase;

              public abstract class BaseTest extends TestCase {
                  @Override
                  protected void setUp() {
                  }
              }
              """,
            """
              import org.junit.Before;

              public abstract class BaseTest {
                  @Before
                  public void setUp() {
                  }
              }
              """
          ),
          //language=java
          java(
            """
              public class ChildTest extends BaseTest {
                  @Override
                  protected void setUp() {
                      super.setUp();
                  }

                  public void testIt() {
                      assertTrue(true);
                  }
              }
              """,
            """
              import org.junit.Before;
              import org.junit.Test;

              import static org.junit.Assert.assertTrue;

              public class ChildTest extends BaseTest {
                  @Override
                  @Before
                  public void setUp() {
                      super.setUp();
                  }

                  @Test
                  public void testIt() {
                      assertTrue(true);
                  }
              }
              """
          )
        );
    }

    @Test
    void getNameReadsTestNameRule() {
        rewriteRun(
          //language=java
          java(
            """
              import junit.framework.TestCase;

              public class NamedTest extends TestCase {
                  public void testIt() {
                      assertEquals("testIt", getName());
                  }
              }
              """,
            """
              import org.junit.Rule;
              import org.junit.Test;
              import org.junit.rules.TestName;

              import static org.junit.Assert.assertEquals;

              public class NamedTest {
                  @Rule
                  public TestName testName = new TestName();

                  @Test
                  public void testIt() {
                      assertEquals("testIt", testName.getMethodName());
                  }
              }
              """
          )
        );
    }

    @Test
    void redundantNameConstructorIsRemovedWithItsCallers() {
        rewriteRun(
          //language=java
          java(
            """
              import junit.framework.TestCase;

              public class MathTest extends TestCase {
                  public MathTest(String testName) {
                      super(testName);
                  }

                  public static void main(String[] args) {
                      new MathTest("FOO").testAdd();
                  }

                  public void testAdd() {
                      assertEquals(2, 1 + 1);
                  }
              }
              """,
            """
              import org.junit.Test;

              import static org.junit.Assert.assertEquals;

              public class MathTest {
                  public static void main(String[] args) {
                      new MathTest().testAdd();
                  }

                  @Test
                  public void testAdd() {
                      assertEquals(2, 1 + 1);
                  }
              }
              """
          ),
          //language=java
          java(
            """
              class Other {
                  void run() {
                      new MathTest("BAR").testAdd();
                  }

                  void run(String name) {
                      new MathTest(name).testAdd();
                  }
              }
              """,
            """
              class Other {
                  void run() {
                      new MathTest().testAdd();
                  }

                  void run(String name) {
                      new MathTest().testAdd();
                  }
              }
              """
          )
        );
    }

    @Test
    void nameConstructorWithInitializationBecomesNoArgument() {
        rewriteRun(
          //language=java
          java(
            """
              import junit.framework.TestCase;

              public class MathTest extends TestCase {
                  private final boolean initialized;

                  public MathTest(String testName) {
                      super(testName);
                      initialized = true;
                  }

                  public void testAdd() {
                      assertTrue(initialized);
                  }
              }
              """,
            """
              import org.junit.Test;

              import static org.junit.Assert.assertTrue;

              public class MathTest {
                  private final boolean initialized;

                  public MathTest() {
                      initialized = true;
                  }

                  @Test
                  public void testAdd() {
                      assertTrue(initialized);
                  }
              }
              """
          )
        );
    }

    @Test
    void fieldNamedLikeTheTestNameDoesNotKeepTheConstructor() {
        rewriteRun(
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
          )
        );
    }

    @Test
    void constructorChainThroughSeveralSubclasses() {
        rewriteRun(
          //language=java
          java(
            """
              import junit.framework.TestCase;

              public abstract class BaseTest extends TestCase {
                  protected boolean ready;

                  public BaseTest(String name) {
                      super(name);
                      ready = true;
                  }
              }
              """,
            """
              public abstract class BaseTest {
                  protected boolean ready;

                  public BaseTest() {
                      ready = true;
                  }
              }
              """
          ),
          //language=java
          java(
            """
              public abstract class MidTest extends BaseTest {
                  public MidTest(String name) {
                      super(name);
                  }
              }
              """,
            """
              public abstract class MidTest extends BaseTest {
              }
              """
          ),
          //language=java
          java(
            """
              public class FooTest extends MidTest {
                  public FooTest(String name) {
                      super(name);
                  }

                  public void testIt() {
                  }
              }
              """,
            """
              import org.junit.Test;

              public class FooTest extends MidTest {
                  @Test
                  public void testIt() {
                  }
              }
              """
          )
        );
    }

    @Test
    void classLeftOnJUnit3StopsPassingTheNameToAMigratedClass() {
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

                  public void testExample() {
                      assertTrue(initialized);
                  }
              }
              """,
            """
              import org.junit.Test;

              import static org.junit.Assert.assertTrue;

              public class InitializedTest {
                  private final boolean initialized;

                  public InitializedTest() {
                      initialized = true;
                  }

                  @Test
                  public void testExample() {
                      assertTrue(initialized);
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
                  @Override
                  public void run(TestResult result) {
                      new InitializedTest("testExample").testExample();
                      super.run(result);
                  }
              }
              """,
            """
              import junit.framework.TestCase;
              import junit.framework.TestResult;

              /*~~(Not migrated from JUnit 3: overrides TestCase.run())~~>*/public class CustomRunnerTest extends TestCase {
                  @Override
                  public void run(TestResult result) {
                      new InitializedTest().testExample();
                      super.run(result);
                  }
              }
              """
          )
        );
    }

    @Test
    void constructorChainThroughSubclass() {
        rewriteRun(
          //language=java
          java(
            """
              import junit.framework.TestCase;

              public abstract class BaseTest extends TestCase {
                  public BaseTest(String name) {
                      super(name);
                  }
              }
              """,
            """
              public abstract class BaseTest {
              }
              """
          ),
          //language=java
          java(
            """
              public class ChildTest extends BaseTest {
                  public ChildTest(String name) {
                      super(name);
                  }

                  public void testIt() {
                  }
              }
              """,
            """
              import org.junit.Test;

              public class ChildTest extends BaseTest {
                  @Test
                  public void testIt() {
                  }
              }
              """
          )
        );
    }

    @Test
    void constructorChainThroughSubclassInDependentModule() {
        rewriteRun(
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

                    public void testAdd() {
                    }
                }
                """,
              """
                package example;

                import org.junit.Test;

                public class MathTest extends BaseTest {
                    @Test
                    public void testAdd() {
                    }
                }
                """
            ),
            //language=java
            java(
              """
                package example;

                class Caller {
                    void run() {
                        new MathTest("math").testAdd();
                    }
                }
                """,
              """
                package example;

                class Caller {
                    void run() {
                        new MathTest().testAdd();
                    }
                }
                """
            )
          )
        );
    }

    @Test
    void subclassNameConstructorWithInitializationBecomesNoArgument() {
        rewriteRun(
          //language=java
          java(
            """
              import junit.framework.TestCase;

              public abstract class BaseTest extends TestCase {
                  public BaseTest(String name) {
                      super(name);
                  }
              }
              """,
            """
              public abstract class BaseTest {
              }
              """
          ),
          //language=java
          java(
            """
              public class MathTest extends BaseTest {
                  private boolean initialized;

                  public MathTest(String name) {
                      super(name);
                      initialized = true;
                  }

                  public void testIt() {
                  }
              }
              """,
            """
              import org.junit.Test;

              public class MathTest extends BaseTest {
                  private boolean initialized;

                  public MathTest() {
                      initialized = true;
                  }

                  @Test
                  public void testIt() {
                  }
              }
              """
          )
        );
    }

    @Test
    void noArgumentConstructorDelegatingToNameConstructorIsRemoved() {
        rewriteRun(
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

                  public void testIt() {
                  }
              }
              """,
            """
              import org.junit.Test;

              public class NoArgumentChainTest {
                  @Test
                  public void testIt() {
                  }
              }
              """
          ),
          //language=java
          java(
            """
              class Other {
                  void run() {
                      new NoArgumentChainTest("test").testIt();
                  }
              }
              """,
            """
              class Other {
                  void run() {
                      new NoArgumentChainTest().testIt();
                  }
              }
              """
          )
        );
    }

    @Test
    void genericNameConstructorIsRemoved() {
        rewriteRun(
          //language=java
          java(
            """
              import junit.framework.TestCase;

              public class GenericNameTest extends TestCase {
                  public <T> GenericNameTest(String name) {
                      super(name);
                  }

                  public void testIt() {
                      new GenericNameTest("test");
                  }
              }
              """,
            """
              import org.junit.Test;

              public class GenericNameTest {
                  @Test
                  public void testIt() {
                      new GenericNameTest();
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

                  public void testIt() {
                      new PrivateGenericNameTest("test");
                  }
              }
              """,
            """
              import org.junit.Test;

              public class PrivateGenericNameTest {
                  @Test
                  public void testIt() {
                      new PrivateGenericNameTest();
                  }
              }
              """
          )
        );
    }

    @Test
    void anonymousSubclassCallerStopsPassingTheName() {
        rewriteRun(
          //language=java
          java(
            """
              import junit.framework.TestCase;

              public class MathTest extends TestCase {
                  public MathTest(String name) {
                      super(name);
                  }

                  public void testAdd() {
                  }

                  static void runAnonymous() {
                      new MathTest("math") {
                      }.testAdd();
                  }
              }
              """,
            """
              import org.junit.Test;

              public class MathTest {
                  @Test
                  public void testAdd() {
                  }

                  static void runAnonymous() {
                      new MathTest() {
                      }.testAdd();
                  }
              }
              """
          )
        );
    }

    @Test
    void memberClassOfNonTestClassLosesNameConstructorAndCallerArgument() {
        rewriteRun(
          //language=java
          java(
            """
              import junit.framework.TestCase;

              public class Outer {
                  public static class InnerTest extends TestCase {
                      public InnerTest(String name) {
                          super(name);
                      }

                      public void testIt() {
                      }
                  }
              }
              """,
            """
              import org.junit.Test;

              public class Outer {
                  public static class InnerTest {
                      @Test
                      public void testIt() {
                      }
                  }
              }
              """
          ),
          //language=java
          java(
            """
              class Caller {
                  void run() {
                      new Outer.InnerTest("inner").testIt();
                  }
              }
              """,
            """
              class Caller {
                  void run() {
                      new Outer.InnerTest().testIt();
                  }
              }
              """
          )
        );
    }

    @Test
    void keepNonAssertionTestCaseReferences() {
        rewriteRun(
          //language=java
          java(
            """
              import junit.framework.TestCase;

              public class FooTest extends TestCase {
                  static boolean isTest(Class<?> c) {
                      return TestCase.class.isAssignableFrom(c);
                  }

                  static String nameOf(TestCase test) {
                      return test.getName();
                  }

                  public void testSomething() {
                      assertTrue(isTest(FooTest.class));
                  }
              }
              """,
            """
              import junit.framework.TestCase;
              import org.junit.Test;

              import static org.junit.Assert.assertTrue;

              public class FooTest {
                  static boolean isTest(Class<?> c) {
                      return TestCase.class.isAssignableFrom(c);
                  }

                  static String nameOf(TestCase test) {
                      return test.getName();
                  }

                  @Test
                  public void testSomething() {
                      assertTrue(isTest(FooTest.class));
                  }
              }
              """
          )
        );
    }

    @Test
    void selfSuiteIsRemoved() {
        rewriteRun(
          //language=java
          java(
            """
              import junit.framework.Test;
              import junit.framework.TestCase;
              import junit.framework.TestSuite;

              public class FooTest extends TestCase {
                  public static Test suite() {
                      return new TestSuite(FooTest.class);
                  }

                  public void testSomething() {
                  }
              }
              """,
            """
              import org.junit.Test;

              public class FooTest {
                  @Test
                  public void testSomething() {
                  }
              }
              """
          )
        );
    }

    @Test
    void selfSuiteInAVariableIsRemoved() {
        rewriteRun(
          //language=java
          java(
            """
              import junit.framework.Test;
              import junit.framework.TestCase;
              import junit.framework.TestSuite;

              public class FooTest extends TestCase {
                  public static Test suite() {
                      TestSuite suite = new TestSuite(FooTest.class);
                      return suite;
                  }

                  public void testSomething() {
                  }
              }
              """,
            """
              import org.junit.Test;

              public class FooTest {
                  @Test
                  public void testSomething() {
                  }
              }
              """
          )
        );
    }

    @Test
    void suiteWithTestSetupBecomesBeforeAndAfterClass() {
        rewriteRun(
          //language=java
          java(
            """
              import junit.extensions.TestSetup;
              import junit.framework.Test;
              import junit.framework.TestCase;
              import junit.framework.TestSuite;

              public class DbTest extends TestCase {
                  static String connection;

                  public static Test suite() {
                      return new TestSetup(new TestSuite(DbTest.class)) {
                          protected void setUp() {
                              connection = "open";
                          }

                          protected void tearDown() {
                              connection = null;
                          }
                      };
                  }

                  public void testQuery() {
                      assertEquals("open", connection);
                  }
              }
              """,
            """
              import org.junit.AfterClass;
              import org.junit.BeforeClass;
              import org.junit.Test;

              import static org.junit.Assert.assertEquals;

              public class DbTest {
                  static String connection;

                  @Test
                  public void testQuery() {
                      assertEquals("open", connection);
                  }

                  @BeforeClass
                  public static void oneTimeSetUp() {
                      connection = "open";
                  }

                  @AfterClass
                  public static void oneTimeTearDown() {
                      connection = null;
                  }
              }
              """
          )
        );
    }

    @Test
    void testSetupNextToPerTestFixtures() {
        rewriteRun(
          //language=java
          java(
            """
              import junit.extensions.TestSetup;
              import junit.framework.Test;
              import junit.framework.TestCase;
              import junit.framework.TestSuite;

              public class MathTest extends TestCase {
                  private static String resource;
                  private static int counter;

                  public static Test suite() {
                      return new TestSetup(new TestSuite(MathTest.class)) {
                          @Override
                          protected void setUp() throws Exception {
                              resource = "open";
                              counter = 1;
                          }

                          @Override
                          protected void tearDown() throws Exception {
                              resource = null;
                          }
                      };
                  }

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
              import org.junit.After;
              import org.junit.AfterClass;
              import org.junit.Before;
              import org.junit.BeforeClass;

              public class MathTest {
                  private static String resource;
                  private static int counter;

                  @Before
                  public void setUp() throws Exception {
                      counter++;
                  }

                  @After
                  public void tearDown() throws Exception {
                      counter--;
                  }

                  @BeforeClass
                  public static void oneTimeSetUp() throws Exception {
                      resource = "open";
                      counter = 1;
                  }

                  @AfterClass
                  public static void oneTimeTearDown() throws Exception {
                      resource = null;
                  }
              }
              """
          )
        );
    }

    @Test
    void oneTimeMethodsDoNotHideInheritedMethods() {
        rewriteRun(
          //language=java
          java(
            """
              import junit.extensions.TestSetup;
              import junit.framework.Test;
              import junit.framework.TestCase;
              import junit.framework.TestSuite;

              class BaseTest extends TestCase {
                  protected static void oneTimeTearDown() {
                  }
              }

              class MathTest extends BaseTest {
                  public static Test suite() {
                      return new TestSetup(new TestSuite(MathTest.class)) {
                          protected void setUp() {
                              System.setProperty("example.mode", "test");
                          }

                          protected void tearDown() {
                              System.clearProperty("example.mode");
                          }
                      };
                  }

                  static void oneTimeSetUp() {
                  }

                  public void testIt() {
                      oneTimeTearDown();
                  }
              }
              """,
            """
              import org.junit.AfterClass;
              import org.junit.BeforeClass;
              import org.junit.Test;

              class BaseTest {
                  protected static void oneTimeTearDown() {
                  }
              }

              class MathTest extends BaseTest {
                  static void oneTimeSetUp() {
                  }

                  @Test
                  public void testIt() {
                      oneTimeTearDown();
                  }

                  @BeforeClass
                  public static void oneTimeSetUp2() {
                      System.setProperty("example.mode", "test");
                  }

                  @AfterClass
                  public static void oneTimeTearDown2() {
                      System.clearProperty("example.mode");
                  }
              }
              """
          )
        );
    }

    @Test
    void aggregateSuiteBecomesSuiteClasses() {
        rewriteRun(
          //language=java
          java(
            """
              import junit.framework.TestCase;

              public class ATest extends TestCase {
                  public void testA() {
                  }
              }
              """,
            """
              import org.junit.Test;

              public class ATest {
                  @Test
                  public void testA() {
                  }
              }
              """
          ),
          //language=java
          java(
            """
              import junit.framework.Test;
              import junit.framework.TestSuite;

              public class AllTests {
                  public static Test suite() {
                      TestSuite suite = new TestSuite("all");
                      suite.addTestSuite(ATest.class);
                      suite.addTest(new TestSuite(BTest.class));
                      return suite;
                  }

                  public static void main(String[] args) {
                      junit.textui.TestRunner.run(suite());
                  }
              }
              """,
            """
              import org.junit.runner.JUnitCore;
              import org.junit.runner.RunWith;
              import org.junit.runners.Suite;

              @RunWith(Suite.class)
              @Suite.SuiteClasses({ATest.class, BTest.class})
              public class AllTests {
                  public static void main(String[] args) {
                      JUnitCore.runClasses(AllTests.class);
                  }
              }
              """
          ),
          //language=java
          java(
            """
              import junit.framework.TestCase;

              public class BTest extends TestCase {
                  public void testB() {
                  }
              }
              """,
            """
              import org.junit.Test;

              public class BTest {
                  @Test
                  public void testB() {
                  }
              }
              """
          )
        );
    }

    @Test
    void replacesTestRunnerMainForClass() {
        rewriteRun(
          //language=java
          java(
            """
              import junit.framework.TestCase;

              public class FooTest extends TestCase {
                  public static void main(String[] args) {
                      junit.textui.TestRunner.run(FooTest.class);
                  }

                  public void testSomething() {
                  }
              }
              """,
            """
              import org.junit.Test;
              import org.junit.runner.JUnitCore;

              public class FooTest {
                  public static void main(String[] args) {
                      JUnitCore.runClasses(FooTest.class);
                  }

                  @Test
                  public void testSomething() {
                  }
              }
              """
          )
        );
    }

    @Test
    void replacesTestRunnerMainWithClassNames() {
        rewriteRun(
          //language=java
          java(
            """
              import junit.framework.TestCase;

              public class FooTest extends TestCase {
                  public static void main(String[] args) {
                      junit.textui.TestRunner.main(new String[]{"FooTest"});
                  }

                  public void testSomething() {
                  }
              }
              """,
            """
              import org.junit.Test;
              import org.junit.runner.JUnitCore;

              public class FooTest {
                  public static void main(String[] args) {
                      JUnitCore.main("FooTest");
                  }

                  @Test
                  public void testSomething() {
                  }
              }
              """
          )
        );
    }

    @Test
    void staticTestMethodBecomesAnInstanceMethod() {
        rewriteRun(
          //language=java
          java(
            """
              import junit.framework.TestCase;

              public class FooTest extends TestCase {
                  public static void testCopy() {
                      assertTrue(true);
                  }
              }
              """,
            """
              import org.junit.Test;

              import static org.junit.Assert.assertTrue;

              public class FooTest {
                  @Test
                  public void testCopy() {
                      assertTrue(true);
                  }
              }
              """
          )
        );
    }

    @Test
    void qualifyTestAnnotationWhenAClassIsNamedTest() {
        rewriteRun(
          //language=java
          java(
            """
              import junit.framework.TestCase;

              public class Test extends TestCase {
                  public void testIt() {
                  }
              }
              """,
            """
              public class Test {
                  @org.junit.Test
                  public void testIt() {
                  }
              }
              """
          )
        );
    }

    @Test
    void suiteOfMemberClassesQualifiesThem() {
        rewriteRun(
          //language=java
          java(
            """
              import junit.framework.Test;
              import junit.framework.TestCase;
              import junit.framework.TestSuite;

              public class ParserTest {
                  public static Test suite() {
                      final TestSuite suite = new TestSuite();
                      suite.addTest(OneTest.suite());
                      return suite;
                  }

                  static class OneTest extends TestCase {
                      public static Test suite() {
                          return new TestSuite(OneTest.class);
                      }

                      public void testIt() {
                      }
                  }
              }
              """,
            """
              import org.junit.Test;
              import org.junit.runner.RunWith;
              import org.junit.runners.Suite;

              @RunWith(Suite.class)
              @Suite.SuiteClasses({ParserTest.OneTest.class})
              public class ParserTest {
                  public static class OneTest {
                      @Test
                      public void testIt() {
                      }
                  }
              }
              """
          )
        );
    }

    @Test
    void annotatedStaticTestMethodBecomesAnInstanceMethod() {
        rewriteRun(
          //language=java
          java(
            """
              import junit.framework.TestCase;
              import org.junit.Test;

              public class FooTest extends TestCase {
                  @Test
                  public static void testCopy() {
                  }
              }
              """,
            """
              import org.junit.Test;

              public class FooTest {
                  @Test
                  public void testCopy() {
                  }
              }
              """
          )
        );
    }

    @Test
    void memberClassWithSuiteMethodThatStaysBecomesPublic() {
        rewriteRun(
          //language=java
          java(
            """
              import junit.framework.Test;
              import junit.framework.TestCase;
              import junit.framework.TestSuite;

              public class ParserTest {
                  public static Test suite() {
                      final TestSuite suite = new TestSuite();
                      suite.addTest(OneTest.suite());
                      return suite;
                  }

                  static class OneTest extends TestCase {
                      public static Test suite() {
                          TestSuite s = new TestSuite(OneTest.class);
                          s.setName("one");
                          return s;
                      }

                      public void testIt() {
                      }
                  }
              }
              """,
            """
              import junit.framework.Test;
              import junit.framework.TestCase;
              import junit.framework.TestSuite;
              import org.junit.runner.RunWith;
              import org.junit.runners.Suite;

              @RunWith(Suite.class)
              @Suite.SuiteClasses({ParserTest.OneTest.class})
              public class ParserTest {
                  /*~~(Not migrated from JUnit 3: its suite() method builds a suite that has no JUnit 4 equivalent)~~>*/public static class OneTest extends TestCase {
                      public static Test suite() {
                          TestSuite s = new TestSuite(OneTest.class);
                          s.setName("one");
                          return s;
                      }

                      public void testIt() {
                      }
                  }
              }
              """
          )
        );
    }

    @Test
    void constructorThatThrowsStays() {
        rewriteRun(
          //language=java
          java(
            """
              import junit.framework.TestCase;

              public class FooTest extends TestCase {
                  public FooTest() throws Exception {
                      super();
                  }

                  public void testIt() {
                  }
              }
              """,
            """
              import org.junit.Test;

              public class FooTest {
                  public FooTest() throws Exception {
                  }

                  @Test
                  public void testIt() {
                  }
              }
              """
          )
        );
    }

    @Test
    void annotatedNonPublicMethodsBecomePublic() {
        rewriteRun(
          //language=java
          java(
            """
              import junit.framework.TestCase;
              import org.junit.After;
              import org.junit.Before;
              import org.junit.Test;

              public class FooTest extends TestCase {
                  @Before
                  protected void setUp() {
                  }

                  @After
                  protected void tearDown() {
                  }

                  @Test
                  void testIt() {
                  }
              }
              """,
            """
              import org.junit.After;
              import org.junit.Before;
              import org.junit.Test;

              public class FooTest {
                  @Before
                  public void setUp() {
                  }

                  @After
                  public void tearDown() {
                  }

                  @Test
                  public void testIt() {
                  }
              }
              """
          )
        );
    }

    @Test
    void beforeClassOnAnInstanceMethodIsDropped() {
        rewriteRun(
          //language=java
          java(
            """
              import junit.framework.TestCase;
              import org.junit.BeforeClass;

              public class FooTest extends TestCase {
                  @BeforeClass
                  public void setUp() {
                  }

                  public void testIt() {
                  }
              }
              """,
            """
              import org.junit.Before;
              import org.junit.Test;

              public class FooTest {
                  @Before
                  public void setUp() {
                  }

                  @Test
                  public void testIt() {
                  }
              }
              """
          )
        );
    }

    @Test
    void removeClassLevelLifecycleAnnotationsThatJUnit3Ignored() {
        rewriteRun(
          //language=java
          java(
            """
              import junit.framework.TestCase;
              import org.junit.AfterClass;
              import org.junit.BeforeClass;

              public class MathTest extends TestCase {
                  @BeforeClass
                  public static void init() {
                  }

                  @AfterClass
                  public static void destroy() {
                  }

                  public void testAdd() {
                  }
              }
              """,
            """
              import org.junit.Test;

              public class MathTest {
                  public static void init() {
                  }

                  public static void destroy() {
                  }

                  @Test
                  public void testAdd() {
                  }
              }
              """
          )
        );
    }

    @Test
    void dropsTestAnnotationOnAHelper() {
        rewriteRun(
          //language=java
          java(
            """
              import junit.framework.TestCase;
              import org.junit.Test;

              public class FooTest extends TestCase {
                  @Test
                  public static int helper(String s) {
                      return 1;
                  }

                  public void testIt() {
                  }
              }
              """,
            """
              import org.junit.Test;

              public class FooTest {
                  public static int helper(String s) {
                      return 1;
                  }

                  @Test
                  public void testIt() {
                  }
              }
              """
          )
        );
    }

    @Nested
    class NoChange {
        @Test
        void leaveClassUsingTheTestNameUntouched() {
            rewriteRun(
              //language=java
              java(
                """
                  import junit.framework.TestCase;

                  public class MathTest extends TestCase {
                      private final String label;

                      public MathTest(String testName) {
                          super(testName);
                          label = "test " + testName;
                      }

                      public void testAdd() {
                          assertEquals(2, 1 + 1);
                      }
                  }
                  """,
                """
                  import junit.framework.TestCase;

                  /*~~(Not migrated from JUnit 3: its (String) constructor uses the test name)~~>*/public class MathTest extends TestCase {
                      private final String label;

                      public MathTest(String testName) {
                          super(testName);
                          label = "test " + testName;
                      }

                      public void testAdd() {
                          assertEquals(2, 1 + 1);
                      }
                  }
                  """
              )
            );
        }

        @Test
        void leaveClassWhoseInstancesAreUsedAsTests() {
            rewriteRun(
              //language=java
              java(
                """
                  import junit.framework.Test;
                  import junit.framework.TestCase;
                  import junit.framework.TestSuite;

                  public class MathTest extends TestCase {
                      public MathTest(String name) {
                          super(name);
                      }

                      public static Test suite() {
                          TestSuite suite = new TestSuite();
                          suite.addTest(new MathTest("testAdd"));
                          return suite;
                      }

                      public void testAdd() {
                      }
                  }
                  """,
                """
                  import junit.framework.Test;
                  import junit.framework.TestCase;
                  import junit.framework.TestSuite;

                  /*~~(Not migrated from JUnit 3: its suite() method builds a suite that has no JUnit 4 equivalent)~~>*/public class MathTest extends TestCase {
                      public MathTest(String name) {
                          super(name);
                      }

                      public static Test suite() {
                          TestSuite suite = new TestSuite();
                          suite.addTest(new MathTest("testAdd"));
                          return suite;
                      }

                      public void testAdd() {
                      }
                  }
                  """
              )
            );
        }

        @Test
        void leaveTestSetupThatCannotBecomeStaticMethods() {
            rewriteRun(
              //language=java
              java(
                """
                  import junit.extensions.TestSetup;
                  import junit.framework.Test;
                  import junit.framework.TestCase;
                  import junit.framework.TestSuite;

                  public class FieldTest extends TestCase {
                      public FieldTest(String name) {
                          super(name);
                      }

                      public static Test suite() {
                          return new TestSetup(new TestSuite(FieldTest.class)) {
                              private String resource;

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

                  /*~~(Not migrated from JUnit 3: its suite() method builds a suite that has no JUnit 4 equivalent)~~>*/public class FieldTest extends TestCase {
                      public FieldTest(String name) {
                          super(name);
                      }

                      public static Test suite() {
                          return new TestSetup(new TestSuite(FieldTest.class)) {
                              private String resource;

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
                  import junit.extensions.TestSetup;
                  import junit.framework.Test;
                  import junit.framework.TestCase;
                  import junit.framework.TestSuite;

                  public class CustomizedSuiteTest extends TestCase {
                      public static Test suite() {
                          return new TestSetup(new TestSuite(CustomizedSuiteTest.class) {
                              public int countTestCases() {
                                  return 1;
                              }
                          }) {
                              protected void setUp() {
                                  System.setProperty("example.mode", "test");
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

                  /*~~(Not migrated from JUnit 3: its suite() method builds a suite that has no JUnit 4 equivalent)~~>*/public class CustomizedSuiteTest extends TestCase {
                      public static Test suite() {
                          return new TestSetup(new TestSuite(CustomizedSuiteTest.class) {
                              public int countTestCases() {
                                  return 1;
                              }
                          }) {
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
                  import junit.extensions.TestSetup;
                  import junit.framework.Test;
                  import junit.framework.TestCase;
                  import junit.framework.TestSuite;

                  public class SuiteVariableTest extends TestCase {
                      public static Test suite() {
                          TestSuite suite = new TestSuite(SuiteVariableTest.class);
                          return new TestSetup(suite) {
                              protected void setUp() {
                                  System.setProperty("example.mode", "test");
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

                  /*~~(Not migrated from JUnit 3: its suite() method builds a suite that has no JUnit 4 equivalent)~~>*/public class SuiteVariableTest extends TestCase {
                      public static Test suite() {
                          TestSuite suite = new TestSuite(SuiteVariableTest.class);
                          return new TestSetup(suite) {
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
                  import junit.extensions.TestSetup;
                  import junit.framework.Test;
                  import junit.framework.TestCase;
                  import junit.framework.TestSuite;

                  public class InstanceReferenceTest extends TestCase {
                      public static Test suite() {
                          return new TestSetup(new TestSuite(InstanceReferenceTest.class)) {
                              protected void setUp() {
                                  this.toString();
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

                  /*~~(Not migrated from JUnit 3: its suite() method builds a suite that has no JUnit 4 equivalent)~~>*/public class InstanceReferenceTest extends TestCase {
                      public static Test suite() {
                          return new TestSetup(new TestSuite(InstanceReferenceTest.class)) {
                              protected void setUp() {
                                  this.toString();
                              }
                          };
                      }
                  }
                  """
              ),
              //language=java
              java(
                """
                  import junit.extensions.TestSetup;
                  import junit.framework.Test;
                  import junit.framework.TestCase;
                  import junit.framework.TestSuite;

                  public class InheritedMethodReferenceTest extends TestCase {
                      public static Test suite() {
                          return new TestSetup(new TestSuite(InheritedMethodReferenceTest.class)) {
                              protected void setUp() {
                                  getTest().countTestCases();
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

                  /*~~(Not migrated from JUnit 3: its suite() method builds a suite that has no JUnit 4 equivalent)~~>*/public class InheritedMethodReferenceTest extends TestCase {
                      public static Test suite() {
                          return new TestSetup(new TestSuite(InheritedMethodReferenceTest.class)) {
                              protected void setUp() {
                                  getTest().countTestCases();
                              }
                          };
                      }
                  }
                  """
              ),
              //language=java
              java(
                """
                  import junit.extensions.TestSetup;
                  import junit.framework.Test;
                  import junit.framework.TestCase;
                  import junit.framework.TestSuite;

                  public class InheritedFieldReferenceTest extends TestCase {
                      public static Test suite() {
                          return new TestSetup(new TestSuite(InheritedFieldReferenceTest.class)) {
                              protected void tearDown() {
                                  fTest.countTestCases();
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

                  /*~~(Not migrated from JUnit 3: its suite() method builds a suite that has no JUnit 4 equivalent)~~>*/public class InheritedFieldReferenceTest extends TestCase {
                      public static Test suite() {
                          return new TestSetup(new TestSuite(InheritedFieldReferenceTest.class)) {
                              protected void tearDown() {
                                  fTest.countTestCases();
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
                      Object fixture = new FieldTest("testExample");
                  }
                  """
              )
            );
        }

        @Test
        void leaveRunTestResultOverrideUntouched() {
            rewriteRun(
              //language=java
              java(
                """
                  import junit.framework.TestCase;
                  import junit.framework.TestResult;

                  public class MultiThreadedTest extends TestCase {
                      private TestResult testResult;

                      public MultiThreadedTest(String name) {
                          super(name);
                      }

                      @Override
                      public void run(TestResult result) {
                          this.testResult = result;
                          super.run(result);
                      }

                      public void testSomething() {
                          assertTrue(true);
                      }
                  }
                  """,
                """
                  import junit.framework.TestCase;
                  import junit.framework.TestResult;

                  /*~~(Not migrated from JUnit 3: overrides TestCase.run())~~>*/public class MultiThreadedTest extends TestCase {
                      private TestResult testResult;

                      public MultiThreadedTest(String name) {
                          super(name);
                      }

                      @Override
                      public void run(TestResult result) {
                          this.testResult = result;
                          super.run(result);
                      }

                      public void testSomething() {
                          assertTrue(true);
                      }
                  }
                  """
              ),
              //language=java
              java(
                """
                  class Caller {
                      void run() {
                          new MultiThreadedTest("testSomething").testSomething();
                      }
                  }
                  """
              )
            );
        }

        @Test
        void leaveSubclassUsingTheTestNameAndItsBaseUntouched() {
            rewriteRun(
              //language=java
              java(
                """
                  import junit.framework.TestCase;

                  public abstract class BaseTest extends TestCase {
                      public BaseTest(String name) {
                          super(name);
                      }
                  }
                  """,
                """
                  import junit.framework.TestCase;

                  /*~~(Not migrated from JUnit 3: its subclass FooTest is not migrated: its (String) constructor uses the test name)~~>*/public abstract class BaseTest extends TestCase {
                      public BaseTest(String name) {
                          super(name);
                      }
                  }
                  """
              ),
              //language=java
              java(
                """
                  public class FooTest extends BaseTest {
                      public FooTest(String name) {
                          super(name);
                          System.out.println(name);
                      }

                      public void testIt() {
                      }
                  }
                  """,
                """
                  /*~~(Not migrated from JUnit 3: its (String) constructor uses the test name)~~>*/public class FooTest extends BaseTest {
                      public FooTest(String name) {
                          super(name);
                          System.out.println(name);
                      }

                      public void testIt() {
                      }
                  }
                  """
              )
            );
        }

        @Test
        void leaveSubclassOfABaseUsingTheTestNameUntouched() {
            rewriteRun(
              //language=java
              java(
                """
                  import junit.framework.TestCase;

                  public abstract class BaseTest extends TestCase {
                      protected final String label;

                      public BaseTest(String name) {
                          super(name);
                          label = name;
                      }
                  }
                  """,
                """
                  import junit.framework.TestCase;

                  /*~~(Not migrated from JUnit 3: its (String) constructor uses the test name)~~>*/public abstract class BaseTest extends TestCase {
                      protected final String label;

                      public BaseTest(String name) {
                          super(name);
                          label = name;
                      }
                  }
                  """
              ),
              //language=java
              java(
                """
                  public class FooTest extends BaseTest {
                      public FooTest(String name) {
                          super(name);
                      }

                      public void testIt() {
                      }
                  }
                  """,
                """
                  /*~~(Not migrated from JUnit 3: extends BaseTest, which is not migrated: its (String) constructor uses the test name)~~>*/public class FooTest extends BaseTest {
                      public FooTest(String name) {
                          super(name);
                      }

                      public void testIt() {
                      }
                  }
                  """
              )
            );
        }

        @Test
        void leaveClassWhoseConstructorIsAMethodReference() {
            rewriteRun(
              //language=java
              java(
                """
                  import junit.framework.TestCase;
                  import java.util.function.Function;

                  public class ReferencedTest extends TestCase {
                      static final Function<String, ReferencedTest> FACTORY = ReferencedTest::new;

                      public ReferencedTest(String name) {
                          super(name);
                      }

                      public void testIt() {
                      }
                  }
                  """,
                """
                  import junit.framework.TestCase;
                  import java.util.function.Function;

                  /*~~(Not migrated from JUnit 3: a constructor call passes an argument with effects that dropping the test name would lose)~~>*/public class ReferencedTest extends TestCase {
                      static final Function<String, ReferencedTest> FACTORY = ReferencedTest::new;

                      public ReferencedTest(String name) {
                          super(name);
                      }

                      public void testIt() {
                      }
                  }
                  """
              )
            );
        }

        @Test
        void leaveClassConstructedWithAnArgumentWithEffects() {
            rewriteRun(
              //language=java
              java(
                """
                  import junit.framework.TestCase;

                  class EffectTest extends TestCase {
                      EffectTest(String name) {
                          super(name);
                      }

                      public void testIt() {
                      }
                  }
                  """,
                """
                  import junit.framework.TestCase;

                  /*~~(Not migrated from JUnit 3: a constructor call passes an argument with effects that dropping the test name would lose)~~>*/class EffectTest extends TestCase {
                      EffectTest(String name) {
                          super(name);
                      }

                      public void testIt() {
                      }
                  }
                  """
              ),
              //language=java
              java(
                """
                  class EffectFactory {
                      private int counter;

                      void run() {
                          new EffectTest(nextName()).testIt();
                      }

                      String nextName() {
                          return "test" + counter++;
                      }
                  }
                  """
              )
            );
        }

        @Test
        void leaveClassCallingSetNameUntouched() {
            rewriteRun(
              //language=java
              java(
                """
                  import junit.framework.TestCase;

                  public class FooTest extends TestCase {
                      public void testSomething() {
                          setName("other");
                      }
                  }
                  """,
                """
                  import junit.framework.TestCase;

                  /*~~(Not migrated from JUnit 3: calls TestCase.setName())~~>*/public class FooTest extends TestCase {
                      public void testSomething() {
                          setName("other");
                      }
                  }
                  """
              )
            );
        }

        @Test
        void leaveSuiteBuiltFromLoops() {
            rewriteRun(
              //language=java
              java(
                """
                  import junit.framework.Test;
                  import junit.framework.TestCase;
                  import junit.framework.TestSuite;

                  public class FooTest extends TestCase {
                      public static Test suite() {
                          TestSuite suite = new TestSuite();
                          for (String name : new String[] {"a", "b"}) {
                              suite.addTest(new FooTest(name));
                          }
                          return suite;
                      }

                      private final String name;

                      public FooTest(String name) {
                          super("testSomething");
                          this.name = name;
                      }

                      public void testSomething() {
                      }
                  }
                  """,
                """
                  import junit.framework.Test;
                  import junit.framework.TestCase;
                  import junit.framework.TestSuite;

                  /*~~(Not migrated from JUnit 3: its (String) constructor passes something other than the test name to super)~~>*/public class FooTest extends TestCase {
                      public static Test suite() {
                          TestSuite suite = new TestSuite();
                          for (String name : new String[] {"a", "b"}) {
                              suite.addTest(new FooTest(name));
                          }
                          return suite;
                      }

                      private final String name;

                      public FooTest(String name) {
                          super("testSomething");
                          this.name = name;
                      }

                      public void testSomething() {
                      }
                  }
                  """
              )
            );
        }

        @Test
        void leaveTestRunnerMainWithOptions() {
            rewriteRun(
              //language=java
              java(
                """
                  import junit.framework.TestCase;

                  public class FooTest extends TestCase {
                      public static void main(String[] args) {
                          junit.textui.TestRunner.main(new String[]{"-wait", "FooTest"});
                      }

                      public void testSomething() {
                      }
                  }
                  """,
                """
                  import junit.framework.TestCase;

                  /*~~(Not migrated from JUnit 3: calls junit.textui.TestRunner.main, which has no JUnit 4 equivalent here)~~>*/public class FooTest extends TestCase {
                      public static void main(String[] args) {
                          junit.textui.TestRunner.main(new String[]{"-wait", "FooTest"});
                      }

                      public void testSomething() {
                      }
                  }
                  """
              )
            );
        }

        @Test
        void leaveClassWhoseInterfaceIsImplementedByTestCase() {
            rewriteRun(
              //language=java
              java(
                """
                  public interface Named {
                      String getName();
                  }
                  """
              ),
              //language=java
              java(
                """
                  import junit.framework.TestCase;

                  public class NamedXaTest extends TestCase implements Named {
                      public void testSomething() {
                      }
                  }
                  """,
                """
                  import junit.framework.TestCase;

                  /*~~(Not migrated from JUnit 3: TestCase provides getName(), which Named asks for)~~>*/public class NamedXaTest extends TestCase implements Named {
                      public void testSomething() {
                      }
                  }
                  """
              )
            );
        }

        @Test
        void keepSuiteCalledByAnAggregateThatStays() {
            rewriteRun(
              //language=java
              java(
                """
                  import junit.framework.Test;
                  import junit.framework.TestCase;
                  import junit.framework.TestSuite;

                  public class AllTest extends TestCase {
                      public AllTest(String name) {
                          super(name);
                      }

                      public static Test suite() {
                          TestSuite suite = new TestSuite(AllTest.class);
                          suite.addTest(OneTest.suite());
                          return suite;
                      }
                  }
                  """,
                """
                  import junit.framework.Test;
                  import junit.framework.TestCase;
                  import junit.framework.TestSuite;

                  /*~~(Not migrated from JUnit 3: its suite() method collects other test classes)~~>*/public class AllTest extends TestCase {
                      public AllTest(String name) {
                          super(name);
                      }

                      public static Test suite() {
                          TestSuite suite = new TestSuite(AllTest.class);
                          suite.addTest(OneTest.suite());
                          return suite;
                      }
                  }
                  """
              ),
              //language=java
              java(
                """
                  import junit.framework.TestCase;
                  import junit.framework.TestSuite;

                  public class OneTest extends TestCase {
                      public static TestSuite suite() {
                          return new TestSuite(OneTest.class);
                      }

                      public void testIt() {
                      }
                  }
                  """,
                """
                  import junit.framework.TestCase;
                  import junit.framework.TestSuite;

                  /*~~(Not migrated from JUnit 3: its suite() method is called)~~>*/public class OneTest extends TestCase {
                      public static TestSuite suite() {
                          return new TestSuite(OneTest.class);
                      }

                      public void testIt() {
                      }
                  }
                  """
              )
            );
        }

        @Test
        void leaveClassPassedAsAJUnit3Test() {
            rewriteRun(
              //language=java
              java(
                """
                  import junit.framework.TestCase;

                  public class FooTest extends TestCase {
                      public void testIt() {
                          Util.run(this);
                      }
                  }
                  """,
                """
                  import junit.framework.TestCase;

                  /*~~(Not migrated from JUnit 3: instances are passed where a junit.framework type is expected)~~>*/public class FooTest extends TestCase {
                      public void testIt() {
                          Util.run(this);
                      }
                  }
                  """
              ),
              //language=java
              java(
                """
                  import junit.framework.TestCase;

                  class Util {
                      static void run(TestCase test) {
                      }
                  }
                  """
              )
            );
        }

        @Test
        void leaveClassWithAnExplicitRunnerUntouched() {
            rewriteRun(
              //language=java
              java(
                """
                  import junit.framework.TestCase;
                  import org.junit.runner.RunWith;
                  import org.junit.runners.JUnit4;

                  @RunWith(JUnit4.class)
                  public class FooTest extends TestCase {
                      public void testIt() {
                      }
                  }
                  """,
                """
                  import junit.framework.TestCase;
                  import org.junit.runner.RunWith;
                  import org.junit.runners.JUnit4;

                  /*~~(Not migrated from JUnit 3: a runner chosen with @RunWith decides which methods run, whereas the TestCase conventions do not apply to it)~~>*/@RunWith(JUnit4.class)
                  public class FooTest extends TestCase {
                      public void testIt() {
                      }
                  }
                  """
              )
            );
        }

        @Test
        void leaveClassWhoseNoArgumentConstructorDoesMore() {
            rewriteRun(
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

                      public void testIt() {
                          new DefaultBehaviorTest("math");
                      }
                  }
                  """,
                """
                  import junit.framework.TestCase;

                  /*~~(Not migrated from JUnit 3: it declares a (String) and a no-argument constructor that differ)~~>*/public class DefaultBehaviorTest extends TestCase {
                      public DefaultBehaviorTest(String name) {
                          super(name);
                      }

                      public DefaultBehaviorTest() {
                          System.out.println("default");
                      }

                      public void testIt() {
                          new DefaultBehaviorTest("math");
                      }
                  }
                  """
              )
            );
        }

        @Test
        void leaveClassWhoseNameIsDeclaredInAnotherModule() {
            rewriteRun(
              mavenProject("first-module",
                //language=java
                java(
                  """
                    import junit.framework.TestCase;

                    public class SharedTest extends TestCase {
                        public SharedTest(String name) {
                            super(name);
                        }

                        public void testIt() {
                            new SharedTest("shared");
                        }
                    }
                    """,
                  """
                    import junit.framework.TestCase;

                    /*~~(Not migrated from JUnit 3: another source file declares a class with the same name)~~>*/public class SharedTest extends TestCase {
                        public SharedTest(String name) {
                            super(name);
                        }

                        public void testIt() {
                            new SharedTest("shared");
                        }
                    }
                    """
                )
              ),
              mavenProject("second-module",
                //language=java
                java(
                  """
                    public class OtherSharedTest {
                        public OtherSharedTest(String name) {
                        }

                        static void create() {
                            new OtherSharedTest("other");
                        }
                    }
                    """,
                  spec -> spec.mapBeforeRecipe(cu -> (J.CompilationUnit) new ChangeType("OtherSharedTest", "SharedTest", false)
                    .getVisitor().visitNonNull(cu, new InMemoryExecutionContext()))
                )
              )
            );
        }

        @Test
        void leaveClassConstructedFromKotlin() {
            rewriteRun(
              spec -> spec.parser(KotlinParser.builder()
                .classpathFromResources(new InMemoryExecutionContext(), "junit-4")
                .dependsOn("class MixedTest(name: String) : junit.framework.TestCase(name) { fun testIt() {} }")),
              //language=java
              java(
                """
                  import junit.framework.TestCase;

                  public class MixedTest extends TestCase {
                      public MixedTest(String name) {
                          super(name);
                      }

                      public void testIt() {
                      }
                  }
                  """,
                """
                  import junit.framework.TestCase;

                  /*~~(Not migrated from JUnit 3: a source file in another language calls its constructor)~~>*/public class MixedTest extends TestCase {
                      public MixedTest(String name) {
                          super(name);
                      }

                      public void testIt() {
                      }
                  }
                  """
              ),
              //language=kotlin
              kotlin(
                """
                  fun runMixedTest() = MixedTest("mixed").testIt()
                  """
              )
            );
        }
    }
}
