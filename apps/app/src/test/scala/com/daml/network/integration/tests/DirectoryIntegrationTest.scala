package com.daml.network.integration.tests
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
  IsolatedCoinEnvironments,
}
import com.daml.network.util.{CommonCoinAppInstanceReferences, Contract}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.daml.network.codegen.CN.{Directory => codegen, Wallet => walletCodegen}

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
      aliceValidator.remoteParticipant.ledger_api.acs
        .await(aliceUserParty, codegen.DirectoryInstall)
      aliceDirectory.requestDirectoryEntry(entryName)
      val entryRequest = directory.remoteParticipant.ledger_api.acs
        .await(providerParty, codegen.DirectoryEntryRequest)
      val entryRequests = directory.listEntryRequests()
      entryRequests.map(_.contractId) shouldBe Seq(entryRequest.contractId)

      // Provider: Request payment for entry
      val paymentRequest = directory.requestEntryPayment(entryRequest.contractId)

      // User: wait until payment request becomes visible
      def getPaymentRequest() =
        aliceRemoteWallet
          .listAppPaymentRequests()
          .headOption
      utils.retry_until_true { getPaymentRequest().isDefined }
      val walletPaymentRequest =
        getPaymentRequest().getOrElse(sys.error("Payment request is unexpectedly not defined."))
      walletPaymentRequest.contractId shouldBe paymentRequest

      // Accept payment request
      aliceRemoteWallet.tap(5.0)
      val _ = aliceRemoteWallet.acceptAppPaymentRequest(walletPaymentRequest.contractId)

      // Collect payment
      val acceptedPayment = directory.remoteParticipant.ledger_api.acs
        .await(providerParty, walletCodegen.AcceptedAppPayment)
      val cid = directory.collectEntryPayment(acceptedPayment.contractId)
      val entry = directory.remoteParticipant.ledger_api.acs
        .await(providerParty, codegen.DirectoryEntry)
      entry.contractId shouldBe cid

      val entryValue =
        Contract(
          cid,
          codegen.DirectoryEntry(aliceUserParty.toPrim, providerParty.toPrim, entryName),
        )

      // Read entries from provider
      directory.listEntries() shouldBe Seq(entryValue)
      directory.lookupEntryByName(entryName) shouldBe entryValue
      directory.lookupEntryByParty(aliceUserParty) shouldBe entryValue
      assertThrowsAndLogsCommandFailures(
        directory.lookupEntryByName("nonexistentname"),
        _.errorMessage should include("nonexistentname"),
      )

      // Read entries from user
      aliceDirectory.listEntries() shouldBe Seq(entryValue)
      aliceDirectory.lookupEntryByName(entryName) shouldBe entryValue
      aliceDirectory.lookupEntryByParty(aliceUserParty) shouldBe entryValue
      assertThrowsAndLogsCommandFailures(
        aliceDirectory.lookupEntryByName("nonexistentname"),
        _.errorMessage should include("nonexistentname"),
      )
    }
  }
}
