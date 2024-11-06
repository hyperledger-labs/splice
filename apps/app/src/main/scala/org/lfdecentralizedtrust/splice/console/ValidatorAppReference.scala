// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.console

import org.lfdecentralizedtrust.splice.auth.AuthUtil
import org.lfdecentralizedtrust.splice.codegen.java.splice.ans.AnsRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules as amuletrulesCodegen
import org.lfdecentralizedtrust.splice.config.NetworkAppClientConfig
import org.lfdecentralizedtrust.splice.environment.SpliceConsoleEnvironment
import org.lfdecentralizedtrust.splice.http.v0.definitions
import org.lfdecentralizedtrust.splice.http.v0.definitions.{
  GenerateExternalPartyTopologyResponse,
  LookupTransferCommandStatusResponse,
  SignedTopologyTx,
}
import org.lfdecentralizedtrust.splice.identities.NodeIdentitiesDump
import org.lfdecentralizedtrust.splice.util.ContractWithState
import org.lfdecentralizedtrust.splice.validator.admin.api.client.commands.*
import org.lfdecentralizedtrust.splice.validator.automation.ValidatorAutomationService
import org.lfdecentralizedtrust.splice.validator.config.{
  AppManagerAppClientConfig,
  ValidatorAppBackendConfig,
  ValidatorAppClientConfig,
}
import org.lfdecentralizedtrust.splice.validator.migration.DomainMigrationDump
import org.lfdecentralizedtrust.splice.validator.{ValidatorApp, ValidatorAppBootstrap}
import org.lfdecentralizedtrust.splice.wallet.automation.UserWalletAutomationService
import com.digitalasset.canton.console.{BaseInspection, Help}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.PartyId
import com.google.protobuf.ByteString
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.model.BodyPartEntity
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.TransferPreapproval
import org.lfdecentralizedtrust.splice.codegen.java.splice.externalpartyamuletrules.TransferCommandCounter

import java.time.Instant
import scala.concurrent.Future

/** Console commands that can be executed either through client or backend reference.
  */
