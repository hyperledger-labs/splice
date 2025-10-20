package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.util.{SvTestUtil, TimeTestUtil, WalletTestUtil}

class TimeBasedTestNetPreviewIntegrationTest
    extends IntegrationTest
    with SvTestUtil
    with TimeTestUtil
    with WalletTestUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4SvsWithSimTime(this.getClass.getSimpleName)
      .addConfigTransform((_, config) => ConfigTransforms.noDevNet(config))

  "TestNet initializes correctly" in { implicit env =>
    clue("DSO contains 4 SV") {
      Seq(sv1Backend, sv2Backend, sv3Backend, sv4Backend).map(
        _.getDsoInfo().dsoRules.payload.svs should have size 4
      )
    }

    val sv1Party = onboardWalletUser(sv1WalletClient, sv1ValidatorBackend)
    val bobParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)

    actAndCheck(
      "Advance round", {
        (1 to 3).foreach { _ =>
          advanceRoundsToNextRoundOpening
          eventually() {
            ensureSvRewardCouponReceivedForCurrentRound(sv1ScanBackend, sv1WalletClient)
          }
        }
      },
    )(
      "Wait for SV rewards for rounds 0&1 to be collected",
      _ => {
        sv1WalletClient.balance().unlockedQty should be > BigDecimal(0)
        forAll(Seq(0, 1)) { round =>
          ensureNoSvRewardCouponExistsForRound(round.toLong, sv1WalletClient)
        }
      },
    )

    val sv1Balance = sv1WalletClient.balance().unlockedQty
    val amountToTransfer = 1
    val feeCeiling = walletUsdToAmulet(smallAmount)

    actAndCheck(
      "Transfer from SV1 wallet to Bob wallet",
      p2pTransfer(sv1WalletClient, bobWalletClient, bobParty, amountToTransfer),
    )(
      "Check balances",
      _ => {
        checkWallet(bobParty, bobWalletClient, Seq((amountToTransfer, amountToTransfer)))
        checkWallet(
          sv1Party,
          sv1WalletClient,
          Seq((sv1Balance - amountToTransfer - feeCeiling, sv1Balance - amountToTransfer)),
        )
      },
    )
  }

}
