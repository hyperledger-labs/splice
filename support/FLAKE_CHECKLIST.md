# Flake Investigation Checklist

This checklist describes the steps you should go through when
investigating a flake. These steps should be followed whether you're on
flake rotation or not.

There are two high-level steps to flake investigations. First, ensure
that the issue is tracked. After that, ensure that it has an assignee
responsible for resolving it.

## Tracking Flakes

1. Make it clear to others that you're investigating the failure by
   assigning yourself to the issue or
   leaving a `:eyes:` emoji on the slack failure notification
   (when there is no issue yet).
2. Go to the CircleCI page and find the failing job. Look at the output you see in CircleCI.
3. Download the logs for our apps and for Canton unless you can identify the issue
   purely based on the CircleCI output.
   * The logs from our tests are in `canton_network_test.clog` in the CircleCI artifacts.
     Note that there is ``.circleci/download-ci-artifacts.sh` to download all CircleCI artifacts
     if you prefer that over going through the UI.
   * The Canton logs are in `canton.clog` for wallclock tests, `canton-simtime.clog` for
     simtime tests and `canton-standalone-$suffix.clog` for tests that
     start a new Canton instance within the test.
4. Based on the CircleCI output and the log files, check if there is
   already an existing issue that describes the failure you are looking at (see section below).
5. If there is no issue, create one. If there is an issue, post a
   comment on the Github issue linking to the new CI failure. This
   helps tracking the frequency of the flake.
6. Post a link in the slack thread of the failure to the Github issue.

## Finding existing issues

Known failures should be organized in one of the following locations:

- [`cn-test-failures`](https://github.com/DACH-NY/cn-test-failures/issues) repository
- [Flaky Tests](https://github.com/DACH-NY/canton-network-node/issues?q=is%3Aopen+is%3Aissue+milestone%3A%22Flaky+Tests%22) milestone on our main repository
- [Upstream Issues](https://github.com/DACH-NY/canton-network-node/issues?q=is%3Aopen+is%3Aissue+milestone%3A%22Upstream+Issues%22) milestone on our main repository

Make sure to also consider closed issues as the failure might already have been (reportedly) fixed.

Since you will likely need to search for matching issues frequently,
you might want to set up some workflow for yourself that makes searching easy for you.
Here is a fish function for example that opens all relevant searches in the browser:

```
function flake
    echo "flake2..."
    gh issue list -R https://github.com/DACH-NY/cn-test-failures -S "$argv" -w &
    echo "flake..."
    gh issue list -S "milestone:\"Flaky Tests\" $argv" -w &
    echo "upstream issues..."
    gh issue list -S "milestone:\"Upstream Issues\" $argv" -w &
end
```

Alternatively, some people use keyword bookmarks in their browser that correspond to the relevant searches.

## Assigning Flakes

Each flake should be assigned to either a person on flake rotation or
a subject matter expert responsible for investigating and eventually
fixing the cause. If you are on flake rotation and have bandwidth,
investigating the cause of a flake can be a useful learning
opportunity to understand parts of the system better that you don't
frequently interact with.

If you don't have the bandwidth to investigate and fix it yourself,
reach out to a subject matter expert. If you are unsure about who to
contact `git blame` can be a useful starting point to find someone
that worked on the relevant part of the system. If you can't find
someone that well, post in `#team-canton-network-internal.`
The person who is assigned the issue should have full context. Specifically, as the person on flake rotation who is assigning an issue, please communicate how often this issue has occurred and how blocking it is for the rest of the team.

## Investigating the cause of a flake and fixing it

Once an issue has been created and assigned, it is time to investigate
the cause and eventually fix it. The prioritization on this usually
depends on the frequency. If you are assigned to a flake keep an eye
out for comments on the GH issue. If it happens frequently, make sure
you prioritize it above your current work.

While the details of this depend on
the specific flake, there are a few common things to look out for:

1. Synchronization issues: Actions such as submitting Daml
   transactions are usually not visible immediately on a read
   afterwards, in particular, when reading across participants. To
   avoid those issues, make sure to use `actAndCheck` which retries
   the check using `eventually`.
