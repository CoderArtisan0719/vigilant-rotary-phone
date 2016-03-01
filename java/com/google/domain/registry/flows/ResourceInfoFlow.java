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

package com.google.domain.registry.flows;

import static com.google.domain.registry.model.EppResourceUtils.cloneResourceWithLinkedStatus;
import static com.google.domain.registry.model.eppoutput.Result.Code.Success;

import com.google.common.collect.ImmutableList;
import com.google.domain.registry.model.EppResource;
import com.google.domain.registry.model.eppinput.ResourceCommand.SingleResourceCommand;
import com.google.domain.registry.model.eppoutput.EppOutput;
import com.google.domain.registry.model.eppoutput.Response.ResponseData;
import com.google.domain.registry.model.eppoutput.Response.ResponseExtension;

/**
 * An EPP flow that reads a storable resource.
 *
 * @param <R> the resource type being manipulated
 * @param <C> the command type, marshalled directly from the epp xml
 */
public abstract class ResourceInfoFlow<R extends EppResource, C extends SingleResourceCommand>
    extends ResourceQueryFlow<R, C> {
  @Override
  public EppOutput runResourceFlow() throws EppException {
    return createOutput(Success, getResourceInfo(), getResponseExtensions());
  }

  @SuppressWarnings("unused")
  protected ResponseData getResourceInfo() throws EppException {
    return cloneResourceWithLinkedStatus(existingResource, now);
  }

  @SuppressWarnings("unused")
  protected ImmutableList<? extends ResponseExtension> getResponseExtensions() throws EppException {
    return null;
  }
}
