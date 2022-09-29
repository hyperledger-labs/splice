package com.daml.network.integration.tests
import com.daml.ledger.api.v1.event.Event
import com.daml.network.codegen.CN.{Directory => codegen}
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
  IsolatedCoinEnvironments,
}
import com.daml.network.util.CommonCoinAppInstanceReferences
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

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
      .withConnectedDomains()
      .withAllocatedValidatorUsers()

  "Directory service" should {
    "list and accept install requests" in { implicit env =>
      import env._
      // Whitelist the directory service on alice's validator
      aliceValidator.remoteParticipant.dars.upload(directoryDarPath)

      // The provider of the directory service
      val providerParty = directory.getProviderPartyId()

      // The user of the directory service.
      val aliceUserParty = aliceValidator.onboardUser(aliceRemoteWallet.config.damlUser)

      // Request install and wait for provider to auto-accept
      aliceDirectory.requestDirectoryInstall()
      utils.retry_until_true(aliceDirectory.lookupInstall(aliceUserParty).isDefined)

      // Request entry
      aliceDirectory.requestDirectoryEntry(entryName)

      // User: wait until payment request becomes visible
      def getPaymentRequest() = aliceRemoteWallet.listAppPaymentRequests().headOption
      val walletPaymentRequest = utils
        .retry(getPaymentRequest())(_.isEmpty)
        .getOrElse(fail("Payment request is unexpectedly not defined."))

      // Accept payment request
      aliceRemoteWallet.tap(5.0)
      val _ = aliceRemoteWallet.acceptAppPaymentRequest(walletPaymentRequest.contractId)

      // Wait until payment is processed and entry was created
      val entryPayload =
        codegen.DirectoryEntry(aliceUserParty.toPrim, providerParty.toPrim, entryName)
      def tryGetEntry() =
        Try(loggerFactory.suppressErrors(directory.lookupEntryByName(entryName)))
      val entry = utils.retry(tryGetEntry())(_.isFailure).get
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
