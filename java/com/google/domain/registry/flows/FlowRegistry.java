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

import static com.google.domain.registry.model.domain.launch.LaunchCreateExtension.CreateType.APPLICATION;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableTable;
import com.google.common.collect.Table;
import com.google.domain.registry.flows.EppException.SyntaxErrorException;
import com.google.domain.registry.flows.EppException.UnimplementedCommandException;
import com.google.domain.registry.flows.contact.ContactCheckFlow;
import com.google.domain.registry.flows.contact.ContactCreateFlow;
import com.google.domain.registry.flows.contact.ContactDeleteFlow;
import com.google.domain.registry.flows.contact.ContactInfoFlow;
import com.google.domain.registry.flows.contact.ContactTransferApproveFlow;
import com.google.domain.registry.flows.contact.ContactTransferCancelFlow;
import com.google.domain.registry.flows.contact.ContactTransferQueryFlow;
import com.google.domain.registry.flows.contact.ContactTransferRejectFlow;
import com.google.domain.registry.flows.contact.ContactTransferRequestFlow;
import com.google.domain.registry.flows.contact.ContactUpdateFlow;
import com.google.domain.registry.flows.domain.ClaimsCheckFlow;
import com.google.domain.registry.flows.domain.DomainAllocateFlow;
import com.google.domain.registry.flows.domain.DomainApplicationCreateFlow;
import com.google.domain.registry.flows.domain.DomainApplicationDeleteFlow;
import com.google.domain.registry.flows.domain.DomainApplicationInfoFlow;
import com.google.domain.registry.flows.domain.DomainApplicationUpdateFlow;
import com.google.domain.registry.flows.domain.DomainCheckFlow;
import com.google.domain.registry.flows.domain.DomainCreateFlow;
import com.google.domain.registry.flows.domain.DomainDeleteFlow;
import com.google.domain.registry.flows.domain.DomainInfoFlow;
import com.google.domain.registry.flows.domain.DomainRenewFlow;
import com.google.domain.registry.flows.domain.DomainRestoreRequestFlow;
import com.google.domain.registry.flows.domain.DomainTransferApproveFlow;
import com.google.domain.registry.flows.domain.DomainTransferCancelFlow;
import com.google.domain.registry.flows.domain.DomainTransferQueryFlow;
import com.google.domain.registry.flows.domain.DomainTransferRejectFlow;
import com.google.domain.registry.flows.domain.DomainTransferRequestFlow;
import com.google.domain.registry.flows.domain.DomainUpdateFlow;
import com.google.domain.registry.flows.host.HostCheckFlow;
import com.google.domain.registry.flows.host.HostCreateFlow;
import com.google.domain.registry.flows.host.HostDeleteFlow;
import com.google.domain.registry.flows.host.HostInfoFlow;
import com.google.domain.registry.flows.host.HostUpdateFlow;
import com.google.domain.registry.flows.poll.PollAckFlow;
import com.google.domain.registry.flows.poll.PollRequestFlow;
import com.google.domain.registry.flows.session.HelloFlow;
import com.google.domain.registry.flows.session.LoginFlow;
import com.google.domain.registry.flows.session.LogoutFlow;
import com.google.domain.registry.model.contact.ContactCommand;
import com.google.domain.registry.model.domain.DomainCommand;
import com.google.domain.registry.model.domain.allocate.AllocateCreateExtension;
import com.google.domain.registry.model.domain.launch.ApplicationIdTargetExtension;
import com.google.domain.registry.model.domain.launch.LaunchCheckExtension;
import com.google.domain.registry.model.domain.launch.LaunchCheckExtension.CheckType;
import com.google.domain.registry.model.domain.launch.LaunchCreateExtension;
import com.google.domain.registry.model.domain.launch.LaunchPhase;
import com.google.domain.registry.model.domain.rgp.RestoreCommand.RestoreOp;
import com.google.domain.registry.model.domain.rgp.RgpUpdateExtension;
import com.google.domain.registry.model.eppinput.EppInput;
import com.google.domain.registry.model.eppinput.EppInput.Hello;
import com.google.domain.registry.model.eppinput.EppInput.InnerCommand;
import com.google.domain.registry.model.eppinput.EppInput.Login;
import com.google.domain.registry.model.eppinput.EppInput.Logout;
import com.google.domain.registry.model.eppinput.EppInput.Poll;
import com.google.domain.registry.model.eppinput.EppInput.ResourceCommandWrapper;
import com.google.domain.registry.model.eppinput.EppInput.Transfer;
import com.google.domain.registry.model.eppinput.EppInput.Transfer.TransferOp;
import com.google.domain.registry.model.eppinput.ResourceCommand;
import com.google.domain.registry.model.host.HostCommand;

