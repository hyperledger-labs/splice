#!/usr/bin/env -S deno run --allow-all

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import process from 'node:process'
import { command, number, optional, positional, run, string } from 'npm:cmd-ts@0.15.0'

function main(
  newVersion: string,
  oldVersionsFile: string,
  numberOfSvsToUpgrade: number,
) {
  type ChartVersions = Record<string, Record<string, string>>
  const chartVersions: ChartVersions = JSON.parse(readFileSync(oldVersionsFile, 'utf-8'))

  const svs = Array.from({ length: numberOfSvsToUpgrade }, (_, index) => `sv-${index + 1}`)
  const svCharts = [
    'splice-participant',
    'splice-global-domain',
    'splice-cometbft',
    'splice-scan',
    'splice-sv-node',
    'splice-validator',
  ]

  for (const sv of svs) {
    chartVersions[sv] ??= {}
    for (const chart of svCharts)
      chartVersions[sv][chart] = newVersion
  }

  const validatorCharts = [
    'splice-validator',
    'splice-participant',
    'splice-splitwell-web-ui',
  ]

  for (const validator of ['validator1']) {
    chartVersions[validator] ??= {}
    for (const chart of validatorCharts)
      chartVersions[validator][chart] = newVersion
  }

  process.stdout.write(JSON.stringify(chartVersions, undefined, 2))
}

await run(
  command({
    name: process.argv[1],
    args: {
      newVersion: positional({ type: string }),
      oldVersionsFile: positional({ type: string }),
      numberOfSvsToUpgrade: positional({ type: optional(number) }),
    },
    handler: (args) => {
      main(
        args.newVersion,
        args.oldVersionsFile,
        args.numberOfSvsToUpgrade ?? 1,
      )
    },
  }),
  process.argv.slice(2),
)
