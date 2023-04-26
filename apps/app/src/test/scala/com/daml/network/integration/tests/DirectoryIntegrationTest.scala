package com.daml.network.integration.tests

import com.daml.network.codegen.java.cn.directory as codegen
import com.daml.network.codegen.java.cn.wallet.subscriptions as subsCodegen
import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.console.{
  RemoteDirectoryAppReference,
  ValidatorAppBackendReference,
  WalletAppClientReference,
}
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.util.WalletTestUtil
import com.digitalasset.canton.DiscardOps
import com.digitalasset.canton.console.CommandFailure
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.topology.PartyId
import org.slf4j.event.Level

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*

class DirectoryIntegrationTest extends CNNodeIntegrationTest with WalletTestUtil {

  import DirectoryIntegrationTest.*

  private val directoryDarPath =
    "daml/directory-service/.daml/dist/directory-service-0.1.0.dar"
  private val testEntryName = "mycoolentry"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      // start only sv1 but not sv2-4
      .addConfigTransformToFront(
        CNNodeConfigTransforms.onlySv1
      )
      .withAdditionalSetup(implicit env => {
        aliceValidator.remoteParticipant.dars.upload(directoryDarPath)
        bobValidator.remoteParticipant.dars.upload(directoryDarPath)
      })

  "Directory service" should {

    "restart cleanly" in { implicit env =>
      directory.stop()
      directory.startSync()
    }

    "not throw an error on shutdown" in { implicit env =>
      import env.*

      // The user of the directory service.
      val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)
      val offsetBefore =
        directory.remoteParticipantWithAdminToken.ledger_api.transactions.end()

      // Trigger three concurrent install requests
      for (_ <- 1 to 3)
        Future {
          aliceDirectory.requestDirectoryInstall()
        }.discard

      // Wait for one transaction, so that automation likely kicks-off but shutdown initiates quickly
      // and thus results in 'handleDirectoryInstallRequest' handlers being aborted due to shutdown.
      directory.remoteParticipantWithAdminToken.ledger_api.transactions
        .flat(Set(aliceUserParty), completeAfter = 1, beginOffset = offsetBefore)
    }

    "ensure unique install despite racing install requests w/ and w/o archivals" in {
      implicit env =>
        // NOTE: this test also serves to check that the stale-contract detection logic in the OnCreateTrigger works properly
        import env.*

        // The user of the directory service.
        val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)

        def raceInstalls() = {
          val (_, installCid) = actAndCheck(
            "Trigger multiple install requests followed immediately by their archival", {
              val installAttemptsFs = Range(0, 10).map(i =>
                Future {
                  clue(s"creating and archiving request $i") {
                    val requestId = aliceDirectory.requestDirectoryInstall()
                    // Issue a concurrent archival for all except the first three requests
                    if (3 <= i) {
                      // TODO(tech-debt): get rid of this ugly archive argument once https://github.com/digital-asset/daml/issues/15540 is resolved
                      val cmd =
                        requestId.exerciseArchive(
                          new com.daml.network.codegen.java.da.internal.template.Archive()
                        )
                      try {
                        aliceValidator.remoteParticipantWithAdminToken.ledger_api_extensions.commands
                          .submitJava(
                            actAs = Seq(aliceUserParty),
                            optTimeout = None,
                            commands = cmd.commands().asScala.toSeq,
                          )
                      } catch {
                        case ex: CommandFailure =>
                          // The archival can fail if the automation is quicker in handling the request
                          logger.info("Ignoring failure of archival command", ex)
                      }
                    }
                  }
                }
              )
              clue(
                "waiting for all install requests creations and archive submissions to be completed"
              ) {
                Future.sequence(installAttemptsFs).futureValue
              }
              clue("Waiting for all install requests to be archived") {
                eventually() {
                  aliceValidator.remoteParticipant.ledger_api_extensions.acs.filterJava(
                    codegen.DirectoryInstallRequest.COMPANION
                  )(aliceUserParty) shouldBe empty
                }
              }
            },
          )(
            "there is exactly one install and no left-over request",
            _ => {
              val installs =
                aliceValidator.remoteParticipantWithAdminToken.ledger_api_extensions.acs
                  .filterJava(codegen.DirectoryInstall.COMPANION)(aliceUserParty)
              installs should have size (1)
              val requests =
                aliceValidator.remoteParticipantWithAdminToken.ledger_api_extensions.acs
                  .filterJava(codegen.DirectoryInstallRequest.COMPANION)(aliceUserParty)
              requests shouldBe Seq.empty
              // return install-cid
              installs(0).id
            },
          )
          installCid
        }

