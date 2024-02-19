package com.daml.network.sv.util

import cats.syntax.traverse.*
import com.daml.network.codegen.java.cn.cometbft.{
  CometBftConfig,
  CometBftConfigLimits,
  CometBftNodeConfig,
}
import com.daml.network.codegen.java.cn.svc.globaldomain.{
  DomainConfig,
  DomainNodeConfig,
  DomainNodeConfigLimits,
  MediatorConfig,
  ScanConfig,
  SequencerConfig,
  SvcGlobalDomainConfig,
}
import com.daml.network.codegen.java.cn.svcrules.SvcRulesConfig
import com.daml.network.codegen.java.cn.{cometbft, svc}
import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.config.BackupDumpConfig
import com.daml.network.environment.BuildInfo
import com.daml.network.http.v0.definitions as http
import com.daml.network.store.MultiDomainAcsStore.JsonAcsSnapshot
import com.daml.network.sv.LocalDomainNode
import com.daml.network.sv.cometbft.CometBftNode
import com.daml.network.sv.config.SvScanConfig
import com.daml.network.sv.store.SvSvcStore
import com.daml.network.util.BackupDump
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.time.EnrichedDurations.*
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext

import java.nio.file.{Path, Paths}
import java.security.interfaces.{ECPrivateKey, ECPublicKey}
import java.security.spec.{EncodedKeySpec, PKCS8EncodedKeySpec, X509EncodedKeySpec}
import java.security.{KeyFactory, SecureRandom, Signature}
import java.time.{Instant, Duration as JavaDuration}
import java.util.{Base64, Optional}
import java.util.concurrent.TimeUnit
import scala.concurrent.{ExecutionContext, Future, blocking}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

object SvUtil {

  // TODO(#9173): include SV reward weights in the onboarding configs and double-check all usages of this dummy weight wrt how they need changing
  val dummySvRewardWeight: Long = 12345L

  private def defaultCometBftNetworkLimits: CometBftConfigLimits = new CometBftConfigLimits(
    2, // maxNumCometBftNodes
    2, // maxNumGovernanceKeys
    2, // maxNumSequencingKeys
    50, // maxNodeIdLength
    256, // maxPubKeyLength
  )

  private def defaultDomainNodeConfigLimits: DomainNodeConfigLimits = new DomainNodeConfigLimits(
    defaultCometBftNetworkLimits // cometBft
  )

  val emptyCometBftConfig = new CometBftConfig(
    Map.empty[String, CometBftNodeConfig].asJava,
    List.empty.asJava,
    List.empty.asJava,
  )

  private val defaultInitialTrafficGrant = 1000_000L

  private def defaultSvcGlobalDomainConfig(domainId: DomainId) = new SvcGlobalDomainConfig(
    // domains
    Map(
      domainId.toProtoPrimitive -> new DomainConfig(
        svc.globaldomain.DomainState.DS_OPERATIONAL,
        "TODO(#4900): share CometBFT genesis.json of founding SV node via SvcRules config.",
        // TODO(M3-47): also share the Canton DomainId of the decentralized domain here
      )
    ).asJava,
    domainId.toProtoPrimitive, // lastDomainId
    domainId.toProtoPrimitive, // activeDomain

  )

  case class LocalSequencerConfig(sequencerId: String, url: String)

  def getSequencerConfig(localDomainNode: Option[LocalDomainNode])(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Option[LocalSequencerConfig]] = localDomainNode.map { node =>
    node.sequencerAdminConnection.getSequencerId.map { sequencerId =>
      LocalSequencerConfig(
        sequencerId.toProtoPrimitive,
        node.sequencerExternalPublicUrl,
      )
    }
  }.sequence

  case class LocalMediatorConfig(mediatorId: String)

  def getMediatorConfig(localDomainNode: Option[LocalDomainNode])(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Option[LocalMediatorConfig]] = localDomainNode.map { node =>
    node.mediatorAdminConnection.getMediatorId.map { mediatorId =>
      LocalMediatorConfig(
        mediatorId.toProtoPrimitive
      )
    }
  }.sequence

