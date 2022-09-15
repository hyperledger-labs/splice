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
      .withSetup(implicit env => {
        directoryValidator.remoteParticipant.dars.upload(directoryDarPath)
        aliceValidator.remoteParticipant.dars.upload(directoryDarPath)
      })

  "Directory service" should {
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

      directoryBackend.listInstallRequests() shouldBe Seq()

      val installRequestCid = aliceDirectory.requestDirectoryInstall()

      directoryBackend.remoteParticipant.ledger_api.acs
        .await(providerParty, codegen.DirectoryInstallRequest)

      val requests = directoryBackend.listInstallRequests()

      requests.map(_.contractId) shouldBe Seq(installRequestCid)

      val installsBefore = directoryBackend.remoteParticipant.ledger_api.acs
        .of_party(providerParty, filterTemplates = Seq(codegen.DirectoryInstall.id))
      installsBefore shouldBe empty

      requests.foreach { case request =>
        directoryBackend.acceptInstallRequest(request.contractId)
      }

      val installsAfter = directoryBackend.remoteParticipant.ledger_api.acs
        .of_party(providerParty, filterTemplates = Seq(codegen.DirectoryInstall.id))
      installsAfter should have length (1)

      directoryBackend.listInstallRequests() shouldBe empty

      // Request entry

      aliceValidator.remoteParticipant.ledger_api.acs.await(userParty, codegen.DirectoryInstall)
      aliceDirectory.requestDirectoryEntry(entryName)
      val entryRequest = directoryBackend.remoteParticipant.ledger_api.acs
        .await(providerParty, codegen.DirectoryEntryRequest)
      val entryRequests = directoryBackend.listEntryRequests()
      entryRequests.map(_.contractId) shouldBe Seq(entryRequest.contractId)

      // Provider: Request payment for entry
      val paymentRequest = directoryBackend.requestEntryPayment(entryRequest.contractId)

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
      aliceRemoteWallet.tap(5.0)
      val _ = aliceRemoteWallet.acceptAppPaymentRequest(walletPaymentRequest.contractId)

      // Collect payment
      val acceptedPayment = directoryBackend.remoteParticipant.ledger_api.acs
        .await(providerParty, walletCodegen.AcceptedAppPayment)
      val cid = directoryBackend.collectEntryPayment(acceptedPayment.contractId)
      val entry = directoryBackend.remoteParticipant.ledger_api.acs
        .await(providerParty, codegen.DirectoryEntry)
      entry.contractId shouldBe cid

      val entryValue =
        Contract(cid, codegen.DirectoryEntry(userParty.toPrim, providerParty.toPrim, entryName))

      // Read entries from provider
      directoryBackend.listEntries() shouldBe Seq(entryValue)
      directoryBackend.lookupEntryByName(entryName) shouldBe entryValue
      directoryBackend.lookupEntryByParty(userParty) shouldBe entryValue
      assertThrowsAndLogsCommandFailures(
        directoryBackend.lookupEntryByName("nonexistentname"),
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
