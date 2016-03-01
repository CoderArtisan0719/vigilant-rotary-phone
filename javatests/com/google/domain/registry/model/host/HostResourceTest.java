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

package com.google.domain.registry.model.host;

import static com.google.common.truth.Truth.assertThat;
import static com.google.domain.registry.model.EppResourceUtils.loadByUniqueId;
import static com.google.domain.registry.testing.DatastoreHelper.cloneAndSetAutoTimestamps;
import static com.google.domain.registry.testing.DatastoreHelper.createTld;
import static com.google.domain.registry.testing.DatastoreHelper.newDomainResource;
import static com.google.domain.registry.testing.DatastoreHelper.persistResource;
import static com.google.domain.registry.testing.HostResourceSubject.assertAboutHosts;

import com.google.common.collect.ImmutableSet;
import com.google.domain.registry.model.EntityTestCase;
import com.google.domain.registry.model.EppResource;
import com.google.domain.registry.model.EppResource.SharedFields;
import com.google.domain.registry.model.billing.BillingEvent;
import com.google.domain.registry.model.domain.DomainResource;
import com.google.domain.registry.model.eppcommon.StatusValue;
import com.google.domain.registry.model.eppcommon.Trid;
import com.google.domain.registry.model.reporting.HistoryEntry;
import com.google.domain.registry.model.transfer.TransferData;
import com.google.domain.registry.model.transfer.TransferData.TransferServerApproveEntity;
import com.google.domain.registry.model.transfer.TransferStatus;
import com.google.domain.registry.testing.ExceptionRule;

import com.googlecode.objectify.Key;
import com.googlecode.objectify.Ref;

import org.joda.money.Money;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.lang.reflect.Field;
import java.net.InetAddress;

import javax.annotation.Nullable;

/** Unit tests for {@link HostResource}. */
public class HostResourceTest extends EntityTestCase {

  @Rule
  public final ExceptionRule thrown = new ExceptionRule();

  HostResource hostResource;

  @Before
  public void setUp() throws Exception {
    createTld("com");
    // Set up a new persisted registrar entity.
    persistResource(
        newDomainResource("example.com").asBuilder()
            .setRepoId("1-COM")
            .setTransferData(new TransferData.Builder()
                .setExtendedRegistrationYears(0)
                .setGainingClientId("gaining")
                .setLosingClientId("losing")
                .setPendingTransferExpirationTime(clock.nowUtc())
                .setServerApproveEntities(
                    ImmutableSet.<Key<? extends TransferServerApproveEntity>>of(
                        Key.create(BillingEvent.OneTime.class, 1)))
                .setTransferRequestTime(clock.nowUtc())
                .setTransferStatus(TransferStatus.SERVER_APPROVED)
                .setTransferRequestTrid(Trid.create("client trid"))
                .build())
            .build());
    hostResource = cloneAndSetAutoTimestamps(
        new HostResource.Builder()
            .setRepoId("DEADBEEF-COM")
            .setFullyQualifiedHostName("ns1.example.com")
            .setCreationClientId("a registrar")
            .setLastEppUpdateTime(clock.nowUtc())
            .setLastEppUpdateClientId("another registrar")
            .setLastTransferTime(clock.nowUtc())
            .setInetAddresses(ImmutableSet.of(InetAddress.getLocalHost()))
            .setStatusValues(ImmutableSet.of(StatusValue.OK))
            .setSuperordinateDomain(Ref.create(
                loadByUniqueId(DomainResource.class, "example.com", clock.nowUtc())))
            .build());
    persistResource(hostResource);
  }

  @Test
  public void testPersistence() throws Exception {
    assertThat(loadByUniqueId(
        HostResource.class, hostResource.getForeignKey(), clock.nowUtc()))
        .isEqualTo(hostResource.cloneProjectedAtTime(clock.nowUtc()));
  }

  @Test
  public void testIndexing() throws Exception {
    // Clone it and save it before running the indexing test so that its transferData fields are
    // populated from the superordinate domain.
    verifyIndexing(
        persistResource(hostResource.cloneProjectedAtTime(clock.nowUtc())),
        "deletionTime",
        "sharedFields.deletionTime",
        "fullyQualifiedHostName",
        "inetAddresses",
        "superordinateDomain",
        "currentSponsorClientId",
        "sharedFields.currentSponsorClientId");
  }

