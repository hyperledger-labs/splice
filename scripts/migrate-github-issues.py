#!/usr/bin/env python3

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import argparse
import os
import re
import subprocess
import time
from requests.exceptions import RetryError
from abc import ABC, abstractmethod
from pydantic import BaseModel, ConfigDict
from graphlib import TopologicalSorter

from github import Github
from github import Auth
from github.Milestone import Milestone as github_Milestone
from github.Issue import Issue as github_Issue
from github.GithubObject import NotSet
from github.GithubException import GithubException

class Milestone(BaseModel):
    model_config = ConfigDict(frozen=True) # Makes it hashable for sets
    number: int
    title: str
    description: str | None

class Issue(BaseModel):
    number: int
    title: str
    body: str
    comments: list[str]
    labels: list[str]
    refs: list[int]
    milestone: Milestone | None
    assignees: list[str]
    author: str
    has_sub_issues: bool

class IssueCollection(BaseModel):
    internal: dict[int, Issue]
    public: dict[int, Issue]
    internal_milestones: list[Milestone]
    public_milestones: list[Milestone]

class TargetRepo(ABC):
    @abstractmethod
    def new_milestone(self, title: str, description: str | None) -> int:
        pass

    @abstractmethod
    def new_issue(self, title: str, body: str, milestone_number: int | None, labels: list[str] | None, assignees: list[str] | None) -> int:
        pass

    @abstractmethod
    def new_comment(self, issue_number: int, comment: str) -> None:
        pass

class DryRunTargetRepo(TargetRepo):

    name: str
    milestones: list[Milestone] = []
    issues: list[Issue] = []

    def __init__(self, name: str):
        self.name = name

    def new_milestone(self, title: str, description: str | None) -> int:

        # print("===")
        # print(f"Dry run ({self.name}): Would create milestone '{title}'")
        # print("===\n")

        new_milestone = Milestone(number=len(self.milestones), title=title, description=description)
        self.milestones.append(new_milestone)
        return new_milestone.number

    def new_issue(self, title: str, body: str, milestone_number: int | None, labels: list[str] | None, assignees: list[str] | None) -> int:
        milestone = self.milestones[milestone_number] if milestone_number is not None else None
        # print("===")
        # print(f"Dry run ({self.name}): Would create issue '{title}'")
        # print(f"  - Body: {body}")
        # if milestone:
        #     print(f"  - Milestone: {milestone.title}")
        # if labels:
        #     print(f"  - Labels: {', '.join(labels)}")
        # if assignees:
        #     print(f"  - Assignees: {', '.join(assignees)}")
        # print("===\n")


        new_issue = Issue(
            number=len(self.issues),
            title=title,
            body=body,
            comments=[],
            labels=labels or [],
            refs=[],
            milestone=milestone,
            assignees=assignees or [],
            author="",
            has_sub_issues=False
        )
        self.issues.append(new_issue)
        return new_issue.number

    def new_comment(self, issue_number: int, comment: str) -> None:
        # print("===")
        # print(f"Dry run ({self.name}): Would add comment to issue #{issue_number}: {comment}")
        # print("===")
        pass


