// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.db

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.scan.store.db.ActivityIngestionMetaCheck.*
import org.lfdecentralizedtrust.splice.scan.store.db.DbAppActivityRecordStore.AppActivityRecordMetaT

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.{ExecutionContext, Future}

class ActivityIngestionMetaCheck(
    activityStore: DbAppActivityRecordStore,
    runningCodeVersion: Int,
    runningUserVersion: Int,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends NamedLogging {

  private val checked = new AtomicBoolean(false)

  /** Whether the meta check has completed successfully at least once. */
  def isChecked: Boolean = checked.get()

  /** Returns verdict timestamps that are missing traffic summaries.
    * Only reports missing summaries after the meta check has completed
    * (ingestion has started); before that, missing summaries are expected
    * during SV onboarding.
    */
  def findMissingSummaryTimes(
      verdictTimes: Seq[CantonTimestamp],
      summaryTimes: Set[CantonTimestamp],
  ): Seq[CantonTimestamp] = {
    if (checked.get()) verdictTimes.filterNot(summaryTimes.contains)
    else Seq.empty
  }

  /** Ensures the activity record meta row exists and versions are compatible.
    * Returns the check result; the caller is responsible for acting on
    * [[DowngradeDetected]] (e.g. shutting down).
    * After the first successful call the result is cached and subsequent
    * calls return [[Resume]] without hitting the database.
    */
  def ensure(
      firstRecordTimeMicros: Long,
      earliestIngestedRound: Long,
  )(implicit tc: TraceContext): Future[MetaCheckResult] = {
    if (checked.get()) Future.successful(Resume)
    else {
      activityStore.getActivityRecordMeta().flatMap { existing =>
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
  ) extends MetaCheckResult

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
