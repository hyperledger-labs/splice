package org.lfdecentralizedtrust.splice.integration.tests

import cats.syntax.either.*
import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.daml.metrics.api.{MetricName, MetricsContext, HistogramInventory}
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.config.{CachingConfigs, CryptoProvider}
import com.digitalasset.canton.crypto.*
import com.digitalasset.canton.crypto.provider.jce.JcePureCrypto
import com.digitalasset.canton.crypto.v30 as cryptoProto
import com.digitalasset.canton.metrics.{
  SigningHistograms,
  DecryptionMetrics,
  SigningMetrics,
  DecryptionHistograms,
}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.util.HexString
import com.digitalasset.canton.version.ProtocolVersion
import com.google.protobuf.ByteString
import org.bouncycastle.asn1.edec.EdECObjectIdentifiers
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.x509.{AlgorithmIdentifier, SubjectPublicKeyInfo}
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.{
  ExternalPartySetupProposal,
  TransferPreapproval,
}
import org.lfdecentralizedtrust.splice.console.{LedgerApiExtensions, ValidatorAppBackendReference}
import org.lfdecentralizedtrust.splice.http.v0.definitions.{
  PrepareAcceptExternalPartySetupProposalResponse,
  SignedTopologyTx,
}
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.TestCommon
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.SpliceTestConsoleEnvironment

import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

trait ExternallySignedPartyTestUtil extends TestCommon {

  def onboardExternalParty(
      validatorBackend: ValidatorAppBackendReference,
      partyHint: Option[String] = None,
  )(implicit env: SpliceTestConsoleEnvironment): OnboardingResult = {
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

    // delete the key from the participant to ensure that it won't be actually used there for anything
    validatorBackend.participantClient.keys.secret.delete(generatedKey.fingerprint, true)

    val keyPair =
      CryptoKeyPair.fromTrustedByteString(signingKeyPairByteString).value

    submitTopologyAndOnboard(
      validatorBackend,
      truePartyHint,
      keyPair,
      PartyId.tryCreate(truePartyHint, generatedKey.fingerprint),
    )
  }

