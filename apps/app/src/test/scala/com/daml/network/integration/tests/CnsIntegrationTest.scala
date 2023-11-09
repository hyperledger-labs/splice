package com.daml.network.integration.tests

import com.daml.network.codegen.java.cn.cns as codegen
import com.daml.network.codegen.java.cn.wallet.subscriptions as subCodegen
import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.console.{ValidatorAppBackendReference, WalletAppClientReference}
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.util.{DisclosedContracts, TriggerTestUtil, WalletTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.topology.PartyId
import com.daml.network.sv.config.InitialCnsConfig

import scala.concurrent.{ExecutionContext, Future}
import com.digitalasset.canton.util.FutureInstances.*
import cats.syntax.parallel.*
import com.daml.network.codegen.java.cn.cns.CnsEntry
import com.daml.network.sv.automation.leaderbased.ExpiredCnsEntryTrigger
import com.daml.network.wallet.automation.SubscriptionReadyForPaymentTrigger
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.logging.SuppressionRule
import org.scalatest.Assertion
import org.slf4j.event.Level

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.jdk.CollectionConverters.*

class CnsIntegrationTest extends CNNodeIntegrationTest with WalletTestUtil with TriggerTestUtil {

  import CnsIntegrationTest.*

  private val testEntryName = "mycoolentry.unverified.cns"
  private val testEntryUrl = "https://cns-dir-url.com"
  private val testEntryDescription = "Sample CNS Directory Entry Description"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .addConfigTransformToFront(
        CNNodeConfigTransforms.onlySv1
      )
      .addConfigTransforms((_, config) =>
        CNNodeConfigTransforms.updateAllAutomationConfigs(
          _.withPausedTrigger[ExpiredCnsEntryTrigger]
        )(config)
      )
      .addConfigTransform((_, config) =>
        // setting the initialCnsEntryLifetime to be the same as initialCnsRenewalDuration
        CNNodeConfigTransforms
          .updateAllSvAppFoundCollectiveConfigs_(
            _.copy(
              initialCnsConfig = InitialCnsConfig(
                renewalDuration = NonNegativeFiniteDuration.ofSeconds(10),
                entryLifetime = NonNegativeFiniteDuration.ofSeconds(10),
              )
            )
          )(config)
      )

  def leaderExpiredCnsEntryTrigger(implicit env: CNNodeTestConsoleEnvironment) =
    sv1Backend.leaderBasedAutomation.trigger[ExpiredCnsEntryTrigger]

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

    "archive expired CNS entries" in { implicit env =>
      setTriggersWithin[Assertion](
        triggersToPauseAtStart = Seq(),
        triggersToResumeAtStart = Seq(leaderExpiredCnsEntryTrigger),
      ) {
        clue("Creating a CNS entry that expires immediately") {
          directoryBackend.listEntries("", 25) shouldBe empty
          directoryBackend.participantClientWithAdminToken.ledger_api_extensions.commands
            .submitJava(
              actAs = Seq(svcParty),
              commands = new CnsEntry(
                svcParty.toProtoPrimitive,
                svcParty.toProtoPrimitive,
                testEntryName,
                testEntryUrl,
                testEntryDescription,
                Instant.now().plus(1, ChronoUnit.SECONDS),
              ).create.commands.asScala.toSeq,
              optTimeout = None,
            )
          eventually()(
            lookupEntryByName(testEntryName) should not be empty
          )
        }
        clue("Waiting for the backend to expire the entry...") {
          eventually()(
            lookupEntryByName(testEntryName) shouldBe empty
          )
        }
      }
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

    "archives terminated CnsEntryContext contracts" in { implicit env =>
      val aliceStaticRefs = StaticUserRefs(aliceValidatorBackend, aliceWalletClient)
      val aliceRefs = setupUser(aliceStaticRefs)
      val (subscriptionRequest, _) = actAndCheck(
        "request directory entry",
        requestEntry(aliceRefs, testEntryName),
      )(
        "alice sees subscription request",
        _ => aliceRefs.wallet.listSubscriptionRequests() should have size 1,
      )
      aliceRefs.validator.participantClientWithAdminToken.ledger_api_extensions.acs
        .filterJava(codegen.CnsEntryContext.COMPANION)(
          aliceRefs.userParty
        ) should have size 1
      actAndCheck(
        "alice rejects subscription request",
        aliceWalletClient.rejectSubscriptionRequest(subscriptionRequest),
      )(
        "DirectoryEntryContext gets archived",
        _ =>
          aliceRefs.validator.participantClientWithAdminToken.ledger_api_extensions.acs
            .filterJava(codegen.CnsEntryContext.COMPANION)(
              aliceRefs.userParty
            ) should have size 0,
      )
    }

    "allocate cns entries following an initial subscription payment and renew entries on follow-up payments" in {
      implicit env =>
        val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
        val aliceUserName = aliceWalletClient.config.ledgerApiUser

        def aliceSubscriptionReadyForPaymentTrigger =
          aliceValidatorBackend
            .userWalletAutomation(aliceUserName)
            .trigger[SubscriptionReadyForPaymentTrigger]

        aliceSubscriptionReadyForPaymentTrigger.pause().futureValue

        val svc = sv1Backend.getSvcInfo().svcParty
        val cnsRules = sv1ScanBackend.getCnsRules()

        val subReqId = clue("Alice requests a cns entry") {
          val cmd = cnsRules.contractId.exerciseCnsRules_RequestEntry(
            testEntryName,
            testEntryUrl,
            testEntryDescription,
            aliceUserParty.toProtoPrimitive,
          )
          aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
            .submitWithResult(
              userId = aliceWalletClient.config.ledgerApiUser,
              actAs = Seq(aliceUserParty),
              readAs = Seq.empty,
              update = cmd,
              disclosedContracts = DisclosedContracts(cnsRules).toLedgerApiDisclosedContracts,
            )
            .exerciseResult
            ._2
        }
        clue("Alice obtains some coins and accepts the subscription") {
          aliceWalletClient.tap(5.0)
          aliceWalletClient.acceptSubscriptionRequest(subReqId)
        }
        val entry = clue("Getting Alice's new entry") {
          eventually()(
            lookupEntryByName(testEntryName).value
          )
        }
        clue("Checking payload of new entry") {
          val expectedPayload = new codegen.CnsEntry(
            aliceUserParty.toProtoPrimitive,
            svc.toProtoPrimitive,
            testEntryName,
            testEntryUrl,
            testEntryDescription,
            entry.data.expiresAt,
          )
          entry.data shouldBe expectedPayload
        }

        aliceSubscriptionReadyForPaymentTrigger.resume()

        val renewedEntry = clue(
          "Eventually, Alice makes a follow-up subscription payment, which the svc collects, renewing her entry."
        ) {
          eventually() {
            val renewed = lookupEntryByName(testEntryName).value
            renewed.id should not equal entry.id
            renewed
          }
        }
        clue("Checking payload of renewed entry") {
          val newEntry = new codegen.CnsEntry(
            entry.data.user,
            entry.data.svc,
            entry.data.name,
            testEntryUrl,
            testEntryDescription,
            entry.data.expiresAt.plus(10, ChronoUnit.SECONDS),
          )
          renewedEntry.data shouldBe newEntry
        }

    }

    "expire stale subscriptions" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val aliceUserName = aliceWalletClient.config.ledgerApiUser

      def aliceSubscriptionReadyForPaymentTrigger =
        aliceValidatorBackend
          .userWalletAutomation(aliceUserName)
          .trigger[SubscriptionReadyForPaymentTrigger]

      setTriggersWithin[Assertion](
        triggersToPauseAtStart = Seq(aliceSubscriptionReadyForPaymentTrigger),
        triggersToResumeAtStart = Seq(leaderExpiredCnsEntryTrigger),
      ) {

        val cnsRules = sv1ScanBackend.getCnsRules()

        val subReqId = clue("Alice requests a cns entry") {
          val cmd = cnsRules.contractId.exerciseCnsRules_RequestEntry(
            testEntryName,
            testEntryUrl,
            testEntryDescription,
            aliceUserParty.toProtoPrimitive,
          )
          aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
            .submitWithResult(
              userId = aliceWalletClient.config.ledgerApiUser,
              actAs = Seq(aliceUserParty),
              readAs = Seq.empty,
              update = cmd,
              disclosedContracts = DisclosedContracts(cnsRules).toLedgerApiDisclosedContracts,
            )
            .exerciseResult
            ._2
        }

        aliceWalletClient.tap(50.0)
        actAndCheck(
          "Alice accepts subscription and waits for entry", {
            aliceWalletClient.acceptSubscriptionRequest(subReqId)
          },
        )(
          "Subscription and entry are created",
          _ => {
            aliceWalletClient.listSubscriptions() should have length 1
            inside(lookupEntryByName(testEntryName)) { case Some(entry) =>
              entry.data.name shouldBe testEntryName
            }
          },
        )

        eventually() {
          lookupEntryByName(testEntryName) shouldBe empty
        }
        // Wait for subscription to be expired.
        eventually() {
          aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
            .filterJava(subCodegen.Subscription.COMPANION)(aliceUserParty) shouldBe empty
          aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
            .filterJava(subCodegen.SubscriptionIdleState.COMPANION)(aliceUserParty) shouldBe empty
          aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
            .filterJava(codegen.CnsEntryContext.COMPANION)(aliceUserParty) shouldBe empty
        }
      }
    }
  }

  private def requestEntry(
      refs: DynamicUserRefs,
      entryName: String,
      entryUrl: String = "https://cns-dir-url.com",
      entryDescription: String = "Sample CNS Entry Description",
  )(implicit env: CNNodeTestConsoleEnvironment) = {
    val cnsRules = sv1ScanBackend.getCnsRules()

    val cmd = cnsRules.contractId.exerciseCnsRules_RequestEntry(
      entryName,
      entryUrl,
      entryDescription,
      refs.userParty.toProtoPrimitive,
    )
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
  }

  private def requestAndPayForEntry(
      refs: DynamicUserRefs,
      entryName: String,
      entryUrl: String = "https://cns-dir-url.com",
      entryDescription: String = "Sample CNS Entry Description",
  )(implicit env: CNNodeTestConsoleEnvironment) = {
    // for paying the cns entry initial payment.
    refs.wallet.tap(5.0)

    val subscriptionRequest = requestEntry(refs, entryName, entryUrl, entryDescription)

    actAndCheck(
      s"Wait for subscription request to be ingested into store and accept it.",
      eventually() {
        inside(refs.wallet.listSubscriptionRequests()) { case Seq(storeRequest) =>
          storeRequest.contractId shouldBe subscriptionRequest
          refs.wallet.acceptSubscriptionRequest(storeRequest.contractId)

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
