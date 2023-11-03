package com.daml.network.integration.tests

import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import better.files.*
import com.daml.network.codegen.java.cc.coin.FeaturedAppRight
import com.daml.network.environment.{
  CNLedgerConnection,
  MediatorAdminConnection,
  RetryFor,
  RetryProvider,
  SequencerAdminConnection,
}
import com.daml.network.sv.LocalDomainNode
import com.daml.network.util.{ProcessTestUtil, TemplateJsonDecoder}
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.RequireTypes.{NonNegativeInt, Port}
import com.digitalasset.canton.config.{
  ClientConfig,
  CommunityCryptoConfig,
  NonNegativeDuration,
  ProcessingTimeout,
}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.domain.config.DomainParametersConfig
import com.digitalasset.canton.metrics.MetricHandle.NoOpMetricsFactory
import com.digitalasset.canton.participant.domain.DomainConnectionConfig
import com.digitalasset.canton.protocol.DynamicDomainParameters
import com.digitalasset.canton.sequencing.{GrpcSequencerConnection, SequencerConnections}
import com.digitalasset.canton.time.{NonNegativeFiniteDuration, WallClock}
import com.digitalasset.canton.topology.processing.{EffectiveTime, SequencedTime}
import com.digitalasset.canton.topology.store.{
  StoredTopologyTransactionX,
  StoredTopologyTransactionsX,
  TimeQueryX,
}
import com.digitalasset.canton.topology.transaction.{
  NamespaceDelegationX,
  OwnerToKeyMappingX,
  UnionspaceDefinitionX,
}
import com.digitalasset.canton.topology.{DomainId, UniqueIdentifier}
import com.digitalasset.canton.version.{DomainProtocolVersion, ProtocolVersion}
import org.scalatest.concurrent.PatienceConfiguration
import org.scalatest.time.Span

import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.CollectionHasAsScala
import scala.util.Using
import java.time.Duration as JavaDuration

class GlobalDomainMigrationIntegrationTest extends SvIntegrationTestBase with ProcessTestUtil {

  private val domainNodeConfig =
    "apps" / "app" / "src" / "test" / "resources" / "global-upgrade-domain-node.conf"

