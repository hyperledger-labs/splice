package com.daml.network.integration.tests

import com.daml.network.codegen.java.splice.ans as codegen
import com.daml.network.codegen.java.splice.wallet.subscriptions as subCodegen
import com.daml.network.config.CNNodeConfigTransforms
import CNNodeConfigTransforms.{ConfigurableApp, updateAutomationConfig}
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.util.{DisclosedContracts, TriggerTestUtil, WalletTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.daml.network.sv.config.InitialAnsConfig

import scala.concurrent.{ExecutionContext, Future}
import com.digitalasset.canton.util.FutureInstances.*
import cats.syntax.parallel.*
import com.daml.network.automation.Trigger
import com.daml.network.http.v0.definitions
import com.daml.network.scan.dso.DsoAnsResolver
import com.daml.network.sv.automation.leaderbased.{
  ExpiredAnsEntryTrigger,
  ExpiredAnsSubscriptionTrigger,
}
import com.daml.network.wallet.automation.SubscriptionReadyForPaymentTrigger
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.topology.PartyId
import org.scalatest.Assertion
import org.slf4j.event.Level

import java.time.{Instant, ZoneOffset}
import java.time.temporal.ChronoUnit
import scala.jdk.CollectionConverters.*

class AnsIntegrationTest extends CNNodeIntegrationTest with WalletTestUtil with TriggerTestUtil {

  import WalletTestUtil.*

  private val testEntryName = "mycoolentry.unverified.cns"
  private val testEntryUrl = "https://ans-dir-url.com"
  private val testEntryDescription = "Sample CNS Entry Description"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Sv)(
          _.withPausedTrigger[ExpiredAnsEntryTrigger]
        )(config)
      )
      .addConfigTransform((_, config) =>
        // setting the initialAnsEntryLifetime to be the same as initialAnsRenewalDuration
        CNNodeConfigTransforms
          .updateAllSvAppFoundCollectiveConfigs_(
            _.copy(
              initialAnsConfig = InitialAnsConfig(
                renewalDuration = NonNegativeFiniteDuration.ofSeconds(10),
                entryLifetime = NonNegativeFiniteDuration.ofSeconds(10),
              )
            )
          )(config)
      )

  def leaderExpiredAnsEntryTrigger(implicit env: CNNodeTestConsoleEnvironment) =
    sv1Backend.leaderBasedAutomation.trigger[ExpiredAnsEntryTrigger]

  "ans" should {
    "allocate unique ans entries, even when multiple parties race for them" in { implicit env =>
      implicit val ec: ExecutionContext = env.executionContext
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
              s"other initial payment collection has been confirmed for the same ans name"
            ))
          }
        },
      )

      val entry = eventually() {
        lookupEntryByName(testEntryName).value
      }

      entry.name shouldBe testEntryName
    }

    "archive expired CNS entries" in { implicit env =>
      setTriggersWithin[Assertion](
        triggersToPauseAtStart = Seq(),
        triggersToResumeAtStart = Seq(leaderExpiredAnsEntryTrigger),
      ) {
        clue("Creating a CNS entry that expires immediately") {
          clue("no user entries is created") {
            val userEntries = sv1ScanBackend
              .listEntries("", 25)
              .filter(entry =>
                !entry.name.endsWith(
                  DsoAnsResolver.svAnsNameSuffix
                ) && entry.name != DsoAnsResolver.dsoAnsName
              )
            userEntries shouldBe empty
          }
          sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands
            .submitJava(
              actAs = Seq(dsoParty),
              commands = new codegen.AnsEntry(
                dsoParty.toProtoPrimitive,
                dsoParty.toProtoPrimitive,
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
          Seq("alice.company.unverified.cns", "alice$company.unverified.cns", "alice.ans")
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
          Seq("s3://alice.arn.ans", "http://asdklfjh%skldjfgh", s"https://${"alice-" * 50}.ans.com")
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
        val invalidDescriptions = Seq("Sample CNS Entry Description -" * 50)
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

    "archive terminated AnsEntryContext contracts" in { implicit env =>
      val aliceStaticRefs = StaticUserRefs(aliceValidatorBackend, aliceWalletClient)
      val aliceRefs = setupUser(aliceStaticRefs)
      val (subscriptionRequest, _) = actAndCheck(
        "request CNS entry",
        requestEntry(aliceRefs, testEntryName),
      )(
        "alice sees subscription request",
        _ => aliceRefs.wallet.listSubscriptionRequests() should have size 1,
      )
      aliceRefs.validator.participantClientWithAdminToken.ledger_api_extensions.acs
        .filterJava(codegen.AnsEntryContext.COMPANION)(
          aliceRefs.userParty
        ) should have size 1
      actAndCheck(
        "alice rejects subscription request",
        aliceWalletClient.rejectSubscriptionRequest(subscriptionRequest),
      )(
        "AnsEntryContext gets archived",
        _ =>
          aliceRefs.validator.participantClientWithAdminToken.ledger_api_extensions.acs
            .filterJava(codegen.AnsEntryContext.COMPANION)(
              aliceRefs.userParty
            ) should have size 0,
      )
    }

    "allocate ans entries following an initial subscription payment and renew entries on follow-up payments" in {
      implicit env =>
        val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
        val aliceUserName = aliceWalletClient.config.ledgerApiUser

        def aliceSubscriptionReadyForPaymentTrigger =
          aliceValidatorBackend
            .userWalletAutomation(aliceUserName)
            .trigger[SubscriptionReadyForPaymentTrigger]

        aliceSubscriptionReadyForPaymentTrigger.pause().futureValue

        val ansRules = sv1ScanBackend.getAnsRules()

        val subReqId = clue("Alice requests a ans entry") {
          val cmd = ansRules.contractId.exerciseAnsRules_RequestEntry(
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
              disclosedContracts = DisclosedContracts(ansRules).toLedgerApiDisclosedContracts,
            )
            .exerciseResult
            .requestCid
        }
        clue("Alice obtains some amulets and accepts the subscription") {
          aliceWalletClient.tap(5.0)
          aliceWalletClient.acceptSubscriptionRequest(subReqId)
        }
        val entry = clue("Getting Alice's new entry") {
          eventually()(
            lookupEntryByName(testEntryName).value
          )
        }
        clue("Checking payload of new entry") {
          entry.contractId should not be empty
          entry.user shouldBe aliceUserParty.toProtoPrimitive
          entry.name shouldBe testEntryName
          entry.url shouldBe testEntryUrl
          entry.description shouldBe testEntryDescription
          entry.expiresAt should not be empty
        }

        aliceSubscriptionReadyForPaymentTrigger.resume()

        val renewedEntry = clue(
          "Eventually, Alice makes a follow-up subscription payment, which the DSO collects, renewing her entry."
        ) {
          eventually() {
            val renewed = lookupEntryByName(testEntryName).value
            renewed.contractId.value should not equal entry.contractId.value
            renewed
          }
        }
        clue("Checking payload of renewed entry") {
          renewedEntry.user shouldBe aliceUserParty.toProtoPrimitive
          renewedEntry.name shouldBe testEntryName
          renewedEntry.url shouldBe testEntryUrl
          renewedEntry.description shouldBe testEntryDescription
          renewedEntry.expiresAt.value shouldBe entry.expiresAt.value.plus(10, ChronoUnit.SECONDS)
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
        triggersToResumeAtStart = Seq[Trigger](
          leaderExpiredAnsEntryTrigger,
          sv1Backend.leaderBasedAutomation.trigger[ExpiredAnsSubscriptionTrigger],
        ),
      ) {

        val ansRules = sv1ScanBackend.getAnsRules()

        val subReqId = clue("Alice requests a ans entry") {
          val cmd = ansRules.contractId.exerciseAnsRules_RequestEntry(
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
              disclosedContracts = DisclosedContracts(ansRules).toLedgerApiDisclosedContracts,
            )
            .exerciseResult
            .requestCid
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
              entry.name shouldBe testEntryName
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
            .filterJava(codegen.AnsEntryContext.COMPANION)(aliceUserParty) shouldBe empty
        }
      }
    }

    "the DSO party's CNS entry can be seen via scan api" in { implicit env =>
      val expectedDsoEntry = definitions.AnsEntry(
        None,
        dsoParty.toProtoPrimitive,
        DsoAnsResolver.dsoAnsName,
        "",
        "",
        None,
      )

      sv1ScanBackend.lookupEntryByName(DsoAnsResolver.dsoAnsName) shouldBe expectedDsoEntry
      sv1ScanBackend.lookupEntryByParty(dsoParty).value shouldBe expectedDsoEntry
      sv1ScanBackend.listEntries("", 100) should contain(expectedDsoEntry)
    }

    "na SV's CNS entry can be seen via scan api" in { implicit env =>
      val dsoRules = sv1Backend.getDsoInfo().dsoRules
      dsoRules.payload.svs.asScala.foreach { case (svParty, svInfo) =>
        val expectedSvEntry = svEntry(svInfo.name, svParty)
        sv1ScanBackend.lookupEntryByName(
          s"${svInfo.name.toLowerCase}${DsoAnsResolver.svAnsNameSuffix}"
        ) shouldBe expectedSvEntry
        sv1ScanBackend
          .lookupEntryByParty(PartyId.tryFromProtoPrimitive(svParty))
          .value shouldBe expectedSvEntry
        sv1ScanBackend.listEntries("", 100) should contain(expectedSvEntry)
      }
    }
  }

  private def svEntry(svName: String, svParty: String) =
    definitions.AnsEntry(
      None,
      svParty,
      s"${svName.toLowerCase}${DsoAnsResolver.svAnsNameSuffix}",
      "",
      "",
      None,
    )

  private def lookupEntryByName(
      name: String
  )(implicit env: CNNodeTestConsoleEnvironment): Option[definitions.AnsEntry] = {
    val dso = sv1Backend.getDsoInfo().dsoParty
    sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
      .filterJava(codegen.AnsEntry.COMPANION)(
        dso,
        (co: codegen.AnsEntry.Contract) => co.data.name == name,
      )
      .headOption
      .map(entry =>
        definitions.AnsEntry(
          Some(entry.id.contractId),
          entry.data.user,
          entry.data.name,
          entry.data.url,
          entry.data.description,
          Some(
            java.time.OffsetDateTime
              .ofInstant(entry.data.expiresAt, ZoneOffset.UTC)
          ),
        )
      )
  }
}
