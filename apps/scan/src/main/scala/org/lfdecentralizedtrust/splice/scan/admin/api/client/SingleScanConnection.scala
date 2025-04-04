// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.admin.api.client

import cats.data.OptionT
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.FeaturedAppRight
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.{
  AmuletRules,
  TransferPreapproval,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.externalpartyamuletrules.{
  ExternalPartyAmuletRules,
  TransferCommandCounter,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.{
  IssuingMiningRound,
  OpenMiningRound,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.ans.AnsRules
import org.lfdecentralizedtrust.splice.config.UpgradesConfig
import org.lfdecentralizedtrust.splice.environment.ledger.api.LedgerClient
import org.lfdecentralizedtrust.splice.environment.{
  HttpAppConnection,
  RetryProvider,
  SpliceLedgerClient,
}
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.http.v0.definitions.{
  LookupTransferCommandStatusResponse,
  MigrationSchedule,
}
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.{
  HttpScanAppClient,
  HttpScanSoftDomainMigrationPocAppClient,
}
import org.lfdecentralizedtrust.splice.scan.config.ScanAppClientConfig
import org.lfdecentralizedtrust.splice.scan.store.db.ScanAggregator
import org.lfdecentralizedtrust.splice.store.HistoryBackfilling.SourceMigrationInfo
import org.lfdecentralizedtrust.splice.util.{
  Codec,
  Contract,
  ContractWithState,
  TemplateJsonDecoder,
}
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import com.google.protobuf.ByteString
import org.apache.pekko.stream.Materializer

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import com.digitalasset.canton.data.CantonTimestamp
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{
  DsoRules_CloseVoteRequestResult,
  VoteRequest,
}

/** Connection to the admin API of CC Scan. This is used by other apps
  * to query for the DSO party id.
  */
class SingleScanConnection private[client] (
    private[client] val config: ScanAppClientConfig,
    upgradesConfig: UpgradesConfig,
    protected val clock: Clock,
    retryProvider: RetryProvider,
    outerLoggerFactory: NamedLoggerFactory,
)(implicit
    protected val ec: ExecutionContextExecutor,
    tc: TraceContext,
    protected val mat: Materializer,
    httpClient: HttpClient,
    templateDecoder: TemplateJsonDecoder,
) extends HttpAppConnection(
      config.adminApi,
      upgradesConfig,
      "scan",
      retryProvider,
      outerLoggerFactory.append("scan-connection", config.adminApi.url.toString),
    )
    with ScanConnection
    with BackfillingScanConnection
    with HasUrl {
  def url = config.adminApi.url

  // cached DSO reference. Never changes.
  private val dsoRef: AtomicReference[Option[PartyId]] = new AtomicReference(None)

  /** Query for the DSO party id. This caches the result internally so
    * clients can call this repeatedly without having to implement caching themselves.
    */
  override def getDsoPartyId()(implicit ec: ExecutionContext, tc: TraceContext): Future[PartyId] = {
    val prev = dsoRef.get()
    prev match {
      case Some(partyId) => Future.successful(partyId)
      case None =>
        for {
          partyId <- runHttpCmd(config.adminApi.url, HttpScanAppClient.GetDsoPartyId(List()))
        } yield {
          // The party id never changes so we don’t need to worry about concurrent setters writing different values.
          dsoRef.set(Some(partyId))
          partyId
        }
    }
  }

  override def getAmuletRulesWithState()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[ContractWithState[AmuletRules.ContractId, AmuletRules]] = {
    getAmuletRulesWithState(None)
  }

  def getAmuletRulesWithState(
      cachedAmuletRules: Option[ContractWithState[AmuletRules.ContractId, AmuletRules]]
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[ContractWithState[AmuletRules.ContractId, AmuletRules]] = {
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetAmuletRules(cachedAmuletRules),
    )
  }

  override def getExternalPartyAmuletRules()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[ContractWithState[ExternalPartyAmuletRules.ContractId, ExternalPartyAmuletRules]] = {
    getExternalPartyAmuletRules(None)
  }

  def getExternalPartyAmuletRules(
      cachedExternalPartyAmuletRules: Option[
        ContractWithState[ExternalPartyAmuletRules.ContractId, ExternalPartyAmuletRules]
      ]
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[ContractWithState[ExternalPartyAmuletRules.ContractId, ExternalPartyAmuletRules]] = {
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetExternalPartyAmuletRules(cachedExternalPartyAmuletRules),
    )
  }

  override def getAnsRules()(implicit
      ec: ExecutionContext,
      mat: Materializer,
      tc: TraceContext,
  ): Future[ContractWithState[AnsRules.ContractId, AnsRules]] = {
    getAnsRules(None)
  }

  def getAnsRules(cachedAnsRules: Option[ContractWithState[AnsRules.ContractId, AnsRules]])(implicit
      ec: ExecutionContext,
      mat: Materializer,
      tc: TraceContext,
  ): Future[ContractWithState[AnsRules.ContractId, AnsRules]] = {
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetAnsRules(cachedAnsRules),
    )
  }

  def lookupAnsEntryByParty(
      id: PartyId
  )(implicit
      tc: TraceContext
  ): Future[Option[org.lfdecentralizedtrust.splice.http.v0.definitions.AnsEntry]] = {
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.LookupAnsEntryByParty(id),
    )
  }

  def lookupAnsEntryByName(
      name: String
  )(implicit
      tc: TraceContext
  ): Future[Option[org.lfdecentralizedtrust.splice.http.v0.definitions.AnsEntry]] = {
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.LookupAnsEntryByName(name),
    )
  }

  def listAnsEntries(namePrefix: Option[String], pageSize: Int)(implicit
      tc: TraceContext
  ): Future[Seq[org.lfdecentralizedtrust.splice.http.v0.definitions.AnsEntry]] = {
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.ListAnsEntries(namePrefix, pageSize),
    )
  }

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
    getOpenAndIssuingMiningRounds(Seq.empty, Seq.empty).map { case (open, issuing, _) =>
      (open, issuing)
    }
  }

  def getOpenAndIssuingMiningRounds(
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
  ] = {
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetSortedOpenAndIssuingMiningRounds(
        cachedOpenRounds,
        cachedIssuingRounds,
      ),
    )
  }

  override def lookupFeaturedAppRight(providerPartyId: PartyId)(implicit
      ec: ExecutionContext,
      mat: Materializer,
      tc: TraceContext,
  ): Future[Option[Contract[FeaturedAppRight.ContractId, FeaturedAppRight]]] = {
    runHttpCmd(config.adminApi.url, HttpScanAppClient.LookupFeaturedAppRight(providerPartyId))
  }

  override def listDsoSequencers()(implicit
      tc: TraceContext
  ): Future[Seq[HttpScanAppClient.DomainSequencers]] = {
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.ListDsoSequencers(),
    )
  }

  override def listDsoScans()(implicit
      tc: TraceContext
  ): Future[Seq[HttpScanAppClient.DomainScans]] = {
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.ListDsoScans(),
    ).map(_.map { scans =>
      if (scans.malformed.nonEmpty) {
        logger.warn(
          s"Malformed scans found for domain ${scans.domainId}: ${scans.malformed.keys}. This likely indicates malicious SVs."
        )
      }
      scans
    })
  }

  def getAcsSnapshot(partyId: PartyId)(implicit tc: TraceContext): Future[ByteString] = {
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetAcsSnapshot(partyId),
    )
  }
  def listRoundTotals(
      start: Long,
      end: Long,
  )(implicit
      tc: TraceContext
  ): Future[Seq[org.lfdecentralizedtrust.splice.http.v0.definitions.RoundTotals]] = {
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.ListRoundTotals(start, end),
    )
  }
  def listRoundPartyTotals(
      start: Long,
      end: Long,
  )(implicit
      tc: TraceContext
  ): Future[Seq[org.lfdecentralizedtrust.splice.http.v0.definitions.RoundPartyTotals]] = {
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.ListRoundPartyTotals(start, end),
    )
  }
  def getAggregatedRounds()(implicit
      tc: TraceContext
  ): Future[Option[ScanAggregator.RoundRange]] = {
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetAggregatedRounds,
    )
  }

  def getRoundAggregate(round: Long)(implicit
      tc: TraceContext
  ): Future[Option[ScanAggregator.RoundAggregate]] = {
    for {
      roundTotals <- listRoundTotals(round, round).flatMap { roundTotals =>
        roundTotals.headOption
          .map { rt =>
            decodeRoundTotal(rt).fold(
              err =>
                Future.failed(ScanAggregator.CannotAdvance(s"Failed to decode round totals: $err")),
              rt => Future.successful(Some(rt)),
            )
          }
          .getOrElse(Future.successful(None))
      }
      roundPartyTotals <- listRoundPartyTotals(round, round).flatMap { roundPartyTotals =>
        val (errors, totals) = roundPartyTotals.partitionMap { rt =>
          decodeRoundPartyTotals(rt)
        }
        if (errors.nonEmpty) {
          Future.failed(
            ScanAggregator.CannotAdvance(
              s"""Failed to decode round party totals: ${errors.mkString(", ")}"""
            )
          )
        } else {
          Future.successful(totals.toVector)
        }
      }
    } yield {
      roundTotals.map { rt =>
        ScanAggregator.RoundAggregate(roundTotals = rt, roundPartyTotals = roundPartyTotals)
      }
    }
  }

  private def decodeRoundTotal(
      rt: org.lfdecentralizedtrust.splice.http.v0.definitions.RoundTotals
  ): Either[String, ScanAggregator.RoundTotals] = {
    (for {
      closedRoundEffectiveAt <- CantonTimestamp.fromInstant(rt.closedRoundEffectiveAt.toInstant)
      appRewards <- Codec.decode(Codec.BigDecimal)(rt.appRewards)
      validatorRewards <- Codec.decode(Codec.BigDecimal)(rt.validatorRewards)
      changeToInitialAmountAsOfRoundZero <- Codec
        .decode(Codec.BigDecimal)(rt.changeToInitialAmountAsOfRoundZero)
      changeToHoldingFeesRate <- Codec.decode(Codec.BigDecimal)(rt.changeToHoldingFeesRate)
      cumulativeAppRewards <- Codec.decode(Codec.BigDecimal)(rt.cumulativeAppRewards)
      cumulativeValidatorRewards <- Codec
        .decode(Codec.BigDecimal)(rt.cumulativeValidatorRewards)
      cumulativeChangeToInitialAmountAsOfRoundZero <- Codec
        .decode(Codec.BigDecimal)(rt.cumulativeChangeToInitialAmountAsOfRoundZero)
      cumulativeChangeToHoldingFeesRate <- Codec
        .decode(Codec.BigDecimal)(rt.cumulativeChangeToHoldingFeesRate)
      totalAmuletBalance <- Codec.decode(Codec.BigDecimal)(rt.totalAmuletBalance)
    } yield {
      ScanAggregator.RoundTotals(
        closedRound = rt.closedRound,
        closedRoundEffectiveAt = closedRoundEffectiveAt,
        appRewards = appRewards,
        validatorRewards = validatorRewards,
        changeToInitialAmountAsOfRoundZero = changeToInitialAmountAsOfRoundZero,
        changeToHoldingFeesRate = changeToHoldingFeesRate,
        cumulativeAppRewards = cumulativeAppRewards,
        cumulativeValidatorRewards = cumulativeValidatorRewards,
        cumulativeChangeToInitialAmountAsOfRoundZero = cumulativeChangeToInitialAmountAsOfRoundZero,
        cumulativeChangeToHoldingFeesRate = cumulativeChangeToHoldingFeesRate,
        totalAmuletBalance = totalAmuletBalance,
      )
    })
  }

  private def decodeRoundPartyTotals(
      rt: org.lfdecentralizedtrust.splice.http.v0.definitions.RoundPartyTotals
  ): Either[String, ScanAggregator.RoundPartyTotals] = {
    (for {
      appRewards <- Codec.decode(Codec.BigDecimal)(rt.appRewards)
      validatorRewards <- Codec.decode(Codec.BigDecimal)(rt.validatorRewards)
      trafficPurchasedCcSpent <- Codec.decode(Codec.BigDecimal)(rt.trafficPurchasedCcSpent)
      cumulativeAppRewards <- Codec.decode(Codec.BigDecimal)(rt.cumulativeAppRewards)
      cumulativeValidatorRewards <- Codec.decode(Codec.BigDecimal)(rt.cumulativeValidatorRewards)
      cumulativeChangeToInitialAmountAsOfRoundZero <- Codec
        .decode(Codec.BigDecimal)(rt.cumulativeChangeToInitialAmountAsOfRoundZero)
      cumulativeChangeToHoldingFeesRate <- Codec
        .decode(Codec.BigDecimal)(rt.cumulativeChangeToHoldingFeesRate)
      cumulativeTrafficPurchasedCcSpent <- Codec
        .decode(Codec.BigDecimal)(rt.cumulativeTrafficPurchasedCcSpent)
    } yield {
      ScanAggregator.RoundPartyTotals(
        closedRound = rt.closedRound,
        party = rt.party,
        appRewards = appRewards,
        validatorRewards = validatorRewards,
        trafficPurchased = rt.trafficPurchased,
        trafficPurchasedCcSpent = trafficPurchasedCcSpent,
        trafficNumPurchases = rt.trafficNumPurchases,
        cumulativeAppRewards = cumulativeAppRewards,
        cumulativeValidatorRewards = cumulativeValidatorRewards,
        cumulativeChangeToInitialAmountAsOfRoundZero = cumulativeChangeToInitialAmountAsOfRoundZero,
        cumulativeChangeToHoldingFeesRate = cumulativeChangeToHoldingFeesRate,
        cumulativeTrafficPurchased = rt.cumulativeTrafficPurchased,
        cumulativeTrafficPurchasedCcSpent = cumulativeTrafficPurchasedCcSpent,
        cumulativeTrafficNumPurchases = rt.cumulativeTrafficNumPurchases,
      )
    })
  }

  override def getMigrationSchedule()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): OptionT[Future, MigrationSchedule] =
    OptionT(
      runHttpCmd(
        config.adminApi.url,
        HttpScanAppClient.GetMigrationSchedule(),
      )
    )

  def getSynchronizerIdentities(domainIdPrefix: String)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[HttpScanSoftDomainMigrationPocAppClient.SynchronizerIdentities] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanSoftDomainMigrationPocAppClient.GetSynchronizerIdentities(domainIdPrefix),
    )

  def getSynchronizerBootstrappingTransactions(domainIdPrefix: String)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[HttpScanSoftDomainMigrationPocAppClient.SynchronizerBootstrappingTransactions] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanSoftDomainMigrationPocAppClient.GetSynchronizerBootstrappingTransactions(
        domainIdPrefix
      ),
    )

  override def lookupTransferPreapprovalByParty(receiver: PartyId)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Option[ContractWithState[TransferPreapproval.ContractId, TransferPreapproval]]] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.LookupTransferPreapprovalByParty(receiver),
    )

  override def lookupTransferCommandCounterByParty(receiver: PartyId)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Option[ContractWithState[TransferCommandCounter.ContractId, TransferCommandCounter]]] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.LookupTransferCommandCounterByParty(receiver),
    )

  override def lookupTransferCommandStatus(sender: PartyId, nonce: Long)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Option[LookupTransferCommandStatusResponse]] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.LookupTransferCommandStatus(sender, nonce),
    )

  override def getMigrationInfo(migrationId: Long)(implicit
      tc: TraceContext
  ): Future[Option[SourceMigrationInfo]] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetMigrationInfo(
        migrationId
      ),
    )

  override def getUpdatesBefore(
      migrationId: Long,
      domainId: DomainId,
      before: CantonTimestamp,
      atOrAfter: Option[CantonTimestamp],
      count: Int,
  )(implicit tc: TraceContext): Future[Seq[LedgerClient.GetTreeUpdatesResponse]] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetUpdatesBefore(
        migrationId,
        domainId,
        before,
        atOrAfter,
        count,
      ),
    )

  override def listDsoRulesVoteRequests()(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[Seq[Contract[VoteRequest.ContractId, VoteRequest]]] = ???

  override def listVoteRequestResults(
      actionName: Option[String],
      accepted: Option[Boolean],
      requester: Option[String],
      effectiveFrom: Option[String],
      effectiveTo: Option[String],
      limit: Int,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Seq[DsoRules_CloseVoteRequestResult]] = runHttpCmd(
    config.adminApi.url,
    HttpScanAppClient.ListVoteRequestResults(
      actionName,
      accepted,
      requester,
      effectiveFrom,
      effectiveTo,
      limit,
    ),
  )

  override def listVoteRequestsByTrackingCid(
      voteRequestCids: Seq[VoteRequest.ContractId]
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[
    Seq[Contract[VoteRequest.ContractId, VoteRequest]]
  ] = runHttpCmd(
    config.adminApi.url,
    HttpScanAppClient.ListVoteRequestsByTrackingCid(
      voteRequestCids
    ),
  )

  override def lookupVoteRequest(contractId: VoteRequest.ContractId)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Option[Contract[VoteRequest.ContractId, VoteRequest]]] = runHttpCmd(
    config.adminApi.url,
    HttpScanAppClient.LookupVoteRequest(
      contractId
    ),
  )

}

