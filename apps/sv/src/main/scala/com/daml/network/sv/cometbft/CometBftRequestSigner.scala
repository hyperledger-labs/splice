// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.sv.cometbft

import com.daml.network.environment.ParticipantAdminConnection
import com.digitalasset.canton.crypto.*
import com.digitalasset.canton.logging.TracedLogger
import com.digitalasset.canton.tracing.TraceContext
import com.google.crypto.tink.subtle.{Ed25519Sign, Ed25519Verify}
import com.google.protobuf.ByteString
import io.grpc.Status
import scalapb.GeneratedMessage

import java.util.Base64
import scala.concurrent.{ExecutionContext, Future}

class CometBftRequestSigner(
    val PublicKeyBase64: String,
    privateKeyBase64: String,
) {

  private val Ed25519KeyLength = 32

  private val PrivateKeyBytes = Base64.getDecoder.decode(privateKeyBase64)
  private val PrivateKey = new Ed25519Sign(PrivateKeyBytes.take(Ed25519KeyLength))

  val PubKeyBytes: Array[Byte] = Base64.getDecoder.decode(PublicKeyBase64)
  val Fingerprint: String =
    CometBftRequestSigner.fingerprintForBase64PublicKey(PublicKeyBase64)
  val PubKeyBytesFromPrivateKey: Array[Byte] = PrivateKeyBytes.drop(Ed25519KeyLength)
  val PubKey = new Ed25519Verify(PubKeyBytes)

  def signRequest(request: GeneratedMessage): Array[Byte] = {
    val requestBytes = request.toByteArray
    PrivateKey.sign(requestBytes)
  }

}

object CometBftRequestSigner {

  private val PubKeyBase64 = "m16haLzv/d/Ok04Sm39ABk0f0HsSWYNZxrIUiyQ+cK8="
  private val PrivateKeyBase64 =
    "+7VcQfNKGpd/LnjhA1+LQ13xWQLV2A44P8mbpnTy/YSbXqFovO/9386TThKbf0AGTR/QexJZg1nGshSLJD5wrw=="

  def getGenesisSigner =
    new CometBftRequestSigner(PubKeyBase64, PrivateKeyBase64)

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
            keypair <- participantAdminConnection.generateKeyPair(name)
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
        val keyPair = CryptoKeyPair.fromTrustedByteString(keyBytes)
        val pubKey = keyPair
          .map(_.publicKey)
          .map(_.toProtoPublicKeyV30.getSigningPublicKey.publicKey.toByteArray)
          .getOrElse(
            throw Status.NOT_FOUND
              .withDescription(
                s"Public key $name could not be parsed."
              )
              .asRuntimeException()
          )
        val privKey = keyPair
          .map(_.privateKey)
          .map(_.toProtoPrivateKey.getSigningPrivateKey.privateKey.toByteArray)
          .getOrElse(
            throw Status.NOT_FOUND
              .withDescription(
                s"Private key $name could not be parsed."
              )
              .asRuntimeException()
          )
        val augmentedPrivKey = privKey ++ pubKey
        new CometBftRequestSigner(
          Base64.getEncoder.encodeToString(pubKey),
          Base64.getEncoder.encodeToString(augmentedPrivKey),
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
    fingerprintForPublicKey(ByteString.copyFrom(decodedKey))
  }

  private def fingerprintForPublicKey(publicKey: ByteString) = {
    val hash = Hash.digest(HashPurpose.PublicKeyFingerprint, publicKey, HashAlgorithm.Sha256)
    val fingerprint = Fingerprint.tryCreate(hash.toLengthLimitedHexString)
    fingerprint.toLengthLimitedString.toString()
  }

}
