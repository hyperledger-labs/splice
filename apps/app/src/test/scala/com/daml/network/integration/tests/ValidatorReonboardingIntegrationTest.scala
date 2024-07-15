package com.daml.network.integration.tests

import com.daml.network.config.ConfigTransforms.bumpUrl
import com.daml.network.config.{
  SpliceDbConfig,
  ParticipantClientConfig,
  NetworkAppClientConfig,
  ParticipantBootstrapDumpConfig,
}
import com.daml.network.environment.EnvironmentImpl
import com.daml.network.integration.EnvironmentDefinition
import com.daml.network.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import com.daml.network.scan.config.ScanAppClientConfig
import com.daml.network.util.{ProcessTestUtil, StandaloneCanton, WalletTestUtil}
import com.daml.network.validator.config.MigrateValidatorPartyConfig
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.ClientConfig
import com.digitalasset.canton.config.RequireTypes.Port
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.topology.{ParticipantId, PartyId}
import com.typesafe.config.ConfigValueFactory
import org.apache.pekko.http.scaladsl.model.Uri
import org.scalatest.time.{Minute, Span}

import java.nio.file.Files

class ValidatorReonboardingIntegrationTest
    extends IntegrationTest
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

  private def aliceValidatorLocalWalletClient(implicit env: SpliceTestConsoleEnvironment) =
    wc("aliceValidatorWalletLocal")

  private def aliceLocalWalletClient(implicit env: SpliceTestConsoleEnvironment) =
    wc("aliceWalletLocal")

  private def charlieLocalWalletClient(implicit env: SpliceTestConsoleEnvironment) =
    wc("charlieWalletLocal")

  override def environmentDefinition
      : BaseEnvironmentDefinition[EnvironmentImpl, SpliceTestConsoleEnvironment] =
    EnvironmentDefinition
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
                  participantClient = ParticipantClientConfig(
                    ClientConfig(port = Port.tryCreate(27502)),
                    aliceValidatorConfig.participantClient.ledgerApi.copy(
                      clientConfig =
                        aliceValidatorConfig.participantClient.ledgerApi.clientConfig.copy(
                          port = Port.tryCreate(27501)
                        )
                    ),
                  ),
                  storage = aliceValidatorConfig.storage match {
                    case c: SpliceDbConfig.Postgres =>
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
                      .File(dumpPath, Some("aliceValidatorLocalNewForValidatorReonboardingIT"))
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
          ) + (
            InstanceName.tryCreate("aliceWalletLocal") -> {
              val aliceWalletConfig =
                config.walletAppClients(InstanceName.tryCreate("aliceWallet"))

              aliceWalletConfig
                .copy(
                  adminApi = aliceWalletConfig.adminApi
                    .copy(url = bumpUrl(22_100, aliceWalletConfig.adminApi.url.toString()))
                )
            }
          ) + (
            InstanceName.tryCreate("charlieWalletLocal") -> {
              val charlieWalletConfig =
                config.walletAppClients(InstanceName.tryCreate("charlieWallet"))

              charlieWalletConfig
                .copy(
                  adminApi = charlieWalletConfig.adminApi
                    .copy(url = bumpUrl(22_100, charlieWalletConfig.adminApi.url.toString()))
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

    val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
    aliceWalletClient.tap(150)
    val charlieParty = onboardWalletUser(charlieWalletClient, aliceValidatorBackend)
    charlieWalletClient.tap(100)

    val lockedAmount = walletUsdToAmulet(BigDecimal(50))
    actAndCheck(
      "alice locks a amulet that both aliceParty and aliceValidatorWalletParty are stake holders",
      lockAmulets(
        aliceValidatorBackend,
        aliceParty,
        aliceValidatorWalletParty,
        aliceWalletClient.list().amulets,
        lockedAmount,
        sv1ScanBackend,
        java.time.Duration.ofMinutes(5),
        CantonTimestamp.now(),
      ),
    )(
      "Wait for locked amulet to appear",
      _ => {
        aliceWalletClient.list().lockedAmulets.loneElement.effectiveAmount shouldBe lockedAmount
      },
    )

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

      clue("onboard users on the new validator") {
        onboardWalletUser(aliceLocalWalletClient, aliceValidatorLocalBackend) shouldBe aliceParty
        onboardWalletUser(
          charlieLocalWalletClient,
          aliceValidatorLocalBackend,
        ) shouldBe charlieParty
      }

      Seq(
        aliceValidatorWalletParty -> aliceValidatorLocalWalletClient,
        aliceParty -> aliceLocalWalletClient,
        charlieParty -> charlieLocalWalletClient,
      ).foreach { case (partyId, walletAppClient) =>
        clue(s"check amulet balance of $partyId") {
          val mapping = aliceValidatorLocalBackend.appState.participantAdminConnection
            .getPartyToParticipant(
              decentralizedSynchronizerId,
              partyId,
            )
            .futureValue
            .mapping
          mapping.participants.map(_.participantId) should contain theSameElementsAs Seq(
            aliceValidatorLocalBackend.participantClient.id
          )

          aliceValidatorLocalBackend.participantClient.id.code shouldBe ParticipantId.Code
          aliceValidatorLocalBackend.participantClient.id.uid.id.unwrap shouldBe "aliceValidatorLocalNewForValidatorReonboardingIT"

          clue(s"party $partyId amulet balance is preserved") {
            val expectedAmulets: Range = 99 to 100
            checkWallet(
              partyId,
              walletAppClient,
              Seq(
                (walletUsdToAmulet(expectedAmulets.start), walletUsdToAmulet(expectedAmulets.end))
              ),
            )
          }

          actAndCheck(
            s"party $partyId tap again on re-onboarded validator",
            walletAppClient.tap(50.0),
          )(
            "balance updated",
            _ => {
              val expectedAmulets: Range = 149 to 150
              checkWallet(
                partyId,
                walletAppClient,
                Seq(
                  (walletUsdToAmulet(expectedAmulets.start), walletUsdToAmulet(expectedAmulets.end))
                ),
              )
            },
          )
        }
      }

      clue("alice still see the locked amulet") {
        aliceLocalWalletClient
          .list()
          .lockedAmulets
          .loneElement
          .effectiveAmount shouldBe lockedAmount
      }
    }
  }
}
