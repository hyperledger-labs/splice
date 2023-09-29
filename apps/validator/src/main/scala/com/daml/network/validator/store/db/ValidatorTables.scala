package com.daml.network.validator.store.db

import com.daml.ledger.javaapi.data.CreatedEvent
import com.daml.lf.data.Time.Timestamp
import com.daml.network.codegen.java.cc.{
  coin as coinCodegen,
  validatorlicense as validatorLicenseCodegen,
}
import com.daml.network.codegen.java.cn.appmanager.store as appManagerCodegen
import com.daml.network.codegen.java.cn.wallet.install as walletCodegen
import com.daml.network.codegen.java.cn.wallet.topupstate as topUpCodegen
import com.daml.network.store.db.AcsTables
import com.daml.network.util.Contract
import com.digitalasset.canton.topology.{DomainId, PartyId}

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
        case coinCodegen.Coin.COMPANION.TEMPLATE_ID =>
          tryToDecode(coinCodegen.Coin.COMPANION, createdEvent)(noIndex)
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

  val acsTableName = "validator_acs_store"
}
