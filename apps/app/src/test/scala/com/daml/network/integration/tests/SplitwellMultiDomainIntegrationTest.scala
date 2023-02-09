package com.daml.network.integration.tests

import com.daml.network.codegen.java.cn.wallet.payment as walletCodegen
import com.daml.network.splitwell.admin.api.client.commands.GrpcSplitwellAppClient
import com.daml.network.config.CoinConfigTransforms
import com.daml.network.codegen.java.cn.{splitwell as splitwellCodegen}
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTestWithSharedEnvironment,
  CoinTestConsoleEnvironment,
}
import com.daml.network.util.WalletTestUtil
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

class SplitwellMultiDomainIntegrationTest
    extends CoinIntegrationTestWithSharedEnvironment
    with WalletTestUtil {

  private val darPath = "daml/splitwell/.daml/dist/splitwell-0.1.0.dar"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .addConfigTransform((_, config) => CoinConfigTransforms.useSeparateSplitwellDomain()(config))
      .withAdditionalSetup(implicit env => {
        aliceValidator.remoteParticipant.dars.upload(darPath)
        bobValidator.remoteParticipant.dars.upload(darPath)
      })

  "splitwell" should {
    "go through install & payment flow on private domain" in { implicit env =>
      val alice = onboardWalletUser(aliceWallet, aliceValidator)
      val bob = onboardWalletUser(bobWallet, bobValidator)

      aliceWallet.tap(50)

      Seq((aliceSplitwell, aliceValidator, alice), (bobSplitwell, bobValidator, bob)).foreach {
        case (splitwell, validator, party) =>
          splitwell.createInstallRequest()
          val install = splitwell.ledgerApi.ledger_api.acs
            .awaitJava(splitwellCodegen.SplitwellInstall.COMPANION)(party)
          val domains = validator.remoteParticipant.transfer
            .lookup_contract_domain(install.id)
          domains shouldBe Map(
            javaToScalaContractId(install.id) -> "splitwell"
          )
      }

      val groupId = "alice_group"
      val groupKey = GrpcSplitwellAppClient.GroupKey(
        alice,
        aliceSplitwell.getProviderPartyId(),
        groupId,
      )

      actAndCheck("Request group", aliceSplitwell.requestGroup(groupId))(
        "Wait for group to be created",
        _ => aliceSplitwell.listGroups() should have size 1,
      )

      val (invite, _) = actAndCheck(
        "alice creates invite",
        aliceSplitwell.createGroupInvite(
          groupId,
          Seq(alice, bob),
        ),
      )("bob sees invite", _ => bobSplitwell.listGroupInvites() should have size 1)
      val (accepted, _) = actAndCheck("bob accepts invite", bobSplitwell.acceptInvite(invite))(
        "alice sees accepted invite",
        _ => aliceSplitwell.listAcceptedGroupInvites(groupId) should have size 1,
      )
      aliceSplitwell.joinGroup(accepted)
      val (_, paymentRequest) = actAndCheck(
        "alice initiates transfer on private domain",
        aliceSplitwell.initiateTransfer(
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

      val (_, balanceUpdate) = actAndCheck(
        "alice initiates payment accept request on global domain",
        aliceWallet.acceptAppPaymentRequest(paymentRequest.contractId),
      )(
        "alice sees accepted app payment on global domain",
        _ =>
          inside(aliceSplitwell.listBalanceUpdates(groupKey)) { case Seq(update) =>
            update
          },
      )
      val domains = aliceValidator.remoteParticipant.transfer
        .lookup_contract_domain(balanceUpdate.contractId)
      domains shouldBe Map(
        javaToScalaContractId(balanceUpdate.contractId) -> "splitwell"
      )
    }
  }
}
