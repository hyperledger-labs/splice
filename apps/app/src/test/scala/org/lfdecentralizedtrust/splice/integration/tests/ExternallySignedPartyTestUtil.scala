package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.{
  ExternalPartySetupProposal,
  TransferPreapproval,
}
import org.lfdecentralizedtrust.splice.console.ValidatorAppBackendReference
import org.lfdecentralizedtrust.splice.http.v0.definitions.{
  PrepareAcceptExternalPartySetupProposalResponse,
  SignedTopologyTx,
}
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.TestCommon
import com.digitalasset.canton.config.CommunityCryptoProvider
import com.digitalasset.canton.crypto.*
import com.digitalasset.canton.crypto.provider.jce.JcePureCrypto
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.util.HexString
import com.digitalasset.canton.version.ProtocolVersion

import java.util.UUID

trait ExternallySignedPartyTestUtil extends TestCommon {
  def onboardExternalParty(
      validatorBackend: ValidatorAppBackendReference,
      partyHint: String = UUID.randomUUID().toString,
  ): OnboardingResult = {
    val signingPublicKey =
      validatorBackend.participantClient.keys.secret
        .generate_signing_key(
          UUID.randomUUID().toString,
          SigningKeyUsage.All,
          Some(SigningKeySpec.EcCurve25519),
        )
    val signingKeyPairByteString = validatorBackend.participantClient.keys.secret
      .download(signingPublicKey.fingerprint, ProtocolVersion.dev)
    val privateKey =
      CryptoKeyPair.fromTrustedByteString(signingKeyPairByteString).value.privateKey
    val listOfTransactionsAndHashes = validatorBackend
      .generateExternalPartyTopology(
        partyHint,
        HexString.toHexString(signingPublicKey.key),
      )
      .topologyTxs
    val signedTopologyTxs = listOfTransactionsAndHashes.map { tx =>
      SignedTopologyTx(
        tx.topologyTx,
        HexString.toHexString(
          crypto
            .sign(
              hash = Hash.fromHexString(tx.hash).value,
              signingKey = privateKey.asInstanceOf[SigningPrivateKey],
            )
            .value
            .signature
        ),
      )
    }

    validatorBackend.submitExternalPartyTopology(
      signedTopologyTxs,
      HexString.toHexString(signingPublicKey.key),
    )

    OnboardingResult(
      PartyId.tryCreate(partyHint, signingPublicKey.fingerprint),
      signingPublicKey,
      privateKey,
    )
  }

  // The parameters here are just defaults so don't really matter
  val crypto = new JcePureCrypto(
    CommunityCryptoProvider.Jce.symmetric.default,
    CommunityCryptoProvider.Jce.signingAlgorithms.default,
    CommunityCryptoProvider.Jce.signingAlgorithms.supported,
    CommunityCryptoProvider.Jce.encryptionAlgorithms.default,
    CommunityCryptoProvider.Jce.encryptionAlgorithms.supported,
    CommunityCryptoProvider.Jce.hash.default,
    CommunityCryptoProvider.Jce.pbkdf.value.default,
    loggerFactory,
  )

  case class OnboardingResult(
      party: PartyId,
      publicKey: SigningPublicKey,
      privateKey: PrivateKey,
  )

  protected def createAndAcceptExternalPartySetupProposal(
      provider: ValidatorAppBackendReference,
      externalPartyOnboarding: OnboardingResult,
      verboseHashing: Boolean = false,
  ): (TransferPreapproval.ContractId, String) = {
    val proposal = createExternalPartySetupProposal(provider, externalPartyOnboarding)
    acceptExternalPartySetupProposal(provider, externalPartyOnboarding, proposal, verboseHashing)
  }

  protected def createExternalPartySetupProposal(
      provider: ValidatorAppBackendReference,
      externalPartyOnboarding: OnboardingResult,
  ): ExternalPartySetupProposal.ContractId = {
    val (proposal, _) = actAndCheck(
      s"Create external party proposal for ${externalPartyOnboarding.party}", {
        provider.createExternalPartySetupProposal(externalPartyOnboarding.party)
      },
    )(
      s"External party proposal for ${externalPartyOnboarding.party} was created",
      proposal => {
        provider
          .listExternalPartySetupProposals()
          .map(_.contract.contractId.contractId) should contain(proposal.contractId)
      },
    )
    proposal
  }

  protected def acceptExternalPartySetupProposal(
      provider: ValidatorAppBackendReference,
      externalPartyOnboarding: OnboardingResult,
      proposal: ExternalPartySetupProposal.ContractId,
      verboseHashing: Boolean = false,
  ): (TransferPreapproval.ContractId, String) = {
    val preparedTx =
      prepareAcceptExternalPartySetupProposal(
        provider,
        externalPartyOnboarding,
        proposal,
        verboseHashing,
      )
    submitExternalPartySetupProposal(provider, externalPartyOnboarding, preparedTx)
  }

  protected def prepareAcceptExternalPartySetupProposal(
      provider: ValidatorAppBackendReference,
      externalPartyOnboarding: OnboardingResult,
      proposal: ExternalPartySetupProposal.ContractId,
      verboseHashing: Boolean = false,
  ): PrepareAcceptExternalPartySetupProposalResponse = {
    val (prepare, _) = actAndCheck(
      s"Prepare acceptExternalPartySetupProposal tx for ${externalPartyOnboarding.party}",
      provider.prepareAcceptExternalPartySetupProposal(
        proposal,
        externalPartyOnboarding.party,
        verboseHashing,
      ),
    )(
      s"acceptExternalPartySetupProposal tx for ${externalPartyOnboarding.party} prepared",
      prepare => {
        prepare.txHash should not be empty
        prepare.transaction should not be empty
        if (verboseHashing)
          prepare.hashingDetails should not be empty
        else
          prepare.hashingDetails shouldBe empty
      },
    )
    prepare
  }

  protected def submitExternalPartySetupProposal(
      provider: ValidatorAppBackendReference,
      externalPartyOnboarding: OnboardingResult,
      preparedTx: PrepareAcceptExternalPartySetupProposalResponse,
  ): (TransferPreapproval.ContractId, String) = {
    val (_, result) = actAndCheck(
      s"Submit acceptExternalPartySetupProposal tx for ${externalPartyOnboarding.party}",
      provider.submitAcceptExternalPartySetupProposal(
        externalPartyOnboarding.party,
        preparedTx.transaction,
        HexString.toHexString(
          crypto
            .signBytes(
              HexString.parseToByteString(preparedTx.txHash).value,
              externalPartyOnboarding.privateKey.asInstanceOf[SigningPrivateKey],
            )
            .value
            .signature
        ),
        HexString.toHexString(externalPartyOnboarding.publicKey.key),
      ),
    )(
      s"acceptExternalPartySetupProposal tx for ${externalPartyOnboarding.party} submitted",
      submitResult => {
        val (transferPreapprovalCid, updateId) = submitResult
        transferPreapprovalCid.contractId should not be empty
        updateId should not be empty
        provider.lookupTransferPreapprovalByParty(externalPartyOnboarding.party) should not be empty
        submitResult
      },
    )
    result
  }
}
