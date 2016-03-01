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

package com.google.domain.registry.model.index;

import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hashing;
import com.google.domain.registry.config.RegistryEnvironment;
import com.google.domain.registry.model.EppResource;
import com.google.domain.registry.model.ImmutableObject;
import com.google.domain.registry.model.annotations.VirtualEntity;

import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;

/** A virtual entity to represent buckets to which EppResourceIndex objects are randomly added. */
@Entity
@VirtualEntity
public class EppResourceIndexBucket extends ImmutableObject {

  @Id
  private long bucketId;

  /**
   * Deterministic function that returns a bucket id based on the resource's roid.
   * NB: At the moment, nothing depends on this being deterministic, so we have the ability to
   * change the number of buckets and utilize a random distribution once we do.
   */
  private static long getBucketIdFromEppResource(Key<? extends EppResource> resourceKey) {
    int numBuckets = RegistryEnvironment.get().config().getEppResourceIndexBucketCount();
    // IDs can't be 0, so add 1 to the hash.
    return Hashing.consistentHash(resourceKey.getName().hashCode(), numBuckets) + 1;
  }

  /** Gets a bucket key as a function of an EppResource to be indexed. */
  public static Key<EppResourceIndexBucket> getBucketKey(Key<? extends EppResource> resourceKey) {
    return Key.create(EppResourceIndexBucket.class, getBucketIdFromEppResource(resourceKey));
  }

  /** Gets the specified numbered bucket key. */
  public static Key<EppResourceIndexBucket> getBucketKey(int bucketId) {
    return Key.create(EppResourceIndexBucket.class, bucketId);
  }

  /** Returns the keys to all buckets. */
  public static Iterable<Key<EppResourceIndexBucket>> getAllBuckets() {
    ImmutableList.Builder<Key<EppResourceIndexBucket>> builder = new ImmutableList.Builder<>();
    int numBuckets = RegistryEnvironment.get().config().getEppResourceIndexBucketCount();
    for (int bucketId = 1; bucketId <= numBuckets; bucketId++) {
      builder.add(getBucketKey(bucketId));
    }
    return builder.build();
  }
}
