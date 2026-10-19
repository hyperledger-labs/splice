// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.automation

import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.error.MediatorError
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.daml.lf.data.Ref.PackageVersion
import com.google.rpc.{Code, ErrorInfo, Status}
import io.grpc.StatusRuntimeException
import io.grpc.protobuf.StatusProto
import org.lfdecentralizedtrust.splice.store.InMemoryUnavailablePartiesStore
import org.scalatest.wordspec.AsyncWordSpec

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Future

class BatchSplittingTest extends AsyncWordSpec with BaseTest {

  import BatchSplittingTest.Contract

  private implicit val tc: TraceContext = TraceContext.empty

  private def party(name: String): PartyId =
    PartyId.tryFromProtoPrimitive(s"$name::1220deadbeef")

  private val dso = party("dso")
  private val alice = party("alice")
  private val bob = party("bob")
  private val charlie = party("charlie")

  private val v1 = PackageVersion.assertFromString("0.1.0")
  private val v2 = PackageVersion.assertFromString("0.2.0")

  private def submissionFailure: StatusRuntimeException =
    StatusProto.toStatusRuntimeException(
      Status
        .newBuilder()
        .setCode(Code.FAILED_PRECONDITION_VALUE)
        .setMessage("failure")
        .addDetails(
          com.google.protobuf.Any.pack(
            ErrorInfo
              .newBuilder()
              .setReason("NO_SYNCHRONIZER_FOR_SUBMISSION")
              .build()
          )
        )
        .build()
    )

  /** A mediator timeout, which reports the parties that did not respond. */
  private def unresponsiveParties(parties: PartyId*): StatusRuntimeException =
    StatusProto.toStatusRuntimeException(
      Status
        .newBuilder()
        .setCode(Code.ABORTED_VALUE)
        .setMessage("timeout")
        .addDetails(
          com.google.protobuf.Any.pack(
            ErrorInfo
              .newBuilder()
              .setReason(MediatorError.Timeout.id)
              .putMetadata("unresponsiveParties", parties.map(_.toProtoPrimitive).mkString(","))
              .build()
          )
        )
        .build()
    )

  private def splitting(
      store: InMemoryUnavailablePartiesStore,
      versions: Map[PartyId, Option[PackageVersion]],
      protectedParties: Set[PartyId] = Set(dso),
  ) =
    new BatchSplitting(
      p => _ => Future.successful(versions.getOrElse(p, Some(v2))),
      store,
      protectedParties,
      loggerFactory,
    )

  private def run(
      contracts: Seq[Contract],
      versions: Map[PartyId, Option[PackageVersion]],
      failingContracts: Set[String],
      store: InMemoryUnavailablePartiesStore = new InMemoryUnavailablePartiesStore(Set.empty),
      protectedParties: Set[PartyId] = Set(dso),
  ): Future[(BatchSplitting.Result, Seq[Seq[String]], InMemoryUnavailablePartiesStore)] = {
    val submissions = new AtomicReference[Seq[Seq[String]]](Seq.empty)
    splitting(store, versions, protectedParties)
      .processBatch[Contract](
        contracts,
        _.parties,
        batch => {
          submissions.updateAndGet(_ :+ batch.map(_.id))
          if (batch.exists(c => failingContracts.contains(c.id)))
            Future.failed(submissionFailure)
          else Future.unit
        },
      )
      .map(result => (result, submissions.get(), store))
  }