class LiveTargetRepo(TargetRepo):

    milestones: dict[int, github_Milestone] = {}
    issues: dict[int, github_Issue] = {}

    def __init__(self, repo_name: str):
        if os.environ.get("GITHUB_TOKEN") is None:
            raise ValueError("GITHUB_TOKEN environment variable is not set.")
        auth = Auth.Token(os.environ["GITHUB_TOKEN"])
        self.github = Github(auth=auth)
        self.repo = self.github.get_repo(repo_name)

    def new_milestone(self, title: str, description: str | None) -> int:
        print("Creating milestone:", title)
        # print(".", end="", flush=True)
        new_milestone = self.repo.create_milestone(title=title, description=description or NotSet)
        self.milestones[new_milestone.number] = new_milestone
        return new_milestone.number

    def _get_milestone(self, milestone_number: int | None) -> github_Milestone | None:
        if milestone_number is None:
            return None

        if milestone_number not in self.milestones:
            print("Fetching milestone:", milestone_number)
            # We don't know this milestone, probably created in a previous run
            self.milestones[milestone_number] = self.repo.get_milestone(milestone_number)

        return self.milestones[milestone_number]

    def _get_issue(self, issue_number: int) -> github_Issue:
        if issue_number not in self.issues:
            print("Fetching issue:", issue_number)
            # We don't know this issue, probably created in a previous run
            self.issues[issue_number] = self.repo.get_issue(issue_number)

        return self.issues[issue_number]


    def new_issue(self, title: str, body: str, milestone_number: int | None, labels: list[str] | None, assignees: list[str] | None) -> int:
        print("Creating issue:", title)
        # print(".", end="", flush=True)
        milestone = self._get_milestone(milestone_number)
        try:
            issue = self.repo.create_issue(
                title=title,
                body=body,
                milestone=milestone or NotSet,
                labels=labels or NotSet,
                assignees=assignees or NotSet
            )
        except GithubException as e:
            if e.status == 422:
                print(f"\nError creating issue: {e.data['message']}, trying without assignees...")
                body = f"[ This issue was originally assigned to {assignees}, but re-assigning the migrated issue failed probably due to the assignee not being a member of the target repository. ]\n{body}"
                issue = self.repo.create_issue(
                    title=title,
                    body=body,
                    milestone=milestone or NotSet,
                    labels=labels or NotSet,
                    assignees=NotSet
                )
            else:
                print(f"Error creating issue: {e}")
                raise e
        self.issues[issue.number] = issue
        return issue.number

    def new_comment(self, issue_number: int, comment: str) -> None:
        print(f"Adding comment to issue #{issue_number}")
        # print(".", end="", flush=True)
        issue = self._get_issue(issue_number)
        if issue is not None:
            issue.create_comment(comment)
        else:
            raise ValueError(f"Issue #{issue_number} not found in the target repository.")


class State(BaseModel):
    public_milestones_map: dict[int, int]
    internal_milestones_map: dict[int, int]
    public_sorted_issues: list[int]
    internal_sorted_issues: list[int]
    public_issues_map: dict[int, int]
    internal_issues_map: dict[int, int]
    public_num_comments: dict[int, int]
    internal_num_comments: dict[int, int]

def save_state(state: State, state_filename: str):
    with open(state_filename, 'w', encoding='utf-8') as f:
        f.write(state.model_dump_json(indent = 2))

# regex pattern to capture the shorthand #XXX or the long <github_repo>/issues/XXX references,
# but not references to comments (which are similar to the long issue references, but followed by a # and a comment number)
ref_pattern = re.compile(r"(?P<short>\B#(?P<short_num>\d+)\b)|(?P<url>https://github.com/DACH-NY/canton-network-node/issues/(?P<url_num>\d+(?![#\d])))")

def get_refs(text: str) -> set[int]:
    refs = set()
    found = ref_pattern.finditer(text)
    for match in found:
        if match.group('short'):
            refs.add(int(match.group('short_num')))
        elif match.group('url'):
            refs.add(int(match.group('url_num')))
    return refs


