package com.daml.network.util

import io.circe.Json
import spray.json.*

object JsonUtil {

  def sprayJsValueToCirceJson(jsValue: JsValue): Json = {

    jsValue match {

      case JsObject(fields) =>
        Json.obj(fields.map { case (key, value) =>
          key -> sprayJsValueToCirceJson(value)
        }.toSeq: _*)

      case JsArray(elements) =>
        Json.fromValues(elements.map(sprayJsValueToCirceJson))

      case JsString(value) =>
        Json.fromString(value)

      case JsNumber(value) if value.isDecimalDouble =>
        Json.fromDoubleOrNull(value.toDouble)

      case JsNumber(value) =>
        Json.fromBigInt(value.toBigInt)

      case JsBoolean(value) =>
        Json.fromBoolean(value)

      case _ => Json.Null
    }
  }

}
