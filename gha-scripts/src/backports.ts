import type { Context, Github } from './types'
import { getFileFromGit } from './git'
import { findLatestReleaseBranchesUpTo, findNLatestReleaseBranches, isSpliceRepo } from './releases'
import * as yaml from 'js-yaml'

export async function parseBackportComments(
  github: Github,
  context: Context,
  prNumber: number,
): Promise<Array<string>> {
  console.log(`Fetching comments for PR #${prNumber}...`)
  const { data: comments } = await github.rest.issues.listComments({
    owner: context.repo.owner,
    repo: context.repo.repo,
    issue_number: prNumber
  })

  const backportComments = [...filterBackportComments(comments)]

  if (backportComments.length === 0) {
    console.log('No backport comments found.')
    return []
  } else {
    console.log('Backport comment found, parsing branches...')
    const branches = [...parseBackportBranches(backportComments)]

    console.log(`Parsed branches from comment: ${branches.join(', ')}`)
    return branches
  }
}

function* filterBackportComments(comments: Array<{ body?: string }>): Generator<string> {
  for (const comment of comments) {
    const body = comment.body
    if (body !== undefined && body.includes('[backport]')) {
      yield body
    }
  }
}

function* parseBackportBranches(comments: Array<string>): Generator<string> {
  for (const comment of comments) {
    for (const line of comment.split('\n')) {
      const trimmedLine = line.trim()
      if (trimmedLine.startsWith('- [x]')) {
        yield trimmedLine.slice(5).trim()
      }
    }
  }
}

export async function addBackportReminderComment(
  github: Github,
  context: Context,
  prNumber: number,
  lookupProdClusterConfigs: boolean,
): Promise<void> {
  console.log(`Fetching PR #${prNumber} details...`)
  const { data: pr } = await github.rest.pulls.get({
    owner: context.repo.owner,
    repo: context.repo.repo,
    pull_number: prNumber
  })

  const baseBranch = pr.base.ref
  console.log(`base branch: ${baseBranch}`)

  console.log(`Determining relevant release branches for backporting...`)
  const [relevantReleaseBranches, explanation] = lookupProdClusterConfigs
    ? await getRelevantReleaseBranchesFromProdClusters(github, context)
    : [await findNLatestReleaseBranches(github, 4), undefined]
  const backportBranchCandidates = ['main', ...alwaysIncludedBranches(context), ...relevantReleaseBranches]
  const backportBranches = [...new Set(backportBranchCandidates)]
    .filter(branch => branch !== baseBranch)

  console.log(`Adding backport reminder comment to PR #${prNumber}...`)
  await github.rest.issues.createComment({
    issue_number: prNumber,
    owner: context.repo.owner,
    repo: context.repo.repo,
    body: formatBackportReminderComment(baseBranch, backportBranches, explanation),
  })
}

// This can be used to temporarily add release-line-a.b.x branches that are not deployed on any cluster but make up the next release.
function alwaysIncludedBranches(context: Context): Array<string> {
  return isSpliceRepo(context) ? ['release-line-0.9.x'] : []
}

async function getRelevantReleaseBranchesFromProdClusters(
  github: Github,
  context: Context,
): Promise<[Array<string>, string]> {
  const prodClusters = ['devnet', 'testnet', 'mainnet']
  const results = prodClusters.map(async cluster => {
    console.log(`Fetching config for ${cluster}...`)
    const configYaml = await getFileFromGit(
      github,
      context,
      'main',
      `cluster/deployment/${cluster}/config.resolved.yaml`,
    )
    const config = yaml.load(configYaml)
    return [cluster, getReleaseBranchFromConfig(config)] as const
  })
  const releaseBranchesByCluster = Object.fromEntries(await Promise.all(results))
  const relevantReleaseBranches = await findLatestReleaseBranchesUpTo(github, releaseBranchesByCluster['mainnet'])
  const explanation = prodClusters
    .map(cluster => `- ${cluster}: ${releaseBranchesByCluster[cluster]}`)
    .join('\n')
  return [relevantReleaseBranches, explanation] as const
}

function getReleaseBranchFromConfig(
  config: unknown,
): string {
  const untypedConfig = config as any
  let reference
  try {
    reference = untypedConfig.synchronizerMigration.active.releaseReference.gitReference
  } catch (e) {
    console.error(`Failed to read synchronizerMigration config.\n${JSON.stringify(untypedConfig?.synchronizerMigration)}`, e)
    throw e
  }
  if (typeof reference !== 'string') {
    throw new Error(`'synchronizerMigration.active.releaseReference.gitReference' is not a string but ${typeof reference}.`)
  }
  const releaseBranch = reference.startsWith('refs/heads/')
    ? reference.slice('refs/heads/'.length)
    : reference
  return releaseBranch
}

function formatBackportReminderComment(
  baseBranch: string,
  branches: Array<string>,
  explanation: string | undefined,
): string {
  return `# [backport] Reminder #
## Please consider backporting to the following branches: ##
${branches.map((b) => `- [ ] ${b}`).join('\n')}

:arrow_forward: Please check the boxes for branches that you wish to backport to and backport PRs will
automatically be created when you merge this PR.
${explanation !== undefined ? `\n## Explanation: ##\n${explanation}\n` : ''}
And your PR is currently against base branch: ${baseBranch}.

Note: Any PR comment containing [backport] will be considered for auto-backporting upon merge,
you can always add those manually for PRs that did not get these reminders. You can also edit
this comment manually and add more branches that this should be backported to.
`
}
