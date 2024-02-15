package com.daml.network.integration.plugins

import com.daml.error.utils.ErrorDetails
import com.daml.network.config.CNNodeConfig
import com.daml.network.console.CNParticipantClientReference
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.tests.CNNodeTests
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.console.ConsoleMacros
import com.digitalasset.canton.integration.EnvironmentSetupPlugin
import com.digitalasset.canton.topology.transaction.DecentralizedNamespaceDefinitionX
import com.digitalasset.canton.topology.TopologyManagerError.SerialMismatch
import io.grpc
import io.grpc.StatusRuntimeException

/** The decentralized namespace is reset to contain only sv1 after each env is used
  * When onboarding SVs their participant namespace is added to the decentralized namespace, so for a "clean" test we have to remove them
  * We cannot just drop the decentralized namespace because the global domain is owned by it
  */
class ResetDecentralizedNamespace
    extends EnvironmentSetupPlugin[CNNodeEnvironmentImpl, CNNodeTests.CNNodeTestConsoleEnvironment]
    with BaseTest {

  override def beforeEnvironmentDestroyed(
      config: CNNodeConfig,
      env: CNNodeTests.CNNodeTestConsoleEnvironment,
  ): Unit = {
    // Stop all, otherwise SvNamespaceMembershipTrigger will concurrently
    // try to onboard again.
    env.stopAll()

    env.svs.local.find(_.name == "sv1") match {
      case None =>
        // This can happen for tests that only rely on a temporary Canton instance started through `withCanton`.
        logger.info("SV1 is not in environment, not attempting to reset decentralized namespace")
      case Some(sv1) =>
        logger.info("SV1 is in environment, checking if decentralized namespace needs to be reset")
        val sv1ParticipantNamespace = sv1.participantClientWithAdminToken.id.uid.namespace
        val decentralizedNamespace =
          DecentralizedNamespaceDefinitionX.computeNamespace(Set(sv1ParticipantNamespace))
        val connectedDomain = sv1.participantClientWithAdminToken.domains
          .list_connected()
          .find(_.domainAlias == sv1.config.domains.global.alias)
          .getOrElse(
            throw new IllegalStateException(
              "Failed to reset environment as SV1 is not connected to global domain"
            )
          )
        val store = connectedDomain.domainId.filterString

        def resetDecentralizedNamespace(): Unit = {
          sv1.participantClientWithAdminToken.topology.decentralized_namespaces
            .list(
              store,
              filterNamespace = decentralizedNamespace.toProtoPrimitive,
            )
            .headOption
            .fold(
              logger.info("Not resetting decentralized namespace as it doesn't exist yet")
            ) { existingDecentralizedNamespace =>
              logger.info("Resetting decentralized namespace to contain only sv1")
              val ownersThatMustBeRemoved =
                existingDecentralizedNamespace.item.owners.diff(Set(sv1ParticipantNamespace))
              if (ownersThatMustBeRemoved.isEmpty) {
                logger.info("Decentralized namespace contains only SV1, nothing to do")
              } else {
                logger.info(
                  s"The following namespaces need to be removed from the decentralized namespace: $ownersThatMustBeRemoved"
                )

                def proposeDecentralizedNamespaceReset(
                    client: CNParticipantClientReference
                ): Unit = {
                  client.topology.decentralized_namespaces
                    .propose(
                      Set(sv1ParticipantNamespace),
                      PositiveInt.one,
                      store,
                      serial = Some(existingDecentralizedNamespace.context.serial + PositiveInt.one),
                    )
                    .discard
                }

                try {
                  val usableSvs =
                    env.svs.local
                      // SV apps that end with local or onboarded run against temporary Canton instances started
                      // using `withCanton` so they don't need to be reset (and cannot be as Canton isn't running at this point anymore).
                      .filterNot(_.name.endsWith("Local"))
                      .filterNot(_.name.endsWith("Onboarded"))
                      .map(_.participantClientWithAdminToken)
                  val usableSvsByNamespace = usableSvs.map(p => p.id.uid.namespace -> p).toMap
                  existingDecentralizedNamespace.item.owners.foreach { namespace =>
                    val sv = usableSvsByNamespace.getOrElse(
                      namespace,
                      throw new IllegalStateException(
                        s"Failed to remove $namespace as there is no SV with that namespace, svs found: ${usableSvsByNamespace.keySet}"
                      ),
                    )
                    proposeDecentralizedNamespaceReset(sv)
                  }
                  logger.info(
                    "All required proposals to reset SV namespace submitted, waiting for it to be effective"
                  )
                  ConsoleMacros.utils.retry_until_true {
                    val results =
                      sv1.participantClientWithAdminToken.topology.decentralized_namespaces
                        .list(store, filterNamespace = decentralizedNamespace.toProtoPrimitive)
                        .map(identity)
                    if (results.size == 1) {
                      val result = results.head
                      val namespace = result.item
                      if (namespace.owners.forgetNE == Set(sv1ParticipantNamespace)) {
                        true
                      } else {
                        if (
                          result.context.serial != existingDecentralizedNamespace.context.serial
                        ) {
                          throw grpc.Status.INVALID_ARGUMENT
                            .withDescription(
                              "Base serial has changed, must be retried"
                            )
                            .asRuntimeException()
                        }
                        logger.info(
                          s"Decentralized namespace still contains ${namespace.owners}, waiting for it to be reset"
                        )
                        false
                      }
                    } else false

                  }(env)
                } catch {
                  case s: StatusRuntimeException if ErrorDetails.matches(s, SerialMismatch) =>
                    logger.info(
                      "Restarting decentralized namespace reset as base serial has changed"
                    )
                    resetDecentralizedNamespace()
                  case s: StatusRuntimeException
                      if s.getStatus.getCode == grpc.Status.Code.INVALID_ARGUMENT =>
                    logger.info(
                      "Restarting decentralized namespace reset as base serial has changed"
                    )
                    resetDecentralizedNamespace()
                  case e: IllegalStateException =>
                    logger.error("Failed to reset decentralized namespace", e)
                    sys.exit(1)
                }
              }
            }
        }
        resetDecentralizedNamespace()
        logger.info("Decentralized namespace has been reset")
    }
  }
}
