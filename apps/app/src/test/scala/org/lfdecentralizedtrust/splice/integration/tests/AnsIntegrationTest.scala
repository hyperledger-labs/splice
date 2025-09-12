package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.codegen.java.splice.ans as codegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.subscriptions as subCodegen
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import ConfigTransforms.{ConfigurableApp, updateAutomationConfig}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.util.{DisclosedContracts, TriggerTestUtil, WalletTestUtil}
import org.lfdecentralizedtrust.splice.sv.config.InitialAnsConfig

import scala.concurrent.{ExecutionContext, Future}
import com.digitalasset.canton.util.FutureInstances.*
import cats.syntax.parallel.*
import com.daml.ledger.javaapi.data.Identifier
import org.lfdecentralizedtrust.splice.http.v0.definitions
import org.lfdecentralizedtrust.splice.scan.dso.DsoAnsResolver
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.{
  ExpiredAnsEntryTrigger,
  ExpiredAnsSubscriptionTrigger,
}
import org.lfdecentralizedtrust.splice.wallet.automation.SubscriptionReadyForPaymentTrigger
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.topology.PartyId
import org.scalatest.Assertion
import org.scalatest.concurrent.PatienceConfiguration
import org.slf4j.event.Level

import java.time.{Instant, OffsetDateTime, ZoneOffset}
import java.time.temporal.ChronoUnit
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

class AnsIntegrationTest extends IntegrationTest with WalletTestUtil with TriggerTestUtil {

