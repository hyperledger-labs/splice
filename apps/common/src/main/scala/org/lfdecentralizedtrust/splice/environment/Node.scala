// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import org.apache.pekko.actor.ActorSystem
import com.daml.grpc.adapter.ExecutionSequencerFactory
import org.lfdecentralizedtrust.splice.SpliceMetrics
import org.lfdecentralizedtrust.splice.config.{ParticipantClientConfig, SharedSpliceAppParameters}
import org.lfdecentralizedtrust.splice.util.HasHealth
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.{TraceContext, TracerProvider}
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContextExecutor, Future}

/** Subclass of NodeBase that provides default initialization for most apps */
abstract class Node[State <: AutoCloseable & HasHealth, PreInitializeState](
    serviceUser: String,
    participantClient: ParticipantClientConfig,
    parameters: SharedSpliceAppParameters,
    loggerFactory: NamedLoggerFactory,
    tracerProvider: TracerProvider,
    futureSupervisor: FutureSupervisor,
    nodeMetrics: SpliceMetrics,
)(implicit
    ac: ActorSystem,
    ec: ExecutionContextExecutor,
    esf: ExecutionSequencerFactory,
    tracer: Tracer,
) extends NodeBase[State](
      serviceUser,
      participantClient,
      parameters,
      loggerFactory,
      tracerProvider,
      futureSupervisor,
      nodeMetrics,
    ) {
  val name: InstanceName

  /** Packages that must be available before init will run.
    */
  protected def requiredPackageIds: Set[String] = Set.empty

  // If true, wait for the ledger API user to have a primary party before calling preInitializeAfterLedgerConnection
  // This can be useful to control dependency order e.g. make sure the validator app waits for the SV app first.
  protected def waitForPartyBeforePreinitialize: Boolean = false

  // Code that is run after a ledger connection becomes available but before
  // waiting for the primary party. This can be used for things like
  // domain connections and allocation of the primary party.
  protected def preInitializeAfterLedgerConnection(
      connection: BaseLedgerConnection,
      ledgerClient: SpliceLedgerClient,
  )(implicit tc: TraceContext): Future[PreInitializeState]

  def initialize(
      ledgerClient: SpliceLedgerClient,
      party: PartyId,
      preInitializeState: PreInitializeState,
  )(implicit tc: TraceContext): Future[State]

  private def waitForParty(
      initConnection: BaseLedgerConnection
  )(implicit tc: TraceContext): Future[PartyId] =
    appInitStep("Get primary party") {
      retryProvider.getValueWithRetries[PartyId](
        RetryFor.WaitingOnInitDependency,
        "primary_party",
        s"primary party of service user $serviceUser",
        initConnection.getPrimaryParty(serviceUser),
        logger,
        // Note: In general, app service users are allocated by the validator app.
        // While the app has a valid access token for its service user but that user has not yet been allocated by the validator app,
        // all ledger API calls with fail with PERMISSION_DENIED.
        // Since this is the first ledger API call in the app, we additionally retry on auth errors here.
        additionalCodes = Seq(Status.Code.PERMISSION_DENIED),
      )
    }

  override protected def initializeNode(
      ledgerClient: SpliceLedgerClient
  )(implicit tc: TraceContext): Future[State] = for {
    _ <- preInitializeBeforeLedgerConnection()
    initConnection = appInitStepSync("Acquire ledger connection") {
      ledgerClient.readOnlyConnection(
        this.getClass.getSimpleName,
        loggerFactory,
      )
    }
    (preInitializeState, serviceParty) <-
      if (waitForPartyBeforePreinitialize) {
        for {
          serviceParty <- waitForParty(initConnection)
          preInitializeState <- preInitializeAfterLedgerConnection(initConnection, ledgerClient)
        } yield (preInitializeState, serviceParty)
      } else {
        for {
          preInitializeState <- preInitializeAfterLedgerConnection(initConnection, ledgerClient)
          serviceParty <- waitForParty(initConnection)
        } yield (preInitializeState, serviceParty)
      }
    _ <- appInitStep("Wait for packages to be uploaded") {
      logger.info(s"Required packages: ${requiredPackageIds}")
      initConnection.waitForPackages(requiredPackageIds)
    }
    state <- initialize(ledgerClient, serviceParty, preInitializeState)
  } yield state
}