def read_from_github(source_repo: str, dump_filename: str, limit: int | None = None):

    internal_issues = {}
    public_issues = {}
    internal_milestones = set()
    public_milestones = set()

    if os.environ.get("GITHUB_TOKEN") is None:
        raise ValueError("GITHUB_TOKEN environment variable is not set.")

    auth = Auth.Token(os.environ["GITHUB_TOKEN"])
    github = Github(auth=auth)

    source_repo = github.get_repo(source_repo)

    issues = source_repo.get_issues(state="open")

    internal = []

    for issue in issues:
        print(".", end="", flush=True)
        # Skip issues that are pull requests
        if issue.pull_request is not None:
            continue

        number = issue.number
        title = issue.title
        body = issue.body or ""
        comments = [f"[ from @{comment.user.login} ]: {comment.body}" for comment in issue.get_comments()]
        labels = [label.name for label in issue.labels]
        milestone = Milestone(number = issue.milestone.number, title = issue.milestone.title, description=issue.milestone.description) if issue.milestone else None
        internal = ('DA internal' in labels) or ('security' in milestone.title.lower() if milestone else False) or ('da internal' in milestone.title.lower() if milestone else False)
        if milestone:
            if internal:
                internal_milestones.add(milestone)
            else:
                public_milestones.add(milestone)
        assignees = [assignee.login for assignee in issue.assignees]
        author = issue.user.login

        refs = get_refs(title).union(get_refs(body))
        for comment in comments:
            refs = refs.union(get_refs(comment))

        has_subissues = issue.raw_data.get('sub_issues_summary').get('total') > 0

        i = Issue(
                number=number,
                title=title,
                body=body,
                comments=comments,
                labels=labels,
                refs=[int(ref) for ref in set(refs)],
                milestone=milestone,
                assignees=assignees,
                author = author,
                has_sub_issues=has_subissues
            )
        if internal:
            internal_issues[issue.number] = i
        else:
            public_issues[issue.number] = i

        if limit is not None and len(internal_issues) + len(public_issues) >= limit:
            print(f"\nStopping after processing {len(internal_issues) + len(public_issues)} issues.")
            break

    print("\nDone processing issues.")

    all = IssueCollection(
        internal=internal_issues,
        public=public_issues,
        internal_milestones=list(internal_milestones),
        public_milestones=list(public_milestones),
    )
    with open(dump_filename, 'w', encoding='utf-8') as f:
        f.write(all.model_dump_json(indent = 2))

def read_from_file(dump_filename: str) -> IssueCollection:
    with open(dump_filename, 'r', encoding='utf-8') as f:
        content = f.read()
        return IssueCollection.model_validate_json(content)

def find_ref_sources(all: IssueCollection, ref: int) -> tuple[list[int], list[int]]:
    internal_sources = [issue for issue in all.internal.keys() if ref in all.internal[issue].refs]
    public_sources = [issue for issue in all.public.keys() if ref in all.public[issue].refs]
    return internal_sources, public_sources

def sort_issues(src: dict[int, Issue]) -> list[int]:
    dep_graph = {issue.number: issue.refs for issue in src.values()}
    sorted = list(TopologicalSorter(dep_graph).static_order())
    return sorted

