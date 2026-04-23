# AI Policy

This policy applies to all contributions — code, issues, reviews, comments — to this repository, regardless of whether you are a maintainer or an external contributor.

Using AI tools is allowed and often useful. The rules below exist to protect the quality of the codebase and the time of reviewers and other contributors.

## You are accountable for what you submit

You are 100% responsible for every contribution you make under your name, regardless of which tools were involved in producing it. Reviewers review *your* work, not AI output.

You must understand every line of code you submit and be able to explain *why* each change is there. "The AI wrote it" is not an answer.

## Quality bar

- Keep patches small, focused, and clearly motivated. One logical change per PR.
- Don't open a PR you wouldn't have opened without AI assistance. An AI making code cheap to produce is not a reason to produce more of it.
- Match the conventions of the surrounding code. AI tools often default to patterns that do not fit this repository.
- Verify what the tool tells you. Hallucinated APIs, fabricated tests, and nonexistent references are common and must not reach a PR.

## Guard reviewers' time

Aggressively self-review AI output before asking anyone else to look at it. If you would not ask a colleague to read a specific patch, do not ask the maintainers either.

**Issues and PRs that appear to be unreviewed AI output — overlong and padded, referencing functions or docs that do not exist, plausible-sounding but wrong, or filled with bot-style prose — may be closed without further discussion.** Maintainers are not obligated to explain, respond to, or debate the merits of such submissions. Repeated submissions of this kind may lead to being blocked from the repository.

If your contribution is closed under this clause and you believe it was in error, leave a comment on the closed issue or PR stating your case briefly and concretely. We may reopen it if convinced.

## Do not leak information through AI tools

Do not paste private, security-sensitive, or proprietary information into AI tools outside of your control — including secrets, customer data, and non-public designs or code.

Prefer running agentic AI tools (Claude Code, Copilot CLI, Cursor agents, and similar) inside a sandbox — a VM, container, or dedicated workstation — to limit the blast radius if the tool is compromised or misbehaves.

## PRs opened autonomously by AI require two human reviews

A PR opened directly by an AI tool, bot, or automated AI workflow must receive approvals from **two** human reviewers before it is merged. A PR that a human opens manually after reviewing AI-generated changes counts as human-authored; the normal review rules apply.

Fully deterministic automated workflows (dependency bumps, submodule bumps, and the like) are not subject to this rule.

## Attribution

There is no requirement to mark AI-assisted contributions as such. Using AI to help you work is not cheating.

That said, do not hide it if asked. If a reviewer asks how a piece of code came about, answer honestly.

## A note for new contributors

If you are picking up a `good first issue` or similar onboarding-oriented task, the task exists partly so that *you* learn the codebase. Using AI to shortcut it is allowed but largely defeats the purpose — for yourself and for the maintainers trying to onboard you. Engage with the code.
