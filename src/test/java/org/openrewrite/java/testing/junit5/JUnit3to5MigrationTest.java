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

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class JUnit3to5MigrationTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec
          .parser(JavaParser.fromJavaVersion()
            .classpathFromResources(new InMemoryExecutionContext(),
              "junit-4",
              "hamcrest-3",
              "junit-jupiter-api-5"))
          .recipeFromResources("org.openrewrite.java.testing.junit5.JUnit4to5Migration");
    }

    @DocumentExample
    @Test
    void migratesTestCaseStraightToJupiter() {
        //language=java
        rewriteRun(
          java(
            """
              import junit.framework.TestCase;

              public class MathTest extends TestCase {
                  protected long value;

                  @Override
                  protected void setUp() {
                      value = 2;
                  }

                  public void testAdd() {
                      assertEquals(4, value + 2);
                  }
              }
              """,
            """
              import org.junit.jupiter.api.BeforeEach;
              import org.junit.jupiter.api.Test;

              import static org.junit.jupiter.api.Assertions.assertEquals;

              public class MathTest {
                  protected long value;

                  @BeforeEach
                  public void setUp() {
                      value = 2;
                  }

                  @Test
                  public void testAdd() {
                      assertEquals(4, value + 2);
                  }
              }
              """
          )
        );
    }

    @Test
    void subclassOfMigratedBaseStaticallyImportsAssertions() {
        //language=java
        rewriteRun(
          java(
            """
              import junit.framework.TestCase;

              public abstract class BaseTest extends TestCase {
              }
              """,
            """
              public abstract class BaseTest {
              }
              """
          ),
          java(
            """
              public class ChildTest extends BaseTest {
                  public void testAdd() {
                      assertEquals(4, 2 + 2);
                  }
              }
              """,
            """
              import org.junit.jupiter.api.Test;

              import static org.junit.jupiter.api.Assertions.assertEquals;

              public class ChildTest extends BaseTest {
                  @Test
                  public void testAdd() {
                      assertEquals(4, 2 + 2);
                  }
              }
              """
          )
        );
    }

    @Test
    void keepTestCaseTypeReferencesWhileMigratingAssertions() {
        //language=java
        rewriteRun(
          java(
            """
              import junit.framework.TestCase;

              class SuiteBuilder {
                  boolean isJUnit3Test(Class<?> c, TestCase test) {
                      TestCase.assertNotNull(test);
                      return TestCase.class.isAssignableFrom(c);
                  }
              }
              """,
            """
              import junit.framework.TestCase;
              import org.junit.jupiter.api.Assertions;

              class SuiteBuilder {
                  boolean isJUnit3Test(Class<?> c, TestCase test) {
                      Assertions.assertNotNull(test);
                      return TestCase.class.isAssignableFrom(c);
                  }
              }
              """
          )
        );
    }

    @Test
    void removeExtendsJUnit3Assert() {
        //language=java
        rewriteRun(
          java(
            """
              import junit.framework.Assert;

              class Verify extends Assert {
                  void check(Object o) {
                      assertNotNull(o);
                  }
              }
              """,
            """
              import static org.junit.jupiter.api.Assertions.assertNotNull;

              class Verify {
                  void check(Object o) {
                      assertNotNull(o);
                  }
              }
              """
          )
        );
    }

    @Test
    void testSetupBecomesStaticJupiterLifecycleMethods() {
        //language=java
        rewriteRun(
          java(
            """
              import junit.extensions.TestSetup;
              import junit.framework.Test;
              import junit.framework.TestCase;
              import junit.framework.TestSuite;

              public class MathTest extends TestCase {
                  private static String resource;

                  public static Test suite() {
                      return new TestSetup(new TestSuite(MathTest.class)) {
                          @Override
                          protected void setUp() throws Exception {
                              resource = "open";
                          }

                          @Override
                          protected void tearDown() throws Exception {
                              resource = null;
                          }
                      };
                  }

                  public void testResource() {
                      assertEquals("open", resource);
                  }
              }
              """,
            """
              import org.junit.jupiter.api.AfterAll;
              import org.junit.jupiter.api.BeforeAll;
              import org.junit.jupiter.api.Test;

              import static org.junit.jupiter.api.Assertions.assertEquals;

              public class MathTest {
                  private static String resource;

                  @Test
                  public void testResource() {
                      assertEquals("open", resource);
                  }

                  @BeforeAll
                  public static void oneTimeSetUp() throws Exception {
                      resource = "open";
                  }

                  @AfterAll
                  public static void oneTimeTearDown() throws Exception {
                      resource = null;
                  }
              }
              """
          )
        );
    }

    @Test
    void removeClassLevelLifecycleAnnotationsThatJUnit3Ignored() {
        //language=java
        rewriteRun(
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
              import org.junit.jupiter.api.Test;

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

    @Nested
    class NoChange {
        @Test
        void classJUnit4CannotRepresentStaysJUnit3() {
            // setName has no JUnit 4 equivalent, so the class is left alone rather than half-migrated
            //language=java
            rewriteRun(
              java(
                """
                  import junit.framework.TestCase;

                  public class NamedTest extends TestCase {
                      public void testAdd() {
                          setName("add");
                          assertEquals(4, 2 + 2);
                      }
                  }
                  """,
                """
                  import junit.framework.TestCase;

                  /*~~(Not migrated from JUnit 3: calls TestCase.setName())~~>*/public class NamedTest extends TestCase {
                      public void testAdd() {
                          setName("add");
                          assertEquals(4, 2 + 2);
                      }
                  }
                  """
              )
            );
        }
    }
}