def migrate_issues(src: dict[int, Issue], sorted_issues: list[int], trgt: TargetRepo, milestones_map: dict[int, int], issues_map: dict[int, int], comments_count: dict[int, int], state: State, state_filename: str):

    def ref_replacer(match) -> str:
        ref = int(match.group('short_num') or match.group('url_num'))
        if ref in issues_map:
            return f"#{issues_map[ref]}"
        else:
            return f"DACH-NY/canton-network-node#{ref}"


    # print(f"\nPublic issues in topological order: {public_sorted}")

    for issue_number in sorted_issues:
        if not issue_number in src:
            # An issue referred to, but not an open issue (e.g. closed or is a PR), we just skip those and reference the original issue
            continue

        issue = src.get(issue_number)
        if issue_number not in issues_map:
            # Issue hasn't been migrated yet, migrate it
            # (if it has been migrated, that's because we continued from a saved state, and this issue was handled in a previous run)
            if issue.milestone:
                if issue.milestone.number not in milestones_map:
                    raise ValueError(f"Public issue #{issue.number} references milestone #{issue.milestone.number}, which is not in the public milestones.")
                milestone_number = milestones_map[issue.milestone.number]
            else:
                milestone_number = None

            title = issue.title
            prefix = f"[ This issue was auto-migrated from DA's internal repo (DACH-NY/canton-network-node#{issue_number}). Original author: @{issue.author} ]\n"
            if issue.has_sub_issues:
                prefix += "[ Note: This issue had sub-issues. They were migrated to this repo, but the sub-issue relationship has not been preserved ]\n"
            body = f"{prefix}\n{issue.body}"
            (title, count) = ref_pattern.subn(ref_replacer, title)
            if count > 0:
                print(f"Replacing references in title: {issue.title} => {title}")
            (body, count) = ref_pattern.subn(ref_replacer, body)
            if count > 0:
                print(f"Replacing references in body: {issue.body} => {body}")

            # print("Milestone:", milestone_number)
            trgt_issue = trgt.new_issue(title, body, milestone_number, issue.labels, issue.assignees)
            issues_map[issue_number] = trgt_issue
            print(f"Issue #{issue_number} => {trgt_issue}: {title}")
            save_state(state, state_filename)
        else:
            trgt_issue = issues_map[issue_number]
            print(f"Issue #{issue_number} already migrated to {trgt_issue}: {issue.title}")

        if issue_number not in comments_count or comments_count[issue_number] < len(issue.comments):
            num_migrated_comments = comments_count[issue_number] if issue_number in comments_count else 0
            comments = issue.comments
            comments = [ref_pattern.sub(ref_replacer, comment) for comment in comments]
            for i in range(num_migrated_comments, len(issue.comments)):
                trgt.new_comment(trgt_issue, comments[i])
                comments_count[issue_number] = i+1
                save_state(state, state_filename)


