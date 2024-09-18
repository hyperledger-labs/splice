package com.daml.network

import org.scalatest.Tag

/** Tag used to mark individual local tests that use authentication via auth0.
  *  Such tests likely require you to set environment variables with auth0 credentials.
  *  The readme provides more details on this.
  */
object LocalAuth0Test extends Tag("LocalAuth0Test")