  def getFounderDomainNodeConfig(
      cometBftNode: Option[CometBftNode],
      localDomainNode: LocalDomainNode,
      scanConfig: Option[SvScanConfig],
      domainId: DomainId,
      clock: Clock,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[java.util.Map[String, DomainNodeConfig]] = {
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
      localSequencerConfig <- getSequencerConfig(Some(localDomainNode))
      sequencerConfig = localSequencerConfig.map(c =>
        new SequencerConfig(
          c.sequencerId,
          c.url,
          Some(clock.now.toInstant).toJava,
        )
      )
      localMediatorConfig <- getMediatorConfig(Some(localDomainNode))
      mediatorConfig = localMediatorConfig.map(c =>
        new MediatorConfig(
          c.mediatorId
        )
      )
    } yield {
      Map(
        domainId.toProtoPrimitive -> new DomainNodeConfig(
          cometBftConfig,
          sequencerConfig.toJava,
          mediatorConfig.toJava,
          scanConfig.map(c => new ScanConfig(c.publicUrl.toString())).toJava,
        )
      ).asJava
    }
  }

  def defaultSvcRulesConfig(domainId: DomainId): SvcRulesConfig = new SvcRulesConfig(
    10, // numUnclaimedRewardsThreshold
    5, // numMemberTrafficContractsThreshold, arbitrarily set as 5 for now.
    new RelTime(TimeUnit.MINUTES.toMicros(5)), // actionConfirmationTimeout
    new RelTime(TimeUnit.HOURS.toMicros(1)), // svOnboardingRequestTimeout
    new RelTime(TimeUnit.HOURS.toMicros(1)), // svOnboardingConfirmedTimeout
    new RelTime(TimeUnit.HOURS.toMicros(7 * 24)), // voteRequestTimeout
    new RelTime(TimeUnit.SECONDS.toMicros(70)), // leaderInactiveTimeout
    defaultDomainNodeConfigLimits,
    1024, // maxTextLength
    defaultInitialTrafficGrant,
    defaultSvcGlobalDomainConfig(domainId), // globalDomainConfig
    Optional.empty(), // nextScheduledHardDomainMigration
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

  def generateRandomOnboardingSecret(): String = {
    val rng = new SecureRandom();
    // 256 bits of entropy
    val bytes = new Array[Byte](32)
    rng.nextBytes(bytes)
    Base64.getEncoder().encodeToString(bytes)
  }

  def fromRelTime(duration: RelTime): JavaDuration =
    JavaDuration.ofMillis(duration.microseconds / 1000)

  def toRelTime(duration: NonNegativeFiniteDuration): RelTime = new RelTime(
    duration.toInternal.toScala.toMicros
  )

  def acsStoreDumpFilename(now: Instant) =
    Paths.get(
      s"svc_acs_dump_${now}.json"
    )

  def writeAcsStoreDump(
      acsDumpConfig: BackupDumpConfig,
      loggerFactory: NamedLoggerFactory,
      svcStore: SvSvcStore,
      clock: Clock,
  )(implicit ec: ExecutionContext, tc: TraceContext): Future[(Path, JsonAcsSnapshot)] = {
    val logger = loggerFactory.getTracedLogger(this.getClass)
    implicit val elc: ErrorLoggingContext =
      ErrorLoggingContext(logger, NamedLoggerFactory.root.properties, tc)

    val now = clock.now.toInstant
    val filename = acsStoreDumpFilename(now)
    logger.debug(
      s"Attempting to write ACS store dump to ${acsDumpConfig.locationDescription} at path: $filename"
    )
    for {
      snapshot <- svcStore.getJsonAcsSnapshot()
      response <- Future {
        blocking {
          import io.circe.syntax.*

          val httpSnapshot = http.GetAcsStoreDumpResponse(
            offset = snapshot.offset,
            contracts = snapshot.contracts.map(_.toHttp).toVector,
            version = Some(BuildInfo.compiledVersion),
          )
          val fileDesc =
            s"ACS store dump for offset ${snapshot.offset} containing ${snapshot.contracts.size} contracts"
          val path = BackupDump.write(
            acsDumpConfig,
            filename,
            httpSnapshot.asJson.noSpaces,
            loggerFactory,
          )

          logger.info(s"Wrote $fileDesc to ${acsDumpConfig.locationDescription} at path: $filename")
          (path, snapshot)
        }
      }
    } yield response
  }
}
