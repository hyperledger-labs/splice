package com.daml.network.integration.tests
import com.daml.ledger.api.v1.event.Event
import com.daml.ledger.client.binding.Primitive
import com.daml.network.codegen.CN.{Directory => codegen}
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
  IsolatedCoinEnvironments,
}
import com.daml.network.util.{CommonCoinAppInstanceReferences, Proto}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

import scala.concurrent.Future
import scala.util.Try

class DirectoryIntegrationTest
    extends CoinIntegrationTest
    with IsolatedCoinEnvironments
    with CommonCoinAppInstanceReferences {

  private val directoryDarPath =
    "apps/directory/daml/.daml/dist/directory-service-0.1.0.dar"
  private val entryName = "mycoolentry"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)

  "Directory service" should {
    "not throw an error on shutdown" in { implicit env =>
      import env._

      // Whitelist the directory service on alice's validator
      aliceValidator.remoteParticipant.dars.upload(directoryDarPath)

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

      // Whitelist the directory service on alice's validator
      aliceValidator.remoteParticipant.dars.upload(directoryDarPath)

      // The user of the directory service.
      val aliceUserParty = aliceValidator.onboardUser(aliceRemoteWallet.config.damlUser)

      // Test that we can do a racy allocation and cancellation of a directory install request multiple times
      for (_ <- 1 to 3) {
        // Remember offset
        val offsetBefore = aliceValidator.remoteParticipant.ledger_api.transactions.end()

        // Request installs and wait for provider to auto-accept
        // TODO(#790): check how one could write an assertion that command dedup is triggered; it is at least in local tests
        val n = 3
        for (_ <- 1 to n) {
          Future {
            aliceDirectory.requestDirectoryInstall()
          }
        }

        // Wait until 2*n transactions have been received (one each: create request + handle request)
        val tx = aliceValidator.remoteParticipant.ledger_api.transactions
          .flat(Set(aliceUserParty), completeAfter = 2 * n, beginOffset = offsetBefore)
        logger.info(
          Seq("Received transactions:")
            .appendedAll(tx.map(_.toString))
            .mkString(System.lineSeparator())
        )
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

    "allocate unique directory entries" in { implicit env =>
      // Whitelist the directory service on alice's validator
      aliceValidator.remoteParticipant.dars.upload(directoryDarPath)

      // The provider of the directory service
      val providerParty = directory.getProviderPartyId()

      // The user of the directory service.
      val aliceUserParty = aliceValidator.onboardUser(aliceRemoteWallet.config.damlUser)

      // Request install and wait for provider to auto-accept
      aliceDirectory.requestDirectoryInstall()
      eventually()(
        aliceValidator.remoteParticipant.ledger_api.acs
          .of_party(aliceUserParty, filterTemplates = Seq(codegen.DirectoryInstall.id))
          should have size (1)
      )

      // Request entry
      aliceDirectory.requestDirectoryEntry(entryName)

      // User: wait until payment request becomes visible
      def getPaymentRequest() = aliceRemoteWallet.listAppPaymentRequests().headOption

      aliceRemoteWallet.tap(5.0)

      val walletPaymentRequest = eventually()(
        getPaymentRequest().getOrElse(fail("Payment request is unexpectedly not defined"))
      )
      // Accept payment request
      val _ = aliceRemoteWallet.acceptAppPaymentRequest(walletPaymentRequest.contractId)

      // Wait until payment is processed and entry was created
      val entryPayload =
        codegen.DirectoryEntry(aliceUserParty.toPrim, providerParty.toPrim, entryName)
      def tryGetEntry() =
        Try(loggerFactory.suppressErrors(directory.lookupEntryByName(entryName)))
      val entry = eventually()(tryGetEntry().getOrElse(fail(s"Could not get entry $entryName")))
      entry.payload shouldBe entryPayload

      // Read entries from provider
      directory.listEntries() shouldBe Seq(entry)
      directory.lookupEntryByName(entryName) shouldBe entry
      directory.lookupEntryByParty(aliceUserParty) shouldBe entry
      assertThrowsAndLogsCommandFailures(
        directory.lookupEntryByName("nonexistentname"),
        _.errorMessage should include("nonexistentname"),
      )

      // Read entries from user
      aliceDirectory.listEntries() shouldBe Seq(entry)
      aliceDirectory.lookupEntryByName(entryName) shouldBe entry
      aliceDirectory.lookupEntryByParty(aliceUserParty) shouldBe entry
      assertThrowsAndLogsCommandFailures(
        aliceDirectory.lookupEntryByName("nonexistentname"),
        _.errorMessage should include("nonexistentname"),
      )

      // Check that a second request for the same entry is rejected
      val entriesBefore = aliceDirectory.listEntries()
      val offsetBefore = aliceValidator.remoteParticipant.ledger_api.transactions.end()
      val entryRequestId = aliceDirectory.requestDirectoryEntry(entryName)

      // Wait for the tx creating the entry request, and the following that archives it
      val txs = aliceValidator.remoteParticipant.ledger_api.transactions
        .flat(Set(aliceUserParty), completeAfter = 2, beginOffset = offsetBefore)
      inside(txs) { case Seq(_, tx2) =>
        assert(
          tx2.events.exists(ev =>
            ev.event match {
              case Event.Event.Archived(archival) => archival.contractId == entryRequestId
              case _ => false
            }
          )
        )
      }
      // Directory entries are unchanged
      aliceDirectory.listEntries() shouldBe entriesBefore

    }
  }
}