  def submitTopologyAndOnboard(
      validatorBackend: ValidatorAppBackendReference,
      partyHint: String,
      keyPair: CryptoKeyPair[PublicKey, PrivateKey],
      partyId: PartyId,
  )(implicit env: SpliceTestConsoleEnvironment): OnboardingResult = {
    val privateKey = keyPair.privateKey
    val subjectPublicKeyInfo = extractSubjectPublicKeyInfoFrom(keyPair)

    val listOfTransactionsAndHashes = validatorBackend
      .generateExternalPartyTopology(
        partyHint,
        publicKeyAsHexString(subjectPublicKeyInfo),
      )
      .topologyTxs

    val signedTopologyTxs = listOfTransactionsAndHashes.map { tx =>
      SignedTopologyTx(
        tx.topologyTx,
        HexString.toHexString(
          crypto(env.executionContext)
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
      partyId,
      keyPair.publicKey.asInstanceOf[SigningPublicKey],
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
        publicKey.toProtoPublicKeyV30
          .valueOr(err => throw new RuntimeException(s"failed to convert public key: $err"))
          .getSigningPublicKey
          .publicKey
          .toByteArray
      )
  }

  private[this] val noOpMetricsFactory = new NoOpMetricsFactory
  private[this] val histogramInventory = new HistogramInventory()
  private[this] implicit val metricsContext: MetricsContext = new MetricsContext(Map.empty)

  private[this] val signingMetrics = new SigningMetrics(
    new SigningHistograms(MetricName("splice-test"))(histogramInventory),
    noOpMetricsFactory,
  )(metricsContext)

  private[this] val decryptionMetrics = new DecryptionMetrics(
    new DecryptionHistograms(MetricName("splice-test"))(histogramInventory),
    noOpMetricsFactory,
  )(metricsContext)

  // The parameters here are just defaults so don't really matter
  def crypto(implicit ec: ExecutionContext) = new JcePureCrypto(
    defaultSymmetricKeyScheme = CryptoProvider.Jce.symmetric.default,
    signingAlgorithmSpecs = CryptoScheme
      .create(
        CryptoProvider.Jce.signingAlgorithms.default,
        CryptoProvider.Jce.signingAlgorithms.supported,
      )
      .value,
    encryptionAlgorithmSpecs = CryptoScheme
      .create(
        CryptoProvider.Jce.encryptionAlgorithms.default,
        CryptoProvider.Jce.encryptionAlgorithms.supported,
      )
      .value,
    defaultHashAlgorithm = CryptoProvider.Jce.hash.default,
    defaultPbkdfScheme = CryptoProvider.Jce.pbkdf.value.default,
    publicKeyConversionCacheConfig = CachingConfigs.defaultPublicKeyConversionCache,
    privateKeyConversionCacheTtl = None,
    signatureVerificationParallelism = PositiveInt.one,
    encryptionParallelism = PositiveInt.one,
    signingMetrics = signingMetrics,
    decryptionMetrics = decryptionMetrics,
    loggerFactory = loggerFactory,
  )

  case class OnboardingResult(
      party: PartyId,
      publicKey: SigningPublicKey,
      privateKey: PrivateKey,
  ) {
    def richPartyId(implicit env: SpliceTestConsoleEnvironment): LedgerApiExtensions.RichPartyId =
      LedgerApiExtensions.RichPartyId.external(
        party,
        privateKey.asInstanceOf[SigningPrivateKey],
        crypto(env.executionContext),
      )
  }

  case class ExternalPartySetupResult(
      transferPreapprovalCid: TransferPreapproval.ContractId,
      updateId: String,
      txHash: String,
  )

  protected def createAndAcceptExternalPartySetupProposal(
      provider: ValidatorAppBackendReference,
      externalPartyOnboarding: OnboardingResult,
      verboseHashing: Boolean = false,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ): ExternalPartySetupResult = {
    val proposal = createExternalPartySetupProposal(provider, externalPartyOnboarding)
    acceptExternalPartySetupProposal(provider, externalPartyOnboarding, proposal, verboseHashing)
  }

  protected def onboardAndSetupExternalParty(
      validatorBackend: ValidatorAppBackendReference,
      partyHint: Option[String] = None,
  )(implicit env: SpliceTestConsoleEnvironment): OnboardingResult = {
    val onboarding = onboardExternalParty(validatorBackend, partyHint)
    eventuallySucceeds() {
      // While there is a server-side retry on this, it is not always sufficiently long in our tests,
      // so we wrap it here in an eventuallySucceeds()
      try {
        createAndAcceptExternalPartySetupProposal(validatorBackend, onboarding)
      } catch {
        case NonFatal(_) =>
          // if this check passes, we're done, stop retrying
          checkExternalPartyExists(validatorBackend, onboarding.party)
      }
    }
    onboarding
  }

  protected def createExternalPartySetupProposal(
      provider: ValidatorAppBackendReference,
      externalPartyOnboarding: OnboardingResult,
  ): ExternalPartySetupProposal.ContractId = {
    provider
      .listExternalPartySetupProposals()
      .find(_.payload.user == externalPartyOnboarding.party.toProtoPrimitive) match {
      case Some(proposal) => proposal.contractId // this will happen on retries
      case None =>
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
  }

  protected def acceptExternalPartySetupProposal(
      provider: ValidatorAppBackendReference,
      externalPartyOnboarding: OnboardingResult,
      proposal: ExternalPartySetupProposal.ContractId,
      verboseHashing: Boolean = false,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ): ExternalPartySetupResult = {
    val preparedTx =
      prepareAcceptExternalPartySetupProposal(
        provider,
        externalPartyOnboarding,
        proposal,
        verboseHashing,
      )
    val (cid, updateId) =
      submitExternalPartySetupProposal(provider, externalPartyOnboarding, preparedTx)
    ExternalPartySetupResult(cid, updateId, preparedTx.txHash)
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
        prepare.txHash should not be empty withClue "txHash"
        prepare.transaction should not be empty withClue "transaction"
        (if (verboseHashing)
           prepare.hashingDetails should not be empty
         else
           prepare.hashingDetails shouldBe empty) withClue "hashingDetails"
      },
    )
    prepare
  }

  protected def submitExternalPartySetupProposal(
      provider: ValidatorAppBackendReference,
      externalPartyOnboarding: OnboardingResult,
      preparedTx: PrepareAcceptExternalPartySetupProposalResponse,
  )(implicit env: SpliceTestConsoleEnvironment): (TransferPreapproval.ContractId, String) = {
    val (_, result) = actAndCheck(
      s"Submit acceptExternalPartySetupProposal tx for ${externalPartyOnboarding.party}",
      provider.submitAcceptExternalPartySetupProposal(
        externalPartyOnboarding.party,
        preparedTx.transaction,
        HexString.toHexString(
          crypto(env.executionContext)
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
        transferPreapprovalCid.contractId should not be empty withClue "TransferPreapproval contractId"
        updateId should not be empty withClue "AcceptExternalPartySetupProposal updateId"
        checkExternalPartyExists(provider, externalPartyOnboarding.party)
        submitResult
      },
    )
    result
  }

  private def checkExternalPartyExists(
      provider: ValidatorAppBackendReference,
      externalParty: PartyId,
  ) = {
    provider.lookupTransferPreapprovalByParty(
      externalParty
    ) should not be empty withClue s"TransferPreapproval for $externalParty"
    provider.scanProxy.lookupTransferPreapprovalByParty(
      externalParty
    ) should not be empty withClue s"TransferPreapproval for $externalParty via scan-proxy"
  }

  /** Pre-generate an Ed25519 key pair and compute the external party's ID
    * without a running participant. This is needed because external party IDs
    * depend on cryptographic key fingerprints that are normally only known at
    * runtime, but some test configs (e.g. `rewardSharingConfigByParty`) require
    * the party ID at config transform time — before participants start.
    *
    * The key generation, DER encoding, and fingerprint computation replicate
    * Canton's `JcePrivateCrypto` logic using BouncyCastle directly, since
    * Canton's key generation APIs are `private[crypto]`. Specifically:
    * - Fingerprint: `Fingerprint.create` = SHA-256 of raw public key bytes
    *   (see `CryptoKeys.scala` and `Signing.scala:getDataForFingerprint`)
    * - Private key: PKCS#8 DER (see `JcePrivateCrypto.encodeEd25519PrivateKey`)
    * - Key pair proto: `v30.SigningKeyPair` (see `SigningKeyPair.fromProtoV30`)
    *
    * Use the returned [[PreGeneratedParty]] in config transforms to reference
    * the party ID, then call [[onboardExternalParty]] with it at test time.
    */
  def preGenerateExternalParty(partyHint: String): PreGeneratedParty = {
    val edPriv = new Ed25519PrivateKeyParameters(new java.security.SecureRandom())
    val edPub = edPriv.generatePublicKey()
    val ed25519Algo = new AlgorithmIdentifier(EdECObjectIdentifiers.id_Ed25519)

    // For Ed25519 + DER SPKI, Canton computes the fingerprint from the raw public
    // key bytes (not the full SPKI DER), for backward compatibility.
    val rawPublicKeyBytes = ByteString.copyFrom(edPub.getEncoded)
    val hash =
      Hash.digest(HashPurpose.PublicKeyFingerprint, rawPublicKeyBytes, HashAlgorithm.Sha256)
    val fingerprint = Fingerprint.tryFromString(hash.toLengthLimitedHexString.unwrap)

    // Encode private key as PKCS#8 DER
    val rawPrivateKeyBytes = edPriv.getEncoded
    val pkcs8Der = ByteString.copyFrom(
      new PrivateKeyInfo(ed25519Algo, new DEROctetString(rawPrivateKeyBytes)).getEncoded
    )

    // Build a serialized CryptoKeyPair protobuf that can be uploaded to a participant
    val keyPairBytes = {
      val signingPrivateKeyProto = cryptoProto.SigningPrivateKey(
        id = fingerprint.unwrap,
        format = cryptoProto.CryptoKeyFormat.CRYPTO_KEY_FORMAT_DER_PKCS8_PRIVATE_KEY_INFO,
        privateKey = pkcs8Der,
        scheme = cryptoProto.SigningKeyScheme.SIGNING_KEY_SCHEME_UNSPECIFIED,
        usage = Seq(
          cryptoProto.SigningKeyUsage.SIGNING_KEY_USAGE_NAMESPACE,
          cryptoProto.SigningKeyUsage.SIGNING_KEY_USAGE_SEQUENCER_AUTHENTICATION,
          cryptoProto.SigningKeyUsage.SIGNING_KEY_USAGE_PROTOCOL,
          cryptoProto.SigningKeyUsage.SIGNING_KEY_USAGE_PROOF_OF_OWNERSHIP,
        ),
        keySpec = cryptoProto.SigningKeySpec.SIGNING_KEY_SPEC_EC_CURVE25519,
      )
      val signingKeyPairProto = cryptoProto.SigningKeyPair(
        privateKey = Some(signingPrivateKeyProto)
      )
      val cryptoKeyPairProto = cryptoProto.CryptoKeyPair(
        pair = cryptoProto.CryptoKeyPair.Pair.SigningKeyPair(signingKeyPairProto)
      )
      // Wrap in versioned envelope
      CryptoKeyPair
        .fromProtoCryptoKeyPairV30(cryptoKeyPairProto)
        .value
        .toByteString(ProtocolVersion.dev)
        .valueOr(err =>
          throw new RuntimeException(s"Failed to convert key pair to bytestring: $err")
        )
    }

    PreGeneratedParty(
      partyId = PartyId.tryCreate(partyHint, fingerprint),
      keyPairBytes = keyPairBytes,
    )
  }

  case class PreGeneratedParty(
      partyId: PartyId,
      keyPairBytes: ByteString,
  )

  /** Onboard an external party using a pre-generated key from [[preGenerateExternalParty]].
    * Imports the key pair into the participant, then follows the standard onboarding flow.
    */
  def onboardExternalParty(
      validatorBackend: ValidatorAppBackendReference,
      preGenerated: PreGeneratedParty,
  )(implicit env: SpliceTestConsoleEnvironment): OnboardingResult = {
    val truePartyHint = preGenerated.partyId.uid.identifier.unwrap

    // Import the pre-generated key pair into the participant
    validatorBackend.participantClient.keys.secret
      .upload(preGenerated.keyPairBytes, Some(truePartyHint))

    val fingerprint = preGenerated.partyId.fingerprint
    val signingKeyPairByteString = validatorBackend.participantClient.keys.secret
      .download(fingerprint, ProtocolVersion.dev)

    // Delete from participant — same as standard onboarding
    validatorBackend.participantClient.keys.secret.delete(fingerprint, true)

    val keyPair = CryptoKeyPair.fromTrustedByteString(signingKeyPairByteString).value

    submitTopologyAndOnboard(validatorBackend, truePartyHint, keyPair, preGenerated.partyId)
  }
}
