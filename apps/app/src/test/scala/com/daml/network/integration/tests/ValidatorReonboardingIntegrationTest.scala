package com.daml.network.integration.tests

import com.daml.network.config.CNNodeConfigTransforms.bumpUrl
import com.daml.network.config.{
  CNDbConfig,
  CNParticipantClientConfig,
  NetworkAppClientConfig,
  ParticipantBootstrapDumpConfig,
}
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.scan.config.ScanAppClientConfig
import com.daml.network.util.{ProcessTestUtil, StandaloneCanton, WalletTestUtil}
import com.daml.network.validator.config.MigrateValidatorPartyConfig
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.ClientConfig
import com.digitalasset.canton.config.RequireTypes.Port
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.topology.{ParticipantId, PartyId}
import com.typesafe.config.ConfigValueFactory
import org.apache.pekko.http.scaladsl.model.Uri
import org.scalatest.time.{Minute, Span}

import java.nio.file.Files

class ValidatorReonboardingIntegrationTest
    extends CNNodeIntegrationTest
    with ProcessTestUtil
    with StandaloneCanton
    with WalletTestUtil {

  override def dbsSuffix = "validator_reonboard"
  override def usesDbs =
    Seq(
      "participant_alice_validator_reonboard_new",
      "cn_apps_reonboard",
    ) ++ super.usesDbs

  // Runs against a temporary Canton instance.
  override lazy val resetRequiredTopologyState = false

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(scaled(Span(1, Minute)))

  val dumpPath = Files.createTempFile("participant-dump", ".json")

  private def aliceValidatorLocalWalletClient(implicit env: CNNodeTestConsoleEnvironment) =
    wc("aliceValidatorWalletLocal")

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .addConfigTransforms((_, config) =>
        config.copy(
          validatorApps = config.validatorApps +
            (InstanceName.tryCreate("aliceValidatorLocal") -> {
              val aliceValidatorConfig =
                config.validatorApps(InstanceName.tryCreate("aliceValidator"))
              aliceValidatorConfig
                .copy(
                  adminApi =
                    aliceValidatorConfig.adminApi.copy(internalPort = Some(Port.tryCreate(27603))),
                  participantClient = CNParticipantClientConfig(
                    ClientConfig(port = Port.tryCreate(27502)),
                    aliceValidatorConfig.participantClient.ledgerApi.copy(
                      clientConfig =
                        aliceValidatorConfig.participantClient.ledgerApi.clientConfig.copy(
                          port = Port.tryCreate(27501)
                        )
                    ),
                  ),
                  storage = aliceValidatorConfig.storage match {
                    case c: CNDbConfig.Postgres =>
                      c.copy(
                        config = c.config
                          .withValue(
                            "properties.databaseName",
                            ConfigValueFactory.fromAnyRef("cn_apps_reonboard"),
                          )
                      )
                    case _ => throw new IllegalArgumentException("Only Postgres is supported")
                  },
                  participantBootstrappingDump = Some(
                    ParticipantBootstrapDumpConfig
                      .File(dumpPath, Some("aliceValidatorLocalNew"))
                  ),
                  migrateValidatorParty = Some(
                    MigrateValidatorPartyConfig(
                      ScanAppClientConfig(
                        adminApi = NetworkAppClientConfig(
                          Uri(s"http://localhost:5012")
                        )
                      )
                    )
                  ),
                )
            }),
          walletAppClients = config.walletAppClients + (
            InstanceName.tryCreate("aliceValidatorWalletLocal") -> {
              val aliceValidatorWalletConfig =
                config.walletAppClients(InstanceName.tryCreate("aliceValidatorWallet"))

              aliceValidatorWalletConfig
                .copy(
                  adminApi = aliceValidatorWalletConfig.adminApi
                    .copy(url = bumpUrl(22_100, aliceValidatorWalletConfig.adminApi.url.toString()))
                )
            }
          ),
        )
      )
      .withTrafficTopupsDisabled
      .withManualStart

  "re-onboard validator" in { implicit env =>
    initDsoWithSv1Only()
    aliceValidatorBackend.startSync()
    val aliceValidatorWalletParty =
      PartyId.tryFromProtoPrimitive(aliceValidatorWalletClient.userStatus().party)
    aliceValidatorWalletClient.tap(100)

    val dump = aliceValidatorBackend.dumpParticipantIdentities()
    clue("Stop aliceValidator") {
      aliceValidatorBackend.stop()
    }
    withCanton(
      Seq(
        testResourcesPath / "standalone-participant-extra.conf"
      ),
      Seq(),
      "alice-reonboard-participant",
      "EXTRA_PARTICIPANT_ADMIN_USER" -> aliceValidatorLocalBackend.config.ledgerApiUser,
      "EXTRA_PARTICIPANT_DB" -> s"participant_alice_validator_reonboard_new",
      "AUTO_INIT_ALL" -> "false",
    ) {
      better.files
        .File(dumpPath)
        .overwrite(
          dump.toJson.noSpaces
        )
      aliceValidatorLocalBackend.startSync()
      val mapping = aliceValidatorLocalBackend.appState.participantAdminConnection
        .getPartyToParticipant(
          decentralizedSynchronizerId,
          aliceValidatorWalletParty,
        )
        .futureValue
        .mapping
      mapping.participants.map(_.participantId) should contain theSameElementsAs Seq(
        aliceValidatorLocalBackend.participantClient.id
      )

      aliceValidatorLocalBackend.participantClient.id.code shouldBe ParticipantId.Code
      aliceValidatorLocalBackend.participantClient.id.uid.id.unwrap shouldBe "aliceValidatorLocalNew"

      clue("alice amulet balance is preserved") {
        val expectedAmulets: Range = 99 to 100
        checkWallet(
          aliceValidatorWalletParty,
          aliceValidatorLocalWalletClient,
          Seq((walletUsdToAmulet(expectedAmulets.start), walletUsdToAmulet(expectedAmulets.end))),
        )
      }

      actAndCheck(
        "Alice validator wallet user tap again on re-onboarded validator",
        aliceValidatorLocalWalletClient.tap(50.0),
      )(
        "balance updated",
        _ => {
          val expectedAmulets: Range = 149 to 150
          checkWallet(
            aliceValidatorWalletParty,
            aliceValidatorLocalWalletClient,
            Seq((walletUsdToAmulet(expectedAmulets.start), walletUsdToAmulet(expectedAmulets.end))),
          )
        },
      )
    }
  }
}
