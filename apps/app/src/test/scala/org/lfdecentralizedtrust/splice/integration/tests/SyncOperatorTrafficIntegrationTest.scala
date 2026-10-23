// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.SynchronizerAlias
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.config.RequireTypes.{
  NonNegativeLong,
  NonNegativeNumeric,
  PositiveLong,
}
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.topology.{Member, SynchronizerId}
import monocle.macros.syntax.lens.*
import org.lfdecentralizedtrust.splice.codegen.java.splice.decentralizedsynchronizer.GovernanceParameters
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.topupstate.ValidatorTopUpState
import org.lfdecentralizedtrust.splice.console.ParticipantClientReference
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.TopologySnapshot
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.http.v0.definitions as d0
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.util.{
  SynchronizerFeesTestUtil,
  SyncOperatorTestUtil,
  TriggerTestUtil,
  WalletTestUtil,
}
import org.lfdecentralizedtrust.splice.validator.automation.TopupMemberTrafficTrigger
import org.lfdecentralizedtrust.splice.wallet.store.TxLogEntry
import org.slf4j.event.Level

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** Buys traffic for a registered synchronizer via `AmuletRules_BuyMemberTraffic` with the
  * registration disclosed, and checks the operator grants it on that synchronizer's sequencer,
  * also through a global-synchronizer outage on the operator's outage traffic allowance.
  */
