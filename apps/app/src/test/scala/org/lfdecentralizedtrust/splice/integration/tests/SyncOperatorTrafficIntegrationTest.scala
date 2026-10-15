// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.SynchronizerAlias
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.config.RequireTypes.{NonNegativeLong, NonNegativeNumeric}
import com.digitalasset.canton.topology.SynchronizerId
import monocle.macros.syntax.lens.*
import org.lfdecentralizedtrust.splice.codegen.java.splice.decentralizedsynchronizer.GovernanceParameters
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.topupstate.ValidatorTopUpState
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

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Buys traffic for a registered synchronizer via `AmuletRules_BuyMemberTraffic` with the
  * registration disclosed, and checks the operator grants it on that synchronizer's sequencer.
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

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .fromResources(
        Seq("simple-topology-1sv.conf", "sync-operator-topology.conf"),
        this.getClass.getSimpleName,
      )
      .withOnlyAliceValidatorConnectingToSplitwell
      .withStandardSetup
      // withStandardSetup turns top-ups on for the decentralized synchronizer. Move alice's
      // target onto splitwell instead, so the operator's synchronizer is the only one she tops
      // up automatically.
      .addConfigTransform((_, config) =>
        ConfigTransforms.updateAllValidatorConfigs { case (name, validatorConfig) =>
          if (name == "aliceValidator")
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
              // Resumed only at the end of the test: a trigger buying on splitwell for the same
              // member would break the exact-equality assertions on the two manual purchases.
              // Alice's validator only: pausing a trigger an app does not register warns, and the
              // SV validator never registers this one.
              .focus(_.automation)
              .modify(_.withPausedTrigger[TopupMemberTrafficTrigger])
          else validatorConfig
        }(config)
      )

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
  }

  // The sync-operator CI job bootstraps splitwell with traffic control and a zero base rate;
  // see start-canton.sh -t.
  private def synchronizerParameters(implicit env: SpliceTestConsoleEnvironment) =
    syncOperatorBackend.appState.sequencerAdminConnection
      .getSynchronizerParametersState(syncOperatorBackend.appState.store.key.synchronizerId)
      .futureValue
      .mapping
      .parameters

}