2. Cascading errors: Sometimes the test failure is only a symptom of
   an earlier error. To find those, start by analyzing failures from the first warning or error you see in the logs.
   Note that the following types of warnings in the
   Canton logs are currently expected and should not be taken into account for this:

   ```
   2023-07-03T18:13:02.366 [c-e-e-context-181] WARN - com.daml.jwt.HMAC256Verifier$ () - HMAC256 JWT Validator is NOT recommended for production environments, please use RSA256!!!
   2023-07-03T18:14:56.149 [c-e-e-context-119] WARN - c.d.c.l.a.a.i.AuthorizationInterceptor:participant=sv4Participant (1a3c257b295e1e784a9eaa2e07c208e9) - PERMISSION_DENIED(7,1a3c257b): Could not resolve is_deactivated status for user 'sv4_validator_user-dec471dc' and identity_provider_id 'Default' due to 'UserNotFound(sv4_validator_user-dec471dc)'
     location: AuthorizationInterceptor.scala:173
     error-code: PERMISSION_DENIED(7,1a3c257b)
     participant: 'sv4Participant'
   ```
3. Unexpected warning/error logs: In addition to the usual test
   assertion that can fail, we also check that there are no unexpected
   warning or error logs in both the Canton logs and our own
   logs. Note that in this case you will not see a scalatest test
   failure which can be confusing. Those issues will look like this in the circleci output:

   ```
   Found problems in log/canton_network_test.clog:
   {"@timestamp":"2023-07-08T12:08:11.996Z","@version":"1","message":"Failed to setup install contract:  AbortError: Aborted","logger_name":"c.d.n.i.t.r.Sv2NonDevNetPreflightIntegrationTest:Sv2NonDevNetPreflightIntegrationTest/web-frontend=sv","thread_name":"BiDi Connection","level":"ERROR","level_value":40000}
   ```

   If the warning is expected, e.g., because a test is deliberately triggering an error, you can use one of the various function in `SuppressingLogger` like `assertLogs`
   to lower the log level. If that is not possible (e.g. for logs in Canton), we maintain lists of ignore patterns in `project/ignore_patterns`.
4. Missing retries: Most operations tend to involve some sort of network request and those can and will eventually fail with transient errors. If you encounter such an error,
   you can often wrap the operation in `retryForAutomation` or `retryForClientCalls`.
5. Insufficient logs: While we try to log enough information to debug
   issues, sometimes there just is not enough information in the logs
   to debug an issue. In that case, consider improving the logs and
   waiting for the issue to reappear. When you do, label the issue
   `infrequent/no repro` and clearly indicate in the issue what logs
   have been added, and that we are waiting for this to re-occur with
   more logging information.
6. Infrequent issues: Some issues happen only once and are either not
   worth investigating further or out of our control (e.g., failure of
   external services). In those cases, add the `infrequent/no repro`
   label. It the issue does not occur within the next 2 weeks it can
   be closed.


## Documenting Issue Investigations

In addition to identifying and possibly fixing the issue, you should
also document how you debugged it, e.g., include log filters that were
useful, `kubectl` commands or other tools you used. This is useful to
share knowledge in the team and get an overview over common patterns
that we might be able to simplify.

