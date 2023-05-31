import $file.BootstrapXNodesShared
import com.digitalasset.canton.concurrent.Threading

import scala.concurrent.{Future, blocking, ExecutionContext}
import java.util.concurrent.atomic.AtomicBoolean

// Need to wrap the whole script in a main function to make the usage of futures in ammonite work
// https://github.com/com-lihaoyi/Ammonite/issues/534
object Simtime {

  val tickTheClockRef: AtomicBoolean = new AtomicBoolean()

  def startTickingTheClock(intervalMillis: Int = 100): Future[Unit] = Future {
    blocking {
      tickTheClockRef.set(true)
      while (tickTheClockRef.get()) {
        clock.advance(java.time.Duration.ofMillis(intervalMillis))
        Threading.sleep(intervalMillis)
      }
    }
  }(ExecutionContext.global)

  def stopTickingTheClock(): Unit = tickTheClockRef.set(false)

}

Simtime.startTickingTheClock()
val topo = new BootstrapXNodesShared.BootstrappedTopology()
