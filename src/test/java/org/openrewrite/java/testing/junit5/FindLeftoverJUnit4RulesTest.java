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

class FindLeftoverJUnit4RulesTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec
          .parser(JavaParser.fromJavaVersion()
            .classpathFromResources(new InMemoryExecutionContext(), "junit-4", "junit-jupiter-api-5"))
          .recipe(new FindLeftoverJUnit4Rules());
    }

    @DocumentExample
    @Test
    void flagsLeftoverRuleField() {
        //language=java
        rewriteRun(
          java(
            """
              import org.junit.rules.TestRule;
              import org.junit.runner.Description;
              import org.junit.runners.model.Statement;

              class CustomRule implements TestRule {
                  @Override
                  public Statement apply(Statement base, Description description) {
                      return base;
                  }
              }
              """
          ),
          java(
            """
              import org.junit.Rule;
              import org.junit.jupiter.api.Test;

              class SomeTest {
                  @Rule
                  public CustomRule rule = new CustomRule();

                  @Test
                  void test() {
                  }
              }
              """,
            """
              import org.junit.Rule;
              import org.junit.jupiter.api.Test;

              class SomeTest {
                  /*~~(JUnit Jupiter does not run JUnit 4 rule `CustomRule`; migrate it to an extension manually)~~>*/@Rule
                  public CustomRule rule = new CustomRule();

                  @Test
                  void test() {
                  }
              }
              """
          )
        );
    }

    @Test
    void flagsLeftoverClassRuleMethod() {
        //language=java
        rewriteRun(
          java(
            """
              import org.junit.ClassRule;
              import org.junit.jupiter.api.Test;
              import org.junit.rules.TestRule;
              import org.junit.rules.Timeout;

              class SomeTest {
                  @ClassRule
                  public static TestRule rule() {
                      return Timeout.seconds(1);
                  }

                  @Test
                  void test() {
                  }
              }
              """,
            """
              import org.junit.ClassRule;
              import org.junit.jupiter.api.Test;
              import org.junit.rules.TestRule;
              import org.junit.rules.Timeout;

              class SomeTest {
                  /*~~(JUnit Jupiter does not run JUnit 4 rule `TestRule`; migrate it to an extension manually)~~>*/@ClassRule
                  public static TestRule rule() {
                      return Timeout.seconds(1);
                  }

                  @Test
                  void test() {
                  }
              }
              """
          )
        );
    }

    @Test
    void ignoresRuleRunByMigrationSupport() {
        //language=java
        rewriteRun(
          spec -> spec.parser(JavaParser.fromJavaVersion()
            .classpathFromResources(new InMemoryExecutionContext(), "junit-4", "junit-jupiter-api-5", "junit-jupiter-migrationsupport-5")),
          java(
            """
              import org.junit.Rule;
              import org.junit.jupiter.api.Test;
              import org.junit.jupiter.api.extension.ExtendWith;
              import org.junit.jupiter.migrationsupport.rules.ExternalResourceSupport;
              import org.junit.rules.ExternalResource;

              @ExtendWith(ExternalResourceSupport.class)
              class SomeTest {
                  @Rule
                  public ExternalResource resource = new ExternalResource() {
                  };

                  @Test
                  void test() {
                  }
              }
              """
          )
        );
    }

    @Test
    void flagsRuleNotCoveredByMigrationSupport() {
        //language=java
        rewriteRun(
          spec -> spec.parser(JavaParser.fromJavaVersion()
            .classpathFromResources(new InMemoryExecutionContext(), "junit-4", "junit-jupiter-api-5", "junit-jupiter-migrationsupport-5")),
          java(
            """
              import org.junit.Rule;
              import org.junit.jupiter.api.Test;
              import org.junit.jupiter.api.extension.ExtendWith;
              import org.junit.jupiter.migrationsupport.rules.ExternalResourceSupport;
              import org.junit.rules.Timeout;

              @ExtendWith(ExternalResourceSupport.class)
              class SomeTest {
                  @Rule
                  public Timeout timeout = Timeout.seconds(1);

                  @Test
                  void test() {
                  }
              }
              """,
            """
              import org.junit.Rule;
              import org.junit.jupiter.api.Test;
              import org.junit.jupiter.api.extension.ExtendWith;
              import org.junit.jupiter.migrationsupport.rules.ExternalResourceSupport;
              import org.junit.rules.Timeout;

              @ExtendWith(ExternalResourceSupport.class)
              class SomeTest {
                  /*~~(JUnit Jupiter does not run JUnit 4 rule `Timeout`; migrate it to an extension manually)~~>*/@Rule
                  public Timeout timeout = Timeout.seconds(1);

                  @Test
                  void test() {
                  }
              }
              """
          )
        );
    }

    @Test
    void ignoresClassStillOnJUnit4() {
        //language=java
        rewriteRun(
          java(
            """
              import org.junit.Rule;
              import org.junit.Test;
              import org.junit.rules.Timeout;

              class SomeTest {
                  @Rule
                  public Timeout timeout = Timeout.seconds(1);

                  @Test
                  public void test() {
                  }
              }
              """
          )
        );
    }

    @Test
    void ignoresConvertedTestWatcher() {
        //language=java
        rewriteRun(
          spec -> spec.recipes(new TestWatcherRuleToExtension(), new UpdateTestAnnotation(), new FindLeftoverJUnit4Rules()),
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
                          System.out.println("passed");
                      }
                  };

                  @Test
                  public void test() {
                  }
              }
              """,
            """
              import org.junit.jupiter.api.Test;
              import org.junit.jupiter.api.extension.ExtensionContext;
              import org.junit.jupiter.api.extension.RegisterExtension;
              import org.junit.jupiter.api.extension.TestWatcher;

              public class SomeTest {
                  @RegisterExtension
                  public TestWatcher watcher = new TestWatcher() {
                      @Override
                      public void testSuccessful(ExtensionContext description) {
                          System.out.println("passed");
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
}
