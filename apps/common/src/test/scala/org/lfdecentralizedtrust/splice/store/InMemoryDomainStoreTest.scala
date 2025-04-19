package org.lfdecentralizedtrust.splice.store

import com.daml.metrics.api.noop.NoOpMetricsFactory
import org.apache.pekko.actor.ActorSystem
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.{BaseTest, SynchronizerAlias}
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.topology.PartyId
import io.grpc.{Status, StatusRuntimeException}
import org.scalatest.wordspec.AsyncWordSpec

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{Future, Promise}

class InMemorySynchronizerStoreTest extends AsyncWordSpec with BaseTest {

  implicit val actorSystem: ActorSystem = ActorSystem("InMemorySynchronizerStoreTest")

  private def synchronizerId(s: String) = SynchronizerId.tryFromString(s"domain::$s")
  private def synchronizerAlias(s: String) = SynchronizerAlias.tryCreate(s)
  private val alice = PartyId.tryFromProtoPrimitive("alice::alice")

  def mkStore(): Future[InMemorySynchronizerStore] =
    Future.successful(
      new InMemorySynchronizerStore(
        alice,
        loggerFactory,
        RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory),
      )
    )

  "InMemorySynchronizerStore" should {

    "ingest domain updates and allow listing them" in {
      for {
        store <- mkStore()
        domains <- store.listConnectedDomains()
        _ = domains shouldBe Map.empty
        _ <- store.ingestionSink.ingestConnectedDomains(
          Map(
            synchronizerAlias("a") -> synchronizerId("aId"),
            synchronizerAlias("b") -> synchronizerId("bId"),
          )
        )
        domains <- store.listConnectedDomains()
        _ = domains shouldBe Map(
          synchronizerAlias("a") -> synchronizerId("aId"),
          synchronizerAlias("b") -> synchronizerId("bId"),
        )
        _ <- store.ingestionSink.ingestConnectedDomains(
          Map(
            synchronizerAlias("a") -> synchronizerId("aId"),
            synchronizerAlias("c") -> synchronizerId("cId"),
          )
        )
        domains <- store.listConnectedDomains()
        _ = domains shouldBe Map(
          synchronizerAlias("a") -> synchronizerId("aId"),
          synchronizerAlias("c") -> synchronizerId("cId"),
        )
      } yield {
        succeed
      }
    }

    "ingest domain updates and support resolving domain aliases" in {
      for {
        store <- mkStore()
        _ <- store.ingestionSink.ingestConnectedDomains(
          Map(
            synchronizerAlias("a") -> synchronizerId("aId")
          )
        )
        id <- store.getSynchronizerId(synchronizerAlias("a"))
        _ = id shouldBe synchronizerId("aId")
        ex <- recoverToExceptionIf[StatusRuntimeException] {
          store.getSynchronizerId(synchronizerAlias("b"))
        }
        _ = ex.getStatus().getCode() shouldBe Status.Code.NOT_FOUND
      } yield {
        succeed
      }
    }

    "stream domain updates" in {
      val eventsFromStart = new AtomicReference(Seq.empty[SynchronizerStore.DomainConnectionEvent])
      val eventsAfterFirstIngestion =
        new AtomicReference(Seq.empty[SynchronizerStore.DomainConnectionEvent])
      val fromStartSize1Promise: Promise[Unit] = Promise[Unit]()
      val fromStartSize3Promise: Promise[Unit] = Promise[Unit]()
      val afterFirstSize1Promise: Promise[Unit] = Promise[Unit]()
      val afterFirstSize3Promise: Promise[Unit] = Promise[Unit]()
      for {
        store <- mkStore()
        _ = store
          .streamEvents()
          .runForeach(event => {
            val updated = eventsFromStart.get().appended(event)
            eventsFromStart.set(updated)
            if (updated.size == 1) {
              fromStartSize1Promise.success(())
            }
            if (updated.size == 3) {
              fromStartSize3Promise.success(())
            }
          })
        _ <- store.ingestionSink.ingestConnectedDomains(
          Map(
            synchronizerAlias("a") -> synchronizerId("aId")
          )
        )
        _ <- fromStartSize1Promise.future
        _ = eventsFromStart.get() shouldBe Seq(
          SynchronizerStore.DomainAdded(synchronizerAlias("a"), synchronizerId("aId"))
        )
        _ = store
          .streamEvents()
          .runForeach(event => {
            val updated = eventsAfterFirstIngestion.get().appended(event)
            eventsAfterFirstIngestion.set(updated)
            if (updated.size == 1) {
              afterFirstSize1Promise.success(())
            }
            if (updated.size == 3) {
              afterFirstSize3Promise.success(())
            }
          })
        _ <- afterFirstSize1Promise.future
        _ =
          eventsAfterFirstIngestion.get() shouldBe Seq(
            SynchronizerStore.DomainAdded(synchronizerAlias("a"), synchronizerId("aId"))
          )
        _ <- store.ingestionSink.ingestConnectedDomains(
          Map(
            synchronizerAlias("b") -> synchronizerId("bId")
          )
        )
        _ <- fromStartSize3Promise.future
        _ =
          eventsFromStart.get() shouldBe Seq(
            SynchronizerStore.DomainAdded(synchronizerAlias("a"), synchronizerId("aId")),
            SynchronizerStore.DomainAdded(synchronizerAlias("b"), synchronizerId("bId")),
            SynchronizerStore.DomainRemoved(synchronizerAlias("a"), synchronizerId("aId")),
          )
        _ <- afterFirstSize3Promise.future
        _ =
          eventsAfterFirstIngestion.get() shouldBe Seq(
            SynchronizerStore.DomainAdded(synchronizerAlias("a"), synchronizerId("aId")),
            SynchronizerStore.DomainAdded(synchronizerAlias("b"), synchronizerId("bId")),
            SynchronizerStore.DomainRemoved(synchronizerAlias("a"), synchronizerId("aId")),
          )
      } yield succeed
    }
  }
}
