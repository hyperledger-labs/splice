// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.NotUsed
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.stream.scaladsl.{Flow, Source}
import org.lfdecentralizedtrust.splice.admin.http.HttpErrorWithHttpCode
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftCallExecutor.NoScanWillHaveData
import org.lfdecentralizedtrust.splice.scan.config.BulkStorageConfig
import org.lfdecentralizedtrust.splice.scan.util.PeerBftScanConnection
import org.lfdecentralizedtrust.splice.store.S3BucketConnection
import org.lfdecentralizedtrust.splice.store.S3BucketConnection.ObjectKeyAndChecksum

import scala.concurrent.{ExecutionContextExecutor, Future}

// TODO(#5884): review parallelism here. We use parallelism = 1 all over, but unsure whether that's actually necessary.

class BulkStorageCommitFromStaging[T](
    stagingS3Connection: S3BucketConnection,
    committedS3Connection: S3BucketConnection,
    getObjects: T => Future[Seq[ObjectKeyAndChecksum]],
    getRequiredCatchupTimestamp: T => CantonTimestamp,
    appConfig: BulkStorageConfig,
    scanConnection: PeerBftScanConnection,
    override val loggerFactory: NamedLoggerFactory,
    onObjectCommitted: Seq[ObjectKeyAndChecksum] => Unit = _ => (),
)(implicit
    tc: TraceContext,
    ec: ExecutionContextExecutor,
) extends NamedLogging {

  private def checkBftForObjects(
      requiredCatchupTimestamp: CantonTimestamp,
      objects: Seq[ObjectKeyAndChecksum],
  ): Future[Boolean] = {
    logger.debug(
      s"Checking BFT agreement for objects: ${objects.map(_.key).mkString(", ")} (requires catchup to $requiredCatchupTimestamp)"
    )
    if (appConfig.bftCheckEnabled) {
      (for {
        connection <- scanConnection.connection
        bft <- connection
          .getBulkObjectChecksums(requiredCatchupTimestamp, objects.map(_.key))
          .map(Some(_))
          .recoverWith {
            case ex @ HttpErrorWithHttpCode(StatusCodes.ServiceUnavailable, _) =>
              logger.debug("Not enough scans have the data yet, will retry after delay")
              Future.successful(None)
            case ex @ HttpErrorWithHttpCode(StatusCodes.BadGateway, _) =>
              logger.error(
                "Could not reach consensus on checksums for objects. This indicates that different peers have different data, and must be investigated."
              )
              Future.successful(None)
          }
      } yield {
        bft match {
          case Some(bftChecksums) =>
            // Consensus achieved from peers, comparing the consensus checksums to mine.
            val consensusChecksums = bftChecksums.checksums.filter(_.value.isDefined)
            logger.debug(
              s"Consensus achieved on ${consensusChecksums.length} out of ${objects.length} objects"
            )

            if (consensusChecksums.length < objects.length) {
              logger.error(
                s"Not all objects are known to the BFT peers, despite them indicating that they have caught up to the required timestamp. This indicates that different peers have different data, and must be investigated."
              )
              false
            } else {
              logger.debug(
                s"All objects are known to the BFT peers. Checking if checksums match."
              )
              val consensus =
                bftChecksums.checksums.filter(_.value.isDefined).map(_.value) == objects.map(oc =>
                  Some(oc.checksum)
                )
              if (!consensus) {
                logger.error(
                  s"Checksums do not match for objects ${objects.map(_.key).mkString(", ")}. My checksums are: ${objects
                      .map(_.checksum)
                      .mkString(", ")}, consensus checksums are: ${consensusChecksums.mkString(", ")}"
                )

                if (appConfig.debugObjectsToNotCommit.intersect(objects.map(_.key)).nonEmpty) {
                  logger.debug(
                    s"Some relevant objects are listed in debugObjectsToNotCommit, will ignore them for the consensus check. Ignored objects: ${appConfig.debugObjectsToNotCommit
                        .intersect(objects.map(_.key))
                        .mkString(", ")}"
                  )
                  val objectsWithConsensusChecksums = objects.zip(consensusChecksums)
                  // Filter out objects for which the key is listed in appConfig.debugObjectsToNotCommit
                  val unignoredObjectsWithTheirConsensusChecksums =
                    objectsWithConsensusChecksums.filter { case (obj, _) =>
                      !appConfig.debugObjectsToNotCommit.contains(obj.key)
                    }
                  val unignoredObjectsWithMyChecksums =
                    objects.filter(obj => !appConfig.debugObjectsToNotCommit.contains(obj.key))
                  // recheck consensus, but now only on the unignored objects. The comparison should be similar to val consensus above
                  val unignoredConsensus =
                    unignoredObjectsWithTheirConsensusChecksums
                      .filter(_._2.value.isDefined)
                      .map(_._2.value) == unignoredObjectsWithMyChecksums.map(oc =>
                      Some(oc.checksum)
                    )

                  if (!unignoredConsensus) {
                    logger.error(
                      s"Checksums still do not match for unignored objects ${unignoredObjectsWithMyChecksums
                          .map(_.key)
                          .mkString(", ")}. Expected: ${unignoredObjectsWithMyChecksums
                          .map(_.checksum)
                          .mkString(", ")}, got: ${unignoredObjectsWithTheirConsensusChecksums.map(_._2.value).mkString(", ")}"
                    )
                  } else {
                    logger.debug(
                      s"After ignoring objects from the config, Checksums match ${unignoredObjectsWithMyChecksums.map(_.key).mkString(", ")}. Proceeding with commit."
                    )
                  }
                  unignoredConsensus
                } else {
                  logger.trace(
                    s"No relevant objects are listed in debugObjectsToNotCommit, will not ignore any objects for the consensus check."
                  )
                  consensus
                }
              } else {
                logger.trace(
                  s"Checksums match for all objects ${objects.map(_.key).mkString(", ")}. Proceeding with commit."
                )
                true
              }
            }
          case None =>
            // No consensus yet
            false
        }
      }).recover { case _: NoScanWillHaveData =>
        logger.info(
          s"No peer will ever hold objects ${objects.map(_.key).mkString(", ")}, committing them on this Scan's own checksums"
        )
        true
      }
    } else {
      logger.trace("BFT check is disabled, skipping BFT agreement check")
      Future.successful(true)
    }
  }

  private def waitForBftAgreement: Flow[
    (T, Seq[ObjectKeyAndChecksum]),
    (T, Seq[ObjectKeyAndChecksum]),
    NotUsed,
  ] = {
    Flow[(T, Seq[ObjectKeyAndChecksum])].flatMapConcat { case (t, obj) =>
      Source
        .repeat(obj)
        .mapAsync(parallelism = 1)(obj =>
          checkBftForObjects(getRequiredCatchupTimestamp(t), obj).map(result => (obj, result))
        )
        .flatMapConcat {
          case (obj, true) =>
            logger.debug(
              s"BFT agreement reached for the objects of $t. Proceeding with commit."
            )
            Source.single((obj, true))

          case (obj, false) =>
            logger.debug(
              s"BFT agreement not yet reached for the objects at $t. Will retry after delay."
            )
            Source.single((obj, false)).delay(appConfig.bftRetryInterval.underlying)
        }
        .takeWhile({ case (_, bftReached) => !bftReached }, inclusive = true)
        .collect { case (o, true) => (t, o) }
    }
  }

  private def copyObjectToCommitted(
      stagingS3Connection: S3BucketConnection,
      committedS3Connection: S3BucketConnection,
  )(
      obj: S3BucketConnection.ObjectKeyAndChecksum
  ): Future[Unit] = {
    committedS3Connection.doesObjectExist(obj.key).flatMap {
      case true =>
        logger.debug(
          s"Object ${obj.key} already exists in committed storage, this may happen e.g. if we restarted before copying all objects and deleting them from staging. Skipping copy"
        )
        Future.unit
      case false =>
        if (appConfig.debugObjectsToNotCommit.contains(obj.key)) {
          logger.debug(
            s"Object ${obj.key} is listed in debugObjectsToNotCommit, skipping copy to committed storage"
          )
          Future.unit
        } else {
          logger.debug(s"Copying object ${obj.key} from staging to committed storage")
          committedS3Connection.copyObject(stagingS3Connection.bucketName, obj.key)
        }
    }
  }

  private def copyToCommitted: Flow[
    (T, Seq[ObjectKeyAndChecksum]),
    (T, Seq[ObjectKeyAndChecksum]),
    NotUsed,
  ] =
    Flow[(T, Seq[ObjectKeyAndChecksum])]
      .mapAsync(parallelism = 1) { case (ts, objs) =>
        logger.debug(
          s"Copying ${objs.size} objects from staging to committed storage for timestamp $ts"
        )
        Future
          .sequence(objs.map(copyObjectToCommitted(stagingS3Connection, committedS3Connection)))
          .map { _ =>
            onObjectCommitted(objs)
            (ts, objs)
          }
      }

  private def deleteFromStaging: Flow[
    (T, Seq[ObjectKeyAndChecksum]),
    T,
    NotUsed,
  ] =
    Flow[(T, Seq[ObjectKeyAndChecksum])]
      .mapAsync(parallelism = 1) { case (ts, objs) =>
        logger.debug(
          s"Deleting ${objs.size} objects from staging storage for timestamp $ts"
        )
        Future
          .sequence(
            objs.map(obj => {
              logger.debug(s"Deleting object ${obj.key} from staging storage")
              stagingS3Connection
                .deleteObject(obj.key)
                .map(_ => logger.debug(s"Deleted object ${obj.key} from staging storage"))
            })
          )
          .map(_ => ts)
      }
      .wireTap(ts => logger.debug(s"Successfully deleted objects from staging for timestamp $ts"))

  def getFlow: Flow[T, T, NotUsed] =
    Flow[T]
      .mapAsync(parallelism = 1)(ts => getObjects(ts).map((ts, _)))
      .via(waitForBftAgreement)
      .via(copyToCommitted)
      .via(deleteFromStaging)
      .wireTap(ts => logger.debug(s"Successfully committed objects for timestamp $ts"))
}

object BulkStorageCommitFromStaging {
  def apply[T](
      stagingS3Connection: S3BucketConnection,
      committedS3Connection: S3BucketConnection,
      getStagingObjects: T => Future[Seq[ObjectKeyAndChecksum]],
      getRequiredCatchupTimestamp: T => CantonTimestamp,
      appConfig: BulkStorageConfig,
      scanConnection: PeerBftScanConnection,
      loggerFactory: NamedLoggerFactory,
      onObjectCommitted: Seq[ObjectKeyAndChecksum] => Unit = _ => (),
  )(implicit
      tc: TraceContext,
      ec: ExecutionContextExecutor,
  ): Flow[T, T, NotUsed] = {
    new BulkStorageCommitFromStaging[T](
      stagingS3Connection,
      committedS3Connection,
      getStagingObjects,
      getRequiredCatchupTimestamp,
      appConfig,
      scanConnection,
      loggerFactory,
      onObjectCommitted,
    ).getFlow
  }
}
