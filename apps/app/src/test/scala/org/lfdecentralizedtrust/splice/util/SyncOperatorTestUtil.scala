// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.{Member, PartyId, SynchronizerId}
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.codegen.java.splice.decentralizedsynchronizer.RegisteredSynchronizer
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.IssuingMiningRound
import org.lfdecentralizedtrust.splice.codegen.java.splice.types.Round
import org.lfdecentralizedtrust.splice.console.SyncOperatorAppBackendReference
import org.lfdecentralizedtrust.splice.environment.SequencerAdminConnection
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  SpliceTestConsoleEnvironment,
  TestCommon,
}

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** Buying traffic for a registered synchronizer, and reading it back off the operator's own
  * sequencer.
  */
trait SyncOperatorTestUtil extends TestCommon {
  this: CommonAppInstanceReferences =>

  /** Exercises `AmuletRules_BuyMemberTraffic` with the registration disclosed, which is the only
    * way a participant that hosts neither the DSO nor the operator can buy.
    */
  protected def buyTraffic(
      buyer: PartyId,
      member: Member,
      synchronizerId: SynchronizerId,
      registration: Option[
        ContractWithState[RegisteredSynchronizer.ContractId, RegisteredSynchronizer]
      ],
      dsoParty: PartyId,
      trafficAmount: Long,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ): splice.amuletrules.AmuletRules_BuyMemberTrafficResult = {
    val transferContext =
      sv1ScanBackend.getTransferContextWithInstances(CantonTimestamp.now())
    val amulets = aliceWalletClient.list().amulets.map(_.contract.contractId.contractId)

    aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
      .submitWithResult(
        aliceValidatorBackend.config.ledgerApiUser,
        actAs = Seq(buyer),
        readAs = Seq(buyer),
        update = transferContext.amuletRules.contract.contractId
          .exerciseAmuletRules_BuyMemberTraffic(
            amulets
              .map[splice.amuletrules.TransferInput](cid =>
                new splice.amuletrules.transferinput.InputAmulet(
                  new splice.amulet.Amulet.ContractId(cid)
                )
              )
              .asJava,
            new splice.amuletrules.TransferContext(
              transferContext.latestOpenMiningRound.contract.contractId,
              Map.empty[Round, IssuingMiningRound.ContractId].asJava,
              Map.empty[String, splice.amulet.ValidatorRight.ContractId].asJava,
              None.toJava,
            ),
            buyer.toProtoPrimitive,
            member.toProtoPrimitive,
            synchronizerId.toProtoPrimitive,
            // a registered synchronizer is pinned to migration id 0
            0L,
            trafficAmount,
            Some(dsoParty.toProtoPrimitive).toJava,
            registration.map(_.contractId).toJava,
          ),
        disclosedContracts = DisclosedContracts
          .forTesting(
            transferContext.amuletRules,
            (Seq[ContractWithState[?, ?]](transferContext.latestOpenMiningRound) ++ registration)*
          )
          .toLedgerApiDisclosedContracts,
      )
      .exerciseResult
  }

  /** The sync operator app that is running, as a test may run another instance in its place. */
  protected def runningSyncOperatorBackend(implicit
      env: SpliceTestConsoleEnvironment
  ): SyncOperatorAppBackendReference =
    env.syncOperators.local
      .find(_.is_running)
      .getOrElse(fail("No sync operator app is running"))

  protected def trafficState(
      member: Member
  )(implicit env: SpliceTestConsoleEnvironment): Option[SequencerAdminConnection.TrafficState] =
    runningSyncOperatorBackend.appState.sequencerAdminConnection
      .lookupSequencerTrafficControlState(member)
      .futureValue

  protected def extraTrafficLimit(
      member: Member
  )(implicit env: SpliceTestConsoleEnvironment): Long =
    trafficState(member).fold(0L)(_.extraTrafficLimit.value)
}
