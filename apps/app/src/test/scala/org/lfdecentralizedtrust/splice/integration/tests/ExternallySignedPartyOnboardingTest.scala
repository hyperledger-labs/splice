package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.environment.EnvironmentImpl
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import com.digitalasset.canton.{HasExecutionContext, HasTempDirectory}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.tracing.TraceContext

import java.util.UUID
import scala.collection.mutable
import scala.sys.process.{Process, ProcessLogger}

class ExternallySignedPartyOnboardingTest
    extends IntegrationTest
    with HasExecutionContext
    with ExternallySignedPartyTestUtil
    with HasTempDirectory {

  override def environmentDefinition
      : BaseEnvironmentDefinition[EnvironmentImpl, SpliceTestConsoleEnvironment] = {
    EnvironmentDefinition.simpleTopology1Sv(this.getClass.getSimpleName)
  }

  "a ccsp provider" should {

    "should be able to onboard a party with externally signed topology transactions" in {
      implicit env =>
        val OnboardingResult(party, _, _) = onboardExternalParty(aliceValidatorBackend)

        eventually() {
          aliceValidatorBackend.participantClient.parties
            .hosted(filterParty = party.filterString) should not be empty
        }
    }

    "should be able to onboard an external party using the test python script" in { implicit env =>
      val partyHint = UUID.randomUUID().toString
      val keyName = "party-key"
      runProcess(
        Seq(
          "python",
          "scripts/external-signing/external-signing.py",
          s"--validator-url=http://localhost:${aliceValidatorBackend.config.adminApi.port}",
          "generate-key-pair",
          s"--key-directory=${tempDirectory.path}",
          s"--key-name=$keyName",
        ),
        aliceValidatorBackend.token.value,
      )
      runProcess(
        Seq(
          "python",
          "scripts/external-signing/external-signing.py",
          s"--validator-url=http://localhost:${aliceValidatorBackend.config.adminApi.port}",
          "setup-party",
          s"--key-directory=${tempDirectory.path}",
          s"--key-name=$keyName",
          s"--party-hint=$partyHint",
        ),
        aliceValidatorBackend.token.value,
      )

      val partyId = aliceValidatorBackend.participantClient.parties
        .hosted(filterParty = partyHint)
        .loneElement
        .party

      // Tap some amulets to pay for purchase of transfer pre-approval
      aliceValidatorWalletClient.tap(50.0)

      runProcess(
        Seq(
          "python",
          "scripts/external-signing/external-signing.py",
          s"--validator-url=http://localhost:${aliceValidatorBackend.config.adminApi.port}",
          "setup-transfer-preapproval",
          s"--key-directory=${tempDirectory.path}",
          s"--key-name=$keyName",
          s"--party-id=${partyId.toProtoPrimitive}",
        ),
        aliceValidatorBackend.token.value,
      )

      aliceValidatorBackend
        .lookupTransferPreapprovalByParty(partyId)
        .value
        .payload
        .receiver shouldBe partyId.toProtoPrimitive

      eventually() {
        sv1ScanBackend
          .lookupTransferPreapprovalByParty(partyId)
          .value
          .payload
          .receiver shouldBe partyId.toProtoPrimitive
      }

      aliceValidatorWalletClient.transferPreapprovalSend(partyId, 40.0, UUID.randomUUID.toString)
      aliceValidatorBackend
        .getExternalPartyBalance(partyId)
        .totalUnlockedCoin shouldBe "40.0000000000"

      val partyHint2 = UUID.randomUUID().toString
      val keyName2 = "party-key-2"
      runProcess(
        Seq(
          "python",
          "scripts/external-signing/external-signing.py",
          s"--validator-url=http://localhost:${aliceValidatorBackend.config.adminApi.port}",
          "generate-key-pair",
          s"--key-directory=${tempDirectory.path}",
          s"--key-name=$keyName2",
        ),
        aliceValidatorBackend.token.value,
      )
      runProcess(
        Seq(
          "python",
          "scripts/external-signing/external-signing.py",
          s"--validator-url=http://localhost:${aliceValidatorBackend.config.adminApi.port}",
          "setup-party",
          s"--key-directory=${tempDirectory.path}",
          s"--key-name=$keyName2",
          s"--party-hint=$partyHint2",
        ),
        aliceValidatorBackend.token.value,
      )

      val partyId2 = aliceValidatorBackend.participantClient.parties
        .hosted(filterParty = partyHint2)
        .loneElement
        .party

      runProcess(
        Seq(
          "python",
          "scripts/external-signing/external-signing.py",
          s"--validator-url=http://localhost:${aliceValidatorBackend.config.adminApi.port}",
          "setup-transfer-preapproval",
          s"--key-directory=${tempDirectory.path}",
          s"--key-name=$keyName2",
          s"--party-id=${partyId2.toProtoPrimitive}",
        ),
        aliceValidatorBackend.token.value,
      )

      eventually() {
        sv1ScanBackend
          .lookupTransferPreapprovalByParty(partyId2)
          .value
          .payload
          .receiver shouldBe partyId2.toProtoPrimitive
      }

      actAndCheck(
        "Prepare and submit transaction to create TransferCommand",
        runProcess(
          Seq(
            "python",
            "scripts/external-signing/external-signing.py",
            s"--validator-url=http://localhost:${aliceValidatorBackend.config.adminApi.port}",
            "transfer-preapproval-send",
            s"--key-directory=${tempDirectory.path}",
            s"--key-name=$keyName",
            s"--sender-party-id=${partyId.toProtoPrimitive}",
            s"--receiver-party-id=${partyId2.toProtoPrimitive}",
            s"--amount=20.0",
            s"--nonce=0",
          ),
          aliceValidatorBackend.token.value,
        ),
      )(
        "DSO automation completes transfer",
        _ =>
          aliceValidatorBackend
            .getExternalPartyBalance(partyId2)
            .totalUnlockedCoin shouldBe "20.0000000000",
      )
    }
  }

  private def runProcess(args: Seq[String], token: String): Unit = {
    val readLines = mutable.Buffer[String]()
    val errorProcessor = ProcessLogger(line => readLines.append(line))
    val exitCode = Process(args, None, ("VALIDATOR_JWT_TOKEN", token)).!(errorProcessor)
    if (exitCode != 0) {
      logger.error(s"Failed to run $args. Dumping output.")(TraceContext.empty)
      readLines.foreach(logger.error(_)(TraceContext.empty))
      throw new RuntimeException(s"$args failed.")
    }
  }
}