        // We need to use assertEventuallyLogsSeq. Otherwise,
        // we wait for the trigger to finish handling the install requests but the logs
        // are slightly delayed.
        // Confusingly log suppression is still active during the log assertion
        // so you can end up seeing the log being suppressed while the assertion does not see it.
        val installCid =
          loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.INFO))(
            raceInstalls(),
            lines => {
              // check that all errors are due to races on the contracts
              forAll(lines) { line =>
                if (line.level == Level.ERROR)
                  line.message should (include("ABORTED/LOCAL_VERDICT_LOCKED_CONTRACTS") or include(
                    "NOT_FOUND/LOCAL_VERDICT_INACTIVE_CONTRACTS"
                  ))
              }

              // Note the DirectoryInstallTrigger completes with log lines of the form:
              //   DirectoryInstallRequestTrigger:DirectoryIntegrationTest/config=8b549e20/directory=directory-app tid:796c4cc07dc9db97b87db1feb158efb4 - Completed processing with outcome: accepted install request.
              // Collect and check the logged outcomes from these lines
              val outcomeRegex =
                "Completed processing with outcome: (.*)".r
              val ignoredRegexes =
                Seq(
                  "(?s)Processing.*Contract.*".r,
                  "(?s)Checking whether the task is stale, as its processing failed with.*".r,
                  "(?s)The operation 'processTaskWithRetry' failed with a retryable error.*".r,
                  "(?s)The operation 'processTaskWithRetry' has failed with an exception.*".r,
                  "(?s)Rejecting duplicate install request.*".r,
                  "(?s)Now retrying operation 'processTaskWithRetry'.*".r,
                  "(?s)skipped, as the task has become stale.*".r,
                )
              val expectedOutcomes = Seq(
                "accepted install request",
                "rejected request for already existing installation",
                "skipped, as the task has become stale",
              )
              // Note that we might not get an outcome for every request, as some create events might never be seen by the trigger
              val actualOutcomes =
                lines
                  .filter(entry => entry.loggerName.contains("DirectoryInstallRequestTrigger"))
                  .flatMap(line => {
                    val outcome = outcomeRegex.findAllMatchIn(line.message).toSeq
                    if (outcome.isEmpty && !ignoredRegexes.exists(_.matches(line.message))) {
                      // Sanity check to make sure our parsing really catches anything interesting.
                      fail(s"Unexpected message: ${line.message}")
                    }
                    outcome
                  })
                  .map(_.group(1).stripSuffix("."))

              forAll(actualOutcomes) { (outcome: String) =>
                expectedOutcomes should contain(outcome)
              }
              actualOutcomes should (contain allOf)(
                "accepted install request",
                "rejected request for already existing installation",
              )

            },
          )

        actAndCheck(
          "Cancel install", {
            val cmds = installCid
              .exerciseDirectoryInstall_Cancel(aliceUserParty.toProtoPrimitive)
              .commands
              .asScala
              .toSeq
            aliceValidator.remoteParticipantWithAdminToken.ledger_api_extensions.commands
              .submitJava(
                actAs = Seq(aliceUserParty),
                commands = cmds,
                optTimeout = None, // Setting to 'None' as otherwise the tx lookup fails
              )
          },
        )(
          "There is no install contract left",
          _ =>
            aliceValidator.remoteParticipantWithAdminToken.ledger_api_extensions.acs
              .filterJava(codegen.DirectoryInstall.COMPANION)(
                aliceUserParty
              ) shouldBe empty,
        )
    }

    def requestAndPayForEntry(refs: DynamicUserRefs, entryName: String) = {
      refs.wallet.tap(5.0)

      // Request entry and get some money to pay for it
      val (_, subscriptionRequest) =
        refs.directory.requestDirectoryEntry(entryName)

      // Wait for subscription request to be ingested into store
      // and accept it.
      val initialPayment = eventually()(inside(refs.wallet.listSubscriptionRequests()) {
        case Seq(storeRequest) =>
          storeRequest.subscriptionRequest.contractId shouldBe subscriptionRequest
          refs.wallet.acceptSubscriptionRequest(storeRequest.subscriptionRequest.contractId)
      })
      // Wait for the SubscriptionInitialPayment to be archived
      eventually() {
        refs.validator.remoteParticipantWithAdminToken.ledger_api_extensions.acs
          .filterJava(subsCodegen.SubscriptionInitialPayment.COMPANION)(
            refs.userParty,
            (request: subsCodegen.SubscriptionInitialPayment.Contract) =>
              request.id == initialPayment,
          ) shouldBe empty
      }
    }

    "allocate unique directory entries, even when multiple parties race for them" in {
      implicit env =>
        import env.*

        // The provider of the directory service
        val providerParty = directory.getProviderPartyId()

        // Setup alice
        val aliceStaticRefs = StaticUserRefs(aliceValidator, aliceDirectory, aliceWallet)
        val aliceRefs = setupUser(aliceStaticRefs)

        // Setup bob
        val bobStaticRefs = StaticUserRefs(bobValidator, bobDirectory, bobWallet)
        val bobRefs = setupUser(bobStaticRefs)

        // Concurrently, request an entry as alice and bob
        loggerFactory.assertLogs(
          {
            val aliceF = Future {
              requestAndPayForEntry(aliceRefs, testEntryName)
            }
            val bobF = Future {
              requestAndPayForEntry(bobRefs, testEntryName)
            }

            // Wait for both of them
            Seq(aliceF.futureValue, bobF.futureValue)
          },
          _.warningMessage should include(
            "rejecting initial subscription payment: entry already exists and owned by"
          ),
        )

        val entry = eventuallySucceeds() {
          directory.lookupEntryByName(testEntryName)
        }

        val winnerUserParty = PartyId.tryFromProtoPrimitive(entry.payload.user)
        logger.info(s"And the winner is ... *drumroll* ... : $winnerUserParty")

        // Check content of winning entry
        val entryPayload =
          new codegen.DirectoryEntry(
            winnerUserParty.toProtoPrimitive,
            providerParty.toProtoPrimitive,
            testEntryName,
            entry.payload.expiresAt,
          )
        entry.payload shouldBe entryPayload

        // Read entries from provider
        directory.listEntries("", 25) shouldBe Seq(entry)
        directory.lookupEntryByName(testEntryName) shouldBe entry
        directory.lookupEntryByParty(winnerUserParty) shouldBe entry
        assertThrowsAndLogsCommandFailures(
          directory.lookupEntryByName("nonexistentname"),
          _.errorMessage should include("nonexistentname"),
        )
    }

    "archive expired directory entries" in { implicit env =>
      clue("Creating a directory entry that expires immediately") {
        directory.listEntries("", 25) shouldBe empty
        val dirParty = directory.getProviderPartyId()
        directory.remoteParticipantWithAdminToken.ledger_api_extensions.commands.submitJava(
          actAs = Seq(dirParty),
          commands = new codegen.DirectoryEntry(
            dirParty.toProtoPrimitive,
            dirParty.toProtoPrimitive,
            testEntryName,
            Instant.now().plus(1, ChronoUnit.SECONDS),
          ).create.commands.asScala.toSeq,
          optTimeout = None,
        )
        eventually()(
          directory.listEntries("", 25) should not be empty
        )
      }
      clue("Waiting for the backend to expire the entry...") {
        eventually()(
          directory.listEntries("", 25) shouldBe empty
        )
      }
    }

    "support prefix lookup" in { implicit env =>
      val aliceStaticRefs = StaticUserRefs(aliceValidator, aliceDirectory, aliceWallet)
      val aliceRefs = setupUser(aliceStaticRefs)
      val bobStaticRefs = StaticUserRefs(bobValidator, bobDirectory, bobWallet)
      val bobRefs = setupUser(bobStaticRefs)

      actAndCheck(
        "Setup entries", {
          requestAndPayForEntry(aliceRefs, "alice.cns")
          requestAndPayForEntry(aliceRefs, "aliceAndCo.cns")
          requestAndPayForEntry(aliceRefs, "aliceAndSons.cns")
          requestAndPayForEntry(aliceRefs, "bobIsntHere.cns")
          requestAndPayForEntry(bobRefs, "bob.cns")
          requestAndPayForEntry(bobRefs, "bobIsCool.cns")
          requestAndPayForEntry(bobRefs, "bobAndFriends.cns")
        },
      )(
        "Lookup entries with prefixes",
        _ => {
          directory.listEntries("", 25) should have length 7
          directory.listEntries("a", 25) should have length 3
          directory.listEntries("a", 2) should have length 2
          directory.listEntries("b", 25) should have length 4
          directory.listEntries("bobIs", 25) should have length 2
          directory.listEntries("c", 25) should have length 0
          directory.listEntries("a", 0) should have length 0
          directory.listEntries("a", -1) should have length 0
        },
      )
    }

    def setupUser(refs: StaticUserRefs): DynamicUserRefs = {
      val userParty = onboardWalletUser(refs.wallet, refs.validator)

      clue("Request install and wait for provider to auto-accept") {
        refs.directory.requestDirectoryInstall()
        refs.validator.remoteParticipantWithAdminToken.ledger_api_extensions.acs
          .awaitJava(codegen.DirectoryInstall.COMPANION)(userParty)
      }

      DynamicUserRefs(userParty, refs)
    }
  }
}

object DirectoryIntegrationTest {

  // Helper classes to make it easier to write test code interacting with a users' services
  case class StaticUserRefs(
      validator: ValidatorAppBackendReference,
      directory: RemoteDirectoryAppReference,
      wallet: WalletAppClientReference,
  )

  case class DynamicUserRefs(userParty: PartyId, static: StaticUserRefs) {
    def validator: ValidatorAppBackendReference = static.validator

    def directory: RemoteDirectoryAppReference = static.directory

    def wallet: WalletAppClientReference = static.wallet
  }
}
