package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletconfig.{
  AmuletConfig,
  TransferConfig,
  USD,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.AmuletRules_AddFutureAmuletConfigSchedule
import org.lfdecentralizedtrust.splice.codegen.java.splice.types.Round
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.{
  ARC_AmuletRules,
  ARC_DsoRules,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.amuletrules_actionrequiringconfirmation.CRARC_AddFutureAmuletConfigSchedule
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.{
  SRARC_AddSv,
  SRARC_OffboardSv,
  SRARC_SetConfig,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.voterequestoutcome.{
  VRO_Accepted,
  VRO_Expired,
  VRO_Rejected,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{
  ActionRequiringConfirmation,
  DsoRulesConfig,
  DsoRules_AddSv,
  DsoRules_OffboardSv,
  DsoRules_SetConfig,
}
import org.lfdecentralizedtrust.splice.codegen.java.da.time.types.RelTime
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.SpliceTestConsoleEnvironment
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.CloseVoteRequestTrigger
import org.lfdecentralizedtrust.splice.sv.config.SvOnboardingConfig.InitialPackageConfig
import org.lfdecentralizedtrust.splice.util.{Codec, TriggerTestUtil}

import java.time.Instant
import java.util.Optional
import scala.collection.parallel.CollectionConverters.seqIsParallelizable
import scala.jdk.CollectionConverters.MapHasAsScala
import scala.jdk.OptionConverters.*

//TODO(#925): adapt this test to work only with SetConfig
class SvStateManagementIntegrationTest extends SvIntegrationTestBase with TriggerTestUtil {

  // TODO(#925): change tests to work with current version
  private val initialPackageConfig = InitialPackageConfig(
    amuletVersion = "0.1.7",
    amuletNameServiceVersion = "0.1.7",
    dsoGovernanceVersion = "0.1.10",
    validatorLifecycleVersion = "0.1.1",
    walletVersion = "0.1.7",
    walletPaymentsVersion = "0.1.7",
  )
  // TODO(#925): when using the latest version, this can be removed
  override protected def runTokenStandardCliSanityCheck: Boolean = false

  override def environmentDefinition: EnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .withManualStart
      .withNoVettedPackages(implicit env => Seq(sv1Backend.participantClient))
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllSvAppFoundDsoConfigs_(
          _.copy(initialPackageConfig = initialPackageConfig)
        )(config)
      )

  private def actionRequiring3VotesForEarlyClosing(sv: String) = new ARC_DsoRules(
    new SRARC_OffboardSv(
      new DsoRules_OffboardSv(
        sv
      )
    )
  )

  private def actionRequiring4VotesForEarlyClosing() = new ARC_DsoRules(
    new SRARC_AddSv(
      new DsoRules_AddSv(
        "alice:1234",
        "Alice",
        1234L,
        "alice-participant-id",
        new Round(42),
      )
    )
  )

  "SVs can create a VoteRequest, vote on it and list them." in { implicit env =>
    initDso()
    val (_, voteRequest) = actAndCheck(
      "sv1 creates a vote request",
      sv1Backend.createVoteRequest(
        sv1Backend.getDsoInfo().svParty.toProtoPrimitive,
        actionRequiring3VotesForEarlyClosing(sv4Backend.getDsoInfo().svParty.toProtoPrimitive),
        "url",
        "remove sv4",
        sv1Backend.getDsoInfo().dsoRules.payload.config.voteRequestTimeout,
        None,
      ),
    )(
      "vote request has been created",
      _ => {
        val voteRequest = sv1Backend.listVoteRequests().loneElement
        sv1Backend.lookupVoteRequest(voteRequest.contractId) shouldBe voteRequest
        voteRequest
      },
    )
    actAndCheck(
      "sv1 updates his vote, sv2, sv3 and sv4 reject the vote request",
      Seq(sv1Backend, sv2Backend, sv3Backend, sv4Backend).foreach { sv =>
        sv.castVote(
          voteRequest.contractId,
          false,
          "url",
          "description",
        )
      },
    )(
      "vote request has been rejected because the majority of the votes are negative",
      _ => {
        sv1Backend.listVoteRequests() shouldBe empty

        sv1Backend
          .listVoteRequestResults(None, Some(false), None, None, None, 1)
          .loneElement
          .outcome shouldBe a[VRO_Rejected]
      },
    )
  }

  "VoteRequest expires with no definitive outcome." in { implicit env =>
    initDso()
    val (_, voteRequest) = actAndCheck(
      "sv1 creates a vote request that expires directly",
      sv1Backend.createVoteRequest(
        sv1Backend.getDsoInfo().svParty.toProtoPrimitive,
        actionRequiring3VotesForEarlyClosing(sv4Backend.getDsoInfo().svParty.toProtoPrimitive),
        "url",
        "remove sv4",
        new RelTime(10_000_000L),
        None,
      ),
    )(
      "vote request has been created",
      _ => {
        sv1Backend.listVoteRequests().loneElement
      },
    )
    actAndCheck(
      "A number of SVs less than the required number of voters cast a vote",
      Seq(sv1Backend, sv2Backend).par.foreach { sv =>
        sv.castVote(
          voteRequest.contractId,
          true,
          "url",
          "description",
        )
      },
    )(
      "vote request has expired",
      _ => {
        sv1Backend.listVoteRequests() shouldBe empty
        sv1Backend
          .listVoteRequestResults(None, Some(false), None, None, None, 1)
          .loneElement
          .outcome shouldBe a[VRO_Expired]
      },
    )
  }

  "VoteRequest expires with a definitive outcome." in { implicit env =>
    initDso()
    val (_, voteRequest) = actAndCheck(
      "sv1 creates a vote request that expires directly",
      sv1Backend.createVoteRequest(
        sv1Backend.getDsoInfo().svParty.toProtoPrimitive,
        actionRequiring4VotesForEarlyClosing(),
        "url",
        "add new sv",
        new RelTime(10_000_000L),
        None,
      ),
    )(
      "vote request has been created",
      _ => {
        sv1Backend.listVoteRequests().loneElement
      },
    )
    actAndCheck(
      "A number of SVs less than the required number of voters cast a vote",
      Seq(sv1Backend, sv2Backend, sv3Backend).par.foreach { sv =>
        sv.castVote(
          voteRequest.contractId,
          false,
          "url",
          "description",
        )
      },
    )(
      "vote request was rejected",
      _ => {
        sv1Backend.listVoteRequests() shouldBe empty
        sv1Backend
          .listVoteRequestResults(None, Some(false), None, None, None, 1)
          .loneElement
          .outcome shouldBe a[VRO_Rejected]
      },
    )
  }

  "SVs can update their AmuletPriceVote contracts" in { implicit env =>
    initDso()
    val svParties =
      Seq(("sv1", sv1Backend), ("sv2", sv2Backend), ("sv3", sv3Backend), ("sv4", sv4Backend)).map {
        case (svName, sv) => svName -> sv.getDsoInfo().svParty
      }.toMap

    clue("initially only sv1 and sv2 have set the AmuletPriceVote") {
      // sv1 because it initialized the DSO and sv2 because we configured it to do so
      eventually() {
        getAmuletPriceVoteMap() shouldBe Map(
          svParties("sv1") -> Seq(Some(BigDecimal(0.005))),
          svParties("sv2") -> Seq(Some(BigDecimal(0.005))),
          svParties("sv3") -> Seq(None),
          svParties("sv4") -> Seq(None),
        )
      }
    }

    actAndCheck(
      "set AmuletPriceVote of sv2, sv3 and sv4", {
        sv2Backend.updateAmuletPriceVote(BigDecimal(4.0))
        sv3Backend.updateAmuletPriceVote(BigDecimal(3.0))
        sv4Backend.updateAmuletPriceVote(BigDecimal(2.0))
      },
    )(
      "AmuletPriceVote contract for sv2, sv3 anc sv4 are updated",
      _ => {
        getAmuletPriceVoteMap() shouldBe Map(
          svParties("sv1") -> Seq(Some(BigDecimal(0.005))),
          svParties("sv2") -> Seq(Some(BigDecimal(4.0))),
          svParties("sv3") -> Seq(Some(BigDecimal(3.0))),
          svParties("sv4") -> Seq(Some(BigDecimal(2.0))),
        )
      },
    )

    actAndCheck(
      "update AmuletPriceVote of sv1", {
        sv1Backend.updateAmuletPriceVote(BigDecimal(5.0))
      },
    )(
      "AmuletPriceVote contract for sv1 are updated",
      _ => {
        getAmuletPriceVoteMap() shouldBe Map(
          svParties("sv1") -> Seq(Some(BigDecimal(5.0))),
          svParties("sv2") -> Seq(Some(BigDecimal(4.0))),
          svParties("sv3") -> Seq(Some(BigDecimal(3.0))),
          svParties("sv4") -> Seq(Some(BigDecimal(2.0))),
        )
      },
    )

    actAndCheck(
      "restarting all SVs", {
        svs.foreach(_.stop())
        startAllSync(svs*)
      },
    )(
      "AmuletPriceVote contracts didn't change",
      _ => {
        getAmuletPriceVoteMap() shouldBe Map(
          svParties("sv1") -> Seq(Some(BigDecimal(5.0))),
          svParties("sv2") -> Seq(Some(BigDecimal(4.0))),
          svParties("sv3") -> Seq(Some(BigDecimal(3.0))),
          svParties("sv4") -> Seq(Some(BigDecimal(2.0))),
        )
      },
    )
  }

  "archive duplicated and non-sv AmuletPriceVote contracts" in { implicit env =>
    initDso()
    val svParties =
      Seq(("sv1", sv1Backend), ("sv2", sv2Backend), ("sv3", sv3Backend), ("sv4", sv4Backend)).map {
        case (svName, sv) => svName -> sv.getDsoInfo().svParty
      }.toMap

    eventually() {
      getAmuletPriceVoteMap() shouldBe Map(
        svParties("sv1") -> Seq(Some(BigDecimal(0.005))),
        svParties("sv2") -> Seq(Some(BigDecimal(0.005))),
        svParties("sv3") -> Seq(None),
        svParties("sv4") -> Seq(None),
      )
    }

    actAndCheck(
      "remove sv3 on dsoRules contract to trigger `GarbageCollectAmuletPriceVotesTrigger` to non sv votes", {
        val removeAction = new ARC_DsoRules(
          new SRARC_OffboardSv(
            new DsoRules_OffboardSv(
              svParties("sv3").toProtoPrimitive
            )
          )
        )

        val (_, voteRequest) = actAndCheck(
          "Creating vote request",
          eventuallySucceeds() {
            sv1Backend.createVoteRequest(
              sv1Backend.getDsoInfo().svParty.toProtoPrimitive,
              removeAction,
              "url",
              "remove sv3",
              sv1Backend.getDsoInfo().dsoRules.payload.config.voteRequestTimeout,
              None,
            )
          },
        )("vote request has been created", _ => sv1Backend.listVoteRequests().loneElement)

        setTriggersWithin(
          // Pause so SV3 can be stopped before it gets offboarded
          triggersToPauseAtStart =
            activeSvs.map(_.dsoDelegateBasedAutomation.trigger[CloseVoteRequestTrigger])
        ) {
          // We need SV3's vote here for immediate offboarding
          Seq(sv2Backend, sv3Backend, sv4Backend).foreach { sv =>
            clue(s"${sv.name} accepts vote") {
              getTrackingId(voteRequest) shouldBe voteRequest.contractId
              eventuallySucceeds() {
                sv.castVote(
                  voteRequest.contractId,
                  true,
                  "url",
                  "description",
                )
              }
            }
          }
          // Stop SV3 to make sure it does not produce
          // TOPOLOGY_UNAUTHORIZED_TRANSACTION warnings, see #11639.
          sv3Backend.stop()
        }
      },
    )(
      "vote of sv3 is removed and sv3 is removed from decentralized namespace",
      _ => {
        getAmuletPriceVoteMap() shouldBe Map(
          svParties("sv1") -> Seq(Some(BigDecimal(0.005))),
          svParties("sv2") -> Seq(Some(BigDecimal(0.005))),
          svParties("sv4") -> Seq(None),
        )
        // Wait for the decentralized namespace change to avoid triggering in TOPOLOGY_UNAUTHORIZED_TRANSACTION
        sv1Backend.participantClient.topology.decentralized_namespaces
          .list(
            store = TopologyStoreId.Synchronizer(decentralizedSynchronizerId),
            filterNamespace = dsoParty.uid.namespace.toProtoPrimitive,
          )
          .loneElement
          .item
          .owners
          .forgetNE should have size (3)
      },
    )
  }

  "At least 3 SVs can vote on changing the DsoRules Configuration" in { implicit env =>
    val newNumUnclaimedRewardsThreshold = 42

    clue("Initialize DSO with 4 SVs") {
      initDso()
      eventually() {
        sv1Backend.getDsoInfo().dsoRules.payload.svs should have size 4
      }
    }

    val (_, (voteRequestCid, initialNumUnclaimedRewardsThreshold)) = actAndCheck(
      "SV1 create a vote request for a new DsoRules Configuration", {
        val newConfig = new DsoRulesConfig(
          newNumUnclaimedRewardsThreshold,
          sv1Backend.getDsoInfo().dsoRules.payload.config.numMemberTrafficContractsThreshold,
          sv1Backend.getDsoInfo().dsoRules.payload.config.actionConfirmationTimeout,
          sv1Backend.getDsoInfo().dsoRules.payload.config.svOnboardingRequestTimeout,
          sv1Backend.getDsoInfo().dsoRules.payload.config.svOnboardingConfirmedTimeout,
          sv1Backend.getDsoInfo().dsoRules.payload.config.voteRequestTimeout,
          sv1Backend.getDsoInfo().dsoRules.payload.config.dsoDelegateInactiveTimeout,
          sv1Backend.getDsoInfo().dsoRules.payload.config.synchronizerNodeConfigLimits,
          sv1Backend.getDsoInfo().dsoRules.payload.config.maxTextLength,
          sv1Backend.getDsoInfo().dsoRules.payload.config.decentralizedSynchronizer,
          sv1Backend.getDsoInfo().dsoRules.payload.config.nextScheduledSynchronizerUpgrade,
        )

        val action: ActionRequiringConfirmation =
          new ARC_DsoRules(new SRARC_SetConfig(new DsoRules_SetConfig(newConfig, Optional.empty())))

        sv1Backend.createVoteRequest(
          sv1Backend.getDsoInfo().svParty.toProtoPrimitive,
          action,
          "url",
          "description",
          sv1Backend.getDsoInfo().dsoRules.payload.config.voteRequestTimeout,
          None,
        )
      },
    )(
      "The vote request has been created, SV1 accepts as he created it and all other SVs observe it",
      _ => {
        svs.foreach { sv => sv.listVoteRequests() should not be empty }
        val head = sv1Backend.listVoteRequests().headOption.value.contractId
        sv1Backend.lookupVoteRequest(head).payload.votes should have size 1
        (head, sv1Backend.getDsoInfo().dsoRules.payload.config.numUnclaimedRewardsThreshold)
      },
    )

    actAndCheck(
      "SV2 votes on accepting the new configuration", {
        sv2Backend.castVote(voteRequestCid, true, "url", "description")
      },
    )(
      "The majority did not vote yet, thus the trigger should not change the dsoRules",
      _ => {
        sv2Backend
          .getDsoInfo()
          .dsoRules
          .payload
          .config
          .numUnclaimedRewardsThreshold shouldBe initialNumUnclaimedRewardsThreshold
      },
    )

    actAndCheck(
      "SV3 refuses the new configuration", {
        sv3Backend.castVote(voteRequestCid, false, "url", "description")
      },
    )(
      "The majority has voted but without an acceptance majority, the trigger should not change the dsoRules",
      _ => {
        sv3Backend
          .getDsoInfo()
          .dsoRules
          .payload
          .config
          .numUnclaimedRewardsThreshold shouldBe initialNumUnclaimedRewardsThreshold
      },
    )

    actAndCheck(
      "SV4 votes on accepting the new configuration", {
        sv4Backend.castVote(voteRequestCid, true, "url", "description")
      },
    )(
      "The majority accepts, the trigger should change the dsoRules accordingly",
      _ => {
        sv4Backend
          .getDsoInfo()
          .dsoRules
          .payload
          .config
          .numUnclaimedRewardsThreshold shouldBe newNumUnclaimedRewardsThreshold
      },
    )
  }

  "At least 3 SVs can vote on changing the Amulet Configuration" in { implicit env =>
    clue("Initialize DSO with 4 SVs") {
      initDso()
      eventually() {
        sv1Backend.getDsoInfo().dsoRules.payload.svs should have size 4
      }
    }

    val (_, (voteRequestCid, initialFutureValuesSize)) = actAndCheck(
      "SV1 create a vote request for a new Amulet Configuration (changing the transfer config)", {

        val initialValue = sv1Backend.getDsoInfo().amuletRules.payload.configSchedule.initialValue
        val transferConfig = initialValue.transferConfig
        val newTransferConfig = new TransferConfig[USD](
          transferConfig.createFee,
          transferConfig.holdingFee,
          transferConfig.transferFee,
          transferConfig.lockHolderFee,
          transferConfig.extraFeaturedAppRewardAmount,
          42,
          42,
          42,
        )

        val futureValue =
          new org.lfdecentralizedtrust.splice.codegen.java.da.types.Tuple2[Instant, AmuletConfig[
            USD
          ]](
            Instant.now().plusSeconds(3600),
            new AmuletConfig[USD](
              newTransferConfig,
              initialValue.issuanceCurve,
              initialValue.decentralizedSynchronizer,
              initialValue.tickDuration,
              initialValue.packageConfig,
              java.util.Optional.empty(),
              java.util.Optional.empty(),
            ),
          )

        val action: ActionRequiringConfirmation =
          new ARC_AmuletRules(
            new CRARC_AddFutureAmuletConfigSchedule(
              new AmuletRules_AddFutureAmuletConfigSchedule(futureValue)
            )
          )

        sv1Backend.createVoteRequest(
          sv1Backend.getDsoInfo().svParty.toProtoPrimitive,
          action,
          "url",
          "description",
          sv1Backend.getDsoInfo().dsoRules.payload.config.voteRequestTimeout,
          None,
        )
      },
    )(
      "The vote request has been created and SV1 accepts as he created it",
      _ => {
        svs.foreach { sv => sv.listVoteRequests() should not be empty }
        val head = sv1Backend.listVoteRequests().headOption.value.contractId
        sv1Backend.lookupVoteRequest(head).payload.votes should have size 1
        (head, sv1Backend.getDsoInfo().amuletRules.payload.configSchedule.futureValues.size())
      },
    )

    actAndCheck(
      "SV2 votes on accepting the new configuration", {
        sv2Backend.castVote(voteRequestCid, true, "url", "description")
      },
    )(
      "The majority did not vote yet, thus the trigger should not change the amulet config futureValues",
      _ => {
        sv2Backend
          .getDsoInfo()
          .amuletRules
          .payload
          .configSchedule
          .futureValues
          .size() shouldBe initialFutureValuesSize
      },
    )

    actAndCheck(
      "SV3 refuses the new configuration", {
        sv3Backend.castVote(voteRequestCid, false, "url", "description")
      },
    )(
      "The majority has voted but without an acceptance majority, the trigger should not change the amulet config futureValues",
      _ => {
        sv3Backend
          .getDsoInfo()
          .amuletRules
          .payload
          .configSchedule
          .futureValues
          .size() shouldBe initialFutureValuesSize
      },
    )

    actAndCheck(
      "SV4 votes on accepting the new configuration", {
        sv4Backend.castVote(voteRequestCid, true, "url", "description")
      },
    )(
      "The majority accepts, the trigger should change the amulet config futureValues",
      _ => {
        sv4Backend
          .getDsoInfo()
          .amuletRules
          .payload
          .configSchedule
          .futureValues
          .size() shouldBe initialFutureValuesSize + 1
      },
    )

    clue("We should be able to query vote requests that have been accepted") {
      eventually() {
        val voteResult =
          sv1Backend
            .listVoteRequestResults(None, Some(true), None, None, None, 1)
            .headOption
            .value
        voteResult.outcome shouldBe a[VRO_Accepted]
        voteResult.request.votes.asScala.values
          .filter(_.accept)
          .map(_.sv) should contain theSameElementsAs Seq(sv1Backend, sv2Backend, sv4Backend).map(
          _.getDsoInfo().svParty.toProtoPrimitive
        )
      }
    }

  }

  "Vote requests expire" in { implicit env =>
    clue("Initialize DSO with 2 SVs") {
      startAllSync(
        sv1Backend,
        sv2Backend,
      )
      eventually() {
        sv1Backend.getDsoInfo().dsoRules.payload.svs should have size 2
      }
    }
    clue("Pausing vote request expiration automation") {
      Seq(sv1Backend, sv2Backend).foreach(
        _.dsoDelegateBasedAutomation.trigger[CloseVoteRequestTrigger].pause().futureValue
      )
    }
    actAndCheck(
      "SV2 creates a vote request for removing SV1", {
        val sv1Party = sv1Backend.getDsoInfo().svParty
        val sv2Party = sv2Backend.getDsoInfo().svParty
        val action: ActionRequiringConfirmation = new ARC_DsoRules(
          new SRARC_OffboardSv(new DsoRules_OffboardSv(sv1Party.toProtoPrimitive))
        )

        sv2Backend.createVoteRequest(
          sv2Party.toProtoPrimitive,
          action,
          "url",
          "description",
          // expire in 5 seconds
          new RelTime(5_000_000L),
          None,
        )
      },
    )(
      "The vote request has been created and all SVs observe it",
      _ => {
        sv1Backend.listVoteRequests() should have size 1
        sv2Backend.listVoteRequests() should have size 1
      },
    )
    clue("Resuming vote request expiration automation") {
      Seq(sv1Backend, sv2Backend).foreach(
        _.dsoDelegateBasedAutomation.trigger[CloseVoteRequestTrigger].resume()
      )
    }
    clue("Eventually the vote request expires and gets archived") {
      eventually() {
        sv1Backend.listVoteRequests() shouldBe empty
        sv2Backend.listVoteRequests() shouldBe empty
      }
    }
  }

  private def getAmuletPriceVoteMap()(implicit env: SpliceTestConsoleEnvironment) =
    sv1Backend
      .listAmuletPriceVotes()
      .groupBy(_.payload.sv)
      .flatMap { case (sv, contracts) =>
        Codec
          .decode(Codec.Party)(sv)
          .map(p =>
            p -> contracts.map(
              _.payload.amuletPrice.toScala.map(BigDecimal(_))
            )
          )
          .toOption
      }

}
