// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.console

import com.digitalasset.canton.admin.api.client.data.NodeStatus
import com.digitalasset.canton.console.{BaseInspection, Help}
import com.digitalasset.canton.topology.{ParticipantId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.auth.AuthUtil
import org.lfdecentralizedtrust.splice.codegen.java.da.time.types.RelTime
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.amuletprice as cp
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{
  ActionRequiringConfirmation,
  DsoRules_CloseVoteRequestResult,
  VoteRequest,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.OpenMiningRound
import org.lfdecentralizedtrust.splice.config.NetworkAppClientConfig
import org.lfdecentralizedtrust.splice.environment.{
  BuildInfo,
  SpliceConsoleEnvironment,
  SpliceStatus,
}
import org.lfdecentralizedtrust.splice.http.v0.definitions
import org.lfdecentralizedtrust.splice.store.VoteResultsFilters
import org.lfdecentralizedtrust.splice.sv.{SvApp, SvAppBootstrap, SvAppClientConfig}
import org.lfdecentralizedtrust.splice.sv.admin.api.client.commands.{
  HttpSvAdminAppClient,
  HttpSvOperatorAppClient,
  HttpSvPublicAppClient,
}
import org.lfdecentralizedtrust.splice.sv.automation.{
  DsoDelegateBasedAutomationService,
  SvDsoAutomationService,
  SvSvAutomationService,
}
import org.lfdecentralizedtrust.splice.sv.config.{
  SvAppBackendConfig,
  SvSynchronizerNodeConfig,
  SvSynchronizerNodesConfig,
}
import org.lfdecentralizedtrust.splice.sv.migration.SynchronizerNodeIdentities
import org.lfdecentralizedtrust.splice.sv.util.ValidatorOnboarding
import org.lfdecentralizedtrust.splice.util.{Contract, DsoInfo}

import java.time.Instant
import scala.concurrent.duration.FiniteDuration
import scala.jdk.OptionConverters.*

abstract class SvAppReference(
    override val spliceConsoleEnvironment: SpliceConsoleEnvironment,
    override val name: String,
) extends HttpAppReference {

  override def basePath = "/api/sv"
  override protected val instanceType = "SV Client"

  def onboardValidator(validator: PartyId, secret: String, contactPoint: String): Unit =
    consoleEnvironment.run {
      httpCommand(
        HttpSvPublicAppClient.OnboardValidator(
          validator,
          secret,
          BuildInfo.compiledVersion,
          contactPoint,
        )
      )
    }

  def startSvOnboarding(token: String): Unit =
    consoleEnvironment.run {
      httpCommand(HttpSvPublicAppClient.StartSvOnboarding(token))
    }

  def getSvOnboardingStatus(candidate: PartyId): HttpSvPublicAppClient.SvOnboardingStatus =
    consoleEnvironment.run {
      httpCommand(HttpSvPublicAppClient.getSvOnboardingStatus(candidate.toProtoPrimitive))
    }

  def getSvOnboardingStatus(candidate: String): HttpSvPublicAppClient.SvOnboardingStatus =
    consoleEnvironment.run {
      httpCommand(HttpSvPublicAppClient.getSvOnboardingStatus(candidate))
    }

  @Help.Summary("Prepare a validator onboarding and return an onboarding secret (via client API)")
  def devNetOnboardValidatorPrepare(): String =
    consoleEnvironment.run {
      httpCommand(HttpSvPublicAppClient.DevNetOnboardValidatorPrepare())
    }

  def getDsoInfo(): DsoInfo =
    consoleEnvironment.run {
      httpCommand(HttpSvOperatorAppClient.GetDsoInfo)
    }

  @Help.Summary("Get the CometBFT node status")
  def cometBftNodeStatus(): definitions.CometBftNodeStatusResponse =
    consoleEnvironment.run {
      httpCommand(HttpSvPublicAppClient.GetCometBftNodeStatus())
    }

  @Help.Summary("Get the CometBFT node dump")
  def cometBftNodeDebugDump(): definitions.CometBftNodeDumpResponse =
    consoleEnvironment.run {
      httpCommand(HttpSvOperatorAppClient.GetCometBftNodeDump())
    }

  @Help.Summary("Make a CometBFT Json RPC request")
  def cometBftJsonRpcRequest(
      id: definitions.CometBftJsonRpcRequestId,
      method: definitions.CometBftJsonRpcRequest.Method,
      params: Map[String, io.circe.Json] = Map.empty,
  ): definitions.CometBftJsonRpcResponse =
    consoleEnvironment.run {
      httpCommand(HttpSvPublicAppClient.CometBftJsonRpcRequest(id, method, params))
    }

  def onboardSvPartyMigrationAuthorize(
      participantId: ParticipantId,
      candidateParty: PartyId,
  ): HttpSvPublicAppClient.OnboardSvPartyMigrationAuthorizeResponse =
    consoleEnvironment
      .run {
        httpCommand(
          HttpSvPublicAppClient.OnboardSvPartyMigrationAuthorize(
            participantId,
            candidateParty,
          )
        )
      }
      .fold(throw _, identity)

  @Help.Summary("Cancel a running logical synchronizer upgrade by removing its LSU announcement")
  def cancelLogicalSynchronizerUpgrade(): Unit =
    consoleEnvironment.run {
      httpCommand(HttpSvOperatorAppClient.CancelLogicalSynchronizerUpgrade())
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
        HttpSvOperatorAppClient.CreateVoteRequest(
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
        HttpSvOperatorAppClient.ListVoteRequests
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

  @Help.Summary("List vote requests by their trackingCids")
  def listVoteRequestsByTrackingCid(
      trackingCids: Seq[VoteRequest.ContractId]
  ): Seq[Contract[VoteRequest.ContractId, VoteRequest]] = {
    consoleEnvironment.run {
      httpCommand(
        HttpSvOperatorAppClient.ListVoteRequestsByTrackingCid(trackingCids)
      )
    }
  }

  @Help.Summary("Lookup vote request")
  def lookupVoteRequest(
      trackingCid: VoteRequest.ContractId
  ): Contract[VoteRequest.ContractId, VoteRequest] = {
    consoleEnvironment.run {
      httpCommand(
        HttpSvOperatorAppClient.LookupVoteRequest(trackingCid)()
      )
    }
  }

  def listVoteRequestResults(
      filters: VoteResultsFilters,
      limit: BigInt,
      pageToken: Option[BigInt] = None,
  ): (Seq[DsoRules_CloseVoteRequestResult], Option[BigInt]) = {
    consoleEnvironment.run {
      httpCommand(
        HttpSvOperatorAppClient.ListVoteRequestResults(
          filters,
          limit,
          pageToken,
        )
      )
    }
  }

  def countVoteRequestResults(
      filters: VoteResultsFilters
  ): Long = {
    consoleEnvironment.run {
      httpCommand(
        HttpSvOperatorAppClient.CountVoteRequestResults(filters)
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
        HttpSvOperatorAppClient.CastVote(trackingCid, isAccepted, reasonUrl, reasonDescription)
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
) extends SvAppReference(consoleEnvironment, name)
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
    "Returns the delegate based automation. The reference is stable for the lifetime of the app."
  )
  def dsoDelegateBasedAutomation: DsoDelegateBasedAutomationService =
    appState.dsoAutomation.dsoDelegateBasedAutomation

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
        HttpSvOperatorAppClient.ListOngoingValidatorOnboardings
      )
    }

  @Help.Summary("Prepare a validator onboarding and return an onboarding secret (via admin API)")
  def prepareValidatorOnboarding(expiresIn: FiniteDuration, partyHint: Option[String]): String =
    consoleEnvironment.run {
      httpCommand(
        HttpSvOperatorAppClient.PrepareValidatorOnboarding(expiresIn, partyHint)
      )
    }

  @Help.Summary("Update CC price vote (via admin API)")
  def updateAmuletPriceVote(amuletPrice: BigDecimal): Unit =
    consoleEnvironment.run {
      httpCommand(
        HttpSvOperatorAppClient.UpdateAmuletPriceVote(amuletPrice)
      )
    }

  @Help.Summary("List CC price vote (via admin API)")
  def listAmuletPriceVotes(): Seq[Contract[cp.AmuletPriceVote.ContractId, cp.AmuletPriceVote]] = {
    consoleEnvironment.run {
      httpCommand(
        HttpSvOperatorAppClient.ListAmuletPriceVotes
      )
    }
  }

  @Help.Summary("List open mining rounds (via admin API)")
  def listOpenMiningRounds(): Seq[Contract[OpenMiningRound.ContractId, OpenMiningRound]] = {
    consoleEnvironment.run {
      httpCommand(
        HttpSvOperatorAppClient.ListOpenMiningRounds
      )
    }
  }

  @Help.Summary(
    "Archive dry-run CalculateRewardsV2 and ProcessRewardsV2 contracts for the given rounds (via admin API)"
  )
  def archiveDryRunRewardAccountingContracts(rounds: Seq[Long]): Unit =
    consoleEnvironment.run {
      httpCommand(
        HttpSvOperatorAppClient.ArchiveDryRunRewardAccountingContracts(rounds)
      )
    }

  @Help.Summary("Get the CometBFT node debug dump")
  def cometBftNodeDump(): definitions.CometBftNodeDumpResponse =
    consoleEnvironment.run {
      httpCommand(HttpSvOperatorAppClient.GetCometBftNodeDump())
    }

  @Help.Summary("Get the party to participant mapping for a given party")
  def getPartyToParticipant(partyId: String): definitions.GetPartyToParticipantResponseV1 =
    consoleEnvironment.run {
      httpCommand(HttpSvOperatorAppClient.GetPartyToParticipant(partyId))
    }

  @Help.Summary("Get the sequencer node status")
  def sequencerNodeStatus(): NodeStatus[SpliceStatus] =
    consoleEnvironment.run {
      httpCommand(HttpSvOperatorAppClient.GetSequencerNodeStatus())
    }

  @Help.Summary("Get the mediator node status")
  def mediatorNodeStatus(): NodeStatus[SpliceStatus] =
    consoleEnvironment.run {
      httpCommand(HttpSvOperatorAppClient.GetMediatorNodeStatus())
    }

  /** Remote participant this sv app is configured to interact with. Uses admin tokens to bypass auth. */
  lazy val participantClientWithAdminToken =
    new ParticipantClientReference(
      consoleEnvironment,
      s"remote participant for `$name`, with admin token",
      config.participantClient.participantClientConfigWithAdminToken,
    )

  lazy val sequencerClient: SequencerClientReference =
    sequencerClientFor(_.current)

  lazy val sequencerClientSuccessor: SequencerClientReference =
    sequencerClientFor(
      _.successor.getOrElse(throw new IllegalStateException("successor not configured"))
    )

  def sequencerClientFor(
      node: SvSynchronizerNodesConfig => SvSynchronizerNodeConfig
  ): SequencerClientReference = {
    new SequencerClientReference(
      consoleEnvironment,
      s"sequencer client for $name",
      node(config.localSynchronizerNodes).sequencer.toCantonConfig,
    )
  }

  lazy val mediatorClient: MediatorClientReference =
    mediatorClientFor(_.current)

  def mediatorClientFor(
      node: SvSynchronizerNodesConfig => SvSynchronizerNodeConfig
  ): MediatorClientReference = {
    new MediatorClientReference(
      consoleEnvironment,
      s"mediator client for $name",
      node(
        config.localSynchronizerNodes
      ).mediator.toCantonConfig,
    )
  }
}
