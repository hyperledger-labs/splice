package com.daml.network.sv.util

import com.daml.network.codegen.java.cn.cometbft.NetworkConfigLimits
import com.daml.network.codegen.java.cn.svcrules.{SvcRules, SvcRulesConfig}
import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.util.Contract

import java.security.{KeyFactory, SecureRandom, Signature}
import java.security.interfaces.{ECPrivateKey, ECPublicKey}
import java.security.spec.{EncodedKeySpec, PKCS8EncodedKeySpec, X509EncodedKeySpec}
import java.util.Base64
import java.util.concurrent.TimeUnit

object SvUtil {

  def defaultCometBftNetworkLimits: NetworkConfigLimits = new NetworkConfigLimits(
    2, // maxNumCometBftNodes
    2, // maxNumGovernanceKeys
    2, // maxNumSequencingKeys
    50, // maxNodeIdLength
    256, // maxPubKeyLength
  )

  def defaultSvcRulesConfig(): SvcRulesConfig = new SvcRulesConfig(
    10, // numUnclaimedRewardsThreshold
    new RelTime(TimeUnit.MINUTES.toMicros(5)), // actionConfirmationTimeout
    new RelTime(TimeUnit.HOURS.toMicros(24)), // svOnboardingTimeout
    new RelTime(TimeUnit.HOURS.toMicros(24)), // svOnboardingConfirmedTimeout
    new RelTime(TimeUnit.HOURS.toMicros(7 * 24)), // voteRequestTimeout
    defaultCometBftNetworkLimits,
    1024, // maxTextLength
  )

  def keyPairMatches(
      publicKeyBase64: String,
      privateKeyBase64: String,
  ): Either[String, ECPrivateKey] = {

    for {
      publicKey <- parsePublicKey(publicKeyBase64)
      privateKey <- parsePrivateKey(privateKeyBase64)
      _ <- {
        // the actual check is signing a challenge and verifying the resulting signature
        val challenge = new Array[Byte](100)
        (new SecureRandom()).nextBytes(challenge)

        // sign using the private key
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(privateKey)
        signer.update(challenge)

        val signature = signer.sign();

        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(publicKey)
        verifier.update(challenge)

        if (verifier.verify(signature)) {
          Right(())
        } else {
          Left("public and private keys don't match")
        }
      }
    } yield privateKey
  }

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def parsePublicKey(keyBase64: String): Either[String, ECPublicKey] = {
    try {
      val keyBytes = Base64.getDecoder().decode(keyBase64)
      val keySpec: EncodedKeySpec = new X509EncodedKeySpec(keyBytes)
      val keyFactory = KeyFactory.getInstance("EC")
      Right(keyFactory.generatePublic(keySpec).asInstanceOf[ECPublicKey])
    } catch {
      case e: Exception => Left(s"could not parse public key: ${e.getMessage()}")
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def parsePrivateKey(keyBase64: String): Either[String, ECPrivateKey] = {
    try {
      val keyBytes = Base64.getDecoder().decode(keyBase64)
      val keySpec: EncodedKeySpec = new PKCS8EncodedKeySpec(keyBytes)
      val keyFactory = KeyFactory.getInstance("EC")
      Right(keyFactory.generatePrivate(keySpec).asInstanceOf[ECPrivateKey])
    } catch {
      case e: Exception => Left(s"could not parse private key: ${e.getMessage()}")
    }
  }

  def requiredNumConfirmations(svcRules: Contract[SvcRules.ContractId, SvcRules]): Int = {
    val memberNum = svcRules.payload.members.size
    // as per `SvcRules` / `summarizeCollective`
    val required = 2 * (memberNum - 1) / 3 + 1
    if (svcRules.payload.isDevNet) required min 4
    else required
  }

  def requiredNumVotes(svcRules: Contract[SvcRules.ContractId, SvcRules]): Int = {
    val memberNum = svcRules.payload.members.size
    // as per `SvcRules` / `summarizeCollective`
    val required = 2 * (memberNum - 1) / 3 + 1
    if (svcRules.payload.isDevNet) required min 4
    else required
  }

  def generateRandomOnboardingSecret(): String = {
    val rng = new SecureRandom();
    // 256 bits of entropy
    val bytes = new Array[Byte](32)
    rng.nextBytes(bytes)
    Base64.getEncoder().encodeToString(bytes)
  }
}
