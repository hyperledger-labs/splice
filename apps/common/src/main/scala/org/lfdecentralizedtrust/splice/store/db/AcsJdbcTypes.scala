// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store.db

import com.daml.ledger.javaapi.data.{CreatedEvent, Identifier}
import com.daml.ledger.javaapi.data.codegen.json.JsonLfWriter
import com.daml.ledger.javaapi.data.codegen.{ContractId, DamlRecord, DefinedDataType}
import com.digitalasset.daml.lf.data.Ref.HexString
import com.digitalasset.daml.lf.data.Time.Timestamp
import org.lfdecentralizedtrust.splice.util.Contract
import org.lfdecentralizedtrust.splice.util.Contract.Companion
import org.lfdecentralizedtrust.splice.util.QualifiedName
import com.digitalasset.canton.config.CantonRequireTypes.{String2066, String300}
import com.digitalasset.canton.data.Offset
import com.digitalasset.canton.topology.{DomainId, Member, PartyId}
import io.circe.Json
import io.circe.parser.parse as circeParse
import slick.ast.FieldSymbol
import slick.jdbc.{GetResult, JdbcType, PositionedParameters, PositionedResult, SetParameter}

import java.sql.{JDBCType, PreparedStatement, ResultSet}
import com.google.protobuf.ByteString

import java.io.StringWriter

trait AcsJdbcTypes {
  import AcsJdbcTypes.JsonString

  val profile: slick.jdbc.JdbcProfile

  import profile.api.*

  protected implicit lazy val byteArrayGetResult: GetResult[Array[Byte]] =
    GetResult { rs => rs.nextBytes() }

  protected implicit def byteStringSetParameter(implicit
      setParameterByteArray: SetParameter[Array[Byte]]
  ): SetParameter[ByteString] =
    (bs: ByteString, pp: PositionedParameters) => setParameterByteArray.apply(bs.toByteArray, pp)

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

  protected implicit def contractIdSetParameter: SetParameter[ContractId[?]] =
    (c: ContractId[?], pp: PositionedParameters) =>
      implicitly[SetParameter[String2066]].apply(lengthLimited(c.contractId), pp)

  protected implicit def contractIdSetParameterOption: SetParameter[Option[ContractId[?]]] =
    (cId: Option[ContractId[?]], pp: PositionedParameters) =>
      implicitly[SetParameter[Option[String2066]]]
        .apply(cId.map(c => lengthLimited(c.contractId)), pp)

  protected implicit def contractIdGetResult[T]: GetResult[ContractId[T]] =
    GetResult.GetString.andThen(new ContractId[T](_))

  protected implicit def contractIdGetResultOption[T]: GetResult[Option[ContractId[T]]] =
    GetResult.GetStringOption.andThen(_.map(new ContractId[T](_)))

  protected implicit def contractIdArrayGetResult[T]: GetResult[Array[ContractId[T]]] =
    stringArrayGetResult.andThen(_.map(new ContractId[T](_)))

  protected implicit lazy val stringArrayGetResult: GetResult[Array[String]] =
    (r: PositionedResult) => {
      (r.rs
        .getArray(r.skip.currentPos)
        .getArray match {
        case arr: Array[String] =>
          arr
        case x =>
          throw new IllegalStateException(
            s"Expected an array of strings, but got $x. Are you sure you selected a text array column?"
          )
      })
    }

  protected implicit lazy val stringArrayOptGetResult: GetResult[Option[Array[String]]] =
    (r: PositionedResult) => {
      Option(r.rs.getArray(r.skip.currentPos)).map {
        _.getArray match {
          case arr: Array[String] =>
            arr
          case x =>
            throw new IllegalStateException(
              s"Expected an optional array of strings, but got $x. Are you sure you selected a text array column?"
            )
        }
      }
    }

  protected implicit lazy val stringSeqGetResult: GetResult[Seq[String]] =
    stringArrayGetResult.andThen(_.toSeq)

  protected implicit lazy val stringSeqOptGetResult: GetResult[Option[Seq[String]]] =
    stringArrayOptGetResult.andThen(_.map(_.toSeq))

  private val stringArraySetParameter: SetParameter[Array[String]] =
    (strings: Array[String], pp: PositionedParameters) =>
      pp.setObject(
        pp.ps.getConnection.createArrayOf("text", strings.map(x => x)),
        JDBCType.ARRAY.getVendorTypeNumber,
      )

  protected implicit lazy val string2066ArraySetParameter: SetParameter[Array[String2066]] =
    (strings: Array[String2066], pp: PositionedParameters) =>
      stringArraySetParameter(strings.map(_.str), pp)

  protected implicit lazy val string2066SeqSetParameter: SetParameter[Seq[String2066]] =
    (strings: Seq[String2066], pp: PositionedParameters) =>
      stringArraySetParameter(strings.map(_.str).toArray, pp)

  protected implicit def contractIdArraySetParameter[T]: SetParameter[Array[ContractId[T]]] =
    (ids: Array[ContractId[T]], pp: PositionedParameters) =>
      stringArraySetParameter(ids.map(_.contractId), pp)

  protected implicit def partyIdGetResult[T]: GetResult[PartyId] =
    GetResult.GetString.andThen(PartyId.tryFromProtoPrimitive)

  protected implicit def partyIdGetResultOption[T]: GetResult[Option[PartyId]] =
    GetResult.GetStringOption.andThen(_.map(PartyId.tryFromProtoPrimitive))

