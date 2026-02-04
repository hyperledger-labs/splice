package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet as amuletCodegen
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.console.ValidatorAppBackendReference
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithSharedEnvironment
import org.lfdecentralizedtrust.splice.util.{SplitwellTestUtil, WalletTestUtil}
import org.lfdecentralizedtrust.splice.wallet.store.{
  BalanceChangeTxLogEntry,
  TxLogEntry as walletLogEntry,
}
import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.topology.PartyId

class WalletTxLogAcsIntegrationTest
    extends IntegrationTestWithSharedEnvironment
    with HasExecutionContext
    with WalletTestUtil
    with SplitwellTestUtil
    with WalletTxLogTestUtil {

  private val amuletPrice = BigDecimal(0.75).setScale(10)

  override def environmentDefinition: EnvironmentDefinition = {
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      // The wallet automation periodically merges amulets, which leads to non-deterministic balance changes.
      // We disable the automation for this suite.
      .withoutAutomaticRewardsCollectionAndAmuletMerging
      // Set a non-unit amulet price to better test CC-USD conversion.
      .addConfigTransform((_, config) => ConfigTransforms.setAmuletPrice(amuletPrice)(config))
      .withManualStart
  }

  "A wallet" should {

    "handle initial ACS" in { implicit env =>
      def amulets(
          validator: ValidatorAppBackendReference,
          party: PartyId,
      ): Seq[amuletCodegen.Amulet] =
        validator.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(amuletCodegen.Amulet.COMPANION)(
            party
          )
          .map(_.data)

      clue("Start all DSO apps except the DSO validator node") {
        val localToStart = env.mergeLocalSpliceInstances(
          env.svs.local,
          env.scans.local,
        )
        localToStart.foreach(_.start())
        localToStart.foreach(_.waitForInitialization())
      }

      // Note: this test uses an SV party, as it's easier to create
      // amulets for parties hosted on an SV participant.
      val sv1UserParty = sv1Backend.getDsoInfo().svParty

      clue("SV1 has no amulets initially") {
        amulets(sv1ValidatorBackend, sv1UserParty) should be(empty)
      }

      val mintAmount = BigDecimal(47.0).bigDecimal.setScale(10)
      actAndCheck(
        "SV1 mints a amulet", {
          tapAmulet(
            sv1ValidatorBackend.participantClientWithAdminToken,
            sv1UserParty,
            mintAmount,
          )
        },
      )(
        "Amulet should appear on the ledger",
        _ => {
          amulets(sv1ValidatorBackend, sv1UserParty).loneElement.amount.initialAmount should be(
            mintAmount
          )
        },
      )

      clue("Start SV1 validator and onboard SV1 as an end user") {
        sv1ValidatorBackend.start()
        sv1ValidatorBackend.waitForInitialization()
        onboardWalletUser(sv1WalletClient, sv1ValidatorBackend)
      }

      checkTxHistory(
        sv1WalletClient,
        Seq(
          { case logEntry: BalanceChangeTxLogEntry =>
            logEntry.subtype.value shouldBe walletLogEntry.BalanceChangeTransactionSubtype.Tap.toProto
            logEntry.amount.bigDecimal shouldBe mintAmount
          }
        ),
        trafficTopups = IgnoreTopupsDevNet,
      )
    }
  }
}
