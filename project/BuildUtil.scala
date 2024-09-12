// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import scala.sys.process._
import scala.collection.mutable
import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}
import sbt.{File, MessageOnlyException}
import sbt.util.Logger

object BuildUtil {
  class BufferedLogger extends ProcessLogger {
    private val buffer = mutable.Buffer[String]()
    private val stdoutBuffer = mutable.Buffer[String]()

    override def out(s: => String): Unit = {
      synchronized {
        buffer.append(s)
        stdoutBuffer.append(s)
      }
    }

    override def err(s: => String): Unit =
      synchronized {
        buffer.append(s)
      }

    override def buffer[T](f: => T): T = f

    /** Output the buffered content to a String applying an optional line prefix.
      * stdout and stderr are interleaved.
      */
    def output(linePrefix: String = ""): String =
      synchronized {
        buffer.map(l => s"$linePrefix$l").mkString(System.lineSeparator)
      }

    /** Like output but excludes stderr.
      */
    def outputStdout(linePrefix: String = ""): String =
      synchronized {
        stdoutBuffer.map(l => s"$linePrefix$l").mkString(System.lineSeparator)
      }
  }

  /** Utility function to run a (shell) command. */
  def runCommand(
      args: Seq[String],
      log: Logger,
      optError: Option[String] = None,
      optCwd: Option[File] = None,
      extraEnv: Seq[(String, String)] = Seq.empty,
  ): String = {
    runCommandOptionalLog(args, Some(log), optError, optCwd, extraEnv)
  }

  def runCommandOptionalLog(
      args: Seq[String],
      optLog: Option[Logger] = None,
      optError: Option[String] = None,
      optCwd: Option[File] = None,
      extraEnv: Seq[(String, String)] = Seq.empty,
  ): String = {
    import scala.sys.process.Process
    val command = args.mkString(" ")
    val processLogger = new BuildUtil.BufferedLogger
    val cwdInfo = optCwd.map(cwd => s" in `$cwd`").getOrElse("")
    if (optLog.isDefined) optLog.map(_.debug(s"Running $command$cwdInfo"))
    val exitCode = Process(args, optCwd, extraEnv = extraEnv: _*) ! processLogger
    val output = processLogger.output()
    if (exitCode != 0) {
      val errorMsg =
        s"Running command `$command`$cwdInfo returned non-zero exit code: $exitCode"
      if (optLog.isDefined) optLog.map(_.error(output))
      if (optError.isDefined && optLog.isDefined)
        if (optLog.isDefined) optLog.map(_.error(optError.getOrElse("")))
      throw new IllegalStateException(errorMsg)
    } else if (output != "" && optLog.isDefined)
      if (optLog.isDefined) optLog.map(_.info(processLogger.output()))
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
            log.info(s"Encountered error $t, retrying ...")
            Thread.sleep(1000)
            go(n - 1)
          }
      }
    }

    go(5)
  }
}
