import scala.sys.process._
import scala.collection.mutable
import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}
import sbt.{File, MessageOnlyException}
import sbt.util.Logger

object BuildUtil {
  class BufferedLogger extends ProcessLogger {
    private val buffer = mutable.Buffer[String]()

    override def out(s: => String): Unit = buffer.append(s)
    override def err(s: => String): Unit = buffer.append(s)
    override def buffer[T](f: => T): T = f

    /** Output the buffered content to a String applying an optional line prefix.
      */
    def output(linePrefix: String = ""): String =
      buffer.map(l => s"$linePrefix$l").mkString(System.lineSeparator)
  }

  /** Utility function to run a (shell) command. */
  def runCommand(
      args: Seq[String],
      log: Logger,
      optError: Option[String] = None,
      optCwd: Option[File] = None,
      extraEnv: Seq[(String, String)] = Seq.empty,
  ): String = {
    import scala.sys.process.Process
    val command = args.mkString(" ")
    val processLogger = new BuildUtil.BufferedLogger
    val cwdInfo = optCwd.map(cwd => s" in `$cwd`").getOrElse("")
    log.debug(s"Running $command$cwdInfo")
    val exitCode = Process(args, optCwd, extraEnv = extraEnv: _*) ! processLogger
    val output = processLogger.output()
    if (exitCode != 0) {
      val errorMsg =
        s"Running command `$command`$cwdInfo returned non-zero exit code: $exitCode}"
      log.error(output)
      if (optError.isDefined) log.error(optError.getOrElse(""))
      throw new IllegalStateException(errorMsg)
    } else if (output != "") log.info(processLogger.output())
    output
  }

  def runCommandWithRetries(
      args: Seq[String],
      log: Logger,
      optError: Option[String] = None,
      optCwd: Option[File] = None,
      numRetries: Int = 5,
  ) = {
    @tailrec
    def go(n: Int): Unit = {
      Try {
        runCommand(args, log, optError, optCwd)
      } match {
        case Success(str) =>
        case Failure(t) =>
          if (n <= 0) {
            log.info("Retries exceeded, giving up ...")
            throw t
          } else {
            log.info("Encountered error $t, retrying ...")
            Thread.sleep(1000)
            go(n - 1)
          }
      }
    }
    go(5)
  }
}
