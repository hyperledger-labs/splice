package com.daml.network.integration.tests

import com.daml.network.codegen.java.cn.cns as codegen
import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.console.{ValidatorAppBackendReference, WalletAppClientReference}
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.util.{DisclosedContracts, WalletTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.topology.PartyId

import scala.concurrent.{ExecutionContext, Future}
import com.digitalasset.canton.util.FutureInstances.*
import cats.syntax.parallel.*
import com.digitalasset.canton.logging.SuppressionRule
import org.slf4j.event.Level

class CnsIntegrationTest extends CNNodeIntegrationTest with WalletTestUtil {

  import CnsIntegrationTest.*

  private val cnsDarPath = "daml/canton-name-service/.daml/dist/canton-name-service-0.1.0.dar"

  private val testEntryName = "mycoolentry.unverified.cns"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .addConfigTransformToFront(
        CNNodeConfigTransforms.onlySv1
      )
      // TODO(#7353): we will not upload the dars here after we have switch to upload dars in validator.
      .withAdditionalSetup(implicit env => {
        aliceValidatorBackend.participantClient.upload_dar_unless_exists(cnsDarPath)
        bobValidatorBackend.participantClient.upload_dar_unless_exists(cnsDarPath)
      })

  "cns" should {
    "allocate unique cns entries, even when multiple parties race for them" in { implicit env =>
      implicit val ec: ExecutionContext = env.executionContext
      val svc = sv1Backend.getSvcInfo().svcParty

      // Setup alice
      val aliceStaticRefs =
        StaticUserRefs(aliceValidatorBackend, aliceWalletClient)
      val aliceRefs = setupUser(aliceStaticRefs)

      // Setup bob
      val bobStaticRefs = StaticUserRefs(bobValidatorBackend, bobWalletClient)
      val bobRefs = setupUser(bobStaticRefs)

      // Concurrently, request an entry as alice and bob
      loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.WARN))(
        Seq(
          aliceRefs,
          bobRefs,
        ).parTraverse { ref =>
          Future { requestAndPayForEntry(ref, testEntryName) }
        }.futureValue,
        lines => {
          forAll(lines) { line =>
            line.message should (include(s"entry already exists and owned by") or include(
              s"other initial payment collection has been confirmed for the same cns name"
            ))
          }
        },
      )

      val entry = eventually() {
        lookupEntryByName(testEntryName).value
      }

      val winnerUserParty = PartyId.tryFromProtoPrimitive(entry.data.user)

      entry.data shouldBe new codegen.CnsEntry(
        winnerUserParty.toProtoPrimitive,
        svc.toProtoPrimitive,
        testEntryName,
        entry.data.url,
        entry.data.description,
        entry.data.expiresAt,
      )
    }

    "reject invalid entry names" in { implicit env =>
      val aliceStaticRefs =
        StaticUserRefs(aliceValidatorBackend, aliceWalletClient)
      val aliceRefs = setupUser(aliceStaticRefs)

      clue("invalid entries(bad names) are rejected") {
        val invalidNames =
          Seq("alice.company.unverified.cns", "alice$company.unverified.cns", "alice.cns")
        invalidNames.foreach { name =>
          loggerFactory.assertLogsSeq(SuppressionRule.Level(Level.WARN))(
            {
              requestAndPayForEntry(aliceRefs, name)
            },
            lines => {
              forAll(lines) { line =>
                line.message should include(s"entry name ($name) is not valid")
              }
            },
          )
        }
      }
    }

    "reject invalid entry urls" in { implicit env =>
      val aliceStaticRefs =
        StaticUserRefs(aliceValidatorBackend, aliceWalletClient)
      val aliceRefs = setupUser(aliceStaticRefs)

      clue("invalid entries(bad urls) are rejected") {
        val invalidUrls =
          Seq("s3://alice.arn.cns", "http://asdklfjh%skldjfgh", s"https://${"alice-" * 50}.cns.com")
        invalidUrls.foreach { url =>
          loggerFactory.assertLogsSeq(SuppressionRule.Level(Level.WARN))(
            {
              requestAndPayForEntry(aliceRefs, "alice.unverified.cns", entryUrl = url)
            },
            lines => {
              forAll(lines) { line =>
                line.message should include(s"entry url ($url) is not valid")
              }
            },
          )
        }
      }
    }

    "reject invalid entry descriptions" in { implicit env =>
      val aliceStaticRefs =
        StaticUserRefs(aliceValidatorBackend, aliceWalletClient)
      val aliceRefs = setupUser(aliceStaticRefs)

      clue("invalid entries(bad descriptions) are rejected") {
        val invalidDescriptions = Seq("Sample CNS Directory Entry Description -" * 50)
        invalidDescriptions.foreach { desc =>
          loggerFactory.assertLogsSeq(SuppressionRule.Level(Level.WARN))(
            {
              requestAndPayForEntry(aliceRefs, "alice.unverified.cns", entryDescription = desc)
            },
            lines => {
              forAll(lines) { line =>
                line.message should include(s"entry description ($desc) is not valid")
              }
            },
          )
        }
      }
    }
  }

  private def requestAndPayForEntry(
      refs: DynamicUserRefs,
      entryName: String,
      entryUrl: String = "https://cns-dir-url.com",
      entryDescription: String = "Sample CNS Entry Description",
  )(implicit env: CNNodeTestConsoleEnvironment) = {
    val cnsRules = sv1ScanBackend.getCnsRules()

    // for paying the cns entry initial payment.
    refs.wallet.tap(5.0)

    // Request entry and get some money to pay for it
    val cmd = cnsRules.contractId.exerciseCnsRules_RequestEntry(
      entryName,
      entryUrl,
      entryDescription,
      refs.userParty.toProtoPrimitive,
    )
    val subscriptionRequest =
      refs.validator.participantClientWithAdminToken.ledger_api_extensions.commands
        .submitWithResult(
          userId = refs.validator.config.ledgerApiUser,
          actAs = Seq(refs.userParty),
          readAs = Seq.empty,
          update = cmd,
          disclosedContracts = DisclosedContracts(cnsRules).toLedgerApiDisclosedContracts,
        )
        .exerciseResult
        ._2

    actAndCheck(
      s"Wait for subscription request to be ingested into store and accept it.",
      eventually() {
        inside(refs.wallet.listSubscriptionRequests()) { case Seq(storeRequest) =>
          storeRequest.subscriptionRequest.contractId shouldBe subscriptionRequest
          refs.wallet.acceptSubscriptionRequest(storeRequest.subscriptionRequest.contractId)

        }
      },
    )(
      s" Wait for the payment to be accepted or rejected.",
      _ => refs.wallet.listSubscriptionInitialPayments() shouldBe empty,
    )
  }

  private def lookupEntryByName(
      name: String
  )(implicit env: CNNodeTestConsoleEnvironment): Option[codegen.CnsEntry.Contract] = {
    val svc = sv1Backend.getSvcInfo().svcParty
    sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
      .filterJava(codegen.CnsEntry.COMPANION)(
        svc,
        (co: codegen.CnsEntry.Contract) => co.data.name == name,
      )
      .headOption
  }

  private def setupUser(refs: StaticUserRefs): DynamicUserRefs = {
    val userParty = onboardWalletUser(refs.wallet, refs.validator)
    DynamicUserRefs(userParty, refs)
  }
}

object CnsIntegrationTest {
  case class StaticUserRefs(
      validator: ValidatorAppBackendReference,
      wallet: WalletAppClientReference,
  )

  case class DynamicUserRefs(userParty: PartyId, static: StaticUserRefs) {
    def validator: ValidatorAppBackendReference = static.validator

    def wallet: WalletAppClientReference = static.wallet
  }
}
