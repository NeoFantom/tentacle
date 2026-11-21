"""Implementer PR publishing, localized outcomes and durable phone follow-ups."""
from __future__ import annotations

import json
import math
from pathlib import Path
import re
import secrets
import time
import unicodedata

from .phone import PhoneError
from .requests_git import Gh, RequestFailure, default_branch, resolve_repo

PHONE_TEXTS = {
    'sent': (
        'Sent to the maintainer for review: {url}. Once merged and released, the app offers the update.',
        '已提交给维护者审查：{url}。合并并发布新版本后，应用会提示更新。'),
    'merged': (
        'The maintainer decided to complete this feature (PR #{number} merged). The app will offer the update after the next release.',
        '维护者决定完成这个功能（PR #{number} 已合并），新版本发布后应用会提示更新。'),
    'closed': (
        'The maintainer closed this request without merging it.',
        '维护者关闭了这个请求，未合并更改。'),
    'fork': (
        'This change only suits your own setup and needs your own fork. Configure it with tentacle requests setup --fork-repo PATH.',
        '此更改只适合你的个人使用方式，需要你自己的 fork。请运行 tentacle requests setup --fork-repo PATH 进行配置。'),
}


def phone_text(key: str, text: str, **values) -> str:
    from .requests_cmd import CJK_RANGES
    letters = [c for c in text if unicodedata.category(c)[0] in 'LN']
    cjk = sum(any(lo <= ord(c) <= hi for lo, hi in CJK_RANGES) for c in letters)
    return PHONE_TEXTS[key][bool(letters) and cjk > len(letters) / 2].format(**values)


def load_prs(path: Path) -> list[dict]:
    if not path.exists():
        return []
    try:
        entries = json.loads(path.read_text(encoding='utf-8'))['prs']
        if not isinstance(entries, list):
            raise ValueError('invalid entries')
        for item in entries:
            if (not isinstance(item, dict)
                    or not isinstance(item.get('id'), str)
                    or not re.fullmatch(r'[A-Za-z0-9_-]{1,64}', item['id'])
                    or not isinstance(item.get('repo'), str)
                    or not re.fullmatch(r'[\w.-]+/[\w.-]+', item['repo'])
                    or type(item.get('number')) is not int or item['number'] <= 0
                    or not all(isinstance(item.get(key), str) for key in ('url', 'text'))):
                raise ValueError('invalid PR identity')
            for key in ('created_at', 'checked_at', 'pending_since'):
                if key == 'pending_since' and key not in item:
                    continue
                value = item.get(key)
                if type(value) not in (int, float) or not math.isfinite(value) or value < 0:
                    raise ValueError('invalid timestamp')
            if 'outcome' in item and (item['outcome'] not in ('done', 'failed')
                                      or not isinstance(item.get('message'), str)
                                      or 'pending_since' not in item):
                raise ValueError('invalid pending outcome')
            if 'superseded' in item and type(item['superseded']) is not bool:
                raise ValueError('invalid retry marker')
        return entries
    except (KeyError, TypeError, ValueError) as exc:
        raise RequestFailure('Local requests-prs.json is unreadable; review it before continuing.') from exc


