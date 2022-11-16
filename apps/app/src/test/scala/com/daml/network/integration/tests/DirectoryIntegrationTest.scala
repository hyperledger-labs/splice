package com.daml.network.integration.tests

import com.daml.network.codegen.java.cn.wallet.payment as walletCodegen
import com.daml.network.codegen.java.cn.{directory => codegen}
import com.daml.network.console.{
  LocalValidatorAppReference,
  RemoteDirectoryAppReference,
  RemoteWalletAppReference,
}
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
}
import com.daml.network.wallet.admin.api.client.commands.GrpcWalletAppClient
import com.digitalasset.canton.DiscardOps
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.topology.PartyId
import org.slf4j.event.Level.WARN

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*
import scala.util.Try

class DirectoryIntegrationTest extends CoinIntegrationTest {

  import DirectoryIntegrationTest._

  private val directoryDarPath =
    "apps/directory/daml/.daml/dist/directory-service-0.1.0.dar"
  private val testEntryName = "mycoolentry"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
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
      import env._

      // The user of the directory service.
      val aliceUserParty = aliceValidator.onboardUser(aliceRemoteWallet.config.damlUser)
      val offsetBefore = directoryValidator.remoteParticipant.ledger_api.transactions.end()

      // Trigger three concurrent install requests
      for (_ <- 1 to 3)
        Future {
          aliceDirectory.requestDirectoryInstall()
        }.discard

