package com.daml.network.sv.cometbft

import com.digitalasset.canton.crypto.{Fingerprint, Hash, HashAlgorithm, HashPurpose}
import com.google.crypto.tink.subtle.{Ed25519Sign, Ed25519Verify}
import com.google.protobuf.ByteString
import scalapb.GeneratedMessage

import java.util.Base64

private[cometbft] object CometBftRequestSigner {

  private val Ed25519KeyLength = 32

  // The code currently uses a static governance key shared among all nodes, as governance requests need to be signed,
  // but we haven't implemented per SV-node keys yet.
  // TODO(#4925) implement per SV-node keys.

  private val GenesisPrivateKeyBase64 =
    "+7VcQfNKGpd/LnjhA1+LQ13xWQLV2A44P8mbpnTy/YSbXqFovO/9386TThKbf0AGTR/QexJZg1nGshSLJD5wrw=="
  val GenesisPubKeyBase64 = "m16haLzv/d/Ok04Sm39ABk0f0HsSWYNZxrIUiyQ+cK8="

  private val GenesisPrivateKeyBytes = Base64.getDecoder.decode(GenesisPrivateKeyBase64)

  // Exposed public for testing only.

  // Using the Google Tink library (same as Canton).
  // CometBFT private keys have 64 bytes. The first 32 bytes contain the right key.
  private val GenesisPrivateKey = new Ed25519Sign(GenesisPrivateKeyBytes.take(Ed25519KeyLength))

  val GenesisPubKeyBytes: Array[Byte] = Base64.getDecoder.decode(GenesisPubKeyBase64)
  val GenesisFingerprint: String = fingerprintForBase64PublicKey(GenesisPubKeyBase64)
  val genesisPubKeyBytesFromPrivateKey: Array[Byte] = GenesisPrivateKeyBytes.drop(Ed25519KeyLength)
  val GenesisPubKey = new Ed25519Verify(GenesisPubKeyBytes)

  def signRequest(request: GeneratedMessage): Array[Byte] = {
    val requestBytes = request.toByteArray
    GenesisPrivateKey.sign(requestBytes)
  }
  def fingerprintForBase64PublicKey(publicKey: String): String = {
    val decodedKey: Array[Byte] = Base64.getDecoder.decode(publicKey)
    fingerprintForPublicKey(ByteString.copyFrom(decodedKey))
  }

  private def fingerprintForPublicKey(publicKey: ByteString) = {
    val hash = Hash.digest(HashPurpose.PublicKeyFingerprint, publicKey, HashAlgorithm.Sha256)
    val fingerprint = new Fingerprint(hash.toLengthLimitedHexString)
    fingerprint.toLengthLimitedString.toString()
  }

}
