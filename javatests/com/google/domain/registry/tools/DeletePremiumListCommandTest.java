// Copyright 2016 Google Inc. All Rights Reserved.
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

package com.google.domain.registry.tools;

import static com.google.common.truth.Truth.assertThat;
import static com.google.domain.registry.model.ofy.ObjectifyService.ofy;
import static com.google.domain.registry.testing.DatastoreHelper.createTld;
import static com.google.domain.registry.testing.DatastoreHelper.persistPremiumList;
import static com.google.domain.registry.testing.DatastoreHelper.persistResource;

import com.google.domain.registry.model.registry.Registry;
import com.google.domain.registry.model.registry.label.PremiumList;
import com.google.domain.registry.model.registry.label.PremiumList.PremiumListEntry;

import org.junit.Test;

/** Unit tests for {@link DeletePremiumListCommand}. */
public class DeletePremiumListCommandTest extends CommandTestCase<DeletePremiumListCommand> {

  @Test
  public void testSuccess() throws Exception {
    PremiumList premiumList = persistPremiumList("xn--q9jyb4c", "blah,USD 100");
    assertThat(premiumList.getPremiumListEntries()).hasSize(1);
    runCommand("--force", "--name=xn--q9jyb4c");
    assertThat(PremiumList.get("xn--q9jyb4c")).isAbsent();

    // Ensure that the Datastore premium list entry entities were deleted correctly.
    assertThat(ofy().load()
        .type(PremiumListEntry.class)
        .ancestor(premiumList.getRevisionKey())
        .keys())
            .isEmpty();
  }

  @Test
  public void testFailure_whenPremiumListDoesNotExist() throws Exception {
    thrown.expect(IllegalArgumentException.class,
        "Cannot delete the premium list foo because it doesn't exist.");
    runCommandForced("--name=foo");
  }

  @Test
  public void testFailure_whenPremiumListIsInUse() throws Exception {
    PremiumList premiumList = persistPremiumList("xn--q9jyb4c", "blah,USD 100");
    createTld("xn--q9jyb4c");
    persistResource(Registry.get("xn--q9jyb4c").asBuilder().setPremiumList(premiumList).build());
    thrown.expect(IllegalArgumentException.class,
        "Cannot delete premium list because it is used on these tld(s): xn--q9jyb4c");
    runCommandForced("--name=" + premiumList.getName());
    assertThat(PremiumList.get(premiumList.getName())).isPresent();
  }
}
