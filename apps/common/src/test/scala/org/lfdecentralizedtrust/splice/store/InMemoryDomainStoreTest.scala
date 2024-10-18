package org.lfdecentralizedtrust.splice.store

import com.daml.metrics.api.noop.NoOpMetricsFactory
import org.apache.pekko.actor.ActorSystem
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.{BaseTest, DomainAlias}
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.topology.PartyId
import io.grpc.{Status, StatusRuntimeException}
import org.scalatest.wordspec.AsyncWordSpec

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{Future, Promise}

class InMemoryDomainStoreTest extends AsyncWordSpec with BaseTest {

  implicit val actorSystem: ActorSystem = ActorSystem("InMemoryDomainStoreTest")

  private def domainId(s: String) = DomainId.tryFromString(s"domain::$s")
  private def domainAlias(s: String) = DomainAlias.tryCreate(s)
  private val alice = PartyId.tryFromProtoPrimitive("alice::alice")

  def mkStore(): Future[InMemoryDomainStore] =
    Future.successful(
      new InMemoryDomainStore(
        alice,
        loggerFactory,
        RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory),
      )
    )

  "InMemoryDomainStore" should {

    "ingest domain updates and allow listing them" in {
      for {
        store <- mkStore()
        domains <- store.listConnectedDomains()
        _ = domains shouldBe Map.empty
        _ <- store.ingestionSink.ingestConnectedDomains(
          Map(
            domainAlias("a") -> domainId("aId"),
            domainAlias("b") -> domainId("bId"),
          )
        )
        domains <- store.listConnectedDomains()
        _ = domains shouldBe Map(
          domainAlias("a") -> domainId("aId"),
          domainAlias("b") -> domainId("bId"),
        )
        _ <- store.ingestionSink.ingestConnectedDomains(
          Map(
            domainAlias("a") -> domainId("aId"),
            domainAlias("c") -> domainId("cId"),
          )
        )
        domains <- store.listConnectedDomains()
        _ = domains shouldBe Map(
          domainAlias("a") -> domainId("aId"),
          domainAlias("c") -> domainId("cId"),
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
            domainAlias("a") -> domainId("aId")
          )
        )
        id <- store.getDomainId(domainAlias("a"))
        _ = id shouldBe domainId("aId")
        ex <- recoverToExceptionIf[StatusRuntimeException] {
          store.getDomainId(domainAlias("b"))
        }
        _ = ex.getStatus().getCode() shouldBe Status.Code.NOT_FOUND
      } yield {
        succeed
      }
    }

    "stream domain updates" in {
      val eventsFromStart = new AtomicReference(Seq.empty[DomainStore.DomainConnectionEvent])
      val eventsAfterFirstIngestion =
        new AtomicReference(Seq.empty[DomainStore.DomainConnectionEvent])
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
            domainAlias("a") -> domainId("aId")
          )
        )
        _ <- fromStartSize1Promise.future
        _ = eventsFromStart.get() shouldBe Seq(
          DomainStore.DomainAdded(domainAlias("a"), domainId("aId"))
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
            DomainStore.DomainAdded(domainAlias("a"), domainId("aId"))
          )
        _ <- store.ingestionSink.ingestConnectedDomains(
          Map(
            domainAlias("b") -> domainId("bId")
          )
        )
        _ <- fromStartSize3Promise.future
        _ =
          eventsFromStart.get() shouldBe Seq(
            DomainStore.DomainAdded(domainAlias("a"), domainId("aId")),
            DomainStore.DomainAdded(domainAlias("b"), domainId("bId")),
            DomainStore.DomainRemoved(domainAlias("a"), domainId("aId")),
          )
        _ <- afterFirstSize3Promise.future
        _ =
          eventsAfterFirstIngestion.get() shouldBe Seq(
            DomainStore.DomainAdded(domainAlias("a"), domainId("aId")),
            DomainStore.DomainAdded(domainAlias("b"), domainId("bId")),
            DomainStore.DomainRemoved(domainAlias("a"), domainId("aId")),
          )
      } yield succeed
    }
  }
}
