package com.daml.network.util

/** Trait for services that can report their health. */
trait HasHealth {

  /** True if the service is running in healthy state */
  def isHealthy: Boolean
}
