import scala.collection.mutable.ListBuffer

import cats.syntax.either._
import java.nio.file.{Paths, Files}
import java.nio.charset.StandardCharsets
import com.digitalasset.canton.console.ParticipantReference
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.domain.config.DomainParametersConfig
import com.digitalasset.canton.version.{DomainProtocolVersion, ProtocolVersion}
import com.digitalasset.canton.console.InstanceReferenceX
import com.digitalasset.canton.DomainAlias

println("Running canton bootstrap script...")

val staticParameters =
  DomainParametersConfig(
    protocolVersion = DomainProtocolVersion(ProtocolVersion.dev),
    devVersionSupport = true,
    uniqueContractKeys = false,
  )
    .toStaticDomainParameters(globalSeqSv1.config.crypto)
    .flatMap(StaticDomainParameters(_).leftMap(_.toString))
    .getOrElse(sys.error("whatever"))

val domainId = globalSeqSv1.domain.bootstrap(
  "global-domain",
  staticParameters,
  // TODO(#5087) Make this a union space
  domainOwners = Seq(sv1Participant, globalSeqSv1, globalMedSv1),
  sequencers = Seq(globalSeqSv1),
  mediators = Seq(globalMedSv1),
)

println("Connecting all participants to global domain...")
participantsX.local.foreach(
  _.domains.connect_local(globalSeqSv1, alias = Some(DomainAlias.tryCreate("global")))
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
