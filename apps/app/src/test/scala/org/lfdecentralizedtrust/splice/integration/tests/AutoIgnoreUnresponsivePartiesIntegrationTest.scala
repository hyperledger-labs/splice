// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.topology.transaction.ParticipantPermission
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.store.db.DbMultiDomainAcsStore
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.{
  AdvanceOpenMiningRoundTrigger,
  ExpiredAmuletTrigger,
  ExpiredLockedAmuletTrigger,
  UpdateExternalPartyConfigStateTrigger,
}
import org.lfdecentralizedtrust.splice.sv.config.UnavailablePartiesBackoffParameters
import org.lfdecentralizedtrust.splice.util.*
import org.slf4j.event.Level
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*

abstract class AutoIgnoreUnresponsivePartiesIntegrationTestBase
    extends IntegrationTest
    with WalletTestUtil
    with TimeTestUtil
    with TriggerTestUtil {

  override protected def runTokenStandardCliSanityCheck: Boolean = false
  override protected def runUpdateHistorySanityCheck: Boolean = false

  protected val enablePersistedUnavailableParties: Boolean

  protected def unavailablePartiesBackoffParameters: UnavailablePartiesBackoffParameters =
    UnavailablePartiesBackoffParameters()

  protected def reconnectAliceAfterIgnore: Boolean = true

  /** Set by the first test so the follow-up tests can query her row. */
  protected val unresponsivePartyRef = new AtomicReference[Option[PartyId]](None)

  protected def unresponsiveParty: PartyId =
    unresponsivePartyRef.get().getOrElse(fail("alice's party was not recorded by the first test"))

  protected def ignoredParties(implicit env: SpliceTestConsoleEnvironment): Seq[PartyId] =
    sv1Backend.dsoDelegateBasedAutomation.unavailablePartiesStore
      .listParties()(TraceContext.empty)
      .futureValue

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .withTrafficTopupsDisabled
      .addConfigTransforms(
        (_, c) =>
          ConfigTransforms.updateInitialTickDuration(NonNegativeFiniteDuration.ofMillis(500))(c),
        (_, c) =>
          ConfigTransforms.updateInitialExternalPartyConfigStateTickDuration(
            NonNegativeFiniteDuration.ofMillis(500)
          )(c),
      )
      .addConfigTransforms((_, c) =>
        updateAutomationConfig(ConfigurableApp.Sv)(
          _.withPausedTrigger[AdvanceOpenMiningRoundTrigger]
            .withPausedTrigger[UpdateExternalPartyConfigStateTrigger]
            .withPausedTrigger[ExpiredAmuletTrigger]
            .withPausedTrigger[ExpiredLockedAmuletTrigger]
        )(c)
      )
      .addConfigTransforms((_, c) =>
        updateAutomationConfig(ConfigurableApp.Validator)(
          _.copy(enableAutomaticRewardsCollectionAndAmuletMerging = false)
        )(c)
      )
      .addConfigTransforms((_, c) =>
        ConfigTransforms.updateAllSvAppConfigs_(
          _.copy(delegatelessAutomationExpiredAmuletBatchSize = 2)
        )(c)
      )
      .addConfigTransforms((_, c) =>
        ConfigTransforms.updateAllSvAppConfigs_(
          _.copy(unavailablePartiesBackoffParameters = unavailablePartiesBackoffParameters)
        )(c)
      )
      .addConfigTransforms((_, c) =>
        ConfigTransforms.updateAllSvAppConfigs_(conf =>
          conf.copy(parameters =
            conf.parameters.copy(enabledFeatures =
              conf.parameters.enabledFeatures
                .copy(enablePersistedUnavailableParties = enablePersistedUnavailableParties)
            )
          )
        )(c)
      )

  "Expiry triggers auto-ignore parties whose participant is disconnected (MEDIATOR_SAYS_TX_TIMED_OUT)" in {
    implicit env =>
      val synchronizerId = decentralizedSynchronizerId

      val aliceUserId = aliceWalletClient.config.ledgerApiUser

      val aliceParty = onboardWalletUserHostedAlsoOn(
        aliceWalletClient,
        aliceValidatorBackend,
        sv1Backend.participantClientWithAdminToken,
        synchronizerId,
      )
      // record alice's party so the follow-up tests in this suite can query her row
      unresponsivePartyRef.set(Some(aliceParty))
      val sv1ParticipantId = sv1Backend.participantClientWithAdminToken.id
      val aliceParticipantId = aliceValidatorBackend.participantClient.id
      val sv1Participant = sv1Backend.participantClientWithAdminToken
      val aliceParticipant = aliceValidatorBackend.participantClient

      val numAmulets = 2
      val amuletAmount = BigDecimal(123.0)

      loggerFactory.suppress(
        SuppressionRule.forLogger[DbMultiDomainAcsStore[?]] && SuppressionRule.Level(Level.ERROR)
      ) {
        actAndCheck(
          "Create dust amulets owned by alice", {
            for (_ <- 1 to numAmulets) {
              createAmulet(
                sv1Backend.participantClientWithAdminToken,
                aliceUserId,
                aliceParty,
                amount = amuletAmount,
                holdingFee = amuletAmount,
              )
              createLockedAmulet(
                sv1Backend.participantClientWithAdminToken,
                aliceUserId,
                aliceParty,
                lockHolders = Seq(aliceParty),
                amount = amuletAmount,
                holdingFee = amuletAmount,
                expiredDuration = Duration.ofSeconds(1),
              )
            }
          },
        )(
          "Dust amulets show up in alice's wallet",
          _ => {
            aliceWalletClient.list().amulets should have length numAmulets.toLong
            aliceWalletClient.list().lockedAmulets should have length numAmulets.toLong
          },
        )
      }

      actAndCheck(
        "Remove alice from sv1 so only her own participant hosts her",
        eventuallySucceeds() {
          aliceParticipant.topology.party_to_participant_mappings.propose(
            party = aliceParty,
            newParticipants = Seq(
              (aliceParticipantId, ParticipantPermission.Submission)
            ),
            store = synchronizerId,
            mustFullyAuthorize = true,
          )
        },
      )(
        "Alice is only hosted on her own participant",
        _ => {
          val hosts = sv1Participant.topology.party_to_participant_mappings
            .list(synchronizerId, filterParty = aliceParty.toProtoPrimitive)
            .flatMap(_.item.participants)
          hosts.exists(_.participantId == sv1ParticipantId) shouldBe false
          hosts.exists(_.participantId == aliceParticipantId) shouldBe true
        },
      )

      clue("Disconnect alice's participant from the synchronizer") {
        // stop to avoid log noise.
        aliceValidatorBackend.stop()
        aliceValidatorBackend.participantClient.synchronizers.disconnect_all()
        aliceValidatorBackend.participantClient.synchronizers.is_connected(
          synchronizerId
        ) shouldBe false
      }

      actAndCheck(timeUntilSuccess = 180.seconds)(
        "Advance 4 rounds and resume expiry triggers", {
          (1 to 4).foreach(_ => advanceRoundsByOneTickViaAutomation())
          updateExternalPartyConfigStatesViaAutomation()
          updateExternalPartyConfigStatesViaAutomation()
          env.svs.local.foreach { sv =>
            sv.dsoDelegateBasedAutomation.trigger[ExpiredAmuletTrigger].resume()
            sv.dsoDelegateBasedAutomation.trigger[ExpiredLockedAmuletTrigger].resume()
          }
        },
      )(
        "Alice is added to the ignored parties store after mediator timeout",
        _ => {
          ignoredParties should contain(aliceParty)
        },
      )

      // reconnect or other tests might get unhappy, in particular `withNoVettedPackages` gets confused if the nodes is disconnected.
      if (reconnectAliceAfterIgnore) {
        clue("Reconnect alice's participant") {
          aliceValidatorBackend.participantClient.synchronizers.reconnect_all()
        }
      }
  }
}

