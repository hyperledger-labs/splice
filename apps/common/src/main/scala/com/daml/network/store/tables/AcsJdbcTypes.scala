package com.daml.network.store.tables

import com.daml.lf.data.Ref
import com.daml.lf.data.Time.Timestamp
import com.daml.lf.value.Value.ContractId
import com.digitalasset.canton.admin.api.client.data.TemplateId
import com.digitalasset.canton.ledger.offset.Offset
import com.digitalasset.canton.topology.DomainId
import io.circe.Json
import slick.ast.FieldSymbol
import slick.jdbc.JdbcType

import java.sql.{PreparedStatement, ResultSet}

trait AcsJdbcTypes {
  val profile: slick.jdbc.JdbcProfile

  import profile.api.*

  protected implicit lazy val timestampJdbcType: JdbcType[Timestamp] =
    MappedColumnType.base[Timestamp, Long](_.micros, Timestamp.assertFromLong)

  protected implicit lazy val contractIdJdbcType: JdbcType[ContractId] =
    MappedColumnType.base[ContractId, String](_.coid, ContractId.assertFromString)

  protected implicit lazy val offsetJdbcType: JdbcType[Offset] =
    MappedColumnType.base[Offset, String](
      _.toHexString,
      s => Offset.fromHexString(Ref.HexString.assertFromString(s)),
    )

  protected implicit lazy val templateIdJdbcType: JdbcType[TemplateId] =
    MappedColumnType.base[TemplateId, String](
      { case TemplateId(packageId, moduleName, entityName) =>
        s"$packageId:$moduleName:$entityName"
      },
      s => {
        s.split(":") match {
          case Array(packageId, moduleName, entityName) =>
            TemplateId(packageId, moduleName, entityName)
          case _ =>
            throw new IllegalStateException(
              s"TemplateId column didn't contain a valid template: $s"
            )
        }
      },
    )

  protected implicit lazy val domainIdJdbcType: JdbcType[DomainId] =
    MappedColumnType.base[DomainId, String](_.toProtoPrimitive, DomainId.tryFromString)

  protected implicit lazy val jsonJdbcType: JdbcType[Json] = new profile.DriverJdbcType[Json]() {
    override def sqlType: Int = java.sql.Types.OTHER

    override def sqlTypeName(sym: Option[FieldSymbol]): String = "jsonb"

    override def setValue(v: Json, p: PreparedStatement, idx: Int): Unit =
      p.setObject(idx, v.noSpaces, java.sql.Types.OTHER)

    @SuppressWarnings(Array("org.wartremover.warts.Null"))
    override def getValue(r: ResultSet, idx: Int): Json = {
      val value = r.getString(idx)
      if (r.wasNull()) null
      else
        io.circe.parser
          .parse(value)
          .getOrElse(throw new IllegalStateException("JSONB column didn't contain valid JSON."))
    }

    override def updateValue(v: Json, r: ResultSet, idx: Int): Unit =
      r.updateObject(idx, v.noSpaces, java.sql.Types.OTHER)

    override def valueToSQLLiteral(value: Json): String = s"'${value.noSpaces}'"
  }

}
