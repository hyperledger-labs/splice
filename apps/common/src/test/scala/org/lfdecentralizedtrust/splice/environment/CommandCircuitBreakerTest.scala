package org.lfdecentralizedtrust.splice.environment

import org.apache.pekko.pattern.CircuitBreaker
import com.daml.ledger.javaapi.data.Command
import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.digitalasset.canton.{BaseTest, HasActorSystem, HasExecutionContext}
import com.digitalasset.canton.concurrent.{FutureSupervisor, Threading}
import com.digitalasset.canton.config.{
  NonNegativeDuration,
  NonNegativeFiniteDuration,
  ProcessingTimeout,
}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.{PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.{NoReportingTracerProvider, TraceContext}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.ValidatorRight
import org.lfdecentralizedtrust.splice.environment.ledger.api.{DedupConfig, LedgerClient}
import LedgerClient.SubmitAndWaitFor
import org.lfdecentralizedtrust.splice.util.{DisclosedContracts, SpliceCircuitBreaker}
import org.scalatest.wordspec.AsyncWordSpec

import io.grpc.{Status, StatusRuntimeException}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*

class CommandCircuitBreakerTest
    extends AsyncWordSpec
    with BaseTest
    with HasActorSystem
    with HasExecutionContext {
  val ledgerClient = mock[LedgerClient]

  val circuitBreaker = new SpliceCircuitBreaker(
    "test",
    new CircuitBreaker(
      actorSystem.scheduler,
      maxFailures = 5,
      callTimeout = 0.seconds,
      resetTimeout = 5.seconds,
      maxResetTimeout = 5.seconds,
      exponentialBackoffFactor = 2.0,
      randomFactor = 0.0,
    ),
  )

  val retryProvider = new RetryProvider(
    NamedLoggerFactory.root,
    ProcessingTimeout(),
    new FutureSupervisor.Impl(NonNegativeDuration.tryFromDuration(10.seconds))(scheduledExecutor()),
    NoOpMetricsFactory,
  )(NoReportingTracerProvider.tracer)

  def mockSubmitResult(result: => Future[Long]) = {
    when(
      ledgerClient.submitAndWait[Long](
        any[String],
        any[String],
        any[String],
        any[DedupConfig],
        any[Seq[String]],
        any[Seq[String]],
        any[Seq[Command]],
        any[DisclosedContracts],
        any[SubmitAndWaitFor[Long]],
        any[Option[NonNegativeFiniteDuration]],
        any[Seq[String]],
      )(any[ExecutionContext], any[TraceContext])
    ).thenReturn(result)
  }

  "SpliceLedgerConnection has a circuit breaker on command submissions" in {
    val connection = new SpliceLedgerConnection(
      ledgerClient,
      "dummy-user",
      loggerFactory,
      retryProvider,
      new AtomicReference(Seq.empty),
      new AtomicReference(Seq.empty),
      new AtomicReference(None),
      _ => Future.unit,
      circuitBreaker,
    )
    val alice = PartyId.tryFromProtoPrimitive("alice::namespace")
    val syncId = SynchronizerId.tryFromString("sync::namespace")
    mockSubmitResult(Future.successful(42L))
    val update = new ValidatorRight(
      alice.toProtoPrimitive,
      alice.toProtoPrimitive,
      alice.toProtoPrimitive,
    ).create
    for {
      _ <- connection
        .submit(
          Seq(alice),
          Seq.empty,
          update,
        )
        .withSynchronizerId(syncId)
        .noDedup
        .yieldUnit()
      _ = mockSubmitResult(Future.failed(Status.ABORTED.asRuntimeException))
      // Trigger enough failures to open the circuit breaker
      _ <- Future.sequence {
        (0 until 5).map { _ =>
          recoverToExceptionIf[StatusRuntimeException](
            connection
              .submit(
                Seq(alice),
                Seq.empty,
                update,
              )
              .withSynchronizerId(syncId)
              .noDedup
              .yieldUnit()
          ).map(ex => ex.getStatus shouldBe Status.ABORTED)
        }
      }
      // Circuit breaker now aborts even if call succeeds
      _ = mockSubmitResult(Future.successful(42L))
      circuitBreakerEx <- recoverToExceptionIf[StatusRuntimeException](
        connection
          .submit(
            Seq(alice),
            Seq.empty,
            update,
          )
          .withSynchronizerId(syncId)
          .noDedup
          .yieldUnit()
      )
      _ = circuitBreakerEx.getStatus.getDescription should include("aborted by circuit breaker")
      // Wait until the circuit breaker is closed again
      _ = Threading.sleep(6000)
      _ <- connection
        .submit(
          Seq(alice),
          Seq.empty,
          update,
        )
        .withSynchronizerId(syncId)
        .noDedup
        .yieldUnit()
    } yield succeed
  }
}