  protected implicit lazy val offsetJdbcType: JdbcType[Offset] =
    MappedColumnType.base[Offset, String](
      _.toHexString,
      s => Offset.fromHexString(HexString.assertFromString(s)),
    )

  protected implicit lazy val identifierSetParameter: SetParameter[Identifier] = {
    (identifier: Identifier, pp: PositionedParameters) =>
      {
        implicitly[SetParameter[String2066]].apply(
          lengthLimited(
            s"${identifier.getPackageId}:${identifier.getModuleName}:${identifier.getEntityName}"
          ),
          pp,
        )
      }
  }

  protected implicit lazy val qualifiedNameSetParameter: SetParameter[QualifiedName] =
    (v1: QualifiedName, v2: PositionedParameters) =>
      implicitly[SetParameter[String2066]].apply(lengthLimited(v1.toString()), v2)

  protected implicit val qualifiedNameGetResult: GetResult[QualifiedName] =
    GetResult.GetString.andThen { s => QualifiedName.assertFromString(s) }

  protected implicit lazy val qualifiedNameJdbcType: JdbcType[QualifiedName] =
    MappedColumnType.base[QualifiedName, String](
      { _.toString }, {
        QualifiedName.assertFromString(_)
      },
    )

  protected implicit lazy val domainIdJdbcType: JdbcType[DomainId] =
    MappedColumnType.base[DomainId, String](_.toProtoPrimitive, DomainId.tryFromString)

  protected implicit lazy val partyIdJdbcType: JdbcType[PartyId] =
    MappedColumnType.base[PartyId, String](_.toProtoPrimitive, PartyId.tryFromProtoPrimitive)

  protected implicit lazy val partyIdSetParameterOption: SetParameter[Option[PartyId]] =
    (partyId: Option[PartyId], pp: PositionedParameters) =>
      implicitly[SetParameter[Option[String2066]]]
        .apply(partyId.map(party => lengthLimited(party.toProtoPrimitive)), pp)

  protected implicit lazy val memberIdSetParameter: SetParameter[Member] =
    (memberId: Member, pp: PositionedParameters) =>
      implicitly[SetParameter[String300]].apply(memberId.toLengthLimitedString, pp)

  protected implicit lazy val memberIdSetParameterOption: SetParameter[Option[Member]] =
    (memberIdO: Option[Member], pp: PositionedParameters) =>
      implicitly[SetParameter[Option[String300]]].apply(memberIdO.map(_.toLengthLimitedString), pp)

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

  protected implicit lazy val jsonSetParameterOption: SetParameter[Option[Json]] =
    (jsValueOpt: Option[Json], pp: PositionedParameters) =>
      jsValueOpt match {
        case Some(jsValue) => jsonSetParameter(jsValue, pp)
        case None => pp.setNull(java.sql.Types.OTHER)
      }

  protected implicit lazy val jsonStringGetResult: GetResult[JsonString] = GetResult { rs =>
    val value = rs.nextString()
    if (rs.wasNull())
      throw new IllegalStateException("Tried to deserialize as Json, but the value was null.")
    else
      JsonString(value)
  }

  protected implicit lazy val jsonStringSetParameter: SetParameter[JsonString] =
    (jsonString: JsonString, pp: PositionedParameters) =>
      pp.setObject(jsonString.value, java.sql.Types.OTHER)

  protected implicit lazy val jsonStringSetParameterOption: SetParameter[Option[JsonString]] =
    (jsonStringOpt: Option[JsonString], pp: PositionedParameters) =>
      jsonStringOpt match {
        case Some(jsonString) => jsonStringSetParameter(jsonString, pp)
        case None => pp.setNull(java.sql.Types.OTHER)
      }

  protected def payloadJsonFromDefinedDataType(
      data: DefinedDataType[?]
  ): Json = AcsJdbcTypes.payloadJsonFromDefinedDataType(data)

  /** The DB may truncate strings of unbounded length, so it's advised to use a LengthLimitedString instead.
    * We use String2066 because it's the max length of an [[com.digitalasset.canton.protocol.LfTemplateId]].
    */
  protected def lengthLimited(s: String): String2066 = String2066.tryCreate(s)

  protected def tryToDecode[TCid <: ContractId[?], T <: DamlRecord[?], D](
      companion: Companion.Template[TCid, T],
      createdEvent: CreatedEvent,
  )(
      toData: Contract[TCid, T] => D
  ): Either[String, D] = {
    Contract
      .fromCreatedEvent(companion)(createdEvent)
      .map(toData)
      .toRight(
        s"Failed to decode ${companion.getTemplateIdWithPackageId} from CreatedEvent of contract id ${createdEvent.getContractId}."
      )
  }
}

object AcsJdbcTypes {
  final case class JsonString(value: String)

  // DB *must not* use stringly ints or decimals;
  // this output relies on a *big* invariant: comparing the raw JSON data
  // with the built-in SQL operators <, >, &c, yields equal results to
  // comparing the same data in a data-aware way. That's why we *must* use
  // numbers-as-numbers in this codec, and why ISO-8601 strings
  // for dates and timestamps are so important.
  @throws[io.circe.ParsingFailure]
  def payloadJsonFromDefinedDataType(
      data: DefinedDataType[?]
  ): Json = {
    val sw = new StringWriter()
    val jw = new JsonLfWriter(
      sw,
      JsonLfWriter.opts().encodeInt64AsString(false).encodeNumericAsString(false),
    )
    data.jsonEncoder.encode(jw)
    circeParse(sw.toString).fold(throw _, identity)
  }
}
