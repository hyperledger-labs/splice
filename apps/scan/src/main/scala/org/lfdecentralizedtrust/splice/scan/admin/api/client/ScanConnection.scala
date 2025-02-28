// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.admin.api.client

import cats.data.OptionT
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.{FeaturedAppRight, ValidatorRight}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.{
  AmuletRules,
  AppTransferContext,
  PaymentTransferContext,
  TransferContext,
  TransferPreapproval,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.externalpartyamuletrules.{
  ExternalPartyAmuletRules,
  TransferCommandCounter,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.types.Round
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.{
  IssuingMiningRound,
  OpenMiningRound,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.ans.AnsRules
import org.lfdecentralizedtrust.splice.config.UpgradesConfig
import org.lfdecentralizedtrust.splice.environment.{
  HttpAppConnection,
  PackageIdResolver,
  RetryFor,
  RetryProvider,
  SpliceLedgerClient,
  SpliceLedgerConnection,
}
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.http.v0.definitions.{
  LookupTransferCommandStatusResponse,
  MigrationSchedule,
}
import org.lfdecentralizedtrust.splice.scan.admin.api.client.ScanConnection.*
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient.TransferContextWithInstances
import org.lfdecentralizedtrust.splice.scan.config.ScanAppClientConfig
import org.lfdecentralizedtrust.splice.util.PrettyInstances.*
import org.lfdecentralizedtrust.splice.util.*
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.FlagCloseableAsync
import com.digitalasset.canton.logging.{NamedLoggerFactory, TracedLogger}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{SynchronizerId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.jdk.CollectionConverters.MapHasAsJava
import scala.jdk.OptionConverters.*

trait ScanConnection extends PackageIdResolver.HasAmuletRules with FlagCloseableAsync {

  protected val clock: Clock
  protected val retryProvider: RetryProvider
  implicit protected val ec: ExecutionContext
  implicit protected val mat: Materializer
  protected def logger: TracedLogger

  def getDsoPartyId()(implicit ec: ExecutionContext, tc: TraceContext): Future[PartyId]

  /** Query for the DSO party id, retrying until it succeeds.
    *
    * Intended to be used for app init.
    */
  def getDsoPartyIdWithRetries()(implicit ec: ExecutionContext, tc: TraceContext): Future[PartyId] =
    retryProvider.getValueWithRetries(
      RetryFor.WaitingOnInitDependency,
      "scan_read_dso_party_id",
      "DSO party ID from scan",
      getDsoPartyId(),
      logger,
    )

  def getAmuletRulesWithState()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[ContractWithState[AmuletRules.ContractId, AmuletRules]]

  def getExternalPartyAmuletRules()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[ContractWithState[ExternalPartyAmuletRules.ContractId, ExternalPartyAmuletRules]]

  def getAnsRules()(implicit
      ec: ExecutionContext,
      mat: Materializer,
      tc: TraceContext,
  ): Future[ContractWithState[AnsRules.ContractId, AnsRules]]

  def getOpenAndIssuingMiningRounds()(implicit
      ec: ExecutionContext,
      mat: Materializer,
      tc: TraceContext,
  ): Future[
    (
        Seq[ContractWithState[OpenMiningRound.ContractId, OpenMiningRound]],
        Seq[ContractWithState[IssuingMiningRound.ContractId, IssuingMiningRound]],
    )
  ]

  def lookupFeaturedAppRight(providerPartyId: PartyId)(implicit
      ec: ExecutionContext,
      mat: Materializer,
      tc: TraceContext,
  ): Future[Option[Contract[FeaturedAppRight.ContractId, FeaturedAppRight]]]

  def listDsoSequencers()(implicit
      tc: TraceContext
  ): Future[Seq[HttpScanAppClient.DomainSequencers]]

  def listDsoScans()(implicit tc: TraceContext): Future[Seq[HttpScanAppClient.DomainScans]]

  def getTransferContextWithInstances()(implicit
      ec: ExecutionContext,
      mat: Materializer,
      tc: TraceContext,
  ): Future[HttpScanAppClient.TransferContextWithInstances] = {
    for {
      openAndIssuingRounds <- getOpenAndIssuingMiningRounds()
      openRounds = openAndIssuingRounds._1
      latestOpenMiningRound = SpliceUtil.selectLatestOpenMiningRound(clock.now, openRounds)
      amuletRules <- getAmuletRulesWithState()
    } yield TransferContextWithInstances(amuletRules, latestOpenMiningRound, openRounds)
  }

  def getAmuletRulesDomain: GetAmuletRulesDomain = { () => implicit tc =>
    getAmuletRulesWithState()
      .flatMap(
        _.state.fold(
          Future.successful,
          Future failed Status.FAILED_PRECONDITION
            .withDescription("AmuletRules is in-flight, no current global domain")
            .asRuntimeException(),
        )
      )
  }

  def getAmuletRules()(implicit
      tc: TraceContext
  ): Future[Contract[AmuletRules.ContractId, AmuletRules]] =
    getAmuletRulesWithState().map(_.contract)

  def getLatestOpenMiningRound()(implicit
      ec: ExecutionContext,
      mat: Materializer,
      tc: TraceContext,
  ): Future[ContractWithState[OpenMiningRound.ContractId, OpenMiningRound]] = {
    for {
      (openRounds, _) <- getOpenAndIssuingMiningRounds()
      now = clock.now
      openRound = SpliceUtil.selectLatestOpenMiningRound(now, openRounds)
    } yield openRound
  }

  def getAppTransferContext(ledgerConnection: SpliceLedgerConnection, providerPartyId: PartyId)(
      implicit
      tc: TraceContext,
      ec: ExecutionContext,
      mat: Materializer,
  ): Future[(AppTransferContext, DisclosedContracts.NE)] = {
    for {
      context <- getTransferContextWithInstances()
      featured <- lookupFeaturedAppRight(providerPartyId)
    } yield {
      val amuletRules = context.amuletRules
      val openMiningRound = context.latestOpenMiningRound
      (
        new AppTransferContext(
          amuletRules.contractId,
          openMiningRound.contractId,
          featured.map(_.contractId).toJava,
        ),
        ledgerConnection.disclosedContracts(amuletRules, openMiningRound),
      )
    }
  }

  def getPaymentTransferContext(ledgerConnection: SpliceLedgerConnection, providerPartyId: PartyId)(
      implicit tc: TraceContext
  ): Future[(PaymentTransferContext, DisclosedContracts.NE)] =
    for {
      (appTransferContext, disclosedContracts) <- getAppTransferContext(
        ledgerConnection,
        providerPartyId,
      )
    } yield (
      new PaymentTransferContext(
        appTransferContext.amuletRules,
        new TransferContext(
          appTransferContext.openMiningRound,
          Map.empty[Round, IssuingMiningRound.ContractId].asJava,
          Map.empty[String, ValidatorRight.ContractId].asJava,
          appTransferContext.featuredAppRight,
        ),
      ),
      disclosedContracts,
    )

  def getAppTransferContextForRound(
      ledgerConnection: SpliceLedgerConnection,
      providerPartyId: PartyId,
      round: Round,
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
      mat: Materializer,
  ): Future[
    Either[String, (AppTransferContext, DisclosedContracts.NE)]
  ] = {
    for {
      context <- getTransferContextWithInstances()
      featured <- lookupFeaturedAppRight(providerPartyId)
    } yield {
      val amuletRules = context.amuletRules
      context.openMiningRounds.find(_.payload.round == round) match {
        case Some(openMiningRound) =>
          Right(
            (
              new AppTransferContext(
                amuletRules.contractId,
                openMiningRound.contractId,
                featured.map(_.contractId).toJava,
              ),
              ledgerConnection.disclosedContracts(amuletRules, openMiningRound),
            )
          )
        case None => Left("round is not an open mining round")
      }
    }
  }

  def getMigrationSchedule()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): OptionT[Future, MigrationSchedule]

  def lookupTransferCommandCounterByParty(receiver: PartyId)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Option[ContractWithState[TransferCommandCounter.ContractId, TransferCommandCounter]]]

  def lookupTransferCommandStatus(sender: PartyId, nonce: Long)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Option[LookupTransferCommandStatusResponse]]

  def lookupTransferPreapprovalByParty(receiver: PartyId)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Option[ContractWithState[TransferPreapproval.ContractId, TransferPreapproval]]]

}

