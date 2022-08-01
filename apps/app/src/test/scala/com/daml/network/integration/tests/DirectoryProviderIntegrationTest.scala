package com.daml.network.integration.tests

import com.daml.ledger.api.refinements.ApiTypes
import com.daml.network.console.{LocalValidatorAppReference, LocalWalletAppReference}
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
  IsolatedCoinEnvironments,
}
import com.daml.network.util.{CommonCoinAppInstanceReferences, Contract}
import com.digitalasset.canton.console.CommandFailure
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.network.{CC => coinCodegen}
import com.digitalasset.network.CN.{Directory => codegen, Wallet => walletCodegen}
import com.digitalasset.network.DA

class DirectoryProviderIntegrationTest
    extends CoinIntegrationTest
    with IsolatedCoinEnvironments
    with CommonCoinAppInstanceReferences {

  private val quantity = 100d
  private val directoryDarPath = "apps/directory-provider/daml/.daml/dist/directory-service.dar"
  private val entryName = "mycoolentry"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition.simpleTopology.withSetup(env => {
      import env._
      participants.all.map(_.dars.upload(directoryDarPath))
      participants.all.foreach(_.domains.connect_local(da))
    })

  "A directory provider" should {
    "list and accept install requests" in { implicit env =>
      import env._

      svc.initialize()
      val svcParty =
        svc.remoteParticipant.parties.list(filterParty = "svc").headOption.value.party
      // The validator operator of the user of the directory service.
      val userValidatorParty = validator1.initialize(svcParty)
      // The provider of the directory service
      val providerParty = directoryValidator.initialize(svcParty)
      // The user of the directory service.
      val userParty = validator1.onboardUser("god")

      wallet1.initialize(svcParty, userValidatorParty)
      wallet1.remoteParticipant.ledger_api.acs
        .await(userValidatorParty, coinCodegen.CoinRules.CoinRules)

      // Setup DirectoryInstall

      directoryProvider.listInstallRequests() shouldBe Seq()

      val installRequestCid = directoryUser.requestDirectoryInstall()

      directoryProvider.remoteParticipant.ledger_api.acs
        .await(providerParty, codegen.DirectoryInstallRequest)

      val requests = directoryProvider.listInstallRequests()

      requests.map(_.contractId) shouldBe Seq(installRequestCid)

      val installsBefore = directoryProvider.remoteParticipant.ledger_api.acs
        .of_party(providerParty, filterTemplates = Seq(codegen.DirectoryInstall.id))
      installsBefore shouldBe empty

      requests.foreach { case request =>
        directoryProvider.acceptInstallRequest(request.contractId, svcParty)
      }

      val installsAfter = directoryProvider.remoteParticipant.ledger_api.acs
        .of_party(providerParty, filterTemplates = Seq(codegen.DirectoryInstall.id))
      installsAfter should have length (1)

      directoryProvider.listInstallRequests() shouldBe empty

      // Request entry

      directoryUser.remoteParticipant.ledger_api.acs.await(userParty, codegen.DirectoryInstall)
      directoryUser.requestDirectoryEntry(entryName)
      val entryRequest = directoryProvider.remoteParticipant.ledger_api.acs
        .await(providerParty, codegen.DirectoryEntryRequest)
      val entryRequests = directoryProvider.listEntryRequests()
      entryRequests.map(_.contractId) shouldBe Seq(entryRequest.contractId)

      // Request payment for entry

      val paymentRequest = directoryProvider.requestEntryPayment(entryRequest.contractId)
      val _ =
        wallet1.remoteParticipant.ledger_api.acs
          .await(userParty, walletCodegen.PaymentRequest.PaymentRequest)

      // Approve payment

      val coin = wallet1.tap("5.0")

      wallet1.remoteParticipant.ledger_api.commands.submit_flat(
        // We only need readAs for userValidatorParty but the API only supports actAs.
        actAs = Seq(userParty, userValidatorParty),
        commands = Seq(
          paymentRequest
            .exercisePaymentRequest_Approve(
              userParty.toPrim,
              walletCodegen.PaymentRequest
                .PaymentRequest_Approve(Seq(coinCodegen.CoinRules.TransferInput.InputCoin(coin))),
            )
            .command
        ),
        optTimeout = None,
      )

      // Collect payment
      val approvedPayment = directoryProvider.remoteParticipant.ledger_api.acs
        .await(providerParty, walletCodegen.PaymentRequest.ApprovedPayment)
      val cid = directoryProvider.collectEntryPayment(approvedPayment.contractId)
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
      directoryUser.listEntries() shouldBe Seq(entryValue)
      directoryUser.lookupEntryByName(entryName) shouldBe entryValue
      directoryUser.lookupEntryByParty(userParty) shouldBe entryValue
      assertThrowsAndLogsCommandFailures(
        directoryUser.lookupEntryByName("nonexistentname"),
        _.errorMessage should include("nonexistentname"),
      )
    }
  }
}
