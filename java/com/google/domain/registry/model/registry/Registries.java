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

package com.google.domain.registry.model.registry;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Predicates.equalTo;
import static com.google.common.base.Strings.emptyToNull;
import static com.google.common.collect.Maps.filterValues;
import static com.google.domain.registry.model.common.EntityGroupRoot.getCrossTldKey;
import static com.google.domain.registry.model.ofy.ObjectifyService.ofy;
import static com.google.domain.registry.util.CacheUtils.memoizeWithShortExpiration;

import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.InternetDomainName;
import com.google.domain.registry.model.registry.Registry.TldType;

import com.googlecode.objectify.Work;

/** Utilities for finding and listing {@link Registry} entities. */
public final class Registries {

  private Registries() {}

  /** Supplier of a cached registries map. */
  private static Supplier<ImmutableMap<String, TldType>> cache = createFreshCache();

  /**
   * Returns a newly-created Supplier of a registries to types map.
   *
   * <p>The supplier's get() method enters a transactionless context briefly to avoid enrolling the
   * query inside an unrelated client-affecting transaction.
   */
  private static Supplier<ImmutableMap<String, TldType>> createFreshCache() {
    return memoizeWithShortExpiration(new Supplier<ImmutableMap<String, TldType>>() {
      @Override
      public ImmutableMap<String, TldType> get() {
        return ofy().doTransactionless(new Work<ImmutableMap<String, TldType>>() {
          @Override
          public ImmutableMap<String, TldType> run() {
            ImmutableMap.Builder<String, TldType> builder = new ImmutableMap.Builder<>();
            for (Registry registry : ofy().load().type(Registry.class).ancestor(getCrossTldKey())) {
              builder.put(registry.getTldStr(), registry.getTldType());
            }
            return builder.build();
          }});
      }});
  }

  /** Manually reset the static cache backing the methods on this class. */
  // TODO(b/24903801): offer explicit cached and uncached paths instead.
  public static void resetCache() {
    cache = createFreshCache();
  }

  public static ImmutableSet<String> getTlds() {
    return cache.get().keySet();
  }

  public static ImmutableSet<String> getTldsOfType(TldType type) {
    return ImmutableSet.copyOf(filterValues(cache.get(), equalTo(type)).keySet());
  }

  /** Shortcut to check whether a tld exists or else throw. If it exists, it is returned back. */
  public static String assertTldExists(String tld) {
    checkArgument(
        getTlds().contains(checkNotNull(emptyToNull(tld), "Null or empty TLD specified")),
        "TLD %s does not exist",
        tld);
    return tld;
  }

  /**
   * Returns the TLD which the domain name or hostname falls under, no matter how many levels of
   * subdomains there are.
   */
  public static Optional<InternetDomainName> findTldForName(InternetDomainName domainName) {
    ImmutableSet<String> tlds = getTlds();
    while (domainName.hasParent()) {
      domainName = domainName.parent();
      if (tlds.contains(domainName.toString())) {
        return Optional.of(domainName);
      }
    }
    return Optional.absent();
  }

  /**
   * Returns the registered TLD which this domain name falls under, or throws an exception if no
   * match exists.
   */
  public static InternetDomainName findTldForNameOrThrow(InternetDomainName domainName) {
    return checkNotNull(
        findTldForName(domainName).orNull(),
        "Domain name is not under a recognized TLD: %s", domainName.toString());
  }
}
