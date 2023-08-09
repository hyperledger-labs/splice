package com.daml.network.integration.tests

import com.daml.network.codegen.java.cn.directory as codegen
import com.daml.network.codegen.java.cn.wallet.subscriptions as subsCodegen
import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.BracketSynchronous.*
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.util.{TimeTestUtil, WalletTestUtil}
import com.daml.network.wallet.admin.api.client.commands.HttpWalletAppClient
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

import java.time.Duration
import java.time.temporal.ChronoUnit
import scala.jdk.CollectionConverters.*

class DirectoryTimeBasedIntegrationTest
    extends CNNodeIntegrationTest
    with WalletTestUtil
    with TimeTestUtil {

  private val testEntryName = "mycoolentry.unverified.cns"
  private val testEntryUrl = "https://cns-dir-url.com"
  private val testEntryDescription = "Sample CNS Directory Entry Description"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)
      // start only sv1 but not sv2-4
      .addConfigTransformToFront(
        CNNodeConfigTransforms.onlySv1
      )

  "Directory service" should {

    "archive expired directory entries also when running on simtime" in { implicit env =>
      clue("Creating a directory entry that expires immediately") {
        directoryBackend.listEntries("", 25) shouldBe empty
        val dirParty = directoryBackend.getProviderPartyId()
        val now = directoryBackend.participantClient.ledger_api.time.get()
        directoryBackend.participantClientWithAdminToken.ledger_api_extensions.commands.submitJava(
          actAs = Seq(dirParty),
          commands = new codegen.DirectoryEntry(
            dirParty.toProtoPrimitive,
            dirParty.toProtoPrimitive,
            testEntryName,
            testEntryUrl,
            testEntryDescription,
            now.plus(Duration.ofDays(90)).toInstant,
          ).create.commands.asScala.toSeq,
          optTimeout = None,
        )
        eventually()(
          directoryBackend.listEntries("", 25) should not be empty
        )
      }
      clue("Waiting for the backend to expire the entry...") {
        advanceTime(Duration.ofDays(90).plus(Duration.ofSeconds(10)))
        eventually()(
          directoryBackend.listEntries("", 25) shouldBe empty
        )
      }
    }

    "allocate directory entries following an initial subscription payment and renew entries on follow-up payments" in {
      implicit env =>
        val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
        val providerParty = directoryBackend.getProviderPartyId()

        clue("Request install and wait for provider to auto-accept") {
          aliceDirectoryClient.requestDirectoryInstall()
          aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
            .awaitJava(codegen.DirectoryInstall.COMPANION)(aliceUserParty)
        }

        val (_, subReqId) = clue("Alice requests a directory entry") {
          aliceDirectoryClient.requestDirectoryEntry(
            testEntryName,
            testEntryUrl,
            testEntryDescription,
          )
        }
        clue("Alice obtains some coins and accepts the subscription") {
          aliceWalletClient.tap(50.0)
          aliceWalletClient.acceptSubscriptionRequest(subReqId)
        }
        val entry = clue("Getting Alice's new entry") {
          eventuallySucceeds()(
            directoryBackend.lookupEntryByName(testEntryName)
          )
        }
        clue("Checking payload of new entry") {
          val expectedPayload = new codegen.DirectoryEntry(
            aliceUserParty.toProtoPrimitive,
            providerParty.toProtoPrimitive,
            testEntryName,
            testEntryUrl,
            testEntryDescription,
            entry.payload.expiresAt,
          )
          entry.payload shouldBe expectedPayload
        }
        // Advance so we're within the renewalInterval + make sure that we have
        // an open round that we can use. We time the advances so that
        // automation doesn't trigger before payments can be made.
        advanceTimeAndWaitForRoundAutomation(Duration.ofDays(89).minus(Duration.ofMinutes(1)))
        advanceTimeToRoundOpen
        val renewedEntry = clue(
          "Eventually, Alice makes a follup-up subscription payment, which the directory collects, renewing her entry."
        ) {
          eventually()(
            directoryBackend
              .lookupEntryByName(testEntryName)
              .contractId should not equal entry.contractId
          )
          directoryBackend.lookupEntryByName(testEntryName)
        }
        clue("Checking payload of renewed entry") {
          val newEntry = new codegen.DirectoryEntry(
            entry.payload.user,
            entry.payload.provider,
            entry.payload.name,
            testEntryUrl,
            testEntryDescription,
            entry.payload.expiresAt.plus(90, ChronoUnit.DAYS),
          )
          renewedEntry.payload shouldBe newEntry
        }
    }

    "expire stale subscriptions" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      clue("Request install and wait for provider to auto-accept") {
        aliceDirectoryClient.requestDirectoryInstall()
        aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
          .awaitJava(codegen.DirectoryInstall.COMPANION)(aliceUserParty)
      }

      val (_, subReqId) = clue("Alice requests a directory entry") {
        aliceDirectoryClient.requestDirectoryEntry(
          testEntryName,
          testEntryUrl,
          testEntryDescription,
        )
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
          inside(aliceDirectoryClient.listEntries("", 25)) { case Seq(entry) =>
            entry.payload.name shouldBe testEntryName
          }
        },
      )
      // Stop validator so renewal does not happen
      aliceValidatorBackend.stop()
      advanceTime(Duration.ofDays(91))
      eventually() {
        aliceDirectoryClient.listEntries("", 25) shouldBe empty
      }
      // Wait for subscription to be expired.
      eventually() {
        aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(subsCodegen.Subscription.COMPANION)(aliceUserParty) shouldBe empty
        aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(subsCodegen.SubscriptionIdleState.COMPANION)(aliceUserParty) shouldBe empty
        aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(codegen.DirectoryEntryContext.COMPANION)(aliceUserParty) shouldBe empty
      }
    }

    "be able to collect subscription payments across round changes" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      aliceWalletClient.tap(50.0)

      clue("Request install and wait for provider to auto-accept") {
        aliceDirectoryClient.requestDirectoryInstall()
        aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
          .awaitJava(codegen.DirectoryInstall.COMPANION)(aliceUserParty)
      }

      val (_, subReqId) = clue("Alice requests a directory entry") {
        aliceDirectoryClient.requestDirectoryEntry(
          testEntryName,
          testEntryUrl,
          testEntryDescription,
        )
      }
      // to avoid automation triggering before the round change
      bracket(directoryBackend.stop(), directoryBackend.startSync()) {
        clue("Alice accepts the subscription") {
          aliceWalletClient.acceptSubscriptionRequest(subReqId)
        }

        advanceRoundsByOneTick
      }
      val entry = eventuallySucceeds() {
        directoryBackend.lookupEntryByName(testEntryName)
      }
      // to avoid automation triggering before the round change
      bracket(directoryBackend.stop(), directoryBackend.startSync()) {
        // Advance so we're within the renewalInterval + make sure that we have
        // an open round that we can use. We time the advances so that
        // automation doesn't trigger before payments can be made.
        advanceTimeAndWaitForRoundAutomation(Duration.ofDays(89).minus(Duration.ofMinutes(1)))
        advanceTimeToRoundOpen
        eventually() {
          aliceWalletClient
            .listSubscriptions()
            .headOption
            .value
            .state shouldBe a[HttpWalletAppClient.SubscriptionPayment]
        }
        advanceRoundsByOneTick
      }
      eventuallySucceeds() {
        val e = directoryBackend.lookupEntryByName(testEntryName)
        e.payload.expiresAt shouldBe entry.payload.expiresAt.plus(90, ChronoUnit.DAYS)
      }
    }
  }
}
