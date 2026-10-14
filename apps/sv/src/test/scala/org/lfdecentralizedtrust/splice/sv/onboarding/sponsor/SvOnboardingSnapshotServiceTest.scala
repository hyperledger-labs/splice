// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.onboarding.sponsor

import better.files.File
import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.PositiveFiniteDuration
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.time.SimClock
import com.digitalasset.canton.topology.{SequencerId, UniqueIdentifier}
import com.digitalasset.canton.{BaseTest, HasActorSystem, HasExecutionContext}
import com.google.protobuf.ByteString
import io.grpc.{Status, StatusRuntimeException}
import org.lfdecentralizedtrust.splice.automation.{TriggerContext, TriggerEnabledSynchronization}
import org.lfdecentralizedtrust.splice.config.AutomationConfig
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.sv.automation.SvOnboardingSnapshotCleanupTrigger
import org.lfdecentralizedtrust.splice.sv.config.SvOnboardingSnapshotsConfig
import org.lfdecentralizedtrust.splice.sv.onboarding.sponsor.SvOnboardingSnapshotService.{
  SnapshotKey,
  SnapshotState,
}
import org.scalatest.wordspec.AnyWordSpec
import org.slf4j.event.Level

import java.nio.file.{Files, Path}
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{Future, Promise, blocking}

