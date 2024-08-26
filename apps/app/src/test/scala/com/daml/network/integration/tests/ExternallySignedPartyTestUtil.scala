package com.daml.network.integration.tests

import com.daml.network.console.ValidatorAppBackendReference
import com.daml.network.http.v0.definitions.SignedTopologyTx
import com.daml.network.integration.tests.SpliceTests.TestCommon
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.crypto.*
import com.digitalasset.canton.crypto.EncryptionAlgorithmSpec.{
  EciesHkdfHmacSha256Aes128Cbc,
  RsaOaepSha256,
}
import com.digitalasset.canton.crypto.HashAlgorithm.Sha256
import com.digitalasset.canton.crypto.PbkdfScheme.Argon2idMode1
import com.digitalasset.canton.crypto.SymmetricKeyScheme.Aes128Gcm
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

    validatorBackend.submitNameDelegationAndPartyTxs(
      partyHint,
      signedTopologyTxs,
      HexString.toHexString(signingPublicKey.key),
    )

    OnboardingResult(
      PartyId.tryCreate(partyHint, signingPublicKey.fingerprint),
      signingPublicKey,
      privateKey,
    )
  }

  // All the parameters here don't matter as they are just the defaults while in our case the signing key defines the actual scheme that is used.
  val crypto = new JcePureCrypto(
    Aes128Gcm,
    EciesHkdfHmacSha256Aes128Cbc,
    NonEmpty.mk(Set, RsaOaepSha256),
    Sha256,
    Argon2idMode1,
    loggerFactory,
  )

  case class OnboardingResult(
      party: PartyId,
      publicKey: SigningPublicKey,
      privateKey: PrivateKey,
  )
}
