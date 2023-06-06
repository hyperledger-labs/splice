package com.daml.network.sv.init

import com.daml.network.codegen.java.cc.v1test as ccV1Test
import com.daml.network.codegen.java.cn
import com.daml.network.environment.*
import com.daml.network.environment.ledger.api.DedupOffset
import com.daml.network.store.CNNodeAppStoreWithIngestion
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.sv.cometbft.CometBftNode
import com.daml.network.sv.config.{SvAppBackendConfig, SvOnboardingConfig}
import com.daml.network.sv.store.SvSvcStore
import com.daml.network.sv.util.SvUtil
import com.daml.network.util.CNNodeUtil.{
  defaultCoinConfig,
  defaultCoinConfigSchedule,
  defaultEnabledChoices,
}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

/** Container for the methods required by the SvApp to initialize the founding SV node. */
class FoundingNodeInitializer(
    svcStoreWithIngestion: CNNodeAppStoreWithIngestion[SvSvcStore],
    config: SvAppBackendConfig,
    foundingConfig: SvOnboardingConfig.FoundCollective,
    domainId: DomainId,
    cometBftNode: Option[CometBftNode],
    override protected val loggerFactory: NamedLoggerFactory,
    retryProvider: RetryProvider,
)(implicit ec: ExecutionContext, tc: TraceContext)
    extends NamedLogging {

  private val svcStore = svcStoreWithIngestion.store
  private val svcParty = svcStore.key.svcParty
  private val svParty = svcStore.key.svParty

  /** The one and only entry-point: found a fresh collective, given a properly allocated SVC party */
  def foundCollective(): Future[Unit] = {
    for {
      _ <- retryProvider.retryForAutomation(
        "bootstrapping SVC",
        bootstrapSvc(),
        logger,
      )
      // make sure we can't act as the svc party anymore now that `SvcBootstrap` is done
      _ <- waiveSvcRights()
    } yield ()
  }

  // Create SvcRules and CoinRules and open the first mining round
  private def bootstrapSvc(): Future[Unit] = {
    for {
      coinRules <- svcStore.lookupCoinRules()
      // TODO(#5428): retry on failure
      founderDomainNodes <- SvUtil
        .getFounderDomainNodeConfig(cometBftNode)
        .fold(error => sys.error(s"Failed to initialize the domain nodes: $error"), identity)
      _ <- svcStore.lookupSvcRulesWithOffset().flatMap {
        case QueryResult(offset, None) => {
          coinRules match {
            case Some(coinRules) =>
              sys.error(
                "A CoinRules contract was found but no SvcRules contract exists. " +
                  show"This should never happen.\nCoinRules: $coinRules"
              )
            case None =>
              logger.info(s"Bootstrapping SVC as $svcParty with BFT nodes $founderDomainNodes")
              svcStoreWithIngestion.connection
                .submitCommands(
                  actAs = Seq(svcParty),
                  readAs = Seq.empty,
                  commands = new cn.svcbootstrap.SvcBootstrap(
                    svcParty.toProtoPrimitive,
                    svParty.toProtoPrimitive,
                    foundingConfig.name,
                    founderDomainNodes,
                    defaultCoinConfig(
                      foundingConfig.initialTickDuration,
                      foundingConfig.initialMaxNumInputs,
                      domainId,
                    ),
                    foundingConfig.initialCoinPrice.bigDecimal,
                    SvUtil.defaultSvcRulesConfig(),
                    defaultEnabledChoices,
                    config.isDevNet,
                  ).createAnd
                    .exerciseSvcBootstrap_Bootstrap()
                    .commands
                    .asScala
                    .toSeq,
                  commandId = CNLedgerConnection
                    .CommandId("com.daml.network.svc.executeSvcBootstrap", Seq()),
                  deduplicationOffset = offset,
                  domainId = domainId,
                )
          }
        }
        case QueryResult(_, Some(svcRules)) =>
          coinRules match {
            case Some(coinRules) => {
              if (svcRules.payload.members.keySet.contains(svParty.toProtoPrimitive)) {
                logger.info(
                  "CoinRules and SvcRules already exist and founding party is an SVC member; doing nothing." +
                    show"\nCoinRules: $coinRules\nSvcRules: $svcRules"
                )
                Future.successful(())
              } else {
                sys.error(
                  "CoinRules and SvcRules already exist but party tasked with founding the SVC isn't member." +
                    "Is more than one SV app configured to `found-collective`?" +
                    show"\nCoinRules: $coinRules\nSvcRules: $svcRules"
                )
              }
            }
            case None =>
              sys.error(
                "An SvcRules contract was found but no CoinRules contract exists. " +
                  show"This should never happen.\nSvcRules: $svcRules"
              )
          }
      }
      _ <- createUpgradedCoinRulesIfEnabled()
    } yield ()
  }

  private def createUpgradedCoinRulesIfEnabled(): Future[Unit] =
    if (config.enableCoinRulesUpgrade)
      createUpgradedCoinRules()
    else
      Future.unit

  private def createUpgradedCoinRules(): Future[Unit] = {
    for {
      _ <- svcStore.lookupCoinRulesV1TestWithOffset().flatMap {
        case QueryResult(offset, None) =>
          svcStoreWithIngestion.connection.submitWithResult(
            actAs = Seq(svcParty),
            readAs = Seq.empty,
            update = new ccV1Test.coin.CoinRulesV1Test(
              svcParty.toProtoPrimitive,
              defaultCoinConfigSchedule(
                foundingConfig.initialTickDuration,
                foundingConfig.initialMaxNumInputs,
                domainId,
              ),
              defaultEnabledChoices,
              config.isDevNet,
              false,
            ).create(),
            commandId = CNLedgerConnection.CommandId(
              "com.daml.network.svc.initiateCoinRulesUpgrade",
              Seq(svcParty),
            ),
            deduplicationConfig = DedupOffset(offset),
            domainId = domainId,
          )
        case QueryResult(_, Some(_)) =>
          logger.info("Upgraded CoinRules (V1Test) contract already exists")
          Future.successful(())
      }
    } yield logger.debug("Created an upgraded CoinRules (V1Test) contract")
  }

  private def waiveSvcRights(
  ): Future[Unit] = {
    val connection = svcStoreWithIngestion.connection
    for {
      _ <- connection.grantUserRights(config.ledgerApiUser, Seq.empty, Seq(svcParty))
      _ <- connection.revokeUserRights(config.ledgerApiUser, Seq(svcParty), Seq.empty)
    } yield ()
  }
}
