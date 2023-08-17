package com.daml.network.integration.tests

import com.daml.ledger.api.v1.transaction.{TransactionTree, TreeEvent}
import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeIntegrationTestWithSharedEnvironment
import com.daml.network.util.WalletTestUtil
import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.data.CantonTimestamp
import com.daml.network.http.v0.definitions as d0
import com.digitalasset.canton.topology.PartyId
import com.daml.network.integration.tests.CNNodeTests.BracketSynchronous.*
import com.daml.network.codegen.java.cc.api.v1.coin as coinv1

import java.time.Duration

class WalletTransferOfferStatusIntegrationTest
    extends CNNodeIntegrationTestWithSharedEnvironment
    with HasExecutionContext
    with WalletTestUtil
    with WalletTxLogTestUtil {

  override def environmentDefinition: CNNodeEnvironmentDefinition = {
    CNNodeEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .addConfigTransforms(CNNodeConfigTransforms.onlySv1)
  }

  "A wallet transfer offer status" should {

    val trackingId = "mytracking"

    def createTransferOffer(senderParty: PartyId, receiverParty: PartyId)(implicit
        env: CNNodeTests.CNNodeTestConsoleEnvironment
    ) = {
      val (offerCid, _) = actAndCheck(
        "Alice creates transfer offer",
        aliceWalletClient.createTransferOffer(
          receiverParty,
          10.0,
          "created->accepted->completed",
          CantonTimestamp.now().plus(Duration.ofMinutes(1)),
          trackingId,
        ),
      )(
        "Alice sees the transfer offer status as Created",
        offerCid => {
          val response = aliceWalletClient.getTransferOfferStatus(trackingId)
          inside(response) {
            case d0.GetTransferOfferStatusResponse(
                  status,
                  Some(txId),
                  Some(contractId),
                  None,
                  None,
                ) =>
              status should be(d0.GetTransferOfferStatusResponse.Status.Created)
              getRootFromTxId(
                txId,
                Set(senderParty, receiverParty),
              )._2.getExercised.choice should be(
                "WalletAppInstall_CreateTransferOffer"
              )
              contractId should be(offerCid.contractId)
          }
        },
      )
      offerCid
    }

    def getRootFromTxId(txId: String, parties: Set[PartyId])(implicit
        env: CNNodeTests.CNNodeTestConsoleEnvironment
    ): (TransactionTree, TreeEvent) = {
      val txTree = aliceValidatorBackend.participantClientWithAdminToken.ledger_api.transactions
        .by_id(parties, txId)
        .getOrElse(fail("Expected to see the transaction tree in the ledger."))
      val root = txTree.eventsById
        .getOrElse(txTree.rootEventIds.head, fail("Must exist"))
      txTree -> root
    }

    "see created->accepted->completed transfers" in { implicit env =>
      val (aliceUserParty, bobUserParty) = onboardAliceAndBob()

      aliceWalletClient.tap(100.0)

      val offerCid = createTransferOffer(aliceUserParty, bobUserParty)

      // Stop validator so it doesn't immediately move from Accepted to Completed
      // TODO (#6408): replace with stopping and starting AcceptedTransferOfferTrigger
      bracket(aliceValidatorBackend.stop(), aliceValidatorBackend.startSync()) {
        actAndCheck(
          "Bob accepts the transfer offer",
          bobWalletClient.acceptTransferOffer(offerCid),
        )(
          "Bob sees the transfer offer status as Accepted",
          _ => {
            val response = bobWalletClient.getTransferOfferStatus(trackingId)
            inside(response) {
              case d0.GetTransferOfferStatusResponse(
                    status,
                    Some(txId),
                    Some(contractId),
                    None,
                    None,
                  ) =>
                status should be(d0.GetTransferOfferStatusResponse.Status.Accepted)
                val (tree, exercise) = getRootFromTxId(
                  txId,
                  Set(aliceUserParty, bobUserParty),
                )
                exercise.getExercised.choice should be("TransferOffer_Accept")
                val acceptChildren = exercise.getExercised.childEventIds.map(
                  tree.eventsById.getOrElse(_, fail("Must exist"))
                )
                acceptChildren.map(_.getCreated.contractId) should contain(contractId)
            }
          },
        )
      }

      actAndCheck(
        "Automation processes the transfer",
        (),
      )(
        "Bob sees the transfer offer status as Completed",
        _ => {
          val response = bobWalletClient.getTransferOfferStatus(trackingId)
          inside(response) {
            case d0.GetTransferOfferStatusResponse(
                  status,
                  Some(txId),
                  Some(contractId),
                  None,
                  None,
                ) =>
              status should be(d0.GetTransferOfferStatusResponse.Status.Completed)
              val (tree, batchExercise) = getRootFromTxId(
                txId,
                Set(aliceUserParty, bobUserParty),
              )
              batchExercise.getExercised.choice should be("WalletAppInstall_ExecuteBatch")
              tree.eventsById
                .exists(
                  _._2.getExercised.choice == "AcceptedTransferOffer_Complete"
                ) should be(true)
              tree.eventsById.exists { case (_, evt) =>
                evt.getCreated.contractId == contractId && evt.getCreated.getTemplateId.entityName == coinv1.Coin.TEMPLATE_ID.getEntityName
              } should be(true)
          }
        },
      )
    }

    "see created->withdrawn transfers" in { implicit env =>
      val (aliceUserParty, bobUserParty) = onboardAliceAndBob()

      aliceWalletClient.tap(100.0)

      val offerCid = createTransferOffer(aliceUserParty, bobUserParty)

      actAndCheck(
        "Alice withdraws the transfer offer",
        aliceWalletClient.withdrawTransferOffer(offerCid),
      )(
        "Alice sees the transfer offer status as Withdrawn",
        _ => {
          val response = aliceWalletClient.getTransferOfferStatus(trackingId)
          inside(response) {
            case d0.GetTransferOfferStatusResponse(
                  status,
                  None,
                  None,
                  Some(failure),
                  Some(withdrawReason),
                ) =>
              status should be(d0.GetTransferOfferStatusResponse.Status.Failed)
              failure should be(d0.GetTransferOfferStatusResponse.FailureKind.Withdrawn)
              withdrawReason should be("Withdrawn by sender")
          }
        },
      )
    }

    "see created->accepted->withdrawn transfers" in { implicit env =>
      val (aliceUserParty, bobUserParty) = onboardAliceAndBob()

      // no funds:
      // aliceWalletClient.tap(100.0)

      val offerCid = createTransferOffer(aliceUserParty, bobUserParty)

      actAndCheck(
        "Bob accepts the transfer offer",
        bobWalletClient.acceptTransferOffer(offerCid),
      )(
        "Alice sees the transfer offer status as Withdrawn due to no funds",
        _ => {
          val response = aliceWalletClient.getTransferOfferStatus(trackingId)
          inside(response) {
            case d0.GetTransferOfferStatusResponse(
                  status,
                  None,
                  None,
                  Some(failure),
                  Some(withdrawReason),
                ) =>
              status should be(d0.GetTransferOfferStatusResponse.Status.Failed)
              failure should be(d0.GetTransferOfferStatusResponse.FailureKind.Withdrawn)
              withdrawReason should startWith("out of funds")
          }
        },
      )
    }

    "see created->rejected transfers" in { implicit env =>
      val (aliceUserParty, bobUserParty) = onboardAliceAndBob()

      aliceWalletClient.tap(100.0)

      val offerCid = createTransferOffer(aliceUserParty, bobUserParty)

      actAndCheck(
        "Bob rejects the transfer offer",
        bobWalletClient.rejectTransferOffer(offerCid),
      )(
        "Alice sees the transfer offer status as Rejected",
        _ => {
          val response = aliceWalletClient.getTransferOfferStatus(trackingId)
          inside(response) {
            case d0.GetTransferOfferStatusResponse(
                  status,
                  None,
                  None,
                  Some(failure),
                  None,
                ) =>
              status should be(d0.GetTransferOfferStatusResponse.Status.Failed)
              failure should be(d0.GetTransferOfferStatusResponse.FailureKind.Rejected)
          }
        },
      )
    }

    "see created->expired transfers" in { implicit env =>
      val (_, bobUserParty) = onboardAliceAndBob()

      aliceWalletClient.tap(100.0)

      val (_, _) = actAndCheck(
        "Alice creates a transfer offer with a ridiculously low expiry",
        aliceWalletClient.createTransferOffer(
          bobUserParty,
          10.0,
          "created->rejected",
          CantonTimestamp.now().plus(Duration.ofNanos(1)),
          trackingId,
        ),
      )(
        "Alice sees the transfer offer status as Expired",
        _ => {
          val response = aliceWalletClient.getTransferOfferStatus(trackingId)
          inside(response) {
            case d0.GetTransferOfferStatusResponse(status, None, None, Some(failure), None) =>
              status should be(d0.GetTransferOfferStatusResponse.Status.Failed)
              failure should be(d0.GetTransferOfferStatusResponse.FailureKind.Expired)
          }
        },
      )
    }

  }
}
