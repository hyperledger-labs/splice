package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.featuredapprightv1
import org.lfdecentralizedtrust.splice.codegen.java.splice.util.featuredapp.batchedmarkersproxy
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.console.ValidatorAppBackendReference
import org.lfdecentralizedtrust.splice.http.v0.definitions
import definitions.DamlValueEncoding.members.CompactJson
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTestWithSharedEnvironment,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.FeaturedAppActivityMarkerTrigger
import org.lfdecentralizedtrust.splice.util.*
import scala.jdk.CollectionConverters.*
import com.digitalasset.canton.topology.PartyId

@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceAmulet_0_1_16
class BatchedFeaturedAppActivityMarkerIntegrationTest
    extends IntegrationTestWithSharedEnvironment
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
    Seq(aliceValidatorBackend, sv1ValidatorBackend, bobValidatorBackend, splitwellValidatorBackend)
      .foreach {
        _.participantClient.upload_dar_unless_exists(
          "daml/splice-util-batched-markers/.daml/dist/splice-util-batched-markers-current.dar"
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
    )
    actAndCheck(
      "Splitwell creates batched marker",
      createBatchedMarkers(
        splitwellValidatorBackend,
        splitwell,
        splitwellProxy.contractId,
        splitwellFeaturedAppRightCid,
        Seq((Seq((splitwell, 1.0)), 50)),
      ),
    )(
      "sv1 observes 200 total markers",
      _ =>
        // The first call prdouces 50 for alice with weight 0.8, 50 for bob with weight 0.2 and 50 for alice with weight 1.0
        // The second produces 50 for splitwell with weight 50
        sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(amulet.FeaturedAppActivityMarker.COMPANION)(dsoParty) should have size 200,
    )

    val scanCursorBeforeConversion = latestEventHistoryCursor(sv1ScanBackend)

    actAndCheck(
      "Resume the featured app marker conversion",
      sv1Backend.dsoDelegateBasedAutomation.trigger[FeaturedAppActivityMarkerTrigger].resume(),
    )(
      "Check that the conversion produced only a single view",
      _ => {
        sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(amulet.FeaturedAppActivityMarker.COMPANION)(dsoParty) shouldBe empty
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
          conversionEvents should not be empty
        }
        // Check that each conversion only produces 2 views, one for the SV and one for validator
        forAll(conversionEvents) { event =>
          val views = event.verdict.value.transactionViews.views
          views should have size (2)
          forExactly(1, views) { view =>
            view.informees should contain theSameElementsAs Seq(
              dsoParty.toProtoPrimitive,
              sv1Backend.getDsoInfo().svParty.toProtoPrimitive,
              sv1Backend.participantClient.id.uid.toProtoPrimitive,
            )
          }
          forExactly(1, views) { view =>
            view.informees should contain atLeastOneOf (alice.toProtoPrimitive, bob.toProtoPrimitive)
          }
        }
      },
    )
  }
}