class SvOnboardingSnapshotServiceTest
    extends AnyWordSpec
    with BaseTest
    with HasActorSystem
    with HasExecutionContext {

  private val sha256 = ByteString.copyFromUtf8("sha256")

  private def key(name: String) =
    SnapshotKey.SequencerOnboardingState(
      SequencerId(UniqueIdentifier.tryFromProtoPrimitive(s"$name::dummy"))
    )

  private def writeSnapshot(result: Future[ByteString]): Path => Future[ByteString] = file => {
    Files.write(file, "snapshot".getBytes)
    result
  }

  private def preventDeletion(file: Path): Path = {
    Files.deleteIfExists(file)
    Files.createDirectory(file)
    Files.write(file.resolve("block-deletion"), Array[Byte](1))
  }

  private def withService(config: SvOnboardingSnapshotsConfig = SvOnboardingSnapshotsConfig())(
      test: (SvOnboardingSnapshotService, SimClock, RetryProvider) => Unit
  ): Unit = {
    val clock = new SimClock(loggerFactory = loggerFactory)
    val retryProvider =
      RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory)
    val service = new SvOnboardingSnapshotService(config, clock, retryProvider, loggerFactory)
    try test(service, clock, retryProvider)
    finally {
      retryProvider.close()
      service.close()
    }
  }

  "SvOnboardingSnapshotService" should {

    "export in the background and reuse the snapshot for the same key" in withService() {
      (service, _, _) =>
        val exported = Promise[ByteString]()
        val exports = new AtomicInteger()
        def prepare(name: String) = service
          .prepare(
            key(name),
            file => {
              exports.incrementAndGet()
              writeSnapshot(exported.future)(file)
            },
          )
          .futureValue

        val id = prepare("sequencer1")
        service.lookup(id) shouldBe Some(SnapshotState.Exporting)
        prepare("sequencer1") shouldBe id

        exported.success(sha256)
        val file = eventually() {
          inside(service.lookup(id)) { case Some(SnapshotState.Ready(file, `sha256`)) => file }
        }
        File(file).contentAsString shouldBe "snapshot"
        prepare("sequencer1") shouldBe id
        exports.get() shouldBe 1

        prepare("sequencer2") should not be id
        eventually()(exports.get() shouldBe 2)
    }

    "start a new export after a failed one" in withService() { (service, _, _) =>
      val failedId =
        loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.WARN))(
          {
            val id = service
              .prepare(
                key("sequencer1"),
                writeSnapshot(Future.failed(new RuntimeException("boom"))),
              )
              .futureValue
            eventually()(service.lookup(id) shouldBe Some(SnapshotState.Failed("boom")))
            id
          },
          forExactly(1, _)(_.warningMessage should include("failed")),
        )
      eventually()(File(service.exportsDirectory).list.toSeq shouldBe empty)

      val id =
        service.prepare(key("sequencer1"), writeSnapshot(Future.successful(sha256))).futureValue
      id should not be failedId
      eventually()(service.lookup(id) should matchPattern { case Some(SnapshotState.Ready(_, _)) =>
      })
    }

    "prepare a new snapshot if the ready file was deleted" in withService() { (service, _, _) =>
      val snapshotKey = key("sequencer1")
      val id = service.prepare(snapshotKey, writeSnapshot(Future.successful(sha256))).futureValue
      val file = eventually() {
        inside(service.lookup(id)) { case Some(SnapshotState.Ready(file, _)) => file }
      }
      Files.delete(file)

      val replacement =
        service.prepare(snapshotKey, writeSnapshot(Future.successful(sha256))).futureValue
      replacement should not be id
      service.lookup(id) shouldBe Some(SnapshotState.Failed("Snapshot file is missing"))
      eventually()(service.lookup(replacement) should matchPattern {
        case Some(SnapshotState.Ready(_, _)) =>
      })
      service
        .prepare(snapshotKey, writeSnapshot(Future.successful(sha256)))
        .futureValue shouldBe replacement
    }

    "log removal of a failed export's file only once" in withService() { (service, clock, _) =>
      val failedId =
        loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.INFO))(
          {
            val id = service
              .prepare(
                key("sequencer1"),
                writeSnapshot(Future.failed(new RuntimeException("export failed"))),
              )
              .futureValue
            eventually()(service.lookup(id) shouldBe Some(SnapshotState.Failed("export failed")))
            id
          },
          entries => {
            forExactly(1, entries)(_.warningMessage should include("Onboarding snapshot export"))
            forExactly(1, entries)(_.message should startWith("Removed onboarding snapshot file"))
          },
        )
      loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.INFO))(
        {
          clock.advance(Duration.ofHours(24))
          service.removeExpired(clock.now).futureValue shouldBe 1
          service.lookup(failedId) shouldBe None
        },
        entries =>
          forAll(entries)(_.message should not startWith "Removed onboarding snapshot file"),
      )
    }

    "run at most `parallelism` exports and reject them when the queue is full" in withService(
      SvOnboardingSnapshotsConfig(parallelism = PositiveInt.two, queueSize = PositiveInt.one)
    ) { (service, _, _) =>
      final class Export(name: String) {
        val started = Promise[Unit]()
        val done = Promise[ByteString]()
        def prepare(): String = service
          .prepare(
            key(name),
            _ => {
              started.success(())
              done.future
            },
          )
          .futureValue
      }
      val slow = new Export("sequencer1")
      val fast = new Export("sequencer2")
      val queued = new Export("sequencer3")
      try {
        Seq(slow, fast).foreach { running =>
          running.prepare()
          running.started.future.futureValue
        }
        queued.prepare()
        inside(
          service.prepare(key("sequencer4"), _ => Future.successful(sha256)).failed.futureValue
        ) { case e: StatusRuntimeException =>
          e.getStatus.getCode shouldBe Status.Code.UNAVAILABLE
        }
        queued.started.isCompleted shouldBe false

        fast.done.success(sha256)
        queued.started.future.futureValue
      } finally Seq(slow, fast, queued).foreach(_.done.trySuccess(sha256))
    }

    "remove snapshots after the retention period from the cleanup trigger" in withService(
      SvOnboardingSnapshotsConfig(retention = PositiveFiniteDuration.ofHours(24))
    ) { (service, clock, retryProvider) =>
      val trigger = new SvOnboardingSnapshotCleanupTrigger(
        TriggerContext(
          AutomationConfig(),
          clock,
          clock,
          TriggerEnabledSynchronization.Noop,
          retryProvider,
          loggerFactory,
          NoOpMetricsFactory,
        ),
        service,
      )
      val ready =
        service.prepare(key("sequencer1"), writeSnapshot(Future.successful(sha256))).futureValue
      val readyFile = eventually() {
        inside(service.lookup(ready)) { case Some(SnapshotState.Ready(file, _)) => file }
      }
      val exported = Promise[ByteString]()
      val exporting = service.prepare(key("sequencer2"), writeSnapshot(exported.future)).futureValue

      try {
        clock.advance(Duration.ofHours(24).minusSeconds(1))
        trigger.performWorkIfAvailable().futureValue
        service.lookup(ready) should matchPattern { case Some(SnapshotState.Ready(_, _)) => }

        clock.advance(Duration.ofSeconds(1))
        trigger.performWorkIfAvailable().futureValue
        service.lookup(ready) shouldBe None
        Files.exists(readyFile) shouldBe false
        service.lookup(exporting) shouldBe Some(SnapshotState.Exporting)
        service
          .prepare(key("sequencer1"), writeSnapshot(Future.successful(sha256)))
          .futureValue should not be ready

        exported.success(sha256)
        val exportedFile = eventually() {
          inside(service.lookup(exporting)) { case Some(SnapshotState.Ready(file, _)) => file }
        }
        service.removeExpired(clock.now).futureValue shouldBe 1
        service.lookup(exporting) shouldBe None
        Files.exists(exportedFile) shouldBe false
      } finally {
        exported.trySuccess(sha256)
        trigger.close()
      }
    }

    "keep processing exports when deleting a failed export fails and retry cleanup" in withService() {
      (service, clock, _) =>
        val blocker = Promise[Path]()
        val failedId = loggerFactory.assertEventuallyLogsSeq(
          SuppressionRule.LevelAndAbove(Level.WARN)
        )(
          {
            val id = service
              .prepare(
                key("sequencer1"),
                file => {
                  blocker.success(preventDeletion(file))
                  Future.failed(new RuntimeException("export failed"))
                },
              )
              .futureValue
            eventually()(service.lookup(id) shouldBe Some(SnapshotState.Failed("export failed")))
            id
          },
          entries => {
            forExactly(1, entries)(_.warningMessage should include("Onboarding snapshot export"))
            forExactly(1, entries)(_.warningMessage should include("cleanup will retry"))
          },
        )

        val next =
          service.prepare(key("sequencer2"), writeSnapshot(Future.successful(sha256))).futureValue
        eventually()(service.lookup(next) should matchPattern {
          case Some(SnapshotState.Ready(_, _)) =>
        })

        val child = blocker.future.futureValue
        Files.delete(child)
        service.removeExpired(clock.now).futureValue shouldBe 1
        Files.exists(child.getParent) shouldBe false
        service.lookup(failedId) shouldBe Some(SnapshotState.Failed("export failed"))
    }

    "retry an expired file deletion without retaining download eligibility or blocking other cleanup" in withService() {
      (service, clock, _) =>
        val first =
          service.prepare(key("sequencer1"), writeSnapshot(Future.successful(sha256))).futureValue
        val second =
          service.prepare(key("sequencer2"), writeSnapshot(Future.successful(sha256))).futureValue
        def fileOf(id: String): Path = eventually() {
          inside(service.lookup(id)) { case Some(SnapshotState.Ready(file, _)) => file }
        }
        val firstFile = fileOf(first)
        val secondFile = fileOf(second)
        val child = preventDeletion(firstFile)
        clock.advance(Duration.ofHours(24))

        loggerFactory.assertLogs(SuppressionRule.LevelAndAbove(Level.WARN))(
          service.removeExpired(clock.now).futureValue shouldBe 1,
          _.warningMessage should include("cleanup will retry"),
        )
        service.lookup(first) shouldBe None
        service.lookup(second) shouldBe None
        Files.exists(firstFile) shouldBe true
        Files.exists(secondFile) shouldBe false

        val replacement =
          service.prepare(key("sequencer1"), writeSnapshot(Future.successful(sha256))).futureValue
        replacement should not be first
        val replacementFile = fileOf(replacement)
        Files.delete(child)
        service.removeExpired(clock.now).futureValue shouldBe 1
        Files.exists(firstFile) shouldBe false
        Files.exists(replacementFile) shouldBe true
        service.removeExpired(clock.now).futureValue shouldBe 0
    }

    Seq(false, true).foreach { closeRetryProviderFirst =>
      s"wait for active writers when closing with retry-provider-first=$closeRetryProviderFirst" in withService() {
        (service, _, retryProvider) =>
          val exported = Promise[Unit]()
          val started = Promise[Path]()
          val queuedStarted = new AtomicInteger()
          val active = service
            .prepare(
              key("sequencer1"),
              file => {
                val output = Files.newOutputStream(file)
                output.write(1)
                started.success(file)
                exported.future.map { _ =>
                  try output.write(2)
                  finally output.close()
                  sha256
                }
              },
            )
            .futureValue
          val file = started.future.futureValue
          service
            .prepare(
              key("sequencer2"),
              _ => {
                queuedStarted.incrementAndGet()
                Future.successful(sha256)
              },
            )
            .futureValue

          if (closeRetryProviderFirst) retryProvider.close()
          val closed = Future(blocking(service.close()))
          try {
            eventually()(service.isClosing shouldBe true)
            closed.isCompleted shouldBe false
            Files.exists(file) shouldBe true
            service.lookup(active) shouldBe None
            inside(
              service.prepare(key("sequencer1"), _ => Future.successful(sha256)).failed.futureValue
            ) { case e: StatusRuntimeException =>
              e.getStatus.getCode shouldBe Status.Code.UNAVAILABLE
            }
          } finally exported.trySuccess(())

          closed.futureValue
          service.lookup(active) shouldBe None
          Files.exists(service.exportsDirectory) shouldBe false
          queuedStarted.get() shouldBe 0
      }
    }

    "clear stale exports and downloads but nothing else on startup" in File
      .usingTemporaryDirectory() { directory =>
        val staleExport = (directory / "exports" / "stale").createIfNotExists(createParents = true)
        val staleDownload =
          (directory / "downloads" / "acs" / "stale").createIfNotExists(createParents = true)
        val otherFile = (directory / "other").createIfNotExists()
        withService(SvOnboardingSnapshotsConfig(directory = Some(directory.path))) {
          (service, _, _) =>
            service.exportsDirectory shouldBe (directory / "exports").path
            staleExport.exists shouldBe false
            staleDownload.exists shouldBe false
            otherFile.exists shouldBe true
        }
      }

    "reject exports after shutdown" in withService() { (service, _, retryProvider) =>
      retryProvider.close()
      inside(
        service.prepare(key("sequencer1"), _ => Future.successful(sha256)).failed.futureValue
      ) { case e: StatusRuntimeException =>
        e.getStatus.getCode shouldBe Status.Code.UNAVAILABLE
      }
    }
  }
}
