package com.daml.network

import org.scalatest.Tag

/** Tag used to mark individual local tests that use authentication via auth0.
  *  Such tests likely require you to set system properties - have a look at
  *  `scripts/start-sbt-for-local-auth0-tests.sh`
  */
object LocalAuth0Test extends Tag("LocalAuth0Test")
