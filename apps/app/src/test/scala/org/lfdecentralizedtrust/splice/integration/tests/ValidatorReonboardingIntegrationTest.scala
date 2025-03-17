package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.config.ConfigTransforms.bumpUrl
import org.lfdecentralizedtrust.splice.config.{
  AuthTokenSourceConfig,
  NetworkAppClientConfig,
  ParticipantBootstrapDumpConfig,
  ParticipantClientConfig,
}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.scan.config.ScanAppClientConfig
import org.lfdecentralizedtrust.splice.util.{ProcessTestUtil, StandaloneCanton, WalletTestUtil}
import org.lfdecentralizedtrust.splice.validator.config.{
  MigrateValidatorPartyConfig,
  ValidatorCantonIdentifierConfig,
}
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.{DbConfig, FullClientConfig}
import com.digitalasset.canton.config.RequireTypes.Port
import com.digitalasset.canton.data.CantonTimestamp
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
      "participant_alice_validator",
      "participant_alice_validator_reonboard_new",
      "splice_apps_reonboard",
    ) ++ super.usesDbs

  // Runs against a temporary Canton instance.
  override lazy val resetRequiredTopologyState = false

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(scaled(Span(1, Minute)))

  val dumpPath = Files.createTempFile("participant-dump", ".json")

  val aliceValidatorLocalRestartName = "aliceValidatorLocalRestart"

  private def aliceValidatorLocalRestart(implicit env: SpliceTestConsoleEnvironment) = v(
    aliceValidatorLocalRestartName
  )

  private def aliceValidatorLocalWalletClient(implicit env: SpliceTestConsoleEnvironment) =
    wc("aliceValidatorWalletLocal")

  private def aliceLocalWalletClient(implicit env: SpliceTestConsoleEnvironment) =
    wc("aliceWalletLocal")

  private def charlieLocalWalletClient(implicit env: SpliceTestConsoleEnvironment) =
    wc("charlieWalletLocal")

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .withPreSetup(_ => ())
      .withAllocatedUsers(extraIgnoredValidatorPrefixes = Seq("aliceValidator"))
      .addConfigTransforms((_, config) => {
        val defaultAliceValidatorConfig =
          config.validatorApps(InstanceName.tryCreate("aliceValidator"))
        val aliceValidatorConfig =
          defaultAliceValidatorConfig
            .copy(
              // We deliberately set the user to the participant name
              // to produce a collision between the participant admin party
              // and our validator operator party to check
              // that we revoke the domain trust cert.
              ledgerApiUser = "alice-validatorLocalForValidatorReonboardingIT-1",
              validatorPartyHint = Some("alice-validatorLocalForValidatorReonboardingIT-1"),
              cantonIdentifierConfig = Some(
                ValidatorCantonIdentifierConfig(
                  participant = "alice-validatorLocalForValidatorReonboardingIT-1"
                )
              ),
              participantClient = ParticipantClientConfig(
                FullClientConfig(port = Port.tryCreate(27502)),
                defaultAliceValidatorConfig.participantClient.ledgerApi.copy(
                  clientConfig =
                    defaultAliceValidatorConfig.participantClient.ledgerApi.clientConfig.copy(
                      port = Port.tryCreate(27501)
                    ),
                  // The nodes run without ledger API auth to simplify the test setup.
                  authConfig = AuthTokenSourceConfig.None(),
                ),
              ),
            )
        val aliceValidatorConfigNewBase = aliceValidatorConfig
          .copy(
            adminApi =
              aliceValidatorConfig.adminApi.copy(internalPort = Some(Port.tryCreate(27603))),
            storage = aliceValidatorConfig.storage match {
              case c: DbConfig.Postgres =>
                c.copy(
                  config = c.config
                    .withValue(
                      "properties.databaseName",
                      ConfigValueFactory.fromAnyRef("splice_apps_reonboard"),
                    )
                )
              case _ => throw new IllegalArgumentException("Only Postgres is supported")
            },
            cantonIdentifierConfig = Some(
              ValidatorCantonIdentifierConfig(
                participant = "aliceValidatorLocalNewForValidatorReonboardingIT"
              )
            ),
          )
        config.copy(
          validatorApps = config.validatorApps +
            (InstanceName.tryCreate("aliceValidator") -> aliceValidatorConfig) +
            (InstanceName.tryCreate("aliceValidatorLocal") -> {
              aliceValidatorConfigNewBase
                .copy(
                  participantBootstrappingDump = Some(
                    ParticipantBootstrapDumpConfig
                      .File(
                        dumpPath,
                        newParticipantIdentifier =
                          Some("aliceValidatorLocalNewForValidatorReonboardingIT"),
                      )
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
            }) +
            (InstanceName.tryCreate(aliceValidatorLocalRestartName) -> aliceValidatorConfigNewBase),
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
      })
      .withTrafficTopupsDisabled
      .withManualStart

  "re-onboard validator" in { implicit env =>
    aliceValidatorBackend.config.ledgerApiUser shouldBe "alice-validatorLocalForValidatorReonboardingIT-1"
    initDsoWithSv1Only()
    // We need a standalone instance so we can revoke the domain trust certificate
    // without breaking the long-running nodes.
    val (dump, aliceValidatorWalletParty, aliceParty, charlieParty, lockedAmount) = withCanton(
      Seq(
        testResourcesPath / "standalone-participant-extra.conf",
        // lockAmulets does a direct ledger API submission and our usual magic for getting admin tokens
        // in tests for Canton does not work for standalone instances.
        testResourcesPath / "standalone-participant-extra-no-auth.conf",
      ),
      Seq.empty,
      "alice-participant",
      "EXTRA_PARTICIPANT_ADMIN_USER" -> aliceValidatorLocalBackend.config.ledgerApiUser,
      "EXTRA_PARTICIPANT_DB" -> s"participant_alice_validator",
    ) {
      aliceValidatorBackend.startSync()
      val aliceValidatorWalletParty =
        PartyId.tryFromProtoPrimitive(aliceValidatorWalletClient.userStatus().party)
      val aliceParticipantId = aliceValidatorBackend.participantClient.id
      // check that we have a collision between paritcipant admin party
      // and validator operator party.
      aliceValidatorWalletParty.uid shouldBe aliceParticipantId.uid
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
      (dump, aliceValidatorWalletParty, aliceParty, charlieParty, lockedAmount)
    }
    withCanton(
      Seq(
        testResourcesPath / "standalone-participant-extra.conf",
        testResourcesPath / "standalone-participant-extra-no-auth.conf",
      ),
      Seq(),
      "alice-reonboard-participant",
      "EXTRA_PARTICIPANT_ADMIN_USER" -> aliceValidatorLocalBackend.config.ledgerApiUser,
      "EXTRA_PARTICIPANT_DB" -> s"participant_alice_validator_reonboard_new",
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
          aliceValidatorLocalBackend.participantClient.id.uid.identifier.unwrap shouldBe "aliceValidatorLocalNewForValidatorReonboardingIT"

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

      clue("Restart validator without migration config") {
        aliceValidatorLocalBackend.stop()
        aliceValidatorLocalRestart.startSync()
      }
    }
  }
}
