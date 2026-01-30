package org.lfdecentralizedtrust.splice.integration.tests

import com.daml.ledger.api.v2.event.CreatedEvent.toJavaProto
import com.daml.ledger.api.v2.event.{Event, ExercisedEvent}
import com.daml.ledger.api.v2.transaction.Transaction
import com.daml.ledger.api.v2.transaction_filter
import com.daml.ledger.api.v2.transaction_filter.{
  CumulativeFilter,
  EventFormat,
  Filters,
  TransactionFormat as TransactionFormatProto,
  UpdateFormat,
}
import com.daml.ledger.api.v2.transaction_filter.CumulativeFilter.IdentifierFilter
import com.daml.ledger.javaapi.data
import com.daml.ledger.javaapi.data.CreatedEvent
import org.lfdecentralizedtrust.splice.history.AmuletCreate
import org.lfdecentralizedtrust.splice.http.v0.definitions as d0
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.BracketSynchronous.*
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithSharedEnvironment
import org.lfdecentralizedtrust.splice.util.WalletTestUtil
import org.lfdecentralizedtrust.splice.wallet.automation.AcceptedTransferOfferTrigger
import org.lfdecentralizedtrust.splice.wallet.store.TxLogEntry
import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.admin.api.client.commands.LedgerApiCommands.UpdateService.*
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.PartyId

import java.time.Duration
import scala.jdk.CollectionConverters.*