import java.util.Map;
import java.util.Set;

/** Registry that can select a flow to handle a given Epp command. */
public class FlowRegistry {

  /** Marker class for unimplemented flows. */
  private abstract static class UnimplementedFlow extends Flow {}

  /** A function type that takes an {@link EppInput} and returns a {@link Flow} class. */
  private abstract static class FlowProvider {
    /** Get the flow associated with this {@link EppInput} or return null to signal no match. */
    Class<? extends Flow> get(EppInput eppInput) {
      InnerCommand innerCommand = eppInput.getCommandWrapper().getCommand();
      return get(eppInput, innerCommand, (innerCommand instanceof ResourceCommandWrapper)
          ? ((ResourceCommandWrapper) innerCommand).getResourceCommand() : null);
    }

    /**
     * Subclasses need to implement this to examine the parameters and choose a flow (or null if
     * the subclass doesn't know of an appropriate flow.
     */
    abstract Class<? extends Flow> get(
        EppInput eppInput, InnerCommand innerCommand, ResourceCommand resourceCommand);
  }

  /** The hello flow is keyed on a special {@code CommandWrapper} type. */
  private static final FlowProvider HELLO_FLOW_PROVIDER = new FlowProvider() {
    @Override
    Class<? extends Flow> get(
        EppInput eppInput, InnerCommand innerCommand, ResourceCommand resourceCommand) {
      return eppInput.getCommandWrapper() instanceof Hello ? HelloFlow.class : null;
    }};

  /** Session flows like login and logout are keyed only on the {@link InnerCommand} type. */
  private static final FlowProvider SESSION_FLOW_PROVIDER = new FlowProvider() {
    private final Map<Class<?>, Class<? extends Flow>> commandFlows =
      ImmutableMap.<Class<?>, Class<? extends Flow>>of(
          Login.class, LoginFlow.class,
          Logout.class, LogoutFlow.class);

    @Override
    Class<? extends Flow> get(
        EppInput eppInput, InnerCommand innerCommand, ResourceCommand resourceCommand) {
      return innerCommand == null ? null : commandFlows.get(innerCommand.getClass());
    }};

  /** Poll flows have an {@link InnerCommand} of type {@link Poll}. */
  private static final FlowProvider POLL_FLOW_PROVIDER = new FlowProvider() {
    @Override
    Class<? extends Flow> get(
        EppInput eppInput, InnerCommand innerCommand, ResourceCommand resourceCommand) {
      if (!(innerCommand instanceof Poll)) {
        return null;
      }
      switch (((Poll) innerCommand).getPollOp()) {
        case ACK:
          return PollAckFlow.class;
        case REQUEST:
          return PollRequestFlow.class;
        default:
          return UnimplementedFlow.class;
      }
    }};

  /**
   * The domain restore command is technically a domain {@literal <update>}, but logically a totally
   * separate flow.
   * <p>
   * This provider must be tried before {@link #RESOURCE_CRUD_FLOW_PROVIDER}. Otherwise, the regular
   * domain update flow will match first.
   */
  private static final FlowProvider DOMAIN_RESTORE_FLOW_PROVIDER = new FlowProvider() {
    @Override
    Class<? extends Flow> get(
        EppInput eppInput, InnerCommand innerCommand, ResourceCommand resourceCommand) {
      if (!(resourceCommand instanceof DomainCommand.Update)) {
        return null;
      }
      RgpUpdateExtension rgpUpdateExtension = eppInput.getSingleExtension(RgpUpdateExtension.class);
      if (rgpUpdateExtension == null) {
        return null;
      }
      // Restore command with an op of "report" is not currently supported.
      return (rgpUpdateExtension.getRestoreCommand().getRestoreOp() == RestoreOp.REQUEST)
          ? DomainRestoreRequestFlow.class
          : UnimplementedFlow.class;
    }};

