package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.RequireTypes.Port
import com.digitalasset.canton.config.{DbConfig, FullClientConfig}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.topology.{ForceFlag, ParticipantId, PartyId}
import com.typesafe.config.ConfigValueFactory
import org.apache.pekko.http.scaladsl.model.Uri
import org.lfdecentralizedtrust.splice.config.*
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.bumpUrl
import org.lfdecentralizedtrust.splice.environment.RetryFor
import org.lfdecentralizedtrust.splice.identities.NodeIdentitiesDump
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
import org.lfdecentralizedtrust.splice.validator.migration.ParticipantPartyMigrator
import org.lfdecentralizedtrust.splice.validator.store.ValidatorConfigProvider
import org.scalatest.time.{Minute, Span}
import org.slf4j.event.Level

import java.nio.file.{Files, Path, Paths}

trait ValidatorReonboardingIntegrationTestBase
    extends IntegrationTest
    with ProcessTestUtil
    with StandaloneCanton
    with ExternallySignedPartyTestUtil
    with WalletTestUtil {

  override def dbsSuffix = "validator_reonboard"

  // override to avoid conflicts between the tests that implement this
  def oldParticipantDb = "participant_alice_validator"
  def newParticipantDb = "participant_alice_validator_reonboard_new"
  def appsDb = "splice_apps_reonboard"

  override def usesDbs =
    Seq(
      oldParticipantDb,
      newParticipantDb,
      appsDb,
    ) ++ super.usesDbs

  // Runs against a temporary Canton instance.
  override lazy val resetRequiredTopologyState = false

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(scaled(Span(1, Minute)))

  val dumpPath = Files.createTempFile("participant-dump", ".json")

  val aliceValidatorPartyHint = "alice-validatorLocalForValidatorReonboardingIT-1"
  // We deliberately set the user to the participant name
  // to produce a collision between the participant admin party
  // and our validator operator party to check
  // that we revoke the domain trust cert.
  val aliceValidatorParticipantNameHint = aliceValidatorPartyHint

  def partiesToMigrate(config: SpliceConfig): Option[Seq[String]] = None
  val participantBootstrappingDump: Option[ParticipantBootstrapDumpConfig] = None

  val aliceValidatorLocalRestartName = "aliceValidatorLocalRestart"

  def aliceValidatorLocalRestart(implicit env: SpliceTestConsoleEnvironment) = v(
    aliceValidatorLocalRestartName
  )

  def aliceValidatorLocalWalletClient(implicit env: SpliceTestConsoleEnvironment) =
    wc("aliceValidatorWalletLocal")

  def aliceLocalWalletClient(implicit env: SpliceTestConsoleEnvironment) =
    wc("aliceWalletLocal")

  def charlieLocalWalletClient(implicit env: SpliceTestConsoleEnvironment) =
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
              ledgerApiUser = aliceValidatorPartyHint,
              validatorPartyHint = Some(aliceValidatorPartyHint),
              cantonIdentifierConfig = Some(
                ValidatorCantonIdentifierConfig(
                  participant = aliceValidatorParticipantNameHint
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
              participantBootstrappingDump = participantBootstrappingDump,
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
                      ConfigValueFactory.fromAnyRef(appsDb),
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
                      ),
                      partiesToMigrate(config),
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

  def assertMapping(
      partyId: PartyId,
      participantId: ParticipantId,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ): Unit = assertMapping(
    partyId,
    Seq(participantId),
  )

  def assertMapping(
      partyId: PartyId,
      participants: Seq[ParticipantId],
  )(implicit
      env: SpliceTestConsoleEnvironment
  ): Unit = {
    val mapping = sv1Backend.appState.participantAdminConnection
      .getPartyToParticipant(
        decentralizedSynchronizerId,
        partyId,
      )
      .futureValue
      .mapping
    mapping.participants.map(_.participantId) should contain theSameElementsAs participants
  }
}

class ValidatorReonboardingIntegrationTest extends ValidatorReonboardingIntegrationTestBase {

  "re-onboard validator" in { implicit env =>
    aliceValidatorBackend.config.ledgerApiUser shouldBe aliceValidatorPartyHint
    initDsoWithSv1Only()
    // We need a standalone instance so we can revoke the domain trust certificate
    // without breaking the long-running nodes.
    val (dump, aliceValidatorWalletParty, aliceParty, charlieParty, daveExtParty, lockedAmount) =
      withCanton(
        Seq(
          testResourcesPath / "standalone-participant-extra.conf",
          // lockAmulets does a direct ledger API submission and our usual magic for getting admin tokens
          // in tests for Canton does not work for standalone instances.
          testResourcesPath / "standalone-participant-extra-no-auth.conf",
        ),
        Seq.empty,
        "alice-participant",
        "EXTRA_PARTICIPANT_ADMIN_USER" -> aliceValidatorLocalBackend.config.ledgerApiUser,
        "EXTRA_PARTICIPANT_DB" -> oldParticipantDb,
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

        val daveExtParty = onboardExternalParty(
          aliceValidatorBackend,
          Some("daveExternal"),
        ).party
        assertMapping(
          daveExtParty,
          aliceValidatorBackend.participantClient.id,
        )

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
        (dump, aliceValidatorWalletParty, aliceParty, charlieParty, daveExtParty, lockedAmount)
      }
    better.files
      .File(dumpPath)
      .overwrite(
        dump.toJson.noSpaces
      )
    withCanton(
      Seq(
        testResourcesPath / "standalone-participant-extra.conf",
        testResourcesPath / "standalone-participant-extra-no-auth.conf",
      ),
      Seq(),
      "alice-reonboard-participant",
      "EXTRA_PARTICIPANT_ADMIN_USER" -> aliceValidatorLocalBackend.config.ledgerApiUser,
      "EXTRA_PARTICIPANT_DB" -> newParticipantDb,
    ) {
      loggerFactory.assertLogsSeq(
        SuppressionRule.forLogger[ParticipantPartyMigrator] || SuppressionRule
          .forLogger[ValidatorConfigProvider] || SuppressionRule
          .LevelAndAbove(Level.WARN)
      )(
        {
          aliceValidatorLocalBackend.startSync()
        },
        entries => {
          // we expect it to refuse to migrate the external party
          forExactly(1, entries) {
            _.warningMessage should include(
              "not be able to migrate due to an unsupported namespace"
            )
          }
          forExactly(1, entries) {
            _.message should include(
              "Storing all the hosted parties (4) in the database to recover in case of failures"
            )
          }
          forExactly(1, entries) {
            _.message should include(
              "Clearing parties that were migrated"
            )
          }
        },
      )

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
        clue(s"check mapping and amulet balance of $partyId") {
          assertMapping(
            partyId,
            aliceValidatorLocalBackend.participantClient.id,
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

      clue(s"external party ${daveExtParty} is not hosted anywhere anymore") {
        assertMapping(
          daveExtParty,
          Seq.empty,
        )
      }
    }
  }
}

class ValidatorReonboardingWithPartiesToMigrateIntegrationTest
    extends ValidatorReonboardingIntegrationTestBase {

  // avoid db collisions; ptm := partiesToMigrate
  override def dbsSuffix = "validator_reonboard_ptm"
  override def oldParticipantDb = "participant_alice_validator_ptm"
  override def newParticipantDb = "participant_alice_validator_reonboard_new_ptm"
  override def appsDb = "splice_apps_reonboard_ptm"

  // For this test we want to test that we won't try to revoke the domain trust certificate
  // (which will fail unless we migrate all parties) despite migrating the operator party.
  // This can only work if there is no collision between the participant admin party and
  // the validator operator party.
  override val aliceValidatorParticipantNameHint = s"$aliceValidatorPartyHint-participant";

  // we bootstrap from dump so we can predict the party IDs
  val testDumpDir: Path = Paths.get("apps/app/src/test/resources/dumps")
  // we use a dedicated dump for this test to avoid conflicts with other tests
  val oldParticipantDumpFile = testDumpDir.resolve("alice-plaintext-id-identity-dump-2.json")

  override val participantBootstrappingDump: Option[ParticipantBootstrapDumpConfig] = Some(
    ParticipantBootstrapDumpConfig.File(
      oldParticipantDumpFile,
      Some(aliceValidatorParticipantNameHint),
    )
  )

  override def partiesToMigrate(config: SpliceConfig): Option[Seq[String]] = {
    val aliceParticipantId = NodeIdentitiesDump
      .fromJsonFile(oldParticipantDumpFile, ParticipantId.tryFromProtoPrimitive)
      .value
      .id
    val aliceNamespaceSuffix = aliceParticipantId.uid.namespace.toProtoPrimitive

    val alicePartyHint = config
      .walletAppClients(InstanceName.tryCreate("aliceWallet"))
      .ledgerApiUser
      .replace("_", "__")

    Some(
      Seq(aliceValidatorPartyHint, alicePartyHint).map(hint => s"$hint::${aliceNamespaceSuffix}")
    )
  }

  "re-onboard validator with partiesToMigrate" in { implicit env =>
    aliceValidatorBackend.config.ledgerApiUser shouldBe aliceValidatorPartyHint
    initDsoWithSv1Only()
    // We need a standalone instance to avoid breaking the long-running nodes.
    val (dump, aliceValidatorWalletParty, aliceParty, charlieParty) = withCanton(
      Seq(
        testResourcesPath / "standalone-participant-extra.conf",
        testResourcesPath / "standalone-participant-extra-no-auth.conf",
      ),
      Seq.empty,
      "alice-reonboard-from-key-database",
      "EXTRA_PARTICIPANT_ADMIN_USER" -> aliceValidatorLocalBackend.config.ledgerApiUser,
      "EXTRA_PARTICIPANT_DB" -> oldParticipantDb,
    ) {
      aliceValidatorBackend.startSync()
      val aliceValidatorWalletParty =
        PartyId.tryFromProtoPrimitive(aliceValidatorWalletClient.userStatus().party)
      val aliceParticipantId = aliceValidatorBackend.participantClient.id
      // check that we have no collision between participant admin party
      // and validator operator party.
      aliceValidatorWalletParty.uid shouldNot be(aliceParticipantId.uid)
      aliceValidatorWalletClient.tap(100)

      val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      aliceWalletClient.tap(100)
      val charlieParty = onboardWalletUser(charlieWalletClient, aliceValidatorBackend)
      charlieWalletClient.tap(100)

      val dump = aliceValidatorBackend.dumpParticipantIdentities()

      loggerFactory.suppressWarningsAndErrors {
        // Tests a worst-case corner case that happened in the wild
        actAndCheck(
          s"Remove the topology mapping for ${aliceValidatorWalletParty} and stop the validator", {
            aliceValidatorBackend.appState.participantAdminConnection
              .ensurePartyToParticipantRemoved(
                RetryFor.WaitingOnInitDependency,
                decentralizedSynchronizerId,
                aliceValidatorWalletParty,
                aliceParticipantId,
                ForceFlag.DisablePartyWithActiveContracts,
              )
              .futureValue
            aliceValidatorBackend.stop()
          },
        )(
          s"$aliceValidatorWalletParty is not hosted anywhere anymore",
          _ => {
            sv1Backend.appState.participantAdminConnection
              .listPartyToParticipantFromAllStores(
                filterParty = aliceValidatorWalletParty.filterString
              )
              .futureValue shouldBe empty
          },
        )
      }
      (dump, aliceValidatorWalletParty, aliceParty, charlieParty)
    }
    better.files
      .File(dumpPath)
      .overwrite(
        dump.toJson.noSpaces
      )
    clue("the party migration config is as expected") {
      // we want to migrate both alice parties, but not charlie
      aliceValidatorLocalBackend.config.migrateValidatorParty.value.partiesToMigrate.value shouldBe Seq(
        aliceValidatorWalletParty.toProtoPrimitive,
        aliceParty.toProtoPrimitive,
      )
    }
    withCanton(
      Seq(
        testResourcesPath / "standalone-participant-extra.conf",
        testResourcesPath / "standalone-participant-extra-no-auth.conf",
      ),
      Seq(),
      "alice-reonboard-participant",
      "EXTRA_PARTICIPANT_ADMIN_USER" -> aliceValidatorLocalBackend.config.ledgerApiUser,
      "EXTRA_PARTICIPANT_DB" -> newParticipantDb,
    ) {
      aliceValidatorLocalBackend.startSync()

      clue("onboard migrated user on the new validator") {
        onboardWalletUser(aliceLocalWalletClient, aliceValidatorLocalBackend) shouldBe aliceParty
      }

      Seq(
        aliceValidatorWalletParty -> aliceValidatorLocalWalletClient,
        aliceParty -> aliceLocalWalletClient,
      ).foreach { case (partyId, walletAppClient) =>
        clue(s"check mapping and amulet balance of $partyId") {
          assertMapping(
            partyId,
            aliceValidatorLocalBackend.participantClient.id,
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
        }
      }
      clue(s"party $charlieParty is still hosted on the old participant") {
        assertMapping(
          charlieParty,
          aliceValidatorBackend.participantClient.id,
        )
      }
    }
  }
}