def migrate_todo_comments(all, public_repo_path, internal_repo_path, public_issues_map, internal_issues_map):
    cmd = ['rg', r'TODO ?\(#(\d+)\)', '--vimgrep', '-o', '--no-column', public_repo_path, '-g', '!**/canton/community/**', '-.']
    print(f"Running command: {' '.join(cmd)}")
    todo_comments = subprocess.run(cmd, capture_output=True, text=True, check=True).stdout.splitlines()
    print("\nTODO comments found in the Splice codebase:")
    todo_refs = set()
    for line in todo_comments:
        split = line.split(':')
        filename = split[0]
        line_number = split[1]
        todo = split[2]
        match = re.search(r'TODO ?\(#(\d+)\)', todo)
        ref = int(match.group(1))
        todo_refs.add(ref)
        if ref in all.public:
            # Referencing a public issue from code in the public repo
            new_ref = f"#{public_issues_map[ref]}"
        elif ref in all.internal:
            # Referencing an internal issue from code in the public repo
            new_ref = f"DACH-NY/canton-network-internal#{internal_issues_map[ref]}"
        else:
            # Unknown reference, most probably a closed issue in the old DA repo
            new_ref = f"DACH-NY/canton-network-node#{ref}"
        new_ref = new_ref.replace("/", "\\/")
        # print(f"Running: sed -i '{line_number}s/#{ref}/{new_ref}/' {filename}")
        subprocess.run(['sed', '-i', f'{line_number}s/#{ref}/{new_ref}/', filename], check=True)

    internal_todo_refs = [ref for ref in todo_refs if ref in all.internal]
    public_todo_refs = [ref for ref in todo_refs if ref in all.public]
    unknonwn_todo_refs = [ref for ref in todo_refs if ref not in all.internal and ref not in all.public]
    print(f"  {len(public_todo_refs)} TODO references to the public repo: {[f"{ref} => #{public_issues_map[ref]}" for ref in public_todo_refs]}")
    print(f"  {len(internal_todo_refs)} TODO references to the internal repo: {[f"{ref} => #{internal_issues_map[ref]}" for ref in internal_todo_refs]}")
    print(f"  {len(unknonwn_todo_refs)} unknown TODO references: {unknonwn_todo_refs}")

    todo_comments = subprocess.run(['rg', r'TODO ?\(#(\d+)\)', '--vimgrep', '-o', '--no-column', internal_repo_path, '-g', '!**/splice/**', '-g', '!**/cluster/configs/configs/**', '-.'], capture_output=True, text=True, check=True).stdout.splitlines()
    print("\nTODO comments found in the internal codebase:")
    todo_refs = set()
    for line in todo_comments:
        split = line.split(':')
        filename = split[0]
        line_number = split[1]
        todo = split[2]
        match = re.search(r'\(#(\d+)\)', todo)
        ref = int(match.group(1))
        todo_refs.add(ref)
        if ref in all.public:
            # Referencing a public issue from code in the internal repo
            new_ref = f"hyperledger-labs/splice#{public_issues_map[ref]}"
        elif ref in all.internal:
            # Referencing an internal issue from code in the internal repo
            new_ref = f"#{internal_issues_map[ref]}"
        else:
            # Unknown reference, most probably a closed issue in the old DA repo
            new_ref = f"DACH-NY/canton-network-node#{ref}"
        new_ref = new_ref.replace("/", "\\/")
        # print(f"Running: sed -i '{line_number}s/#{ref}/{new_ref}/' {filename}")
        subprocess.run(['sed', '-i', f'{line_number}s/#{ref}/{new_ref}/', filename], check=True)

    internal_todo_refs = [ref for ref in todo_refs if ref in all.internal]
    public_todo_refs = [ref for ref in todo_refs if ref in all.public]
    unknonwn_todo_refs = [ref for ref in todo_refs if ref not in all.internal and ref not in all.public]
    print(f"  {len(public_todo_refs)} TODO references to the public repo: {[f"{ref} => #{public_issues_map[ref]}" for ref in public_todo_refs]}")
    print(f"  {len(internal_todo_refs)} TODO references to the internal repo: {[f"{ref} => #{internal_issues_map[ref]}" for ref in internal_todo_refs]}")
    print(f"  {len(unknonwn_todo_refs)} unknown TODO references: {unknonwn_todo_refs}")

def validate_issue_references(all: IssueCollection):
    with_subissues = [issue for issue in all.internal.values() if issue.has_sub_issues] + [issue for issue in all.public.values() if issue.has_sub_issues]
    if with_subissues:
        print(f"\nWARNING: {len(with_subissues)} issues have sub-issues, that relation will be lost")
        for issue in with_subissues:
            print(f" - #{issue.number} ({issue.title})")

    internal_refs = [ref for issue in all.internal.values() for ref in issue.refs]
    public_refs = [ref for issue in all.public.values() for ref in issue.refs]

    from_internal_to_public = [ref for ref in internal_refs if ref in all.public]
    from_public_to_internal = [ref for ref in public_refs if ref in all.internal]

    if from_internal_to_public:
        print("\nERROR: Internal issues referencing public issues:")
        for ref in from_internal_to_public:
            print(f"  Public issue {ref} is referenced by internal issue(s): {find_ref_sources(all, ref)[0]}")
        raise ValueError("Internal issues should not reference public issues. Please fix the references before migrating.")

    if from_public_to_internal:
        print("\nERROR: Public issues referencing internal issues:")
        for ref in from_public_to_internal:
            print(f"  Internal issue {ref} is referenced by public issue(s): {find_ref_sources(all, ref)[1]}")
        raise ValueError("Public issues should not reference internal issues. Please fix the references before migrating.")

def migrate_milestones(milestones: list[Milestone], trgt: TargetRepo, state: State, milestones_map_state: dict[int, int], state_filename: str):
    for milestone in milestones:
        if not milestone.number in milestones_map_state:
            target = trgt.new_milestone(milestone.title, milestone.description)
            milestones_map_state[milestone.number] = target
            save_state(state, state_filename)
            print(f"Milestone {milestone.number} => {target}: {milestone.title}")
        else:
            print(f"Milestone {milestone.number} already migrated")

def process(all: IssueCollection, public_repo: str, public_repo_path: str, internal_repo:str, internal_repo_path: str, state_filename: str, dry_run: bool = True):
    print(f"Found {len(all.internal)} internal issues and {len(all.public)} public issues.")

    if dry_run:
        trgt_internal = DryRunTargetRepo("internal")
        trgt_public = DryRunTargetRepo("public")
    else:
        trgt_internal = LiveTargetRepo(internal_repo)
        trgt_public = LiveTargetRepo(public_repo)

    if os.path.exists(state_filename):
        print("Rehydrating state from file")
        with open(state_filename, 'r', encoding='utf-8') as f:
            content = f.read()
            state = State.model_validate_json(content)
    else:
        state = State(
            public_milestones_map={},
            internal_milestones_map={},
            public_sorted_issues=[],
            internal_sorted_issues=[],
            public_issues_map={},
            internal_issues_map={},
            public_num_comments={},
            internal_num_comments={}
        )

    migrate_milestones(all.public_milestones, trgt_public, state, state.public_milestones_map, state_filename)
    migrate_milestones(all.internal_milestones, trgt_internal, state, state.internal_milestones_map, state_filename)

    validate_issue_references(all)

    state.public_sorted_issues = sort_issues(all.public)
    state.internal_sorted_issues = sort_issues(all.internal)
    save_state(state, state_filename)
    migrate_issues(all.public, state.public_sorted_issues, trgt_public, state.public_milestones_map, state.public_issues_map, state.public_num_comments, state, state_filename)
    migrate_issues(all.internal, state.internal_sorted_issues, trgt_internal, state.internal_milestones_map, state.internal_issues_map, state.internal_num_comments, state, state_filename)

    migrate_todo_comments(all, public_repo_path, internal_repo_path, state.public_issues_map, state.internal_issues_map)

def main():
    parser = argparse.ArgumentParser(description="Migrate GitHub issues between repos.")
    parser.add_argument("--renew-dump", action="store_true", help="Renew the dump from GitHub instead of reading from the file.")
    parser.add_argument("--limit", type=int, default=None, help="Limit the number of issues to process (useful for quick iterations during testing).")
    parser.add_argument("--dump-filename", type=str, default="issues-dump.json", help="Filename to read/write the issues dump (default: issues-dump.json).")
    parser.add_argument("--source-repo", type=str, default="DACH-NY/canton-network-node", help="GitHub repository to read issues from (default: DACH-NY/canton-network-node).")
    parser.add_argument("--dry-run", action="store_true", default=False, help="If set, the script will not modify any data, only print what it would do.")
    parser.add_argument("--public-repo", type=str, default="hyperledger-labs/splice", help="Public GitHub repository to migrate issues to (default: hyperledger-labs/splice).")
    parser.add_argument("--public-repo-path", type=str, help= "Path to the public repo (used for TODO comments)")
    parser.add_argument("--internal-repo", type=str, default="DACH-NY/canton-network-internal", help="Internal GitHub repository to migrate issues to (default: DACH-NY/canton-network-internal).")
    parser.add_argument("--internal-repo-path", type=str, help= "Path to the internal repo (used for TODO comments)")
    parser.add_argument("--state-filename", default="issues-migration-state.json", help="Filename for stored intermediate state")
    args = parser.parse_args()

    if not args.public_repo_path or not args.internal_repo_path:
        raise ValueError("Both --public-repo-path and --internal-repo-path must be set for TODO comment processing.")

    if not args.dry_run and (not args.public_repo or not args.internal_repo):
        raise ValueError("When not in dry run mode, both --public-repo and --internal-repo must be set.")

    if args.renew_dump:
        read_from_github(args.source_repo, args.dump_filename, args.limit)

    all = read_from_file(args.dump_filename)

    while True:
        try:
            process(all, args.public_repo, args.public_repo_path, args.internal_repo, args.internal_repo_path, args.state_filename, args.dry_run)
            break
        except RetryError as e:
            print("An error occurred during migration, retrying in 10 minutes...")
            time.sleep(600)
            continue

if __name__ == "__main__":
    main()
