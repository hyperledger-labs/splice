package com.daml.network.auth

import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.time.Clock
import org.mockito.ArgumentMatchersSugar
import org.scalatest.wordspec.AsyncWordSpec

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.concurrent.{Future, Promise}

object AuthTokenManagerTest extends org.mockito.MockitoSugar with ArgumentMatchersSugar {
  val mockClock = mock[Clock]
  when(mockClock.scheduleAt(any[CantonTimestamp => Unit], any[CantonTimestamp]))
    .thenReturn(FutureUnlessShutdown.unit)
}

class AuthTokenManagerTest extends AsyncWordSpec with BaseTest {

  val now = CantonTimestamp.Epoch
  val token1: AuthToken = AuthToken("foo", now.plusSeconds(20), None)
  val token2: AuthToken = AuthToken("bar", now.plusSeconds(40), None)

  "first call to getToken will obtain it" in {
    val (tokenManager, mock, _) = setup()

    mock.succeed(token1)

    for {
      _ <- tokenManager.getToken
    } yield mock.callCount shouldBe 1
  }

  "multiple calls to getToken before obtain has completed will return pending" in {
    val (tokenManager, mock, _) = setup()

    val call1 = tokenManager.getToken
    val call2 = tokenManager.getToken

    mock.succeed(token1)

    for {
      result1 <- call1.map(_.value)
      result2 <- call2.map(_.value)
    } yield {
      mock.callCount shouldBe 1
      result1 shouldEqual result2
    }
  }

  "getToken after error will cause refresh" in {
    val (tokenManager, mock, _) = setup()

    for {
      error1 <- loggerFactory.suppressWarningsAndErrors {
        val call1 = tokenManager.getToken
        mock.error()
        call1.map(_ shouldBe empty)
      }
      _ = mock.resetNextResult()
      call2 = tokenManager.getToken
      _ = mock.succeed(token1)
      result2 <- call2.map(_.value)
    } yield {
      result2 shouldBe nextToken(token1)
    }
  }

  "getToken after failure will cause refresh" in {
    val (tokenManager, mock, _) = setup()

    for {
      error1 <- loggerFactory.suppressWarningsAndErrors {
        val call1 = tokenManager.getToken
        mock.fail(new RuntimeException("uh oh"))

        call1.failed.map(_.getMessage)
      }
      _ = error1 shouldBe "uh oh"
      _ = mock.resetNextResult()
      call2 = tokenManager.getToken
      _ = mock.succeed(token1)
      result2 <- call2.map(_.value)
    } yield result2 shouldBe nextToken(token1)
  }

  "automatically renew token in due time" in {
    val clockMock = mock[Clock]
    val retryMe = new AtomicReference[Option[CantonTimestamp => Unit]](None)
    when(clockMock.scheduleAt(any[CantonTimestamp => Unit], any[CantonTimestamp]))
      .thenAnswer[CantonTimestamp => Unit, CantonTimestamp] { case (action, _) =>
        logger.debug(s"schedule at called, ${retryMe.get()}")
        retryMe.getAndUpdate(_ => Some(action)) shouldBe empty
        FutureUnlessShutdown.unit
      }

    val (tokenManager, obtainMock, _) = setup(Some(clockMock))
    val call1 = clue("get token1") {
      tokenManager.getToken
    }
    clue("succeed with token1") { obtainMock.succeed(token1) }
    for {
      // wait for token to succeed
      t1 <- call1.map(_.value)
      _ = retryMe.get() should not be empty
      _ = obtainMock.resetNextResult()
      // now, invoke the scheduled renewal
      _ = retryMe.get().value.apply(CantonTimestamp.Epoch)
      // obtain intermediate result
      t2f = tokenManager.getToken
      // subsequent getToken will result in another scheduled renewal
      _ = retryMe.set(None)
      // satisfy this request
      _ = obtainMock.succeed(token2)
      t3 <- tokenManager.getToken.map(_.value)
      t2 <- t2f.map(_.value)
    } yield {
      t1 shouldBe nextToken(token1)
      t2 shouldBe nextToken(token2)
      t3 shouldBe nextToken(token2)
    }
  }

  private def setup(
      clockO: Option[Clock] = None
  ): (AuthTokenManager, ObtainTokenMock, Clock) = {
    val mck = new ObtainTokenMock
    val clock = clockO.getOrElse(AuthTokenManagerTest.mockClock)
    val tokenManager = new AuthTokenManager(
      () => mck.obtain(),
      false,
      clock,
      loggerFactory,
    )
    (tokenManager, mck, clock)
  }

  def nextToken(token: AuthToken): AuthToken = token.copy(expiresAt = now.plusSeconds(100))

  private class ObtainTokenMock {
    private val callCounter = new AtomicInteger()
    private val nextResult = new AtomicReference[Promise[Option[AuthToken]]]()

    resetNextResult()

    def callCount: Int = callCounter.get()

    def obtain(): Future[Option[AuthToken]] = {
      callCounter.incrementAndGet()

      nextResult.get.future.map(
        _.map(nextToken)
      )
    }

    def resetNextResult(): Unit = {
      nextResult.set(Promise[Option[AuthToken]]())
    }

    def succeed(token: AuthToken): Unit = {
      logger.debug(s"returning success from obtain token: $token")
      nextResult.get().success(Some(token))
    }

    def error(): Unit = {
      nextResult.get().success(None)
    }

    def fail(throwable: Throwable): Unit = {
      nextResult.get().failure(throwable)
    }
  }
}