  "BatchSplitting" should {

    "submit the full batch if it succeeds" in {
      val contracts =
        Seq(Contract("c1", Set(alice, dso)), Contract("c2", Set(bob, dso)))
      run(contracts, Map.empty, Set.empty).map { case (result, submissions, store) =>
        result.submittedContracts shouldBe 2
        result.splits shouldBe 0
        submissions should have size 1
        store.listParties().futureValue shouldBe empty
      }
    }

    "isolate the failing contract by splitting the batch and ignore the party on the lowest version" in {
      val contracts = Seq(
        Contract("c1", Set(alice, dso)),
        Contract("c2", Set(bob, dso)),
        Contract("c3", Set(bob, charlie, dso)),
        Contract("c4", Set(alice, dso)),
      )
      // charlie is on a old buggy version and makes c3 fail
      val versions =
        Map(alice -> Some(v2), bob -> Some(v2), charlie -> Some(v1), dso -> Some(v2))
      run(contracts, versions, Set("c3")).map { case (result, submissions, store) =>
        // [c3,c1,c2,c4] -> [c3,c1] + [c2,c4], then [c3,c1] -> [c3] + [c1]
        result.splits shouldBe 2
        result.failedContracts shouldBe 1
        result.submittedContracts shouldBe 3
        result.ignoredParties shouldBe Set(charlie)
        store.listParties().futureValue.toSet shouldBe Set(charlie)
        // the failing contract is tried first since it has the lowest version,
        // and the halves are retried depth-first, left before right
        submissions shouldBe Seq(
          Seq("c3", "c1", "c2", "c4"),
          Seq("c3", "c1"),
          Seq("c3"),
          Seq("c1"),
          Seq("c2", "c4"),
        )
      }
    }

    "over-approximate if all parties are on the same version" in {
      val contracts = Seq(Contract("c1", Set(alice, bob, dso)))
      run(contracts, Map.empty, Set("c1")).map { case (result, _, store) =>
        result.ignoredParties shouldBe Set(alice, bob)
        store.listParties().futureValue.toSet shouldBe Set(alice, bob)
      }
    }

    "never ignore protected parties" in {
      val contracts = Seq(Contract("c1", Set(alice, dso)))
      run(
        contracts,
        Map(alice -> Some(v1), dso -> Some(v2)),
        Set("c1"),
        protectedParties = Set(dso, alice),
      ).map { case (result, _, store) =>
        result.ignoredParties shouldBe empty
        store.listParties().futureValue shouldBe empty
      }
    }

    "not submit contracts of parties ignored while processing the batch" in {
      val contracts = Seq(
        Contract("c1", Set(charlie, dso)),
        Contract("c2", Set(charlie, dso)),
      )
      val versions = Map(charlie -> Some(v1), dso -> Some(v2))
      run(contracts, versions, Set("c1")).map { case (result, submissions, _) =>
        result.ignoredParties shouldBe Set(charlie)
        // c2 is only submitted as part of the initial batch, never again once charlie is ignored
        submissions.count(_ == Seq("c2")) shouldBe 0
      }
    }

    "skip contracts of already ignored parties" in {
      val store = new InMemoryUnavailablePartiesStore(Set(charlie))
      val contracts = Seq(
        Contract("c1", Set(alice, dso)),
        Contract("c2", Set(charlie, dso)),
      )
      run(contracts, Map.empty, Set.empty, store).map { case (result, submissions, _) =>
        result.submittedContracts shouldBe 1
        submissions.flatten should contain only "c1"
      }
    }

    "ignore the parties reported as unresponsive without splitting" in {
      val store = new InMemoryUnavailablePartiesStore(Set.empty)
      val submissions = new AtomicReference[Seq[Seq[String]]](Seq.empty)
      val contracts = Seq(
        Contract("c1", Set(alice, dso)),
        Contract("c2", Set(bob, dso)),
        Contract("c3", Set(charlie, dso)),
      )
      splitting(store, Map.empty)
        .processBatch[Contract](
          contracts,
          _.parties,
          batch => {
            submissions.updateAndGet(_ :+ batch.map(_.id))
            if (batch.exists(_.id == "c3")) Future.failed(unresponsiveParties(charlie))
            else Future.unit
          },
        )
        .map { result =>
          // the mediator names the culprit, so there is no need to bisect the batch
          result.splits shouldBe 0
          result.ignoredParties shouldBe Set(charlie)
          result.failedContracts shouldBe 1
          result.submittedContracts shouldBe 2
          store.listParties().futureValue.toSet shouldBe Set(charlie)
          // the contracts that do not involve charlie are retried right away
          submissions.get() shouldBe Seq(Seq("c1", "c2", "c3"), Seq("c1", "c2"))
        }
    }

  }
}

object BatchSplittingTest {
  final case class Contract(id: String, parties: Set[PartyId])
}
