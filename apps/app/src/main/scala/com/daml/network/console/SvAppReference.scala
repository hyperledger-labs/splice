package com.daml.network.console

import org.apache.pekko.actor.ActorSystem
import com.daml.network.auth.AuthUtil
import com.daml.network.codegen.java.cc.round.OpenMiningRound
import com.daml.network.codegen.java.cn.svc.coinprice as cp
import com.daml.network.codegen.java.cn.svcrules.{
  ActionRequiringConfirmation,
  Vote,
  VoteRequest,
  VoteResult,
}
import com.daml.network.codegen.java.cn.validatoronboarding as vo
import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.config.NetworkAppClientConfig
import com.daml.network.environment.{CNNodeConsoleEnvironment, CNNodeStatus}
import com.daml.network.http.v0.definitions
import com.daml.network.sv.SvApp
import com.daml.network.sv.admin.api.client.commands.{HttpSvAdminAppClient, HttpSvAppClient}
import com.daml.network.sv.automation.{LeaderBasedAutomationService, SvSvcAutomationService}
import com.daml.network.sv.automation.singlesv.RestartLeaderBasedAutomationTrigger
import com.daml.network.sv.config.{SvAppBackendConfig, SvAppClientConfig}
import com.daml.network.util.Contract
import com.digitalasset.canton.console.{BaseInspection, Help}
import com.digitalasset.canton.health.admin.data.NodeStatus
import com.digitalasset.canton.participant.ParticipantNode
import com.digitalasset.canton.topology.{ParticipantId, PartyId}

import scala.concurrent.duration.FiniteDuration

