package com.daml.network.integration.tests

import com.daml.network.codegen.java.cn.wallet.subscriptions as subsCodegen
import com.daml.network.codegen.java.cn.directory as codegen
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
}
import com.daml.network.util.{TimeTestUtil, WalletTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

import java.time.Duration
import java.time.temporal.ChronoUnit
import scala.jdk.CollectionConverters.*
import scala.util.Try

class DirectoryTimeBasedIntegrationTest
    extends CoinIntegrationTest
    with WalletTestUtil
    with TimeTestUtil {

  private val directoryDarPath =
    "daml/directory-service/.daml/dist/directory-service-0.1.0.dar"
  private val testEntryName = "mycoolentry"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)
      .withAdditionalSetup(implicit env => {
        aliceValidator.remoteParticipant.dars.upload(directoryDarPath)
        bobValidator.remoteParticipant.dars.upload(directoryDarPath)
      })

  "Directory service" should {
    "archive expired directory entries also when running on simtime" in { implicit env =>
      clue("Creating a directory entry that expires immediately") {
        directory.listEntries("", 25) shouldBe empty
        val dirParty = directory.getProviderPartyId()
        val now = directory.remoteParticipant.ledger_api.time.get()
        directory.remoteParticipantWithAdminToken.ledger_api_extensions.commands.submitJava(
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
          aliceValidator.remoteParticipantWithAdminToken.ledger_api_extensions.acs
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
          def tryGetEntry() =
            Try(loggerFactory.suppressErrors(directory.lookupEntryByName(testEntryName)))
          eventually()(tryGetEntry().getOrElse(fail(s"Could not get entry $testEntryName")))
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
        // Advance so we’re within the renewalInterval
        advanceTime(Duration.ofDays(89).plus(Duration.ofSeconds(10)))
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
        aliceValidator.remoteParticipantWithAdminToken.ledger_api_extensions.acs
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
      // Stop wallet so renewal does not happen
      aliceWalletBackend.stop()
      advanceTime(Duration.ofDays(91))
      eventually() {
        aliceDirectory.listEntries("", 25) shouldBe empty
      }
      // Wait for subscription to be expired.
      eventually() {
        aliceWalletBackend.remoteParticipantWithAdminToken.ledger_api_extensions.acs
          .filterJava(subsCodegen.Subscription.COMPANION)(aliceUserParty) shouldBe empty
        aliceWalletBackend.remoteParticipantWithAdminToken.ledger_api_extensions.acs
          .filterJava(subsCodegen.SubscriptionIdleState.COMPANION)(aliceUserParty) shouldBe empty
        aliceWalletBackend.remoteParticipantWithAdminToken.ledger_api_extensions.acs
          .filterJava(codegen.DirectoryEntryContext.COMPANION)(aliceUserParty) shouldBe empty
      }
    }

  }
}
