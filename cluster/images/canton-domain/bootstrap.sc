import io.circe._, io.circe.generic.auto._, io.circe.parser._, io.circe.syntax._
import cats.syntax.either._
import com.digitalasset.canton.console.LocalInstanceReference
import com.digitalasset.canton.domain.config.DomainParametersConfig
import com.digitalasset.canton.admin.api.client.data.NodeStatus
import com.digitalasset.canton.version.ProtocolVersion

def main() {
  val domainParametersConfig = DomainParametersConfig(
    alphaVersionSupport = true
  )

  def staticParameters(sequencer: LocalInstanceReference) =
    domainParametersConfig
      .toStaticDomainParameters(sequencer.config.crypto, ProtocolVersion.v32)
      .map(StaticDomainParameters(_))
      .getOrElse(sys.error("whatever"))

  val initialize = decode[Boolean](sys.env.get("CANTON_DOMAIN_INITIALIZE").getOrElse("true"))
    .getOrElse(sys.error("Failed to parse CANTON_DOMAIN_INITIALIZE. Expected true or false"))
  if (initialize) {
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
            domainThreshold = PositiveInt.one,
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

}
