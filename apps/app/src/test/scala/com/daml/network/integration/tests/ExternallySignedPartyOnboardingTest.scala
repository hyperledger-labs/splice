package com.daml.network.integration.tests

import com.daml.network.environment.EnvironmentImpl
import com.daml.network.http.v0.definitions.SignedTopologyTx
import com.daml.network.integration.EnvironmentDefinition
import com.daml.network.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.crypto.EncryptionAlgorithmSpec.{
  EciesHkdfHmacSha256Aes128Cbc,
  RsaOaepSha256,
}
import com.digitalasset.canton.crypto.HashAlgorithm.Sha256
import com.digitalasset.canton.crypto.PbkdfScheme.Argon2idMode1
import com.digitalasset.canton.crypto.SymmetricKeyScheme.Aes128Gcm
import com.digitalasset.canton.crypto.provider.jce.JcePureCrypto
import com.digitalasset.canton.crypto.*
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.util.HexString
import com.digitalasset.canton.version.ProtocolVersion

import java.util.{Base64, UUID}

class ExternallySignedPartyOnboardingTest extends IntegrationTest with HasExecutionContext {

  override def environmentDefinition
      : BaseEnvironmentDefinition[EnvironmentImpl, SpliceTestConsoleEnvironment] = {
    EnvironmentDefinition.simpleTopology1Sv(this.getClass.getSimpleName)
  }

  def sign(
      hash: Hash,
      signingKey: SigningPrivateKey,
      crypto: JcePureCrypto,
  ): Signature =
    crypto
      .sign(
        hash,
        signingKey,
      )
      .value

  "a ccsp provider" should {

    "should be able to onboard a party with externally signed topology transactions" in {
      implicit env =>
        val signingPublicKey =
          aliceValidatorBackend.participantClient.keys.secret
            .generate_signing_key(UUID.randomUUID().toString, Some(SigningKeyScheme.Ed25519))

        val signingKeyPairByteString = aliceValidatorBackend.participantClient.keys.secret
          .download(signingPublicKey.fingerprint, ProtocolVersion.dev)

        val privateKey =
          CryptoKeyPair.fromTrustedByteString(signingKeyPairByteString).value.privateKey

        val partyHint = UUID.randomUUID().toString
        val listOfTransactionsAndHashes = aliceValidatorBackend
          .createNamespaceDelegationAndPartyTxs(
            partyHint,
            HexString.toHexString(signingPublicKey.key),
          )
          .topologyTxs

        // All the parameters here don't matter as they are just the defaults while in our case the signing key defines the actual scheme that is used.
        val crypto = new JcePureCrypto(
          Aes128Gcm,
          EciesHkdfHmacSha256Aes128Cbc,
          NonEmpty.mk(Set, RsaOaepSha256),
          Sha256,
          Argon2idMode1,
          loggerFactory,
        )

        val signedTopologyTxs = listOfTransactionsAndHashes.map { tx =>
          SignedTopologyTx(
            tx.topologyTx,
            Base64.getEncoder.encodeToString(
              sign(
                hash = Hash.fromHexString(tx.hash).value,
                signingKey = privateKey.asInstanceOf[SigningPrivateKey],
                crypto = crypto,
              ).signature.toByteArray
            ),
          )
        }

        aliceValidatorBackend.submitNameDelegationAndPartyTxs(
          partyHint,
          signedTopologyTxs,
          HexString.toHexString(signingPublicKey.key),
        )

        eventually() {
          aliceValidatorBackend.participantClient.parties
            .hosted(filterParty = partyHint) should not be empty
        }

    }
  }

}
