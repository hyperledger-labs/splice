import io.circe._, io.circe.generic.auto._, io.circe.parser._, io.circe.syntax._

def main() {
  logger.info("Waiting for validators to finish init...")
  validators.map(_.waitForInitialization(2.minutes))
}