  @Test
  public void testEmptyStringsBecomeNull() {
    assertThat(new HostResource.Builder().setCurrentSponsorClientId(null).build()
        .getCurrentSponsorClientId())
            .isNull();
    assertThat(new HostResource.Builder().setCurrentSponsorClientId("").build()
        .getCurrentSponsorClientId())
            .isNull();
    assertThat(new HostResource.Builder().setCurrentSponsorClientId(" ").build()
        .getCurrentSponsorClientId())
            .isNotNull();
  }

  @Test
  public void testCurrentSponsorClientId_comesFromSuperordinateDomain() {
    assertThat(hostResource.getCurrentSponsorClientId()).isNull();
    HostResource projectedHost =
        loadByUniqueId(HostResource.class, hostResource.getForeignKey(), clock.nowUtc());
    assertThat(projectedHost.getCurrentSponsorClientId())
        .isEqualTo(loadByUniqueId(
            DomainResource.class,
            "example.com",
            clock.nowUtc())
            .getCurrentSponsorClientId());
  }

  @Test
  public void testEmptySetsBecomeNull() throws Exception {
    assertThat(new HostResource.Builder().setInetAddresses(null).build().inetAddresses).isNull();
    assertThat(new HostResource.Builder()
        .setInetAddresses(ImmutableSet.<InetAddress>of()).build().inetAddresses)
            .isNull();
    assertThat(new HostResource.Builder()
        .setInetAddresses(ImmutableSet.of(InetAddress.getLocalHost())).build().inetAddresses)
            .isNotNull();
  }

  @Test
  public void testEmptyTransferDataBecomesNull() throws Exception {
    HostResource withNull = new HostResource.Builder().setTransferData(null).build();
    HostResource withEmpty = withNull.asBuilder().setTransferData(TransferData.EMPTY).build();
    assertThat(withNull).isEqualTo(withEmpty);
    // We don't have package access to SharedFields so we need to use reflection to check for null.
    Field sharedFieldsField = EppResource.class.getDeclaredField("sharedFields");
    sharedFieldsField.setAccessible(true);
    Field transferDataField = SharedFields.class.getDeclaredField("transferData");
    transferDataField.setAccessible(true);
    assertThat(transferDataField.get(sharedFieldsField.get(withEmpty))).isNull();
  }

  @Test
  public void testImplicitStatusValues() {
    // OK is implicit if there's no other statuses.
    StatusValue[] statuses = {StatusValue.OK};
    assertAboutHosts()
        .that(new HostResource.Builder().build())
        .hasExactlyStatusValues(statuses);
    StatusValue[] statuses1 = {StatusValue.OK, StatusValue.LINKED};
    // OK is also implicit if the only other status is LINKED.
    assertAboutHosts()
        .that(new HostResource.Builder()
            .setStatusValues(ImmutableSet.of(StatusValue.LINKED)).build())
        .hasExactlyStatusValues(statuses1);
    StatusValue[] statuses2 = {StatusValue.CLIENT_HOLD};
    // If there are other status values, OK should be suppressed.
    assertAboutHosts()
        .that(new HostResource.Builder()
            .setStatusValues(ImmutableSet.of(StatusValue.CLIENT_HOLD))
            .build())
        .hasExactlyStatusValues(statuses2);
    StatusValue[] statuses3 = {StatusValue.LINKED, StatusValue.CLIENT_HOLD};
    assertAboutHosts()
        .that(new HostResource.Builder()
            .setStatusValues(ImmutableSet.of(StatusValue.LINKED, StatusValue.CLIENT_HOLD))
            .build())
        .hasExactlyStatusValues(statuses3);
    StatusValue[] statuses4 = {StatusValue.CLIENT_HOLD};
    // When OK is suppressed, it should be removed even if it was originally there.
    assertAboutHosts()
        .that(new HostResource.Builder()
            .setStatusValues(ImmutableSet.of(StatusValue.OK, StatusValue.CLIENT_HOLD))
            .build())
        .hasExactlyStatusValues(statuses4);
  }

