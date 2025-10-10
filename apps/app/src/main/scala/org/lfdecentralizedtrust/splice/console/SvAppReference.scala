// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.console

import org.lfdecentralizedtrust.splice.auth.AuthUtil
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.OpenMiningRound
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.amuletprice as cp
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{
  ActionRequiringConfirmation,
  DsoRules_CloseVoteRequestResult,
  VoteRequest,
}
import org.lfdecentralizedtrust.splice.codegen.java.da.time.types.RelTime
import org.lfdecentralizedtrust.splice.config.NetworkAppClientConfig
import org.lfdecentralizedtrust.splice.environment.{
  BuildInfo,
  SpliceConsoleEnvironment,
  SpliceStatus,
}
import org.lfdecentralizedtrust.splice.http.v0.definitions
import org.lfdecentralizedtrust.splice.sv.{SvApp, SvAppBootstrap, SvAppClientConfig}
import org.lfdecentralizedtrust.splice.sv.admin.api.client.commands.{
  HttpSvAdminAppClient,
  HttpSvAppClient,
}
import org.lfdecentralizedtrust.splice.sv.automation.{
  DsoDelegateBasedAutomationService,
  SvSvAutomationService,
  SvDsoAutomationService,
}
import org.lfdecentralizedtrust.splice.sv.config.SvAppBackendConfig
import org.lfdecentralizedtrust.splice.sv.migration.{DomainDataSnapshot, SynchronizerNodeIdentities}
import org.lfdecentralizedtrust.splice.sv.util.ValidatorOnboarding
import org.lfdecentralizedtrust.splice.util.Contract
import com.digitalasset.canton.admin.api.client.data.NodeStatus
import com.digitalasset.canton.console.{BaseInspection, Help}
import com.digitalasset.canton.topology.{ParticipantId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.actor.ActorSystem

import scala.jdk.OptionConverters.*
import java.time.Instant
import scala.concurrent.duration.FiniteDuration

abstract class SvAppReference(
    override val spliceConsoleEnvironment: SpliceConsoleEnvironment,
    override val name: String,
) extends HttpAppReference {

  override def basePath = "/api/sv"
  override protected val instanceType = "SV Client"

  def onboardValidator(validator: PartyId, secret: String, contactPoint: String): Unit =
    consoleEnvironment.run {
      httpCommand(
        HttpSvAppClient.OnboardValidator(validator, secret, BuildInfo.compiledVersion, contactPoint)
      )
    }

  def startSvOnboarding(token: String): Unit =
    consoleEnvironment.run {
      httpCommand(HttpSvAppClient.StartSvOnboarding(token))
    }

  def getSvOnboardingStatus(candidate: PartyId): HttpSvAppClient.SvOnboardingStatus =
    consoleEnvironment.run {
      httpCommand(HttpSvAppClient.getSvOnboardingStatus(candidate.toProtoPrimitive))
    }

  def getSvOnboardingStatus(candidate: String): HttpSvAppClient.SvOnboardingStatus =
    consoleEnvironment.run {
      httpCommand(HttpSvAppClient.getSvOnboardingStatus(candidate))
    }

  @Help.Summary("Prepare a validator onboarding and return an onboarding secret (via client API)")
  def devNetOnboardValidatorPrepare(): String =
    consoleEnvironment.run {
      httpCommand(HttpSvAppClient.DevNetOnboardValidatorPrepare())
    }

  def getDsoInfo(): HttpSvAppClient.DsoInfo =
    consoleEnvironment.run {
      httpCommand(HttpSvAppClient.GetDsoInfo)
    }

  @Help.Summary("Get the CometBFT node status")
  def cometBftNodeStatus(): definitions.CometBftNodeStatusResponse =
    consoleEnvironment.run {
      httpCommand(HttpSvAppClient.GetCometBftNodeStatus())
    }

  @Help.Summary("Get the CometBFT node dump")
  def cometBftNodeDebugDump(): definitions.CometBftNodeDumpResponse =
    consoleEnvironment.run {
      httpCommand(HttpSvAdminAppClient.GetCometBftNodeDump())
    }

  @Help.Summary("Make a CometBFT Json RPC request")
  def cometBftJsonRpcRequest(
      id: definitions.CometBftJsonRpcRequestId,
      method: definitions.CometBftJsonRpcRequest.Method,
      params: Map[String, io.circe.Json] = Map.empty,
  ): definitions.CometBftJsonRpcResponse =
    consoleEnvironment.run {
      httpCommand(HttpSvAppClient.CometBftJsonRpcRequest(id, method, params))
    }

  def onboardSvPartyMigrationAuthorize(
      participantId: ParticipantId,
      candidateParty: PartyId,
  ): HttpSvAppClient.OnboardSvPartyMigrationAuthorizeResponse =
    consoleEnvironment
      .run {
        httpCommand(
          HttpSvAppClient.OnboardSvPartyMigrationAuthorize(
            participantId,
            candidateParty,
          )
        )
      }
      .fold(throw _, identity)

  @Help.Summary("Pause the global domain")
  def pauseDecentralizedSynchronizer(): Unit =
    consoleEnvironment.run {
      httpCommand(HttpSvAdminAppClient.PauseDecentralizedSynchronizer())
    }

  @Help.Summary("Unpause the global domain")
  def unpauseDecentralizedSynchronizer(): Unit =
    consoleEnvironment.run {
      httpCommand(HttpSvAdminAppClient.UnpauseDecentralizedSynchronizer())
    }

  @Help.Summary("Dump all the required data for domain migration to the configured location")
  def triggerDecentralizedSynchronizerMigrationDump(migrationId: Long): Unit =
    consoleEnvironment.run {
      httpCommand(HttpSvAdminAppClient.TriggerDomainMigrationDump(migrationId))
    }

  @Help.Summary("Get a snapshot of all the dynamic data from the domain")
  def getDomainDataSnapshot(
      timestamp: Instant,
      partyId: Option[PartyId] = None,
      migrationId: Option[Long] = None,
      force: Boolean = false,
  ): DomainDataSnapshot.Response =
    consoleEnvironment.run {
      httpCommand(
        HttpSvAdminAppClient.GetDomainDataSnapshot(
          timestamp,
          partyId,
          migrationId = migrationId,
          force = force,
        )
      )
    }

  @Help.Summary("Get identities of all domain node components")
  def getSynchronizerNodeIdentitiesDump(): SynchronizerNodeIdentities =
    consoleEnvironment.run {
      httpCommand(HttpSvAdminAppClient.GetSynchronizerNodeIdentitiesDump())
    }

  @Help.Summary("Create a vote request")
  def createVoteRequest(
      requester: String,
      action: ActionRequiringConfirmation,
      reasonUrl: String,
      reasonDescription: String,
      expiration: RelTime,
      effectiveTime: Option[Instant],
  )(implicit tc: TraceContext): Unit = {
    consoleEnvironment.run {
      httpCommand(
        HttpSvAdminAppClient.CreateVoteRequest(
          requester,
          action,
          reasonUrl,
          reasonDescription,
          expiration,
          effectiveTime,
        )
      )
    }
  }

  @Help.Summary("List vote requests")
  def listVoteRequests(): Seq[Contract[VoteRequest.ContractId, VoteRequest]] = {
    consoleEnvironment.run {
      httpCommand(
        HttpSvAdminAppClient.ListVoteRequests
      )
    }
  }

  @Help.Summary("Get the latest vote request trackingCid")
  def getLatestVoteRequestTrackingCid(): VoteRequest.ContractId = {
    val latestVoteRequest = this
      .listVoteRequests()
      .headOption
      .getOrElse(
        throw new RuntimeException("No latest vote request found")
      )
    latestVoteRequest.payload.trackingCid.toScala.getOrElse(latestVoteRequest.contractId)
  }

  @Help.Summary("Lookup vote request")
  def lookupVoteRequest(
      trackingCid: VoteRequest.ContractId
  ): Contract[VoteRequest.ContractId, VoteRequest] = {
    consoleEnvironment.run {
      httpCommand(
        HttpSvAdminAppClient.LookupVoteRequest(trackingCid)()
      )
    }
  }

  def listVoteRequestResults(
      actionName: Option[String],
      accepted: Option[Boolean],
      requester: Option[String],
      effectiveFrom: Option[String],
      effectiveTo: Option[String],
      limit: BigInt,
  ): Seq[DsoRules_CloseVoteRequestResult] = {
    consoleEnvironment.run {
      httpCommand(
        HttpSvAdminAppClient.ListVoteRequestResults(
          actionName,
          accepted,
          requester,
          effectiveFrom,
          effectiveTo,
          limit,
        )
      )
    }
  }

  @Help.Summary("Cast a vote")
  def castVote(
      trackingCid: VoteRequest.ContractId,
      isAccepted: Boolean,
      reasonUrl: String,
      reasonDescription: String,
  ): Unit = {
    consoleEnvironment.run {
      httpCommand(
        HttpSvAdminAppClient.CastVote(trackingCid, isAccepted, reasonUrl, reasonDescription)
      )
    }
  }

}

final case class SvAppClientReference(
    override val consoleEnvironment: SpliceConsoleEnvironment,
    override val name: String,
    val config: SvAppClientConfig,
    override val token: Option[String] = None,
) extends SvAppReference(consoleEnvironment, name) {

  override def httpClientConfig = config.adminApi

  override protected val instanceType = "SV Client"

}

/** Single sv app backend reference. Defines the console commands that can be run against a backend SV
  * app.
  */
class SvAppBackendReference(
    override val consoleEnvironment: SpliceConsoleEnvironment,
    name: String,
)(implicit actorSystem: ActorSystem)
    extends SvAppReference(consoleEnvironment, name)
    with AppBackendReference
    with BaseInspection[SvApp] {

  override def runningNode: Option[SvAppBootstrap] =
    consoleEnvironment.environment.svs.getRunning(name)

  override def startingNode: Option[SvAppBootstrap] =
    consoleEnvironment.environment.svs.getStarting(name)

  override protected val instanceType = "SV"

  override def token: Option[String] = {
    Some(
      AuthUtil.testToken(
        audience = AuthUtil.testAudience,
        user = config.ledgerApiUser,
        secret = AuthUtil.testSecret,
      )
    )
  }

  override def httpClientConfig = NetworkAppClientConfig(
    s"http://127.0.0.1:${config.clientAdminApi.port}"
  )

  val nodes: org.lfdecentralizedtrust.splice.environment.SvApps = consoleEnvironment.environment.svs

  @Help.Summary(
    "Returns the state of this app. May only be called while the app is running."
  )
  def appState: SvApp.State = _appState[SvApp.State, SvApp]

  @Help.Summary(
    "Returns the current delegate based automation. Do not keep references to the result, as this automation gets replaced whenever the DSO delegate changes."
  )
  def dsoDelegateBasedAutomation: DsoDelegateBasedAutomationService = {
    appState.dsoAutomation.restartDsoDelegateBasedAutomationTrigger.epochState
      .getOrElse(throw new RuntimeException("LeaderBasedAutomation is not fully started up"))
      .dsoDelegateBasedAutomation
  }

  @Help.Summary(
    "Returns the current DSO automation."
  )
  def dsoAutomation: SvDsoAutomationService = {
    appState.dsoAutomation
  }

  @Help.Summary(
    "Returns the current SV automation."
  )
  def svAutomation: SvSvAutomationService = {
    appState.svAutomation
  }

  @Help.Summary("Return SV app config")
  def config: SvAppBackendConfig =
    consoleEnvironment.environment.config.svsByString(name)

  def listOngoingValidatorOnboardings(): Seq[ValidatorOnboarding] =
    consoleEnvironment.run {
      httpCommand(
        HttpSvAdminAppClient.ListOngoingValidatorOnboardings
      )
    }

  @Help.Summary("Prepare a validator onboarding and return an onboarding secret (via admin API)")
  def prepareValidatorOnboarding(expiresIn: FiniteDuration, partyHint: Option[String]): String =
    consoleEnvironment.run {
      httpCommand(
        HttpSvAdminAppClient.PrepareValidatorOnboarding(expiresIn, partyHint)
      )
    }

  @Help.Summary("Update CC price vote (via admin API)")
  def updateAmuletPriceVote(amuletPrice: BigDecimal): Unit =
    consoleEnvironment.run {
      httpCommand(
        HttpSvAdminAppClient.UpdateAmuletPriceVote(amuletPrice)
      )
    }

  @Help.Summary("List CC price vote (via admin API)")
  def listAmuletPriceVotes(): Seq[Contract[cp.AmuletPriceVote.ContractId, cp.AmuletPriceVote]] = {
    consoleEnvironment.run {
      httpCommand(
        HttpSvAdminAppClient.ListAmuletPriceVotes
      )
    }
  }

  @Help.Summary("List open mining rounds (via admin API)")
  def listOpenMiningRounds(): Seq[Contract[OpenMiningRound.ContractId, OpenMiningRound]] = {
    consoleEnvironment.run {
      httpCommand(
        HttpSvAdminAppClient.ListOpenMiningRounds
      )
    }
  }

  @Help.Summary("Get the CometBFT node debug dump")
  def cometBftNodeDump(): definitions.CometBftNodeDumpResponse =
    consoleEnvironment.run {
      httpCommand(HttpSvAdminAppClient.GetCometBftNodeDump())
    }

  @Help.Summary("Get the sequencer node status")
  def sequencerNodeStatus(): NodeStatus[SpliceStatus] =
    consoleEnvironment.run {
      httpCommand(HttpSvAdminAppClient.GetSequencerNodeStatus())
    }

  @Help.Summary("Get the mediator node status")
  def mediatorNodeStatus(): NodeStatus[SpliceStatus] =
    consoleEnvironment.run {
      httpCommand(HttpSvAdminAppClient.GetMediatorNodeStatus())
    }

  /** Remote participant this sv app is configured to interact with. */
  lazy val participantClient =
    new ParticipantClientReference(
      consoleEnvironment,
      s"remote participant for `$name``",
      config.participantClient.getParticipantClientConfig(),
    )

  /** Remote participant this sv app is configured to interact with. Uses admin tokens to bypass auth. */
  lazy val participantClientWithAdminToken =
    new ParticipantClientReference(
      consoleEnvironment,
      s"remote participant for `$name`, with admin token",
      config.participantClient.participantClientConfigWithAdminToken,
    )

  private def localSynchronizerNode = config.localSynchronizerNode.getOrElse(
    throw new RuntimeException("No synchronizer node configured for SV app")
  )

  lazy val sequencerClient: SequencerClientReference =
    new SequencerClientReference(
      consoleEnvironment,
      s"sequencer client for $name",
      localSynchronizerNode.sequencer.toCantonConfig,
    )

  lazy val mediatorClient: MediatorClientReference =
    new MediatorClientReference(
      consoleEnvironment,
      s"mediator client for $name",
      localSynchronizerNode.mediator.toCantonConfig,
    )
}
