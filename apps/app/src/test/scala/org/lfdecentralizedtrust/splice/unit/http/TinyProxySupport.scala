package org.lfdecentralizedtrust.splice.unit.http

import com.digitalasset.canton.concurrent.Threading

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent.blocking
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.sys.process.{Process, ProcessLogger}
import scala.concurrent.duration.*

trait TinyProxySupport {
  object HttpProxy {
    def apply(maxStartupTime: Duration, auth: Option[(String, String)]): HttpProxy = {
      val configFile = File.createTempFile("tinyproxy", ".conf")
      val proxyPort = 3128
      createConfig(configFile, proxyPort, auth)
      val proxyProcess = new ProxyProcess(configFile)
      @tailrec
      def waitUntilStarted(maxWait: Long): Unit = {
        if (maxWait > 0) {
          val wait = 50L
          Threading.sleep(wait)
          if (
            !proxyProcess.stdOutLines.exists(
              _.contains("Starting main loop. Accepting connections.")
            )
          ) {
            waitUntilStarted(maxWait - wait)
          }
        } else
          throw new RuntimeException(
            "Timed out waiting for tinyproxy to start, stderr:\n" + proxyProcess.stdErrLines
              .mkString("\n")
          )
      }
      waitUntilStarted(maxStartupTime.toMillis)
      TinyProxy(proxyProcess, proxyPort, configFile)
    }

    private def createConfig(
        configFile: File,
        proxyPort: Int,
        auth: Option[(String, String)],
    ): Unit = {
      val config =
        s"""
           |Port ${proxyPort}
           |Listen 0.0.0.0
           |Timeout 600
           |Allow 127.0.0.1
           |Allow localhost
           |${auth.map { case (u, p) => s"BasicAuth $u $p" }.getOrElse("")}
           |""".stripMargin
      Files.write(configFile.toPath, config.getBytes(StandardCharsets.UTF_8))
    }

    case class TinyProxy(process: ProxyProcess, port: Int, configFile: File) extends HttpProxy {
      def hasNoErrors: Boolean = process.hasNoErrors
      def proxiedConnectRequest(serverPort: Int): Boolean = {
        List(
          process.stdOutLines.exists(_.contains(s"CONNECT localhost:$serverPort")),
          process.stdOutLines.exists(_.contains(s"opening connection to localhost:$serverPort")),
          process.stdOutLines.exists(
            _.contains(s"opensock: getaddrinfo returned for localhost:$serverPort")
          ),
        ).forall(_ == true)
      }
      def stop(): Unit = {
        process.destroy()
        configFile.delete()
      }
    }
  }

  class ProxyProcess(configFile: File) {
    private val cmd = s"tinyproxy -d -c ${configFile.getAbsolutePath}"
    private val processBuilder = Process(cmd)
    private val stdout = mutable.Buffer[String]()
    private val stderr = mutable.Buffer[String]()
    private val processLogger = ProcessLogger(
      out =>
        blocking {
          synchronized {
            stdout.append(out)
          }
        },
      err =>
        blocking {
          synchronized {
            stderr.append(err)
          }
        },
    )
    private val process = processBuilder.run(processLogger)
    def stdOutLines: Seq[String] = blocking { synchronized { stdout.toSeq } }
    def stdErrLines: Seq[String] = blocking { synchronized { stderr.toSeq } }
    def hasNoErrors: Boolean = stdErrLines.isEmpty
    def destroy(): Unit = process.destroy()
  }

  trait HttpProxy {
    def port: Int
    def process: ProxyProcess
    def hasNoErrors: Boolean
    def proxiedConnectRequest(serverPort: Int): Boolean
    def stop(): Unit
  }

  def withProxy[T](
      auth: Option[(String, String)] = None,
      maxStartupTime: FiniteDuration = 5.seconds,
  )(testCode: HttpProxy => T): T = {
    val proxy = HttpProxy(maxStartupTime, auth)
    try {
      testCode(proxy)
    } finally {
      proxy.stop()
    }
  }
}
