// Copyright 2018 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.testing;

import org.junit.rules.MethodRule;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.Statement;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

/** A rule that initializes Mockito-annotated fields (e.g. mocks and captors). */
// TODO(b/77815684): Replace with native version from Mockito v1.10 once we upgrade to that.
public class MockitoJUnitRule implements MethodRule {

  public static MockitoJUnitRule create() {
    return new MockitoJUnitRule();
  }

  @Override
  public Statement apply(Statement base, FrameworkMethod method, Object target) {
    return new Statement() {
      @Override
      public void evaluate() throws Throwable {
        MockitoAnnotations.initMocks(target);
        try {
          base.evaluate();
        } finally {
          Mockito.validateMockitoUsage();
        }
      }
    };
  }
}
