package hello

import com.digitalasset.canton.config.RequireTypes.NonNegativeInt
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.RateLimiter

@SuppressWarnings(Array("org.wartremover.warts.Null"))
object Hello extends NamedLogging with App {
  override protected def loggerFactory: NamedLoggerFactory =
    NamedLoggerFactory.root

  println("Hello")
  val limiter = new RateLimiter(NonNegativeInt.tryCreate(5))

  logger.warn(s"This is a log message: ${limiter.maxTasksPerSecond}")(
    TraceContext.empty
  )
}
