import io.circe._, io.circe.generic.auto._, io.circe.parser._, io.circe.syntax._
import cats.syntax.either._
import com.digitalasset.canton.console.LocalInstanceReference
import com.digitalasset.canton.synchronizer.config.SynchronizerParametersConfig
import com.digitalasset.canton.admin.api.client.data.NodeStatus
import com.digitalasset.canton.version.ProtocolVersion

def main() {
  val synchronizerParametersConfig = SynchronizerParametersConfig(
    alphaVersionSupport = true
  )

  def staticParameters(sequencer: LocalInstanceReference) =
    synchronizerParametersConfig
      .toStaticSynchronizerParameters(sequencer.config.crypto, ProtocolVersion.v34, NonNegativeInt.zero)
      .map(StaticSynchronizerParameters(_))
      .getOrElse(sys.error("whatever"))

  val initialize = decode[Boolean](sys.env.get("CANTON_DOMAIN_INITIALIZE").getOrElse("true"))
    .getOrElse(sys.error("Failed to parse CANTON_synchronizer_INITIALIZE. Expected true or false"))
  if (initialize) {
    utils.retry_until_true {
      sequencer.health.status match {
        case NodeStatus.Failure(msg) =>
          logger.info(s"Failed to query sequencer status: $msg")
          false
        case NodeStatus.NotInitialized(_, _) =>
          logger.info("Initializing synchronizer")
          com.digitalasset.canton.console.ConsoleMacros.bootstrap.synchronizer(
            "synchronizer",
            synchronizerOwners = Seq(sequencer),
            sequencers = Seq(sequencer),
            mediators = Seq(mediator),
            synchronizerThreshold = PositiveInt.one,
            staticSynchronizerParameters = staticParameters(sequencer),
          )
          logger.info("synchronizer initialized")
          true
        case NodeStatus.Success(_) =>
          logger.info(s"synchronizer is already initialized")
          true
      }
    }
  }

}
