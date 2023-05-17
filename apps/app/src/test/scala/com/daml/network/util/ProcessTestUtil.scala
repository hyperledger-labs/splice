package com.daml.network.util

import java.io.File
import scala.util.Using.Releasable

/** Utilities for starting Canton within our tests.
  * For most tests, we use the Canton environment started up by `./start-canton.sh` but
  * for testing the runbook we spin up our own Canton instance.
  */
object ProcessTestUtil {
  final case class Process(process: java.lang.Process) {
    def destroyAndWait(): Unit = {
      process.destroy()
      process.waitFor()
    }
  }

  object Process {
    implicit val releasable: Releasable[Process] = new Releasable[Process] {
      override def release(process: Process) = process.destroyAndWait()
    }
  }
}

trait ProcessTestUtil {
  import ProcessTestUtil.Process

  protected def startProcess(
      args: Seq[String],
      outputFile: Option[File] = None,
      extraEnv: Seq[(String, String)] = Seq.empty,
  ): Process = {
    // We use the Java process management APIs here since they
    // support proper stream inheritance. Scala implements that by
    // copying streams which can result in some stream closed exceptions
    // if the child process dies while the reader is blocked on a read.
    // See https://github.com/scala/scala/blob/1efd473aae819c8ddd2dc0656a4259c89bf03312/src/library/scala/sys/process/BasicIO.scala#LL246C39-L246C52
    // for the code in the scala stdlib that does the copying.
    val builder = new ProcessBuilder(
      args: _*
    )
    extraEnv.foreach { case (k, v) =>
      builder.environment.put(k, v)
    }
    builder.inheritIO()
    outputFile.foreach(builder.redirectOutput(_))
    Process(
      builder.start()
    )
  }

  private def defaultArgsCanton(logSuffix: String) =
    Seq(
      "canton",
      "daemon",
      "--log-level-canton=DEBUG",
      "--log-level-stdout",
      "OFF",
      "--log-encoder",
      "json",
      "--log-file-name",
      s"log/canton-standalone-$logSuffix.clog",
    )

  protected def startCanton(
      args: Seq[String],
      logSuffix: String,
      extraEnv: (String, String)*
  ): Process =
    startProcess(
      defaultArgsCanton(logSuffix) ++ args,
      extraEnv = extraEnv,
    )

  private def defaultArgsJsonApi(port: Int) =
    Seq(
      "json-api",
      "--log-encoder=json",
      "--log-level=DEBUG",
      "--ledger-host=localhost",
      s"--ledger-port=$port",
      "--address=0.0.0.0",
      "--http-port=7575",
      "--allow-insecure-tokens",
    )

  protected def startJsonApi(port: Int, logFile: File): Process =
    startProcess(defaultArgsJsonApi(port), outputFile = Some(logFile))
}