  "can replay unionspace definition on new domain" in { implicit env =>
    import env.environment.scheduler
    import env.{actorSystem, executionContext, toDomainAlias, toIdentifier}
    val retryProvider = new RetryProvider(
      loggerFactory,
      ProcessingTimeout(),
      new FutureSupervisor.Impl(NonNegativeDuration.tryFromDuration(10.seconds)),
      NoOpMetricsFactory,
    )
    val wallClock = new WallClock(
      ProcessingTimeout(),
      loggerFactory,
    )
    val staticParams =
      DomainParametersConfig(
        protocolVersion = DomainProtocolVersion(ProtocolVersion.dev),
        devVersionSupport = true,
      ).toStaticDomainParameters(CommunityCryptoConfig())
        .valueOrFail("static")
    val initial = DynamicDomainParameters.initialXValues(wallClock, ProtocolVersion.dev)
    val parameters = initial.copy(topologyChangeDelay = NonNegativeFiniteDuration.Zero)(
      initial.representativeProtocolVersion
    )
    Using.resource(
      startCanton(
        Seq(
          "-c",
          domainNodeConfig.toString(),
        ),
        "global-domain-migration-test",
      )
    ) { _ =>
      initSvc()
      implicit val httpClient: HttpRequest => Future[HttpResponse] = sv1Backend.appState.httpClient
      implicit val decoder: TemplateJsonDecoder = sv1Backend.appState.decoder
      Using.resource(
        new LocalDomainNode(
          new SequencerAdminConnection(
            ClientConfig(port = Port.tryCreate(5909)),
            loggerFactory,
            retryProvider,
            wallClock,
          ),
          new MediatorAdminConnection(
            ClientConfig(port = Port.tryCreate(5907)),
            loggerFactory,
            retryProvider,
            wallClock,
          ),
          staticParams,
          ClientConfig(port = Port.tryCreate(5908)),
          "",
          JavaDuration.ZERO,
          loggerFactory,
          retryProvider,
        )
      ) { upgradeDomainNode =>
        val upgradeDomainName = "global-domain-upgrade"
        val upgradeDomainAlias: DomainAlias = upgradeDomainName
        val upgradeDomainId = DomainId(
          UniqueIdentifier(
            upgradeDomainName,
            sv1Backend.appState.svcAutomation.store.key.svcParty.uid.namespace,
          )
        )

        val globalDomain = sv1Backend.participantClientWithAdminToken.domains.id_of(
          sv1Backend.config.domains.global.alias
        )
        val svcPartyUnionspace = sv2Backend.appState.svcStore.key.svcParty.uid.namespace
        val sv2ParticipantAdminConnection = sv2Backend.appState.participantAdminConnection
        val globalDomainUnionspaceDefinition = sv2ParticipantAdminConnection
          .getUnionspaceDefinition(globalDomain, svcPartyUnionspace)
          .futureValue
        val sv2ParticipantClientWithAdminToken = sv2Backend.participantClientWithAdminToken
        val allTopologyTransactions =
          sv2ParticipantClientWithAdminToken.topology.transactions.list(
            filterStore = globalDomain.filterString,
            timeQuery = TimeQueryX.Range(None, None),
          )
        val allUnionspaceDefinitionTransactions = allTopologyTransactions.result
          .flatMap { transaction =>
            transaction.selectMapping[UnionspaceDefinitionX]
          }
          .sortBy(_.transaction.transaction.serial)
        val unionspaceOwnersIdentityTransactions = allTopologyTransactions.result.flatMap {
          transaction =>
            transaction
              .selectMapping[OwnerToKeyMappingX]
              .filter(transaction =>
                globalDomainUnionspaceDefinition.mapping.owners
                  .contains(transaction.transaction.transaction.mapping.namespace)
              )
              .orElse(
                transaction
                  .selectMapping[NamespaceDelegationX]
                  .filter(transaction =>
                    globalDomainUnionspaceDefinition.mapping.owners
                      .contains(transaction.transaction.transaction.mapping.namespace)
                  )
              )
        }
        val sequencerIdentityTransactions = eventuallySucceeds() {
          upgradeDomainNode.sequencerAdminConnection
            .getId()
            .flatMap(id =>
              upgradeDomainNode.sequencerAdminConnection.getIdentityTransactions(id, None)
            )
            .futureValue
        }
        val mediatorIdentityTransactions = upgradeDomainNode.mediatorAdminConnection
          .getId()
          .flatMap(id =>
            upgradeDomainNode.mediatorAdminConnection.getIdentityTransactions(id, None)
          )
          .futureValue
        val initialDomainParams = sv2ParticipantAdminConnection
          .proposeInitialDomainParameters(
            upgradeDomainId,
            parameters,
            sv2ParticipantClientWithAdminToken.id.uid.namespace.fingerprint,
          )
          .futureValue
        val sequencerDomainState =
          sv2ParticipantAdminConnection
            .proposeInitialSequencerDomainState(
              upgradeDomainId,
              Seq(upgradeDomainNode.sequencerAdminConnection.getSequencerId.futureValue),
              Seq.empty,
              sv2ParticipantClientWithAdminToken.id.uid.namespace.fingerprint,
            )
            .futureValue
        val mediatorDomainState =
          sv2ParticipantAdminConnection
            .proposeInitialMediatorDomainState(
              upgradeDomainId,
              NonNegativeInt.zero,
              Seq(upgradeDomainNode.mediatorAdminConnection.getMediatorId.futureValue),
              Seq.empty,
              sv2ParticipantClientWithAdminToken.id.uid.namespace.fingerprint,
            )
            .futureValue

        val sv2Party = sv2Backend.appState.svStore.key.svParty
        val partyHosting = sv2ParticipantAdminConnection
          .proposeInitialPartyToParticipant(
            svcParty,
            sv2Backend.participantClientWithAdminToken.id,
            sv2Party.uid.namespace.fingerprint,
          )
          .futureValue
        withClue("sequencer is initialized") {
          upgradeDomainNode.sequencerAdminConnection
            .initialize(
              StoredTopologyTransactionsX(
                unionspaceOwnersIdentityTransactions ++ allUnionspaceDefinitionTransactions ++ (sequencerIdentityTransactions ++ mediatorIdentityTransactions
                  ++ Seq(
                    initialDomainParams,
                    sequencerDomainState,
                    mediatorDomainState,
                    partyHosting,
                  ))
                  .map(signed =>
                    StoredTopologyTransactionX(
                      SequencedTime(CantonTimestamp.MinValue.immediateSuccessor),
                      EffectiveTime(CantonTimestamp.MinValue.immediateSuccessor),
                      None,
                      signed.copy(isProposal = false),
                    )
                  )
              ),
              staticParams,
              None,
            )
            .futureValue
            .discard
          eventuallySucceeds() {
            upgradeDomainNode.sequencerAdminConnection.getStatus.futureValue.successOption
              .valueOrFail("not initialized")
          }
        }
        withClue("mediator is initialized") {
          upgradeDomainNode.mediatorAdminConnection
            .initialize(
              upgradeDomainId,
              staticParams,
              upgradeDomainNode.sequencerConnection,
            )
            .futureValue
          eventuallySucceeds() {
            upgradeDomainNode.mediatorAdminConnection.getStatus.futureValue.successOption
              .valueOrFail("not initialized")
          }
        }

        sv2ParticipantAdminConnection
          .ensureDomainRegistered(
            DomainConnectionConfig(
              upgradeDomainAlias,
              SequencerConnections.single(
                GrpcSequencerConnection.tryCreate("http://localhost:5908")
              ),
              manualConnect = true,
              domainId = Some(upgradeDomainId),
            ),
            RetryFor.Automation,
          )
          .futureValue(PatienceConfiguration.Timeout(Span.convertDurationToSpan(2.minutes)))

        sv2ParticipantAdminConnection
          .connectDomain(upgradeDomainAlias)
          .futureValue shouldBe ()

        withClue("unionspace is replicated on the new global domain") {
          eventuallySucceeds(timeUntilSuccess = 1.minute) {
            upgradeDomainNode.sequencerAdminConnection.getStatus.futureValue.successOption.value.domainId shouldBe upgradeDomainId
            upgradeDomainNode.sequencerAdminConnection.getStatus.futureValue.successOption.value.connectedParticipants should not be empty
            sv2ParticipantAdminConnection
              .getUnionspaceDefinition(
                upgradeDomainId,
                svcPartyUnionspace,
              )
              .futureValue
              .mapping shouldBe globalDomainUnionspaceDefinition.mapping
            upgradeDomainNode.sequencerAdminConnection
              .getUnionspaceDefinition(
                upgradeDomainId,
                svcPartyUnionspace,
              )
              .futureValue
              .mapping shouldBe globalDomainUnionspaceDefinition.mapping
          }
        }
        withClue("unionspace can be modified on the new domain") {
          eventuallySucceeds() {
            sv2ParticipantAdminConnection
              .ensureUnionspaceDefinitionOwnerChangeProposalAccepted(
                "keep just sv2",
                upgradeDomainId,
                svcPartyUnionspace,
                _ => NonEmpty(Set, sv2Party.uid.namespace),
                sv2Party.uid.namespace.fingerprint,
                RetryFor.WaitingOnInitDependency,
              )
              .futureValue
              .discard

            sv2ParticipantAdminConnection
              .getUnionspaceDefinition(
                upgradeDomainId,
                svcPartyUnionspace,
              )
              .futureValue
              .mapping
              .owners shouldBe Set(sv2Party.uid.namespace)
          }
        }
        withClue("submit dummy transaction to the new domain to validate it") {
          sv2ParticipantClientWithAdminToken.ledger_api_extensions.commands
            .submitJava(
              actAs = Seq(svcParty),
              readAs = Seq(sv2Party),
              commands =
                new FeaturedAppRight(svcParty.toProtoPrimitive, sv2Party.toProtoPrimitive).createAnd
                  .exerciseArchive()
                  .commands()
                  .asScala
                  .toSeq,
              workflowId = CNLedgerConnection.domainIdToWorkflowId(upgradeDomainId),
              optTimeout = None,
            )
        }
        sv2ParticipantClientWithAdminToken.domains.disconnect(upgradeDomainAlias)
      }
    }
  }
}
