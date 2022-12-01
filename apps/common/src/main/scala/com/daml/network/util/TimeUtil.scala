package com.daml.network.util

import com.daml.network.environment.CoinLedgerConnection
import com.digitalasset.canton.config.ClockConfig
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.Future

object TimeUtil {
  def getTime(connection: CoinLedgerConnection, clockConfig: ClockConfig)(implicit
      tc: TraceContext
  ) = {
    clockConfig match {
      case ClockConfig.SimClock =>
        connection.getTime()
      case ClockConfig.WallClock(_) =>
        Future.successful(CantonTimestamp.now())
      case _: ClockConfig.RemoteClock =>
        sys.error(
          "Remote clock mode is unsupported for CN apps, use either SimClock or WallClock"
        )
    }
  }

}
