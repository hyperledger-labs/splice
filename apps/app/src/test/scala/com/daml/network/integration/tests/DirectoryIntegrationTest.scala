package com.daml.network.integration.tests

import com.daml.network.codegen.java.cn.directory as codegen
import com.daml.network.codegen.java.cn.wallet.subscriptions as subsCodegen
import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.console.{
  DirectoryAppClientReference,
  ValidatorAppBackendReference,
  WalletAppClientReference,
}
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.plugins.UseInMemoryStores
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
import scala.util.Try

class InMemoryDirectoryIntegrationTest extends DirectoryIntegrationTest {
  registerPlugin(new UseInMemoryStores(loggerFactory))
}

class DirectoryIntegrationTest extends CNNodeIntegrationTest with WalletTestUtil {

  import DirectoryIntegrationTest.*

  private val testEntryName = "mycoolentry.unverified.cns"
  private val testEntryUrl = "https://cns-dir-url.com"
  private val testEntryDescription = "Sample CNS Directory Entry Description"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      // start only sv1 but not sv2-4
      .addConfigTransformToFront(
        CNNodeConfigTransforms.onlySv1
      )

  "Directory service" should {

    "restart cleanly" in { implicit env =>
      directoryBackend.stop()
      directoryBackend.startSync()
    }

    "not throw an error on shutdown" in { implicit env =>
      import env.*

      // The user of the directory service.
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val offsetBefore =
        directoryBackend.participantClientWithAdminToken.ledger_api.transactions.end()

      // Trigger three concurrent install requests
      for (_ <- 1 to 3)
        Future {
          aliceDirectoryClient.requestDirectoryInstall()
        }.discard

      // Wait for one transaction, so that automation likely kicks-off but shutdown initiates quickly
      // and thus results in 'handleDirectoryInstallRequest' handlers being aborted due to shutdown.
      directoryBackend.participantClientWithAdminToken.ledger_api.transactions
        .flat(Set(aliceUserParty), completeAfter = 1, beginOffset = offsetBefore)
    }

    "use svc as provider party" in { implicit env =>
      val svc = sv1Backend.getSvcInfo().svcParty
      directoryBackend.getProviderPartyId() shouldBe svc
    }

    "ensure unique install despite racing install requests w/ and w/o archivals" in {
      implicit env =>
        // NOTE: this test also serves to check that the stale-contract detection logic in the OnCreateTrigger works properly
        import env.*

        // The user of the directory service.
        val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)

        def raceInstalls() = {
          val (_, installCid) = actAndCheck(
            "Trigger multiple install requests followed immediately by their archival", {
              val installAttemptsFs = Range(0, 10).map(i =>
                Future {
                  clue(s"creating and archiving request $i") {
                    val requestId = aliceDirectoryClient.requestDirectoryInstall()
                    // Issue a concurrent archival for all except the first three requests
                    if (3 <= i) {
                      // TODO(tech-debt): get rid of this ugly archive argument once https://github.com/digital-asset/daml/issues/15540 is resolved
                      val cmd =
                        requestId.exerciseArchive(
                          new com.daml.network.codegen.java.da.internal.template.Archive()
                        )
                      try {
                        aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
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
                  aliceValidatorBackend.participantClient.ledger_api_extensions.acs.filterJava(
                    codegen.DirectoryInstallRequest.COMPANION
                  )(aliceUserParty) shouldBe empty
                }
              }
            },
          )(
            "there is exactly one install and no left-over request",
            _ => {
              val installs =
                aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
                  .filterJava(codegen.DirectoryInstall.COMPANION)(aliceUserParty)
              installs should have size (1)
              val requests =
                aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
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
            aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
              .submitJava(
                actAs = Seq(aliceUserParty),
                commands = cmds,
                optTimeout = None, // Setting to 'None' as otherwise the tx lookup fails
              )
          },
        )(
          "There is no install contract left",
          _ =>
            aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
              .filterJava(codegen.DirectoryInstall.COMPANION)(
                aliceUserParty
              ) shouldBe empty,
        )
    }

    def requestAndPayForEntry(
        refs: DynamicUserRefs,
        entryName: String,
        entryUrl: String = "https://cns-dir-url.com",
        entryDescription: String = "Sample CNS Directory Entry Description",
    ) = {
      refs.wallet.tap(5.0)

      // Request entry and get some money to pay for it
      val (_, subscriptionRequest) =
        refs.directory.requestDirectoryEntry(entryName, entryUrl, entryDescription)

      // Wait for subscription request to be ingested into store
      // and accept it.
      val initialPayment = eventually()(inside(refs.wallet.listSubscriptionRequests()) {
        case Seq(storeRequest) =>
          storeRequest.contractId shouldBe subscriptionRequest
          refs.wallet.acceptSubscriptionRequest(storeRequest.contractId)
      })
      // Wait for the SubscriptionInitialPayment to be archived
      eventually() {
        refs.validator.participantClientWithAdminToken.ledger_api_extensions.acs
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
        val providerParty = directoryBackend.getProviderPartyId()

        // Setup alice
        val aliceStaticRefs =
          StaticUserRefs(aliceValidatorBackend, aliceDirectoryClient, aliceWalletClient)
        val aliceRefs = setupUser(aliceStaticRefs)

        // Setup bob
        val bobStaticRefs = StaticUserRefs(bobValidatorBackend, bobDirectoryClient, bobWalletClient)
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
          directoryBackend.lookupEntryByName(testEntryName)
        }

        val winnerUserParty = PartyId.tryFromProtoPrimitive(entry.payload.user)
        logger.info(s"And the winner is ... *drumroll* ... : $winnerUserParty")

        // Check content of winning entry
        val entryPayload =
          new codegen.DirectoryEntry(
            winnerUserParty.toProtoPrimitive,
            providerParty.toProtoPrimitive,
            testEntryName,
            entry.payload.url,
            entry.payload.description,
            entry.payload.expiresAt,
          )
        entry.payload shouldBe entryPayload

        // Read entries from provider
        directoryBackend.listEntries("", 25) shouldBe Seq(entry)
        directoryBackend.lookupEntryByName(testEntryName) shouldBe entry
        directoryBackend.lookupEntryByParty(winnerUserParty) shouldBe entry
        assertThrowsAndLogsCommandFailures(
          directoryBackend.lookupEntryByName("nonexistentname"),
          _.errorMessage should include("nonexistentname"),
        )
    }

    "archive expired directory entries" in { implicit env =>
      clue("Creating a directory entry that expires immediately") {
        directoryBackend.listEntries("", 25) shouldBe empty
        val dirParty = directoryBackend.getProviderPartyId()
        directoryBackend.participantClientWithAdminToken.ledger_api_extensions.commands.submitJava(
          actAs = Seq(dirParty),
          commands = new codegen.DirectoryEntry(
            dirParty.toProtoPrimitive,
            dirParty.toProtoPrimitive,
            testEntryName,
            testEntryUrl,
            testEntryDescription,
            Instant.now().plus(1, ChronoUnit.SECONDS),
          ).create.commands.asScala.toSeq,
          optTimeout = None,
        )
        eventually()(
          directoryBackend.listEntries("", 25) should not be empty
        )
      }
      clue("Waiting for the backend to expire the entry...") {
        eventually()(
          directoryBackend.listEntries("", 25) shouldBe empty
        )
      }
    }

    "support prefix lookup" in { implicit env =>
      val aliceStaticRefs =
        StaticUserRefs(aliceValidatorBackend, aliceDirectoryClient, aliceWalletClient)
      val aliceRefs = setupUser(aliceStaticRefs)
      val bobStaticRefs = StaticUserRefs(bobValidatorBackend, bobDirectoryClient, bobWalletClient)
      val bobRefs = setupUser(bobStaticRefs)

      actAndCheck(
        "Setup entries", {
          requestAndPayForEntry(aliceRefs, "alice.unverified.cns")
          requestAndPayForEntry(aliceRefs, "alice-and-co.unverified.cns")
          requestAndPayForEntry(aliceRefs, "alice-and-sons.unverified.cns")
          requestAndPayForEntry(aliceRefs, "bob-isnt_here.unverified.cns")
          requestAndPayForEntry(bobRefs, "bob.unverified.cns")
          requestAndPayForEntry(bobRefs, "bob-is-cool.unverified.cns")
          requestAndPayForEntry(bobRefs, "bob_and_friends.unverified.cns")
        },
      )(
        "Lookup entries with prefixes",
        _ => {
          directoryBackend.listEntries("", 25) should have length 7
          directoryBackend.listEntries("a", 25) should have length 3
          directoryBackend.listEntries("a", 2) should have length 2
          directoryBackend.listEntries("b", 25) should have length 4
          directoryBackend.listEntries("bob-is", 25) should have length 2
          directoryBackend.listEntries("c", 25) should have length 0
          loggerFactory.assertLogs(
            Try(directoryBackend.listEntries("a", 0)).isFailure should be(true),
            _.errorMessage should include(
              "Limit must be at least 1."
            ),
          )
          loggerFactory.assertLogs(
            Try(directoryBackend.listEntries("a", -1)).isFailure should be(true),
            _.errorMessage should include(
              "Limit must be at least 1."
            ),
          )
        },
      )
    }

    "reject invalid entry names" in { implicit env =>
      val aliceStaticRefs =
        StaticUserRefs(aliceValidatorBackend, aliceDirectoryClient, aliceWalletClient)
      val aliceRefs = setupUser(aliceStaticRefs)

      clue("invalid entries(bad names) are rejected") {
        val invalidNames =
          Seq("alice.company.unverified.cns", "alice$company.unverified.cns", "alice.cns")
        invalidNames.foreach { name =>
          loggerFactory.assertLogs(
            {
              requestAndPayForEntry(aliceRefs, name)
            },
            _.warningMessage should include(s"entry name ($name) is not valid"),
          )
        }
      }
    }

    "reject invalid entry urls" in { implicit env =>
      val aliceStaticRefs =
        StaticUserRefs(aliceValidatorBackend, aliceDirectoryClient, aliceWalletClient)
      val aliceRefs = setupUser(aliceStaticRefs)

      clue("invalid entries(bad urls) are rejected") {
        val invalidUrls =
          Seq("s3://alice.arn.cns", "http://asdklfjh%skldjfgh", s"https://${"alice-" * 50}.cns.com")
        invalidUrls.foreach { url =>
          loggerFactory.assertLogs(
            {
              requestAndPayForEntry(aliceRefs, "alice.unverified.cns", entryUrl = url)
            },
            _.warningMessage should include(s"entry url ($url) is not valid"),
          )
        }
      }
    }

    "reject invalid entry descriptions" in { implicit env =>
      val aliceStaticRefs =
        StaticUserRefs(aliceValidatorBackend, aliceDirectoryClient, aliceWalletClient)
      val aliceRefs = setupUser(aliceStaticRefs)

      clue("invalid entries(bad descriptions) are rejected") {
        val invalidDescriptions = Seq("Sample CNS Directory Entry Description -" * 50)
        invalidDescriptions.foreach { desc =>
          loggerFactory.assertLogs(
            {
              requestAndPayForEntry(aliceRefs, "alice.unverified.cns", entryDescription = desc)
            },
            _.warningMessage should include(s"entry description ($desc) is not valid"),
          )
        }
      }
    }

    "archives terminated DirectoryEntryContext contracts" in { implicit env =>
      val aliceStaticRefs =
        StaticUserRefs(aliceValidatorBackend, aliceDirectoryClient, aliceWalletClient)
      val aliceRefs = setupUser(aliceStaticRefs)
      val ((_, subscriptionRequest), _) = actAndCheck(
        "request directory entry",
        aliceRefs.directory
          .requestDirectoryEntry(testEntryName, "https://example.com", "description"),
      )(
        "alice sees subscription request",
        _ => aliceRefs.wallet.listSubscriptionRequests() should have size 1,
      )
      aliceDirectoryClient.ledgerApi.ledger_api_extensions.acs
        .filterJava(codegen.DirectoryEntryContext.COMPANION)(
          aliceRefs.userParty
        ) should have size 1
      actAndCheck(
        "alice rejects subscription request",
        aliceWalletClient.rejectSubscriptionRequest(subscriptionRequest),
      )(
        "DirectoryEntryContext gets archived",
        _ =>
          aliceDirectoryClient.ledgerApi.ledger_api_extensions.acs
            .filterJava(codegen.DirectoryEntryContext.COMPANION)(
              aliceRefs.userParty
            ) should have size 0,
      )
    }

    def setupUser(refs: StaticUserRefs): DynamicUserRefs = {
      val userParty = onboardWalletUser(refs.wallet, refs.validator)

      clue("Request install and wait for provider to auto-accept") {
        refs.directory.requestDirectoryInstall()
        refs.validator.participantClientWithAdminToken.ledger_api_extensions.acs
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
      directory: DirectoryAppClientReference,
      wallet: WalletAppClientReference,
  )

  case class DynamicUserRefs(userParty: PartyId, static: StaticUserRefs) {
    def validator: ValidatorAppBackendReference = static.validator

    def directory: DirectoryAppClientReference = static.directory

    def wallet: WalletAppClientReference = static.wallet
  }
}
