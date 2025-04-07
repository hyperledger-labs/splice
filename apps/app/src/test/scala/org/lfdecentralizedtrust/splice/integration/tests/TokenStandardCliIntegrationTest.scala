package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.crypto.SigningPrivateKey
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.{HasExecutionContext, HasTempDirectory}

import java.io.FileOutputStream
import scala.collection.mutable
import scala.sys.process.{Process, ProcessLogger}
import scala.util.Using

// This is an integration test because it requires a live canton & scan to function
class TokenStandardCliIntegrationTest
    extends TokenStandardTest
    with HasExecutionContext
    with ExternallySignedPartyTestUtil
    with HasTempDirectory {

  "Token Standard CLI" should {

    "execute transfers between external parties" in { implicit env =>
      val onboardingAlice @ OnboardingResult(aliceParty, alicePublicKey, alicePrivateKey) =
        onboardExternalParty(aliceValidatorBackend, Some("aliceExternal"))
      Using(new FileOutputStream(s"${tempDirectory.path}/alice.pub")) { out =>
        alicePublicKey.key.writeTo(out)
      }
      Using(new FileOutputStream(s"${tempDirectory.path}/alice.priv")) { out =>
        alicePrivateKey
          .asInstanceOf[SigningPrivateKey]
          .key
          .writeTo(out)
      }

      val onboardingBob @ OnboardingResult(bobParty, _, _) =
        onboardExternalParty(aliceValidatorBackend, Some("bobExternal"))

      aliceValidatorWalletClient.tap(5000.0)

      Seq(onboardingAlice, onboardingBob).foreach { onboarding =>
        aliceValidatorBackend.participantClient.parties
          .hosted(filterParty = onboarding.party.filterString) should not be empty

        createAndAcceptExternalPartySetupProposal(
          aliceValidatorBackend,
          onboarding,
          verboseHashing = true,
        )

        eventually() {
          aliceValidatorBackend.lookupTransferPreapprovalByParty(
            onboarding.party
          ) should not be empty
          aliceValidatorBackend.scanProxy.lookupTransferPreapprovalByParty(
            onboarding.party
          ) should not be empty
        }
      }

      // Transfers from non-external parties are not supported by the CLI
      actAndCheck(
        "Transfer 2000.0 to Alice via Token Standard", {
          executeTransferViaTokenStandard(
            aliceValidatorBackend.participantClientWithAdminToken,
            aliceValidatorBackend.getValidatorPartyId(),
            aliceParty,
            BigDecimal(2000.0),
          )
        },
      )(
        "Alice (external party) has received the 2000.0 Amulet",
        _ => {
          aliceValidatorBackend
            .getExternalPartyBalance(aliceParty)
            .totalUnlockedCoin shouldBe "2000.0000000000"
        },
      )

      actAndCheck(
        "Transfer 10.0 from Alice to Bob using Token Standard CLI", {
          val readLines = mutable.Buffer[String]()
          val logProcessor = ProcessLogger { line =>
            {
              logger.debug(s"CLI output: $line")
              readLines.append(line)
            }
          }
          val cwd = new java.io.File("token-standard/cli")
          // npm ci (CI's install) is required for anything to run
          Process(Seq("npm", "ci"), cwd).!(logProcessor)
          val args = Seq(
            "npm",
            "run",
            "cli",
            "--",
            "transfer",
            "-s",
            aliceParty.toProtoPrimitive,
            "-r",
            bobParty.toProtoPrimitive,
            "--amount",
            "10.0",
            "-e",
            dsoParty.toProtoPrimitive,
            "-d",
            "Amulet",
            "--public-key",
            s"${tempDirectory.path}/alice.pub",
            "--private-key",
            s"${tempDirectory.path}/alice.priv",
            "-R",
            s"http://localhost:${sv1ScanBackend.config.adminApi.port.toString}",
            "-l",
            "http://localhost:6201", // not available in any config
            "-a",
            aliceValidatorBackend.participantClientWithAdminToken.adminToken.value,
            "-u",
            "dummyUser", // Doesn't actually matter what we put here as the admin token ignores the user.
          )
          val exitCode = Process(args, cwd).!(logProcessor)
          // TODO (#18610): check that recordtime and updateid are present
          inside(readLines) { case _ :+ last =>
            last should be("{}")
          }
          if (exitCode != 0) {
            logger.error(s"Failed to run $args. Dumping output.")(TraceContext.empty)
            readLines.foreach(logger.error(_)(TraceContext.empty))
            throw new RuntimeException(s"$args failed.")
          }
        },
      )(
        "Bob's has the 10.0",
        _ => {
          aliceValidatorBackend
            .getExternalPartyBalance(bobParty)
            .totalUnlockedCoin shouldBe "10.0000000000"
        },
      )
    }

  }

}
