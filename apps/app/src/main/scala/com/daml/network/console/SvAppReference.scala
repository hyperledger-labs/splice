package com.daml.network.console

import com.daml.network.auth.AuthUtil
import com.daml.network.codegen.java.cc.round as cr
import com.daml.network.codegen.java.cn.svc.coinprice as cp
import com.daml.network.codegen.java.cn.svcrules.{Vote, VoteRequest}
import com.daml.network.codegen.java.cn.validatoronboarding as vo
import com.daml.network.config.NetworkAppClientConfig
import com.daml.network.environment.{CNNodeConsoleEnvironment, CNNodeStatus}
import com.daml.network.http.v0.definitions.{CometBftNodeDumpResponse, CometBftNodeStatusResponse}
import com.daml.network.sv.admin.api.client.commands.{HttpSvAdminAppClient, HttpSvAppClient}
import com.daml.network.sv.config.{SvAppBackendConfig, SvAppClientConfig}
import com.daml.network.util.Contract
import com.digitalasset.canton.console.{BaseInspection, Help}
import com.digitalasset.canton.health.admin.data.NodeStatus
import com.digitalasset.canton.participant.ParticipantNode
import com.digitalasset.canton.topology.{MediatorId, ParticipantId, PartyId, SequencerId}

import scala.concurrent.duration.FiniteDuration

abstract class SvAppReference(
    override val cnNodeConsoleEnvironment: CNNodeConsoleEnvironment,
    override val name: String,
) extends HttpCNNodeAppReference {

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

  def onboardSvPartyMigrationAuthorize(
      participantId: ParticipantId,
      sequencerId: Option[SequencerId],
      mediatorId: Option[MediatorId],
      candidateParty: PartyId,
  ): HttpSvAppClient.OnboardSvPartyMigrationAuthorizeResponse =
    consoleEnvironment.run {
      httpCommand(
        HttpSvAppClient.OnboardSvPartyMigrationAuthorize(
          participantId,
          sequencerId,
          mediatorId,
          candidateParty,
        )
      )
    }
}

class SvAppClientReference(
    override val consoleEnvironment: CNNodeConsoleEnvironment,
    name: String,
    val config: SvAppClientConfig,
) extends SvAppReference(consoleEnvironment, name)
    with BaseInspection[ParticipantNode] {

  override def httpClientConfig = config.adminApi
}

/** Single sv app backend reference. Defines the console commands that can be run against a backend SV
  * app.
  */
class SvAppBackendReference(
    override val consoleEnvironment: CNNodeConsoleEnvironment,
    name: String,
) extends SvAppReference(consoleEnvironment, name)
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

  protected val nodes = consoleEnvironment.environment.svs

  @Help.Summary("Return sv app config")
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

  @Help.Summary("Update cc price vote (via admin API)")
  def updateCoinPriceVote(coinPrice: BigDecimal): Unit =
    consoleEnvironment.run {
      httpCommand(
        HttpSvAdminAppClient.UpdateCoinPriceVote(coinPrice)
      )
    }

  @Help.Summary("List cc price vote (via admin API)")
  def listCoinPriceVotes(): Seq[Contract[cp.CoinPriceVote.ContractId, cp.CoinPriceVote]] = {
    consoleEnvironment.run {
      httpCommand(
        HttpSvAdminAppClient.ListCoinPriceVotes
      )
    }
  }

  @Help.Summary("List open mining rounds (via admin API)")
  def listOpenMiningRounds(): Seq[Contract[cr.OpenMiningRound.ContractId, cr.OpenMiningRound]] = {
    consoleEnvironment.run {
      httpCommand(
        HttpSvAdminAppClient.ListOpenMiningRounds
      )
    }
  }

  @Help.Summary("Create a vote request (via admin API)")
  def createVoteRequest(
      requester: String,
      action: String,
      reasonUrl: String,
      reasonDescription: String,
  ): Unit = {
    consoleEnvironment.run {
      httpCommand(
        HttpSvAdminAppClient.CreateVoteRequest(requester, action, reasonUrl, reasonDescription)
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

  @Help.Summary("Get the CometBFT node status")
  def cometBftNodeStatus(): CometBftNodeStatusResponse =
    consoleEnvironment.run {
      httpCommand(HttpSvAdminAppClient.GetCometBftNodeStatus())
    }

  @Help.Summary("Get the CometBFT node debug dump")
  def cometBftNodeDump(): CometBftNodeDumpResponse =
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
