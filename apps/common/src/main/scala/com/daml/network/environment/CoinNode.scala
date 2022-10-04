package com.daml.network.environment

import com.daml.network.config.SharedCoinAppParameters
import com.digitalasset.canton.config.RequireTypes.InstanceName
import com.digitalasset.canton.environment.CantonNode
import com.digitalasset.canton.lifecycle.{AsyncCloseable, AsyncOrSyncCloseable, FlagCloseableAsync}
import com.digitalasset.canton.logging.NamedLogging
import com.digitalasset.canton.tracing.NoTracing
import scala.concurrent.{ExecutionContext, Future}

/** A running instance of a canton node */
abstract class CoinNode[State <: AutoCloseable](parameters: SharedCoinAppParameters)(implicit
    ec: ExecutionContext
) extends CantonNode
    with FlagCloseableAsync
    with NamedLogging
    with NoTracing {
  val name: InstanceName

  override val timeouts = parameters.processingTimeouts

  def initialize(): Future[State]

  val initializeF: Future[State] = initialize()

  // TODO(#885): Cleanup init failures
  initializeF.failed.foreach { err =>
    logger.error(s"Initialization of $name failed with $err")
    sys.exit(1)
  }

  override def closeAsync(): Seq[AsyncOrSyncCloseable] = {
    logger.info(s"Stopping $name node")
    Seq(AsyncCloseable(s"$name App state", initializeF.map(_.close()), closingTimeout))
  }
}