      // Wait for one transaction, so that automation likely kicks-off but shutdown initiates quickly
      // and thus results in 'handleDirectoryInstallRequest' handlers being aborted due to shutdown.
      directoryValidator.remoteParticipant.ledger_api.transactions
        .flat(Set(aliceUserParty), completeAfter = 1, beginOffset = offsetBefore)
    // TODO(#790): figure out how to assert on at least one of the handleDirectoryInstallRequest automation services getting aborted
    }

    "accept unique install requests" in { implicit env =>
      import env._
      // The user of the directory service.
      val aliceUserParty = aliceValidator.onboardUser(aliceRemoteWallet.config.damlUser)

      // Test that we can do a racy allocation and cancellation of a directory install request multiple times
      for (_ <- 1 to 3) {
        // Remember offset
        val offsetBefore = aliceValidator.remoteParticipant.ledger_api.transactions.end()

        // Request installs and wait for provider to auto-accept
        val n = 3
        (1 to n).foreach(_ => Future(aliceDirectory.requestDirectoryInstall()).discard)

        // Wait until 2*n transactions have been received (one each: create request + handle request)
        val tx = aliceValidator.remoteParticipant.ledger_api.transactions
          .flat(Set(aliceUserParty), completeAfter = 2 * n, beginOffset = offsetBefore)
        logger.info(
          Seq("Received transactions:")
            .appendedAll(tx.map(_.toString))
            .mkString(System.lineSeparator())
        )

        // check that there is only one install
        val installs = aliceValidator.remoteParticipant.ledger_api.acs
          .filterJava(codegen.DirectoryInstall.COMPANION)(aliceUserParty)
        installs should have size (1)

        val requests = aliceValidator.remoteParticipant.ledger_api.acs
          .filterJava(codegen.DirectoryInstallRequest.COMPANION)(aliceUserParty)
        requests shouldBe Seq.empty

        // Cancel install
        val installCid: codegen.DirectoryInstall.ContractId = installs(0).id
        val cmds = installCid
          .exerciseDirectoryInstall_Cancel(aliceUserParty.toProtoPrimitive)
          .commands
          .asScala
          .toSeq
        aliceValidator.remoteParticipant.ledger_api.commands.submitJava(
          actAs = Seq(aliceUserParty),
          commands = cmds,
          optTimeout = None, // Setting to 'None' as otherwise the tx lookup fails
        )

        // Wait for install to no longer be available on alice's participant
        eventually()(
          aliceValidator.remoteParticipant.ledger_api.acs
            .filterJava(codegen.DirectoryInstall.COMPANION)(
              aliceUserParty
            ) shouldBe empty
        )
      }
    }

    "allocate unique directory entries, even when multiple parties race for them" in {
      implicit env =>
        import env._

        // The provider of the directory service
        val providerParty = directory.getProviderPartyId()

        def requestAndPayForEntry(refs: DynamicUserRefs, entryName: String): String = {
          refs.wallet.tap(5.0)

          // Request entry and get some money to pay for it
          val entryRequest = refs.directory.requestDirectoryEntry(entryName)

          // Wait for DirectoryEntryRequest to be archived
          eventually() {
            refs.validator.remoteParticipant.ledger_api.acs
              .filterJava(codegen.DirectoryEntryRequest.COMPANION)(
                refs.userParty,
                (request: codegen.DirectoryEntryRequest.Contract) => request.id == entryRequest,
              ) shouldBe empty
          }

          // Check if a payment request got created
          val paymentRequests = refs.validator.remoteParticipant.ledger_api.acs
            .filterJava(walletCodegen.AppPaymentRequest.COMPANION)(
              refs.userParty
            )

          paymentRequests match {
            case Seq(req) =>
              // Accept payment request, we need another eventually here to wait for store ingestion.
              val accepted = eventually()(inside(refs.wallet.listAppPaymentRequests()) {
                case Seq(storeRequest) =>
                  storeRequest.contractId shouldBe req.id
                  refs.wallet.acceptAppPaymentRequest(storeRequest.contractId)
              })
              // Wait for the AcceptedAppPayment to be archived
              eventually() {
                refs.validator.remoteParticipant.ledger_api.acs
                  .filterJava(walletCodegen.AcceptedAppPayment.COMPANION)(
                    refs.userParty,
                    (request: walletCodegen.AcceptedAppPayment.Contract) => request.id == accepted,
                  ) shouldBe empty
              }
              "AcceptedRequest"
            case Seq() =>
              // No payment request, other entry already got created
              "RejectedRequest"
            case _ => fail(s"More than one payment request created: $paymentRequests")
          }
        }

        // Setup alice
        val aliceStaticRefs = StaticUserRefs(aliceValidator, aliceDirectory, aliceRemoteWallet)
        val aliceRefs = setupUser(aliceStaticRefs)

        // Setup bob
        val bobStaticRefs = StaticUserRefs(bobValidator, bobDirectory, bobRemoteWallet)
        val bobRefs = setupUser(bobStaticRefs)

        // Concurrently, request an entry as alice and bob
        loggerFactory.assertLogsSeqWithResult[Seq[String]](SuppressionRule.LevelAndAbove(WARN))(
          {
            val aliceF = Future {
              requestAndPayForEntry(aliceRefs, testEntryName)
            }
            val bobF = Future {
              requestAndPayForEntry(bobRefs, testEntryName)
            }

            // Wait for both of them
            // TODO(#790): check how one could write an assertion that command dedup is triggered
            Seq(aliceF.futureValue, bobF.futureValue)
          },
          {
            case (results, logs) if results.contains("RejectedRequest") =>
              // We don’t log on requested requests since that’s a user error.
              logs shouldBe empty
            case (results, logs) =>
              results should contain("AcceptedRequest")
              inside(logs) { case Seq(msg) =>
                msg.warningMessage should include(
                  "rejecting accepted app payment: entry already exists and owned by"
                )
              }

          },
        )

        // Check who won
        def tryGetEntry() =
          Try(loggerFactory.suppressErrors(directory.lookupEntryByName(testEntryName)))
        val entry =
          eventually()(tryGetEntry().getOrElse(fail(s"Could not get entry $testEntryName")))
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
        directory.listEntries() shouldBe Seq(entry)
        directory.lookupEntryByName(testEntryName) shouldBe entry
        directory.lookupEntryByParty(winnerUserParty) shouldBe entry
        assertThrowsAndLogsCommandFailures(
          directory.lookupEntryByName("nonexistentname"),
          _.errorMessage should include("nonexistentname"),
        )
    }
    "allocate directory entries following an initial subscription payment and renew entries on follow-up payments" in {
      implicit env =>
        val aliceUserParty = aliceValidator.onboardUser(aliceRemoteWallet.config.damlUser)
        val providerParty = directory.getProviderPartyId()

        val aliceRefs = clue("Setup Alice") {
          val aliceStaticRefs = StaticUserRefs(aliceValidator, aliceDirectory, aliceRemoteWallet)
          setupUser(aliceStaticRefs)
        }
        val (_, subReqId) = clue("Alice requests a directory entry") {
          aliceRefs.directory.requestDirectoryEntryWithSubscription(testEntryName)
        }
        clue("Alice obtains some coins and accepts the subscription") {
          aliceRefs.wallet.tap(50.0)
          aliceRefs.wallet.acceptSubscriptionRequest(subReqId)
        }
        val entry = clue("Getting Alice's new entry") {
          def tryGetEntry() =
            Try(loggerFactory.suppressErrors(directory.lookupEntryByName(testEntryName)))
          eventually()(tryGetEntry().getOrElse(fail(s"Could not get entry $testEntryName")))
        }
        clue("Checking payload of new entry") {
          val expectedPayload = new codegen.DirectoryEntry(
            aliceUserParty.toProtoPrimitive,
            providerParty.toProtoPrimitive,
            testEntryName,
            entry.payload.expiresAt,
          )
          entry.payload shouldBe expectedPayload
        }
        clue("Alice makes a follow-up subscription payment") {
          val subscriptionStateId =
            eventually()(inside(aliceRefs.wallet.listSubscriptions()) { case Seq(sub) =>
              inside(sub.state) { case GrpcWalletAppClient.SubscriptionIdleState(state) =>
                state.contractId
              }
            })
          aliceRefs.wallet.makeSubscriptionPayment(subscriptionStateId)
        }
        val renewedEntry = clue("Getting Alice's renewed entry") {
          eventually()(
            directory
              .lookupEntryByName(testEntryName)
              .contractId should not equal entry.contractId
          )
          directory.lookupEntryByName(testEntryName)
        }
        clue("Checking payload of renewed entry") {
          val newEntry = new codegen.DirectoryEntry(
            entry.payload.user,
            entry.payload.provider,
            entry.payload.name,
            entry.payload.expiresAt.plus(90, ChronoUnit.DAYS),
          )
          renewedEntry.payload shouldBe newEntry
        }
    }
    "archive expired directory entries" in { implicit env =>
      clue("Creating a directory entry that expires immediately") {
        directory.listEntries() shouldBe empty
        val dirParty = directory.getProviderPartyId()
        directory.remoteParticipant.ledger_api.commands.submitJava(
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
          directory.listEntries() should not be empty
        )
      }
      clue("Waiting for the backend to expire the entry...") {
        eventually()(
          directory.listEntries() shouldBe empty
        )
      }
    }

    def setupUser(refs: StaticUserRefs): DynamicUserRefs = {
      val userParty = refs.validator.onboardUser(refs.wallet.config.damlUser)

      clue("Request install and wait for provider to auto-accept") {
        refs.directory.requestDirectoryInstall()
        refs.validator.remoteParticipant.ledger_api.acs
          .awaitJava(codegen.DirectoryInstall.COMPANION)(userParty)
      }

      DynamicUserRefs(userParty, refs)
    }
  }
}

object DirectoryIntegrationTest {

  // Helper classes to make it easier to write test code interacting with a users' services
  case class StaticUserRefs(
      validator: LocalValidatorAppReference,
      directory: RemoteDirectoryAppReference,
      wallet: RemoteWalletAppReference,
  )

  case class DynamicUserRefs(userParty: PartyId, static: StaticUserRefs) {
    def validator: LocalValidatorAppReference = static.validator
    def directory: RemoteDirectoryAppReference = static.directory
    def wallet: RemoteWalletAppReference = static.wallet
  }
}
