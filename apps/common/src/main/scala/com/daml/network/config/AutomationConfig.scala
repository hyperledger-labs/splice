package com.daml.network.config

case class AutomationConfig(
    /** How many automation tasks should be run in parallel per kind of task. */
    parallelism: Int = 4
)
