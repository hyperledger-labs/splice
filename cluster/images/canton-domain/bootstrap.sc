import cats.syntax.either._
import com.digitalasset.canton.console.LocalInstanceReference
import com.digitalasset.canton.domain.config.DomainParametersConfig
import com.digitalasset.canton.health.admin.data.NodeStatus
import com.digitalasset.canton.version.ProtocolVersion

def main() {
  val domainParametersConfig = DomainParametersConfig(
    alphaVersionSupport = true
  )
  def staticParameters(sequencer: LocalInstanceReference) =
    domainParametersConfig
      .toStaticDomainParameters(sequencer.config.crypto, ProtocolVersion.v31)
      .map(StaticDomainParameters(_))
      .getOrElse(sys.error("whatever"))
  utils.retry_until_true {
    sequencer.health.status match {
      case NodeStatus.Failure(msg) =>
        logger.info(s"Failed to query sequencer status: $msg")
        false
      case NodeStatus.NotInitialized(_, _) =>
        logger.info("Initializing domain")
        com.digitalasset.canton.console.EnterpriseConsoleMacros.bootstrap.domain(
          "domain",
          domainOwners = Seq(sequencer),
          sequencers = Seq(sequencer),
          mediators = Seq(mediator),
          staticDomainParameters = staticParameters(sequencer),
        )
        logger.info("Domain initialized")
        true
      case NodeStatus.Success(_) =>
        logger.info(s"Domain is already initialized")
        true
    }
  }
}
