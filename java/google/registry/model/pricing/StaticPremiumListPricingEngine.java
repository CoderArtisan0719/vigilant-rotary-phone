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

package google.registry.model.pricing;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Strings.emptyToNull;
import static google.registry.model.registry.Registry.TldState.SUNRISE;
import static google.registry.model.registry.label.ReservationType.NAME_COLLISION;
import static google.registry.model.registry.label.ReservedList.getReservation;
import static google.registry.util.DomainNameUtils.getTldFromDomainName;

import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.net.InternetDomainName;
import google.registry.model.registry.Registry;
import google.registry.model.registry.label.PremiumList;
import javax.inject.Inject;
import org.joda.money.Money;
import org.joda.time.DateTime;

/** A premium list pricing engine that stores static pricing information in Datastore entities. */
public final class StaticPremiumListPricingEngine implements PremiumPricingEngine {

  @Inject StaticPremiumListPricingEngine() {}

  @Override
  public DomainPrices getDomainPrices(String fullyQualifiedDomainName, DateTime priceTime) {
    String tld = getTldFromDomainName(fullyQualifiedDomainName);
    String label = InternetDomainName.from(fullyQualifiedDomainName).parts().get(0);
    Registry registry = Registry.get(checkNotNull(tld, "tld"));
    Optional<Money> premiumPrice = Optional.<Money>absent();
    if (registry.getPremiumList() != null) {
      String listName = registry.getPremiumList().getName();
      Optional<PremiumList> premiumList = PremiumList.get(listName);
      checkState(premiumList.isPresent(), "Could not load premium list: %s", listName);
      premiumPrice = premiumList.get().getPremiumPrice(label);
    }
    boolean isNameCollisionInSunrise =
        registry.getTldState(priceTime).equals(SUNRISE)
            && getReservation(label, tld) == NAME_COLLISION;
    String feeClass = emptyToNull(Joiner.on('-').skipNulls().join(
            premiumPrice.isPresent() ? "premium" : null,
            isNameCollisionInSunrise ? "collision" : null));
    return DomainPrices.create(
        premiumPrice.isPresent(),
        premiumPrice.or(registry.getStandardCreateCost()),
        premiumPrice.or(registry.getStandardRenewCost(priceTime)),
        Optional.<String>fromNullable(feeClass));
  }
}
