# What is this file?

Currently, we have a copy of the Canton OS files in `canton/`.
We want to switch to eventually reusing this Canton code via a library
setting. The purpose of this file is to track all changes we
make to the Canton file copies before then,
to know which and/or what changes we'll need to upstream before the switch.

# Changes
## Methods or classes with changed visibility
* `idHelper`, `tryDomainNodeParametersByString`, `tryParticipantNodeParametersByString`, 
    `MetricsFactory.registerReporter`, `BaseIntegrationTest` made public
* `testingTimeService` made protected
## Misc
* Generalization of `Environment`
* Generalization of `MetricsFactory`
* Removed a trailing comma in many places because the CC Scala compiler doesn't like it (e.g. `.authorize(op, domain, mediator, side, key.some, )` -> `.authorize(op, domain, mediator, side, key.some)`)
* Temporarily added a new release version in `CantonVersion.scala`
## Build system
* Added refs to GH issues in project/DamlPlugin.sbt for two bugs