class WalletTransferOfferStatusIntegrationTest
    extends IntegrationTestWithSharedEnvironment
    with HasExecutionContext
    with WalletTestUtil
    with WalletTxLogTestUtil {

  override def environmentDefinition: EnvironmentDefinition = {
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
  }

  "A wallet transfer offer status" should {

    val trackingId = "mytracking"
    val transferOfferAmount = BigDecimal(10.0)

    def createTransferOffer(senderParty: PartyId, receiverParty: PartyId)(implicit
        env: SpliceTests.SpliceTestConsoleEnvironment
    ) = {
      val (offerCid, _) = actAndCheck(
        "Alice creates transfer offer",
        aliceWalletClient.createTransferOffer(
          receiverParty,
          transferOfferAmount,
          "created->accepted->completed",
          CantonTimestamp.now().plus(Duration.ofMinutes(1)),
          trackingId,
        ),
      )(
        "Alice sees the transfer offer status as Created",
        offerCid => {
          val response = aliceWalletClient.getTransferOfferStatus(trackingId)
          inside(response) {
            case d0.GetTransferOfferStatusResponse.members.TransferOfferCreatedResponse(
                  d0.TransferOfferCreatedResponse(
                    status,
                    txId,
                    contractId,
                  )
                ) =>
              status should be(TxLogEntry.Http.TransferOfferStatus.Created)
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
        env: SpliceTests.SpliceTestConsoleEnvironment
    ): (Transaction, Event) = {
      val eventFormat = EventFormat(
        filtersByParty = parties.toSeq
          .map(party =>
            party.toLf -> Filters(
              Seq(
                CumulativeFilter(
                  IdentifierFilter.WildcardFilter(
                    transaction_filter.WildcardFilter(includeCreatedEventBlob = false)
                  )
                )
              )
            )
          )
          .toMap,
        filtersForAnyParty = None,
        verbose = false,
      )
      val txTree = inside(
        aliceValidatorBackend.participantClientWithAdminToken.ledger_api.updates
          .update_by_id(
            txId,
            UpdateFormat(
              includeTransactions = Some(
                TransactionFormatProto(
                  Some(eventFormat),
                  transaction_filter.TransactionShape.TRANSACTION_SHAPE_LEDGER_EFFECTS,
                )
              ),
              None,
              None,
            ),
          )
          .getOrElse(fail("Expected to see the transaction tree in the ledger."))
      ) { case TransactionWrapper(tx) =>
        tx
      }
      val root = txTree.events.headOption.value
      txTree -> root
    }

    "see created->accepted->completed transfers" in { implicit env =>
      val (aliceUserParty, bobUserParty) = onboardAliceAndBob()

      aliceWalletClient.tap(100.0)

      val offerCid = createTransferOffer(aliceUserParty, bobUserParty)

      def acceptedTransferOfferTrigger =
        aliceValidatorBackend
          .userWalletAutomation(aliceWalletClient.config.ledgerApiUser)
          .futureValue
          .trigger[AcceptedTransferOfferTrigger]

      // Pause AcceptedTransferOfferTrigger so the offer doesn't immediately move from Accepted to Completed
      bracket(acceptedTransferOfferTrigger.pause(), acceptedTransferOfferTrigger.resume()) {
        actAndCheck(
          "Bob accepts the transfer offer",
          bobWalletClient.acceptTransferOffer(offerCid),
        )(
          "Bob sees the transfer offer status as Accepted",
          _ => {
            val response = bobWalletClient.getTransferOfferStatus(trackingId)
            inside(response) {
              case d0.GetTransferOfferStatusResponse.members.TransferOfferAcceptedResponse(
                    d0.TransferOfferAcceptedResponse(
                      status,
                      txId,
                      contractId,
                    )
                  ) =>
                status should be(TxLogEntry.Http.TransferOfferStatus.Accepted)
                val (tree, exercise) = getRootFromTxId(
                  txId,
                  Set(aliceUserParty, bobUserParty),
                )
                exercise.getExercised.choice should be("TransferOffer_Accept")
                val javaTx = data.Transaction
                  .fromProto(Transaction.toJavaProto(tree))
                val childNodeIds = javaTx
                  .getChildNodeIds(
                    data.ExercisedEvent.fromProto(ExercisedEvent.toJavaProto(exercise.getExercised))
                  )
                  .asScala
                val acceptChildren = childNodeIds.map(nodeId =>
                  tree.events.find(e => e.getCreated.nodeId == nodeId).getOrElse(fail("Must exist"))
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
            case d0.GetTransferOfferStatusResponse.members.TransferOfferCompletedResponse(
                  d0.TransferOfferCompletedResponse(
                    status,
                    txId,
                    contractId,
                  )
                ) =>
              status should be(TxLogEntry.Http.TransferOfferStatus.Completed)
              val (tree, batchExercise) = getRootFromTxId(
                txId,
                Set(aliceUserParty, bobUserParty),
              )
              batchExercise.getExercised.choice should be("WalletAppInstall_ExecuteBatch")
              tree.events
                .find(
                  _.getExercised.choice == "AcceptedTransferOffer_Complete"
                )
                .valueOrFail("Did not find complete tx")
              val amulets = tree.events
                .filter(_.getCreated.contractId.nonEmpty)
                .map(evt => CreatedEvent.fromProto(toJavaProto(evt.getCreated)))
                .collect { case AmuletCreate(amulet) =>
                  amulet
                }
              amulets.exists { amulet =>
                amulet.payload.owner == bobUserParty.toProtoPrimitive &&
                amulet.payload.amount.initialAmount
                  .doubleValue() == transferOfferAmount.doubleValue &&
                amulet.contractId.contractId == contractId
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
            case d0.GetTransferOfferStatusResponse.members.TransferOfferFailedResponse(
                  d0.TransferOfferFailedResponse(
                    status,
                    failure,
                    Some(withdrawReason),
                  )
                ) =>
              status should be(TxLogEntry.Http.TransferOfferStatus.Failed)
              failure should be(d0.TransferOfferFailedResponse.FailureKind.Withdrawn)
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
            case d0.GetTransferOfferStatusResponse.members.TransferOfferFailedResponse(
                  d0.TransferOfferFailedResponse(
                    status,
                    failure,
                    Some(withdrawReason),
                  )
                ) =>
              status should be(TxLogEntry.Http.TransferOfferStatus.Failed)
              failure should be(d0.TransferOfferFailedResponse.FailureKind.Withdrawn)
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
            case d0.GetTransferOfferStatusResponse.members.TransferOfferFailedResponse(
                  d0.TransferOfferFailedResponse(
                    status,
                    failure,
                    None,
                  )
                ) =>
              status should be(TxLogEntry.Http.TransferOfferStatus.Failed)
              failure should be(d0.TransferOfferFailedResponse.FailureKind.Rejected)
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
            case d0.GetTransferOfferStatusResponse.members.TransferOfferFailedResponse(
                  d0.TransferOfferFailedResponse(status, failure, None)
                ) =>
              status should be(TxLogEntry.Http.TransferOfferStatus.Failed)
              failure should be(d0.TransferOfferFailedResponse.FailureKind.Expired)
          }
        },
      )
    }

  }
}
