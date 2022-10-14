package com.daml.network.integration.tests

import com.daml.ledger.client.binding.Primitive
import com.daml.network.codegen.CN.{Directory => codegen}
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
  IsolatedCoinEnvironments,
}
import com.daml.network.util.{CommonCoinAppInstanceReferences, Proto}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.topology.PartyId

import scala.concurrent.Future
import scala.util.Try

class DirectoryIntegrationTest
    extends CoinIntegrationTest
    with IsolatedCoinEnvironments
    with CommonCoinAppInstanceReferences {

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
        }

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
        loggerFactory.assertLogs(
          {
            val n = 3
            // loggerFactory.assertLogs() assertThrowsAndLogs({
            for (_ <- 1 to n)
              Future {
                aliceDirectory.requestDirectoryInstall()
              }

            // Wait until 2*n transactions have been received (one each: create request + handle request)
            val tx = aliceValidator.remoteParticipant.ledger_api.transactions
              .flat(Set(aliceUserParty), completeAfter = 2 * n, beginOffset = offsetBefore)
            logger.info(
              Seq("Received transactions:")
                .appendedAll(tx.map(_.toString))
                .mkString(System.lineSeparator())
            )
          },
          _.warningMessage should include("Rejecting duplicate install request from user party"),
          _.warningMessage should include("Rejecting duplicate install request from user party"),
        )

        // check that there is only one install
        val installs = aliceValidator.remoteParticipant.ledger_api.acs
          .of_party(aliceUserParty, filterTemplates = Seq(codegen.DirectoryInstall.id))
        installs should have size (1)

        val requests = aliceValidator.remoteParticipant.ledger_api.acs
          .of_party(aliceUserParty, filterTemplates = Seq(codegen.DirectoryInstallRequest.id))
        requests shouldBe Seq.empty

        // Cancel install
        val arg = codegen.DirectoryInstall_Cancel(aliceUserParty.toPrim)
        val installCid: Primitive.ContractId[codegen.DirectoryInstall] =
          Proto.tryDecodeContractId(installs(0).event.contractId)
        val cmd = installCid.exerciseDirectoryInstall_Cancel(arg).command
        aliceValidator.remoteParticipant.ledger_api.commands.submit_flat(
          actAs = Seq(aliceUserParty),
          commands = Seq(cmd),
          optTimeout = None, // Setting to 'None' as otherwise the tx lookup fails
        )

        // Wait for install to no longer be available on alice's participant
        eventually()(
          aliceValidator.remoteParticipant.ledger_api.acs
            .of_party(
              aliceUserParty,
              filterTemplates = Seq(codegen.DirectoryInstall.id),
            ) shouldBe empty
        )
      }
    }

    "allocate unique directory entries, even when multiple parties race for them" in {
      implicit env =>
        import env._

        // The provider of the directory service
        val providerParty = directory.getProviderPartyId()

        def setupUser(refs: StaticUserRefs): DynamicUserRefs = {
          val userParty = refs.validator.onboardUser(refs.wallet.config.damlUser)

          // Request install and wait for provider to auto-accept
          refs.directory.requestDirectoryInstall()
          refs.validator.remoteParticipant.ledger_api.acs.await(userParty, codegen.DirectoryInstall)

          DynamicUserRefs(userParty, refs)
        }

        def requestAndPayForEntry(refs: DynamicUserRefs, entryName: String) = {
          // Grab the current offset
          val offsetBefore = refs.validator.remoteParticipant.ledger_api.transactions.end()

          // Request entry and get some money to pay for it
          refs.directory.requestDirectoryEntry(entryName)
          refs.wallet.tap(5.0)

          // Accept first payment request that becomes visible
          eventually()(inside(refs.wallet.listAppPaymentRequests()) { case reqId +: _ =>
            refs.wallet.acceptAppPaymentRequest(reqId.contractId)
          })

          // Wait until create entry-request, request payment, accept payment, and collect payment  have been processed
          // NOTE: we wait for transaction to ensure the accepted payment has been processed *independent* of the outcome (accept or reject).
          val txs = refs.validator.remoteParticipant.ledger_api.transactions
            .flat(Set(refs.userParty), completeAfter = 4, beginOffset = offsetBefore)
          logger.info(
            Seq(s"Received transactions for ${refs.userParty}:")
              .appendedAll(txs.map(_.toString))
              .mkString(System.lineSeparator())
          )
        }

        // Setup alice
        val aliceStaticRefs = StaticUserRefs(aliceValidator, aliceDirectory, aliceRemoteWallet)
        val aliceRefs = setupUser(aliceStaticRefs)

        // Setup bob
        val bobStaticRefs = StaticUserRefs(bobValidator, bobDirectory, bobRemoteWallet)
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
            // TODO(#790): check how one could write an assertion that command dedup is triggered
            aliceF.futureValue
            bobF.futureValue
          },
          _.warningMessage should include(
            "rejecting accepted app payment: entry already exists and owned by"
          ),
        )

        // Check who won
        def tryGetEntry() =
          Try(loggerFactory.suppressErrors(directory.lookupEntryByName(testEntryName)))
        val entry =
          eventually()(tryGetEntry().getOrElse(fail(s"Could not get entry $testEntryName")))
        val winnerUserParty = PartyId.tryFromPrim(entry.payload.user)
        logger.info(s"And the winner is ... *drumroll* ... : $winnerUserParty")

        // Check content of winning entry
        val entryPayload =
          codegen.DirectoryEntry(
            winnerUserParty.toPrim,
            providerParty.toPrim,
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
