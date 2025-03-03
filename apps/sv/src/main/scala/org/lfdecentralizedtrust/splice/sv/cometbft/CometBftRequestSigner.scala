// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.cometbft

import com.digitalasset.canton.crypto.*
import com.digitalasset.canton.logging.TracedLogger
import com.digitalasset.canton.serialization.ProtoConverter.ParsingResult
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

class CometBftRequestSigner(
    publicKey: Array[Byte],
    privateKey: Array[Byte],
) {

  val PublicKeyBase64: String = Base64.getEncoder.encodeToString(publicKey)
  private val PrivateKey = new Ed25519Sign(
    privateKey
  )

  val Fingerprint: String =
    CometBftRequestSigner.fingerprintForPublicKey(publicKey)
  val PubKey = new Ed25519Verify(publicKey)

  def signRequest(request: GeneratedMessage): Array[Byte] = {
    val requestBytes = request.toByteArray
    PrivateKey.sign(requestBytes)
  }

}

object CometBftRequestSigner {

  val GenesisPubKeyBase64 = "m16haLzv/d/Ok04Sm39ABk0f0HsSWYNZxrIUiyQ+cK8="
  val PrivateKeyBase64 =
    "+7VcQfNKGpd/LnjhA1+LQ13xWQLV2A44P8mbpnTy/YSbXqFovO/9386TThKbf0AGTR/QexJZg1nGshSLJD5wrw=="

  def apply(key: CryptoKeyPair[PublicKey, PrivateKey]): CometBftRequestSigner = {
    val pubKey = SubjectPublicKeyInfo.getInstance(
      key.publicKey.toProtoPublicKeyV30.getSigningPublicKey.publicKey.toByteArray
    )
    val privateKey = PrivateKeyInfo.getInstance(
      key.privateKey.toProtoPrivateKey.getSigningPrivateKey.privateKey.toByteArray
    )
    val privateKeyData =
      ASN1OctetString.getInstance(privateKey.getPrivateKey.getOctets).getOctets
    new CometBftRequestSigner(
      pubKey.getPublicKeyData.getBytes,
      privateKeyData,
    )
  }

  lazy val GenesisSigner: CometBftRequestSigner = {
    val Ed25519KeyLength = 32
    val publicKeyDecoded: Array[Byte] = Base64.getDecoder.decode(GenesisPubKeyBase64)
    val privateKeyDecoded = Base64.getDecoder
      .decode(PrivateKeyBase64)
      .take(
        Ed25519KeyLength
      ) // the key is only 32 bytes followed by the public key, we want only the private key
    new CometBftRequestSigner(publicKeyDecoded, privateKeyDecoded)
  }

  def getOrGenerateSigner(
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
        val keyPair: ParsingResult[CryptoKeyPair[PublicKey, PrivateKey]] =
          CryptoKeyPair.fromTrustedByteString(keyBytes)
        CometBftRequestSigner(
          keyPair.getOrElse(
            throw Status.NOT_FOUND
              .withDescription(
                s"Public key $name could not be parsed."
              )
              .asRuntimeException()
          )
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
