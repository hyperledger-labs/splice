package com.daml.network.integration.tests

import com.daml.network.console.ValidatorAppBackendReference
import com.daml.network.http.v0.definitions.SignedTopologyTx
import com.daml.network.integration.tests.SpliceTests.TestCommon
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
        .generate_signing_key(UUID.randomUUID().toString, Some(SigningKeyScheme.Ed25519))
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
}