  /**
   * The claims check flow is keyed on the type of the {@link ResourceCommand} and on having the
   * correct extension with a specific phase value.
   */
  private static final FlowProvider DOMAIN_CHECK_FLOW_PROVIDER = new FlowProvider() {
    @Override
    Class<? extends Flow> get(
        EppInput eppInput, InnerCommand innerCommand, ResourceCommand resourceCommand) {
      if (!(resourceCommand instanceof DomainCommand.Check)) {
        return null;
      }
      LaunchCheckExtension extension = eppInput.getSingleExtension(LaunchCheckExtension.class);
      if (extension == null || CheckType.AVAILABILITY.equals(extension.getCheckType())) {
        // We don't distinguish between registry phases for "avail", so don't bother checking phase.
        return DomainCheckFlow.class;
      }
      if (CheckType.CLAIMS.equals(extension.getCheckType())
          && LaunchPhase.CLAIMS.equals(extension.getPhase())) {
        return ClaimsCheckFlow.class;
      }
      return null;
    }};

  /** General resource CRUD flows are keyed on the type of their {@link ResourceCommand}. */
  private static final FlowProvider RESOURCE_CRUD_FLOW_PROVIDER = new FlowProvider() {
    private final Map<Class<?>, Class<? extends Flow>> resourceCrudFlows =
        new ImmutableMap.Builder<Class<?>, Class<? extends Flow>>()
            .put(ContactCommand.Check.class, ContactCheckFlow.class)
            .put(ContactCommand.Create.class, ContactCreateFlow.class)
            .put(ContactCommand.Delete.class, ContactDeleteFlow.class)
            .put(ContactCommand.Info.class, ContactInfoFlow.class)
            .put(ContactCommand.Update.class, ContactUpdateFlow.class)
            .put(DomainCommand.Create.class, DomainCreateFlow.class)
            .put(DomainCommand.Delete.class, DomainDeleteFlow.class)
            .put(DomainCommand.Info.class, DomainInfoFlow.class)
            .put(DomainCommand.Renew.class, DomainRenewFlow.class)
            .put(DomainCommand.Update.class, DomainUpdateFlow.class)
            .put(HostCommand.Check.class, HostCheckFlow.class)
            .put(HostCommand.Create.class, HostCreateFlow.class)
            .put(HostCommand.Delete.class, HostDeleteFlow.class)
            .put(HostCommand.Info.class, HostInfoFlow.class)
            .put(HostCommand.Update.class, HostUpdateFlow.class)
            .build();

    @Override
    Class<? extends Flow> get(
        EppInput eppInput, InnerCommand innerCommand, ResourceCommand resourceCommand) {
      return resourceCommand == null ? null : resourceCrudFlows.get(resourceCommand.getClass());
    }};

  /** The domain allocate flow has a specific extension. */
  private static final FlowProvider ALLOCATE_FLOW_PROVIDER = new FlowProvider() {
    @Override
    Class<? extends Flow> get(
        EppInput eppInput, InnerCommand innerCommand, ResourceCommand resourceCommand) {
      return (resourceCommand instanceof DomainCommand.Create
          && eppInput.getSingleExtension(AllocateCreateExtension.class) != null)
        ? DomainAllocateFlow.class : null;
    }};

  /**
   * Application CRUD flows have an extension and are keyed on the type of their
   * {@link ResourceCommand}.
   */
  private static final FlowProvider APPLICATION_CRUD_FLOW_PROVIDER = new FlowProvider() {

    private final Map<Class<? extends ResourceCommand>, Class<? extends Flow>> applicationFlows =
        ImmutableMap.<Class<? extends ResourceCommand>, Class<? extends Flow>>of(
            DomainCommand.Create.class, DomainApplicationCreateFlow.class,
            DomainCommand.Delete.class, DomainApplicationDeleteFlow.class,
            DomainCommand.Info.class, DomainApplicationInfoFlow.class,
            DomainCommand.Update.class, DomainApplicationUpdateFlow.class);

    private final Set<LaunchPhase> launchPhases = ImmutableSet.of(
        LaunchPhase.SUNRISE, LaunchPhase.SUNRUSH, LaunchPhase.LANDRUSH);

    @Override
    Class<? extends Flow> get(
        EppInput eppInput, InnerCommand innerCommand, ResourceCommand resourceCommand) {
      if (eppInput.getSingleExtension(ApplicationIdTargetExtension.class) != null) {
        return applicationFlows.get(resourceCommand.getClass());
      }
      LaunchCreateExtension createExtension =
          eppInput.getSingleExtension(LaunchCreateExtension.class);
      // Return a flow if the type is APPLICATION, or if it's null and we are in a launch phase.
      // If the type is specified as REGISTRATION, return null.
      if (createExtension != null) {
        LaunchPhase launchPhase = createExtension.getPhase();
        if (APPLICATION.equals(createExtension.getCreateType())
            || (createExtension.getCreateType() == null && launchPhases.contains(launchPhase))) {
          return applicationFlows.get(resourceCommand.getClass());
        }
      }
      return null;
    }};

