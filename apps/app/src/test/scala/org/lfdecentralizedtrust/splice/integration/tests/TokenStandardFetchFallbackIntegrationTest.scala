package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.transferinstructionv1.TransferInstruction
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.updateAllScanAppConfigs_
import org.lfdecentralizedtrust.splice.http.v0.definitions.TransferInstructionResultOutput.members
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithSharedEnvironment
import org.lfdecentralizedtrust.splice.store.ContractFetcher
import org.lfdecentralizedtrust.splice.util.{TriggerTestUtil, WalletTestUtil}

import java.util.UUID

class TokenStandardFetchFallbackIntegrationTest
    extends IntegrationTestWithSharedEnvironment
    with WalletTestUtil
    with WalletTxLogTestUtil
    with TriggerTestUtil
    with HasActorSystem
    with HasExecutionContext {

  override def environmentDefinition: EnvironmentDefinition = {
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .addConfigTransforms((_, config) =>
        updateAllScanAppConfigs_(scanConfig =>
          scanConfig.copy(parameters =
            scanConfig.parameters.copy(contractFetchLedgerFallbackConfig =
              ContractFetcher.StoreContractFetcherWithLedgerFallbackConfig(
                enabled = true
              )
            )
          )
        )(config)
      )
  }

  "Token Standard Transfers should" should {

    "TransferInstruction context can be fetched from Scan even if it's not yet ingested into the store" in {
      implicit env =>
        pauseScanIngestionWithin(sv1ScanBackend) {
          onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
          val bobUserParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)
          aliceWalletClient.tap(100)

          val response = actAndCheck(
            "Alice creates transfer offer",
            aliceWalletClient.createTokenStandardTransfer(
              bobUserParty,
              10,
              s"Transfer",
              CantonTimestamp.now().plusSeconds(3600L),
              UUID.randomUUID().toString,
            ),
          )(
            "Alice and Bob see it",
            _ => {
              Seq(aliceWalletClient, bobWalletClient).foreach(
                _.listTokenStandardTransfers() should have size 1
              )
            },
          )._1

          val cid = response.output match {
            case members.TransferInstructionPending(value) =>
              new TransferInstruction.ContractId(value.transferInstructionCid)
            case _ => fail("The transfers were expected to be pending.")
          }

          clue("SV-1's Scan sees it (still, even though ingestion is paused)") {
            eventuallySucceeds() {
              sv1ScanBackend.getTransferInstructionAcceptContext(cid)
            }
          }
        }
    }

  }

}
