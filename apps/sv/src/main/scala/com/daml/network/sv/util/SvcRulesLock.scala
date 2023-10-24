package com.daml.network.sv.util

import cats.data.EitherT
import cats.implicits.catsSyntaxApplicativeError
import com.daml.network.environment.RetryProvider
import com.daml.network.store.CNNodeAppStoreWithIngestion
import com.daml.network.sv.onboarding.SvcPartyHosting.{LockAcquireFailure, SvcPartyMigrationFailure}
import com.daml.network.sv.store.SvSvcStore
import com.digitalasset.canton.DiscardOps
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class SvcRulesLock(
    svcStoreWithIngestion: CNNodeAppStoreWithIngestion[SvSvcStore],
    override protected val loggerFactory: NamedLoggerFactory,
    retryProvider: RetryProvider,
)(implicit ec: ExecutionContext)
    extends NamedLogging {
  private val svcStore = svcStoreWithIngestion.store
  private val svParty = svcStore.key.svParty
  private val svcParty = svcStore.key.svcParty

  private def lock()(implicit tc: TraceContext) =
    for {
      svcRules <- svcStore.getSvcRules()
      coinRules <- svcStore.getCoinRules()
      res <- svcStoreWithIngestion.connection
        .submit(
          Seq(svParty),
          Seq(svcParty),
          svcRules.exercise(
            _.exerciseSvcRules_Lock(svParty.toProtoPrimitive, coinRules.contractId)
          ),
        )
        .noDedup
        .yieldResult()
    } yield res

  private def unlock()(implicit tc: TraceContext) = {
    retryProvider.retryForAutomation(
      "unlock", {
        for {
          svcRules <- svcStore.getSvcRules()
          coinRules <- svcStore.getCoinRules()
          res <- svcStoreWithIngestion.connection
            .submit(
              Seq(svParty),
              Seq(svcParty),
              svcRules.exercise(
                _.exerciseSvcRules_Unlock(svParty.toProtoPrimitive, coinRules.contractId)
              ),
            )
            .noDedup
            .yieldResult()
        } yield res
      },
      logger,
    )
  }

  def withLock[T](
      reason: String
  )(
      f: => EitherT[Future, SvcPartyMigrationFailure, T]
  )(implicit tc: TraceContext): EitherT[Future, SvcPartyMigrationFailure, T] = {
    logger.info(s"Locking SvcRules and CoinRules contracts before $reason")
    lock().attemptT.leftMap(LockAcquireFailure(reason, _)).flatMap { _ =>
      logger.info(s"Locked SvcRules and CoinRules contracts before $reason")
      val result = f
      result.value
        .andThen(result => {
          unlock().andThen {
            case Failure(exception) =>
              logger.warn(s"Failed to unlock SvcRules and CoinRules after $reason", exception)
            case Success(_) =>
              result match {
                case Failure(_) | Success(Left(_)) =>
                  logger.info(s"unlocked SvcRules and CoinRules contracts after $reason on failure")
                case _ =>
                  logger.info(s"unlocked SvcRules and CoinRules contracts after $reason")
              }
          }
        })
        .discard
      result
    }
  }
}
