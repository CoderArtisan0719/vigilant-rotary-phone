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

package google.registry.model.domain.fee06;

import google.registry.model.domain.fee.FeeQueryCommandExtensionItemImpl;
import google.registry.model.eppinput.EppInput.CommandExtension;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlType;
import org.joda.money.CurrencyUnit;

/** A fee extension that may be present on domain info commands. */
@XmlRootElement(name = "info")
@XmlType(propOrder = {"currency", "command", "period"})
public class FeeInfoCommandExtensionV06
    extends FeeQueryCommandExtensionItemImpl implements CommandExtension {

  /** A three-character ISO4217 currency code. */
  CurrencyUnit currency;

  @Override
  public CurrencyUnit getCurrency() {
    return currency;
  }
}

