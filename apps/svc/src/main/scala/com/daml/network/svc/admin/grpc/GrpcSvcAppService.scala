package com.daml.network.svc.admin.grpc

import com.daml.ledger.api.v1.value.Record.toJavaProto
import com.daml.ledger.javaapi.data.DamlRecord
import com.daml.ledger.javaapi.data.codegen.PrimitiveValueDecoders
import com.daml.network.store.CNNodeAppStoreWithIngestion
import com.daml.network.codegen.java.cc.coin.FeaturedAppRight
import com.daml.network.codegen.java.cc.coinconfig.{CoinConfig, USD}
import com.daml.network.codegen.java.cc.schedule.Schedule
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.environment.ledger.api.DedupOffset
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.svc.store.SvcStore
import com.daml.network.svc.v0
import com.daml.network.svc.v0.{
  GrantFeaturedAppRightRequest,
  GrantFeaturedAppRightResponse,
  SetConfigScheduleRequest,
  SvcServiceGrpc,
  WithdrawFeaturedAppRightRequest,
}
import com.daml.network.util.Codec
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.Spanning
import com.google.protobuf.empty.Empty
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class GrpcSvcAppService(
    svcUserName: String,
    svcStoreWithIngestion: CNNodeAppStoreWithIngestion[SvcStore],
    globalDomain: DomainId,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends SvcServiceGrpc.SvcService
    with Spanning
    with NamedLogging {

  private val svcStore = svcStoreWithIngestion.store

  override def getDebugInfo(request: Empty): Future[v0.GetDebugInfoResponse] =
    withSpanFromGrpcContext("GrpcSvcAppService") { _ => _ =>
      Future.successful(
        v0.GetDebugInfoResponse(
          svcUser = svcUserName,
          svcPartyId = Codec.encode(svcStore.svcParty),
        )
      )
    }

  /** Grant a featured app right to an app provider
    */
  override def grantFeaturedAppRight(
      request: GrantFeaturedAppRightRequest
  ): Future[GrantFeaturedAppRightResponse] =
    withSpanFromGrpcContext("GrpcSvcAppService") { implicit traceContext => _ =>
      val svcParty = svcStore.svcParty
      val providerParty = PartyId.tryFromProtoPrimitive(request.appProvider)
      for {
        result <- svcStore.lookupFeaturedAppByProviderWithOffset(request.appProvider).flatMap {
          case QueryResult(offset, None) =>
            svcStoreWithIngestion.connection.submitWithResult(
              actAs = Seq(svcParty),
              readAs = Seq.empty,
              update = new FeaturedAppRight(
                svcStore.svcParty.toProtoPrimitive,
                request.appProvider,
              ).create(),
              commandId = CNLedgerConnection.CommandId(
                "com.daml.network.svc.grantFeaturedAppRight",
                Seq(svcParty, providerParty),
              ),
              deduplicationConfig = DedupOffset(offset),
              domainId = globalDomain,
            )
          case QueryResult(_, Some(_)) =>
            logger.info("Rejecting duplicate featured app requests")
            throw new StatusRuntimeException(
              Status.ALREADY_EXISTS.withDescription(
                s"App provider ${request.appProvider} already has a featured app right"
              )
            )
        }
      } yield GrantFeaturedAppRightResponse(Codec.encodeContractId(result.contractId))
    }

  /** Withdraw a featured app right from an app provider, with a textual reasoning
    */
  override def withdrawFeaturedAppRight(request: WithdrawFeaturedAppRightRequest): Future[Empty] =
    withSpanFromGrpcContext("GrpcSvcAppService") { implicit traceContext => _ =>
      for {
        _ <- svcStore.lookupFeaturedAppByProviderWithOffset(request.appProvider).flatMap {
          case QueryResult(_, None) =>
            throw new StatusRuntimeException(
              Status.NOT_FOUND.withDescription(
                s"No featured app right found for provider ${request.appProvider}"
              )
            )
          case QueryResult(_, Some(c)) =>
            svcStoreWithIngestion.connection.submitWithResultNoDedup(
              actAs = Seq(svcStore.svcParty),
              readAs = Seq.empty,
              update = c.contractId.exerciseFeaturedAppRight_Withdraw(request.reason),
              domainId = globalDomain,
            )
        }
      } yield Empty()
    }

  override def setConfigSchedule(request: SetConfigScheduleRequest): Future[Empty] =
    withSpanFromGrpcContext("GrpcSvcAppService") { implicit traceContext => _ =>
      for {
        coinRuleOpt <- svcStore.lookupCoinRules()
        _ <- (request.schedule, coinRuleOpt) match {
          case (None, _) =>
            throw new StatusRuntimeException(
              Status.INVALID_ARGUMENT.withDescription("Config schedule is required")
            )
          case (_, None) =>
            throw new StatusRuntimeException(
              Status.FAILED_PRECONDITION.withDescription("CoinRules doesn't exist")
            )
          case (Some(configSchedule), Some(coinRules)) =>
            val record = DamlRecord.fromProto(toJavaProto(configSchedule))
            val schedule = Schedule
              .valueDecoder(
                PrimitiveValueDecoders.fromTimestamp,
                CoinConfig.valueDecoder(USD.valueDecoder()),
              )
              .decode(record)
            svcStoreWithIngestion.connection.submitWithResultNoDedup(
              actAs = Seq(svcStore.svcParty),
              readAs = Seq.empty,
              update = coinRules.contractId
                .exerciseCoinRules_SetConfigSchedule(schedule),
              domainId = globalDomain,
            )
        }
      } yield Empty()
    }
}