abstract class SvAppReference(
    override val cnNodeConsoleEnvironment: CNNodeConsoleEnvironment,
    override val name: String,
) extends HttpCNNodeAppReference {

  override def basePath = "/api/sv"
  override protected val instanceType = "SV Client"

  def onboardValidator(validator: PartyId, secret: String): Unit =
    consoleEnvironment.run {
      httpCommand(HttpSvAppClient.OnboardValidator(validator, secret))
    }

  def approveSvIdentity(name: String, key: String): Unit =
    consoleEnvironment.run {
      httpCommand(HttpSvAdminAppClient.ApproveSvIdentity(name, key))
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

  def getSvcInfo(): HttpSvAppClient.SvcInfo =
    consoleEnvironment.run {
      httpCommand(HttpSvAppClient.GetSvcInfo)
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
      id: io.circe.Json,
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

  @Help.Summary("Trigger and a dump of the ACS visible to the SVC party")
  def triggerAcsDump(): definitions.TriggerAcsDumpResponse =
    consoleEnvironment.run {
      httpCommand(HttpSvAdminAppClient.TriggerAcsDump())
    }

  @Help.Summary("Trigger and a dump of the ACS visible to the SVC party")
  def getAcsStoreDump(): definitions.GetAcsStoreDumpResponse =
    consoleEnvironment.run {
      httpCommand(HttpSvAdminAppClient.GetAcsStoreDump())
    }
}

final case class SvAppClientReference(
    override val consoleEnvironment: CNNodeConsoleEnvironment,
    override val name: String,
    val config: SvAppClientConfig,
    override val token: Option[String] = None,
) extends SvAppReference(consoleEnvironment, name)
    with BaseInspection[ParticipantNode] {

  override def httpClientConfig = config.adminApi

  override protected val instanceType = "SV Client"

}

/** Single sv app backend reference. Defines the console commands that can be run against a backend SV
  * app.
  */
class SvAppBackendReference(
    override val consoleEnvironment: CNNodeConsoleEnvironment,
    name: String,
)(implicit actorSystem: ActorSystem)
    extends SvAppReference(consoleEnvironment, name)
    with CNNodeAppBackendReference
    with BaseInspection[ParticipantNode] {

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

  val nodes = consoleEnvironment.environment.svs

  @Help.Summary(
    "Returns the state of this app. May only be called while the app is running."
  )
  def appState: SvApp.State = _appState[SvApp.State, SvApp]

  @Help.Summary(
    "Returns the current leader based automation. Do not keep references to the result, as this automation gets replaced whenever the SVC leader changes."
  )
  def leaderBasedAutomation: LeaderBasedAutomationService = {
    appState.svcAutomation
      .trigger[RestartLeaderBasedAutomationTrigger]
      .epochState
      .getOrElse(throw new RuntimeException("LeaderBasedAutomation is not fully started up"))
      .leaderBasedAutomation
  }

  @Help.Summary(
    "Returns the current SVC automation."
  )
  def svcAutomation: SvSvcAutomationService = {
    appState.svcAutomation
  }

  @Help.Summary("Return SV app config")
  def config: SvAppBackendConfig =
    consoleEnvironment.environment.config.svsByString(name)

  def listOngoingValidatorOnboardings()
      : Seq[Contract[vo.ValidatorOnboarding.ContractId, vo.ValidatorOnboarding]] =
    consoleEnvironment.run {
      httpCommand(
        HttpSvAdminAppClient.ListOngoingValidatorOnboardings
      )
    }

  @Help.Summary("Prepare a validator onboarding and return an onboarding secret (via admin API)")
  def prepareValidatorOnboarding(expiresIn: FiniteDuration): String =
    consoleEnvironment.run {
      httpCommand(
        HttpSvAdminAppClient.PrepareValidatorOnboarding(expiresIn)
      )
    }

  @Help.Summary("Update CC price vote (via admin API)")
  def updateCoinPriceVote(coinPrice: BigDecimal): Unit =
    consoleEnvironment.run {
      httpCommand(
        HttpSvAdminAppClient.UpdateCoinPriceVote(coinPrice)
      )
    }

  @Help.Summary("List CC price vote (via admin API)")
  def listCoinPriceVotes(): Seq[Contract[cp.CoinPriceVote.ContractId, cp.CoinPriceVote]] = {
    consoleEnvironment.run {
      httpCommand(
        HttpSvAdminAppClient.ListCoinPriceVotes
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

  @Help.Summary("Create an election request (via admin API)")
  def createElectionRequest(
      requester: String,
      ranking: Vector[String],
  ): Unit = {
    consoleEnvironment.run {
      httpCommand(
        HttpSvAdminAppClient.CreateElectionRequest(requester, ranking)
      )
    }
  }

  @Help.Summary("Create a vote request (via admin API)")
  def createVoteRequest(
      requester: String,
      action: ActionRequiringConfirmation,
      reasonUrl: String,
      reasonDescription: String,
      expiration: RelTime,
  ): Unit = {
    consoleEnvironment.run {
      httpCommand(
        HttpSvAdminAppClient.CreateVoteRequest(
          requester,
          action,
          reasonUrl,
          reasonDescription,
          expiration,
        )
      )
    }
  }

  @Help.Summary("List vote requests (via admin API)")
  def listVoteRequests(): Seq[Contract[VoteRequest.ContractId, VoteRequest]] = {
    consoleEnvironment.run {
      httpCommand(
        HttpSvAdminAppClient.ListVoteRequests
      )
    }
  }

  @Help.Summary("List vote results")
  def listVoteResults(
      actionName: Option[String],
      executed: Option[Boolean],
      requester: Option[String],
      effectiveFrom: Option[String],
      effectiveTo: Option[String],
      limit: BigInt,
  ): Seq[VoteResult] = {
    consoleEnvironment.run {
      httpCommand(
        HttpSvAdminAppClient.ListVoteResults(
          actionName,
          executed,
          requester,
          effectiveFrom,
          effectiveTo,
          limit,
        )()
      )
    }
  }

  @Help.Summary("Cast a vote (via admin API)")
  def castVote(
      voteRequestCid: VoteRequest.ContractId,
      isAccepted: Boolean,
      reasonUrl: String,
      reasonDescription: String,
  ): Unit = {
    consoleEnvironment.run {
      httpCommand(
        HttpSvAdminAppClient.CastVote(voteRequestCid, isAccepted, reasonUrl, reasonDescription)
      )
    }
  }

  @Help.Summary("Update a vote (via admin API)")
  def updateVote(
      voteRequestCid: VoteRequest.ContractId,
      isAccepted: Boolean,
      reasonUrl: String,
      reasonDescription: String,
  ): Unit = {
    consoleEnvironment.run {
      httpCommand(
        HttpSvAdminAppClient.UpdateVote(voteRequestCid, isAccepted, reasonUrl, reasonDescription)
      )
    }
  }

  @Help.Summary("List votes (via admin API)")
  def listVotes(voteRequestCid: Vector[String]): Seq[Contract[Vote.ContractId, Vote]] = {
    consoleEnvironment.run {
      httpCommand(
        HttpSvAdminAppClient.ListVotes(voteRequestCid)
      )
    }
  }

  @Help.Summary("Get the CometBFT node debug dump")
  def cometBftNodeDump(): definitions.CometBftNodeDumpResponse =
    consoleEnvironment.run {
      httpCommand(HttpSvAdminAppClient.GetCometBftNodeDump())
    }

  @Help.Summary("Get the sequencer node status")
  def sequencerNodeStatus(): NodeStatus[CNNodeStatus] =
    consoleEnvironment.run {
      httpCommand(HttpSvAdminAppClient.GetSequencerNodeStatus())
    }

  @Help.Summary("Get the mediator node status")
  def mediatorNodeStatus(): NodeStatus[CNNodeStatus] =
    consoleEnvironment.run {
      httpCommand(HttpSvAdminAppClient.GetMediatorNodeStatus())
    }

  @Help.Summary("Set DomainRatePerParticipant to zero")
  def pauseGlobalDomain(): Unit =
    consoleEnvironment.run {
      httpCommand(HttpSvAdminAppClient.PauseGlobalDomain())
    }

  /** Remote participant this sv app is configured to interact with. */
  lazy val participantClient =
    new CNParticipantClientReference(
      consoleEnvironment,
      s"remote participant for `$name``",
      config.participantClient.getParticipantClientConfig(),
    )

  /** Remote participant this sv app is configured to interact with. Uses admin tokens to bypass auth. */
  lazy val participantClientWithAdminToken =
    new CNParticipantClientReference(
      consoleEnvironment,
      s"remote participant for `$name`, with admin token",
      config.participantClient.participantClientConfigWithAdminToken,
    )

  /** secret, not publicly documented way to get the admin token */
  def adminToken: Option[String] = underlying.map(_.adminToken.secret)
}