  /** Transfer flows have an {@link InnerCommand} of type {@link Transfer}. */
  private static final FlowProvider TRANSFER_FLOW_PROVIDER = new FlowProvider() {
    private final Table<Class<?>, TransferOp, Class<? extends Flow>> transferFlows = ImmutableTable
        .<Class<?>, TransferOp, Class<? extends Flow>>builder()
        .put(ContactCommand.Transfer.class, TransferOp.APPROVE, ContactTransferApproveFlow.class)
        .put(ContactCommand.Transfer.class, TransferOp.CANCEL, ContactTransferCancelFlow.class)
        .put(ContactCommand.Transfer.class, TransferOp.QUERY, ContactTransferQueryFlow.class)
        .put(ContactCommand.Transfer.class, TransferOp.REJECT, ContactTransferRejectFlow.class)
        .put(ContactCommand.Transfer.class, TransferOp.REQUEST, ContactTransferRequestFlow.class)
        .put(DomainCommand.Transfer.class, TransferOp.APPROVE, DomainTransferApproveFlow.class)
        .put(DomainCommand.Transfer.class, TransferOp.CANCEL, DomainTransferCancelFlow.class)
        .put(DomainCommand.Transfer.class, TransferOp.QUERY, DomainTransferQueryFlow.class)
        .put(DomainCommand.Transfer.class, TransferOp.REJECT, DomainTransferRejectFlow.class)
        .put(DomainCommand.Transfer.class, TransferOp.REQUEST, DomainTransferRequestFlow.class)
        .build();

    @Override
    Class<? extends Flow> get(
        EppInput eppInput, InnerCommand innerCommand, ResourceCommand resourceCommand) {
      return resourceCommand != null && innerCommand instanceof Transfer
          ? transferFlows.get(resourceCommand.getClass(), ((Transfer) innerCommand).getTransferOp())
          : null;
    }};

  /** Return the appropriate flow to handle this EPP command. */
  public static Class<? extends Flow> getFlowClass(EppInput eppInput) throws EppException {
    // Do some sanity checking on the input; anything but Hello must have a command type.
    InnerCommand innerCommand = eppInput.getCommandWrapper().getCommand();
    if (innerCommand == null && !(eppInput.getCommandWrapper() instanceof Hello)) {
      throw new MissingCommandException();
    }
    // Try the FlowProviders until we find a match. The order matters because it's possible to
    // match multiple FlowProviders and so more specific matches are tried first.
    for (FlowProvider flowProvider : new FlowProvider[] {
        HELLO_FLOW_PROVIDER,
        SESSION_FLOW_PROVIDER,
        POLL_FLOW_PROVIDER,
        DOMAIN_RESTORE_FLOW_PROVIDER,
        ALLOCATE_FLOW_PROVIDER,
        APPLICATION_CRUD_FLOW_PROVIDER,
        DOMAIN_CHECK_FLOW_PROVIDER,
        RESOURCE_CRUD_FLOW_PROVIDER,
        TRANSFER_FLOW_PROVIDER}) {
      Class<? extends Flow> flowClass = flowProvider.get(eppInput);
      if (flowClass == UnimplementedFlow.class) {
        break;  // We found it, but it's marked as not implemented.
      }
      if (flowClass != null) {
        return flowClass;  // We found it!
      }
    }
    // Nothing usable was found, so throw an exception.
    throw new UnimplementedCommandException(
        innerCommand,
        innerCommand instanceof ResourceCommandWrapper
            ? ((ResourceCommandWrapper) innerCommand).getResourceCommand() : null);
  }

  /** Command missing. */
  static class MissingCommandException extends SyntaxErrorException {
    public MissingCommandException() {
      super("Command missing");
    }
  }
}
