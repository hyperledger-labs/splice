package com.daml.network.integration.tests

import com.daml.network.codegen.java.cn.cns as codegen
import com.daml.network.codegen.java.cn.wallet.subscriptions as subCodegen
import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.util.{DisclosedContracts, TimeTestUtil, WalletTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

import java.time.Duration
import java.time.temporal.ChronoUnit

class CnsTimeBasedIntegrationTest
    extends CNNodeIntegrationTest
    with WalletTestUtil
    with TimeTestUtil {

  private val cnsDarPath = "daml/canton-name-service/.daml/dist/canton-name-service-0.1.0.dar"

  private val testEntryName = "mycoolentry.unverified.cns"
  private val testEntryUrl = "https://cns-dir-url.com"
  private val testEntryDescription = "Sample CNS Entry Description"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)
      // start only sv1 but not sv2-4
      .addConfigTransformToFront(
        CNNodeConfigTransforms.onlySv1
      )
      // TODO(#7353): we will not upload the dars here after we have switch to upload dars in validator.
      .withAdditionalSetup(implicit env => {
        aliceValidatorBackend.participantClient.upload_dar_unless_exists(cnsDarPath)
        bobValidatorBackend.participantClient.upload_dar_unless_exists(cnsDarPath)
      })

  "cns" should {
    "allocate cns entries following an initial subscription payment and renew entries on follow-up payments" in {
      implicit env =>
        val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
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
        // Advance so we're within the renewalInterval + make sure that we have
        // an open round that we can use. We time the advances so that
        // automation doesn't trigger before payments can be made.
        advanceTimeAndWaitForRoundAutomation(Duration.ofDays(89).minus(Duration.ofMinutes(1)))
        advanceTimeToRoundOpen
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
            entry.data.expiresAt.plus(90, ChronoUnit.DAYS),
          )
          renewedEntry.data shouldBe newEntry
        }
    }

    "expire stale subscriptions" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
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
      // Stop validator so renewal does not happen
      aliceValidatorBackend.stop()
      advanceTime(Duration.ofDays(91))
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
}