  import WalletTestUtil.*

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      // TODO(#787): make AnsIntegrationTest use simpleTopology4Svs
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Sv)(
          _.withPausedTrigger[ExpiredAnsEntryTrigger]
        )(config)
      )
      .addConfigTransform((_, config) =>
        // setting the initialAnsEntryLifetime to be the same as initialAnsRenewalDuration
        ConfigTransforms
          .updateAllSvAppFoundDsoConfigs_(
            _.copy(
              initialAnsConfig = InitialAnsConfig(
                renewalDuration = NonNegativeFiniteDuration.ofSeconds(10),
                entryLifetime = NonNegativeFiniteDuration.ofSeconds(10),
              )
            )
          )(config)
      )

  def dsoDelegateExpiredAnsEntryTriggers(implicit env: SpliceTestConsoleEnvironment) =
    activeSvs.map(_.dsoDelegateBasedAutomation.trigger[ExpiredAnsEntryTrigger])

  // created by the expiry test
  override protected lazy val sanityChecksIgnoredRootCreates: Seq[Identifier] = Seq(
    codegen.AnsEntry.TEMPLATE_ID_WITH_PACKAGE_ID
  )

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
        }.futureValue(timeout = PatienceConfiguration.Timeout(FiniteDuration(40, "seconds"))),
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

    "register an entry despite there is an expired ANS entry with the same name" in {
      implicit env =>
        clue("no user entries is created") {
          val userEntries = sv1ScanBackend
            .listEntries("", 25)
            .filter(entry =>
              !entry.name.endsWith(
                DsoAnsResolver.svAnsNameSuffix(ansAcronym)
              ) && entry.name != DsoAnsResolver.dsoAnsName(ansAcronym)
            )
          userEntries shouldBe empty
        }

        clue("Creating an ANS entry that expires immediately") {
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
            )
          clue("Created entry is expired") {
            eventually() {
              inside(lookupEntryByName(testEntryName)) { case Some(expired) =>
                inside(expired.expiresAt) { case Some(expiresAt) =>
                  expiresAt should be < OffsetDateTime.now
                }
              }
              assertThrowsAndLogsCommandFailures(
                sv1ScanBackend.lookupEntryByName(testEntryName),
                _.message should include(s"Entry with name $testEntryName not found"),
              )
            }
          }

        }

        clue("new entry with the same name can be registered by another user") {
          val aliceStaticRefs =
            StaticUserRefs(aliceValidatorBackend, aliceWalletClient)
          val aliceRefs = setupUser(aliceStaticRefs)

          requestAndPayForEntry(aliceRefs, testEntryName)
          val entry = eventuallySucceeds(timeUntilSuccess = 2.minutes) {
            sv1ScanBackend.lookupEntryByName(testEntryName)
          }
          entry.name shouldBe testEntryName
          entry.user shouldBe aliceRefs.userParty.toProtoPrimitive

        }
    }

    "reject invalid entry names" in { implicit env =>
      val aliceStaticRefs =
        StaticUserRefs(aliceValidatorBackend, aliceWalletClient)
      val aliceRefs = setupUser(aliceStaticRefs)

      clue("invalid entries(bad names) are rejected") {
        val invalidNames =
          Seq(
            s"alice.company.unverified.$ansAcronym",
            s"alice$$company.unverified.$ansAcronym",
            s"alice.$ansAcronym",
          )
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
          Seq(
            s"s3://alice.arn.$ansAcronym",
            "http://asdklfjh%skldjfgh",
            s"https://${"alice-" * 50}.$ansAcronym.com",
          )
        invalidUrls.foreach { url =>
          loggerFactory.assertLogsSeq(SuppressionRule.Level(Level.WARN))(
            {
              requestAndPayForEntry(aliceRefs, s"alice.unverified.$ansAcronym", entryUrl = url)
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
        val invalidDescriptions = Seq("Sample ANS Entry Description -" * 50)
        invalidDescriptions.foreach { desc =>
          loggerFactory.assertLogsSeq(SuppressionRule.Level(Level.WARN))(
            {
              requestAndPayForEntry(
                aliceRefs,
                s"alice.unverified.$ansAcronym",
                entryDescription = desc,
              )
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
        "request ANS entry",
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
            .futureValue
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
              disclosedContracts =
                DisclosedContracts.forTesting(ansRules).toLedgerApiDisclosedContracts,
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
          .futureValue
          .trigger[SubscriptionReadyForPaymentTrigger]

      setTriggersWithin[Assertion](
        triggersToPauseAtStart = Seq(aliceSubscriptionReadyForPaymentTrigger),
        triggersToResumeAtStart = dsoDelegateExpiredAnsEntryTriggers,
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
              disclosedContracts =
                DisclosedContracts.forTesting(ansRules).toLedgerApiDisclosedContracts,
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
        withClue("subscription expires even with disabled trigger") {
          eventually() {
            aliceWalletClient.listSubscriptions() shouldBe empty
          }
        }
        setTriggersWithin(
          Seq.empty,
          triggersToResumeAtStart =
            activeSvs.map(_.dsoDelegateBasedAutomation.trigger[ExpiredAnsSubscriptionTrigger]),
        ) {
          withClue("contracts removed with subscription trigger reenabled") {
            // Wait for subscription to be expired.
            eventually() {
              import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.ConstrainedTemplate
              forEvery(
                Table[ConstrainedTemplate](
                  "template",
                  subCodegen.Subscription.COMPANION,
                  subCodegen.SubscriptionIdleState.COMPANION,
                  codegen.AnsEntryContext.COMPANION,
                )
              ) { companion =>
                aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
                  .filterJava(companion)(aliceUserParty) shouldBe empty
              }
            }
          }
        }
      }
    }

    "the DSO party's ANS entry can be seen via scan api" in { implicit env =>
      val expectedDsoEntry = definitions.AnsEntry(
        None,
        dsoParty.toProtoPrimitive,
        DsoAnsResolver.dsoAnsName(ansAcronym),
        "",
        "",
        None,
      )

      sv1ScanBackend.lookupEntryByName(
        DsoAnsResolver.dsoAnsName(ansAcronym)
      ) shouldBe expectedDsoEntry
      sv1ScanBackend.lookupEntryByParty(dsoParty).value shouldBe expectedDsoEntry
      sv1ScanBackend.listEntries("", 100) should contain(expectedDsoEntry)
    }

    "an SV's ANS entry can be seen via scan api" in { implicit env =>
      val dsoRules = sv1Backend.getDsoInfo().dsoRules
      dsoRules.payload.svs.asScala.foreach { case (svParty, svInfo) =>
        val expectedSvEntry = svEntry(svInfo.name, svParty, ansAcronym)
        sv1ScanBackend.lookupEntryByName(
          s"${svInfo.name.toLowerCase}${DsoAnsResolver.svAnsNameSuffix(ansAcronym)}"
        ) shouldBe expectedSvEntry
        sv1ScanBackend
          .lookupEntryByParty(PartyId.tryFromProtoPrimitive(svParty))
          .value shouldBe expectedSvEntry
        sv1ScanBackend.listEntries("", 100) should contain(expectedSvEntry)
      }
    }
  }

  private def svEntry(svName: String, svParty: String, ansAcronym: String) =
    definitions.AnsEntry(
      None,
      svParty,
      s"${svName.toLowerCase}${DsoAnsResolver.svAnsNameSuffix(ansAcronym)}",
      "",
      "",
      None,
    )

  private def lookupEntryByName(
      name: String
  )(implicit env: SpliceTestConsoleEnvironment): Option[definitions.AnsEntry] = {
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
