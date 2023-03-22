package com.daml.network.sv.util

import com.daml.network.codegen.java.cn.svcrules.{SvcRules, SvcRulesConfig}
import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.util.Contract

import java.security.{KeyFactory, SecureRandom, Signature}
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.EncodedKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.TimeUnit

object SvUtil {

  def defaultSvcRulesConfig(): SvcRulesConfig = new SvcRulesConfig(
    10, // numUnclaimedRewardsThreshold
    new RelTime(TimeUnit.HOURS.toMicros(24)), // svOnboardingTimeout
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
    2 * (memberNum - 1) / 3 + 1
  }
}
