// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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

package google.registry.tools;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;

import java.security.NoSuchAlgorithmException;
import java.security.ProviderException;
import java.security.SecureRandom;
import java.util.Random;

/** Dagger module for Registry Tool. */
@Module
abstract class RegistryToolModule {

  @Provides
  static RegistryToolEnvironment provideRegistryToolEnvironment() {
    return RegistryToolEnvironment.get();
  }

  @Binds
  abstract PasswordGenerator providePasswordGenerator(RandomPasswordGenerator passwordGenerator);

  @Provides
  static Random provideRandom() {
    try {
      return SecureRandom.getInstance("NativePRNG");
    } catch (NoSuchAlgorithmException e) {
      throw new ProviderException(e);
    }
  }
}
