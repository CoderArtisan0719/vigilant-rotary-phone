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

package com.google.domain.registry.model.domain.fee;

import com.google.common.base.CharMatcher;
import com.google.domain.registry.model.ImmutableObject;

import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlValue;

/** A command name along with the launch phase and subphase it is to be executed in. */
public class FeeCommandDescriptor extends ImmutableObject {

  /** The name of a command that might have an associated fee. */
  public enum CommandName {
    CREATE,
    RENEW,
    TRANSFER,
    RESTORE,
    UNKNOWN
  }

  @XmlAttribute
  String phase;

  @XmlAttribute
  String subphase;

  @XmlValue
  String command;

  public String getPhase() {
    return phase;
  }

  public String getSubphase() {
    return subphase;
  }

  public String getUnparsedCommandName() {
    return command;
  }

  public CommandName getCommand() {
    // Require the xml string to be lowercase.
    if (command != null && CharMatcher.javaLowerCase().matchesAllOf(command)) {
      try {
        return CommandName.valueOf(command.toUpperCase());
      } catch (IllegalArgumentException e) {
        // Swallow this and return UNKNOWN below because there's no matching CommandName.
      }
    }
    return CommandName.UNKNOWN;
  }
}
