package com.daml.network.validator.store.db

import com.daml.ledger.javaapi.data.CreatedEvent
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.lf.data.Time.Timestamp
import com.daml.network.codegen.java.cc.{
  coin as coinCodegen,
  validatorlicense as validatorLicenseCodegen,
}
import com.daml.network.codegen.java.cc.globaldomain as domainCodegen
import com.daml.network.codegen.java.cn.appmanager.store as appManagerCodegen
import com.daml.network.codegen.java.cn.wallet.install as walletCodegen
import com.daml.network.codegen.java.cn.wallet.topupstate as topUpCodegen
import com.daml.network.store.db.AcsTables
import com.daml.network.store.db.AcsTables.AcsStoreTemplate
import com.daml.network.util.Contract
import com.digitalasset.canton.admin.api.client.data.TemplateId
import com.digitalasset.canton.topology.{DomainId, PartyId}
import io.circe.Json
import shapeless.HNil

object ValidatorTables extends AcsTables {
  import profile.api.*

  lazy val schema: profile.SchemaDescription = acsBaseSchema ++ ValidatorAcsStore.schema

  case class ValidatorAcsStoreRow(
      storeId: Int,
      eventNumber: Long,
      contractId: ContractId[Any],
      templateId: TemplateId,
      createArguments: Json,
      contractMetadataCreatedAt: Timestamp,
      contractMetadataContractKeyHash: Option[String] = None,
      contractMetadataDriverInternal: Array[Byte],
      contractExpiresAt: Option[Timestamp] = None,
      // Index columns
      userParty: Option[PartyId] = None,
      userName: Option[String] = None,
      providerParty: Option[PartyId] = None,
      validatorParty: Option[PartyId] = None,
      trafficDomainId: Option[DomainId] = None,
      appConfigurationVersion: Option[Long] = None,
      appReleaseVersion: Option[String] = None,
      jsonHash: Option[String] = None,
  )

  case class ValidatorAcsStoreRowData(
      contract: Contract[?, ?],
      contractExpiresAt: Option[Timestamp],
      userParty: Option[PartyId] = None,
      userName: Option[String] = None,
      providerParty: Option[PartyId] = None,
      validatorParty: Option[PartyId] = None,
      trafficDomainId: Option[DomainId] = None,
      appConfigurationVersion: Option[Long] = None,
      appReleaseVersion: Option[String] = None,
      jsonHash: Option[String] = None,
  )

