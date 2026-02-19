#!/usr/bin/env -S deno run --allow-all

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import process from 'node:process'

const args = process.argv.slice(2)
const newVersion = args[0]
const oldVersionsFile = args[1]
const numberOfSvsToUpgrade = args.length > 2
  ? Number.parseInt(args[2])
  : 1

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

for (const sv of svs) for (const chart of svCharts) chartVersions[sv][chart] = newVersion
for (const chart of ['splice-validator', 'splice-participant', 'splice-splitwell-web-ui'])
  chartVersions['validator1'][chart] = newVersion

process.stdout.write(JSON.stringify(chartVersions, undefined, 2))
