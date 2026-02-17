package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.concurrent.Threading
import scala.jdk.CollectionConverters.*
import com.digitalasset.canton.data.CantonTimestamp
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithSharedEnvironment
import org.lfdecentralizedtrust.splice.util.{
  DisclosedContracts,
  SplitwellTestUtil,
  SvTestUtil,
  TriggerTestUtil,
  WalletTestUtil,
}
import com.digitalasset.canton.HasExecutionContext

// This test just exists to trigger an OwnerExpireLock exercise
// to test the scan tx log script as we don't have automation that triggers this atm.
class ScanTxLogOwnerExpireLockIntegrationTest
    extends IntegrationTestWithSharedEnvironment
    with HasExecutionContext
    with WalletTestUtil
    with SplitwellTestUtil
    with WalletTxLogTestUtil
    with TriggerTestUtil
    with SvTestUtil {

  override def environmentDefinition: EnvironmentDefinition = {
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
  }

  "scan tx log" should {
    "handle owner expire lock" in { implicit env =>
      val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val aliceValidatorParty = aliceValidatorBackend.getValidatorPartyId()

      actAndCheck(
        "Tap to get some amulet",
        aliceWalletClient.tap(100),
      )(
        "Wait for amulet to appear",
        _ => aliceWalletClient.list().amulets should have size (1),
      )

      val (_, locked) = actAndCheck(
        "Lock a small amulet",
        lockAmulets(
          aliceValidatorBackend,
          aliceParty,
          aliceValidatorParty,
          aliceWalletClient.list().amulets,
          BigDecimal(0.000005),
          sv1ScanBackend,
          java.time.Duration.ofSeconds(5),
          CantonTimestamp.now(),
        ),
      )(
        "Wait for locked amulet to appear",
        _ => aliceWalletClient.list().lockedAmulets.loneElement,
      )
      Threading.sleep(5000)
      val openRound = sv1ScanBackend.getLatestOpenMiningRound(CantonTimestamp.now())
      aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
        .submitJava(
          Seq(aliceParty),
          commands = locked.contract.contractId
            .exerciseLockedAmulet_OwnerExpireLockV2(
            )
            .commands()
            .asScala
            .toSeq,
          disclosedContracts = DisclosedContracts
            .forTesting(
              openRound
            )
            .toLedgerApiDisclosedContracts,
        )
    }
  }
}