abstract class ValidatorAppReference(
    override val spliceConsoleEnvironment: SpliceConsoleEnvironment,
    override val name: String,
) extends HttpAppReference {

  override def basePath = "/api/validator"
  override protected val instanceType = "Validator"

  @Help.Summary("Get validator user info")
  @Help.Description("Return the user info of the validator operator")
  def getValidatorUserInfo(): UserInfo = {
    consoleEnvironment.run {
      httpCommand(
        HttpValidatorPublicAppClient.GetValidatorUserInfo
      )
    }
  }

  @Help.Summary("Get validator party id")
  @Help.Description("Return the party id of the validator operator")
  def getValidatorPartyId(): PartyId =
    getValidatorUserInfo().primaryParty

  @Help.Summary("Create a namespace delegation and party transaction")
  @Help.Description(
    """Create a namespace delegation and party transaction
      |Return the topology transaction and transaction authorization hash (this should be signed by CCSP).""".stripMargin
  )
  def generateExternalPartyTopology(
      partyHint: String,
      publicKey: String,
  ): GenerateExternalPartyTopologyResponse = {
    consoleEnvironment.run {
      httpCommand(
        HttpValidatorAdminAppClient.GenerateExternalPartyTopology(partyHint, publicKey)
      )
    }
  }

  @Help.Summary("Submit a namespace delegation and party transaction")
  def submitExternalPartyTopology(
      topologyTxs: Vector[SignedTopologyTx],
      publicKey: String,
  ): PartyId = {
    consoleEnvironment.run {
      httpCommand(
        HttpValidatorAdminAppClient.SubmitExternalPartyTopology(
          topologyTxs,
          publicKey,
        )
      )
    }
  }

  @Help.Summary("Onboard a new user")
  @Help.Description("""Onboard individual canton-amulet user with a fresh or existing party-id.
                      |Return the user's partyId.""".stripMargin)
  def onboardUser(user: String, existingPartyId: Option[PartyId] = None): PartyId = {
    consoleEnvironment.run {
      httpCommand(
        HttpValidatorAdminAppClient.OnboardUser(user, existingPartyId)
      )
    }
  }

  @Help.Summary("Register a new user identified by token")
  @Help.Description(
    """Register the authenticated canton-amulet user with a fresh party-id.
      |Return the newly set up partyId.""".stripMargin
  )
  def register(): PartyId = {
    consoleEnvironment.run {
      httpCommand(
        HttpValidatorAppClient.Register
      )
    }
  }

  @Help.Summary("List all onboarded users")
  def listUsers(): Seq[String] = {
    consoleEnvironment.run {
      httpCommand(
        HttpValidatorAdminAppClient.ListUsers
      )
    }
  }

  @Help.Summary("Offboard user")
  @Help.Description(
    "Offboards a user from the validator by deleting their wallet install contracts, and closing their wallet automations"
  )
  def offboardUser(username: String) = {
    consoleEnvironment.run {
      httpCommand(
        HttpValidatorAdminAppClient.OffboardUser(username)
      )
    }
  }

  @Help.Summary("Export participant identities")
  @Help.Description(
    "Exports participant ID, secret keys, and necessary topology transactions for cloning to a new participant"
  )
  def dumpParticipantIdentities(): NodeIdentitiesDump = {
    consoleEnvironment.run {
      httpCommand(
        HttpValidatorAdminAppClient.DumpParticipantIdentities()
      )
    }
  }

  @Help.Summary("Extract validator data snapshot")
  def getValidatorDomainDataSnapshot(
      timestamp: String,
      migrationId: Option[Long] = None,
      force: Boolean = false,
  ): DomainMigrationDump = {
    consoleEnvironment.run {
      httpCommand(
        HttpValidatorAdminAppClient.GetValidatorDomainDataSnapshot(
          Instant.parse(timestamp),
          migrationId = migrationId,
          force = force,
        )
      )
    }
  }

  def decentralizedSynchronizerConnectionConfig()
      : definitions.GetDecentralizedSynchronizerConnectionConfigResponse = {
    consoleEnvironment.run {
      httpCommand(
        HttpValidatorAdminAppClient.GetDecentralizedSynchronizerConnectionConfig()
      )
    }
  }

  @Help.Summary("Creates ExternalPartySetupProposal contract for a given party")
  def createExternalPartySetupProposal(
      userPartyId: PartyId
  ): amuletrulesCodegen.ExternalPartySetupProposal.ContractId = {
    consoleEnvironment.run {
      httpCommand(
        HttpValidatorAdminAppClient.CreateExternalPartySetupProposal(userPartyId)
      )
    }
  }

  @Help.Summary("List ExternalPartySetupProposal contracts")
  def listExternalPartySetupProposals(): Seq[ContractWithState[
    amuletrulesCodegen.ExternalPartySetupProposal.ContractId,
    amuletrulesCodegen.ExternalPartySetupProposal,
  ]] = {
    consoleEnvironment.run {
      httpCommand(
        HttpValidatorAdminAppClient.ListExternalPartySetupProposals()
      )
    }
  }

  @Help.Summary("Prepare AcceptExternalPartySetupProposal for a given party")
  def prepareAcceptExternalPartySetupProposal(
      contractId: amuletrulesCodegen.ExternalPartySetupProposal.ContractId,
      userPartyId: PartyId,
  ): definitions.PrepareAcceptExternalPartySetupProposalResponse = {
    consoleEnvironment.run {
      httpCommand(
        HttpValidatorAdminAppClient.PrepareAcceptExternalPartySetupProposal(contractId, userPartyId)
      )
    }
  }

  @Help.Summary("Submit AcceptExternalPartySetupProposal for a given party")
  def submitAcceptExternalPartySetupProposal(
      userPartyId: PartyId,
      transaction: String,
      signature: String,
      publicKey: String,
  ): (amuletrulesCodegen.TransferPreapproval.ContractId, String) = {
    consoleEnvironment.run {
      httpCommand(
        HttpValidatorAdminAppClient.SubmitAcceptExternalPartySetupProposal(
          userPartyId,
          transaction,
          signature,
          publicKey,
        )
      )
    }
  }

  @Help.Summary("Fetch TransferPreapproval for a given party")
  def lookupTransferPreapprovalByParty(userPartyId: PartyId): Option[ContractWithState[
    amuletrulesCodegen.TransferPreapproval.ContractId,
    amuletrulesCodegen.TransferPreapproval,
  ]] = {
    consoleEnvironment.run {
      httpCommand(HttpValidatorAdminAppClient.LookupTransferPreapprovalByParty(userPartyId))
    }
  }

  @Help.Summary("Cancel TransferPreapproval for a given party")
  def cancelTransferPreapprovalByParty(userPartyId: PartyId): Unit = {
    consoleEnvironment.run {
      httpCommand(HttpValidatorAdminAppClient.CancelTransferPreapprovalByParty(userPartyId))
    }
  }

  @Help.Summary("Prepare TransferPreapproval send")
  def prepareTransferPreapprovalSend(
      senderPartyId: PartyId,
      receiverPartyId: PartyId,
      amount: BigDecimal,
      expiresAt: CantonTimestamp,
      nonce: Long,
  ): definitions.PrepareTransferPreapprovalSendResponse = {
    consoleEnvironment.run {
      httpCommand(
        HttpValidatorAdminAppClient.PrepareTransferPreapprovalSend(
          senderPartyId,
          receiverPartyId,
          amount,
          expiresAt,
          nonce,
        )
      )
    }
  }

  @Help.Summary("Submit TransferPreapproval send")
  def submitTransferPreapprovalSend(
      senderPartyId: PartyId,
      transaction: String,
      signature: String,
      publicKey: String,
  ): String = {
    consoleEnvironment.run {
      httpCommand(
        HttpValidatorAdminAppClient.SubmitTransferPreapprovalSend(
          senderPartyId,
          transaction,
          signature,
          publicKey,
        )
      )
    }
  }

  def getExternalPartyBalance(partyId: PartyId): definitions.ExternalPartyBalanceResponse = {
    consoleEnvironment.run {
      httpCommand(
        HttpValidatorAdminAppClient.GetExternalPartyBalance(
          partyId
        )
      )
    }
  }

  @Help.Summary("List TransferPreapprovals contracts")
  def listTransferPreapprovals(): Seq[ContractWithState[
    amuletrulesCodegen.TransferPreapproval.ContractId,
    amuletrulesCodegen.TransferPreapproval,
  ]] = {
    consoleEnvironment.run {
      httpCommand(
        HttpValidatorAdminAppClient.ListTransferPreapprovals()
      )
    }
  }

  def registerApp(
      providerUserId: String,
      configuration: definitions.AppConfiguration,
      release: BodyPartEntity,
  ): Unit =
    consoleEnvironment.run {
      httpCommand(
        HttpAppManagerAdminAppClient.RegisterApp(providerUserId, configuration, release)
      )
    }

  def publishAppRelease(provider: PartyId, release: BodyPartEntity): Unit =
    consoleEnvironment.run {
      httpCommand(
        HttpAppManagerAdminAppClient.PublishAppRelease(provider, release)
      )
    }

  def updateAppConfiguration(provider: PartyId, configuration: definitions.AppConfiguration): Unit =
    consoleEnvironment.run {
      httpCommand(
        HttpAppManagerAdminAppClient.UpdateAppConfiguration(provider, configuration)
      )
    }

  def listRegisteredApps(): Seq[definitions.RegisteredApp] =
    consoleEnvironment.run {
      httpCommand(
        HttpAppManagerAppClient.ListRegisteredApps
      )
    }

  def getLatestAppConfiguration(provider: PartyId): definitions.AppConfiguration =
    consoleEnvironment.run {
      httpCommand(
        HttpAppManagerPublicAppClient.GetLatestAppConfiguration(provider)
      )
    }

  def getLatestAppConfigurationByName(name: String): definitions.AppConfiguration =
    consoleEnvironment.run {
      httpCommand(
        HttpAppManagerPublicAppClient.GetLatestAppConfigurationByName(name)
      )
    }

  def getAppRelease(provider: PartyId, version: String): definitions.AppRelease =
    consoleEnvironment.run {
      httpCommand(
        HttpAppManagerPublicAppClient.GetAppRelease(provider, version)
      )
    }

  def getDarFile(darHash: String): ByteString =
    consoleEnvironment.run {
      httpCommand(
        HttpAppManagerPublicAppClient.GetDarFile(darHash)
      )
    }

  def installApp(manifestUrl: String): Unit =
    consoleEnvironment.run {
      httpCommand(
        HttpAppManagerAdminAppClient.InstallApp(manifestUrl)
      )
    }

  def approveAppReleaseConfiguration(
      provider: PartyId,
      configurationVersion: Long,
      releaseConfigurationIndex: Int,
  ): Unit =
    consoleEnvironment.run {
      httpCommand(
        HttpAppManagerAdminAppClient.ApproveAppReleaseConfiguration(
          provider,
          configurationVersion,
          releaseConfigurationIndex,
        )
      )
    }

  def listInstalledApps(): Seq[definitions.InstalledApp] =
    consoleEnvironment.run {
      httpCommand(
        HttpAppManagerAppClient.ListInstalledApps
      )
    }

  def oauth2Jwks(): definitions.JwksResponse =
    consoleEnvironment.run {
      httpCommand(
        HttpAppManagerPublicAppClient.Oauth2Jwks
      )
    }

  def oauth2OpenIdConfiguration(): definitions.OpenIdConfigurationResponse =
    consoleEnvironment.run {
      httpCommand(
        HttpAppManagerPublicAppClient.Oauth2OpenIdConfiguration
      )
    }

  def oauth2Token(
      grantType: String,
      code: String,
      redirectUri: String,
      clientId: String,
  ): definitions.TokenResponse =
    consoleEnvironment.run {
      httpCommand(
        HttpAppManagerPublicAppClient.Oauth2Token(grantType, code, redirectUri, clientId)
      )
    }

  object scanProxy {

    def getDsoParty(): PartyId = {
      consoleEnvironment.run {
        httpCommand(
          HttpScanProxyAppClient.GetDsoParty
        )
      }
    }
    def getAnsRules(): ContractWithState[AnsRules.ContractId, AnsRules] = {
      consoleEnvironment.run {
        httpCommand(
          HttpScanProxyAppClient.GetAnsRules
        )
      }
    }
    def lookupTransferPreapprovalByParty(
        party: PartyId
    ): Option[ContractWithState[TransferPreapproval.ContractId, TransferPreapproval]] = {
      consoleEnvironment.run {
        httpCommand(
          HttpScanProxyAppClient.LookupTransferPreapprovalByParty(party)
        )
      }
    }

    def lookupTransferCommandCounterByParty(
        party: PartyId
    ): Option[ContractWithState[TransferCommandCounter.ContractId, TransferCommandCounter]] = {
      consoleEnvironment.run {
        httpCommand(
          HttpScanProxyAppClient.LookupTransferCommandCounterByParty(party)
        )
      }
    }

    def lookupTransferCommandStatus(
        sender: PartyId,
        nonce: Long,
    ): Option[LookupTransferCommandStatusResponse] = {
      consoleEnvironment.run {
        httpCommand(
          HttpScanProxyAppClient.LookupTransferCommandStatus(sender, nonce)
        )
      }
    }
  }
}

