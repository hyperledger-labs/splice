package org.lfdecentralizedtrust.splice.integration.tests

import com.daml.ledger.javaapi.data.codegen.Choice
import com.digitalasset.canton.admin.api.client.commands.LedgerApiCommands
import com.digitalasset.canton.crypto.{PrivateKey, SigningPrivateKey, SigningPublicKey}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.{HasExecutionContext, HasTempDirectory}
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.transferinstructionv1.{
  TransferFactory,
  TransferInstruction,
}
import org.lfdecentralizedtrust.splice.console.LedgerApiExtensions.RichPartyId
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTestWithSharedEnvironment,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.util.TokenStandardMetadata
import org.lfdecentralizedtrust.tokenstandard.transferinstruction

import java.io.FileOutputStream
import scala.collection.mutable
import scala.sys.process.{Process, ProcessLogger}
import scala.util.Using

// This is an integration test because it requires a live canton & scan to function
@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceAmulet_0_1_9
class TokenStandardCliIntegrationTest
    extends IntegrationTestWithSharedEnvironment
    with TokenStandardTest
    with HasExecutionContext
    with ExternallySignedPartyTestUtil
    with HasTempDirectory {

  "Token Standard CLI" should {

    "execute transfers between external parties" in { implicit env =>
      val onboardingAlice @ OnboardingResult(aliceParty, alicePublicKey, alicePrivateKey) =
        onboardExternalParty(aliceValidatorBackend, Some("aliceExternal"))
      val (alicePublicKeyPath, alicePrivateKeyPath) =
        writeKeysToTempFile("alice", alicePublicKey, alicePrivateKey)

      val onboardingBob @ OnboardingResult(bobParty, bobPublicKey, bobPrivateKey) =
        onboardExternalParty(aliceValidatorBackend, Some("bobExternal"))
      val (bobPublicKeyPath, bobPrivateKeyPath) =
        writeKeysToTempFile("bob", bobPublicKey, bobPrivateKey)

      aliceValidatorWalletClient.tap(5000.0)

      // only alice will have a transfer preapproval
      aliceValidatorBackend.participantClient.parties
        .hosted(filterParty = onboardingAlice.party.filterString) should not be empty

      createAndAcceptExternalPartySetupProposal(
        aliceValidatorBackend,
        onboardingAlice,
        verboseHashing = true,
      )

      eventually() {
        aliceValidatorBackend.lookupTransferPreapprovalByParty(
          onboardingAlice.party
        ) should not be empty
        aliceValidatorBackend.scanProxy.lookupTransferPreapprovalByParty(
          onboardingAlice.party
        ) should not be empty
      }

      // Transfers from non-external parties are not supported by the CLI
      actAndCheck(
        "Transfer 2000.0 to Alice via Token Standard", {
          executeTransferViaTokenStandard(
            aliceValidatorBackend.participantClientWithAdminToken,
            RichPartyId.local(aliceValidatorBackend.getValidatorPartyId()),
            aliceParty,
            BigDecimal(2000.0),
            transferinstruction.v1.definitions.TransferFactoryWithChoiceContext.TransferKind.Direct,
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

      val reason = "Because I'm a very generous Alice"
      val (_, (transferInstructionCid, _)) = actAndCheck(
        "Transfer 10.0 from Alice to Bob using Token Standard CLI", {
          runCommand(
            Seq(
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
              alicePublicKeyPath,
              "--private-key",
              alicePrivateKeyPath,
              "-R",
              s"http://localhost:${sv1ScanBackend.config.adminApi.port.toString}",
              "-l",
              "http://localhost:6501", // not available in any config
              "-a",
              aliceValidatorBackend.participantClientWithAdminToken.adminToken.value,
              "-u",
              "dummyUser", // Doesn't actually matter what we put here as the admin token ignores the user.
              "--reason",
              reason,
            ),
            aliceParty,
            TransferFactory.CHOICE_TransferFactory_Transfer,
          )
        },
      )(
        "Bob sees the transfer instruction",
        _ => {
          val instruction = listTransferInstructions(
            aliceValidatorBackend.participantClientWithAdminToken,
            bobParty,
          ).loneElement
          instruction._2.transfer.meta.values.get(TokenStandardMetadata.reasonMetaKey) should be(
            reason
          )
          instruction
        },
      )

      actAndCheck(
        "Bob accepts the transfer via CLI", {
          runCommand(
            Seq(
              "npm",
              "run",
              "cli",
              "--",
              "accept-transfer-instruction",
              transferInstructionCid.contractId,
              "-p",
              bobParty.toProtoPrimitive,
              "--public-key",
              bobPublicKeyPath,
              "--private-key",
              bobPrivateKeyPath,
              "-R",
              s"http://localhost:${sv1ScanBackend.config.adminApi.port.toString}",
              "-l",
              "http://localhost:6501", // not available in any config
              "-a",
              aliceValidatorBackend.participantClientWithAdminToken.adminToken.value,
              "-u",
              "dummyUser", // Doesn't actually matter what we put here as the admin token ignores the user.
            ),
            bobParty,
            TransferInstruction.CHOICE_TransferInstruction_Accept,
          )
        },
      )(
        "Bob doesn't see the transfer instruction anymore",
        _ => {
          listTransferInstructions(
            aliceValidatorBackend.participantClientWithAdminToken,
            bobParty,
          ) shouldBe empty
        },
      )

      // necessary to call the balance endpoint after
      createAndAcceptExternalPartySetupProposal(
        aliceValidatorBackend,
        onboardingBob,
        verboseHashing = true,
      )
      clue("Bob's balance has been updated") {
        eventually() {
          aliceValidatorBackend
            .getExternalPartyBalance(bobParty)
            .totalUnlockedCoin shouldBe "10.0000000000"
        }
      }
    }

  }

  private def runCommand(
      args: Seq[String],
      checkingPartyId: PartyId,
      expectedChoice: Choice[?, ?, ?],
  )(implicit
      env: SpliceTestConsoleEnvironment
  ) = {
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

    val exitCode = Process(args, cwd).!(logProcessor)

    if (exitCode != 0) {
      logger.error(s"Failed to run $args. Dumping output.")(TraceContext.empty)
      readLines.foreach(logger.error(_)(TraceContext.empty))
      throw new RuntimeException(s"$args failed.")
    }

    val start = readLines.indexWhere(_.startsWith("{"))
    val end = readLines.lastIndexWhere(_.endsWith("}"))
    val jsonSlice = readLines.slice(start, end + 1)
    inside(io.circe.parser.parse(jsonSlice.mkString(""))) { case Right(json) =>
      val output = json
        .as[CommandOutput](io.circe.generic.semiauto.deriveDecoder)
        .valueOrFail(s"Failed to decode output: $json")

      output.status should be("success")
      output.synchronizerId should be(decentralizedSynchronizerId.toProtoPrimitive)

      import com.daml.ledger.api.v2.transaction_filter.*

      val txTree = aliceValidatorBackend.participantClientWithAdminToken.ledger_api.updates
        .update_by_id(
          output.updateId,
          UpdateFormat(
            includeTransactions = Some(
              TransactionFormat(
                eventFormat = Some(
                  EventFormat(filtersByParty =
                    Map(checkingPartyId.toProtoPrimitive -> Filters(Nil))
                  )
                ),
                transactionShape = TransactionShape.TRANSACTION_SHAPE_LEDGER_EFFECTS,
              )
            ),
            includeReassignments = None,
            includeTopologyEvents = None,
          ),
        )
        .valueOrFail(s"No transaction tree found for output: $output")

      inside(txTree) { case LedgerApiCommands.UpdateService.TransactionWrapper(tx) =>
        forExactly(1, tx.events.map(_.event)) {
          case com.daml.ledger.api.v2.event.Event.Event.Exercised(value) =>
            value.choice should be(expectedChoice.name)
          case _ => fail("not an exercised event")
        }
      }
    }
  }

  private def writeKeysToTempFile(
      fileName: String,
      publicKey: SigningPublicKey,
      privateKey: PrivateKey,
  ) = {
    val pubPath = s"${tempDirectory.path}/$fileName.pub"
    val privPath = s"${tempDirectory.path}/$fileName.priv"
    Using(new FileOutputStream(pubPath)) { out =>
      publicKey.key.writeTo(out)
    }
    Using(new FileOutputStream(privPath)) { out =>
      privateKey
        .asInstanceOf[SigningPrivateKey]
        .key
        .writeTo(out)
    }
    (pubPath, privPath)
  }

  case class CommandOutput(
      status: String,
      updateId: String,
      synchronizerId: String,
      recordTime: String,
  )
}
