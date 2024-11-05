// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.admin.api.client

import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.AmuletRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.externalpartyamuletrules.ExternalPartyAmuletRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.{
  IssuingMiningRound,
  OpenMiningRound,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.ans.AnsRules
import org.lfdecentralizedtrust.splice.environment.SpliceLedgerClient
import org.lfdecentralizedtrust.splice.scan.admin.api.client.ScanConnection.{
  CachedAnsRules,
  CachedAmuletRules,
  CachedMiningRounds,
}
import org.lfdecentralizedtrust.splice.util.ContractWithState
import org.lfdecentralizedtrust.splice.util.PrettyInstances.PrettyContractId
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import org.lfdecentralizedtrust.splice.util.PrettyInstances.*
import org.apache.pekko.stream.Materializer

import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}

trait CachingScanConnection extends ScanConnection {

  protected val amuletLedgerClient: SpliceLedgerClient
  protected val amuletRulesCacheTimeToLive: NonNegativeFiniteDuration

  private val amuletRulesCache: AtomicReference[Option[CachedAmuletRules]] =
    new AtomicReference(None)

  // While ExternalPartyAmuletRules should never change relying on that assumption seems dangerous and cache invalidation doesn't work
  // since we'll only learn about the contract being inactive when submitting not when preparing.
  // So we don't set a cache expiration time and instead only use the cache to avoid transmitting the contract payload
  // if it already exists.
  private val externalPartyAmuletRulesCache: AtomicReference[
    Option[ContractWithState[ExternalPartyAmuletRules.ContractId, ExternalPartyAmuletRules]]
  ] =
    new AtomicReference(None)

  private val ansRulesCache: AtomicReference[Option[CachedAnsRules]] =
    new AtomicReference(None)

  private val cachedRounds: AtomicReference[CachedMiningRounds] =
    new AtomicReference(CachedMiningRounds())

  // register the callback to potentially invalidate the AmuletRules cache.
  amuletLedgerClient.registerInactiveContractsCallback(signalPossiblyOutdatedAmuletRulesCache)
  // and the rounds cache
  amuletLedgerClient.registerInactiveContractsCallback(signalPossiblyOutdatedRoundsCache)
  // and also nuke everything when we get an error that we're trying to downgrade.
  amuletLedgerClient.registerContractDowngradeErrorCallback(() => signalOutdatedCache())

  /** We cache the AmuletRules contract, but it may be come outdated if, e.g., the DSO updates the config schedule.
    * The inactive-contracts error message that the ledger returns does not specify the template-id, thus we need
    * to check for each inactive-contract we receive from the ledger that the failure was not caused by an outdated cache
    * of the AmuletRules.
    */
  private def signalPossiblyOutdatedAmuletRulesCache(inactiveContract: String): Unit =
    amuletRulesCache.get() match {
      case Some(CachedAmuletRules(_, cachedContract))
          if (cachedContract.contractId.contractId: String) == inactiveContract =>
        logger.info(
          show"Invalidating the AmuletRules cache with value ${PrettyContractId(cachedContract.contract)}"
        )(TraceContext.empty)
        amuletRulesCache.set(None)
      case _ => ()
    }

  private def signalPossiblyOutdatedRoundsCache(inactiveContract: String): Unit = {
    val rounds = cachedRounds.get()
    if (rounds containsContractId inactiveContract) {
      logger.debug(
        show"Invalidating the rounds cache at ${rounds.describeRounds}"
      )(TraceContext.empty)
      cachedRounds.set(CachedMiningRounds())
    } else ()
  }

  private def signalOutdatedCache(): Unit = {
    logger.debug("Invalidating AmuletRules and rounds cache after a failed contract downgrade")(
      TraceContext.empty
    )
    amuletRulesCache.set(None)
    cachedRounds.set(CachedMiningRounds())
  }

  override def getAmuletRulesWithState()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[ContractWithState[AmuletRules.ContractId, AmuletRules]] = {
    val now = clock.now
    amuletRulesCache.get() match {
      case Some(ccr @ CachedAmuletRules(_, amuletRules)) if ccr validAsOf now =>
        Future.successful(amuletRules)
      case cacheO =>
        // Note that here and at other caches in this class, multiple concurrent cache misses result in multiple
        // requests that are not deduplicated against each other. We accept that as we expect low concurrency by default.
        logger.debug(
          s"AmuletRules cache is empty or outdated, retrieving AmuletRules from CC scan."
        )
        for {
          amuletRules <- runGetAmuletRulesWithState(cacheO.map(_.amuletRules))
        } yield {
          amuletRulesCache.set(
            Some(
              CachedAmuletRules(
                now.add(amuletRulesCacheTimeToLive.asJava),
                amuletRules,
              )
            )
          )
          amuletRules
        }
    }
  }
  protected def runGetAmuletRulesWithState(
      cachedAmuletRules: Option[ContractWithState[AmuletRules.ContractId, AmuletRules]]
  )(implicit tc: TraceContext): Future[ContractWithState[AmuletRules.ContractId, AmuletRules]]