final class ValidatorAppBackendReference(
    override val consoleEnvironment: SpliceConsoleEnvironment,
    name: String,
)(implicit actorSystem: ActorSystem)
    extends ValidatorAppReference(consoleEnvironment, name)
    with AppBackendReference
    with BaseInspection[ValidatorApp] {

  override def runningNode: Option[ValidatorAppBootstrap] =
    consoleEnvironment.environment.validators.getRunning(name)

  override def startingNode: Option[ValidatorAppBootstrap] =
    consoleEnvironment.environment.validators.getStarting(name)

  override protected val instanceType = "Local Validator"

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

  val nodes = consoleEnvironment.environment.validators

  @Help.Summary(
    "Returns the state of this app. May only be called while the app is running."
  )
  def appState: ValidatorApp.State = _appState[ValidatorApp.State, ValidatorApp]

  @Help.Summary(
    "Returns the automation service for the wallet of the given user. May only be called while the app is running."
  )
  def userWalletAutomation(
      userName: String
  ): Future[UserWalletAutomationService] = {
    appState.walletManager
      .getOrElse(throw new RuntimeException(s"Wallet is disabled"))
      .lookupUserWallet(userName)
      .map(_.getOrElse(throw new RuntimeException(s"User ${userName} doesn't exist")).automation)(
        executionContext
      )
  }

  @Help.Summary(
    "Returns the automation service of this validator. May only be called while the app is running."
  )
  def validatorAutomation: ValidatorAutomationService = {
    appState.automation
  }

  @Help.Summary("Return local validator app config")
  override def config: ValidatorAppBackendConfig =
    consoleEnvironment.environment.config.validatorsByString(name)

  /** Remote participant this validator app is configured to interact with. */
  lazy val participantClient =
    new ParticipantClientReference(
      consoleEnvironment,
      s"remote participant for `$name`",
      config.participantClient.getParticipantClientConfig(),
    )

  /** Remote participant this validator app is configured to interact with. Uses admin tokens to bypass auth. */
  val participantClientWithAdminToken =
    new ParticipantClientReference(
      consoleEnvironment,
      s"remote participant for `$name`, with admin token",
      config.participantClient.participantClientConfigWithAdminToken,
    )
}

