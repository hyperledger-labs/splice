package org.lfdecentralizedtrust.splice.integration.plugins

import org.lfdecentralizedtrust.splice.config.SpliceConfig
import org.lfdecentralizedtrust.splice.console.SvAppBackendReference
import org.lfdecentralizedtrust.splice.environment.SpliceEnvironment
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.console.CommandFailure
import com.digitalasset.canton.integration.EnvironmentSetupPlugin
import com.digitalasset.canton.topology.SynchronizerId
import io.grpc
import io.grpc.StatusRuntimeException
import scala.util.control.NonFatal

abstract class ResetTopologyStatePlugin
    extends EnvironmentSetupPlugin[
      SpliceConfig,
      SpliceEnvironment,
    ]
    with BaseTest {

  private val MAX_RETRIES = 15

  protected def resetTopologyState(
      env: SpliceTests.SpliceTestConsoleEnvironment,
      synchronizerId: SynchronizerId,
      sv1: SvAppBackendReference,
  ): Unit

  protected def topologyType: String

  override def beforeEnvironmentDestroyed(
      config: SpliceConfig,
      env: SpliceTests.SpliceTestConsoleEnvironment,
  ): Unit = {

    // Stop all nodes to stop them from submitting topology TXs.
    env.stopAll()

    try {
      attemptToResetTopologyState(env)
    } catch {
      case NonFatal(e) =>
        val msg = s"Resetting $topologyType failed with: $e, giving up"
        logger.error(msg)
        System.err.println(msg)
        sys.exit(1)
    }
  }

  private def attemptToResetTopologyState(env: SpliceTests.SpliceTestConsoleEnvironment): Unit = {
    val sv1 = env.svs.local.find(_.name == "sv1").value
    val synchronizerId = eventuallySucceeds() {
      val connectedDomain = sv1.participantClientWithAdminToken.synchronizers
        .list_connected()
        .find(_.synchronizerAlias == sv1.config.domains.global.alias)
        .getOrElse(
          throw new IllegalStateException(
            "Failed to reset environment as SV1 is not connected to global domain"
          )
        )
      connectedDomain.synchronizerId
    }

    def resetTopologyStateRetries(retries: Int): Unit = {
      if (retries > MAX_RETRIES) {
        logger.error(
          s"Exceeded max retries for resetting $topologyType: $MAX_RETRIES, giving up"
        )
        sys.exit(1)
      }
      try {
        resetTopologyState(env, synchronizerId, sv1)
      } catch {
        case _: CommandFailure =>
          logger.info(
            s"Restarting $topologyType reset as command failed likely because base serial has changed"
          )
          resetTopologyStateRetries(retries + 1)
        case s: StatusRuntimeException
            if s.getStatus.getCode == grpc.Status.Code.INVALID_ARGUMENT =>
          logger.info(
            s"Restarting $topologyType reset as base serial has changed"
          )
          resetTopologyStateRetries(retries + 1)
        case e: Throwable =>
          logger.error(s"Failed to reset $topologyType", e)
          sys.exit(1)
      }
    }
    resetTopologyStateRetries(0)
    logger.info(s"$topologyType has been reset")
  }
}
