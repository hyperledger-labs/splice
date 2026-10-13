// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.logging.{LogEntry, SuppressionRule}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import org.apache.pekko.NotUsed
import org.apache.pekko.http.scaladsl.model.Uri
import org.lfdecentralizedtrust.splice.config.NetworkAppClientConfig
import org.lfdecentralizedtrust.splice.scan.config.ScanAppClientConfig
import org.lfdecentralizedtrust.splice.test.HasRetryProvider
import org.slf4j.event.Level
import org.apache.pekko.stream.scaladsl.{Flow, Keep}
import org.apache.pekko.stream.testkit.scaladsl.{TestSink, TestSource}
import org.lfdecentralizedtrust.splice.environment.SpliceLedgerClient
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.http.v0.definitions.{
  BulkObjectsAvailability,
  BulkStorageBucket,
  GetBulkObjectChecksumsResponse,
  GetBulkObjectsProgressResponse,
}
import org.lfdecentralizedtrust.splice.scan.admin.api.client.{
  BftScanConnection,
  SingleScanConnection,
}
import org.lfdecentralizedtrust.splice.scan.config.BulkStorageConfig
import org.lfdecentralizedtrust.splice.store.S3BucketConnection.ObjectKeyAndChecksum
import org.lfdecentralizedtrust.splice.store.{HasS3Mock, StoreTestBase}
import org.lfdecentralizedtrust.splice.store.db.SplicePostgresTest
import org.lfdecentralizedtrust.splice.util.TemplateJsonDecoder
import org.lfdecentralizedtrust.splice.scan.util.PeerBftScanConnection
import org.scalatest.Assertion

import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.concurrent.duration.*

