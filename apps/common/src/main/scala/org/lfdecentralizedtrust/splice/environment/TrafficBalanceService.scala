// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.config.RequireTypes.NonNegativeLong
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.{Status, StatusRuntimeException}

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

trait TrafficBalanceService {

  /** Lookup the amount of traffic reserved for top-ups on the given domain.
    * Returns None if no traffic reservation has been configured for the domain.
    */
  def lookupReservedTraffic(domainId: DomainId): Future[Option[NonNegativeLong]]

  /** Lookup this member's available traffic balance on the given domain.
    * Returns None if no traffic state exists for this member.
    */
  def lookupAvailableTraffic(
      domainId: DomainId
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[Option[Long]]
}

object TrafficBalanceService {

  private trait AvailableTrafficLookupService {
    def lookupAvailableTraffic(
        domainId: DomainId
    )(implicit tc: TraceContext, ec: ExecutionContext): Future[Option[Long]]
  }

  private class ParticipantAdminConnectionTrafficLookupService(
      participantAdminConnection: ParticipantAdminConnection
  ) extends AvailableTrafficLookupService {
    override def lookupAvailableTraffic(
        domainId: DomainId
    )(implicit tc: TraceContext, ec: ExecutionContext): Future[Option[Long]] = {
      // Ideally we would just throw a NOT_FOUND error here if the traffic state does not exist
      // and have the caller retry it. However, currently the traffic state gets initialized in
      // the participant not when it connects to the sequencer but the first time it receives an
      // event from the sequencer.
      //
      // Using this traffic balance check to block command submissions while the traffic state is missing
      // has the potential to result in flaky tests if the participant has to rely on receiving events
      // triggered by other sequencer members as we are unable to submit commands via this participant.
      //
      // This is why we translate the NOT_FOUND into a None here and just ignore the traffic balance
      // check in this case.
      // TODO(#6644): Revisit this once Canton initializes the traffic state on sequencer connection.
      participantAdminConnection
        .getParticipantTrafficState(domainId: DomainId)
        .transform {
          case Success(value) => Success(Some(value.extraTrafficRemainder))
          case Failure(e: StatusRuntimeException)
              if e.getStatus.getCode == Status.NOT_FOUND.getCode =>
            Success(None)
          case Failure(e) => Failure(e)
        }
    }
  }

  private class CachedTrafficLookupService(
      trafficLookupService: AvailableTrafficLookupService,
      clock: Clock,
      trafficBalanceCacheTTL: NonNegativeFiniteDuration,
      override protected val loggerFactory: NamedLoggerFactory,
  ) extends AvailableTrafficLookupService
      with NamedLogging {

    private case class CachedTrafficBalance(
        cacheValidUntil: CantonTimestamp,
        trafficBalance: Long,
    )

    private val trafficBalanceCache: AtomicReference[Option[CachedTrafficBalance]] =
      new AtomicReference(None)

    override def lookupAvailableTraffic(
        domainId: DomainId
    )(implicit tc: TraceContext, ec: ExecutionContext): Future[Option[Long]] = {
      val now = clock.now
      trafficBalanceCache.get() match {
        case Some(CachedTrafficBalance(cacheValidUntil, trafficBalance))
            if now.isBefore(cacheValidUntil) =>
          logger.debug(s"Traffic balance cache hit. Fetched traffic balance as $trafficBalance")
          Future.successful(Some(trafficBalance))
        case _ =>
          for {
            trafficBalanceO <- trafficLookupService.lookupAvailableTraffic(domainId)
          } yield trafficBalanceO.map(trafficBalance => {
            trafficBalanceCache.set(
              Some(CachedTrafficBalance(now.add(trafficBalanceCacheTTL.asJava), trafficBalance))
            )
            logger.debug(s"Traffic balance cache miss. Fetched traffic balance as $trafficBalance")
            trafficBalance
          })
      }

    }
  }

  def apply(
      lookupReservedTrafficForDomain: DomainId => Future[Option[NonNegativeLong]],
      participantAdminConnection: ParticipantAdminConnection,
      clock: Clock,
      trafficBalanceCacheTTL: NonNegativeFiniteDuration,
      loggerFactory: NamedLoggerFactory,
  ): TrafficBalanceService = {
    val trafficLookupService = new CachedTrafficLookupService(
      new ParticipantAdminConnectionTrafficLookupService(
        participantAdminConnection
      ),
      clock,
      trafficBalanceCacheTTL,
      loggerFactory,
    )
    new TrafficBalanceService {
      override def lookupReservedTraffic(domainId: DomainId): Future[Option[NonNegativeLong]] =
        lookupReservedTrafficForDomain(domainId)

      override def lookupAvailableTraffic(
          domainId: DomainId
      )(implicit tc: TraceContext, ec: ExecutionContext): Future[Option[Long]] =
        trafficLookupService.lookupAvailableTraffic(domainId)
    }
  }

}
