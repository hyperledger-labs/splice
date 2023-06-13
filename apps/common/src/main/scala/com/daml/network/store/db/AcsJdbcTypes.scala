package com.daml.network.store.db

import com.daml.ledger.javaapi.data
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.lf.data.Ref.*
import com.daml.lf.data.Time.Timestamp
import com.digitalasset.canton.admin.api.client.data.TemplateId
import com.digitalasset.canton.config.CantonRequireTypes.String2066
import com.digitalasset.canton.ledger.offset.Offset
import com.digitalasset.canton.topology.{DomainId, PartyId}
import io.circe.Json
import slick.ast.FieldSymbol
import slick.jdbc.{GetResult, JdbcType, PositionedParameters, SetParameter}

import java.sql.{PreparedStatement, ResultSet}

trait AcsJdbcTypes {
  val profile: slick.jdbc.JdbcProfile

  import profile.api.*

  protected implicit lazy val byteArrayGetResult: GetResult[Array[Byte]] =
    GetResult { rs => rs.nextBytes() }

  protected implicit lazy val timestampJdbcType: JdbcType[Timestamp] =
    MappedColumnType.base[Timestamp, Long](_.micros, Timestamp.assertFromLong)

  protected implicit lazy val timestampGetResult: GetResult[Timestamp] =
    GetResult.GetLong.andThen(Timestamp.assertFromLong)

  protected implicit lazy val timestampGetResultOption: GetResult[Option[Timestamp]] =
    GetResult.GetLongOption.andThen(_.map(Timestamp.assertFromLong))

  protected implicit lazy val timestampSetParameter: SetParameter[Timestamp] =
    (ts: Timestamp, pp: PositionedParameters) => SetParameter.SetLong.apply(ts.micros, pp)

  protected implicit lazy val timestampSetParameterOption: SetParameter[Option[Timestamp]] =
    (ts: Option[Timestamp], pp: PositionedParameters) =>
      SetParameter.SetLongOption.apply(ts.map(_.micros), pp)

  protected implicit def contractIdJdbcType[T]: JdbcType[ContractId[T]] =
    MappedColumnType.base[ContractId[T], String](_.contractId, new ContractId[T](_))

  protected implicit def contractIdSetParameter[T]: SetParameter[ContractId[T]] =
    (c: ContractId[T], pp: PositionedParameters) =>
      implicitly[SetParameter[String2066]].apply(lengthLimited(c.contractId), pp)

  protected implicit def contractIdSetParameterOption[T]: SetParameter[Option[ContractId[T]]] =
    (cId: Option[ContractId[T]], pp: PositionedParameters) =>
      implicitly[SetParameter[Option[String2066]]]
        .apply(cId.map(c => lengthLimited(c.contractId)), pp)

  protected implicit def contractIdGetResult[T]: GetResult[ContractId[T]] =
    GetResult.GetString.andThen(new ContractId[T](_))

  protected implicit lazy val offsetJdbcType: JdbcType[Offset] =
    MappedColumnType.base[Offset, String](
      _.toHexString,
      s => Offset.fromHexString(HexString.assertFromString(s)),
    )

  protected implicit lazy val identifierSetParameter: SetParameter[Identifier] =
    (v1: Identifier, v2: PositionedParameters) =>
      implicitly[SetParameter[String2066]].apply(lengthLimited(v1.toString()), v2)

  protected implicit lazy val javaIdentifierSetParameter
      : SetParameter[com.daml.ledger.javaapi.data.Identifier] =
    (v1: data.Identifier, v2: PositionedParameters) =>
      identifierSetParameter.apply(
        Identifier(
          PackageId.assertFromString(v1.getPackageId),
          QualifiedName(
            ModuleName.assertFromString(v1.getModuleName),
            DottedName.assertFromString(v1.getEntityName),
          ),
        ),
        v2,
      )

  protected implicit lazy val templateIdJdbcType: JdbcType[TemplateId] =
    MappedColumnType.base[TemplateId, String](
      { case TemplateId(packageId, moduleName, entityName) =>
        Identifier(
          PackageId.assertFromString(packageId),
          QualifiedName(
            ModuleName.assertFromString(moduleName),
            DottedName.assertFromString(entityName),
          ),
        ).toString()
      },
      s => {
        val identifier = Identifier.assertFromString(s)
        TemplateId(
          identifier.packageId,
          identifier.qualifiedName.module.dottedName,
          identifier.qualifiedName.name.dottedName,
        )
      },
    )

  protected implicit val templateIdGetResult: GetResult[TemplateId] =
    GetResult.GetString.andThen { s =>
      val identifier = Identifier.assertFromString(s)
      TemplateId(
        identifier.packageId,
        identifier.qualifiedName.module.dottedName,
        identifier.qualifiedName.name.dottedName,
      )
    }

  protected implicit lazy val domainIdJdbcType: JdbcType[DomainId] =
    MappedColumnType.base[DomainId, String](_.toProtoPrimitive, DomainId.tryFromString)

  protected implicit lazy val partyIdJdbcType: JdbcType[PartyId] =
    MappedColumnType.base[PartyId, String](_.toProtoPrimitive, PartyId.tryFromProtoPrimitive)

  protected implicit lazy val partyIdSetParameterOption: SetParameter[Option[PartyId]] =
    (partyId: Option[PartyId], pp: PositionedParameters) =>
      implicitly[SetParameter[Option[String2066]]]
        .apply(partyId.map(party => lengthLimited(party.toProtoPrimitive)), pp)

  protected implicit lazy val jsonJdbcType: JdbcType[Json] = new profile.DriverJdbcType[Json]() {
    override def sqlType: Int = java.sql.Types.OTHER

    override def sqlTypeName(sym: Option[FieldSymbol]): String = "jsonb"

    override def setValue(v: Json, p: PreparedStatement, idx: Int): Unit =
      p.setObject(idx, v.noSpaces, java.sql.Types.OTHER)

    override def getValue(r: ResultSet, idx: Int): Json = {
      val value = r.getString(idx)
      if (r.wasNull())
        throw new IllegalStateException("Tried to deserialize as Json, but the value was null.")
      else
        io.circe.parser
          .parse(value)
          .getOrElse(throw new IllegalStateException("JSONB column didn't contain valid JSON."))
    }

    override def updateValue(v: Json, r: ResultSet, idx: Int): Unit =
      r.updateObject(idx, v.noSpaces, java.sql.Types.OTHER)

    override def valueToSQLLiteral(value: Json): String = s"'${value.noSpaces}'"
  }

  protected implicit lazy val jsonGetResult: GetResult[Json] = GetResult { rs =>
    val value = rs.nextString()
    if (rs.wasNull())
      throw new IllegalStateException("Tried to deserialize as Json, but the value was null.")
    else
      io.circe.parser
        .parse(value)
        .getOrElse(throw new IllegalStateException("JSONB column didn't contain valid JSON."))
  }

  protected implicit lazy val jsonSetParameter: SetParameter[Json] =
    (json: Json, pp: PositionedParameters) => {
      pp.setObject(json.noSpaces, java.sql.Types.OTHER)
    }

  /** The DB may truncate strings of unbounded length, so it's advised to use a LengthLimitedString instead.
    * We use String2066 because it's the max length of an [[com.digitalasset.canton.protocol.LfTemplateId]].
    */
  protected def lengthLimited(s: String): String2066 = String2066.tryCreate(s)

}
