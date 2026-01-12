package org.lfdecentralizedtrust.splice.integration.tests

import cats.data.NonEmptyList
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.sequencing.GrpcSequencerConnection
import com.digitalasset.canton.SynchronizerAlias
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.console.{ParticipantClientReference, SvAppBackendReference}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.util.{SvTestUtil, WalletTestUtil}

import scala.jdk.OptionConverters.*
import scala.concurrent.{ExecutionContext, Future}
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority.Low
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.decentralizedsynchronizer.{
  SequencerConfig,
  SynchronizerNodeConfig,
}

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import org.apache.pekko.http.scaladsl.model.Uri

class ValidatorSequencerConnectionIntegrationTest
    extends IntegrationTest
    with SvTestUtil
    with WalletTestUtil {

  private val globalSyncAlias = SynchronizerAlias.tryCreate("global")
  implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllValidatorConfigs {
          case (name, c) if name == "aliceValidator" =>
            c.copy(
              domains = c.domains.copy(
                global = c.domains.global.copy(
                  sequencerNames = Some(NonEmptyList.of(getSvName(1), getSvName(2), getSvName(3))),
                  threshold = Some(2),
                )
              ),
              automation =
                c.automation.copy(pollingInterval = NonNegativeFiniteDuration.ofSeconds(1)),
            )
          case (_, c) => c
        }(config)
      )
      .withManualStart

  "validator with 'sequencerNames' set in config connects to specified sequencers and tracks URL changes of sequencers" in {
    implicit env =>
      startAllSync(
        sv1Backend,
        sv1ScanBackend,
        sv2Backend,
        sv2ScanBackend,
        sv3Backend,
        sv3ScanBackend,
        sv4Backend,
        sv4ScanBackend,
      )

      aliceValidatorBackend.startSync()

      val sv1Url = getPublicSequencerUrl(sv1Backend)
      val sv2InitialUrl = getPublicSequencerUrl(sv2Backend)
      val sv3Url = getPublicSequencerUrl(sv3Backend)
      val initialExpectedUrls = Set(sv1Url, sv2InitialUrl, sv3Url)

      withClue("Validator should connect to the filtered list of sequencers from the config") {
        eventually(60.seconds, 1.second) {
          val connectedUrls = getSequencerPublicUrls(
            aliceValidatorBackend.participantClientWithAdminToken,
            globalSyncAlias,
          )
          connectedUrls shouldBe initialExpectedUrls

          val currentThreshold = getSequencerTrustThreshold(
            aliceValidatorBackend.participantClientWithAdminToken,
            globalSyncAlias,
          )
          currentThreshold shouldBe 2
        }
      }
      val newSv2Address = "localhost:19108"
      val newSv2Url = "http://" + newSv2Address

      setSequencerUrl(sv2Backend, newSv2Url).futureValue

      val updatedExpectedUrls = Set(sv1Url, newSv2Address)

      withClue("Validator should eventually see the updated sequencer URL from the ledger") {
        eventually(60.seconds, 1.second) {
          val connectedUrls = getSequencerPublicUrls(
            aliceValidatorBackend.participantClientWithAdminToken,
            globalSyncAlias,
          )
          connectedUrls shouldBe updatedExpectedUrls
        }
      }

      withClue("Alice's validator should remain functional after the URL change") {
        eventuallySucceeds() {
          aliceValidatorBackend.onboardUser(aliceWalletClient.config.ledgerApiUser)
        }
      }

  }

  private def getPublicSequencerUrl(sv: SvAppBackendReference): String = {
    val fullUrl = sv.config.localSynchronizerNode.value.sequencer.externalPublicApiUrl
    Uri(fullUrl).authority.toString()
  }

  private def getSequencerTrustThreshold(
      participantConnection: ParticipantClientReference,
      synchronizerAlias: SynchronizerAlias,
  ): Int = {
    participantConnection.synchronizers
      .config(synchronizerAlias)
      .value
      .sequencerConnections
      .sequencerTrustThreshold
      .unwrap
  }

  private def getSequencerPublicUrls(
      participantConnection: ParticipantClientReference,
      synchronizerAlias: SynchronizerAlias,
  ): Set[String] = {
    val sequencerConnections = participantConnection.synchronizers
      .config(synchronizerAlias)
      .value
      .sequencerConnections

    sequencerConnections.connections.forgetNE.collect {
      case GrpcSequencerConnection(endpoints, _, _, _, _) => endpoints.head1.toString
    }.toSet
  }

  private def setSequencerUrl(
      svBackend: SvAppBackendReference,
      newUrl: String,
  )(implicit
      ec: ExecutionContext
  ): Future[Unit] = {

    val appState = svBackend.appState

    val dsoStore = appState.dsoStore
    val svStore = appState.svStore
    val svParty = svStore.key.svParty
    val dsoParty = svStore.key.dsoParty
    val svAutomation = appState.svAutomation
    val connection = svAutomation.connection(Low)
    val participantAdmin = appState.participantAdminConnection
    val synchronizerAlias = svBackend.config.domains.global.alias

    for {
      synchronizerId <- participantAdmin.getSynchronizerId(synchronizerAlias)
      rulesAndState <- dsoStore.getDsoRulesWithSvNodeState(svParty)
      nodeState = rulesAndState.svNodeState.payload

      synchronizerNodeConfig = nodeState.state.synchronizerNodes.asScala
        .get(synchronizerId.toProtoPrimitive)
        .getOrElse(
          sys.error(s"No config found for synchronizer $synchronizerId")
        )

      existingSequencerConfig = synchronizerNodeConfig.sequencer.toScala
        .getOrElse(
          sys.error(s"No sequencer config found for synchronizer $synchronizerId")
        )

      updatedSequencerConfig = new SequencerConfig(
        existingSequencerConfig.migrationId,
        existingSequencerConfig.sequencerId,
        newUrl,
        existingSequencerConfig.availableAfter,
      )

      newNodeConfig = new SynchronizerNodeConfig(
        synchronizerNodeConfig.cometBft,
        Some(updatedSequencerConfig).toJava,
        synchronizerNodeConfig.mediator.toScala.toJava,
        synchronizerNodeConfig.scan.toScala.toJava,
        synchronizerNodeConfig.legacySequencerConfig.toScala.toJava,
      )

      cmd = rulesAndState.dsoRules.exercise(
        _.exerciseDsoRules_SetSynchronizerNodeConfig(
          svParty.toProtoPrimitive,
          synchronizerId.toProtoPrimitive,
          newNodeConfig,
          rulesAndState.svNodeState.contractId,
        )
      )
      _ <- connection
        .submit(Seq(svParty), Seq(dsoParty), cmd)
        .noDedup
        .yieldResult()
    } yield ()
  }
}
