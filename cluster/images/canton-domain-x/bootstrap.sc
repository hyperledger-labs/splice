import cats.syntax.either._
import com.digitalasset.canton.domain.config.DomainParametersConfig
import com.digitalasset.canton.version.{DomainProtocolVersion, ProtocolVersion}
import com.digitalasset.canton.console.LocalInstanceReferenceX

def main() {
  val domainParametersConfig = DomainParametersConfig(
    protocolVersion = DomainProtocolVersion(ProtocolVersion.dev),
    devVersionSupport = true,
    uniqueContractKeys = false,
  )
  def staticParameters(sequencer: LocalInstanceReferenceX) =
    domainParametersConfig
      .toStaticDomainParameters(sequencer.config.crypto)
      .flatMap(StaticDomainParameters(_).leftMap(_.toString))
      .getOrElse(sys.error("whatever"))
  logger.info("Bootstrapping domain")
  sequencer.domain.bootstrap(
    "domain",
    staticParameters(sequencer),
    domainOwners = Seq(sequencer, mediator),
    sequencers = Seq(sequencer),
    mediators = Seq(mediator),
  )
  logger.info("Bootstrapped domain")
}