class PrFlow:
    """Mixin using Watcher's checked git, command, result and phone primitives."""

    def prepare_branch(self, repo: Path, request_id: str) -> tuple[str, str]:
        branch = default_branch(self, repo)
        self.git(repo, 'switch', branch)
        self.git(repo, 'merge', '--ff-only', self.options.push_remote + '/' + branch)
        if self.git(repo, 'rev-parse', 'HEAD') != self.git(repo, 'rev-parse', self.options.push_remote + '/' + branch):
            raise RequestFailure('The base branch has unpublished commits; review them before running requests.')
        name = 'tentacle-request/' + request_id
        # Retrying a closed/failed request must not overwrite its previous history.
        existing = self.git(repo, 'branch', '--list', name)
        if existing:
            name += '-' + secrets.token_hex(4)
        self.git(repo, 'switch', '-c', name)
        return branch, name

    def check_versions(self, repo: Path, base: str) -> None:
        diff = self.git(repo, 'diff', '--no-ext-diff', '--unified=0', base + '..HEAD', '--',
                        'android/app/build.gradle.kts', 'pc/pyproject.toml', 'pc/src/tentacle/__init__.py')
        if any(line[:1] in ('+', '-') and not line.startswith(('+++', '---'))
               and re.search(r'\b(versionCode|versionName|__version__|version)\s*=', line)
               for line in diff.splitlines()):
            raise RequestFailure('Upstream PR branches must not bump versions; the maintainer bumps versions after merge.')

    def publish_pr(self, request: dict, repo: Path, branch: str, base_branch: str,
                   message: str, folder: Path) -> str:
        from .requests_cmd import plain, write_json
        gh = Gh(repo)
        gh.check_auth()
        destination, push_name = resolve_repo(self, repo)
        # Read the ledger before pushing so corrupt state cannot lose a new PR.
        path = self.cfg.data_dir / 'requests-prs.json'
        entries = load_prs(path)
        state = json.loads((folder / 'state.json').read_text(encoding='utf-8'))
        state['push_attempted'] = True
        write_json(folder / 'state.json', state)
        self.git(repo, 'push', self.options.push_remote, 'HEAD:refs/heads/' + branch)
        state['pushed'] = True
        write_json(folder / 'state.json', state)
        head = branch if destination == push_name else push_name.split('/')[0] + ':' + branch
        message = self.clean(message)
        quoted = '\n'.join('> ' + line for line in self.clean(request['text']).splitlines())
        body = (message + '\n\nUntrusted phone request:\n\n' + quoted
                + f"\n\nRequest id: {request['id']}\nKind: {request['kind']}\n\n"
                + f"<!-- tentacle-request:v1 id={request['id']} -->\n")
        url = gh.call('pr', 'create', '--repo', destination, '--head', head,
                      '--base', base_branch, '--title', 'Request: ' + plain(message)[:90], '--body', body)
        match = re.search(r'/pull/(\d+)\s*$', url)
        if not match:
            raise RequestFailure('GitHub CLI did not return a pull request URL; check the pushed branch before retrying.')
        now = time.time()
        entries.append(dict(id=request['id'], repo=destination, number=int(match[1]), url=url,
                            text=request['text'], created_at=now, checked_at=0))
        write_json(path, dict(prs=entries))
        return phone_text('sent', request['text'], url=url)

    def follow_up(self) -> None:
        """Check each PR at most every five minutes, keeping pending updates durable."""
        from .requests_cmd import plain, write_json
        path = self.cfg.data_dir / 'requests-prs.json'
        entries = load_prs(path)
        if not entries:
            return
        now = time.time()
        gh = Gh(self.options.repo_path)
        changed = False
        retained = []
        for item in entries:
            if item.get('superseded'):
                changed = True
                continue
            retained.append(item)
            if now - item.get('checked_at', 0) < 300:
                continue
            item['checked_at'] = now
            changed = True
            try:
                if 'outcome' not in item:
                    gh.check_auth()
                    info = gh.json('pr', 'view', str(item['number']), '--repo', item['repo'],
                                   '--json', 'state,mergedAt,url,comments')
                    if info.get('mergedAt') or info.get('state') == 'MERGED':
                        item['outcome'] = 'done'
                        item['message'] = phone_text('merged', item['text'], number=item['number'])
                    elif info.get('state') == 'CLOSED':
                        comments = info.get('comments') or []
                        comment = next((plain(c.get('body', '')) for c in reversed(comments)
                                        if c.get('authorAssociation') in ('OWNER', 'MEMBER', 'COLLABORATOR')
                                        and plain(c.get('body', ''))), '')
                        item['outcome'] = 'failed'
                        item['message'] = comment[:1000] or phone_text('closed', item['text'])
                    else:
                        continue
                    item['pending_since'] = now
                capabilities = self.client.ping().get('requests')
                supported = capabilities is None or (isinstance(capabilities, dict)
                                                     and capabilities.get('followup') is True)
                if supported:
                    try:
                        self.client.update_request(item['id'], item['outcome'], self.clean(item['message'])[:1000])
                    except PhoneError as exc:
                        if exc.status == 404:
                            retained.pop()  # Phone retained only its newest 20 requests.
                            continue
                        if exc.status != 409:
                            raise
                    else:
                        retained.pop()
                        continue
                if now - item.get('pending_since', now) >= 7 * 86400:
                    self.report(f"Dropping PR follow-up for {item['id']} after 7 days; update the old phone app.")
                    retained.pop()
            except RequestFailure as exc:
                self.report(self.clean(str(exc)))
            except PhoneError as exc:
                self.report(f"PR follow-up phone update failed ({exc.code}); will retry.")
        if changed:
            write_json(path, dict(prs=retained))
