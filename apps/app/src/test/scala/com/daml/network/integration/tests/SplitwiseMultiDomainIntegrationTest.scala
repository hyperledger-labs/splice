package com.daml.network.integration.tests

import com.daml.network.codegen.java.cn.wallet.payment as walletCodegen
import com.daml.network.splitwise.admin.api.client.commands.GrpcSplitwiseAppClient
import com.daml.network.config.CoinConfigTransforms
import com.daml.network.codegen.java.cn.{splitwise as splitwiseCodegen}
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTestWithSharedEnvironment,
  CoinTestConsoleEnvironment,
}
import com.daml.network.util.WalletTestUtil
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

class SplitwiseMultiDomainIntegrationTest
    extends CoinIntegrationTestWithSharedEnvironment
    with WalletTestUtil {

  private val darPath = "daml/splitwise/.daml/dist/splitwise-0.1.0.dar"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .addConfigTransform((_, config) => CoinConfigTransforms.useSeparateSplitwiseDomain()(config))
      .withAdditionalSetup(implicit env => {
        aliceValidator.remoteParticipant.dars.upload(darPath)
        bobValidator.remoteParticipant.dars.upload(darPath)
      })

  "splitwise" should {
    "go through install flow on private domain" in { implicit env =>
      val alice = onboardWalletUser(aliceWallet, aliceValidator)
      val bob = onboardWalletUser(bobWallet, bobValidator)

      aliceWallet.tap(50)

      Seq((aliceSplitwise, aliceValidator, alice), (bobSplitwise, bobValidator, bob)).foreach {
        case (splitwise, validator, party) =>
          splitwise.createInstallRequest()
          val install = splitwise.ledgerApi.ledger_api.acs
            .awaitJava(splitwiseCodegen.SplitwiseInstall.COMPANION)(party)
          val domains = validator.remoteParticipant.transfer
            .lookup_contract_domain(install.id)
          domains shouldBe Map(
            javaToScalaContractId(install.id) -> "splitwise"
          )
      }

      val groupId = "alice_group"
      val groupKey = GrpcSplitwiseAppClient.GroupKey(
        alice,
        aliceSplitwise.getProviderPartyId(),
        groupId,
      )

      actAndCheck("Request group", aliceSplitwise.requestGroup(groupId))(
        "Wait for group to be created",
        _ => aliceSplitwise.listGroups() should have size 1,
      )

      val (invite, _) = actAndCheck(
        "alice creates invite",
        aliceSplitwise.createGroupInvite(
          groupId,
          Seq(alice, bob),
        ),
      )("bob sees invite", _ => bobSplitwise.listGroupInvites() should have size 1)
      val (accepted, _) = actAndCheck("bob accepts invite", bobSplitwise.acceptInvite(invite))(
        "alice sees accepted invite",
        _ => aliceSplitwise.listAcceptedGroupInvites(groupId) should have size 1,
      )
      aliceSplitwise.joinGroup(accepted)
      val (_, paymentRequest) = actAndCheck(
        "alice initiates transfer on private domain",
        aliceSplitwise.initiateTransfer(
          groupKey,
          Seq(
            new walletCodegen.ReceiverCCAmount(
              bob.toProtoPrimitive,
              BigDecimal(42.0).bigDecimal,
            )
          ),
        ),
      )(
        "alice sees payment request on global domain",
        _ => aliceWallet.listAppPaymentRequests().headOption.value,
      )

      actAndCheck(
        "alice initiates payment accept request on global domain",
        aliceWallet.acceptAppPaymentRequest(paymentRequest.contractId),
      )(
        "alice sees accepted app payment on global domain",
        _ => aliceWallet.listAcceptedAppPayments() should have size 1,
      )
    }
  }
}
