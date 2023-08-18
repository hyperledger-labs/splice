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
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.topology.PartyId
import org.slf4j.event.Level.INFO

import scala.concurrent.{ExecutionContext, Future}
import com.digitalasset.canton.util.FutureInstances.*
import cats.syntax.parallel.*

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
      .withTrafficTopupsEnabled

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
      val entry = loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(INFO))(
        {
          Seq(
            aliceRefs,
            bobRefs,
          ).parTraverse { ref =>
            Future { requestAndPayForEntry(ref, testEntryName) }
          }.futureValue

          eventually() {
            lookupEntryByName(testEntryName).value
          }
        },
        logEntries => {
          forAtLeast(1, logEntries)(logEntry => {
            logEntry.message should (
              include("initial payment collection has been confirmed")
                or include("entry already exists and owned")
                or include("is already created for such entry name")
            )
          })
          forAtLeast(2, logEntries)(logEntry => {
            logEntry.message should include("Completed processing with outcome")
          })
        },
      )

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
  }

  private def requestAndPayForEntry(
      refs: DynamicUserRefs,
      entryName: String,
      entryUrl: String = "https://cns-dir-url.com",
      entryDescription: String = "Sample CNS Entry Description",
  )(implicit env: CNNodeTestConsoleEnvironment) = {
    val svcInfo = sv1Backend.getSvcInfo()
    val svc = svcInfo.svcParty
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
          readAs = Seq(svc),
          update = cmd,
          disclosedContracts = DisclosedContracts(cnsRules).toLedgerApiDisclosedContracts,
        )
        .exerciseResult
        ._2

    // Wait for subscription request to be ingested into store and accept it.
    eventually()(inside(refs.wallet.listSubscriptionRequests()) { case Seq(storeRequest) =>
      storeRequest.subscriptionRequest.contractId shouldBe subscriptionRequest
      refs.wallet.acceptSubscriptionRequest(storeRequest.subscriptionRequest.contractId)
    })
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
