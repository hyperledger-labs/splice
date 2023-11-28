package com.daml.network.store

import org.apache.pekko.Done
import com.daml.ledger.javaapi.data
import com.daml.ledger.javaapi.data.codegen.{ContractId, DamlRecord}
import com.daml.lf.data.Ref
import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cc.coinimport.importpayload.IP_ValidatorLicense
import com.daml.network.environment.{CNLedgerConnection, CommandPriority, RetryFor, RetryProvider}
import com.daml.network.http.v0.definitions as http
import com.daml.network.util.Contract.Companion
import com.daml.network.util.{
  AssignedContract,
  Contract,
  ContractWithState,
  DisclosedContracts,
  TemplateJsonDecoder,
}
import com.digitalasset.canton.ProtoDeserializationError
import com.digitalasset.canton.logging.TracedLogger
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*

import scala.concurrent.{ExecutionContext, Future}

object AcsStoreDump {

  /** A shipment of crates ready for import -- sorry for the bad pun :D */
  case class ImportShipment(
      openRound: AssignedContract[
        cc.round.OpenMiningRound.ContractId,
        cc.round.OpenMiningRound,
      ],
      crates: Seq[
        ContractWithState[cc.coinimport.ImportCrate.ContractId, cc.coinimport.ImportCrate]
      ],
  )
  private def fromJsonIgnoringPackageId[TCid <: ContractId[T], T <: DamlRecord[?]](
      companion: Companion.Template[TCid, T]
  )(contract: http.Contract)(implicit
      decoder: TemplateJsonDecoder
  ): Either[ProtoDeserializationError, Contract[TCid, T]] = {
    val fixedPackageId = Ref.PackageId.assertFromString(companion.TEMPLATE_ID.getPackageId)
    // fixup templateId
    val templateId = Ref.Identifier.assertFromString(contract.templateId)
    val fixedTemplateId = Ref.Identifier(
      fixedPackageId,
      templateId.qualifiedName,
    )
    val fixedContract = contract.copy(templateId = fixedTemplateId.toString())
    Contract.fromHttp(companion)(fixedContract)
  }

  def extractOpenMiningRounds(
      contracts: Seq[http.Contract]
  )(implicit templateDecoder: TemplateJsonDecoder): Seq[cc.round.OpenMiningRound] =
    contracts.collect(
      Function.unlift(co =>
        fromJsonIgnoringPackageId(cc.round.OpenMiningRound.COMPANION)(co).toOption
          .map(_.payload)
      )
    )

  def extractImportCommands(svcParty: PartyId)(
      contracts: Seq[http.Contract]
  )(implicit
      templateDecoder: TemplateJsonDecoder
  ): Seq[data.codegen.HasCommands] = {

    def extractCoin(co: http.Contract): Seq[cc.coin.Coin] =
      // attempt to decode as a: Coin
      fromJsonIgnoringPackageId(cc.coin.Coin.COMPANION)(co)
        .map(_.payload)
        // LockedCoin
        .orElse(fromJsonIgnoringPackageId(cc.coin.LockedCoin.COMPANION)(co).map(_.payload.coin))
        .toSeq

    val coinCommands = for {
      httpCo <- contracts
      coin <- extractCoin(httpCo)
    } yield new cc.coinimport.ImportCrate(
      svcParty.toProtoPrimitive,
      coin.owner,
      new cc.coinimport.importpayload.IP_Coin(
        coin
      ),
    )

    val validatorLicenseCommands = for {
      httpCo <- contracts
      license <- fromJsonIgnoringPackageId(cc.validatorlicense.ValidatorLicense.COMPANION)(
        httpCo
      ).toSeq
    } yield new cc.coinimport.ImportCrate(
      svcParty.toProtoPrimitive,
      license.payload.validator,
      new IP_ValidatorLicense(license.payload),
    )

    val importCrateCommands = for {
      httpCo <- contracts
      crate <- fromJsonIgnoringPackageId(cc.coinimport.ImportCrate.COMPANION)(httpCo).toSeq
    } yield new cc.coinimport.ImportCrate(
      svcParty.toProtoPrimitive, // override the svc party to the current one
      crate.payload.receiver, // keep as-is
      crate.payload.payload, // keep as-is
    )

    (coinCommands ++ validatorLicenseCommands ++ importCrateCommands)
      .map(_.create())
  }

  def receiveCratesFor(
      party: PartyId,
      getImportShipment: (PartyId, TraceContext) => Future[ImportShipment],
      ledgerConnection: CNLedgerConnection,
      retryProvider: RetryProvider,
      logger: TracedLogger,
      priority: CommandPriority = CommandPriority.Low,
  )(implicit
      ec: ExecutionContext
  ): Future[Done] = TraceContext.withNewTraceContext(implicit tc => {

    // Note: we receive the crates using individual commands that we submit one-by-one
    // so that we can make progress even if the domain fees only allow us to receive one crate after the other.
    def receiveCrates(shipment: ImportShipment): Future[Done] =
      shipment.crates match {
        case crate +: otherCrates =>
          logger.debug(show"Attempting to receive $crate")
          ledgerConnection
            .submit(
              actAs = Seq(party),
              readAs = Seq.empty,
              crate.exercise(
                _.exerciseImportCrate_Receive(
                  party.toProtoPrimitive,
                  shipment.openRound.contractId,
                )
              ),
              priority = priority,
            )
            .withDisclosedContracts(
              DisclosedContracts(crate, shipment.openRound.toContractWithState)
            )
            .noDedup
            .yieldUnit()
            .flatMap(_ => receiveCrates(shipment.copy(crates = otherCrates)))
        case _ => Future.successful(Done)
      }

    def getAndReceiveShipment(): Future[Done] = for {
      shipment <- getImportShipment(party, tc)
      _ = logger.debug(
        show"Attempting to receive ${shipment.crates.size} crates on ${shipment.openRound.domain} for $party"
      )
      case Done <- receiveCrates(shipment)
    } yield {
      logger.info(show"Received ${shipment.crates.size} crates for $party")
      Done
    }

    retryProvider
      .retry(
        RetryFor.WaitingOnInitDependency,
        show"receive shipment of crates for $party",
        getAndReceiveShipment(),
        logger,
      )
      .transform {
        case scala.util.Success(_) => scala.util.Success(Done)
        case scala.util.Failure(ex) if retryProvider.isClosing =>
          logger.debug(
            s"Ignoring exception when receiving crates for $party, as we are shutting down",
            ex,
          )
          scala.util.Success(Done)
        case scala.util.Failure(ex) =>
          logger.error(
            s"Unexpected exception when receiving crates for $party",
            ex,
          )
          scala.util.Failure(ex)
      }
  })
}
