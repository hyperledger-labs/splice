package com.daml.network.integration.plugins

import com.daml.network.config.CNNodeConfig
import com.daml.network.console.CNParticipantClientReference
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.tests.CNNodeTests
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.console.{CommandFailure, ConsoleMacros}
import com.digitalasset.canton.integration.EnvironmentSetupPlugin
import com.digitalasset.canton.logging.{SuppressingLogger, SuppressionRule}
import io.grpc
import io.grpc.StatusRuntimeException
import org.slf4j.event.Level

/** We reset the sequencer domain state threshold to one after the shared environment is destroyed. We cannot just remove sequencers
  * and re-onboard them in subsequent tests since they are in a broken state after off-boarding.
  *
  * The transactions that set the traffic balance make use of the sequencer threshold causing tests that use different number of SVs
  * but use a shared Canton instance to interfere with each other. Resetting the threshold ensures that we start with a clean slate
  * for each test.
  *
  * TODO(#10824) - Unify the topology reset test plugins
  */
class ResetSequencerDomainStateThreshold
    extends EnvironmentSetupPlugin[CNNodeEnvironmentImpl, CNNodeTests.CNNodeTestConsoleEnvironment]
    with BaseTest {

  private val MAX_RETRIES = 15

  override def beforeEnvironmentDestroyed(
      config: CNNodeConfig,
      env: CNNodeTests.CNNodeTestConsoleEnvironment,
  ): Unit = {
    val sv1 = env.svs.local.find(_.name == "sv1").value
    val connectedDomain = sv1.participantClientWithAdminToken.domains
      .list_connected()
      .find(_.domainAlias == sv1.config.domains.global.alias)
      .getOrElse(
        throw new IllegalStateException(
          "Failed to reset environment as SV1 is not connected to global domain"
        )
      )
    val domainId = connectedDomain.domainId

    def resetSequencerDomainStateThreshold(retries: Int): Unit = {
      if (retries > MAX_RETRIES) {
        logger.error(
          s"Exceeded max retries for resetting sequencer domain state threshold $MAX_RETRIES, giving up"
        )
        sys.exit(1)
      }
      sv1.participantClientWithAdminToken.topology.sequencers
        .list(filterStore = domainId.filterString)
        .headOption
        .fold(
          logger.info(s"Not resetting threshold as sequencer domain state doesn't exist yet")
        ) { existingSequencerDomainState =>
          logger.info(s"Resetting sequencer domain state threshold to 1")
          val sequencerThreshold =
            existingSequencerDomainState.item.threshold
          if (sequencerThreshold == PositiveInt.one) {
            logger.info(
              s"Sequencer domain state threshold already set to 1, nothing to do"
            )
          } else {

            def proposeSequencerDomainStateReset(
                client: CNParticipantClientReference
            ): Unit = {
              env.environment.loggerFactory
                .asInstanceOf[SuppressingLogger]
                .assertLogsSeq(SuppressionRule.LevelAndAbove(Level.ERROR))(
                  client.topology.sequencers
                    .propose(
                      domainId,
                      threshold = PositiveInt.one,
                      active = existingSequencerDomainState.item.active,
                      passive = existingSequencerDomainState.item.observers,
                      store = Some(domainId.filterString),
                      serial = Some(
                        existingSequencerDomainState.context.serial + PositiveInt.one
                      ),
                    )
                    .discard,
                  forAll(_)(_.message should include("FAILED_PRECONDITION/SERIAL_MISMATCH")),
                )
            }

            try {
              logger.info("Proposing new sequencer domain state threshold through SV1")
              proposeSequencerDomainStateReset(sv1.participantClientWithAdminToken)
              logger.info(
                "Waiting for proposal to reset sequencer domain state threshold to be effective"
              )
              ConsoleMacros.utils.retry_until_true {
                val results =
                  sv1.participantClientWithAdminToken.topology.sequencers
                    .list(filterStore = domainId.filterString)
                if (results.size == 1) {
                  val result = results.head
                  val sequencerState = result.item
                  if (sequencerState.threshold == PositiveInt.one) {
                    true
                  } else {
                    if (result.context.serial != existingSequencerDomainState.context.serial) {
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
            } catch {
              case _: CommandFailure =>
                logger.info(
                  "Restarting sequencer domain state threshold reset as command failed likely because base serial has changed"
                )
                resetSequencerDomainStateThreshold(retries + 1)
              case s: StatusRuntimeException
                  if s.getStatus.getCode == grpc.Status.Code.INVALID_ARGUMENT =>
                logger.info(
                  "Restarting sequencer domain state threshold reset as base serial has changed"
                )
                resetSequencerDomainStateThreshold(retries + 1)
              case e: Throwable =>
                logger.error("Failed to reset sequencer domain state threshold", e)
                sys.exit(1)
            }
          }
        }
    }
    resetSequencerDomainStateThreshold(0)
    logger.info("Sequencer domain state threshold has been reset")
  }
}