class AutoIgnoreUnresponsivePartiesInMemoryIntegrationTest
    extends AutoIgnoreUnresponsivePartiesIntegrationTestBase {
  override protected val enablePersistedUnavailableParties: Boolean = false

  "Ignored parties don't survive an SV app restart" in { implicit env =>
    sv1Backend.stop()
    sv1Backend.startSync()
    ignoredParties shouldBe empty
  }
}

class AutoIgnoreUnresponsivePartiesWithPersistenceIntegrationTest
    extends AutoIgnoreUnresponsivePartiesIntegrationTestBase {

  override protected val enablePersistedUnavailableParties: Boolean = true

  override protected def reconnectAliceAfterIgnore: Boolean = false

  override protected def unavailablePartiesBackoffParameters: UnavailablePartiesBackoffParameters =
    UnavailablePartiesBackoffParameters(
      baseIgnoreDuration = NonNegativeFiniteDuration.ofSeconds(15),
      maxIgnoreDuration = NonNegativeFiniteDuration.ofSeconds(30),
    )

  private def ignoreDurationOf(
      party: PartyId
  )(implicit env: SpliceTestConsoleEnvironment): Option[Long] = {
    val db = sv1Backend.appState.storage
    implicit val closeContext: CloseContext = CloseContext(db)
    db.querySingle(
      sql"""select ignore_duration
            from dso_unavailable_parties
            where party = ${party.toProtoPrimitive}"""
        .as[Long]
        .headOption,
      "test.lookupIgnoreDuration",
    ).value
      .futureValueUS
  }

  private def resumeExpiryTriggers()(implicit env: SpliceTestConsoleEnvironment): Unit =
    env.svs.local.foreach { sv =>
      sv.dsoDelegateBasedAutomation.trigger[ExpiredAmuletTrigger].resume()
      sv.dsoDelegateBasedAutomation.trigger[ExpiredLockedAmuletTrigger].resume()
    }

  "Ignored parties survive an SV app restart" in { implicit env =>
    sv1Backend.stop()
    sv1Backend.startSync()
    eventually() {
      ignoredParties should not be empty
    }
    resumeExpiryTriggers()
  }

  "A party that is still unavailable is retried and ignored again with a doubled window" in {
    implicit env =>
      val baseMicros = unavailablePartiesBackoffParameters.baseIgnoreDuration.underlying.toMicros

      clue("Alice's window elapses, the retry fails again, and her ignore duration is doubled") {
        eventually(60.seconds) {
          ignoreDurationOf(unresponsiveParty) shouldBe Some(2 * baseMicros)
        }
      }
  }

  "A party that became available again is removed from the store" in { implicit env =>
    loggerFactory.assertEventuallyLogsSeq(SuppressionRule.Level(Level.INFO))(
      {
        clue("Reconnect alice's participant so that the next retry succeeds") {
          aliceValidatorBackend.participantClient.synchronizers.reconnect_all()
        }
        clue("Alice is removed from the store once the expiry submission succeeds") {
          eventually(timeUntilSuccess = 60.seconds) {
            ignoredParties shouldBe empty
          }
        }
      },
      logEntries =>
        forAtLeast(1, logEntries) { entry =>
          entry.message should include regex
            raw"""Submission succeeded, recovered 1 unavailable parties: .*\Q${unresponsiveParty.toString}\E"""
        },
      timeUntilSuccess = 60.seconds,
    )
  }
}
