package com.daml.network.store

import akka.actor.ActorSystem
import com.daml.network.environment.LedgerClient.GetTreeUpdatesResponse.{Transfer, TransferEvent}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Future

class InMemoryTransferStoreTest extends StoreTest {

  implicit val actorSystem: ActorSystem = ActorSystem("InMemoryTransferStoreTest")

  private val alice = mkPartyId("alice")

  def mkStore(): Future[InMemoryTransferStore] = Future {
    new InMemoryTransferStore(
      loggerFactory,
      alice,
    )
  }

  val coupon1 = appRewardCoupon(0, alice)
  val coupon2 = appRewardCoupon(1, alice)
  val coupon3 = appRewardCoupon(1, alice)

  "InMemoryTransferStore" should {
    "stream transfers and report stale transfers" in {
      val transfers = new AtomicReference(Seq.empty[Transfer[TransferEvent.Out]])
      for {
        store <- mkStore()
        streamF = store
          .streamReadyForTransferIn()
          .take(2)
          .runForeach(transfer => transfers.updateAndGet(_.appended(transfer)))
        t0Out = mkTransfer(
          "0",
          toTransferOutEvent(
            coupon1.contractId
          ),
        )
        t0In = mkTransfer("1", toTransferInEvent(coupon1))
        _ <- store.ingestionSink.ingestTransferOut(t0Out)
        r <- store.isReadyForTransferIn(t0Out)
        _ = r shouldBe true
        // Ingest transfer in, transfer out should be marked as no longer ready for transfer in
        _ <- store.ingestionSink.ingestTransferIn(t0In)
        r <- store.isReadyForTransferIn(t0Out)
        _ = r shouldBe false

        t1Out = mkTransfer(
          "2",
          toTransferOutEvent(
            coupon2.contractId
          ),
        )
        t1In = mkTransfer("3", toTransferInEvent(coupon2))
        // Ingest transfer in first
        _ <- store.ingestionSink.ingestTransferIn(t1In)
        r <- store.isReadyForTransferIn(t1Out)
        _ = r shouldBe false
        // Now ingest transfer out, no event will be emitted
        _ <- store.ingestionSink.ingestTransferOut(t1Out)
        r <- store.isReadyForTransferIn(t1Out)
        _ = r shouldBe false
        // Ingest another transfer out to make sure that we really saw no event for t1
        t2Out = mkTransfer(
          "3",
          toTransferOutEvent(
            coupon3.contractId
          ),
        )
        _ <- store.ingestionSink.ingestTransferOut(t2Out)
        _ <- streamF
      } yield {
        transfers.get() shouldBe Seq(t0Out, t2Out)
      }
    }
  }
}
