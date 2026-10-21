// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.historystart

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import org.lfdecentralizedtrust.splice.scan.store.ScanKeyValueProvider

import scala.concurrent.{ExecutionContext, Future}

trait HistoryStartStore {
  def read(implicit tc: TraceContext): Future[Option[HistoryStart]]

  def recordOnce(start: HistoryStart)(implicit tc: TraceContext): Future[HistoryStart]

  def reset()(implicit tc: TraceContext): Future[Unit]
}

class KvHistoryStartStore(kvProvider: ScanKeyValueProvider)(implicit ec: ExecutionContext)
    extends HistoryStartStore {
  import KvHistoryStartStore.*

  override def read(implicit tc: TraceContext): Future[Option[HistoryStart]] =
    kvProvider.store.readValueAndLogOnDecodingFailure[HistoryStart](kvStoreKey).value

  override def recordOnce(start: HistoryStart)(implicit tc: TraceContext): Future[HistoryStart] =
    for {
      _ <- kvProvider.store.setValueIfNotExists(kvStoreKey, start)
      stored <- read
    } yield stored.getOrElse(
      throw new IllegalStateException(s"$kvStoreKey is missing right after it was recorded")
    )

  override def reset()(implicit tc: TraceContext): Future[Unit] =
    kvProvider.store.deleteKey(kvStoreKey)
}

object KvHistoryStartStore {
  val kvStoreKey = "scan_history_start"

  private implicit val timestampCodec: Codec[CantonTimestamp] =
    Codec
      .from[Long](implicitly, implicitly)
      .iemap(timestamp => CantonTimestamp.fromProtoPrimitive(timestamp).left.map(_.message))(
        _.toProtoPrimitive
      )

  implicit val historyStartCodec: Codec[HistoryStart] = deriveCodec[HistoryStart]
}

class ScanHistoryStart(
    store: HistoryStartStore,
    sources: HistoryStartSources,
    override val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends NamedLogging {

  def get(implicit tc: TraceContext): Future[Option[HistoryStart]] =
    store.read.flatMap {
      case Some(start) => Future.successful(Some(start))
      case None =>
        resolve.flatMap {
          case None =>
            logger.debug("The history start of this Scan cannot be determined yet")
            Future.successful(None)
          case Some(start) =>
            store.recordOnce(start).map { stored =>
              logger.info(s"Recorded the history start of this Scan: $stored")
              Some(stored)
            }
        }
    }

  def forgetRecorded()(implicit tc: TraceContext): Future[Unit] =
    store.reset().map { _ =>
      logger.warn(
        "Forgot the recorded history start of this Scan, it is determined again from the current data"
      )
    }

  private def resolve(implicit tc: TraceContext): Future[Option[HistoryStart]] =
    if (sources.isFoundingSv) Future.successful(Some(HistoryStart.Genesis))
    else
      sources.historyBackfilledFromGenesis.flatMap {
        case None => Future.successful(None)
        case Some(true) => Future.successful(Some(HistoryStart.Genesis))
        case Some(false) if sources.historyBackfillEnabled => Future.successful(None)
        case Some(false) => sources.dsoPartyHostedSince.map(_.map(HistoryStart.From(_)))
      }
}
