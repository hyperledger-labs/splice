package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.config.CryptoProvider
import com.digitalasset.canton.crypto.*
import com.digitalasset.canton.crypto.provider.jce.JcePureCrypto
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.util.HexString
import com.digitalasset.canton.version.ProtocolVersion
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
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

import java.util.UUID

trait ExternallySignedPartyTestUtil extends TestCommon {

  def onboardExternalParty(
      validatorBackend: ValidatorAppBackendReference,
      partyHint: Option[String] = None,
  ): OnboardingResult = {
    val generatedKey: SigningPublicKey =
      validatorBackend.participantClient.keys.secret
        .generate_signing_key(
          UUID.randomUUID().toString,
          SigningKeyUsage.All,
          Some(SigningKeySpec.EcCurve25519),
        )
    val truePartyHint = partyHint.getOrElse(UUID.randomUUID().toString)
    val signingKeyPairByteString = validatorBackend.participantClient.keys.secret
      .download(generatedKey.fingerprint, ProtocolVersion.dev)
    val keyPair =
      CryptoKeyPair.fromTrustedByteString(signingKeyPairByteString).value
    val privateKey = keyPair.privateKey
    val subjectPublicKeyInfo = extractSubjectPublicKeyInfoFrom(keyPair)

    val listOfTransactionsAndHashes = validatorBackend
      .generateExternalPartyTopology(
        truePartyHint,
        publicKeyAsHexString(subjectPublicKeyInfo),
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
              usage = SigningKeyUsage.ProtocolOnly,
            )
            .value
            .toProtoV30
            .signature
        ),
      )
    }

    validatorBackend.submitExternalPartyTopology(
      signedTopologyTxs,
      publicKeyAsHexString(subjectPublicKeyInfo),
    )

    OnboardingResult(
      PartyId.tryCreate(truePartyHint, generatedKey.fingerprint),
      generatedKey,
      privateKey,
    )
  }

  def publicKeyAsHexString(keyPair: CryptoKeyPair[PublicKey, PrivateKey]): String = {
    publicKeyAsHexString(extractSubjectPublicKeyInfoFrom(keyPair))
  }

  def publicKeyAsHexString(publicKey: PublicKey): String = {
    publicKeyAsHexString(extractSubjectPublicKeyInfoFrom(publicKey))
  }

  def publicKeyAsHexString(subjectPublicKeyInfo: SubjectPublicKeyInfo): String = {
    HexString.toHexString(subjectPublicKeyInfo.getPublicKeyData.getBytes)
  }

  def extractSubjectPublicKeyInfoFrom(
      keyPair: CryptoKeyPair[PublicKey, PrivateKey]
  ): SubjectPublicKeyInfo = {
    extractSubjectPublicKeyInfoFrom(keyPair.publicKey)
  }

  def extractSubjectPublicKeyInfoFrom(
      publicKey: PublicKey
  ): SubjectPublicKeyInfo = {
    SubjectPublicKeyInfo
      .getInstance(
        publicKey.toProtoPublicKeyV30.getSigningPublicKey.publicKey.toByteArray
      )
  }

  // The parameters here are just defaults so don't really matter
  val crypto = new JcePureCrypto(
    CryptoProvider.Jce.symmetric.default,
    CryptoProvider.Jce.signingAlgorithms.default,
    CryptoProvider.Jce.signingAlgorithms.supported,
    CryptoProvider.Jce.encryptionAlgorithms.default,
    CryptoProvider.Jce.encryptionAlgorithms.supported,
    CryptoProvider.Jce.hash.default,
    CryptoProvider.Jce.pbkdf.value.default,
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
              usage = SigningKeyUsage.ProtocolOnly,
            )
            .value
            .toProtoV30
            .signature
        ),
        publicKeyAsHexString(externalPartyOnboarding.publicKey),
      ),
    )(
      s"acceptExternalPartySetupProposal tx for ${externalPartyOnboarding.party} submitted",
      submitResult => {
        val (transferPreapprovalCid, updateId) = submitResult
        transferPreapprovalCid.contractId should not be empty
        updateId should not be empty
        provider.lookupTransferPreapprovalByParty(externalPartyOnboarding.party) should not be empty
        provider.scanProxy.lookupTransferPreapprovalByParty(
          externalPartyOnboarding.party
        ) should not be empty
        submitResult
      },
    )
    result
  }
}
