
async function getFileFromGit(deps, path, ref) {
  const content = await deps.github.rest.repos.getContent({
    owner: deps.context.repo.owner,
    repo: deps.context.repo.repo,
    path: path,
    ref: ref
  });
  return Buffer.from(content.data.content, 'base64').toString('utf-8');
}

async function getBranchesForCluster(deps, cluster) {
  const configYaml = await getFileFromGit(deps, `cluster/deployment/${cluster}/config.resolved.yaml`, 'main');
  const config = deps.jsyaml.load(configYaml);
  const active = config.synchronizerMigration.active.releaseReference.gitReference;
  const upgrade = config.synchronizerMigration.upgrade?.releaseReference.gitReference;
  const archived = config.synchronizerMigration.archived?.map((a) => a.releaseReference?.gitReference);

  return {
    cnVersion: active,
    active,
    upgrade,
    archived
  };
}

function flattenBranchesForCluster(branches) {
  return [
    branches.cnVersion,
    branches.active,
    ...(branches.upgrade ? [branches.upgrade] : []),
    ...(branches.archived ? branches.archived.filter((a) => a !== undefined) : [])
  ].map((b) => b.replace('refs/heads/', ''));
}

function explainBranchesForCluster(cluster, branches) {
  return `### ${cluster} follows: ###
  - **canton-network stack**: ${branches.cnVersion}
  - **active migration**: ${branches.active}
  - **upgrade migration**: ${branches.upgrade}
  - **archived migrations**: ${branches.archived?.join(', ')}
  `
}

function explainData(prodClusters, clusterBranches) {
  return prodClusters.map((c, i) => explainBranchesForCluster(c, clusterBranches[i])).join('\n');
}

async function main(deps) {
  const prodClusters = ['devnet', 'testnet', 'mainnet'];

  const clusterBranches = await Promise.all(prodClusters.map(async (cluster) => {
    const branchesForCluster = await getBranchesForCluster(deps, cluster)
    console.log(`branches for ${cluster}:`);
    console.log(branchesForCluster);
    return branchesForCluster;
  }));

  for (const [i, cluster] of prodClusters.entries()) {
    console.log(`data for ${cluster}: `);
    console.log(clusterBranches[i]);
  }

  const allBranches = [
    ...(clusterBranches.map(c => flattenBranchesForCluster(c))),
    // TODO(DACH-NY/canton-network-node#16006): un-hardcode release-line-0.2 here
    ['main', 'release-line-0.2', 'release-line-0.3.0']
  ]

  console.log(`all branches: ${allBranches}`);

  const pr = await deps.github.rest.pulls.get({
    owner: deps.context.repo.owner,
    repo: deps.context.repo.repo,
    // In GH, "every PR is also an issue", so the pr number is found in issue.number
    pull_number: deps.context.issue.number
  });

  const base = pr.data.base.ref;
  console.log(`base branch: ${base}`);

  const finalBranches =
    // Flatten and remove duplicates
    Array.from(new Set(allBranches.flat()));
  console.log(`final branches: ${finalBranches}`);

  const body = `# [backport] Reminder #
## Please consider backporting to the following branches: ##
${finalBranches.map((b) => `- [ ] ${b}`).join('\n')}

:arrow_forward: Please check the boxes for branches that you wish to backport to and backport PRs will
automatically be created when you merge this PR.

## Explanation: ##
${explainData(prodClusters, clusterBranches)}

And your PR is currently against base branch: ${base}.

Note: Any PR comment containing [backport] will be considered for auto-backporting upon merge,
you can always add those manually for PRs that did not get these reminders. You can also edit
this comment manually and add more branches that this should be backported to.
`;

  deps.github.rest.issues.createComment({
    issue_number: deps.context.issue.number,
    owner: deps.context.repo.owner,
    repo: deps.context.repo.repo,
    body: `${body}`
  });
}

module.exports = async ({ github, context, jsyaml }) => {
  await main({ github, context, jsyaml });
};
