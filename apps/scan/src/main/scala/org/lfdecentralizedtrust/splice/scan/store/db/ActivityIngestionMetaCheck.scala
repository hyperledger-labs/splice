// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.db

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.scan.store.db.ActivityIngestionMetaCheck.*
import org.lfdecentralizedtrust.splice.scan.store.db.DbAppActivityRecordStore.{
  AppActivityRecordMetaT,
  AppActivityRecordT,
}

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.{ExecutionContext, Future}

/** Tracks and validates activity record ingestion metadata.
  *
  * This class manages the lifecycle of the `app_activity_record_meta`
  * table row that records when ingestion started and which versions
  * are running.
  *
  * On the first batch that produces activity records, [[ensureIfReady]]
  * creates the meta row storing the current code/user version and the
  * earliest ingested round. On subsequent batches it is a no-op. If a
  * version downgrade is detected, it returns [[DowngradeDetected]] so
  * the caller can shut down.
  */
class ActivityIngestionMetaCheck(
    activityStore: DbAppActivityRecordStore,
    runningCodeVersion: Int,
    runningUserVersion: Int,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends NamedLogging {

  private val checked = new AtomicBoolean(false)

  /** Ensures the meta row exists if this batch has activity records.
    * Returns any detected downgrade and whether ingestion has started.
    */
  def ensureIfReady(
      firstRecordTimeMicros: Long,
      appActivityRecords: Seq[(CantonTimestamp, AppActivityRecordT)],
  )(implicit tc: TraceContext): Future[(Option[DowngradeDetected], Boolean)] = {
    if (checked.get()) Future.successful((None, true))
    else if (appActivityRecords.isEmpty) Future.successful((None, false))
    else {
      val earliestRound = appActivityRecords
        .map(_._2.roundNumber)
        .minOption
        .getOrElse(0L)
      ensure(firstRecordTimeMicros, earliestRound).map {
        case d: DowngradeDetected => (Some(d), false)
        case _ => (None, checked.get())
      }
    }
  }

  private[scan] def ensure(
      firstRecordTimeMicros: Long,
      earliestIngestedRound: Long,
  )(implicit tc: TraceContext): Future[MetaCheckResult] = {
    if (checked.get()) Future.successful(Resume)
    else {
      activityStore.lookupActivityRecordMeta().flatMap { existing =>
        checkMetaVersions(existing, runningCodeVersion, runningUserVersion) match {
          case InsertMeta =>
            val label = if (existing.isDefined) "version upgrade" else "initializing"
            logger.info(
              s"Activity record meta $label: codeVersion=$runningCodeVersion, " +
                s"userVersion=$runningUserVersion, startedIngestingAt=$firstRecordTimeMicros, " +
                s"earliestIngestedRound=$earliestIngestedRound"
            )
            activityStore
              .insertActivityRecordMeta(
                runningCodeVersion,
                runningUserVersion,
                firstRecordTimeMicros,
                earliestIngestedRound,
              )
              .map { _ =>
                checked.set(true)
                InsertMeta
              }
          case Resume =>
            checked.set(true)
            Future.successful(Resume)
          case d: DowngradeDetected =>
            Future.successful(d)
        }
      }
    }
  }
}

object ActivityIngestionMetaCheck {

  sealed trait MetaCheckResult
  case object InsertMeta extends MetaCheckResult
  case object Resume extends MetaCheckResult
  final case class DowngradeDetected(
      runningCode: Int,
      runningUser: Int,
      storedCode: Int,
      storedUser: Int,
  ) extends MetaCheckResult {
    def message: String =
      s"Activity ingestion version downgrade detected: " +
        s"running=($runningCode,$runningUser), stored=($storedCode,$storedUser). " +
        s"Shutting down to prevent data corruption."
  }

  def checkMetaVersions(
      existing: Option[AppActivityRecordMetaT],
      runningCode: Int,
      runningUser: Int,
  ): MetaCheckResult = existing match {
    case None => InsertMeta
    case Some(meta) =>
      if (runningCode < meta.codeVersion || runningUser < meta.userVersion)
        DowngradeDetected(runningCode, runningUser, meta.codeVersion, meta.userVersion)
      else if (runningCode > meta.codeVersion || runningUser > meta.userVersion)
        InsertMeta
      else
        Resume
  }
}