class BulkStorageCommitFromStagingTest
    extends StoreTestBase
    with HasExecutionContext
    with HasActorSystem
    with HasS3Mock
    with SplicePostgresTest
    with HasRetryProvider {

  override val initialBuckets = Seq("staging", "committed")
  implicit val httpClient: HttpClient = null
  implicit val templateJsonDecoder: TemplateJsonDecoder = null

  "BulkStorageCommitFromStaging" should {
    val appConfig = BulkStorageConfig(
      bftCheckEnabled = false
    )

    "successfully move objects from staging to committed S3 bucket" in {

      val (stagingS3Connection, committedS3Connection, objsWithDigests) = setupTest

      triggerCopyFlowAndAssertCompletion(
        newCopyFlow(stagingS3Connection, committedS3Connection, objsWithDigests)
      )

      assertObjectsMoved(stagingS3Connection, committedS3Connection, objsWithDigests)
    }

    "skip previously copied objects" in {
      val (stagingS3Connection, committedS3Connection, objsWithDigests) = setupTest

      /* Pre-copy one object to the committed bucket to simulate the pipeline/app restarting during the copy process.
       * The copy flow should skip this object and not attempt to copy it again.
       */
      val preCopiedObject = objsWithDigests.head
      committedS3Connection
        .copyObject(stagingS3Connection.bucketName, preCopiedObject.key)
        .futureValue

      loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.DEBUG))(
        {
          triggerCopyFlowAndAssertCompletion(
            newCopyFlow(stagingS3Connection, committedS3Connection, objsWithDigests)
          )
        },
        logEntries => forExactly(1, logEntries)(_.message should include("Skipping copy")),
      )

      assertObjectsMoved(stagingS3Connection, committedS3Connection, objsWithDigests)
    }

    def newCopyFlow(
        stagingS3Connection: S3BucketConnectionForUnitTests,
        committedS3Connection: S3BucketConnectionForUnitTests,
        objsWithDigests: Seq[ObjectKeyAndChecksum],
    ) = {
      BulkStorageCommitFromStaging[String](
        stagingS3Connection,
        committedS3Connection,
        _ => Future.successful(objsWithDigests),
        _ => CantonTimestamp.MinValue,
        appConfig,
        null, // not used when bft reads are disabled
        loggerFactory,
      )
    }

  }

  "BulkStorageCommitFromStaging with BFT reads enabled" should {
    val appConfig = BulkStorageConfig(
      bftRetryInterval = NonNegativeFiniteDuration.ofSeconds(1)
    )

    "successfully move objects from staging to committed S3 bucket when there's full consensus" in {
      val (stagingS3Connection, committedS3Connection, objsWithDigests) = setupTest

      val mockScanConnections = new MockScanConnections(objsWithDigests)
      Seq.range(0, mockScanConnections.nrResponses).foreach { i =>
        mockScanConnections.scanAgrees(i)
      }

      val flow = newCopyFlow(
        stagingS3Connection,
        committedS3Connection,
        objsWithDigests,
        mockScanConnections,
      )

      triggerCopyFlowAndAssertCompletion(flow)

      assertObjectsMoved(stagingS3Connection, committedS3Connection, objsWithDigests)
    }

    "commit on its own checksums when every peer will never have the objects" in {
      val (stagingS3Connection, committedS3Connection, objsWithDigests) = setupTest

      val mockScanConnections = new MockScanConnections(objsWithDigests)
      Seq.range(0, mockScanConnections.nrResponses).foreach { i =>
        mockScanConnections.scanWillNeverHaveData(i)
      }

      val flow = newCopyFlow(
        stagingS3Connection,
        committedS3Connection,
        objsWithDigests,
        mockScanConnections,
      )

      triggerCopyFlowAndAssertCompletion(flow)

      assertObjectsMoved(stagingS3Connection, committedS3Connection, objsWithDigests)
    }

    "wait until all objects are known to the peers, and report disagreement on consensus correctly" in {
      val (stagingS3Connection, committedS3Connection, objsWithDigests) = setupTest

      val mockScanConnections = new MockScanConnections(objsWithDigests)

      val flow = newCopyFlow(
        stagingS3Connection,
        committedS3Connection,
        objsWithDigests,
        mockScanConnections,
      )

      val (pub, sub) = TestSource
        .probe[String]
        .via(flow)
        .toMat(TestSink.probe[String])(Keep.both)
        .run()
      sub.request(1)
      pub.sendNext("go")

      final case class AssertLogsSeqStep(
          within: () => Assertion,
          assertion: Seq[LogEntry] => Assertion,
      )

      def assertLogsSeqStep(
          within: => Assertion,
          assertion: Seq[LogEntry] => Assertion,
      ): AssertLogsSeqStep = AssertLogsSeqStep(() => within, assertion)

      def multiStepAssertLogsSeq(
          rule: SuppressionRule
      )(steps: Seq[AssertLogsSeqStep]): Assertion =
        loggerFactory.suppress(rule) {
          steps.map { case AssertLogsSeqStep(within, assertion) =>
            loggerFactory.runWithCleanup(
              within(),
              (_: Assertion) => loggerFactory.checkLogsAssertion(assertion),
              () => (),
            )
          }
          succeed
        }

      def assertNoObjectsCopied = {
        sub.expectNoMessage(5.seconds)
        stagingS3Connection.listObjects.futureValue
          .contents()
          .asScala should have size objsWithDigests.size.toLong
        committedS3Connection.listObjects.futureValue.contents().asScala shouldBe empty
      }

      // The errors continue beyond every step, until the changes in the following step are made,
      // so we put all the steps in one big log suppression, to avoid the late errors failing the log checker
      multiStepAssertLogsSeq(SuppressionRule.LevelAndAbove(Level.DEBUG))(
        Seq(
          assertLogsSeqStep(
            clue(
              "When no peers have any data, the copy flow should not complete, but should not emit any warnings or errors"
            ) {
              Seq
                .range(0, mockScanConnections.nrResponses)
                .foreach(i => mockScanConnections.setHasNoDataResponse(i))
              assertNoObjectsCopied
            },
            (logEntries: Seq[LogEntry]) => {
              forAll(logEntries)(entry => {
                entry.level should not be Level.ERROR
                entry.level should not be Level.WARN
              })
              forAtLeast(1, logEntries)(entry => {
                entry.message should include(
                  "Not enough scans have the data yet. 0 scans have data, 7 have responded with 'not yet'"
                )
              })
            },
          ),
          assertLogsSeqStep(
            clue("One scan gets the data, that's still not enough for the copy flow to complete") {
              mockScanConnections.scanAgrees(0)
              assertNoObjectsCopied

            },
            (logEntries: Seq[LogEntry]) => {
              forAll(logEntries)(entry => {
                entry.level should not be Level.ERROR
                entry.level should not be Level.WARN
              })
              forAtLeast(1, logEntries)(entry => {
                entry.message should include(
                  "Not enough scans have the data yet. 1 scans have data, 6 have responded with 'not yet'"
                )
              })
            },
          ),
          assertLogsSeqStep(
            clue(
              "When one object is not known to the peers, the copy flow should not complete, with an error emitted"
            ) {
              Seq.range(0, 2).foreach(i => mockScanConnections.scanAgrees(i))
              Seq
                .range(2, mockScanConnections.nrResponses)
                .foreach(i => mockScanConnections.scanMissingAnObject(i, 1))

              assertNoObjectsCopied

            },
            (logEntries: Seq[LogEntry]) => {
              forAtLeast(1, logEntries)(entry => {
                entry.level shouldBe Level.ERROR
                entry.message should include(
                  "Not all objects are known to the BFT peers, despite them indicating that they have caught up to the required timestamp."
                )
              })
              forAtLeast(1, logEntries)(entry => {
                entry.level shouldBe Level.WARN
                entry.message should (include(
                  "The following Scan URLs disagreed with consensus"
                ) and include("scan_0"))
              })

            },
          ),
          assertLogsSeqStep(
            clue(
              "Simulate a majority disagreeing with our digests, the copy flow should not complete and an error should be emitted"
            ) {
              Seq.range(2, 7).foreach(i => mockScanConnections.scanDisagreesOnDigest(i, 1))
              assertNoObjectsCopied

            },
            (logEntries: Seq[LogEntry]) =>
              forAtLeast(1, logEntries)(entry => {
                entry.level shouldBe Level.ERROR
                entry.message should include(
                  "Checksums do not match for objects"
                )
              }),
          ),
          assertLogsSeqStep(
            clue(
              "Enough scans do agree - the copy flow should complete successfully, but still warn about those that did not agree"
            ) {
              Seq.range(2, 5).foreach(i => mockScanConnections.scanAgrees(i))
              sub.expectNext(5.seconds, "go")
              assertObjectsMoved(stagingS3Connection, committedS3Connection, objsWithDigests)
            },
            (logEntries: Seq[LogEntry]) =>
              forAtLeast(1, logEntries)(entry => {
                entry.level shouldBe Level.WARN
                entry.message should (include(
                  "The following Scan URLs disagreed with consensus"
                ) and include("scan_5") and include("scan_6"))
              }),
          ),
        )
      )
    }

    "ignore digest mismatches for objects listed in debugObjectsToNotCommit and not copy them to the committed bucket" in {
      val (stagingS3Connection, committedS3Connection, objsWithDigests) = setupTest

      val ignoredObject = objsWithDigests(1)

      val mockScanConnections = new MockScanConnections(objsWithDigests)
      // all peers disagree with us on the digest of the ignored object only
      Seq.range(0, 7).foreach(i => mockScanConnections.scanDisagreesOnDigest(i, 1))

      val flow = newCopyFlow(
        stagingS3Connection,
        committedS3Connection,
        objsWithDigests,
        mockScanConnections,
        appConfig.copy(debugObjectsToNotCommit = Seq(ignoredObject.key)),
      )

      loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.ERROR))(
        {
          triggerCopyFlowAndAssertCompletion(flow)
        },
        logEntries =>
          forAll(logEntries)(
            _.message should include("Checksums do not match for objects")
          ),
      )

      val expectedCommittedObjects = objsWithDigests.filterNot(_.key == ignoredObject.key)

      clue("All objects have been deleted from staging") {
        stagingS3Connection.listObjects.futureValue.contents().asScala shouldBe empty
      }
      clue("Only the non-ignored objects have been copied to the committed bucket") {
        committedS3Connection.listObjects.futureValue
          .contents()
          .asScala
          .map(_.key()) should contain theSameElementsAs expectedCommittedObjects.map(_.key)
      }
      clue("Checksums of objects in committed S3 bucket match the expected digests") {
        committedS3Connection
          .getChecksums(expectedCommittedObjects.map(_.key))
          .futureValue should contain theSameElementsAs expectedCommittedObjects
      }
    }

    class MockScanConnections(
        objsWithDigests: Seq[ObjectKeyAndChecksum]
    ) {
      val nrResponses = 7
      private val responses
          : Seq[AtomicReference[Option[(Boolean, GetBulkObjectChecksumsResponse)]]] =
        Seq.fill(nrResponses)(
          new AtomicReference[Option[(Boolean, GetBulkObjectChecksumsResponse)]](None)
        )
      private val neverHaveData: Seq[AtomicBoolean] =
        Seq.fill(nrResponses)(new AtomicBoolean(false))

      private val singleScanConnections: Seq[SingleScanConnection] =
        Seq.range(0, nrResponses).map { i =>
          val mockConn = mock[SingleScanConnection]
          when(mockConn.config) thenReturn ScanAppClientConfig(
            NetworkAppClientConfig(
              Uri(s"http://dummy-admin-$i")
            )
          )
          when(mockConn.url) thenReturn Uri(s"http://scan_$i")
          when(
            mockConn.getBulkObjectChecksums(any[CantonTimestamp], any[Seq[String]])(
              any[ExecutionContext],
              any[TraceContext],
            )
          ).thenAnswer {
            responses(i).get() match {
              case Some((false, _)) =>
                Future.failed[GetBulkObjectChecksumsResponse](
                  new IllegalStateException(
                    s"Scan scan_$i has no data, getChecksums should not have been called"
                  )
                )
              case Some((true, checksums)) => Future.successful(checksums)
              case None =>
                Future.failed[GetBulkObjectChecksumsResponse](
                  new IllegalStateException(s"No response configured for scan_$i")
                )
            }
          }
          when(
            mockConn.getBulkObjectsProgress(any[CantonTimestamp], any[BulkStorageBucket])(
              any[ExecutionContext],
              any[TraceContext],
            )
          ).thenAnswer(
            responses(i).get() match {
              case _ if neverHaveData(i).get() =>
                Future.successful(
                  GetBulkObjectsProgressResponse(BulkObjectsAvailability.Backfilling)
                )
              case Some((hasData, _)) =>
                Future.successful(
                  GetBulkObjectsProgressResponse(
                    if (hasData) BulkObjectsAvailability.Available
                    else BulkObjectsAvailability.Processing
                  )
                )
              case None =>
                Future.failed[GetBulkObjectsProgressResponse](
                  new IllegalStateException(s"No response configured for scan_$i")
                )
            }
          )
          mockConn
        }

      private def setChecksumsResponse(idx: Integer, checksums: Seq[Option[String]]): Unit =
        responses(idx).set(
          Some(
            (
              true,
              new GetBulkObjectChecksumsResponse(
                checksums
                  .map(digest => new GetBulkObjectChecksumsResponse.Checksums(digest))
                  .toVector
              ),
            )
          )
        )

      def setHasNoDataResponse(idx: Integer): Unit =
        responses(idx).set(
          Some(
            (false, new GetBulkObjectChecksumsResponse(Vector.empty))
          )
        )

      def scanWillNeverHaveData(idx: Integer): Unit =
        neverHaveData(idx).set(true)

      def scanAgrees(idx: Integer): Unit =
        setChecksumsResponse(idx, objsWithDigests.map(obj => Some(obj.checksum)))

      def scanDisagreesOnDigest(scanIdx: Integer, objIdx: Integer): Unit =
        setChecksumsResponse(
          scanIdx,
          objsWithDigests.map(_.checksum).updated(objIdx, "wrong-digest").map(Some(_)),
        )

      def scanMissingAnObject(scanIdx: Integer, objIdx: Integer): Unit =
        setChecksumsResponse(
          scanIdx,
          objsWithDigests.map(obj => Some(obj.checksum)).updated(objIdx, None),
        )

      private val scanList = new BftScanConnection.AllDsoScansBft(
        initialScanConnections = singleScanConnections,
        initialFailedConnections = Map.empty,
        connectionBuilder = _ => Future.failed(new RuntimeException("Shouldn't be refreshing!")),
        scanUrlsChangedCallback = _ => Future.unit,
        getScans = BftScanConnection.Bft.getScansInDsoRules,
        scansRefreshInterval = NonNegativeFiniteDuration.ofDays(10),
        retryProvider = testRetryProvider,
        loggerFactory = loggerFactory,
      )
      private val bftConnection = new BftScanConnection(
        amuletLedgerClient = mock[SpliceLedgerClient],
        amuletRulesCacheTimeToLive = NonNegativeFiniteDuration.ofSeconds(1),
        scanList = scanList,
        clock = wallClock,
        retryProvider = testRetryProvider,
        loggerFactory = loggerFactory,
      )
      val peerBftConnection: PeerBftScanConnection = mock[PeerBftScanConnection]
      when(peerBftConnection.connection(any[TraceContext]))
        .thenReturn(Future.successful(bftConnection))
    }

    def newCopyFlow(
        stagingS3Connection: S3BucketConnectionForUnitTests,
        committedS3Connection: S3BucketConnectionForUnitTests,
        objsWithDigests: Seq[ObjectKeyAndChecksum],
        mockScanConnections: MockScanConnections,
        config: BulkStorageConfig = appConfig,
    ) = {
      new BulkStorageCommitFromStaging[String](
        stagingS3Connection,
        committedS3Connection,
        _ => Future.successful(objsWithDigests),
        _ => CantonTimestamp.MinValue,
        config,
        mockScanConnections.peerBftConnection,
        loggerFactory,
      ).getFlow
    }
  }

  private def triggerCopyFlowAndAssertCompletion(
      flow: Flow[String, String, NotUsed]
  ) = {
    val (pub, sub) = TestSource
      .probe[String]
      .via(flow)
      .toMat(TestSink.probe[String])(Keep.both)
      .run()

    try {
      sub.request(1)
      pub.sendNext("go")
      sub.expectNext("go")
      pub.sendComplete()
      sub.expectComplete()
    } catch {
      case ex: Throwable =>
        pub.sendError(ex)
        sub.cancel()
        throw ex
    }
  }

  private def assertObjectsMoved(
      stagingS3Connection: S3BucketConnectionForUnitTests,
      committedS3Connection: S3BucketConnectionForUnitTests,
      objsWithDigests: Seq[ObjectKeyAndChecksum],
  ) = {
    clue("All objects have been moved from staging to committed S3 bucket") {
      stagingS3Connection.listObjects.futureValue.contents().asScala shouldBe empty
      committedS3Connection.listObjects.futureValue
        .contents()
        .asScala
        .map(_.key()) should contain theSameElementsAs objsWithDigests.map(_.key)
    }
    clue("Checksums of objects in committed S3 bucket match the expected digests") {
      committedS3Connection
        .getChecksums(objsWithDigests.map(_.key))
        .futureValue should contain theSameElementsAs objsWithDigests
    }
  }

  private def setupTest = {
    val stagingS3Connection =
      new S3BucketConnectionForUnitTests(s3ConfigMock("staging"), loggerFactory)
    val committedS3Connection =
      new S3BucketConnectionForUnitTests(s3ConfigMock("committed"), loggerFactory)

    def createStagingObject(content: String) = {
      val md = MessageDigest.getInstance("SHA-256")
      md.update(content.getBytes)
      val digest = Base64.getEncoder.encodeToString(md.digest())
      val key = s"$content.txt"
      stagingS3Connection.createObject(key, content.getBytes).futureValue
      ObjectKeyAndChecksum(key, digest)
    }

    val objsWithDigests =
      Seq("object1", "object2", "object3").map(createStagingObject)
    (stagingS3Connection, committedS3Connection, objsWithDigests)
  }

  override protected def cleanDb(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[?] = resetAllAppTables(storage)
}
