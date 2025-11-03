package org.lfdecentralizedtrust.splice.config

import com.digitalasset.canton.config.*
import com.digitalasset.canton.admin.api.client.data.PruningSchedule

/** The schedule is specified in cron format and "max_duration" and "retention" durations. The cron string indicates
  *      the points in time at which pruning should begin in the GMT time zone, and the maximum duration indicates how
  *      long from the start time pruning is allowed to run as long as pruning has not finished pruning up to the
  *      specified retention period.
  */
final case class PruningConfig(
    cron: String,
    maxDuration: PositiveDurationSeconds,
    retention: PositiveDurationSeconds,
) {
  def toSchedule: PruningSchedule = PruningSchedule(
    cron,
    maxDuration,
    retention
  )
}
