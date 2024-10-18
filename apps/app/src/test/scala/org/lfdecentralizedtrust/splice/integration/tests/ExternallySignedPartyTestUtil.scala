package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.{
  ExternalPartySetupProposal,
  TransferPreapproval,
}
import org.lfdecentralizedtrust.splice.console.ValidatorAppBackendReference
import org.lfdecentralizedtrust.splice.http.v0.definitions.SignedTopologyTx
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
      validatorBackend: ValidatorAppBackendReference
  ): OnboardingResult = {
    val signingPublicKey =
      validatorBackend.participantClient.keys.secret
        .generate_signing_key(
          UUID.randomUUID().toString,
          SigningKeyUsage.All,
          Some(SigningKeyScheme.Ed25519),
        )
    val signingKeyPairByteString = validatorBackend.participantClient.keys.secret
      .download(signingPublicKey.fingerprint, ProtocolVersion.dev)
    val privateKey =
      CryptoKeyPair.fromTrustedByteString(signingKeyPairByteString).value.privateKey
    val partyHint = UUID.randomUUID().toString
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
  ): (TransferPreapproval.ContractId, String) = {
    val proposal = provider.createExternalPartySetupProposal(externalPartyOnboarding.party)
    provider
      .listExternalPartySetupProposals()
      .map(c => c.contract.contractId.contractId) contains proposal.contractId
    acceptExternalPartySetupProposal(provider, externalPartyOnboarding, proposal)
  }

  protected def acceptExternalPartySetupProposal(
      provider: ValidatorAppBackendReference,
      externalPartyOnboarding: OnboardingResult,
      proposal: ExternalPartySetupProposal.ContractId,
  ): (TransferPreapproval.ContractId, String) = {
    val prepare =
      provider.prepareAcceptExternalPartySetupProposal(proposal, externalPartyOnboarding.party)
    prepare.txHash should not be empty
    prepare.transaction should not be empty

    provider.submitAcceptExternalPartySetupProposal(
      externalPartyOnboarding.party,
      prepare.transaction,
      HexString.toHexString(
        crypto
          .sign(
            Hash.fromByteString(HexString.parseToByteString(prepare.txHash).value).value,
            externalPartyOnboarding.privateKey.asInstanceOf[SigningPrivateKey],
          )
          .value
          .signature
      ),
      HexString.toHexString(externalPartyOnboarding.publicKey.key),
    )
  }
}
