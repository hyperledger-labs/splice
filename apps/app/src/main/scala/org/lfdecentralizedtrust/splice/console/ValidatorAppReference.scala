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
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient
import org.lfdecentralizedtrust.splice.util.{
  ChoiceContextWithDisclosures,
  ContractWithState,
  FactoryChoiceWithDisclosures,
}
import org.lfdecentralizedtrust.splice.validator.admin.api.client.commands.*
import org.lfdecentralizedtrust.splice.validator.automation.ValidatorAutomationService
import org.lfdecentralizedtrust.splice.validator.config.{
  ValidatorAppBackendConfig,
  ValidatorAppClientConfig,
}
import org.lfdecentralizedtrust.splice.validator.migration.DomainMigrationDump
import org.lfdecentralizedtrust.splice.validator.{ValidatorApp, ValidatorAppBootstrap}
import org.lfdecentralizedtrust.splice.wallet.automation.UserWalletAutomationService
import org.lfdecentralizedtrust.tokenstandard.{metadata, transferinstruction}
import com.digitalasset.canton.console.{BaseInspection, Help}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.PartyId
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.TransferPreapproval
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.{
  allocationinstructionv1,
  allocationv1,
  transferinstructionv1,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.UnclaimedDevelopmentFundCoupon
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
    """Create a namespace delegation and party transaction. Return the topology transaction and transaction authorization hash (this should be signed by CCSP)."""
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
  @Help.Description(
    """Onboard individual canton-amulet user with a fresh or existing party-id. Return the user's partyId."""
  )
  def onboardUser(
      user: String,
      existingPartyId: Option[PartyId] = None,
      createIfMissing: Option[Boolean] = None,
  ): PartyId = {
    consoleEnvironment.run {
      httpCommand(
        HttpValidatorAdminAppClient.OnboardUser(user, existingPartyId, createIfMissing)
      )
    }
  }

  @Help.Summary("Register a new user identified by token")
  @Help.Description(
    """Register the authenticated canton-amulet user with a fresh party-id. Return the newly set up partyId."""
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
      verboseHashing: Boolean = false,
  ): definitions.PrepareAcceptExternalPartySetupProposalResponse = {
    consoleEnvironment.run {
      httpCommand(
        HttpValidatorAdminAppClient.PrepareAcceptExternalPartySetupProposal(
          contractId,
          userPartyId,
          verboseHashing,
        )
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
      description: Option[String],
      verboseHashing: Boolean = false,
  ): definitions.PrepareTransferPreapprovalSendResponse = {
    consoleEnvironment.run {
      httpCommand(
        HttpValidatorAdminAppClient.PrepareTransferPreapprovalSend(
          senderPartyId,
          receiverPartyId,
          amount,
          expiresAt,
          nonce,
          description,
          verboseHashing,
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

  object scanProxy {

    def getDsoParty(): PartyId = {
      consoleEnvironment.run {
        httpCommand(
          HttpScanProxyAppClient.GetDsoParty
        )
      }
    }

    def getHoldingsSummaryAt(
        at: CantonTimestamp,
        migrationId: Long,
        ownerPartyIds: Vector[PartyId],
        recordTimeMatch: Option[definitions.HoldingsSummaryRequest.RecordTimeMatch] = None,
        asOfRound: Option[Long] = None,
    ): Option[definitions.HoldingsSummaryResponse] = {
      consoleEnvironment.run {
        httpCommand(
          HttpScanProxyAppClient.GetHoldingsSummaryAt(
            at,
            migrationId,
            ownerPartyIds,
            recordTimeMatch,
            asOfRound,
          )
        )
      }
    }

    def getDsoInfo(): definitions.GetDsoInfoResponse = {
      consoleEnvironment.run {
        httpCommand(
          HttpScanProxyAppClient.GetDsoInfo
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

    def listUnclaimedDevelopmentFundCoupons(): Seq[
      ContractWithState[UnclaimedDevelopmentFundCoupon.ContractId, UnclaimedDevelopmentFundCoupon]
    ] = {
      consoleEnvironment.run {
        httpCommand(
          HttpScanProxyAppClient.ListUnclaimedDevelopmentFundCoupons
        )
      }
    }

    private val scanProxyPrefix = "/api/validator/v0/scan-proxy"

    def getRegistryInfo(): metadata.v1.definitions.GetRegistryInfoResponse = {
      consoleEnvironment.run {
        httpCommand(
          HttpScanAppClient.GetRegistryInfo,
          Some(scanProxyPrefix),
        )
      }
    }

    def lookupInstrument(instrumentId: String) =
      consoleEnvironment.run {
        httpCommand(HttpScanAppClient.LookupInstrument(instrumentId), Some(scanProxyPrefix))
      }

    def listInstruments() =
      consoleEnvironment.run {
        httpCommand(
          HttpScanAppClient.ListInstruments(pageSize = None, pageToken = None),
          Some(scanProxyPrefix),
        )
      }

    def getTransferFactory(
        choiceArgs: transferinstructionv1.TransferFactory_Transfer
    ): (
        FactoryChoiceWithDisclosures[
          transferinstructionv1.TransferFactory.ContractId,
          transferinstructionv1.TransferFactory_Transfer,
        ],
        transferinstruction.v1.definitions.TransferFactoryWithChoiceContext.TransferKind,
    ) = {
      consoleEnvironment.run {
        httpCommand(HttpScanAppClient.GetTransferFactory(choiceArgs), Some(scanProxyPrefix))
      }
    }

    def getTransferInstructionAcceptContext(
        transferInstructionId: transferinstructionv1.TransferInstruction.ContractId
    ): ChoiceContextWithDisclosures = {
      consoleEnvironment.run {
        httpCommand(
          HttpScanAppClient.GetTransferInstructionAcceptContext(transferInstructionId),
          Some(scanProxyPrefix),
        )
      }
    }

    def getTransferInstructionRejectContext(
        transferInstructionId: transferinstructionv1.TransferInstruction.ContractId
    ): ChoiceContextWithDisclosures = {
      consoleEnvironment.run {
        httpCommand(
          HttpScanAppClient.GetTransferInstructionRejectContext(transferInstructionId),
          Some(scanProxyPrefix),
        )
      }
    }

    def getTransferInstructionWithdrawContext(
        transferInstructionId: transferinstructionv1.TransferInstruction.ContractId
    ): ChoiceContextWithDisclosures = {
      consoleEnvironment.run {
        httpCommand(
          HttpScanAppClient.GetTransferInstructionWithdrawContext(transferInstructionId),
          Some(scanProxyPrefix),
        )
      }
    }

    def getAllocationFactory(
        choiceArgs: allocationinstructionv1.AllocationFactory_Allocate
    ): FactoryChoiceWithDisclosures[
      allocationinstructionv1.AllocationFactory.ContractId,
      allocationinstructionv1.AllocationFactory_Allocate,
    ] = {
      consoleEnvironment.run {
        httpCommand(HttpScanAppClient.GetAllocationFactory(choiceArgs), Some(scanProxyPrefix))
      }
    }

    def getAllocationTransferContext(
        allocationId: allocationv1.Allocation.ContractId
    ): ChoiceContextWithDisclosures = {
      consoleEnvironment.run {
        httpCommand(
          HttpScanAppClient.GetAllocationTransferContext(allocationId),
          Some(scanProxyPrefix),
        )
      }
    }

    def getAllocationCancelContext(
        allocationId: allocationv1.Allocation.ContractId
    ): ChoiceContextWithDisclosures = {
      consoleEnvironment.run {
        httpCommand(
          HttpScanAppClient.GetAllocationCancelContext(allocationId),
          Some(scanProxyPrefix),
        )
      }
    }

    def getAllocationWithdrawContext(
        allocationId: allocationv1.Allocation.ContractId
    ): ChoiceContextWithDisclosures = {
      consoleEnvironment.run {
        httpCommand(
          HttpScanAppClient.GetAllocationWithdrawContext(allocationId),
          Some(scanProxyPrefix),
        )
      }
    }
  }
}

final class ValidatorAppBackendReference(
    override val consoleEnvironment: SpliceConsoleEnvironment,
    name: String,
) extends ValidatorAppReference(consoleEnvironment, name)
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

  val nodes: org.lfdecentralizedtrust.splice.environment.ValidatorApps =
    consoleEnvironment.environment.validators

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
