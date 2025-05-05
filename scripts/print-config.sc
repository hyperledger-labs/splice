import com.typesafe.config._
import java.io.File

import scala.util.control.NonFatal

object PrintConfig {
  private val parseOptions = ConfigParseOptions.defaults.setAllowMissing(false)

  def main(args: Array[String]): Unit = {
    args match {
      case Array(arg) =>
        try {
          val file = new File(arg)
          val config = ConfigFactory.parseFile(file, parseOptions)

          val output = config
            .resolve()
            .root()
            .render(ConfigRenderOptions.defaults().setComments(false).setOriginComments(false))
          println(output)
        } catch {
          case NonFatal(e) =>
            Console.err.println(e.getMessage)
            sys.exit(1)
        }

      case _ =>
        sys.exit(1)
    }
  }
}
