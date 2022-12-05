package com.daml.network.integration.tests

import com.daml.network.codegen.java.cn.wallet.subscriptions as subsCodegen
import com.daml.network.codegen.java.cn.{directory => codegen}
import com.daml.network.console.{
  LocalValidatorAppReference,
  RemoteDirectoryAppReference,
  WalletAppClientReference,
}
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
}
import com.daml.network.util.CoinTestUtil
import com.digitalasset.canton.DiscardOps
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.topology.PartyId

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*
import scala.util.Try

class DirectoryIntegrationTest extends CoinIntegrationTest with CoinTestUtil {

  import DirectoryIntegrationTest._

  private val directoryDarPath =
    "daml/directory-service/.daml/dist/directory-service-0.1.0.dar"
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
      val aliceUserParty = onboardWalletUser(this, aliceWallet, aliceValidator)
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
    }

    "accept unique install requests" in { implicit env =>
      import env._
      // The user of the directory service.
      val aliceUserParty = onboardWalletUser(this, aliceWallet, aliceValidator)

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

        def requestAndPayForEntry(refs: DynamicUserRefs, entryName: String) = {
          refs.wallet.tap(5.0)

          // Request entry and get some money to pay for it
          val (_, subscriptionRequest) =
            refs.directory.requestDirectoryEntryWithSubscription(entryName)

          // Wait for subscription request to be ingested into store
          // and accept it.
          val initialPayment = eventually()(inside(refs.wallet.listSubscriptionRequests()) {
            case Seq(storeRequest) =>
              storeRequest.contractId shouldBe subscriptionRequest
              refs.wallet.acceptSubscriptionRequest(storeRequest.contractId)
          })
          // Wait for the SubscriptionInitialPayment to be archived
          eventually() {
            refs.validator.remoteParticipant.ledger_api.acs
              .filterJava(subsCodegen.SubscriptionInitialPayment.COMPANION)(
                refs.userParty,
                (request: subsCodegen.SubscriptionInitialPayment.Contract) =>
                  request.id == initialPayment,
              ) shouldBe empty
          }
        }

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
      val userParty = onboardWalletUser(this, refs.wallet, refs.validator)

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
      wallet: WalletAppClientReference,
  )

  case class DynamicUserRefs(userParty: PartyId, static: StaticUserRefs) {
    def validator: LocalValidatorAppReference = static.validator
    def directory: RemoteDirectoryAppReference = static.directory
    def wallet: WalletAppClientReference = static.wallet
  }
}