An example writeup can be found
[here](https://github.com/DACH-NY/canton-network-node/issues/9374#issuecomment-1896157364).

If you find yourself repeatedly using the same queries in log
explorer, add them to the
[documentation](https://github.com/DACH-NY/canton-network-node/blob/main/cluster/README.md#gce-log-explorer).

## Investigating Canton-related errors

We are using development versions of Canton and are frequently among the first users of new Canton features.
This means that we are very likely to encounter Canton bugs as well, not just bugs in our own code.

### How critical is this error?

Some Canton bugs can leave nodes irreparably broken, forcing a network reset, or stuck in a state where they manually need to be restarted.
If the error you are investigating caused an already bootstrapped Canton node to become irreparably faulty,
investigating this issue should have very high priority.

Even if the effects of this bug are not catastrophic,
keep in mind that we are the first users of many new Canton features and do relatively extensive testing.
This means:

1. If we don't report the bug, chances of it getting discovered elsewhere and getting fixed are relatively low.
2. Even if the bug is already known it might not be prioritized. Because we are also the *only* users for some features, prioritization is pretty much directly driven by our requests.

So it is always in our interest to report Canton bugs quickly.

### What could be the root cause?

Debugging Canton problems can be very hard.
Here are few guiding questions for narrowing down possible root causes:

- What is the earliest error we are seeing?
- Does something relevant show up when we search for the errors in past [issues](https://github.com/DACH-NY/canton-network-node/issues?q=)?
- Did our (app) code do anything particular around the time of the error(s)?
  SV onboarding is a notorious source of errors, for example.
- Which Canton nodes might have been relevant to the operation that failed?
  Make sure you are also looking at *their* logs.
  (Recall that the `1220de7c...` of party IDs like `alice::1220de7c...` is the participant namespace,
  shared by the participant ID and all other parties on that participant.)
- By whom was the action (e.g., transaction) that caused the error initiated? Was this expected?
- Do the timestamps on the action that failed make sense? (Frequent source of errors.)
- Which contracts might have been involved in the action that failed?
  This can give you a hint about the context in which the failure happened,
  as well as the parties (and hence participants) that are relevant here as informees.
- Does searching for error message(s) you observe in the [Canton codebase](https://github.com/DACH-NY/canton) provide any clues?

### Best practices for escalating to Canton

Once you stop making significant progress in your analysis and/or have
high confidence that the error is not caused by us "using it wrongly"
(also after consulting with another CN team member for a second opinion),
it is prudent to escalate to the Canton team for
A. debugging help and
B. finding a solution or at least workaround for the issue.

In order to use their time effectively, create an GitHub issue for your problem and make sure it
(or a comment on it) contains:

- [ ] All logs that are potentially relevant to analyzing the problem, as lnav-readable files (or one big zip file).
      Upload the logs themselves, not just links to our CI runs.
      The Canton team has access to our gcloud logs so gcloud log links (with carefully curated filters and time ranges) are fine too.
- [ ] A *concise* summary of your analysis and your best hypotheses about what the root cause might be.
- [ ] The context in which the error happened; e.g.:
  - Which nodes (participants, sequencer nodes, mediator nodes) are involved?
  - Any exotic features that we are using that might be relevant here
    (decentralizedNamespace for the DSO party, party migrations, distributed domain...)?
  - What our app code was trying to do when the error happened
     - in Canton terms; ledger API submissions, topology transactions, ...
     - pay attention to concurrent operations that might trigger the bug
     - especially when the logs don't display full transactions (often the case for logs from our clusters): describe the shape of relevant transaction trees
- [ ] The Canton version (commit height) we were using.
      Have a look at `version` in `nix/canton-sources.json` - the `9d36b99f`
      in `2.7.0-snapshot.20230726.10871.0.v9d36b99f` is what the Canton team is typically interested in.
- [ ] The priority/severity of the issue (e.g.: it has impact on production clusters/customers, it is blocking us from consuming new Canton upgrades, it makes our current CI very flaky, it has only occured once so far, …)
- [ ] (optional) If you would like to pair with a member of the Canton team for futher debugging (a potential learning opportunity for both sides) you can signal this directly on the issue.

If you create the issue in the Canton repository, make sure that it is
assigned to the [Canton Ledgers Support Issues](https://github.com/orgs/DACH-NY/projects/30/views/1)
board. Note that it is also fine to just create an issue in the
`canton-network-node` repo and leave it up to the Canton team to
create corresponding issues on their side.

After documenting as much information as you can about the issue, ping
the corresponding Canton team member that is on the support rotation
this week. This can be found in the description of the
[#team-canton](https://daholdings.slack.com/archives/C044LUG7CKT) channel.
Don't forget to reference your GH issue.

**Addendum 1:**
If the issue you are investigating affects a long-running cluster and/or an important externally communicated timeline,
it is OK to escalate to the Canton team more quickly to speed up resolution.
I.e., in some cases (use your judgement) it is OK to grab attention from Canton engineers *before* writing up our analysis nicely.

**Addendum 2:**
In the future this process might change and we might be asked to open support tickets instead of reaching out directly via Slack.
For high-priority issues (see Addendum 1 above) we can open tickets with up to SEV1 priority.
