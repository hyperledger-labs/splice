package com.daml.network.util

import io.circe.Json
import spray.json.{JsValue, enrichString}

object JsonUtil {

  def circeJsonToSprayJsValue(jsValue: Json): JsValue =
    jsValue.noSpaces.parseJson

}