object ScanConnection {
  def singleCached(
      amuletLedgerClient: SpliceLedgerClient,
      config: ScanAppClientConfig,
      upgradesConfig: UpgradesConfig,
      clock: Clock,
      retryProvider: RetryProvider,
      loggerFactory: NamedLoggerFactory,
      retryConnectionOnInitialFailure: Boolean = true,
  )(implicit
      ec: ExecutionContextExecutor,
      tc: TraceContext,
      mat: Materializer,
      httpClient: HttpClient,
      templateDecoder: TemplateJsonDecoder,
  ): Future[ScanConnection] =
    HttpAppConnection.checkVersionOrClose(
      new CachedScanConnection(
        amuletLedgerClient,
        config,
        upgradesConfig,
        clock,
        retryProvider,
        loggerFactory,
      ),
      retryConnectionOnInitialFailure,
    )

  def singleUncached(
      config: ScanAppClientConfig,
      upgradesConfig: UpgradesConfig,
      clock: Clock,
      retryProvider: RetryProvider,
      loggerFactory: NamedLoggerFactory,
      retryConnectionOnInitialFailure: Boolean,
  )(implicit
      ec: ExecutionContextExecutor,
      tc: TraceContext,
      mat: Materializer,
      httpClient: HttpClient,
      templateDecoder: TemplateJsonDecoder,
  ): Future[SingleScanConnection] =
    HttpAppConnection.checkVersionOrClose(
      new SingleScanConnection(config, upgradesConfig, clock, retryProvider, loggerFactory),
      retryConnectionOnInitialFailure,
    )

