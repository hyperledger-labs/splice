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
import com.digitalasset.canton.topology.{PartyId, SequencerId, SynchronizerId, UniqueIdentifier}
import com.digitalasset.canton.{BaseTest, HasActorSystem, HasExecutionContext}
import com.google.protobuf.ByteString
import io.grpc.{Status, StatusRuntimeException}
import org.lfdecentralizedtrust.splice.automation.{TriggerContext, TriggerEnabledSynchronization}
import org.lfdecentralizedtrust.splice.config.AutomationConfig
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.sv.automation.SvOnboardingSnapshotCleanupTrigger
import org.lfdecentralizedtrust.splice.sv.config.SvOnboardingSnapshotsConfig
import org.lfdecentralizedtrust.splice.sv.onboarding.SvOnboardingSnapshotId
import org.lfdecentralizedtrust.splice.sv.onboarding.sponsor.SvOnboardingSnapshotService.{
  SnapshotKey,
  SnapshotState,
}
import org.scalatest.wordspec.AnyWordSpec
import org.slf4j.event.Level

import java.nio.file.{Files, Path}
import java.nio.file.attribute.FileTime
import java.time.{Duration, Instant}
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{Future, Promise, blocking}

class SvOnboardingSnapshotServiceTest
    extends AnyWordSpec
    with BaseTest
    with HasActorSystem
    with HasExecutionContext {

  private val sha256 = ByteString.copyFromUtf8("sha256")

  private def key(name: String) =
    SnapshotKey.Acs(
      SynchronizerId.tryFromString("synchronizer::dummy"),
      PartyId.tryFromProtoPrimitive(s"$name::dummy"),
      Instant.EPOCH,
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
  ): Unit = if (config.directory.isEmpty) {
    File.usingTemporaryDirectory() { directory =>
      withService(config.copy(directory = Some(directory.path)))(test)
    }
  } else {
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

    "start without a directory but reject prepare" in {
      val clock = new SimClock(loggerFactory = loggerFactory)
      val retryProvider =
        RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory)
      val service =
        new SvOnboardingSnapshotService(
          SvOnboardingSnapshotsConfig(),
          clock,
          retryProvider,
          loggerFactory,
        )
      def notConfigured(result: Future[?]) =
        inside(result.failed.futureValue) { case e: StatusRuntimeException =>
          e.getStatus.getCode shouldBe Status.Code.INTERNAL
          e.getStatus.getDescription should include("onboarding-snapshots.directory")
        }
      try {
        service.exportsDirectory shouldBe None
        service.removeExpired(clock.now).futureValue shouldBe 0
        notConfigured(service.checkConfigured())
        notConfigured(service.prepare(key("sv1"), writeSnapshot(Future.successful(sha256))))
        service.lookup(SvOnboardingSnapshotId(UUID.randomUUID().toString)) shouldBe None
      } finally {
        retryProvider.close()
        service.close()
      }
    }

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

        val id = prepare("sv1")
        service.lookup(id) shouldBe Some(SnapshotState.Exporting)
        prepare("sv1") shouldBe id

        exported.success(sha256)
        val file = eventually() {
          inside(service.lookup(id)) { case Some(SnapshotState.Ready(file, `sha256`)) => file }
        }
        File(file).contentAsString shouldBe "snapshot"
        Files.exists(file.getParent.resolve("metadata.json")) shouldBe true
        prepare("sv1") shouldBe id
        exports.get() shouldBe 1

        prepare("sv2") should not be id
        eventually()(exports.get() shouldBe 2)
    }

    "start a new export after a failed one" in withService() { (service, _, _) =>
      val failedId =
        loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.WARN))(
          {
            val id = service
              .prepare(
                key("sv1"),
                writeSnapshot(Future.failed(new RuntimeException("boom"))),
              )
              .futureValue
            eventually()(service.lookup(id) shouldBe None)
            id
          },
          forExactly(1, _)(_.warningMessage should include("failed")),
        )
      eventually()(service.exportsDirectory.toList.flatMap(File(_).list) shouldBe empty)

      val id =
        service.prepare(key("sv1"), writeSnapshot(Future.successful(sha256))).futureValue
      id should not be failedId
      eventually()(service.lookup(id) should matchPattern { case Some(SnapshotState.Ready(_, _)) =>
      })
    }

    "prepare a new snapshot if the ready file was deleted" in withService() { (service, _, _) =>
      val snapshotKey = key("sv1")
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
                key("sv1"),
                writeSnapshot(Future.failed(new RuntimeException("export failed"))),
              )
              .futureValue
            eventually()(service.lookup(id) shouldBe None)
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
          service.removeExpired(clock.now).futureValue shouldBe 0
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
        def prepare(): SvOnboardingSnapshotId = service
          .prepare(
            key(name),
            file => {
              Files.write(file, Array[Byte](1))
              started.success(())
              done.future
            },
          )
          .futureValue
      }
      val slow = new Export("sv1")
      val fast = new Export("sv2")
      val queued = new Export("sv3")
      try {
        Seq(slow, fast).foreach { running =>
          running.prepare()
          running.started.future.futureValue
        }
        queued.prepare()
        inside(
          service.prepare(key("sv4"), _ => Future.successful(sha256)).failed.futureValue
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
        service.prepare(key("sv1"), writeSnapshot(Future.successful(sha256))).futureValue
      val readyFile = eventually() {
        inside(service.lookup(ready)) { case Some(SnapshotState.Ready(file, _)) => file }
      }
      val exported = Promise[ByteString]()
      val exporting = service.prepare(key("sv2"), writeSnapshot(exported.future)).futureValue

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
          .prepare(key("sv1"), writeSnapshot(Future.successful(sha256)))
          .futureValue should not be ready

        exported.success(sha256)
        val exportedFile = eventually() {
          inside(service.lookup(exporting)) { case Some(SnapshotState.Ready(file, _)) => file }
        }
        service.removeExpired(clock.now).futureValue shouldBe 0
        service
          .prepare(key("sv2"), _ => fail("must reuse the finished export"))
          .futureValue shouldBe exporting

        clock.advance(Duration.ofHours(24))
        trigger.performWorkIfAvailable().futureValue
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
                key("sv1"),
                file => {
                  blocker.success(preventDeletion(file))
                  Future.failed(new RuntimeException("export failed"))
                },
              )
              .futureValue
            eventually()(service.lookup(id) shouldBe None)
            id
          },
          entries => {
            forExactly(1, entries)(_.warningMessage should include("Onboarding snapshot export"))
            forExactly(1, entries)(_.warningMessage should include("cleanup will retry"))
          },
        )

        val next =
          service.prepare(key("sv2"), writeSnapshot(Future.successful(sha256))).futureValue
        eventually()(service.lookup(next) should matchPattern {
          case Some(SnapshotState.Ready(_, _)) =>
        })

        val child = blocker.future.futureValue
        Files.delete(child)
        service.removeExpired(clock.now).futureValue shouldBe 1
        Files.exists(child.getParent) shouldBe false
        service.lookup(failedId) shouldBe None
    }

    "retry an expired file deletion without retaining download eligibility or blocking other cleanup" in withService() {
      (service, clock, _) =>
        val first =
          service.prepare(key("sv1"), writeSnapshot(Future.successful(sha256))).futureValue
        val second =
          service.prepare(key("sv2"), writeSnapshot(Future.successful(sha256))).futureValue
        def fileOf(id: SvOnboardingSnapshotId): Path = eventually() {
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
          service.prepare(key("sv1"), writeSnapshot(Future.successful(sha256))).futureValue
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
              key("sv1"),
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
              key("sv2"),
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
              service.prepare(key("sv1"), _ => Future.successful(sha256)).failed.futureValue
            ) { case e: StatusRuntimeException =>
              e.getStatus.getCode shouldBe Status.Code.UNAVAILABLE
            }
          } finally exported.trySuccess(())

          closed.futureValue
          service.lookup(active) shouldBe None
          Files.readAllBytes(file) shouldBe Array[Byte](1, 2)
          Files.exists(file.getParent.resolve("metadata.json")) shouldBe true
          queuedStarted.get() shouldBe 0
      }
    }

    "recover completed snapshots with the same id and original retention after restart" in File
      .usingTemporaryDirectory() { directory =>
        val config = SvOnboardingSnapshotsConfig(directory = Some(directory.path))
        val id = new java.util.concurrent.atomic.AtomicReference[SvOnboardingSnapshotId]()
        withService(config) { (service, clock, _) =>
          clock.advance(Duration.ofHours(1))
          id.set(service.prepare(key("sv1"), writeSnapshot(Future.successful(sha256))).futureValue)
          eventually()(service.lookup(id.get()) should matchPattern {
            case Some(SnapshotState.Ready(_, _)) =>
          })
        }
        withService(config) { (service, clock, _) =>
          inside(service.lookup(id.get())) { case Some(SnapshotState.Ready(file, digest)) =>
            File(file).contentAsString shouldBe "snapshot"
            digest shouldBe sha256
          }
          service
            .prepare(key("sv1"), _ => fail("must reuse the persisted export"))
            .futureValue shouldBe id.get()
          clock.advance(Duration.ofHours(24))
          service.removeExpired(clock.now).futureValue shouldBe 0
          service.lookup(id.get()) should matchPattern { case Some(SnapshotState.Ready(_, _)) => }
          clock.advance(Duration.ofHours(1))
          service.removeExpired(clock.now).futureValue shouldBe 1
          service.lookup(id.get()) shouldBe None
          service
            .prepare(key("sv1"), writeSnapshot(Future.successful(sha256)))
            .futureValue should not be id.get()
        }
      }

    "recover sequencer onboarding snapshots after restart" in File.usingTemporaryDirectory() {
      directory =>
        val config = SvOnboardingSnapshotsConfig(directory = Some(directory.path))
        val sequencerKey = SnapshotKey.SequencerOnboardingState(
          SynchronizerId.tryFromString("synchronizer::dummy"),
          SequencerId(UniqueIdentifier.tryFromProtoPrimitive("sequencer::dummy")),
        )
        val id = new java.util.concurrent.atomic.AtomicReference[SvOnboardingSnapshotId]()
        withService(config) { (service, _, _) =>
          id.set(
            service.prepare(sequencerKey, writeSnapshot(Future.successful(sha256))).futureValue
          )
          eventually()(service.lookup(id.get()) should matchPattern {
            case Some(SnapshotState.Ready(_, _)) =>
          })
        }
        withService(config) { (service, _, _) =>
          service
            .prepare(sequencerKey, _ => fail("must reuse the persisted export"))
            .futureValue shouldBe id.get()
          service
            .prepare(key("sv1"), writeSnapshot(Future.successful(sha256)))
            .futureValue should not be id.get()
        }
    }

    "discard interrupted exports without touching downloads or completed exports on startup" in File
      .usingTemporaryDirectory() { directory =>
        val interrupted = directory / "exports" / UUID.randomUUID().toString
        (interrupted / "snapshot").createIfNotExists(createParents = true)
        (interrupted / "metadata.tmp").writeText("unfinished metadata")
        val download =
          (directory / "downloads" / "acs" / "snapshot").createIfNotExists(createParents = true)
        val otherFile = (directory / "other").createIfNotExists()
        withService(SvOnboardingSnapshotsConfig(directory = Some(directory.path))) { (_, _, _) =>
          interrupted.exists shouldBe false
          download.exists shouldBe true
          otherFile.exists shouldBe true
        }
      }

    "expire abandoned downloads while preserving recently written downloads" in File
      .usingTemporaryDirectory() { directory =>
        val abandoned =
          (directory / "downloads" / "old" / "snapshot").createIfNotExists(createParents = true)
        val recent =
          (directory / "downloads" / "recent" / "snapshot").createIfNotExists(createParents = true)
        withService(SvOnboardingSnapshotsConfig(directory = Some(directory.path))) {
          (service, clock, _) =>
            abandoned.exists shouldBe true
            Files.setLastModifiedTime(abandoned.path, FileTime.from(Instant.EPOCH))
            Files.setLastModifiedTime(abandoned.parent.path, FileTime.from(Instant.EPOCH))
            service.removeExpired(clock.now).futureValue shouldBe 1
            abandoned.parent.exists shouldBe false
            clock.advance(Duration.ofDays(365 * 100))
            service.removeExpired(clock.now).futureValue shouldBe 0
            recent.exists shouldBe true
        }
      }

    "not reuse an expired snapshot while waiting for cleanup" in withService() {
      (service, clock, _) =>
        val id = service.prepare(key("sv1"), writeSnapshot(Future.successful(sha256))).futureValue
        eventually()(service.lookup(id) should matchPattern {
          case Some(SnapshotState.Ready(_, _)) =>
        })
        clock.advance(Duration.ofHours(24))
        service
          .prepare(key("sv1"), writeSnapshot(Future.successful(sha256)))
          .futureValue should not be id
    }

    "discard snapshots stored without a SHA-256 digest on startup" in File
      .usingTemporaryDirectory() { directory =>
        val stored = directory / "exports" / UUID.randomUUID().toString
        (stored / "snapshot").createIfNotExists(createParents = true).writeText("snapshot")
        (stored / "metadata.json").writeText(
          """{"synchronizerId":"synchronizer::dummy","party":"sv1::dummy","activationTime":"1970-01-01T00:00:00Z","createdAt":"1970-01-01T00:00:00Z","size":8}"""
        )
        withService(SvOnboardingSnapshotsConfig(directory = Some(directory.path))) {
          (service, _, _) =>
            stored.exists shouldBe false
            service.lookup(SvOnboardingSnapshotId(stored.name)) shouldBe None
        }
      }

    "discard corrupt completion metadata on startup" in File.usingTemporaryDirectory() {
      directory =>
        val corrupt = directory / "exports" / UUID.randomUUID().toString
        (corrupt / "snapshot").createIfNotExists(createParents = true)
        (corrupt / "metadata.json").writeText("not json")
        loggerFactory.assertLogs(SuppressionRule.LevelAndAbove(Level.WARN))(
          withService(SvOnboardingSnapshotsConfig(directory = Some(directory.path))) { (_, _, _) =>
            corrupt.exists shouldBe false
          },
          _.warningMessage should include("Cannot recover onboarding snapshot"),
        )
    }

    "never resolve a download id outside the export directory" in withService() { (service, _, _) =>
      service.lookup(SvOnboardingSnapshotId("../../other")) shouldBe None
    }

    "reject exports after shutdown" in withService() { (service, _, retryProvider) =>
      retryProvider.close()
      inside(
        service.prepare(key("sv1"), _ => Future.successful(sha256)).failed.futureValue
      ) { case e: StatusRuntimeException =>
        e.getStatus.getCode shouldBe Status.Code.UNAVAILABLE
      }
    }
  }
}
