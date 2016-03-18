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

package com.google.domain.registry.rdap;

import static com.google.common.truth.Truth.assertThat;
import static com.google.domain.registry.testing.DatastoreHelper.createTld;
import static com.google.domain.registry.testing.DatastoreHelper.persistResource;
import static com.google.domain.registry.testing.DatastoreHelper.persistSimpleGlobalResources;
import static com.google.domain.registry.testing.FullFieldsTestEntityHelper.makeAndPersistContactResource;
import static com.google.domain.registry.testing.FullFieldsTestEntityHelper.makeContactResource;
import static com.google.domain.registry.testing.FullFieldsTestEntityHelper.makeDomainResource;
import static com.google.domain.registry.testing.FullFieldsTestEntityHelper.makeHostResource;
import static com.google.domain.registry.testing.FullFieldsTestEntityHelper.makeRegistrar;
import static com.google.domain.registry.testing.FullFieldsTestEntityHelper.makeRegistrarContacts;
import static com.google.domain.registry.testing.TestDataHelper.loadFileWithSubstitutions;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.domain.registry.model.contact.ContactResource;
import com.google.domain.registry.model.host.HostResource;
import com.google.domain.registry.model.ofy.Ofy;
import com.google.domain.registry.model.registrar.Registrar;
import com.google.domain.registry.testing.AppEngineRule;
import com.google.domain.registry.testing.FakeClock;
import com.google.domain.registry.testing.FakeResponse;
import com.google.domain.registry.testing.InjectRule;

import org.joda.time.DateTime;
import org.json.simple.JSONValue;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Map;

/** Unit tests for {@link RdapEntityAction}. */
@RunWith(JUnit4.class)
public class RdapEntityActionTest {

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder()
      .withDatastore()
      .build();

  @Rule
  public final InjectRule inject = new InjectRule();

  private final FakeResponse response = new FakeResponse();
  private final FakeClock clock = new FakeClock(DateTime.parse("2000-01-01TZ"));

  private RdapEntityAction action;

  private Registrar registrarLol;
  private ContactResource registrant;
  private ContactResource adminContact;
  private ContactResource techContact;
  private ContactResource disconnectedContact;
  private ContactResource deletedContact;

  @Before
  public void setUp() throws Exception {
    inject.setStaticField(Ofy.class, "clock", clock);
    // lol
    createTld("lol");
    registrarLol = persistResource(makeRegistrar(
        "evilregistrar", "Yes Virginia <script>", Registrar.State.ACTIVE));
    persistSimpleGlobalResources(makeRegistrarContacts(registrarLol));
    registrant = makeAndPersistContactResource(
        "8372808-ERL",
        "(◕‿◕)",
        "lol@cat.みんな",
        ImmutableList.of("1 Smiley Row", "Suite みんな"),
        clock.nowUtc());
    adminContact = makeAndPersistContactResource(
        "8372808-ERL",
        "(◕‿◕)",
        "lol@cat.みんな",
        ImmutableList.of("1 Smiley Row", "Suite みんな"),
        clock.nowUtc());
    techContact = makeAndPersistContactResource(
        "8372808-ERL",
        "(◕‿◕)",
        "lol@cat.みんな",
        ImmutableList.of("1 Smiley Row", "Suite みんな"),
        clock.nowUtc());
    HostResource host1 =
        persistResource(makeHostResource("ns1.cat.lol", "1.2.3.4"));
    HostResource host2 =
        persistResource(makeHostResource("ns2.cat.lol", "bad:f00d:cafe:0:0:0:15:beef"));
    persistResource(makeDomainResource("cat.lol",
        registrant,
        adminContact,
        techContact,
        host1,
        host2,
        registrarLol));
    // xn--q9jyb4c
    createTld("xn--q9jyb4c");
    Registrar registrarIdn =
        persistResource(makeRegistrar("idnregistrar", "IDN Registrar", Registrar.State.ACTIVE));
    persistSimpleGlobalResources(makeRegistrarContacts(registrarIdn));
    persistResource(makeDomainResource("cat.みんな",
        registrant,
        adminContact,
        techContact,
        host1,
        host2,
        registrarIdn));
    createTld("1.tld");
    Registrar registrar1tld = persistResource(
        makeRegistrar("1tldregistrar", "Multilevel Registrar", Registrar.State.ACTIVE));
    persistSimpleGlobalResources(makeRegistrarContacts(registrar1tld));
    persistResource(makeDomainResource("cat.1.tld",
        registrant,
        adminContact,
        techContact,
        host1,
        host2,
        registrar1tld));
    disconnectedContact = makeAndPersistContactResource(
        "8372808-ERL",
        "(◕‿◕)",
        "lol@cat.みんな",
        ImmutableList.of("1 Smiley Row", "Suite みんな"),
        clock.nowUtc());
    deletedContact = persistResource(makeContactResource(
            "8372808-ERL",
            "(◕‿◕)",
            "lol@cat.みんな",
            ImmutableList.of("1 Smiley Row", "Suite みんな"))
        .asBuilder().setDeletionTime(clock.nowUtc()).build());
    action = new RdapEntityAction();
    action.clock = clock;
    action.response = response;
    action.rdapLinkBase = "https://example.com/rdap/";
    action.rdapWhoisServer = "whois.example.tld";
  }

