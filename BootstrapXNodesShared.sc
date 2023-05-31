import scala.collection.mutable.ListBuffer

import cats.syntax.either._
import java.nio.file.{Paths, Files}
import java.nio.charset.StandardCharsets
import com.digitalasset.canton.console.ParticipantReference
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.domain.config.DomainParametersConfig
import com.digitalasset.canton.version.{DomainProtocolVersion, ProtocolVersion}
import com.digitalasset.canton.console.LocalInstanceReferenceX
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.concurrent.Threading

class BootstrappedTopology() {
  println("Running canton bootstrap script...")

  val domainParametersConfig = DomainParametersConfig(
    protocolVersion = DomainProtocolVersion(ProtocolVersion.dev),
    devVersionSupport = true,
    uniqueContractKeys = false,
  )

  def staticParameters(sequencer: LocalInstanceReferenceX) = {
    domainParametersConfig
      .toStaticDomainParameters(sequencer.config.crypto)
      .flatMap(StaticDomainParameters(_).leftMap(_.toString))
      .getOrElse(sys.error("whatever"))
  }

  val globalDomainId = globalSequencerSv1.domain.bootstrap(
    "global-domain",
    staticParameters(globalSequencerSv1),
    // TODO(#5087) Make this a union space
    domainOwners = Seq(sv1Participant, globalSequencerSv1, globalMediatorSv1),
    sequencers = Seq(globalSequencerSv1),
    mediators = Seq(globalMediatorSv1),
  )

  Seq(
    ("splitwell", splitwellSequencer, splitwellMediator),
    ("splitwellUpgrade", splitwellUpgradeSequencer, splitwellUpgradeMediator),
  ).foreach { case (name, sequencer, mediator) =>
    sequencer.domain.bootstrap(
      name,
      staticParameters(sequencer),
      domainOwners = Seq(sequencer, mediator),
      sequencers = Seq(sequencer),
      mediators = Seq(mediator),
    )
  }

  println("Connecting all participants to global domain...")
  participantsX.local.foreach(
    _.domains.connect_local(globalSequencerSv1, alias = Some(DomainAlias.tryCreate("global")))
  )

  println("Connecting splitwell, alice & bob participant to splitwell domain...")
  Seq(aliceParticipant, bobParticipant, splitwellParticipant).foreach(
    _.domains.connect_local(splitwellSequencer, alias = Some(DomainAlias.tryCreate("splitwell")))
  )
  // We only connect splitwell by default since we want to simulate users connecting gradually to the domain.
  println("Connecting splitwell to upgraded domain...")
  splitwellParticipant.domains.connect_local(
    splitwellUpgradeSequencer,
    alias = Some(DomainAlias.tryCreate("splitwellUpgrade")),
  )

  println(s"Collecting admin tokens...")
  val adminTokensData = ListBuffer[(String, String)]()
  participantsX.local.foreach(participant => {
    val adminToken = participant.underlying.map(_.adminToken.secret).getOrElse("")
    val port = participant.config.ledgerApi.internalPort.get.unwrap
    adminTokensData.append(s"$port" -> adminToken)
  })
  val tokenFile = System.getenv("CANTON_TOKEN_FILENAME")
  if (tokenFile == null) {
    sys.error("Environment variable CANTON_TOKEN_FILENAME was not set")
  }
  println(s"Writing admin tokens file to $tokenFile...")
  val adminTokensContent =
    adminTokensData.map(x => s"${x._1} ${x._2}").mkString(System.lineSeparator())
  Files.write(Paths.get(tokenFile), adminTokensContent.getBytes(StandardCharsets.UTF_8))

  println("Canton bootstrap script done.")
}
