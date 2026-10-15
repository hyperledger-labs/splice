// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk.backfilling

import cats.data.NonEmptyList
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.http.scaladsl.model.{StatusCodes, Uri}
import org.lfdecentralizedtrust.splice.admin.http.HttpErrorWithHttpCode
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftCallExecutor.NoScanWillHaveData
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient.BulkStorageObjects
import org.lfdecentralizedtrust.splice.scan.config.ScanStorageConfig.Encoding
import org.lfdecentralizedtrust.splice.scan.util.PeerBftScanConnection
import org.lfdecentralizedtrust.splice.store.S3BucketConnection.ObjectKeyAndChecksum

import scala.concurrent.{ExecutionContext, Future}

/** What the peers can give for a listing: the listed objects, not yet (a peer holds them later, or a configured peer
  * cannot be reached right now), or never (every configured peer says it will never hold them).
  */
sealed trait PeerListing[+T]

object PeerListing {
  case object NotAvailableYet extends PeerListing[Nothing]
  case object NoPeerWillHold extends PeerListing[Nothing]
  final case class Available[T](value: T) extends PeerListing[T]
}

final case class ObjectsOnPeers(objects: Seq[ObjectKeyAndChecksum], peers: Seq[Uri])

object ObjectsOnPeers {
  def objectsIn(perEncoding: NonEmptyList[ObjectsOnPeers]): Seq[ObjectKeyAndChecksum] =
    perEncoding.toList.flatMap(_.objects)
}

final case class SnapshotOnPeers(
    recordTime: CantonTimestamp,
    perEncoding: NonEmptyList[ObjectsOnPeers],
)

trait BulkObjectListing {
  def updateObjectsPage(
      startRecordTime: CantonTimestamp,
      endRecordTime: CantonTimestamp,
      pageSize: Int,
      availableAt: CantonTimestamp,
  )(implicit tc: TraceContext): Future[PeerListing[NonEmptyList[ObjectsOnPeers]]]

  def snapshotObjectsAtOrBefore(recordTime: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[PeerListing[Option[SnapshotOnPeers]]]
}

class BftBulkObjectListing(
    peerConnection: PeerBftScanConnection,
    override val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends BulkObjectListing
    with NamedLogging {

  override def updateObjectsPage(
      startRecordTime: CantonTimestamp,
      endRecordTime: CantonTimestamp,
      pageSize: Int,
      availableAt: CantonTimestamp,
  )(implicit tc: TraceContext): Future[PeerListing[NonEmptyList[ObjectsOnPeers]]] =
    inEveryEncoding(encoding =>
      peerConnection.connection.flatMap(
        _.listBulkUpdateHistoryObjectsWithPeers(
          startRecordTime,
          endRecordTime,
          pageSize,
          availableAt,
          encoding,
        )
      )
    )(perEncoding =>
      PeerListing.Available(perEncoding.map { case (page, peers) =>
        ObjectsOnPeers(page.objects, peers)
      })
    )

  override def snapshotObjectsAtOrBefore(recordTime: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[PeerListing[Option[SnapshotOnPeers]]] =
    inEveryEncoding(encoding =>
      peerConnection.connection.flatMap(
        _.listBulkAcsSnapshotObjectsWithPeers(recordTime, encoding)
      )
    )(sameSnapshotInEveryEncoding(recordTime, _))

  private def sameSnapshotInEveryEncoding(
      requested: CantonTimestamp,
      perEncoding: NonEmptyList[(Option[BulkStorageObjects.SnapshotObjects], Seq[Uri])],
  )(implicit tc: TraceContext): PeerListing[Option[SnapshotOnPeers]] = {
    val recordTimes = perEncoding.map { case (snapshot, _) => snapshot.map(_.recordTime) }
    recordTimes.distinct match {
      case NonEmptyList(None, Nil) => PeerListing.Available(None)
      case NonEmptyList(Some(recordTime), Nil) =>
        val objectsOnPeers = perEncoding.map { case (snapshot, peers) =>
          ObjectsOnPeers(snapshot.fold(Seq.empty[ObjectKeyAndChecksum])(_.objects), peers)
        }
        PeerListing.Available(Some(SnapshotOnPeers(recordTime, objectsOnPeers)))
      case _ =>
        logger.warn(
          s"The encodings list different snapshots at or before $requested: ${recordTimes.toList
              .mkString(", ")}. A snapshot is committed in every encoding together, so this indicates inconsistent " +
            "data on the peers; waiting"
        )
        PeerListing.NotAvailableYet
    }
  }

  private def inEveryEncoding[T, U](list: Encoding => Future[(T, List[Uri])])(
      merge: NonEmptyList[(T, Seq[Uri])] => PeerListing[U]
  ): Future[PeerListing[U]] =
    oneEncodingAfterTheOther(encoding => withPeers(list(encoding))).map { listings =>
      listings.traverse[Option, (T, Seq[Uri])] {
        case PeerListing.Available(listed) => Some(listed)
        case PeerListing.NotAvailableYet | PeerListing.NoPeerWillHold => None
      } match {
        case Some(available) => merge(available)
        case None if listings.forall(_ == PeerListing.NoPeerWillHold) => PeerListing.NoPeerWillHold
        case None => PeerListing.NotAvailableYet
      }
    }

  private def oneEncodingAfterTheOther[T](
      listOne: Encoding => Future[T]
  ): Future[NonEmptyList[T]] =
    Encoding.all.tail.foldLeft(listOne(Encoding.all.head).map(NonEmptyList.one)) {
      (listedSoFar, encoding) => listedSoFar.flatMap(listed => listOne(encoding).map(listed :+ _))
    }

  private def withPeers[T](call: Future[(T, List[Uri])]): Future[PeerListing[(T, Seq[Uri])]] =
    call
      .map[PeerListing[(T, Seq[Uri])]] { case (value, peers) =>
        PeerListing.Available((value, peers))
      }
      .recover {
        case HttpErrorWithHttpCode(StatusCodes.ServiceUnavailable, _) =>
          PeerListing.NotAvailableYet
        case _: NoScanWillHaveData => PeerListing.NoPeerWillHold
      }
}
