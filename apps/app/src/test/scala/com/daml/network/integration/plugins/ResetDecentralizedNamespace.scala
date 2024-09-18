package com.daml.network.integration.plugins

import com.daml.network.console.{ParticipantClientReference, SvAppBackendReference}
import com.daml.network.integration.tests.SpliceTests
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.console.ConsoleMacros
import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.logging.{SuppressingLogger, SuppressionRule}
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.topology.transaction.DecentralizedNamespaceDefinition
import io.grpc
import org.slf4j.event.Level

/** The decentralized namespace is reset to contain only sv1 after each env is used
  * When onboarding SVs their participant namespace is added to the decentralized namespace, so for a "clean" test we have to remove them
  * We cannot just drop the decentralized namespace because the global domain is owned by it
  */
final class ResetDecentralizedNamespace extends ResetTopologyStatePlugin {

  override protected lazy val topologyType = "decentralized namespace"

  override protected def resetTopologyState(
      env: SpliceTests.SpliceTestConsoleEnvironment,
      domainId: DomainId,
      sv1: SvAppBackendReference,
  ): Unit = {
    val sv1ParticipantNamespace = sv1.participantClientWithAdminToken.id.uid.namespace
    val decentralizedNamespace =
      DecentralizedNamespaceDefinition.computeNamespace(Set(sv1ParticipantNamespace))
    val store = domainId.filterString

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
              client: ParticipantClientReference
          ) = {
            env.environment.loggerFactory
              .asInstanceOf[SuppressingLogger]
              .assertLogsSeq(SuppressionRule.LevelAndAbove(Level.ERROR))(
                client.topology.decentralized_namespaces
                  .propose(
                    Set(sv1ParticipantNamespace),
                    PositiveInt.one,
                    store,
                    serial = Some(
                      existingDecentralizedNamespace.context.serial + PositiveInt.one
                    ),
                    synchronize = None,
                  )
                  .discard,
                forAll(_)(_.message should include("FAILED_PRECONDITION/SERIAL_MISMATCH")),
              )
          }

          val usableSvs =
            env.svs.local
              // SV apps that end with local or onboarded run against temporary Canton instances started
              // using `withCanton` so they don't need to be reset (and cannot be as Canton isn't running at this point anymore).
              .filterNot(_.name.endsWith("Local"))
              .filterNot(_.name.endsWith("Onboarded"))
              .map(_.participantClientWithAdminToken)
          val usableSvsByNamespace = usableSvs.map(p => p.id.uid.namespace -> p).toMap
          (existingDecentralizedNamespace.item.owners + sv1.participantClientWithAdminToken.id.uid.namespace)
            .foreach { namespace =>
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
                if (result.context.serial != existingDecentralizedNamespace.context.serial) {
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
        }
      }
  }
}
