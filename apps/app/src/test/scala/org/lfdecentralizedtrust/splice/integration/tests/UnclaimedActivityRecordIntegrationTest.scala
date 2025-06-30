package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.automation.Trigger
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.{
  UnclaimedActivityRecord,
  UnclaimedReward,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_DsoRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.SRARC_CreateUnallocatedUnclaimedActivityRecord
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.DsoRules_CreateUnallocatedUnclaimedActivityRecord
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.UnallocatedUnclaimedActivityRecord
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.{
  AllocateUnallocatedUnclaimedActivityRecordTrigger,
  ExpiredUnallocatedUnclaimedActivityRecordTrigger,
  ExpiredUnclaimedActivityRecordTrigger,
}
import org.lfdecentralizedtrust.splice.util.TriggerTestUtil
import org.lfdecentralizedtrust.splice.util.WalletTestUtil
import org.lfdecentralizedtrust.splice.wallet.automation.CollectRewardsAndMergeAmuletsTrigger

import com.daml.ledger.javaapi.data.Identifier
import java.time.Instant
import java.time.temporal.ChronoUnit

class UnclaimedActivityRecordIntegrationTest
    extends SvIntegrationTestBase
    with TriggerTestUtil
    with WalletTestUtil {

  override def environmentDefinition
      : org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)

  override protected lazy val sanityChecksIgnoredRootCreates: Seq[Identifier] = Seq(
    UnclaimedReward.TEMPLATE_ID_WITH_PACKAGE_ID
  )

  "An UnallocatedUnclaimedActivityRecord gets expired" in { implicit env =>
    val sv1Party = sv1Backend.getDsoInfo().svParty
    val aliceUserName = aliceWalletClient.config.ledgerApiUser
    val aliceParty = aliceValidatorBackend.onboardUser(aliceUserName)
    val amountToMint = 15.0

    def allocateTriggers: Seq[Trigger] =
     activeSvs.map(_.dsoDelegateBasedAutomation.trigger[AllocateUnallocatedUnclaimedActivityRecordTrigger])
    def expiredUnallocatedTriggers: Seq[Trigger] =
     activeSvs.map(_.dsoDelegateBasedAutomation.trigger[ExpiredUnallocatedUnclaimedActivityRecordTrigger])
    setTriggersWithin(
     triggersToPauseAtStart = allocateTriggers ++ expiredUnallocatedTriggers,
     triggersToResumeAtStart = Seq.empty,
    ) {
     actAndCheck(
       "Creating vote request", {
         val action = new ARC_DsoRules(
           new SRARC_CreateUnallocatedUnclaimedActivityRecord(
             new DsoRules_CreateUnallocatedUnclaimedActivityRecord(
               aliceParty.toProtoPrimitive,
               BigDecimal(amountToMint).bigDecimal,
               "alice is doing great - vote",
               Instant.now().plus(5, ChronoUnit.SECONDS),
             )
           )
         )
         eventuallySucceeds() {
           sv1Backend.createVoteRequest(
             sv1Party.toProtoPrimitive,
             action,
             "url",
             "alice is doing great",
             sv1Backend.getDsoInfo().dsoRules.payload.config.voteRequestTimeout,
             None,
           )
         }
       },
     )("vote request and UnallocatedUnclaimedActivityRecord have been created",
        _ => {
         sv1Backend.listVoteRequests().loneElement
          eventually(){
           sv1Backend.participantClient.ledger_api_extensions.acs
             .filterJava(UnallocatedUnclaimedActivityRecord.COMPANION)(dsoParty) should not be empty
          }
        }
      )
    }

    setTriggersWithin(
     triggersToPauseAtStart = allocateTriggers,
     triggersToResumeAtStart = Seq.empty,
    ) {
      clue("UnallocatedUnclaimedActivityRecord gets archived"){
        eventually(){
         sv1Backend.participantClient.ledger_api_extensions.acs
           .filterJava(UnallocatedUnclaimedActivityRecord.COMPANION)(dsoParty) shouldBe empty
        }
      }
    }
  }

  "An UnclaimedActivityRecord gets expired" in { implicit env =>
    val sv1UserId = sv1WalletClient.config.ledgerApiUser
    val sv1Party = sv1Backend.getDsoInfo().svParty
    val aliceUserName = aliceWalletClient.config.ledgerApiUser
    val aliceParty = aliceValidatorBackend.onboardUser(aliceUserName)
    val amountToMint = 15.0
    val unclaimedRewardsToMint = Seq(15.0)

    def allocateTriggers: Seq[Trigger] =
      activeSvs.map(_.dsoDelegateBasedAutomation.trigger[AllocateUnallocatedUnclaimedActivityRecordTrigger])
    def expiredUnallocatedTriggers: Seq[Trigger] =
      activeSvs.map(_.dsoDelegateBasedAutomation.trigger[ExpiredUnallocatedUnclaimedActivityRecordTrigger])
    def expiredUnclaimedTriggers: Seq[Trigger] =
      activeSvs.map(_.dsoDelegateBasedAutomation.trigger[ExpiredUnclaimedActivityRecordTrigger])
    // The trigger that merges amulets for alice
    // Note: using `def`, as the trigger may be destroyed and recreated (when user is offboarded and onboarded)
    def aliceMergeAmuletsTrigger: Trigger =
      aliceValidatorBackend
        .userWalletAutomation(aliceUserName)
        .futureValue
        .trigger[CollectRewardsAndMergeAmuletsTrigger]

    actAndCheck(
      "Mint some unclaimed rewards",
      unclaimedRewardsToMint.foreach { amount =>
        createUnclaimedReward(
          sv1ValidatorBackend.participantClientWithAdminToken,
          sv1UserId,
          amount
        )
      },
    )(
      "Unclaimed rewards gets created",
      _ => {
        sv1Backend.participantClient.ledger_api_extensions.acs
          .filterJava(UnclaimedReward.COMPANION)(dsoParty) should not be empty
      },
    )

    setTriggersWithin(
      triggersToPauseAtStart = allocateTriggers ++ expiredUnallocatedTriggers ++ expiredUnclaimedTriggers,
      triggersToResumeAtStart = Seq.empty,
    ) {
      actAndCheck(
        "Creating vote request", {
          val action = new ARC_DsoRules(
            new SRARC_CreateUnallocatedUnclaimedActivityRecord(
              new DsoRules_CreateUnallocatedUnclaimedActivityRecord(
                aliceParty.toProtoPrimitive,
                BigDecimal(amountToMint).bigDecimal,
                "alice is doing great - vote",
                Instant.now().plus(5, ChronoUnit.SECONDS),
              )
            )
          )
          eventuallySucceeds() {
            sv1Backend.createVoteRequest(
              sv1Party.toProtoPrimitive,
              action,
              "url",
              "alice is doing great",
              sv1Backend.getDsoInfo().dsoRules.payload.config.voteRequestTimeout,
              None,
            )
          }
        },
      )("vote request and UnallocatedUnclaimedActivityRecord have been created",
        _ => {
          sv1Backend.listVoteRequests().loneElement
          eventually(){
            sv1Backend.participantClient.ledger_api_extensions.acs
              .filterJava(UnallocatedUnclaimedActivityRecord.COMPANION)(dsoParty) should not be empty
          }
        }
      )
    }

    setTriggersWithin(
      triggersToPauseAtStart = expiredUnclaimedTriggers :+ aliceMergeAmuletsTrigger,
      triggersToResumeAtStart = Seq.empty,
    ) {
      clue("UnclaimedActivityRecord has been created"){
        eventually(){
          sv1Backend.participantClient.ledger_api_extensions.acs
            .filterJava(UnclaimedActivityRecord.COMPANION)(dsoParty) should not be empty

          sv1Backend.participantClient.ledger_api_extensions.acs
            .filterJava(UnclaimedReward.COMPANION)(dsoParty) shouldBe empty
        }
      }
    }

    setTriggersWithin(
      triggersToPauseAtStart = Seq(aliceMergeAmuletsTrigger),
      triggersToResumeAtStart = Seq.empty,
    ) {
      clue("UnclaimedActivityRecord gets archived"){
        eventually(){
          sv1Backend.participantClient.ledger_api_extensions.acs
            .filterJava(UnclaimedActivityRecord.COMPANION)(dsoParty) shouldBe empty
        }
      }
    }
  }

  "Amulet gets minted" in { implicit env =>
    val sv1UserId = sv1WalletClient.config.ledgerApiUser
    val sv1Party = sv1Backend.getDsoInfo().svParty
    val aliceUserName = aliceWalletClient.config.ledgerApiUser
    val aliceParty = aliceValidatorBackend.onboardUser(aliceUserName)
    val unclaimedRewardsToMint = Seq(12.0, 2.0, 5.0)
    val amountToMint = 15.0

    def allocateTriggers: Seq[Trigger] =
     activeSvs.map(_.dsoDelegateBasedAutomation.trigger[AllocateUnallocatedUnclaimedActivityRecordTrigger])
    // The trigger that merges amulets for alice
    // Note: using `def`, as the trigger may be destroyed and recreated (when user is offboarded and onboarded)
    def aliceMergeAmuletsTrigger: Trigger =
     aliceValidatorBackend
       .userWalletAutomation(aliceUserName)
       .futureValue
       .trigger[CollectRewardsAndMergeAmuletsTrigger]

    aliceWalletClient.list().amulets should have length 0

    actAndCheck(
     "Mint some unclaimed rewards",
     unclaimedRewardsToMint.foreach { amount =>
       createUnclaimedReward(
         sv1ValidatorBackend.participantClientWithAdminToken,
         sv1UserId,
         amount
       )
     },
    )(
      "Unclaimed rewards gets created",
      _ => {
        sv1Backend.participantClient.ledger_api_extensions.acs
          .filterJava(UnclaimedReward.COMPANION)(dsoParty) should not be empty
      },
    )

    setTriggersWithin(
     triggersToPauseAtStart = allocateTriggers,
     triggersToResumeAtStart = Seq.empty,
    ) {
      actAndCheck(
       "Creating vote request", {
         val action = new ARC_DsoRules(
           new SRARC_CreateUnallocatedUnclaimedActivityRecord(
             new DsoRules_CreateUnallocatedUnclaimedActivityRecord(
               aliceParty.toProtoPrimitive,
               BigDecimal(amountToMint).bigDecimal,
               "alice is doing great - vote",
               Instant.now().plus(1, ChronoUnit.DAYS),
             )
           )
         )
         eventuallySucceeds() {
           sv1Backend.createVoteRequest(
             sv1Party.toProtoPrimitive,
             action,
             "url",
             "alice is doing great",
             sv1Backend.getDsoInfo().dsoRules.payload.config.voteRequestTimeout,
             None,
           )
         }
       },
      )("vote request and UnallocatedUnclaimedActivityRecord have been created",
        _ => {
         sv1Backend.listVoteRequests().loneElement
         eventually(){
           sv1Backend.participantClient.ledger_api_extensions.acs
             .filterJava(UnallocatedUnclaimedActivityRecord.COMPANION)(dsoParty) should not be empty
         }
        }
      )
    }

    setTriggersWithin(
     triggersToPauseAtStart = Seq(aliceMergeAmuletsTrigger),
     triggersToResumeAtStart = Seq.empty,
    ) {
      clue("UnclaimedActivityRecord has been created"){
        eventually(){
         sv1Backend.participantClient.ledger_api_extensions.acs
           .filterJava(UnclaimedActivityRecord.COMPANION)(dsoParty) should not be empty

         val unclaimedRewardLeftover: BigDecimal =
           sv1Backend.participantClient.ledger_api_extensions.acs
             .filterJava(UnclaimedReward.COMPANION)(dsoParty).map(r => scala.math.BigDecimal(r.data.amount)).sum
         unclaimedRewardLeftover shouldBe BigDecimal(unclaimedRewardsToMint.sum - amountToMint)
        }
      }
    }

    clue("Amulet gets minted"){
      eventually(){
        aliceWalletClient.list().amulets should have length 1
      }
    }

    clue("UnclaimedActivityRecord gets archived"){
      eventually(){
        sv1Backend.participantClient.ledger_api_extensions.acs
          .filterJava(UnclaimedActivityRecord.COMPANION)(dsoParty) shouldBe empty
      }
    }
  }
}