/** Client (aka remote) reference to a validator app in the style of ParticipantClientReference, i.e.,
  * it accepts the config as an argument rather than reading it from the global map.
  */
final case class ValidatorAppClientReference(
    override val consoleEnvironment: SpliceConsoleEnvironment,
    override val name: String,
    val config: ValidatorAppClientConfig,
    override val token: Option[String] = None,
) extends ValidatorAppReference(consoleEnvironment, name) {

  override def httpClientConfig = config.adminApi

  override protected val instanceType = "Validator Client"
}

final case class AppManagerAppClientReference(
    override val spliceConsoleEnvironment: SpliceConsoleEnvironment,
    override val name: String,
    val config: AppManagerAppClientConfig,
) extends HttpAppReference {

  override def basePath = "/api/validator"
  override def httpClientConfig = config.adminApi

  override def token: Option[String] = {
    Some(
      AuthUtil.testToken(
        audience = AuthUtil.testAudience,
        user = config.ledgerApiUser,
        secret = AuthUtil.testSecret,
      )
    )
  }

  def authorizeApp(
      provider: PartyId
  ): Unit =
    consoleEnvironment.run {
      httpCommand(
        HttpAppManagerAppClient.AuthorizeApp(provider)
      )
    }

  def checkAppAuthorized(
      provider: PartyId,
      redirectUri: String,
      state: String,
  ): String =
    consoleEnvironment.run {
      httpCommand(
        HttpAppManagerAppClient.CheckAppAuthorized(provider, redirectUri, state)
      )
    }

  override protected val instanceType = "App Manager User Client"
}