  override def getExternalPartyAmuletRules()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[ContractWithState[ExternalPartyAmuletRules.ContractId, ExternalPartyAmuletRules]] = {
    for {
      externalPartyAmuletRules <- runGetExternalPartyAmuletRules(
        externalPartyAmuletRulesCache.get()
      )
    } yield {
      externalPartyAmuletRulesCache.set(
        Some(
          externalPartyAmuletRules
        )
      )
      externalPartyAmuletRules
    }
  }

  protected def runGetExternalPartyAmuletRules(
      cachedExternalPartyAmuletRules: Option[
        ContractWithState[ExternalPartyAmuletRules.ContractId, ExternalPartyAmuletRules]
      ]
  )(implicit
      tc: TraceContext
  ): Future[ContractWithState[ExternalPartyAmuletRules.ContractId, ExternalPartyAmuletRules]]

  override def getAnsRules()(implicit
      ec: ExecutionContext,
      mat: Materializer,
      tc: TraceContext,
  ): Future[ContractWithState[AnsRules.ContractId, AnsRules]] = {
    val now = clock.now
    getAmuletRulesWithState().flatMap { amuletRules =>
      ansRulesCache.get() match {
        case Some(ccr @ CachedAnsRules(_, ansRules)) if ccr.validAsOf(now, amuletRules) =>
          Future.successful(ansRules)
        case cacheO =>
          logger.debug(
            s"ansRules cache is empty or outdated, retrieving AnsRules from CC scan."
          )
          for {
            ansRules <- runGetAnsRules(cacheO.map(_.ansRules))
          } yield {
            ansRulesCache.set(
              Some(
                CachedAnsRules(
                  now.add(amuletRulesCacheTimeToLive.asJava),
                  ansRules,
                )
              )
            )
            ansRules
          }
      }
    }
  }

  protected def runGetAnsRules(
      cachedAnsRules: Option[ContractWithState[AnsRules.ContractId, AnsRules]]
  )(implicit tc: TraceContext): Future[ContractWithState[AnsRules.ContractId, AnsRules]]

  override def getOpenAndIssuingMiningRounds()(implicit
      ec: ExecutionContext,
      mat: Materializer,
      tc: TraceContext,
  ): Future[
    (
        Seq[ContractWithState[OpenMiningRound.ContractId, OpenMiningRound]],
        Seq[ContractWithState[IssuingMiningRound.ContractId, IssuingMiningRound]],
    )
  ] = {
    val now = clock.now
    val cache = cachedRounds.get()
    getAmuletRulesWithState().flatMap { amuletRules =>
      if (cache.validAsOf(now, amuletRules)) {
        logger.info(
          s"Using the client-cache (validUntil ${cache.cacheValidUntil}) to load ${cache.describeRounds}."
        )
        Future.successful(cache.getRoundTuple)
      } else {
        logger.debug(
          s"querying the scan app for the latest round information because the cache expired at ${cache.cacheValidUntil}"
        )
        for {
          (openRounds, issuingRounds, ttlInMicros) <- runGetOpenAndIssuingMiningRounds(
            cache.sortedOpenMiningRounds,
            cache.sortedIssuingMiningRounds,
          )

        } yield {
          val newValidUntil = now.add(Duration.ofNanos(ttlInMicros.longValue * 1000))
          val newRoundsCache = CachedMiningRounds(
            Some(newValidUntil),
            openRounds,
            issuingRounds,
          )
          logger.info(s"New rounds-cache is $newRoundsCache.")
          cachedRounds.set(newRoundsCache)
          cachedRounds.get().getRoundTuple
        }
      }
    }
  }

  protected def runGetOpenAndIssuingMiningRounds(
      cachedOpenRounds: Seq[ContractWithState[OpenMiningRound.ContractId, OpenMiningRound]],
      cachedIssuingRounds: Seq[ContractWithState[IssuingMiningRound.ContractId, IssuingMiningRound]],
  )(implicit
      ec: ExecutionContext,
      mat: Materializer,
      tc: TraceContext,
  ): Future[
    (
        Seq[ContractWithState[OpenMiningRound.ContractId, OpenMiningRound]],
        Seq[ContractWithState[IssuingMiningRound.ContractId, IssuingMiningRound]],
        BigInt,
    )
  ]
}
