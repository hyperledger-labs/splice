package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.topology.{ForceFlag, ForceFlags, PartyId}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.{
  featuredapprightv1,
  featuredapprightv2,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.util.featuredapp.batchedmarkersproxy
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.console.ValidatorAppBackendReference
import org.lfdecentralizedtrust.splice.environment.DarResources
import org.lfdecentralizedtrust.splice.http.v0.definitions
import definitions.DamlValueEncoding.members.CompactJson
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.FeaturedAppActivityMarkerTrigger
import org.lfdecentralizedtrust.splice.util.*
import org.lfdecentralizedtrust.splice.validator.automation.ValidatorPackageVettingTrigger
import org.slf4j.event.Level
import scala.jdk.CollectionConverters.*

@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceAmulet_0_1_16
class BatchedFeaturedAppActivityMarkerIntegrationTest
    extends IntegrationTest
    with ScanTestUtil
    with WalletTestUtil
    with TimeTestUtil
    with SplitwellTestUtil
    with TriggerTestUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAutomationConfig(ConfigTransforms.ConfigurableApp.Sv)(
          _.withPausedTrigger[FeaturedAppActivityMarkerTrigger]
        )(config)
      )
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllSvAppConfigs_(
          _.copy( // Set high batch sizes because we really want to test that everything gets submitted in a single batch
            delegatelessAutomationFeaturedAppActivityMarkerCatchupThreshold = 500,
            delegatelessAutomationFeaturedAppActivityMarkerBatchSize = 500,
          )
        )(config)
      )

  def createBatchedMarkers(
      validator: ValidatorAppBackendReference,
      provider: PartyId,
      proxyCid: batchedmarkersproxy.BatchedMarkersProxy.ContractId,
      featuredAppRightCid: amulet.FeaturedAppRight.ContractId,
      batches: Seq[(Seq[(PartyId, Double)], Long)],
  ) = {
    validator.participantClientWithAdminToken.ledger_api_extensions.commands
      .submitJava(
        actAs = Seq(provider),
        commands = proxyCid
          .exerciseBatchedMarkersProxy_CreateMarkers(
            featuredAppRightCid.toInterface(featuredapprightv1.FeaturedAppRight.INTERFACE),
            batches.map { case (beneficiaries, numMarkers) =>
              new batchedmarkersproxy.RewardBatch(
                beneficiaries.map { case (beneficiary, weight) =>
                  new featuredapprightv1.AppRewardBeneficiary(
                    beneficiary.toProtoPrimitive,
                    BigDecimal(weight).bigDecimal,
                  )
                }.asJava,
                numMarkers,
              )
            }.asJava,
          )
          .commands()
          .asScala
          .toSeq,
      )
  }

  def createBatchedMarkersV2(
      validator: ValidatorAppBackendReference,
      provider: PartyId,
      proxyCid: batchedmarkersproxy.BatchedMarkersProxy.ContractId,
      featuredAppRightCid: amulet.FeaturedAppRight.ContractId,
      batches: Seq[(Seq[(PartyId, Double)], BigDecimal)],
  ) = {
    validator.participantClientWithAdminToken.ledger_api_extensions.commands
      .submitJava(
        actAs = Seq(provider),
        commands = proxyCid
          .exerciseBatchedMarkersProxy_CreateMarkersV2(
            featuredAppRightCid.toInterface(featuredapprightv2.FeaturedAppRight.INTERFACE),
            batches.map { case (beneficiaries, markerWeight) =>
              new batchedmarkersproxy.RewardBatchV2(
                beneficiaries.map { case (beneficiary, weight) =>
                  new featuredapprightv2.AppRewardBeneficiary(
                    beneficiary.toProtoPrimitive,
                    BigDecimal(weight).bigDecimal,
                  )
                }.asJava,
                markerWeight.bigDecimal,
              )
            }.asJava,
          )
          .commands()
          .asScala
          .toSeq,
      )
  }

  def createBatchedMarkersProxy(validator: ValidatorAppBackendReference, provider: PartyId)(implicit
      env: SpliceTestConsoleEnvironment
  ) =
    validator.participantClientWithAdminToken.ledger_api_extensions.commands
      .submitWithResult(
        userId = validator.config.ledgerApiUser,
        actAs = Seq(provider),
        readAs = Seq.empty,
        update = new batchedmarkersproxy.BatchedMarkersProxy(
          provider.toProtoPrimitive,
          dsoParty.toProtoPrimitive,
        ).create,
      )

  "Batched activity marker creation produces only one view" in { implicit env =>
    clue("Bob unvets amulet > 0.1.15") {
      bobValidatorBackend.validatorAutomation
        .trigger[ValidatorPackageVettingTrigger]
        .pause()
        .futureValue
      val packagesToUnvet = DarResources.amulet.others.filter(
        _.metadata.version > DarResources.amulet_0_1_15.metadata.version
      )
      bobValidatorBackend.participantClient.topology.vetted_packages.propose_delta(
        bobValidatorBackend.participantClient.id,
        store = decentralizedSynchronizerId,
        removes = packagesToUnvet.map(p =>
          com.digitalasset.daml.lf.data.Ref.PackageId.assertFromString(p.packageId)
        ),
        force = ForceFlags(
          ForceFlag.AllowUnvettedDependencies
        ),
      )
    }

    val alice = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
    val bob = onboardWalletUser(bobWalletClient, bobValidatorBackend)
    val splitwell = onboardWalletUser(splitwellWalletClient, splitwellValidatorBackend)
    val aliceFeaturedAppRightCid = aliceWalletClient.selfGrantFeaturedAppRight()
    val splitwellFeaturedAppRightCid = splitwellWalletClient.selfGrantFeaturedAppRight()
    val aliceProxy = createBatchedMarkersProxy(aliceValidatorBackend, alice)
    val splitwellProxy = createBatchedMarkersProxy(splitwellValidatorBackend, splitwell)
    val (_, verdict) = actAndCheck(
      "Alice creates batched markers",
      createBatchedMarkers(
        aliceValidatorBackend,
        alice,
        aliceProxy.contractId,
        aliceFeaturedAppRightCid,
        Seq((Seq((alice, 0.8), (bob, 0.2)), 50), (Seq((alice, 1.0)), 50)),
      ),
    )(
      "Verdict for tx is observable in scan",
      tx => {
        sv1ScanBackend.getEventById(tx.getUpdateId, None).value.verdict.value
      },
    )
    val view = verdict.transactionViews.views.loneElement
    view.informees should contain theSameElementsAs Seq(
      aliceValidatorBackend.participantClient.id.uid.toProtoPrimitive,
      alice.toProtoPrimitive,
      bob.toProtoPrimitive,
      dsoParty.toProtoPrimitive,
    ) withClue "informees"

    actAndCheck(
      "Splitwell creates batched marker through the v2 choice", {
        createBatchedMarkers(
          splitwellValidatorBackend,
          splitwell,
          splitwellProxy.contractId,
          splitwellFeaturedAppRightCid,
          Seq((Seq((splitwell, 1.0)), 50)),
        )
        createBatchedMarkersV2(
          splitwellValidatorBackend,
          splitwell,
          splitwellProxy.contractId,
          splitwellFeaturedAppRightCid,
          Seq((Seq((splitwell, 1.0)), 50)),
        )
      },
    )(
      "sv1 observes 201 total markers",
      _ =>
        // The first call prdouces 50 for alice with weight 0.8, 50 for bob with weight 0.2 and 50 for alice with weight 1.0
        // The second produces 50 for splitwell with weight 50.
        // The third one produces one marker with weight 1.
        sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(amulet.FeaturedAppActivityMarker.COMPANION)(
            dsoParty
          ) should have size 201 withClue "FeaturedAppActivityMarkers",
    )

    val scanCursorBeforeConversion = latestEventHistoryCursor(sv1ScanBackend)

    loggerFactory.assertLogsSeq(
      SuppressionRule.LevelAndAbove(Level.INFO) && SuppressionRule.LoggerNameContains(
        "FeaturedAppActivityMarkerTrigger"
      )
    )(
      actAndCheck(
        "Resume the featured app marker conversion",
        sv1Backend.dsoDelegateBasedAutomation.trigger[FeaturedAppActivityMarkerTrigger].resume(),
      )(
        "Check that the conversion produced only a single view except for bob who has not vetted the view reduction",
        _ => {
          sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
            .filterJava(amulet.FeaturedAppActivityMarker.COMPANION)(
              dsoParty
            ) shouldBe empty withClue "FeaturedAppActivityMarkers"
          val events =
            sv1ScanBackend.getEventHistory(1000, Some(scanCursorBeforeConversion), CompactJson)
          // Note: There may actually be multiple batches in the trigger because we can't easily synchronize on it having ingested all the markers.
          // We don't care all that much about whether the trigger needs to run multiple time for this test.
          val conversionEvents = events.filter {
            _.update match {
              case Some(definitions.UpdateHistoryItemV2.members.UpdateHistoryTransactionV2(tx)) =>
                tx.eventsById(tx.rootEventIds.head) match {
                  case definitions.TreeEvent.members.ExercisedEvent(exercised) =>
                    exercised.choice == "DsoRules_AmuletRules_ConvertFeaturedAppActivityMarkers"
                  case _ => false
                }
              case _ => false
            }
          }
          // Check that we actually got some so the forAll does not become redundant
          withClue(s"Unfiltered events: $events") {
            conversionEvents should not be empty withClue "ConvertFeaturedAppActivityMarkers txs"
          }
          // Check that each conversion not including bob only produces 2 views, one for the SV and one for validator
          forAll(conversionEvents) { event =>
            val views = event.verdict.value.transactionViews.views
            if (!views.exists(_.informees.contains(bob.toProtoPrimitive))) {
              views should have size (2) withClue "conversionEvent views"
              forExactly(1, views) { view =>
                view.informees should contain theSameElementsAs Seq(
                  dsoParty.toProtoPrimitive,
                  sv1Backend.getDsoInfo().svParty.toProtoPrimitive,
                  sv1Backend.participantClient.id.uid.toProtoPrimitive,
                ) withClue "informees"
              }
              forExactly(1, views) { view =>
                view.informees should contain atLeastOneOf (alice.toProtoPrimitive, bob.toProtoPrimitive) withClue "informees"
              }
            }
          }
        },
      ),
      entries => {
        forExactly(1, entries) { line =>
          line.message should (include("vettedAmuletVersion = 0.1.15") and include("Processing"))
        }
        val currentAmuletVersion = DarResources.amulet.latest.metadata.version

        forAtLeast(1, entries) { line =>
          line.message should (include(s"vettedAmuletVersion = $currentAmuletVersion") and include(
            "Processing"
          ))
        }
      },
    )
  }
}
