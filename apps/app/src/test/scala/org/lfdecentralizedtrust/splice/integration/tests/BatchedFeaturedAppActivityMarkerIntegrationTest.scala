package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.codegen.java.splice.api.featuredapprightv1
import org.lfdecentralizedtrust.splice.codegen.java.splice.util.featuredapp.batchedmarkersproxy
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithSharedEnvironment
import org.lfdecentralizedtrust.splice.util.*
import scala.jdk.CollectionConverters.*

class BatchedFeaturedAppActivityMarkerIntegrationTest
    extends IntegrationTestWithSharedEnvironment
    with WalletTestUtil
    with TimeTestUtil
    with SplitwellTestUtil
    with TriggerTestUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)

  "Batched activity marker creation produces only one view" in { implicit env =>
    Seq(aliceValidatorBackend, sv1ValidatorBackend, bobValidatorBackend).foreach {
      _.participantClient.upload_dar_unless_exists(
        "daml/splice-util-batched-markers/.daml/dist/splice-util-batched-markers-current.dar"
      )
    }
    val alice = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
    val bob = onboardWalletUser(bobWalletClient, bobValidatorBackend)
    val featuredAppRightCid = aliceWalletClient.selfGrantFeaturedAppRight()
    val cid = aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
      .submitWithResult(
        userId = aliceValidatorBackend.config.ledgerApiUser,
        actAs = Seq(alice),
        readAs = Seq.empty,
        update = new batchedmarkersproxy.BatchedMarkersProxy(
          alice.toProtoPrimitive,
          dsoParty.toProtoPrimitive,
        ).create,
      )
    val (_, verdict) = actAndCheck(
      "Alice creates batched markers",
      aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
        .submitJava(
          actAs = Seq(alice),
          commands = cid.contractId
            .exerciseBatchedMarkersProxy_CreateMarkers(
              featuredAppRightCid.toInterface(featuredapprightv1.FeaturedAppRight.INTERFACE),
              Seq(
                new batchedmarkersproxy.RewardBatch(
                  Seq(
                    new featuredapprightv1.AppRewardBeneficiary(
                      alice.toProtoPrimitive,
                      BigDecimal(0.8).bigDecimal,
                    ),
                    new featuredapprightv1.AppRewardBeneficiary(
                      bob.toProtoPrimitive,
                      BigDecimal(0.2).bigDecimal,
                    ),
                  ).asJava,
                  50,
                ),
                new batchedmarkersproxy.RewardBatch(
                  Seq(
                    new featuredapprightv1.AppRewardBeneficiary(
                      alice.toProtoPrimitive,
                      BigDecimal(1.0).bigDecimal,
                    )
                  ).asJava,
                  50,
                ),
              ).asJava,
            )
            .commands()
            .asScala
            .toSeq,
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
  }
}