  object ValidatorAcsStoreRowData {
    def fromCreatedEvent(
        createdEvent: CreatedEvent
    ): Either[String, ValidatorAcsStoreRowData] = {
      def noIndex(contract: Contract[?, ?]) =
        ValidatorAcsStoreRowData(
          contract = contract,
          contractExpiresAt = None,
        )

      createdEvent.getTemplateId match {
        case walletCodegen.WalletAppInstall.TEMPLATE_ID =>
          tryToDecode(walletCodegen.WalletAppInstall.COMPANION, createdEvent)(contract =>
            ValidatorAcsStoreRowData(
              contract = contract,
              contractExpiresAt = None,
              userParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.endUserParty)),
              userName = Some(contract.payload.endUserName),
            )
          )
        case coinCodegen.CoinRules.TEMPLATE_ID =>
          tryToDecode(coinCodegen.CoinRules.COMPANION, createdEvent)(noIndex)
        case validatorLicenseCodegen.ValidatorLicense.TEMPLATE_ID =>
          tryToDecode(validatorLicenseCodegen.ValidatorLicense.COMPANION, createdEvent)(contract =>
            ValidatorAcsStoreRowData(
              contract = contract,
              contractExpiresAt = None,
              validatorParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.validator)),
            )
          )
        case coinCodegen.ValidatorRight.TEMPLATE_ID =>
          tryToDecode(coinCodegen.ValidatorRight.COMPANION, createdEvent)(contract =>
            ValidatorAcsStoreRowData(
              contract = contract,
              contractExpiresAt = None,
              userParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.user)),
              validatorParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.validator)),
            )
          )
        case coinCodegen.FeaturedAppRight.TEMPLATE_ID =>
          tryToDecode(coinCodegen.FeaturedAppRight.COMPANION, createdEvent)(contract =>
            ValidatorAcsStoreRowData(
              contract = contract,
              contractExpiresAt = None,
              providerParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.provider)),
            )
          )
        case domainCodegen.ValidatorTraffic.TEMPLATE_ID =>
          tryToDecode(domainCodegen.ValidatorTraffic.COMPANION, createdEvent)(contract =>
            ValidatorAcsStoreRowData(
              contract = contract,
              contractExpiresAt = None,
              validatorParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.validator)),
              trafficDomainId = Some(DomainId.tryFromString(contract.payload.domainId)),
            )
          )
        case domainCodegen.ValidatorTrafficCreationIntent.TEMPLATE_ID =>
          tryToDecode(domainCodegen.ValidatorTrafficCreationIntent.COMPANION, createdEvent)(
            contract =>
              ValidatorAcsStoreRowData(
                contract = contract,
                contractExpiresAt = None,
                validatorParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.validator)),
                trafficDomainId = Some(DomainId.tryFromString(contract.payload.domainId)),
              )
          )
        case appManagerCodegen.AppConfiguration.TEMPLATE_ID =>
          tryToDecode(appManagerCodegen.AppConfiguration.COMPANION, createdEvent)(contract =>
            ValidatorAcsStoreRowData(
              contract = contract,
              contractExpiresAt = None,
              providerParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.provider)),
              appConfigurationVersion = Some(contract.payload.version),
            )
          )
        case appManagerCodegen.AppRelease.TEMPLATE_ID =>
          tryToDecode(appManagerCodegen.AppRelease.COMPANION, createdEvent)(contract =>
            ValidatorAcsStoreRowData(
              contract = contract,
              contractExpiresAt = None,
              providerParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.provider)),
              appReleaseVersion = Some(contract.payload.version),
            )
          )
        case appManagerCodegen.RegisteredApp.TEMPLATE_ID =>
          tryToDecode(appManagerCodegen.RegisteredApp.COMPANION, createdEvent)(contract =>
            ValidatorAcsStoreRowData(
              contract = contract,
              contractExpiresAt = None,
              providerParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.provider)),
            )
          )
        case appManagerCodegen.InstalledApp.TEMPLATE_ID =>
          tryToDecode(appManagerCodegen.InstalledApp.COMPANION, createdEvent)(contract =>
            ValidatorAcsStoreRowData(
              contract = contract,
              contractExpiresAt = None,
              providerParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.provider)),
            )
          )
        case appManagerCodegen.ApprovedReleaseConfiguration.TEMPLATE_ID =>
          tryToDecode(appManagerCodegen.ApprovedReleaseConfiguration.COMPANION, createdEvent)(
            contract =>
              ValidatorAcsStoreRowData(
                contract = contract,
                contractExpiresAt = None,
                providerParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.provider)),
                jsonHash = Some(contract.payload.jsonHash),
              )
          )
        case topUpCodegen.ValidatorTopUpState.TEMPLATE_ID =>
          tryToDecode(topUpCodegen.ValidatorTopUpState.COMPANION, createdEvent)(contract =>
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

  class ValidatorAcsStore(_tableTag: Tag)
      extends AcsStoreTemplate[ValidatorAcsStoreRow](_tableTag, "validator_acs_store") {
    def * =
      (templateColumns :::
        userParty ::
        userName ::
        providerParty ::
        validatorParty ::
        trafficDomainId ::
        appConfigurationVersion ::
        appReleaseVersion ::
        jsonHash ::
        HNil).tupled
        .<>(ValidatorAcsStoreRow.tupled, ValidatorAcsStoreRow.unapply)

    val userParty: Rep[Option[PartyId]] =
      column[Option[PartyId]]("user_party", O.Default(None))

    val userName: Rep[Option[String]] =
      column[Option[String]]("user_name", O.Default(None))

    val providerParty: Rep[Option[PartyId]] =
      column[Option[PartyId]]("provider_party", O.Default(None))

    val validatorParty: Rep[Option[PartyId]] =
      column[Option[PartyId]]("validator_party", O.Default(None))

    val trafficDomainId: Rep[Option[DomainId]] =
      column[Option[DomainId]]("traffic_domain_id", O.Default(None))

    val appConfigurationVersion: Rep[Option[Long]] =
      column[Option[Long]]("app_configuration_version", O.Default(None))

    val appReleaseVersion: Rep[Option[String]] =
      column[Option[String]]("app_release_version", O.Default(None))

    val jsonHash: Rep[Option[String]] =
      column[Option[String]]("json_hash", O.Default(None))
  }

  lazy val ValidatorAcsStore = new TableQuery(tag => new ValidatorAcsStore(tag))
}
