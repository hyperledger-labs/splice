package com.daml.network.integration.tests

import com.daml.network.codegen.java.cc.coin as coinCodegen
import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.console.ValidatorAppBackendReference
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeIntegrationTestWithSharedEnvironment
import com.daml.network.util.{SplitwellTestUtil, WalletTestUtil}
import com.daml.network.wallet.store.UserWalletTxLogParser.TxLogEntry as walletLogEntry
import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.topology.PartyId

class WalletTxLogAcsIntegrationTest
    extends CNNodeIntegrationTestWithSharedEnvironment
    with HasExecutionContext
    with WalletTestUtil
    with SplitwellTestUtil
    with WalletTxLogTestUtil {

  private val coinPrice = BigDecimal(0.75).setScale(10)

  override def environmentDefinition: CNNodeEnvironmentDefinition = {
    CNNodeEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      // The wallet automation periodically merges coins, which leads to non-deterministic balance changes.
      // We disable the automation for this suite.
      .withoutAutomaticRewardsCollectionAndCoinMerging
      // disable top-ups to prevent extra traffic purchase txs entering the tx log non-deterministically
      // and interfering with the tests in this suite.
      .withTrafficTopupsDisabled
      .addConfigTransformToFront(
        CNNodeConfigTransforms.onlySv1
      )
      // Set a non-unit coin price to better test CC-USD conversion.
      .addConfigTransform((_, config) => CNNodeConfigTransforms.setCoinPrice(coinPrice)(config))
      .withManualStart
  }

  "A wallet" should {

    "handle initial ACS" in { implicit env =>
      def coins(validator: ValidatorAppBackendReference, party: PartyId): Seq[coinCodegen.Coin] =
        validator.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(coinCodegen.Coin.COMPANION)(
            party
          )
          .map(_.data)

      clue("Start all SVC apps except the SVC validator node") {
        val localToStart = env.mergeLocalCNNodeInstances(
          env.svs.local,
          env.scans.local,
          env.directories.local,
        )
        localToStart.foreach(_.start())
        localToStart.foreach(_.waitForInitialization())
      }

      // Note: this test uses an SV party, as it's easier to create
      // coins for parties hosted on a SV participant.
      val sv1UserParty = sv1Backend.getSvcInfo().svParty

      clue("SV1 has no coins initially") {
        coins(sv1ValidatorBackend, sv1UserParty) should be(empty)
      }

      val mintAmount = BigDecimal(47.0).bigDecimal.setScale(10)
      actAndCheck(
        "SV1 mints a coin", {
          tapCoin(
            sv1ValidatorBackend.participantClientWithAdminToken,
            sv1UserParty,
            mintAmount,
          )
        },
      )(
        "Coin should appear on the ledger",
        _ => {
          coins(sv1ValidatorBackend, sv1UserParty).loneElement.amount.initialAmount should be(
            mintAmount
          )
        },
      )

      clue("Start SV1 validator and onboard SV1 as an end user") {
        sv1ValidatorBackend.start()
        sv1ValidatorBackend.waitForInitialization()
        onboardWalletUser(sv1WalletClient, sv1ValidatorBackend)
      }

      // Arbitrary coin price assigned by the user wallet tx log parser
      val unknownCoinPrice = BigDecimal(1)

      checkTxHistory(
        sv1WalletClient,
        Seq(
          { case logEntry: walletLogEntry.BalanceChange =>
            // Entry appears as a mint even though the coin was created with a tap,
            // since we cannot determine how coins in the initial ACS were created.
            logEntry.transactionSubtype shouldBe walletLogEntry.BalanceChange.Mint
            logEntry.amount.bigDecimal shouldBe mintAmount
            logEntry.coinPrice shouldBe unknownCoinPrice
          }
        ),
      )
    }
  }
}
