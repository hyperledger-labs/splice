package com.daml.network.util

import better.files.{File, *}

import scala.util.Using.Releasable
import java.io.File as JFile
import scala.util.Using

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
    implicit val releasable: Releasable[Process] = (process: Process) => process.destroyAndWait()
  }
}

trait ProcessTestUtil {
  import ProcessTestUtil.Process

  val testResourcesPath: File = "apps" / "app" / "src" / "test" / "resources"

  private def startProcess(
      args: Seq[String],
      extraEnv: Seq[(String, String)],
      outputFile: Option[JFile] = None,
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
    outputFile.foreach(builder.redirectOutput)
    Process(
      builder.start()
    )
  }

  protected def withCanton[A](
      configs: Seq[File],
      extraConfigs: Seq[String],
      logSuffix: String,
      extraEnv: (String, String)*
  )(test: => A): A = {
    Using.resource(
      startCantonInternal(
        configs.flatMap(config => Seq("-c", config.toString)) ++ extraConfigs.flatMap(Seq("-C", _)),
        logSuffix,
        extraEnv: _*
      )
    )(_ => test)
  }

  protected def startCanton(
      configs: Seq[File],
      extraConfigs: Seq[String],
      logSuffix: String,
      extraEnv: (String, String)*
  ): Process = {
    startCantonInternal(
      configs.flatMap(config => Seq("-c", config.toString)) ++ extraConfigs.flatMap(Seq("-C", _)),
      logSuffix,
      extraEnv: _*
    )
  }

  private def startCantonInternal(
      args: Seq[String],
      logSuffix: String,
      extraEnv: (String, String)*
  ): Process =
    startProcess(
      defaultArgsCanton(logSuffix) ++ args,
      extraEnv = extraEnv,
    )

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
}
