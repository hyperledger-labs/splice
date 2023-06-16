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

  private val directoryDarPath =
    "daml/directory-service/.daml/dist/directory-service-0.1.0.dar"
  private val testEntryName = "mycoolentry"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopologyXCentralizedDomainWithSimTime(this.getClass.getSimpleName)
      // start only sv1 but not sv2-4
      .addConfigTransformToFront(
        CNNodeConfigTransforms.onlySv1
      )
      .withAdditionalSetup(implicit env => {
        aliceValidator.participantClient.upload_dar_unless_exists(directoryDarPath)
        bobValidator.participantClient.upload_dar_unless_exists(directoryDarPath)
      })

  "Directory service" should {
    "archive expired directory entries also when running on simtime" in { implicit env =>
      clue("Creating a directory entry that expires immediately") {
        directory.listEntries("", 25) shouldBe empty
        val dirParty = directory.getProviderPartyId()
        val now = directory.participantClient.ledger_api.time.get()
        directory.participantClientWithAdminToken.ledger_api_extensions.commands.submitJava(
          actAs = Seq(dirParty),
          commands = new codegen.DirectoryEntry(
            dirParty.toProtoPrimitive,
            dirParty.toProtoPrimitive,
            testEntryName,
            now.plus(Duration.ofDays(90)).toInstant,
          ).create.commands.asScala.toSeq,
          optTimeout = None,
        )
        eventually()(
          directory.listEntries("", 25) should not be empty
        )
      }
      clue("Waiting for the backend to expire the entry...") {
        advanceTime(Duration.ofDays(90).plus(Duration.ofSeconds(10)))
        eventually()(
          directory.listEntries("", 25) shouldBe empty
        )
      }
    }
    "allocate directory entries following an initial subscription payment and renew entries on follow-up payments" in {
      implicit env =>
        val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)
        val providerParty = directory.getProviderPartyId()

        clue("Request install and wait for provider to auto-accept") {
          aliceDirectory.requestDirectoryInstall()
          aliceValidator.participantClientWithAdminToken.ledger_api_extensions.acs
            .awaitJava(codegen.DirectoryInstall.COMPANION)(aliceUserParty)
        }

        val (_, subReqId) = clue("Alice requests a directory entry") {
          aliceDirectory.requestDirectoryEntry(testEntryName)
        }
        clue("Alice obtains some coins and accepts the subscription") {
          aliceWallet.tap(50.0)
          aliceWallet.acceptSubscriptionRequest(subReqId)
        }
        val entry = clue("Getting Alice's new entry") {
          eventuallySucceeds()(
            directory.lookupEntryByName(testEntryName)
          )
        }
        clue("Checking payload of new entry") {
          val expectedPayload = new codegen.DirectoryEntry(
            aliceUserParty.toProtoPrimitive,
            providerParty.toProtoPrimitive,
            testEntryName,
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
            directory
              .lookupEntryByName(testEntryName)
              .contractId should not equal entry.contractId
          )
          directory.lookupEntryByName(testEntryName)
        }
        clue("Checking payload of renewed entry") {
          val newEntry = new codegen.DirectoryEntry(
            entry.payload.user,
            entry.payload.provider,
            entry.payload.name,
            entry.payload.expiresAt.plus(90, ChronoUnit.DAYS),
          )
          renewedEntry.payload shouldBe newEntry
        }
    }
    "expire stale subscriptions" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)
      clue("Request install and wait for provider to auto-accept") {
        aliceDirectory.requestDirectoryInstall()
        aliceValidator.participantClientWithAdminToken.ledger_api_extensions.acs
          .awaitJava(codegen.DirectoryInstall.COMPANION)(aliceUserParty)
      }

      val (_, subReqId) = clue("Alice requests a directory entry") {
        aliceDirectory.requestDirectoryEntry(testEntryName)
      }
      aliceWallet.tap(50.0)
      actAndCheck(
        "Alice accepts subscription and waits for entry", {
          aliceWallet.acceptSubscriptionRequest(subReqId)
        },
      )(
        "Subscription and entry are created",
        _ => {
          aliceWallet.listSubscriptions() should have length 1
          inside(aliceDirectory.listEntries("", 25)) { case Seq(entry) =>
            entry.payload.name shouldBe testEntryName
          }
        },
      )
      // Stop validator so renewal does not happen
      aliceValidator.stop()
      advanceTime(Duration.ofDays(91))
      eventually() {
        aliceDirectory.listEntries("", 25) shouldBe empty
      }
      // Wait for subscription to be expired.
      eventually() {
        aliceValidator.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(subsCodegen.Subscription.COMPANION)(aliceUserParty) shouldBe empty
        aliceValidator.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(subsCodegen.SubscriptionIdleState.COMPANION)(aliceUserParty) shouldBe empty
        aliceValidator.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(codegen.DirectoryEntryContext.COMPANION)(aliceUserParty) shouldBe empty
      }
    }

    "be able to collect subscription payments across round changes" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)
      aliceWallet.tap(50.0)

      clue("Request install and wait for provider to auto-accept") {
        aliceDirectory.requestDirectoryInstall()
        aliceValidator.participantClientWithAdminToken.ledger_api_extensions.acs
          .awaitJava(codegen.DirectoryInstall.COMPANION)(aliceUserParty)
      }

      val (_, subReqId) = clue("Alice requests a directory entry") {
        aliceDirectory.requestDirectoryEntry(testEntryName)
      }
      // to avoid automation triggering before the round change
      bracket(directory.stop(), directory.startSync()) {
        clue("Alice accepts the subscription") {
          aliceWallet.acceptSubscriptionRequest(subReqId)
        }

        advanceRoundsByOneTick
      }
      val entry = eventuallySucceeds() {
        directory.lookupEntryByName(testEntryName)
      }
      // to avoid automation triggering before the round change
      bracket(directory.stop(), directory.startSync()) {
        // Advance so we're within the renewalInterval + make sure that we have
        // an open round that we can use. We time the advances so that
        // automation doesn't trigger before payments can be made.
        advanceTimeAndWaitForRoundAutomation(Duration.ofDays(89).minus(Duration.ofMinutes(1)))
        advanceTimeToRoundOpen
        eventually() {
          aliceWallet
            .listSubscriptions()
            .headOption
            .value
            .state shouldBe a[HttpWalletAppClient.SubscriptionPayment]
        }
        advanceRoundsByOneTick
      }
      eventuallySucceeds() {
        val e = directory.lookupEntryByName(testEntryName)
        e.payload.expiresAt shouldBe entry.payload.expiresAt.plus(90, ChronoUnit.DAYS)
      }
    }
  }
}
