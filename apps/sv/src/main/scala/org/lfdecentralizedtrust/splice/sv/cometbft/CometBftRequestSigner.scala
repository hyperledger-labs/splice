// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.cometbft

import com.digitalasset.canton.crypto.*
import com.digitalasset.canton.logging.TracedLogger
import com.digitalasset.canton.tracing.TraceContext
import com.google.crypto.tink.subtle.{Ed25519Sign, Ed25519Verify}
import com.google.protobuf.ByteString
import io.grpc.Status
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.lfdecentralizedtrust.splice.environment.ParticipantAdminConnection
import scalapb.GeneratedMessage

import java.util.Base64
import scala.concurrent.{ExecutionContext, Future}

case class CometBftRequestSigner(
    publicKeyBase64: String,
    privateKeyBase64: String,
) {

  private val privateKeyBytes = Base64.getDecoder.decode(privateKeyBase64)
  private val privateKey = new Ed25519Sign(privateKeyBytes)

  val pubKeyBytes: Array[Byte] = Base64.getDecoder.decode(publicKeyBase64)
  val pubKey = new Ed25519Verify(pubKeyBytes)

  val fingerprint: String =
    CometBftRequestSigner.fingerprintForBase64PublicKey(publicKeyBase64)

  def signRequest(request: GeneratedMessage): Array[Byte] = {
    val requestBytes = request.toByteArray
    privateKey.sign(requestBytes)
  }
}

object CometBftRequestSigner {

  private val genesisPublicKeyBase64 = "m16haLzv/d/Ok04Sm39ABk0f0HsSWYNZxrIUiyQ+cK8="
  private val genesisPrivateKeyBase64 = "+7VcQfNKGpd/LnjhA1+LQ13xWQLV2A44P8mbpnTy/YQ="

  def genesisSigner =
    new CometBftRequestSigner(genesisPublicKeyBase64, genesisPrivateKeyBase64)

  def getOrGenerateSignerFromParticipant(
      name: String,
      participantAdminConnection: ParticipantAdminConnection,
      logger: TracedLogger,
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[CometBftRequestSigner] = {
    for {
      keysMetadata <- participantAdminConnection.listMyKeys(name)
      fingerprint <-
        if (keysMetadata.isEmpty) {
          for {
            // None of the key usages really fit so we somewhat randomly pick ProtocolOnly which
            // seems better than All at least.
            keypair <- participantAdminConnection.generateKeyPair(
              name,
              SigningKeyUsage.ProtocolOnly,
            )
          } yield {
            logger.info(s"Generating new $name keys with fingerprint ${keypair.id}.")
            keypair.id
          }
        } else {
          val fingerprint = keysMetadata.headOption
            .getOrElse(
              throw Status.NOT_FOUND
                .withDescription(
                  s"Fingerprint for public key $name could not be found."
                )
                .asRuntimeException()
            )
            .id
          logger.info(s"Using existing $name keys with fingerprint $fingerprint.")
          Future.successful(fingerprint)
        }
      keyBytes <- participantAdminConnection.exportKeyPair(fingerprint)
    } yield keyBytes match {
      case keyBytes: ByteString =>
        val keyPair =
          CryptoKeyPair
            .fromTrustedByteString(keyBytes)
            .getOrElse(
              throw Status.NOT_FOUND
                .withDescription(
                  s"Key pair $name could not be parsed."
                )
                .asRuntimeException()
            )
        val pubKey = SubjectPublicKeyInfo.getInstance(
          keyPair.publicKey.toProtoPublicKeyV30.getSigningPublicKey.publicKey.toByteArray
        )
        val privateKey = PrivateKeyInfo.getInstance(
          keyPair.privateKey.toProtoPrivateKey.getSigningPrivateKey.privateKey.toByteArray
        )
        val privateKeyData =
          ASN1OctetString.getInstance(privateKey.getPrivateKey.getOctets).getOctets
        new CometBftRequestSigner(
          Base64.getEncoder.encodeToString(pubKey.getPublicKeyData.getBytes),
          Base64.getEncoder.encodeToString(privateKeyData),
        )
      case _ =>
        throw Status.NOT_FOUND
          .withDescription(
            s"Could not export KeyPair $name from Canton KMS"
          )
          .asRuntimeException()
    }

  }

  def fingerprintForBase64PublicKey(publicKey: String): String = {
    val decodedKey: Array[Byte] = Base64.getDecoder.decode(publicKey)
    fingerprintForPublicKey(decodedKey)
  }

  private def fingerprintForPublicKey(publicKey: Array[Byte]) = {
    val hash = Hash.digest(
      HashPurpose.PublicKeyFingerprint,
      ByteString.copyFrom(publicKey),
      HashAlgorithm.Sha256,
    )
    val fingerprint = Fingerprint.tryFromString(hash.toLengthLimitedHexString)
    fingerprint.toLengthLimitedString.toString()
  }

}
