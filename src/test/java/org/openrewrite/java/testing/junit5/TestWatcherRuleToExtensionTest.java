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

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class TestWatcherRuleToExtensionTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec
          .parser(JavaParser.fromJavaVersion()
            .classpathFromResources(new InMemoryExecutionContext(), "junit-4", "junit-jupiter-api-5"))
          .recipe(new TestWatcherRuleToExtension());
    }

    @DocumentExample
    @Test
    void anonymousTestWatcherRule() {
        //language=java
        rewriteRun(
          java(
            """
              import org.junit.Rule;
              import org.junit.Test;
              import org.junit.rules.TestWatcher;
              import org.junit.runner.Description;

              public class SomeTest {
                  @Rule
                  public TestWatcher watcher = new TestWatcher() {
                      @Override
                      protected void succeeded(Description description) {
                          System.out.println("passed " + description.getMethodName());
                      }

                      @Override
                      protected void failed(Throwable e, Description description) {
                          System.out.println("failed " + description.getClassName() + " " + description.getMethodName());
                      }
                  };

                  @Test
                  public void test() {
                  }
              }
              """,
            """
              import org.junit.Test;
              import org.junit.jupiter.api.extension.ExtensionContext;
              import org.junit.jupiter.api.extension.RegisterExtension;
              import org.junit.jupiter.api.extension.TestWatcher;

              public class SomeTest {
                  @RegisterExtension
                  public TestWatcher watcher = new TestWatcher() {
                      @Override
                      public void testSuccessful(ExtensionContext description) {
                          System.out.println("passed " + description.getRequiredTestMethod().getName());
                      }

                      @Override
                      public void testFailed(ExtensionContext description, Throwable e) {
                          System.out.println("failed " + description.getRequiredTestClass().getName() + " " + description.getRequiredTestMethod().getName());
                      }
                  };

                  @Test
                  public void test() {
                  }
              }
              """
          )
        );
    }

    @Test
    void namedSubclassWithStartingAndFinished() {
        //language=java
        rewriteRun(
          java(
            """
              import org.junit.AssumptionViolatedException;
              import org.junit.rules.TestWatcher;
              import org.junit.runner.Description;

              public class LoggingWatcher extends TestWatcher {
                  @Override
                  protected void starting(Description description) {
                      System.out.println("start " + description.getDisplayName());
                  }

                  @Override
                  protected void skipped(AssumptionViolatedException e, Description description) {
                      System.out.println("skipped " + description.getTestClass().getSimpleName());
                  }

                  @Override
                  protected void finished(Description description) {
                      System.out.println("end " + description.getMethodName());
                  }
              }
              """,
            """
              import org.junit.jupiter.api.extension.AfterEachCallback;
              import org.junit.jupiter.api.extension.BeforeEachCallback;
              import org.junit.jupiter.api.extension.ExtensionContext;
              import org.junit.jupiter.api.extension.TestWatcher;

              public class LoggingWatcher implements TestWatcher, BeforeEachCallback, AfterEachCallback {
                  @Override
                  public void beforeEach(ExtensionContext description) {
                      System.out.println("start " + description.getDisplayName());
                  }

                  @Override
                  public void testAborted(ExtensionContext description, Throwable e) {
                      System.out.println("skipped " + description.getRequiredTestClass().getSimpleName());
                  }

                  @Override
                  public void afterEach(ExtensionContext description) {
                      System.out.println("end " + description.getRequiredTestMethod().getName());
                  }
              }
              """
          ),
          java(
            """
              import org.junit.Rule;
              import org.junit.Test;

              public class SomeTest {
                  @Rule
                  public LoggingWatcher watcher = new LoggingWatcher();

                  @Test
                  public void test() {
                  }
              }
              """,
            """
              import org.junit.Test;
              import org.junit.jupiter.api.extension.RegisterExtension;

              public class SomeTest {
                  @RegisterExtension
                  public LoggingWatcher watcher = new LoggingWatcher();

                  @Test
                  public void test() {
                  }
              }
              """
          )
        );
    }

    @Test
    void doNotConvertWhenApplyIsOverridden() {
        //language=java
        rewriteRun(
          java(
            """
              import org.junit.rules.TestWatcher;
              import org.junit.runner.Description;
              import org.junit.runners.model.Statement;

              public class CustomWatcher extends TestWatcher {
                  @Override
                  public Statement apply(Statement base, Description description) {
                      return super.apply(base, description);
                  }

                  @Override
                  protected void failed(Throwable e, Description description) {
                  }
              }
              """
          )
        );
    }

    @Test
    void doNotConvertWhenDescriptionEscapes() {
        //language=java
        rewriteRun(
          java(
            """
              import org.junit.Rule;
              import org.junit.rules.TestWatcher;
              import org.junit.runner.Description;

              public class SomeTest {
                  @Rule
                  public TestWatcher watcher = new TestWatcher() {
                      @Override
                      protected void failed(Throwable e, Description description) {
                          log(description);
                      }
                  };

                  static void log(Description description) {
                  }
              }
              """
          )
        );
    }

    @Test
    void doNotConvertAnonymousWatcherWithStarting() {
        //language=java
        rewriteRun(
          java(
            """
              import org.junit.Rule;
              import org.junit.rules.TestWatcher;
              import org.junit.runner.Description;

              public class SomeTest {
                  @Rule
                  public TestWatcher watcher = new TestWatcher() {
                      @Override
                      protected void starting(Description description) {
                      }
                  };
              }
              """
          )
        );
    }

    @Test
    void doNotConvertWatcherInRuleChain() {
        //language=java
        rewriteRun(
          java(
            """
              import org.junit.Rule;
              import org.junit.rules.RuleChain;
              import org.junit.rules.TestWatcher;
              import org.junit.runner.Description;

              public class SomeTest {
                  @Rule
                  public RuleChain chain = RuleChain.outerRule(new TestWatcher() {
                      @Override
                      protected void failed(Throwable e, Description description) {
                      }
                  });
              }
              """
          )
        );
    }

    @Test
    void doNotConvertClassRule() {
        //language=java
        rewriteRun(
          java(
            """
              import org.junit.ClassRule;
              import org.junit.rules.TestWatcher;
              import org.junit.runner.Description;

              public class SomeTest {
                  @ClassRule
                  public static TestWatcher watcher = new TestWatcher() {
                      @Override
                      protected void failed(Throwable e, Description description) {
                      }
                  };
              }
              """
          )
        );
    }
}