@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceDsoGovernance_0_1_31
class SyncOperatorTrafficIntegrationTest
    extends IntegrationTest
    with SynchronizerFeesTestUtil
    with SyncOperatorTestUtil
    with TriggerTestUtil
    with WalletTestUtil {

  private val firstPurchase = 1_000_000L
  private val secondPurchase = 2_000_000L
  private val walletRequestPurchase = 3_000_000L
  private val discount = BigDecimal("0.5")
  private val splitwellAlias = SynchronizerAlias.tryCreate("splitwell")
  private val globalAlias = SynchronizerAlias.tryCreate("global")

  // The outage test's amounts: small enough that a few dozen pings take bob past what was bought
  // for him and on through the allowance.
  private val outageTrafficAllowance = 50_000L
  private val bobPurchase = 50_000L
  // Bought once the global synchronizer is back but before the allowance is removed.
  private val bobPurchaseWithAllowanceSet = 20_000L
  // The same operator app with the allowance set, which the outage test runs in place of
  // syncOperator. Both use one store, so they never run at the same time.
  private val syncOperatorWithAllowanceName = "syncOperatorWithAllowance"

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .fromResources(
        Seq("simple-topology-1sv.conf", "sync-operator-topology.conf"),
        this.getClass.getSimpleName,
      )
      // Only alice and bob connect to splitwell: alice for the purchases, bob for the outage test,
      // which needs a member whose balance starts small. The operator's participant joins during
      // the outage test, as a member with no purchase on record.
      .withValidatorsDisconnectedFromSplitwell("splitwellValidator")
      .withStandardSetup
      // Down from 200 KB, so bob's small purchases are allowed.
      .addConfigTransform((_, conf) =>
        ConfigTransforms.updateAllSvAppFoundDsoConfigs_(
          _.focus(_.initialSynchronizerFeesConfig.minTopupAmount)
            .replace(NonNegativeLong.tryCreate(10_000L))
        )(conf)
      )
      // withStandardSetup turns top-ups on for the decentralized synchronizer. Move alice's and
      // bob's targets onto splitwell instead, so the operator's synchronizer is the only one they
      // top up automatically.
      .addConfigTransform((_, config) =>
        ConfigTransforms.updateAllValidatorConfigs { case (name, validatorConfig) =>
          if (name == "bobValidator")
            validatorConfig
              // Paused throughout, so bob's traffic on splitwell is only what the test buys him.
              .focus(_.automation)
              .modify(_.withPausedTrigger[TopupMemberTrafficTrigger])
          else if (name == "aliceValidator")
            validatorConfig
              .focus(_.domains.global.buyExtraTraffic.targetThroughput)
              .replace(NonNegativeNumeric.tryCreate(BigDecimal(0)))
              .focus(_.domains.extra)
              .modify(_.map { extra =>
                if (extra.alias == splitwellAlias)
                  extra
                    .focus(_.topup.targetThroughput)
                    .replace(NonNegativeNumeric.tryCreate(BigDecimal(100000)))
                    .focus(_.topup.minTopupInterval)
                    .replace(NonNegativeFiniteDuration.ofMinutes(1))
                else extra
              })
              // Resumed only at the end of the first test: a trigger buying on splitwell for the
              // same member would break the exact-equality assertions on the two manual purchases.
              // Alice's and bob's validators only: pausing a trigger an app does not register warns,
              // and the SV validator never registers this one.
              .focus(_.automation)
              .modify(_.withPausedTrigger[TopupMemberTrafficTrigger])
          else validatorConfig
        }(config)
      )
      // Last, so it is a copy of syncOperator as every transform above left it.
      .addConfigTransform { (_, conf) =>
        val allowance = Some(PositiveLong.tryCreate(outageTrafficAllowance))
        val syncOperator = conf.syncOperatorApps(InstanceName.tryCreate("syncOperator"))
        conf.copy(syncOperatorApps =
          conf.syncOperatorApps.updated(
            InstanceName.tryCreate(syncOperatorWithAllowanceName),
            syncOperator.copy(outageTrafficAllowance = allowance),
          )
        )
      }
      // Every app but syncOperatorWithAllowance starts as it would without manual start, and the
      // multi-synchronizer flag that withManualStart's reset drops is set again once they have.
      .withManualStart
      .withAdditionalSetup(implicit env =>
        startAllSync(
          env.amuletNodes.local
            .distinctBy(_.name)
            .filterNot(_.name == syncOperatorWithAllowanceName)*
        )
      )
      .withMultiSyncFeatureFlag()

  "sync operator" should {

    "grant traffic purchased for its synchronizer on its own sequencer" in { implicit env =>
      val operatorParty = syncOperatorBackend.appState.store.key.operatorParty
      val dsoParty = sv1Backend.getDsoInfo().dsoParty
      val dsoRules = sv1Backend.getDsoInfo().dsoRules
      // The operator is pointed at the splitwell sequencer, so that is the synchronizer whose
      // traffic it grants. Alice's participant is a member of it.
      val synchronizerId = aliceValidatorBackend.participantClientWithAdminToken.synchronizers
        .id_of(splitwellAlias)
        .logical
      val member = aliceValidatorBackend.participantClient.id

      clue("the synchronizer runs traffic control with a zero base rate") {
        synchronizerParameters.trafficControl.map(_.maxBaseTrafficAmount.value) shouldBe Some(0L)
      }

      clue("the synchronizer charges traffic the way the global synchronizer does") {
        val participant = aliceValidatorBackend.participantClientWithAdminToken
        val globalSynchronizerId =
          participant.synchronizers.id_of(SynchronizerAlias.tryCreate("global")).logical
        eventually() {
          val global = participant.topology.synchronizer_parameters
            .get_dynamic_synchronizer_parameters(globalSynchronizerId)
            .trafficControl
            .value
          val dedicated = synchronizerParameters.trafficControl.value
          dedicated.readVsWriteScalingFactor shouldBe global.readVsWriteScalingFactor
          dedicated.freeConfirmationResponses shouldBe global.freeConfirmationResponses
        }
      }

      clue("the mediator is granted unlimited traffic") {
        val mediator = syncOperatorBackend.appState.sequencerAdminConnection
          .getMediatorSynchronizerState(
            syncOperatorBackend.appState.store.key.synchronizerId,
            TopologySnapshot.Effective,
          )
          .futureValue
          .mapping
          .active
          .forgetNE
          .loneElement
        eventually() {
          extraTrafficLimit(mediator) shouldBe NonNegativeLong.maxValue.value
        }
      }

      clue("the DSO registers the synchronizer to this operator") {
        sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands
          .submitJava(
            actAs = Seq(dsoParty),
            readAs = Seq(dsoParty),
            commands = dsoRules.contractId
              .exerciseDsoRules_RegisterSynchronizer(
                synchronizerId.toProtoPrimitive,
                operatorParty.toProtoPrimitive,
                new GovernanceParameters(discount.bigDecimal.setScale(10)),
              )
              .commands
              .asScala
              .toSeq,
            userId = sv1Backend.config.ledgerApiUser,
          )
      }

      // Alice's participant hosts neither the DSO nor the operator, so the registration has to
      // be disclosed, and Scan is the only source of its created-event blob.
      sv1ScanBackend.lookupSynchronizerRegistration("dedicated::does-not-exist") shouldBe None
      val registration = eventually() {
        sv1ScanBackend.lookupSynchronizerRegistration(synchronizerId.toProtoPrimitive).value
      }

      clue("the registration carries the discount") {
        BigDecimal(registration.payload.governanceParameters.discountFactor) shouldBe discount
      }

      clue("the validator serves the registration to its wallet clients through the scan proxy") {
        aliceValidatorBackend.scanProxy
          .lookupSynchronizerRegistration(synchronizerId.toProtoPrimitive)
          .value
          .contractId shouldBe registration.contractId
      }

      val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      aliceWalletClient.tap(walletUsdToAmulet(200.0))

      clue("no traffic is granted before any purchase") {
        extraTrafficLimit(member) shouldBe 0L
        // With a zero base rate the member has no allowance at all, so it cannot transact
        // until a purchase is granted.
        trafficState(member).map(_.state.baseTrafficRemainder.value) shouldBe Some(0L)
      }

      val (dedicatedPurchase, _) = actAndCheck(
        "alice buys traffic for the splitwell synchronizer",
        buyTraffic(aliceParty, member, synchronizerId, Some(registration), dsoParty, firstPurchase),
      )(
        "the purchase is granted on the splitwell sequencer",
        _ => extraTrafficLimit(member) shouldBe firstPurchase,
      )

      // Alice's topology broadcasts were bounced by the zero base rate; once granted they go
      // through and consume the purchased traffic.
      clue("the granted traffic is drawn down") {
        eventually() {
          trafficState(member).map(_.extraTrafficConsumed.value).getOrElse(0L) should be > 0L
        }
      }

      clue("the purchase costs the global synchronizer's price at the discount") {
        val globalSynchronizerId =
          aliceValidatorBackend.participantClientWithAdminToken.synchronizers
            .id_of(SynchronizerAlias.tryCreate("global"))
            .logical
        val globalPurchase =
          buyTraffic(aliceParty, member, globalSynchronizerId, None, dsoParty, firstPurchase)
        // Allows for Daml rounding each step to ten decimal places.
        BigDecimal(dedicatedPurchase.amuletPaid) shouldBe
          (BigDecimal(globalPurchase.amuletPaid) * discount +- BigDecimal("0.000001"))
      }

      clue("a purchase for a synchronizer that is neither required nor registered is refused") {
        val unregisteredSynchronizerId =
          SynchronizerId.tryFromString(
            s"unregistered::${synchronizerId.namespace.toProtoPrimitive}"
          )
        assertThrowsAndLogsCommandFailures(
          buyTraffic(aliceParty, member, unregisteredSynchronizerId, None, dsoParty, firstPurchase),
          _.errorMessage should include("Unknown synchronizer provided"),
        )
      }

      actAndCheck(
        "alice buys a second traffic amount",
        buyTraffic(aliceParty, member, synchronizerId, Some(registration), dsoParty, secondPurchase),
      )(
        "the limit rises by exactly the second amount",
        _ => extraTrafficLimit(member) shouldBe (firstPurchase + secondPurchase),
      )

      // The end-user path: the wallet resolves and discloses the registration itself.
      val manualAndWalletPurchases = firstPurchase + secondPurchase + walletRequestPurchase
      actAndCheck(
        "alice requests traffic for splitwell through her wallet",
        aliceWalletClient.createBuyTrafficRequest(
          aliceValidatorBackend.getValidatorPartyId(),
          synchronizerId,
          walletRequestPurchase,
          "splitwell-request",
          env.environment.clock.now.plus(java.time.Duration.ofMinutes(1)),
        ),
      )(
        "the request completes and is granted on the splitwell sequencer",
        _ => {
          inside(aliceWalletClient.getTrafficRequestStatus("splitwell-request")) {
            case d0.GetBuyTrafficRequestStatusResponse.members.BuyTrafficRequestCompletedResponse(
                  d0.BuyTrafficRequestCompletedResponse(status, _)
                ) =>
              status shouldBe TxLogEntry.Http.BuyTrafficRequestStatus.Completed
          }
          extraTrafficLimit(member) shouldBe manualAndWalletPurchases
        },
      )

      // The validator's own top-up trigger does the same buy unattended, on splitwell only:
      // alice's global target is zero, so the fan-out is the only reason it runs at all.
      val topupTrigger = aliceValidatorBackend.appState.automation
        .trigger[TopupMemberTrafficTrigger]
      setTriggersWithin(triggersToResumeAtStart = Seq(topupTrigger)) {
        clue("the trigger tops up splitwell on its own") {
          eventually(2.minutes) {
            extraTrafficLimit(member) should be > manualAndWalletPurchases
          }
        }
        clue("it holds a top-up state for splitwell, which only the fan-out creates") {
          inside(
            listValidatorContracts(ValidatorTopUpState.COMPANION)(
              aliceValidatorBackend,
              _.data.synchronizerId == synchronizerId.toProtoPrimitive,
            )
          ) { case Seq(topupState) =>
            topupState.data.memberId shouldBe member.toProtoPrimitive
          }
        }
      }
    }

    "keep members transacting through a global-synchronizer outage on the operator's allowance" in {
      implicit env =>
        val synchronizerId = syncOperatorBackend.appState.store.key.synchronizerId
        val dsoParty = sv1Backend.getDsoInfo().dsoParty
        // Registered by the test above.
        val registration = eventually() {
          sv1ScanBackend.lookupSynchronizerRegistration(synchronizerId.toProtoPrimitive).value
        }
        val aliceParticipant = aliceValidatorBackend.participantClientWithAdminToken
        val alice = aliceParticipant.id
        val bobParticipant = bobValidatorBackend.participantClientWithAdminToken
        val bob = bobParticipant.id
        // The operator app sees purchases, which are made on the global synchronizer, through
        // this participant.
        val operatorParticipant = splitwellValidatorBackend.participantClientWithAdminToken
        // Not connected to splitwell until it joins during the outage.
        val operator = operatorParticipant.id
        val syncOperatorWithAllowance = syncop(syncOperatorWithAllowanceName)
        def purchased() =
          runningSyncOperatorBackend.appState.store.listTotalPurchasedMemberTraffic().futureValue
        def limitsAreTotalsPlus(allowance: Long, totals: Map[Member, Long]) =
          forAll(totals) { case (member, total) =>
            extraTrafficLimit(member) shouldBe total + allowance
          }

        clue("bob holds no traffic on splitwell yet") {
          extraTrafficLimit(bob) shouldBe 0L
        }

        // Anyone can fund a member's traffic, so alice pays for bob's.
        val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
        aliceWalletClient.tap(walletUsdToAmulet(50.0))
        actAndCheck(
          "alice buys a little traffic for bob on splitwell",
          buyTraffic(aliceParty, bob, synchronizerId, Some(registration), dsoParty, bobPurchase),
        )(
          "the purchase is granted",
          _ => extraTrafficLimit(bob) shouldBe bobPurchase,
        )
        val totals = purchased()
        totals.keySet shouldBe Set(alice, bob)

        try {
          // Suppresses only what is expected while the operator's participant is cut off from the
          // global synchronizer, so the sync operator apps' own warnings and errors still show:
          // the warnings of the operator's validator, whose triggers fail meanwhile, and the
          // errors the test console (created in EnvironmentDefinition) logs for bob's refused
          // pings.
          loggerFactory.suppress(
            (SuppressionRule.LoggerNameContains("validator=splitwellValidator") &&
              SuppressionRule.Level(Level.WARN)) ||
              (SuppressionRule.forLogger[EnvironmentDefinition] &&
                SuppressionRule.Level(Level.ERROR))
          ) {
            // Cut off from the global synchronizer, the operator's participant sees no purchases,
            // which is what an outage looks like to the operator.
            operatorParticipant.synchronizers.disconnect(globalAlias)

            actAndCheck(
              "the operator sets the allowance and restarts its app", {
                syncOperatorBackend.stop()
                syncOperatorWithAllowance.startSync()
              },
            )(
              "each member's limit rises to its purchased total plus the allowance",
              _ => limitsAreTotalsPlus(outageTrafficAllowance, totals),
            )

            clue("a restart with the allowance still set moves no limit") {
              // A grant moves the serial even where it sets the limit the member already has.
              def serials() = totals.keys.map(member => member -> serial(member)).toMap
              val serialsBefore = serials()
              syncOperatorWithAllowance.stop()
              syncOperatorWithAllowance.startSync()
              // Long enough for several of the restarted app's polls.
              val window = 10.seconds
              val pollingInterval = syncOperatorWithAllowance.config.automation.pollingInterval
              pollingInterval.asFiniteApproximation * 3 should be < window
              always(durationOfSuccess = window, pollIntervalMs = 1000) {
                limitsAreTotalsPlus(outageTrafficAllowance, totals)
                serials() shouldBe serialsBefore
              }
            }

            // The most one of bob's pings took his consumed traffic up by.
            val pingCost = clue("bob keeps transacting past what was bought for him") {
              val costs = pingWhile(bobParticipant, synchronizerId)(consumed(bob) <= bobPurchase)
              consumed(bob) should be > bobPurchase
              costs.maxOption.getOrElse(fail("bob never pinged"))
            }

            clue("and is refused once he has used the allowance") {
              def headroom = bobPurchase + outageTrafficAllowance - consumed(bob)
              // A ping can fail for other reasons too, so only a failure that leaves bob less than
              // one ping's worth of traffic shows that he has used the allowance up. A few other
              // failures are tolerated, rather than up to maxPings that can each take a timeout.
              val refused = Iterator
                .continually(Try(ping(bobParticipant, synchronizerId)))
                .take(maxPings)
                .filter(_.isFailure)
                .take(maxRefusals)
                .exists(_ => headroom < pingCost)
              withClue(s"bob has $headroom left, and one ping took up to $pingCost:") {
                refused shouldBe true
              }
            }

            clue("alice runs on what was bought for her, without the allowance") {
              val consumedBefore = consumed(alice)
              ping(aliceParticipant, synchronizerId)
              eventually() {
                consumed(alice) should be > consumedBefore
              }
              consumed(alice) should be <= totals(alice)
            }

            actAndCheck(timeUntilSuccess = 1.minute)(
              "the operator's participant joins splitwell, with no purchase on record",
              operatorParticipant.synchronizers.connect_by_config(
                aliceParticipant.synchronizers
                  .config(splitwellAlias)
                  .value
                  // So that nothing reconnects it once it has left again.
                  .copy(manualConnect = true),
                // Its topology transactions only go through once it has traffic.
                synchronize = None,
              ),
            )(
              "its limit is the allowance alone",
              _ => {
                purchased().keySet should not contain operator
                extraTrafficLimit(operator) shouldBe outageTrafficAllowance
              },
            )
            // It only joined for that check.
            operatorParticipant.synchronizers.disconnect(splitwellAlias)

            operatorParticipant.synchronizers.reconnect(globalAlias)

            actAndCheck(
              "alice buys more traffic for bob once the global synchronizer is back",
              buyTraffic(
                aliceParty,
                bob,
                synchronizerId,
                Some(registration),
                dsoParty,
                bobPurchaseWithAllowanceSet,
              ),
            )(
              "bob's limit rises to his new purchased total plus the allowance",
              _ => {
                purchased()(bob) shouldBe bobPurchase + bobPurchaseWithAllowanceSet
                extraTrafficLimit(bob) shouldBe
                  bobPurchase + bobPurchaseWithAllowanceSet + outageTrafficAllowance
              },
            )

            syncOperatorWithAllowance.stop()
          }
        } finally {
          def connectedTo(alias: SynchronizerAlias) =
            operatorParticipant.synchronizers.list_connected().exists(_.synchronizerAlias == alias)
          if (connectedTo(splitwellAlias))
            operatorParticipant.synchronizers.disconnect(splitwellAlias)
          if (!connectedTo(globalAlias))
            operatorParticipant.synchronizers.reconnect(globalAlias)
        }

        val totalsWithoutAllowance = actAndCheck(
          "the operator restarts its app without the allowance", {
            syncOperatorBackend.startSync()
            purchased()
          },
        )(
          "each member's limit goes back to its purchased total, which is zero for the participant " +
            "that joined during the outage",
          totals => {
            limitsAreTotalsPlus(0L, totals)
            extraTrafficLimit(operator) shouldBe 0L
          },
        )._1

        clue("alice, who did not use the allowance, keeps transacting") {
          ping(aliceParticipant, synchronizerId)
        }

        clue("bob used more than was bought for him, so he is refused until a purchase covers it") {
          consumed(bob) should be > totalsWithoutAllowance(bob)
          assertThrowsAndLogsCommandFailures(
            ping(bobParticipant, synchronizerId),
            _.errorMessage should include("Unable to ping"),
          )
        }

    }
  }

  // Bounds the ping loops, so a ping that costs less than expected fails the test instead of
  // running forever.
  private val maxPings = 300

  // Bounds the failed pings the refusal check looks at.
  private val maxRefusals = 5

  private def ping(participant: ParticipantClientReference, synchronizerId: SynchronizerId) =
    participant.health.ping(participant.id, synchronizerId = Some(synchronizerId))

  /** Pings while `condition` holds, and returns how much each ping took the participant's
    * consumed traffic up by.
    */
  private def pingWhile(participant: ParticipantClientReference, synchronizerId: SynchronizerId)(
      condition: => Boolean
  )(implicit env: SpliceTestConsoleEnvironment): Seq[Long] =
    Iterator
      .continually(())
      .take(maxPings)
      .takeWhile(_ => condition)
      .map { _ =>
        val before = consumed(participant.id)
        ping(participant, synchronizerId)
        consumed(participant.id) - before
      }
      .toSeq

  private def consumed(member: Member)(implicit env: SpliceTestConsoleEnvironment): Long =
    trafficState(member).fold(0L)(_.extraTrafficConsumed.value)

  private def serial(member: Member)(implicit env: SpliceTestConsoleEnvironment) =
    trafficState(member).flatMap(_.state.serial)

  // The sync-operator CI job bootstraps splitwell with traffic control and a zero base rate;
  // see start-canton.sh -t.
  private def synchronizerParameters(implicit env: SpliceTestConsoleEnvironment) =
    syncOperatorBackend.appState.sequencerAdminConnection
      .getSynchronizerParametersState(syncOperatorBackend.appState.store.key.synchronizerId)
      .futureValue
      .mapping
      .parameters

}
