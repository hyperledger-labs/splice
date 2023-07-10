# Flake Investigation Checklist

This checklist describes the steps you should go through when
investigating a flake. These steps should be followed whether you're on
flake rotation or not.

There are two high-level steps to flake investigations. First, ensure
that the issue is tracked. After that, ensure that it has an assignee
responsible for resolving it.

## Tracking Flakes

1. Make it clear to others that you're investigating the failure by
   leaving a `:eyes:` emoji on the slack failure notification.
2. Go to the CircleCI page and find the failing job. Look at the output you see in CircleCI.
3. Download the logs for our apps and for Canton unless you can identify the issue
   purely based on the CircleCI output.
   * The logs from our tests are in `canton_network_test.clog` in the CircleCI artifacts.
     Note that there is ``./scripts/download-ci-artifacts.sh` to download all CircleCI artifacts
     if you prefer that over going through the UI.
   * The Canton logs are in `canton.clog` for wallclock tests, `canton-simtime.clog` for
     simtime tests and `canton-standalone-$suffix.clog` for tests that
     start a new Canton instance within the test.
   * For preflight checks, you frequently also need the logs of the
     corresponding app running in our cluster. Those logs are not in
     the CircleCI artifacts.  Our CircleCI jobs
     ([example](https://app.circleci.com/pipelines/github/DACH-NY/canton-network-node/50917/workflows/448be273-6661-4e02-87d9-97a8e2b20b8a/jobs/314609))
     have a step called *Displaying commands to download gcloud
     logs*. This prints the `cncluster gcloud_logs` command to
     download the logs for each app in our cluster for the timeframe
     the test ran. Downloading all of them can be slow so usually you
     want to identify the potentially relevant apps based on the logs
     found in the CircleCI artifacts, e.g., a failure in
     `Validator1PreflightIntegrationTest` is likely going to require
     logs of the validator1 validator app and the validator1
     participant.
4. Based on the CircleCI output and the log files, check if there is
   already an issue in the [Flaky Tests
   milestone](https://github.com/DACH-NY/canton-network-node/issues?q=is%3Aopen+is%3Aissue+milestone%3A%22Flaky+Tests%22). If
   you are looking at an older failure, make sure to also consider
   consider [closed issues](https://github.com/DACH-NY/canton-network-node/issues?q=is%3Aclosed+is%3Aissue+milestone%3A%22Flaky+Tests%22+)
   as they might already have been fixed.
5. If there is no issue, create one. If there is an issue, post a
   comment on the Github issue linking to the new CI failure. This
   helps tracking the frequency of the flake.
6. Post a link in the slack thread of the failure to the Github issue.

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
