// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.util

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.metadatav1
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.metadatav1.AnyContract
import org.lfdecentralizedtrust.splice.codegen.java.splice.round
import org.lfdecentralizedtrust.splice.scan.store.ScanStore
import org.lfdecentralizedtrust.splice.store.ChoiceContextContractFetcher
import org.lfdecentralizedtrust.splice.util.{AmuletConfigSchedule, Contract, ContractWithState}

import java.time.Instant
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.{ExecutionContext, Future}

abstract class ChoiceContextBuilder[DisclosedContract, ChoiceContext, Self](
    val activeSynchronizerId: String,
    val excludeDebugFields: Boolean,
) { self: Self =>

  protected def toTokenStandardDisclosedContract[TCid, T](
      contract: Contract[TCid, T],
      synchronizerId: String,
      excludeDebugFields: Boolean,
  ): DisclosedContract

  val disclosedContracts: ListBuffer[DisclosedContract] = ListBuffer.empty
  val contextEntries: mutable.Map[String, metadatav1.AnyValue] = mutable.Map.empty

  def disclose(contract: Contract[?, ?]): Self = {
    disclosedContracts.addOne(
      toTokenStandardDisclosedContract(contract, activeSynchronizerId, excludeDebugFields)
    )
    this
  }

  def addContract(contextKey: String, contract: Contract[?, ?]): Self = {
    contextEntries.addOne(
      contextKey ->
        new metadatav1.anyvalue.AV_ContractId(
          new AnyContract.ContractId(contract.contractId.contractId)
        )
    )
    disclose(contract)
  }

  def addContract(keyedContract: (String, Contract[?, ?])): Self =
    this.addContract(keyedContract._1, keyedContract._2)

  def addContracts(keyedContracts: (String, Contract[?, ?])*): Self = {
    keyedContracts.foreach(this.addContract)
    this
  }

  def addOptionalContract(
      contextKey: String,
      optContract: Option[Contract[?, ?]],
  ): Self = {
    optContract.foreach(addContract(contextKey, _))
    this
  }

  def addOptionalContract(
      keyedContract: (String, Option[Contract[?, ?]])
  ): Self = addOptionalContract(keyedContract._1, keyedContract._2)

  def addOptionalContracts(
      keyedContracts: (String, Option[ContractWithState[?, ?]])*
  ): Self = {
    keyedContracts.foreach(x => addOptionalContract(x._1, x._2.map(_.contract)))
    this
  }

  def addBool(contextKey: String, value: Boolean): Self = {
    contextEntries.addOne(contextKey -> new metadatav1.anyvalue.AV_Bool(value))
    this
  }

  def build(): ChoiceContext
}

object ChoiceContextBuilder {
  private def getAmuletRulesTransferContext[
      DisclosedContract,
      ChoiceContext,
      Builder <: ChoiceContextBuilder[DisclosedContract, ChoiceContext, Builder],
  ](
      store: ScanStore,
      clock: Clock,
      newBuilder: String => Builder,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[(Builder, round.OpenMiningRound)] = {
    val now = clock.now
    for {
      amuletRules <- store.getAmuletRules()
      newestOpenRound <- store
        .lookupLatestUsableOpenMiningRound(now)
        .map(
          _.getOrElse(
            throw io.grpc.Status.NOT_FOUND
              .withDescription(s"No open usable OpenMiningRound found.")
              .asRuntimeException()
          )
        )
      // TODO(#3630) Don't include amulet rules and newest open round when informees all have vetted the newest version.
      externalPartyConfigStateO <- store.lookupLatestExternalPartyConfigState()
    } yield {
      val choiceContextBuilder: Builder = newBuilder(
        AmuletConfigSchedule(amuletRules.payload.configSchedule)
          .getConfigAsOf(now)
          .decentralizedSynchronizer
          .activeSynchronizer
      )

      (
        choiceContextBuilder
          .addContracts(
            "amulet-rules" -> amuletRules,
            "open-round" -> newestOpenRound.contract,
          )
          .addOptionalContract("external-party-config-state" -> externalPartyConfigStateO),
        newestOpenRound.contract.payload,
      )
    }
  }

  def getTwoStepTransferContext[DisclosedContract, ChoiceContext, Builder <: ChoiceContextBuilder[
    DisclosedContract,
    ChoiceContext,
    Builder,
  ]](
      description: String,
      lockedAmuletId: amulet.LockedAmulet.ContractId,
      expiry: Instant,
      requireLockedAmulet: Boolean,
      featuredProvider: Option[PartyId],
      store: ScanStore,
      fetcher: ChoiceContextContractFetcher,
      clock: Clock,
      newBuilder: String => Builder,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[ChoiceContext] = {
    for {
      optLockedAmulet <- fetcher.lookupContractById(
        amulet.LockedAmulet.COMPANION
      )(lockedAmuletId)
      (choiceContextBuilder, _) <- getAmuletRulesTransferContext[
        DisclosedContract,
        ChoiceContext,
        Builder,
      ](store, clock, newBuilder)
      featuredAppRightO <- featuredProvider.fold(
        Future.successful[Option[
          ContractWithState[amulet.FeaturedAppRight.ContractId, amulet.FeaturedAppRight]
        ]](None)
      )(provider =>
        store.lookupFeaturedAppRight(
          provider
        )
      )
    } yield {
      if (optLockedAmulet.isEmpty) {
        // the locked amulet did expire and was unlocked
        if (requireLockedAmulet) {
          val expiresAt =
            CantonTimestamp.fromInstant(expiry)
          throw io.grpc.Status.NOT_FOUND
            .withDescription(
              s"LockedAmulet '$lockedAmuletId' not found for $description, which expires on $expiresAt"
            )
            .asRuntimeException()
        } else {
          // only communicate that the amulet does not need to be unlocked
          newBuilder(choiceContextBuilder.activeSynchronizerId)
            .addBool("expire-lock", false)
            .build()
        }
      } else {
        optLockedAmulet.foreach(contract => choiceContextBuilder.disclose(contract))
        choiceContextBuilder
          // the choice implementation should only attempt to expire the lock if it exists
          .addBool("expire-lock", optLockedAmulet.isDefined)
          .addOptionalContract("featured-app-right", featuredAppRightO.map(_.contract))
          .build()
      }
    }
  }

}
