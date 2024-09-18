// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.util

import io.circe.Json
import spray.json.{JsValue, enrichString}

object JsonUtil {

  def circeJsonToSprayJsValue(jsValue: Json): JsValue =
    jsValue.noSpaces.parseJson

}