  @Nullable
  private DateTime runCloneProjectedAtTimeTest(
      @Nullable DateTime domainTransferTime,
      @Nullable DateTime hostTransferTime,
      @Nullable DateTime superordinateChangeTime) {
    DomainResource domain = loadByUniqueId(
        DomainResource.class, "example.com", clock.nowUtc());
    persistResource(
        domain.asBuilder().setTransferData(null).setLastTransferTime(domainTransferTime).build());
    hostResource = persistResource(
        hostResource.asBuilder()
            .setLastSuperordinateChange(superordinateChangeTime)
            .setLastTransferTime(hostTransferTime)
            .setTransferData(null)
            .build());
    return hostResource.cloneProjectedAtTime(clock.nowUtc()).getLastTransferTime();
  }

  @Test
  public void testCloneProjectedAtTime_lastTransferTimeComesOffHostWhenTransferredMoreRecently() {
    assertThat(runCloneProjectedAtTimeTest(
        clock.nowUtc().minusDays(10), clock.nowUtc().minusDays(2), clock.nowUtc().minusDays(1)))
            .isEqualTo(clock.nowUtc().minusDays(2));
  }

  @Test
  public void testCloneProjectedAtTime_lastTransferTimeNullWhenAllTransfersAreNull() {
    assertThat(runCloneProjectedAtTimeTest(null, null, null)).isNull();
  }

  @Test
  public void testCloneProjectedAtTime_lastTransferTimeComesOffHostWhenTimeOnDomainIsNull() {
    assertThat(runCloneProjectedAtTimeTest(null, clock.nowUtc().minusDays(30), null))
        .isEqualTo(clock.nowUtc().minusDays(30));
  }

  @Test
  public void testCloneProjectedAtTime_lastTransferTimeIsNullWhenHostMovedAfterDomainTransferred() {
    assertThat(runCloneProjectedAtTimeTest(
        clock.nowUtc().minusDays(30), null, clock.nowUtc().minusDays(20)))
            .isNull();
  }

  @Test
  public void testCloneProjectedAtTime_lastTransferTimeComesOffDomainWhenTimeOnHostIsNull() {
    assertThat(runCloneProjectedAtTimeTest(clock.nowUtc().minusDays(5), null, null))
        .isEqualTo(clock.nowUtc().minusDays(5));
  }

  @Test
  public void testCloneProjectedAtTime_lastTransferTimeComesOffDomainWhenLastMoveIsntNull() {
    assertThat(runCloneProjectedAtTimeTest(
        clock.nowUtc().minusDays(5), null, clock.nowUtc().minusDays(10)))
            .isEqualTo(clock.nowUtc().minusDays(5));
  }

  @Test
  public void testCloneProjectedAtTime_lastTransferTimeComesOffDomainWhenThatIsMostRecent() {
    assertThat(runCloneProjectedAtTimeTest(
        clock.nowUtc().minusDays(5), clock.nowUtc().minusDays(20), clock.nowUtc().minusDays(10)))
            .isEqualTo(clock.nowUtc().minusDays(5));
  }

  @Test
  public void testExpiredTransfer_subordinateHost() {
    DomainResource domain = loadByUniqueId(
        DomainResource.class, "example.com", clock.nowUtc());
    persistResource(domain.asBuilder()
        .setTransferData(domain.getTransferData().asBuilder()
            .setTransferStatus(TransferStatus.PENDING)
            .setPendingTransferExpirationTime(clock.nowUtc().plusDays(1))
            .setGainingClientId("winner")
            .setExtendedRegistrationYears(2)
            .setServerApproveBillingEvent(Ref.create(
                new BillingEvent.OneTime.Builder()
                    .setParent(new HistoryEntry.Builder().setParent(domain).build())
                    .setCost(Money.parse("USD 100"))
                    .setBillingTime(clock.nowUtc().plusYears(2))
                    .setReason(BillingEvent.Reason.TRANSFER)
                    .setClientId("TheRegistrar")
                    .setTargetId("example.com")
                    .setEventTime(clock.nowUtc().plusYears(2))
                    .setPeriodYears(2)
                    .build()))
            .build())
        .build());
    HostResource afterTransfer = hostResource.cloneProjectedAtTime(clock.nowUtc().plusDays(1));
    assertThat(afterTransfer.getTransferData().getTransferStatus())
        .isEqualTo(TransferStatus.SERVER_APPROVED);
    assertThat(afterTransfer.getCurrentSponsorClientId()).isEqualTo("winner");
    assertThat(afterTransfer.getLastTransferTime()).isEqualTo(clock.nowUtc().plusDays(1));
  }
}
