// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.util

import cats.syntax.traverse.*
import org.lfdecentralizedtrust.splice.codegen.java.splice.cometbft.{
  CometBftConfig,
  CometBftConfigLimits,
  CometBftNodeConfig,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.decentralizedsynchronizer.{
  DsoDecentralizedSynchronizerConfig,
  MediatorConfig,
  ScanConfig,
  SequencerConfig,
  SynchronizerConfig,
  SynchronizerNodeConfig,
  SynchronizerNodeConfigLimits,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.DsoRulesConfig
import org.lfdecentralizedtrust.splice.codegen.java.splice.{cometbft, dso}
import org.lfdecentralizedtrust.splice.codegen.java.da.time.types.RelTime
import org.lfdecentralizedtrust.splice.environment.{
  MediatorAdminConnection,
  ParticipantAdminConnection,
  RetryProvider,
  SequencerAdminConnection,
}
import org.lfdecentralizedtrust.splice.sv.{LocalSynchronizerNode, SynchronizerNode}
import org.lfdecentralizedtrust.splice.sv.cometbft.{
  CometBftClient,
  CometBftNode,
  CometBftRequestSigner,
}
import org.lfdecentralizedtrust.splice.sv.config.{BeneficiaryConfig, SvCometBftConfig, SvScanConfig}
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.config.{NonNegativeFiniteDuration, PositiveDurationSeconds}
import com.digitalasset.canton.logging.{NamedLoggerFactory, TracedLogger}
import com.digitalasset.canton.protocol.AcsCommitmentsCatchUpParameters
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status

import java.security.interfaces.{ECPrivateKey, ECPublicKey}
import java.security.spec.{EncodedKeySpec, PKCS8EncodedKeySpec, X509EncodedKeySpec}
import java.security.{KeyFactory, SecureRandom, Signature}
import java.time.Duration as JavaDuration
import java.util.{Base64, Optional}
import java.util.concurrent.TimeUnit
import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

object SvUtil {

  val ValidatorOnboardingSecretLength = 32

  // Assumption: the sv1 node is run by the foundation
  val DefaultSV1Weight: Long = 10_000L

  // We set the reconciliation interval for ACS commitments to 30 mins by default to ensure that
  // frequent ACS commitments do not eat up the base rate traffic and prevent validators from topping up
  // (See #12107).
  val defaultAcsCommitmentReconciliationInterval: PositiveDurationSeconds =
    PositiveDurationSeconds.ofMinutes(30)
  val defaultAcsCommitmentsCatchUpParameters: AcsCommitmentsCatchUpParameters =
    AcsCommitmentsCatchUpParameters(
      // With the default reconciliation interval of 30m this corresponds to a catchup interval of 30m * 24 = 12 hours.
      // Catchup mode will trigger after a participant has been lagging for 1 day i.e. 2 "catchup" intervals and
      // the participant will only send an ACS commitment every 12 hours during catchup.
      catchUpIntervalSkip = PositiveInt.tryCreate(24),
      nrIntervalsToTriggerCatchUp = PositiveInt.tryCreate(2),
    )

  def weightDistributionForSv(
      memberSvRewardWeightBps: Long,
      extraBeneficiaries: Seq[BeneficiaryConfig],
      svParty: PartyId,
  )(implicit logger: TracedLogger, tc: TraceContext): Map[PartyId, Long] = {
    @tailrec
    def go(
        remainder: Long,
        remainingBeneficiaries: Seq[BeneficiaryConfig],
        acc: Map[PartyId, Long],
    ): Map[PartyId, Long] =
      if (remainder <= 0) {
        if (!remainingBeneficiaries.isEmpty) {
          logger.info(
            s"Total SV weight $memberSvRewardWeightBps does not cover the following beneficiaries: $remainingBeneficiaries"
          )
        }
        acc
      } else {
        remainingBeneficiaries match {
          case BeneficiaryConfig(party, weight) +: beneficiaries =>
            val usableWeight = if (weight.value > remainder) {
              logger.warn(
                s"Beneficiary weight $weight for $party is greater than the remainder $remainder, capping weight to remainder"
              )
              remainder
            } else weight.value
            val newAcc = acc.updatedWith(party)(prev => Some(prev.getOrElse(0L) + usableWeight))
            go(remainder - usableWeight, beneficiaries, newAcc)
          case _ =>
            acc.updatedWith(svParty)(prev => Some(prev.getOrElse(0L) + remainder))
        }
      }
    go(memberSvRewardWeightBps, extraBeneficiaries, Map.empty)
  }

  private def defaultCometBftNetworkLimits: CometBftConfigLimits = new CometBftConfigLimits(
    2, // maxNumCometBftNodes
    2, // maxNumGovernanceKeys
    2, // maxNumSequencingKeys
    50, // maxNodeIdLength
    256, // maxPubKeyLength
  )

  private def defaultSynchronizerNodeConfigLimits: SynchronizerNodeConfigLimits =
    new SynchronizerNodeConfigLimits(
      defaultCometBftNetworkLimits // cometBft
    )

  val emptyCometBftConfig = new CometBftConfig(
    Map.empty[String, CometBftNodeConfig].asJava,
    List.empty.asJava,
    List.empty.asJava,
  )

  private def defaultDsoDecentralizedSynchronizerConfig(synchronizerId: SynchronizerId) =
    new DsoDecentralizedSynchronizerConfig(
      // domains
      Map(
        synchronizerId.toProtoPrimitive -> new SynchronizerConfig(
          dso.decentralizedsynchronizer.SynchronizerState.DS_OPERATIONAL,
          "TODO(DACH-NY/canton-network-node#4900): share CometBFT genesis.json of sv1 via DsoRules config.",
          // TODO(M3-47): also share the Canton SynchronizerId of the decentralized domain here
          Optional.of(defaultAcsCommitmentReconciliationInterval.duration.toSeconds),
        )
      ).asJava,
      synchronizerId.toProtoPrimitive, // lastSynchronizerId
      synchronizerId.toProtoPrimitive, // activeSynchronizer
    )

  case class LocalSequencerConfig(
      sequencerId: String,
      url: String,
      migrationId: Long,
  )

  def getSequencerConfig(synchronizerNode: Option[SynchronizerNode], migrationId: Long)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Option[LocalSequencerConfig]] = synchronizerNode.map { node =>
    node.sequencerAdminConnection.getSequencerId.map { sequencerId =>
      LocalSequencerConfig(
        sequencerId.toProtoPrimitive,
        node.sequencerExternalPublicUrl,
        migrationId,
      )
    }
  }.sequence

  case class LocalMediatorConfig(mediatorId: String)

  def getMediatorConfig(synchronizerNode: Option[SynchronizerNode])(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Option[LocalMediatorConfig]] = synchronizerNode.map { node =>
    node.mediatorAdminConnection.getMediatorId.map { mediatorId =>
      LocalMediatorConfig(
        mediatorId.toProtoPrimitive
      )
    }
  }.sequence

  def getSV1SynchronizerNodeConfig(
      cometBftNode: Option[CometBftNode],
      localSynchronizerNode: LocalSynchronizerNode,
      scanConfig: SvScanConfig,
      synchronizerId: SynchronizerId,
      clock: Clock,
      migrationId: Long,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[java.util.Map[String, SynchronizerNodeConfig]] = {
    for {
      nodeConfigOpt <- cometBftNode
        .map(_.getLocalNodeConfig())
        .sequence
      cometBftConfig = nodeConfigOpt
        .map { cometBftNodeConfig =>
          new CometBftConfig(
            cometBftNodeConfig.cometbftNodes.view
              .mapValues(config =>
                new cometbft.CometBftNodeConfig(
                  config.validatorPubKey,
                  config.votingPower,
                )
              )
              .toMap
              .asJava,
            cometBftNodeConfig.governanceKeys
              .map(key => new cometbft.GovernanceKeyConfig(key.pubKey))
              .asJava,
            cometBftNodeConfig.sequencingKeys
              .map(key => new cometbft.SequencingKeyConfig(key.pubKey))
              .asJava,
          )
        }
        .getOrElse(SvUtil.emptyCometBftConfig)
      localSequencerConfig <- getSequencerConfig(Some(localSynchronizerNode), migrationId)
      sequencerConfig = localSequencerConfig.map(c =>
        new SequencerConfig(
          migrationId,
          c.sequencerId,
          c.url,
          Some(clock.now.toInstant).toJava,
        )
      )
      localMediatorConfig <- getMediatorConfig(Some(localSynchronizerNode))
      mediatorConfig = localMediatorConfig.map(c =>
        new MediatorConfig(
          c.mediatorId
        )
      )
    } yield {
      Map(
        synchronizerId.toProtoPrimitive -> new SynchronizerNodeConfig(
          cometBftConfig,
          sequencerConfig.toJava,
          mediatorConfig.toJava,
          Optional.of(new ScanConfig(scanConfig.publicUrl.toString())),
          Optional.empty(),
        )
      ).asJava
    }
  }

  // TODO(#1271): deduplicate with the definition in SpliceUtil
  def defaultDsoRulesConfig(
      synchronizerId: SynchronizerId,
      voteCooldownTime: Option[NonNegativeFiniteDuration] = None,
  ): DsoRulesConfig = new DsoRulesConfig(
    10, // numUnclaimedRewardsThreshold
    5, // numMemberTrafficContractsThreshold, arbitrarily set as 5 for now.
    new RelTime(TimeUnit.HOURS.toMicros(1)), // actionConfirmationTimeout
    new RelTime(TimeUnit.HOURS.toMicros(1)), // svOnboardingRequestTimeout
    new RelTime(TimeUnit.HOURS.toMicros(1)), // svOnboardingConfirmedTimeout
    new RelTime(TimeUnit.HOURS.toMicros(7 * 24)), // voteRequestTimeout
    new RelTime(TimeUnit.SECONDS.toMicros(70)), // dsoDelegateInactiveTimeout
    defaultSynchronizerNodeConfigLimits,
    1024, // maxTextLength
    defaultDsoDecentralizedSynchronizerConfig(synchronizerId), // decentralizedSynchronizerConfig
    Optional.empty(), // nextScheduledHardDomainMigration
    voteCooldownTime.map(t => new RelTime(t.duration.toMicros)).toJava,
    Optional.empty(), // voteExecutionInstructionTimeout
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

  def generateRandomOnboardingSecret(
      sv: PartyId,
      partyHint: Option[String],
  ): ValidatorOnboardingSecret = {
    val rng = new SecureRandom();
    // 256 bits of entropy
    val bytes = new Array[Byte](ValidatorOnboardingSecretLength)
    rng.nextBytes(bytes)
    val secret = Base64.getEncoder().encodeToString(bytes)
    ValidatorOnboardingSecret(
      sv,
      secret,
      partyHint,
    )
  }

  def fromRelTime(duration: RelTime): JavaDuration =
    JavaDuration.ofMillis(duration.microseconds / 1000)

  def toRelTime(duration: NonNegativeFiniteDuration): RelTime = new RelTime(
    duration.toInternal.toScala.toMicros
  )

  def getSequencerAdminConnection(
      primarySequencerAdminConnection: Option[SequencerAdminConnection]
  ): SequencerAdminConnection =
    primarySequencerAdminConnection.getOrElse(
      throw Status.FAILED_PRECONDITION
        .withDescription("No sequencer admin connection configured for SV App")
        .asRuntimeException()
    )

  def getMediatorAdminConnection(
      primaryMediatorAdminConnection: Option[MediatorAdminConnection]
  ): MediatorAdminConnection =
    primaryMediatorAdminConnection.getOrElse(
      throw Status.FAILED_PRECONDITION
        .withDescription("No mediator admin connection configured for SV App")
        .asRuntimeException()
    )

  def mapToCometBftNode(
      cometBftClient: Option[CometBftClient],
      cometBftConfig: Option[SvCometBftConfig],
      participantAdminConnection: ParticipantAdminConnection,
      logger: TracedLogger,
      loggerFactory: NamedLoggerFactory,
      retryProvider: RetryProvider,
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[Option[CometBftNode]] =
    (cometBftClient, cometBftConfig) match {
      case (Some(client), Some(config)) =>
        SvUtil
          .getOrGenerateCometBftGovernanceKeySigner(
            config,
            participantAdminConnection,
            logger,
          )
          .map(signer =>
            Some(
              new CometBftNode(
                client,
                signer,
                config,
                loggerFactory,
                retryProvider,
              )
            )
          )
      case _ => Future.successful(None)
    }

  def getOrGenerateCometBftGovernanceKeySigner(
      config: SvCometBftConfig,
      participantAdminConnection: ParticipantAdminConnection,
      logger: TracedLogger,
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[CometBftRequestSigner] = {
    config.governanceKey match {
      case Some(governanceKey) => {
        logger.info("Using CometBFT governance key from config")
        Future.successful(
          new CometBftRequestSigner(governanceKey.publicKey, governanceKey.privateKey)
        )
      }
      case None => {
        logger.info("Using CometBFT governance key managed by participant")
        CometBftRequestSigner.getOrGenerateSignerFromParticipant(
          "cometbft-governance-keys",
          participantAdminConnection,
          logger,
        )
      }
    }
  }
}
