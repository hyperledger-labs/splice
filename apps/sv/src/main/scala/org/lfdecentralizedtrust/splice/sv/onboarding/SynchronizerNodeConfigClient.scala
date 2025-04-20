// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.onboarding

import cats.data.OptionT
import org.lfdecentralizedtrust.splice.codegen.java.splice.cometbft.{
  CometBftConfig,
  CometBftNodeConfig,
  GovernanceKeyConfig,
  SequencingKeyConfig,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.decentralizedsynchronizer.SynchronizerNodeConfig
import org.lfdecentralizedtrust.splice.environment.SpliceLedgerConnection
import org.lfdecentralizedtrust.splice.store.DsoRulesStore
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import com.digitalasset.canton.drivers.cometbft.SvNodeConfig
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import scala.jdk.CollectionConverters.*
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.OptionConverters.{RichOption, RichOptional}

trait SynchronizerNodeConfigClient {

  protected def getNewSynchronizerNodeConfig(
      synchronizerNodeConfig: Option[SynchronizerNodeConfig],
      localSvNodeConfig: SvNodeConfig,
  ): SynchronizerNodeConfig =
    new SynchronizerNodeConfig(
      getNewCometBftNodeConfig(localSvNodeConfig),
      synchronizerNodeConfig.flatMap(_.sequencer.toScala).toJava,
      synchronizerNodeConfig.flatMap(_.mediator.toScala).toJava,
      synchronizerNodeConfig.flatMap(_.scan.toScala).toJava,
      synchronizerNodeConfig.flatMap(_.legacySequencerConfig.toScala).toJava,
    )

  protected def getNewCometBftNodeConfig(
      localSvNodeConfig: SvNodeConfig
  ): CometBftConfig =
    new CometBftConfig(
      localSvNodeConfig.cometbftNodes.map { case (nodeId, config) =>
        (
          nodeId,
          new CometBftNodeConfig(
            config.validatorPubKey,
            config.votingPower,
          ),
        )
      }.asJava,
      localSvNodeConfig.governanceKeys
        .map(key => new GovernanceKeyConfig(key.pubKey))
        .asJava,
      localSvNodeConfig.sequencingKeys
        .map(key => new SequencingKeyConfig(key.pubKey))
        .asJava,
    )

  protected def getCometBftNodeConfigDsoState(dsoStore: SvDsoStore, svParty: PartyId)(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): OptionT[Future, (DsoRulesStore.DsoRulesWithSvNodeState, Option[SynchronizerNodeConfig])] = {
    for {
      rulesAndState <- OptionT.liftF(dsoStore.getDsoRulesWithSvNodeState(svParty))
      synchronizerId = rulesAndState.dsoRules.domain
      nodeState = rulesAndState.svNodeState.payload
      synchronizerNodeConfig =
        nodeState.state.synchronizerNodes.asScala.get(
          synchronizerId.toProtoPrimitive
        )
    } yield (rulesAndState, synchronizerNodeConfig)
  }

  protected def updateSynchronizerNodeConfig(
      rulesAndState: DsoRulesStore.DsoRulesWithSvNodeState,
      newSvNodeConfig: SynchronizerNodeConfig,
      store: SvDsoStore,
      connection: SpliceLedgerConnection,
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[Unit] = {
    val synchronizerId = rulesAndState.dsoRules.domain
    val cmd = rulesAndState.dsoRules.exercise(
      _.exerciseDsoRules_SetSynchronizerNodeConfig(
        store.key.svParty.toProtoPrimitive,
        synchronizerId.toProtoPrimitive,
        newSvNodeConfig,
        rulesAndState.svNodeState.contractId,
      )
    )
    for {
      _ <- connection
        .submit(Seq(store.key.svParty), Seq(store.key.dsoParty), cmd)
        .noDedup
        .yieldResult()
    } yield ()
  }

}