  private Object generateActualJson(String name) {
    action.requestPath = RdapEntityAction.PATH + name;
    action.run();
    return JSONValue.parse(response.getPayload());
  }

  private Object generateExpectedJson(
      String handle,
      String expectedOutputFile) {
    return JSONValue.parse(loadFileWithSubstitutions(
        this.getClass(),
        expectedOutputFile,
        ImmutableMap.of(
            "NAME", handle,
            "FULLNAME", "(◕‿◕)",
            "ADDRESS", "\"1 Smiley Row\", \"Suite みんな\"",
            "EMAIL", "lol@cat.みんな",
            "TYPE", "entity")));
  }

  private Object generateExpectedJsonWithTopLevelEntries(
      String handle,
      String expectedOutputFile) {
    Object obj = generateExpectedJson(handle, expectedOutputFile);
    if (obj instanceof Map) {
      @SuppressWarnings("unchecked")
      Map<String, Object> map = (Map<String, Object>) obj;
      ImmutableMap.Builder<String, Object> builder = new ImmutableMap.Builder<>();
      builder.putAll(map);
      if (!map.containsKey("rdapConformance")) {
        builder.put("rdapConformance", ImmutableList.of("rdap_level_0"));
      }
      if (!map.containsKey("port43")) {
        builder.put("port43", "whois.example.tld");
      }
      if (!map.containsKey("notices")) {
        RdapTestHelper.addTermsOfServiceNotice(builder, "https://example.com/rdap/");
      }
      if (!map.containsKey("remarks")) {
        RdapTestHelper.addNonDomainBoilerplateRemarks(builder);
      }
      obj = builder.build();
    }
    return obj;
  }

  @Test
  public void testInvalidEntity_returns400() throws Exception {
    assertThat(generateActualJson("invalid/entity/handle")).isEqualTo(
        generateExpectedJson(
            "invalid/entity/handle is not a valid entity handle",
            "rdap_error_400.json"));
    assertThat(response.getStatus()).isEqualTo(400);
  }

  @Test
  public void testUnknownEntity_returns404() throws Exception {
    assertThat(generateActualJson("MISSING-ENTITY")).isEqualTo(
        generateExpectedJson("MISSING-ENTITY not found", "rdap_error_404.json"));
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  public void testValidRegistrantContact_works() throws Exception {
    assertThat(generateActualJson(registrant.getRepoId())).isEqualTo(
        generateExpectedJsonWithTopLevelEntries(registrant.getRepoId(), "rdap_contact.json"));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  public void testValidAdminContact_works() throws Exception {
    assertThat(generateActualJson(adminContact.getRepoId())).isEqualTo(
        generateExpectedJsonWithTopLevelEntries(adminContact.getRepoId(), "rdap_contact.json"));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  public void testValidTechContact_works() throws Exception {
    assertThat(generateActualJson(techContact.getRepoId())).isEqualTo(
        generateExpectedJsonWithTopLevelEntries(techContact.getRepoId(), "rdap_contact.json"));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  public void testValidDisconnectedContact_works() throws Exception {
    assertThat(generateActualJson(disconnectedContact.getRepoId())).isEqualTo(
        generateExpectedJsonWithTopLevelEntries(
            disconnectedContact.getRepoId(), "rdap_contact.json"));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  public void testRegistrar_works() throws Exception {
    assertThat(generateActualJson(registrarLol.getClientIdentifier())).isEqualTo(
        generateExpectedJsonWithTopLevelEntries(
            registrarLol.getClientIdentifier(), "rdap_registrar.json"));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  public void testDeletedContact_returns404() throws Exception {
    assertThat(generateActualJson(deletedContact.getRepoId())).isEqualTo(
        generateExpectedJson(deletedContact.getRepoId() + " not found", "rdap_error_404.json"));
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  public void testQueryParameter_ignored() throws Exception {
    assertThat(generateActualJson(techContact.getRepoId() + "?key=value")).isEqualTo(
        generateExpectedJsonWithTopLevelEntries(techContact.getRepoId(), "rdap_contact.json"));
    assertThat(response.getStatus()).isEqualTo(200);
  }
}
