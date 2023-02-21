package com.daml.network.svc.admin.grpc

import com.daml.ledger.api.v1.value.Record.toJavaProto
import com.daml.ledger.javaapi.data.DamlRecord
import com.daml.ledger.javaapi.data.codegen.PrimitiveValueDecoders
import com.daml.network.codegen.java.cc.coin.FeaturedAppRight
import com.daml.network.codegen.java.cc.coinconfig.CoinConfig
import com.daml.network.codegen.java.cc.coinconfig.USD
import com.daml.network.codegen.java.cc.schedule.Schedule
import com.daml.network.environment.{CoinLedgerClient, CoinLedgerConnection, DedupOffset}
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.svc.store.SvcStore
import com.daml.network.svc.v0.{
  GrantFeaturedAppRightRequest,
  GrantFeaturedAppRightResponse,
  JoinConsortiumRequest,
  SetConfigScheduleRequest,
  SvcServiceGrpc,
  WithdrawFeaturedAppRightRequest,
}
import com.daml.network.svc.v0
import com.daml.network.util.Proto
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.Spanning
import com.google.protobuf.empty.Empty
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class GrpcSvcAppService(
    ledgerClient: CoinLedgerClient,
    svcUserName: String,
    store: SvcStore,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends SvcServiceGrpc.SvcService
    with Spanning
    with NamedLogging {

  private val connection = ledgerClient.connection()

  override def getDebugInfo(request: Empty): Future[v0.GetDebugInfoResponse] =
    withSpanFromGrpcContext("GrpcSvcAppService") { _ => _ =>
      for {
        // TODO (#2705) Set domain id from config here.
        domainId <- store.domains.signalWhenConnected(store.defaultAcsDomain)
      } yield v0.GetDebugInfoResponse(
        svcUser = svcUserName,
        svcPartyId = Proto.encode(store.svcParty),
      )
    }

  /** Grant a featured app right to an app provider
    */
  override def grantFeaturedAppRight(
      request: GrantFeaturedAppRightRequest
  ): Future[GrantFeaturedAppRightResponse] =
    withSpanFromGrpcContext("GrpcSvcAppService") { implicit traceContext => _ =>
      val svcParty = store.svcParty
      val providerParty = PartyId.tryFromProtoPrimitive(request.appProvider)
      for {
        domainId <- store.domains.getUniqueDomainId()
        result <- store.lookupFeaturedAppByProviderWithOffset(request.appProvider).flatMap {
          case QueryResult(off, None) =>
            connection.submitWithResult(
              actAs = Seq(svcParty),
              readAs = Seq.empty,
              update = new FeaturedAppRight(
                store.svcParty.toProtoPrimitive,
                request.appProvider,
              ).create(),
              commandId = CoinLedgerConnection.CommandId(
                "com.daml.network.svc.grantFeaturedAppRight",
                Seq(svcParty, providerParty),
              ),
              deduplicationConfig = DedupOffset(off),
              domainId = domainId,
            )
          case QueryResult(_, Some(_)) =>
            logger.info("Rejecting duplicate featured app requests")
            throw new StatusRuntimeException(
              Status.ALREADY_EXISTS.withDescription(
                s"App provider ${request.appProvider} already has a featured app right"
              )
            )
        }
      } yield GrantFeaturedAppRightResponse(Proto.encodeContractId(result.contractId))
    }

  /** Withdraw a featured app right from an app provider, with a textual reasoning
    */
  override def withdrawFeaturedAppRight(request: WithdrawFeaturedAppRightRequest): Future[Empty] =
    withSpanFromGrpcContext("GrpcSvcAppService") { implicit traceContext => _ =>
      for {
        domainId <- store.domains.getUniqueDomainId()
        _ <- store.lookupFeaturedAppByProviderWithOffset(request.appProvider).flatMap {
          case QueryResult(_, None) =>
            throw new StatusRuntimeException(
              Status.NOT_FOUND.withDescription(
                s"No featured app right found for provider ${request.appProvider}"
              )
            )
          case QueryResult(_, Some(c)) =>
            connection.submitWithResultNoDedup(
              actAs = Seq(store.svcParty),
              readAs = Seq.empty,
              update = c.contractId.exerciseFeaturedAppRight_Withdraw(request.reason),
              domainId = domainId,
            )
        }
      } yield Empty()
    }

  // TODO(#2241) only needed for mock SVC bootstrap; remove after we have proper SVC bootstrap
  override def joinConsortium(request: JoinConsortiumRequest): Future[Empty] =
    withSpanFromGrpcContext("GrpcSvcAppService") { implicit traceContext => _ =>
      logger.info(s"Party ${request.svParty} wants to join the SV consortium")
      for {
        domainId <- store.domains.getUniqueDomainId()
        _ <- store.lookupSvcRules().flatMap {
          case None => {
            logger.info("SvcRules doesn't exist yet, waiting for the founding SV app to create it")
            throw new StatusRuntimeException(
              Status.FAILED_PRECONDITION.withDescription(
                s"Cannot join consortium for party ${request.svParty}, as its `SvcRules` don't exist yet."
              )
            )
          }
          case Some(svcRules) =>
            if (svcRules.payload.members.keySet.contains(request.svParty)) {
              logger.info("SvcRules exist and party is member; doing nothing")
              Future.successful(())
            } else {
              logger.info("SvcRules exist; adding party as member")
              connection.submitWithResultNoDedup(
                actAs = Seq(store.svcParty),
                readAs = Seq.empty,
                update =
                  svcRules.contractId.exerciseSvcRules_AddMember(request.svParty, "mock bootstrap"),
                domainId = domainId,
              )
            }
        },
      } yield Empty()
    }

  override def setConfigSchedule(request: SetConfigScheduleRequest): Future[Empty] =
    withSpanFromGrpcContext("GrpcSvcAppService") { implicit traceContext => _ =>
      for {
        domainId <- store.domains.getUniqueDomainId()
        coinRuleOpt <- store.lookupCoinRules()
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
            connection.submitWithResultNoDedup(
              actAs = Seq(store.svcParty),
              readAs = Seq.empty,
              update = coinRules.contractId
                .exerciseCoinRules_SetConfigSchedule(schedule),
              domainId = domainId,
            )
        }
      } yield Empty()
    }
}