  private[client] case class CachedAmuletRules(
      cacheValidUntil: CantonTimestamp,
      amuletRules: ContractWithState[AmuletRules.ContractId, AmuletRules],
  ) {
    def validAsOf(now: CantonTimestamp): Boolean =
      now.isBefore(cacheValidUntil) && amuletRules.state.fold(
        assignment =>
          AmuletConfigSchedule(amuletRules)
            .getConfigAsOf(now)
            .decentralizedSynchronizer
            .activeSynchronizer == assignment.toProtoPrimitive,
        false,
      )
  }

  private[client] case class CachedAnsRules(
      cacheValidUntil: CantonTimestamp,
      ansRules: ContractWithState[AnsRules.ContractId, AnsRules],
  ) {
    def validAsOf(now: CantonTimestamp, amuletRules: ContractWithState[?, AmuletRules]): Boolean =
      now.isBefore(cacheValidUntil) && amuletRules.state.fold(
        assignment =>
          AmuletConfigSchedule(amuletRules)
            .getConfigAsOf(now)
            .decentralizedSynchronizer
            .activeSynchronizer == assignment.toProtoPrimitive,
        false,
      )
  }

  private[client] case class CachedMiningRounds(
      cacheValidUntil: Option[CantonTimestamp] = None,
      sortedOpenMiningRounds: Seq[ContractWithState[OpenMiningRound.ContractId, OpenMiningRound]] =
        Seq(),
      sortedIssuingMiningRounds: Seq[
        ContractWithState[IssuingMiningRound.ContractId, IssuingMiningRound]
      ] = Seq(),
  ) {
    def validAsOf(now: CantonTimestamp, amuletRules: ContractWithState[?, AmuletRules]): Boolean =
      cacheValidUntil.exists(validUntil => now.isBefore(validUntil)) && {
        val states = (sortedOpenMiningRounds.view ++ sortedIssuingMiningRounds).map(_.state).toSet
        states.sizeIs <= 1 && states.forall(
          _.fold(
            assignment =>
              AmuletConfigSchedule(amuletRules)
                .getConfigAsOf(now)
                .decentralizedSynchronizer
                .activeSynchronizer == assignment.toProtoPrimitive,
            false,
          )
        )
      }

    def getRoundTuple: (
        Seq[ContractWithState[OpenMiningRound.ContractId, OpenMiningRound]],
        Seq[ContractWithState[IssuingMiningRound.ContractId, IssuingMiningRound]],
    ) =
      (sortedOpenMiningRounds, sortedIssuingMiningRounds)

    def containsContractId(contractId: String): Boolean =
      (sortedOpenMiningRounds.view ++ sortedIssuingMiningRounds).exists { c =>
        (c.contractId.contractId: String) == contractId
      }

    def describeRounds = s"following issuing rounds: ${sortedIssuingMiningRounds
        .map(_.payload.round.number)}, and following open rounds: ${sortedOpenMiningRounds.map(_.payload.round.number)}"
  }

  type GetAmuletRulesDomain = () => TraceContext => Future[SynchronizerId]
}

/** Connection to the admin API of CC Scan usable for version and availability checks
  * before a ledger connection is available.
  */
// TODO(tech-debt) consider removing this if we stop doing early version checks
class MinimalScanConnection(
    config: ScanAppClientConfig,
    upgradesConfig: UpgradesConfig,
    retryProvider: RetryProvider,
    loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor,
    tc: TraceContext,
    mat: Materializer,
    httpClient: HttpClient,
    templateDecoder: TemplateJsonDecoder,
) extends HttpAppConnection(
      config.adminApi,
      upgradesConfig,
      "scan",
      retryProvider,
      loggerFactory,
    ) {}
object MinimalScanConnection {
  def apply(
      config: ScanAppClientConfig,
      upgradesConfig: UpgradesConfig,
      retryProvider: RetryProvider,
      loggerFactory: NamedLoggerFactory,
      retryConnectionOnInitialFailure: Boolean = true,
  )(implicit
      ec: ExecutionContextExecutor,
      tc: TraceContext,
      mat: Materializer,
      httpClient: HttpClient,
      templateDecoder: TemplateJsonDecoder,
  ): Future[MinimalScanConnection] =
    HttpAppConnection.checkVersionOrClose(
      new MinimalScanConnection(config, upgradesConfig, retryProvider, loggerFactory),
      retryConnectionOnInitialFailure,
    )
}
