
async function getBackportComment(deps) {
  // get all comments on the PR
  const comments = await deps.github.rest.issues.listComments({
    owner: deps.context.repo.owner,
    repo: deps.context.repo.repo,
    issue_number: deps.context.issue.number
  });

  const backportComments = comments.data.filter(
    (comment) => comment.body.includes("[backport]")
  );

  if (backportComments.length == 0) {
    console.log("No backport comment found");
    return undefined;
  }
  const comment = backportComments.join("\n");
  return comment;
}

async function getBackportBranches(deps) {
  comment = await getBackportComment(deps);
  if (comment == undefined) {
    return [];
  }
  console.log(comment.body);

  const lines = comment.body.split('\n');
  const backportLines = lines.filter((line) => {
    return line.match(/^\s*- \[x\]/) !== null;
  });

  const backportBranches = backportLines.map((line) => {
    return line.split(']')[1].trim();
  });

  return backportBranches;
}

module.exports = async ({ github, context }) => {
  return await getBackportBranches({ github, context });
};
