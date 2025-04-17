package org.lfdecentralizedtrust.splice.integration.plugins

import org.lfdecentralizedtrust.splice.console.{ParticipantClientReference, SvAppBackendReference}
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.console.ConsoleMacros
import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.logging.{SuppressingLogger, SuppressionRule}
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId
import io.grpc
import org.slf4j.event.Level

/** We reset the sequencer domain state threshold to one after the shared environment is destroyed. We cannot just remove sequencers
  * and re-onboard them in subsequent tests since they are in a broken state after off-boarding.
  *
  * The transactions that set the traffic balance make use of the sequencer threshold causing tests that use different number of SVs
  * but use a shared Canton instance to interfere with each other. Resetting the threshold ensures that we start with a clean slate
  * for each test.
  */
final class ResetSequencerSynchronizerStateThreshold extends ResetTopologyStatePlugin {
  override protected lazy val topologyType = "sequencer domain state threshold"

  override protected def resetTopologyState(
      env: SpliceTests.SpliceTestConsoleEnvironment,
      synchronizerId: SynchronizerId,
      sv1: SvAppBackendReference,
  ): Unit = {
    sv1.participantClientWithAdminToken.topology.sequencers
      .list(
        store = Some(TopologyStoreId.Synchronizer(synchronizerId))
      )
      .headOption
      .fold(
        logger.info(s"Not resetting threshold as sequencer domain state doesn't exist yet")
      ) { existingSequencerSynchronizerState =>
        logger.info(s"Resetting sequencer domain state threshold to 1")
        val sequencerThreshold =
          existingSequencerSynchronizerState.item.threshold
        if (sequencerThreshold == PositiveInt.one) {
          logger.info(
            s"Sequencer domain state threshold already set to 1, nothing to do"
          )
        } else {

          def proposeSequencerSynchronizerStateReset(
              client: ParticipantClientReference
          ): Unit = {
            env.environment.loggerFactory
              .asInstanceOf[SuppressingLogger]
              .assertLogsSeq(SuppressionRule.LevelAndAbove(Level.ERROR))(
                client.topology.sequencers
                  .propose(
                    synchronizerId,
                    threshold = PositiveInt.one,
                    active = existingSequencerSynchronizerState.item.active,
                    passive = existingSequencerSynchronizerState.item.observers,
                    store = Some(TopologyStoreId.Synchronizer(synchronizerId)),
                    serial = Some(
                      existingSequencerSynchronizerState.context.serial + PositiveInt.one
                    ),
                  )
                  .discard,
                forAll(_)(_.message should include("FAILED_PRECONDITION/SERIAL_MISMATCH")),
              )
          }

          logger.info("Proposing new sequencer domain state threshold through SV1")
          proposeSequencerSynchronizerStateReset(sv1.participantClientWithAdminToken)
          logger.info(
            "Waiting for proposal to reset sequencer domain state threshold to be effective"
          )
          ConsoleMacros.utils.retry_until_true {
            val results =
              sv1.participantClientWithAdminToken.topology.sequencers
                .list(
                  store = Some(TopologyStoreId.Synchronizer(synchronizerId))
                )
            if (results.size == 1) {
              val result = results.head
              val sequencerState = result.item
              if (sequencerState.threshold == PositiveInt.one) {
                true
              } else {
                if (result.context.serial != existingSequencerSynchronizerState.context.serial) {
                  throw grpc.Status.INVALID_ARGUMENT
                    .withDescription(
                      "Base serial has changed, must be retried"
                    )
                    .asRuntimeException()
                }
                logger.info(
                  s"Sequencer domain state threshold is still ${sequencerState.threshold}, waiting for it to be reset"
                )
                false
              }
            } else false

          }(env)
        }
      }
  }
}
