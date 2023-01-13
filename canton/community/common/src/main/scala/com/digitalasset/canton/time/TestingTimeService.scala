// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.time

import com.digitalasset.canton.concurrent.Threading
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.tracing.TraceContext

class TestingTimeService(clock: Clock, private val getSimClocks: () => Seq[SimClock]) {

  /** Test-only hook to advance time only on locally hosted domains and participants. This provides the illusion of a
    * single-system with a single clock on all participants and domains to those test and demo environments that
    * expect "legacy time service" support
    * @param currentTime current time that needs to match with actual, current time in order to allow changing the time
    * @param newTime time to set sim-clock to
    */
  def advanceTime(currentTime: CantonTimestamp, newTime: CantonTimestamp)(implicit
      traceContext: TraceContext
  ): Either[String, Unit] = {
    // We sleep for 1s before changing the time here. This avoids inflight commands
    // getting dropped by the sequencer due to exceeding max-sequencing-time.
    // While we do recover from such an issue, we recover from it once the participant
    // times out with LOCAL_VERDICT_TIMEOUT. That timeout is measured in wall clock
    // so we will wait the full participantResponseTimeout (30s by default) which
    // then results in `eventually`’s in tests never completing.
    // Waiting 1s should ensure that existing background automation (e.g. coin merging)
    // can complete in-flight commands before we change the time. The assumption here
    // is that our period automation also takes sim time into account so if
    // the time does not change, it won’t continue sending commands.
    Threading.sleep(1000)
    (for {
      _ <- Either.cond(
        !newTime.isBefore(currentTime),
        (),
        s"New time ${newTime.toString} is before current time ${currentTime.toString}",
      )
      ledgerTime = clock.now
      _ <- Either.cond(
        ledgerTime.compareTo(currentTime) == 0,
        (),
        s"Specified current time ${currentTime.toString} does not match ledger time ${ledgerTime.toString}",
      )
      _ = getSimClocks().foreach(_.advanceTo(newTime))
    } yield ()).left.map(e => s"Cannot advance clock: $e")
  }

}
