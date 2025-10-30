package org.lfdecentralizedtrust.splice.util

import better.files.{File, *}
import com.digitalasset.canton.BaseTest

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

  def startProcess(
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
      args*
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
}

trait ProcessTestUtil { this: BaseTest =>
  import ProcessTestUtil.*

  val testResourcesPath: File = "apps" / "app" / "src" / "test" / "resources"

  protected def withCanton[A](
      configs: Seq[File],
      extraConfigs: Seq[String],
      logSuffix: String,
      extraEnv: (String, String)*
  )(test: => A): A = {
    Using.resource(
      clue(s"Starting external Canton process $logSuffix")(
        startCantonInternal(
          configs.flatMap(config => Seq("-c", config.toString)) ++ extraConfigs.flatMap(
            Seq("-C", _)
          ),
          logSuffix,
          extraEnv*
        )
      )
    )(_ =>
      clue(s"Using external Canton process $logSuffix") {
        test
      }
    )((resource: Process) => {
      clue(s"Destroying external Canton process $logSuffix") {
        resource.destroyAndWait()
      }
    })
  }

  protected def withBundledSplice[A](
      configs: Seq[File],
      logSuffix: String,
  )(test: => A): A = {
    Using.resource(
      startProcess(
        Seq(
          "splice-node",
          "daemon",
          "--log-level-canton=DEBUG",
          "--log-level-stdout=OFF",
          "--log-encoder",
          "json",
          "--log-file-name",
          s"log/splice-node-${logSuffix}.clog",
        ) ++ configs.flatMap(config => Seq("-c", config.toString)),
        Seq(),
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
      extraEnv*
    )
  }

  private def startCantonInternal(
      args: Seq[String],
      logSuffix: String,
      extraEnv: (String, String)*
  ): Process = {
    logger.trace(
      s"${extraEnv.map(e => s"${e._1}=${e._2}").mkString(" ")} ${(defaultArgsCanton(logSuffix) ++ args)
          .mkString(" ")}"
    )
    startProcess(
      defaultArgsCanton(logSuffix) ++ args,
      // Using the same memory settings as in the `./start-canton.sh` script
      extraEnv = extraEnv :+ (("JAVA_TOOL_OPTIONS", "-Xms6g -Xmx8g")),
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
}
