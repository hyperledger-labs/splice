package com.daml.network.validator.store.db

import com.daml.ledger.javaapi.data.CreatedEvent
import com.daml.lf.data.Time.Timestamp
import com.daml.network.codegen.java.cc.{
  coin as coinCodegen,
  coinrules as coinrulesCodegen,
  validatorlicense as validatorLicenseCodegen,
}
import com.daml.network.codegen.java.cn.appmanager.store as appManagerCodegen
import com.daml.network.codegen.java.cn.wallet.install as walletCodegen
import com.daml.network.codegen.java.cn.wallet.topupstate as topUpCodegen
import com.daml.network.store.db.{AcsRowData, AcsTables, IndexColumnValue}
import com.daml.network.util.{Contract, QualifiedName}
import com.daml.network.http.v0.definitions
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.google.protobuf.ByteString
import cats.syntax.either.*

object ValidatorTables extends AcsTables {

  case class ValidatorAcsStoreRowData(
      contract: Contract[?, ?],
      contractExpiresAt: Option[Timestamp],
      userParty: Option[PartyId] = None,
      userName: Option[String] = None,
      providerParty: Option[PartyId] = None,
      validatorParty: Option[PartyId] = None,
      trafficDomainId: Option[DomainId] = None,
      appConfigurationVersion: Option[Long] = None,
      appConfigurationName: Option[String] = None,
      appReleaseVersion: Option[String] = None,
      jsonHash: Option[String] = None,
  ) extends AcsRowData {
    override def indexColumns: Seq[(String, IndexColumnValue[?])] = Seq(
      "user_party" -> userParty,
      "user_name" -> userName.map(lengthLimited),
      "provider_party" -> providerParty,
      "validator_party" -> validatorParty,
      "traffic_domain_id" -> trafficDomainId,
      "app_configuration_version" -> appConfigurationVersion,
      "app_configuration_name" -> appConfigurationName.map(lengthLimited),
      "app_release_version" -> appReleaseVersion.map(lengthLimited),
      "json_hash" -> jsonHash.map(lengthLimited),
    )
  }

  object ValidatorAcsStoreRowData {
    def fromCreatedEvent(
        createdEvent: CreatedEvent,
        createdEventBlob: ByteString,
    ): Either[String, ValidatorAcsStoreRowData] = {
      def noIndex(contract: Contract[?, ?]) =
        ValidatorAcsStoreRowData(
          contract = contract,
          contractExpiresAt = None,
        )
      // TODO(#8125) Switch to map lookups instead
      QualifiedName(createdEvent.getTemplateId) match {
        case t if t == QualifiedName(walletCodegen.WalletAppInstall.TEMPLATE_ID) =>
          tryToDecode(walletCodegen.WalletAppInstall.COMPANION, createdEvent, createdEventBlob)(
            contract =>
              ValidatorAcsStoreRowData(
                contract = contract,
                contractExpiresAt = None,
                userParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.endUserParty)),
                userName = Some(contract.payload.endUserName),
              )
          )
        case t if t == QualifiedName(coinrulesCodegen.CoinRules.TEMPLATE_ID) =>
          tryToDecode(coinrulesCodegen.CoinRules.COMPANION, createdEvent, createdEventBlob)(noIndex)
        case t if t == QualifiedName(coinCodegen.Coin.COMPANION.TEMPLATE_ID) =>
          tryToDecode(coinCodegen.Coin.COMPANION, createdEvent, createdEventBlob)(noIndex)
        case t if t == QualifiedName(validatorLicenseCodegen.ValidatorLicense.TEMPLATE_ID) =>
          tryToDecode(
            validatorLicenseCodegen.ValidatorLicense.COMPANION,
            createdEvent,
            createdEventBlob,
          )(contract =>
            ValidatorAcsStoreRowData(
              contract = contract,
              contractExpiresAt = None,
              validatorParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.validator)),
            )
          )
        case t if t == QualifiedName(coinCodegen.ValidatorRight.TEMPLATE_ID) =>
          tryToDecode(coinCodegen.ValidatorRight.COMPANION, createdEvent, createdEventBlob)(
            contract =>
              ValidatorAcsStoreRowData(
                contract = contract,
                contractExpiresAt = None,
                userParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.user)),
                validatorParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.validator)),
              )
          )
        case t if t == QualifiedName(coinCodegen.FeaturedAppRight.TEMPLATE_ID) =>
          tryToDecode(coinCodegen.FeaturedAppRight.COMPANION, createdEvent, createdEventBlob)(
            contract =>
              ValidatorAcsStoreRowData(
                contract = contract,
                contractExpiresAt = None,
                providerParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.provider)),
              )
          )
        case t if t == QualifiedName(appManagerCodegen.AppConfiguration.TEMPLATE_ID) =>
          for {
            contract <- tryToDecode(
              appManagerCodegen.AppConfiguration.COMPANION,
              createdEvent,
              createdEventBlob,
            )(
              identity
            )
            name <- io.circe.parser
              .decode[definitions.AppConfiguration](contract.payload.json)
              .map(_.name)
              .leftMap(_ => s"Failed to extract name from ${contract.payload.json}")
          } yield ValidatorAcsStoreRowData(
            contract = contract,
            contractExpiresAt = None,
            providerParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.provider)),
            appConfigurationVersion = Some(contract.payload.version),
            appConfigurationName = Some(name),
          )
        case t if t == QualifiedName(appManagerCodegen.AppRelease.TEMPLATE_ID) =>
          tryToDecode(appManagerCodegen.AppRelease.COMPANION, createdEvent, createdEventBlob)(
            contract =>
              ValidatorAcsStoreRowData(
                contract = contract,
                contractExpiresAt = None,
                providerParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.provider)),
                appReleaseVersion = Some(contract.payload.version),
              )
          )
        case t if t == QualifiedName(appManagerCodegen.RegisteredApp.TEMPLATE_ID) =>
          tryToDecode(appManagerCodegen.RegisteredApp.COMPANION, createdEvent, createdEventBlob)(
            contract =>
              ValidatorAcsStoreRowData(
                contract = contract,
                contractExpiresAt = None,
                providerParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.provider)),
              )
          )
        case t if t == QualifiedName(appManagerCodegen.InstalledApp.TEMPLATE_ID) =>
          tryToDecode(appManagerCodegen.InstalledApp.COMPANION, createdEvent, createdEventBlob)(
            contract =>
              ValidatorAcsStoreRowData(
                contract = contract,
                contractExpiresAt = None,
                providerParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.provider)),
              )
          )
        case t if t == QualifiedName(appManagerCodegen.ApprovedReleaseConfiguration.TEMPLATE_ID) =>
          tryToDecode(
            appManagerCodegen.ApprovedReleaseConfiguration.COMPANION,
            createdEvent,
            createdEventBlob,
          )(contract =>
            ValidatorAcsStoreRowData(
              contract = contract,
              contractExpiresAt = None,
              providerParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.provider)),
              jsonHash = Some(contract.payload.jsonHash),
            )
          )
        case t if t == QualifiedName(topUpCodegen.ValidatorTopUpState.TEMPLATE_ID) =>
          tryToDecode(topUpCodegen.ValidatorTopUpState.COMPANION, createdEvent, createdEventBlob)(
            contract =>
              ValidatorAcsStoreRowData(
                contract = contract,
                contractExpiresAt = None,
                trafficDomainId = Some(DomainId.tryFromString(contract.payload.domainId)),
              )
          )
        case t =>
          Left(s"Template $t cannot be decoded as an entry for the validator store.")
      }
    }
  }

  val acsTableName = "validator_acs_store"
}