object SingleScanConnection {
  def withSingleScanConnection[T](
      scanConfig: ScanAppClientConfig,
      upgradesConfig: UpgradesConfig,
      clock: Clock,
      retryProvider: RetryProvider,
      loggerFactory: NamedLoggerFactory,
  )(f: SingleScanConnection => Future[T])(implicit
      ec: ExecutionContextExecutor,
      traceContext: TraceContext,
      mat: Materializer,
      httpClient: HttpClient,
      templateDecoder: TemplateJsonDecoder,
  ): Future[T] =
    for {
      scanConnection <- ScanConnection.singleUncached(
        scanConfig,
        upgradesConfig,
        clock,
        retryProvider,
        loggerFactory,
        retryConnectionOnInitialFailure = true,
      )
      r <- f(scanConnection).andThen { _ => scanConnection.close() }
    } yield r
}

class CachedScanConnection private[client] (
    protected val amuletLedgerClient: SpliceLedgerClient,
    config: ScanAppClientConfig,
    upgradesConfig: UpgradesConfig,
    clock: Clock,
    retryProvider: RetryProvider,
    outerLoggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor,
    tc: TraceContext,
    mat: Materializer,
    httpClient: HttpClient,
    templateDecoder: TemplateJsonDecoder,
) extends SingleScanConnection(config, upgradesConfig, clock, retryProvider, outerLoggerFactory)
    with CachingScanConnection {

  override protected val amuletRulesCacheTimeToLive: NonNegativeFiniteDuration =
    config.amuletRulesCacheTimeToLive

  override protected def runGetAmuletRulesWithState(
      cachedAmuletRules: Option[ContractWithState[AmuletRules.ContractId, AmuletRules]]
  )(implicit tc: TraceContext): Future[ContractWithState[AmuletRules.ContractId, AmuletRules]] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetAmuletRules(cachedAmuletRules),
    )

  override protected def runGetExternalPartyAmuletRules(
      cachedExternalPartyAmuletRules: Option[
        ContractWithState[ExternalPartyAmuletRules.ContractId, ExternalPartyAmuletRules]
      ]
  )(implicit
      tc: TraceContext
  ): Future[ContractWithState[ExternalPartyAmuletRules.ContractId, ExternalPartyAmuletRules]] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetExternalPartyAmuletRules(cachedExternalPartyAmuletRules),
    )

  override protected def runGetAnsRules(
      cachedAnsRules: Option[ContractWithState[AnsRules.ContractId, AnsRules]]
  )(implicit tc: TraceContext): Future[ContractWithState[AnsRules.ContractId, AnsRules]] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetAnsRules(cachedAnsRules),
    )

  override protected def runGetOpenAndIssuingMiningRounds(
      cachedOpenRounds: Seq[ContractWithState[OpenMiningRound.ContractId, OpenMiningRound]],
      cachedIssuingRounds: Seq[ContractWithState[IssuingMiningRound.ContractId, IssuingMiningRound]],
  )(implicit ec: ExecutionContext, mat: Materializer, tc: TraceContext): Future[
    (
        Seq[ContractWithState[OpenMiningRound.ContractId, OpenMiningRound]],
        Seq[ContractWithState[IssuingMiningRound.ContractId, IssuingMiningRound]],
        BigInt,
    )
  ] = runHttpCmd(
    config.adminApi.url,
    HttpScanAppClient.GetSortedOpenAndIssuingMiningRounds(
      cachedOpenRounds,
      cachedIssuingRounds,
    ),
  )

  override def getMigrationSchedule()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): OptionT[Future, MigrationSchedule] = OptionT(
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetMigrationSchedule(),
    )
  )
}
