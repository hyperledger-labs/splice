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
import com.daml.network.codegen.{CC => coinCodegen}

class DirectoryProviderIntegrationTest
    extends CoinIntegrationTest
    with IsolatedCoinEnvironments
    with CommonCoinAppInstanceReferences {

  private val directoryDarPath =
    "apps/directory-provider/daml/.daml/dist/directory-service-0.1.0.dar"
  private val entryName = "mycoolentry"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .withSetup(implicit env => {
        directoryValidator.remoteParticipant.dars.upload(directoryDarPath)
        aliceValidator.remoteParticipant.dars.upload(directoryDarPath)
      })

  "A directory provider" should {
    "list and accept install requests" in { implicit env =>
      import env._

      // The validator operator of the user of the directory service.
      val aliceValidatorParty = aliceValidator.initialize()
      // The provider of the directory service
      val providerParty = directoryValidator.initialize()

      aliceWallet.initialize(aliceValidatorParty)
      aliceWallet.remoteParticipant.ledger_api.acs
        .await(aliceValidatorParty, coinCodegen.CoinRules.CoinRules)

      // The user of the directory service.
      val userParty = aliceValidator.onboardUser(aliceRemoteWallet.config.damlUser)

      // Setup DirectoryInstall

      directoryProvider.listInstallRequests() shouldBe Seq()

      val installRequestCid = aliceDirectory.requestDirectoryInstall()

      directoryProvider.remoteParticipant.ledger_api.acs
        .await(providerParty, codegen.DirectoryInstallRequest)

      val requests = directoryProvider.listInstallRequests()

      requests.map(_.contractId) shouldBe Seq(installRequestCid)

      val installsBefore = directoryProvider.remoteParticipant.ledger_api.acs
        .of_party(providerParty, filterTemplates = Seq(codegen.DirectoryInstall.id))
      installsBefore shouldBe empty

      requests.foreach { case request =>
        directoryProvider.acceptInstallRequest(request.contractId)
      }

      val installsAfter = directoryProvider.remoteParticipant.ledger_api.acs
        .of_party(providerParty, filterTemplates = Seq(codegen.DirectoryInstall.id))
      installsAfter should have length (1)

      directoryProvider.listInstallRequests() shouldBe empty

      // Request entry

      aliceValidator.remoteParticipant.ledger_api.acs.await(userParty, codegen.DirectoryInstall)
      aliceDirectory.requestDirectoryEntry(entryName)
      val entryRequest = directoryProvider.remoteParticipant.ledger_api.acs
        .await(providerParty, codegen.DirectoryEntryRequest)
      val entryRequests = directoryProvider.listEntryRequests()
      entryRequests.map(_.contractId) shouldBe Seq(entryRequest.contractId)

      // Provider: Request payment for entry
      val paymentRequest = directoryProvider.requestEntryPayment(entryRequest.contractId)

      // User: wait until payment request becomes visible
      def getPaymentRequest() =
        aliceRemoteWallet
          .listAppPaymentRequests()
          .find(c => c.payload.reference == entryRequest.contractId)
      utils.retry_until_true { getPaymentRequest().isDefined }
      val walletPaymentRequest =
        getPaymentRequest().getOrElse(sys.error("Payment request is unexpectedly not defined."))
      walletPaymentRequest.contractId shouldBe paymentRequest

      // Accept payment request
      val coin = aliceRemoteWallet.tap(5.0)
      val _ = aliceRemoteWallet.acceptAppPaymentRequest(walletPaymentRequest.contractId, coin)

      // Collect payment
      val acceptedPayment = directoryProvider.remoteParticipant.ledger_api.acs
        .await(providerParty, walletCodegen.AcceptedAppPayment)
      val cid = directoryProvider.collectEntryPayment(acceptedPayment.contractId)
      val entry = directoryProvider.remoteParticipant.ledger_api.acs
        .await(providerParty, codegen.DirectoryEntry)
      entry.contractId shouldBe cid

      val entryValue =
        Contract(cid, codegen.DirectoryEntry(userParty.toPrim, providerParty.toPrim, entryName))

      // Read entries from provider
      directoryProvider.listEntries() shouldBe Seq(entryValue)
      directoryProvider.lookupEntryByName(entryName) shouldBe entryValue
      directoryProvider.lookupEntryByParty(userParty) shouldBe entryValue
      assertThrowsAndLogsCommandFailures(
        directoryProvider.lookupEntryByName("nonexistentname"),
        _.errorMessage should include("nonexistentname"),
      )

      // Read entries from user
      aliceDirectory.listEntries() shouldBe Seq(entryValue)
      aliceDirectory.lookupEntryByName(entryName) shouldBe entryValue
      aliceDirectory.lookupEntryByParty(userParty) shouldBe entryValue
      assertThrowsAndLogsCommandFailures(
        aliceDirectory.lookupEntryByName("nonexistentname"),
        _.errorMessage should include("nonexistentname"),
      )
    }
  }
}
