package org.lfdecentralizedtrust.splice.util

import com.digitalasset.canton.logging.TracedLogger
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.connectors.file.scaladsl.FileTailSource
import org.apache.pekko.stream.scaladsl.Sink

import java.nio.file.Paths
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

trait LoggerUtil {

  def findExpectedLogInFile(
      regex: String,
      filePath: String,
      logger: TracedLogger,
      timeout: FiniteDuration = 65.seconds,
  )(implicit system: ActorSystem, tc: TraceContext): Future[Boolean] = {
    implicit val ec: ExecutionContext = system.dispatcher

    val path = Paths.get(filePath)
    val source = FileTailSource.lines(
      path = path,
      maxLineSize = 100000,
      pollingInterval = 200.millis,
    )

    val stream: Future[Option[String]] = source
      .filter { line =>
        val found = regex.r.findFirstIn(line).isDefined
        if (found) {
          logger.info(s"String containing $regex found in: $line")
        }
        found
      }
      .take(1)
      .takeWithin(timeout)
      .runWith(Sink.headOption)

    stream
      .map { maybeLine =>
        maybeLine.isDefined
      }
      .recover { case NonFatal(e) =>
        logger.error(s"Error while parsing the log file: $e")
        false
      }
  }
}
