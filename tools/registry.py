"""Read-only, fail-closed checks for the dedicated public Maven registry."""
import hashlib
import json
import pathlib
import re
import subprocess
import sys
import urllib.parse
import urllib.request

PROJECT = 'https://gitlab.com/api/v4/projects/86375734'
REGISTRY = PROJECT + '/packages/maven'
STATUSES = ('default', 'hidden', 'processing', 'error', 'pending_destruction', 'deprecated')


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError('unexpected registry redirect')


def read_bytes(url):
    # Deliberately anonymous: successful authenticated reads would not prove
    # public consumer access. GitLab may redirect package downloads to storage.
    with urllib.request.urlopen(url, timeout=60) as response:
        if response.geturl().split(':', 1)[0] != 'https':
            raise ValueError('insecure download redirect')
        data = response.read(50_000_001)
        if len(data) > 50_000_000:
            raise ValueError('unexpectedly large registry artifact')
        return data


def read_json(url):
    # A redirect/sign-in page or failed API request is never evidence of absence.
    with urllib.request.build_opener(NoRedirect).open(url, timeout=60) as response:
        data = json.load(response)
        if not isinstance(data, list):
            raise ValueError('invalid package listing')
        return data, response.headers.get('X-Next-Page', '')


def validate_version(version):
    if not re.fullmatch(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)', version):
        raise ValueError('registry operations require a numeric release version')


def require_absent(artifacts, version):
    validate_version(version)
    for artifact in artifacts:
        name = 'com/ratelimitly/' + artifact
        # GitLab's default listing omits processing/hidden/pending-destruction
        # packages. Check every documented state, not just complete releases.
        for status in STATUSES:
            page = '1'
            seen = set()
            while page:
                if not page.isdecimal() or page in seen:
                    raise ValueError('invalid registry pagination')
                seen.add(page)
                query = urllib.parse.urlencode(dict(package_type='maven', package_name=name,
                                                    package_version=version, status=status,
                                                    per_page=100, page=page))
                rows, page = read_json(PROJECT + '/packages?' + query)
                if any(row.get('name') == name and row.get('version') == version for row in rows):
                    raise ValueError('coordinate already exists, possibly incomplete: ' + name + ':' + version)


def compare_download(url, local):
    data = read_bytes(url)
    if data != local.read_bytes():
        raise ValueError('registry artifact differs from reviewed build: ' + local.name)
    return data


def check_signature_status(status, fingerprint):
    signatures = [line.split()[2] for line in status.splitlines()
                  if line.startswith('[GNUPG:] VALIDSIG ')]
    if signatures != [fingerprint.upper()] or any(code in status for code in (
            'BADSIG', 'ERRSIG', 'EXPSIG', 'EXPKEYSIG', 'REVKEYSIG')):
        raise ValueError('signature does not match the pinned valid signing key')


def verify(assets, output, version, fingerprint):
    validate_version(version)
    if not re.fullmatch('[0-9A-Fa-f]{40}', fingerprint):
        raise ValueError('full signing fingerprint required')
    files = sorted(p for p in assets.iterdir() if p.suffix in ('.jar', '.pom'))
    if not files:
        raise ValueError('missing reviewed assets')
    output.mkdir(parents=True, exist_ok=False)
    for local in files:
        # Classifiers follow the version, so identify the artifact from its POM
        # basename rather than guessing from a potentially ambiguous JAR name.
        artifact = local.name.split('-' + version, 1)[0]
        if not re.fullmatch('ratelimitly-[a-z-]+', artifact):
            raise ValueError('unexpected artifact name')
        url = f'{REGISTRY}/com/ratelimitly/{artifact}/{version}/{local.name}'
        payload = output / local.name
        payload.write_bytes(compare_download(url, local))
        signature = output / (local.name + '.asc')
        signature.write_bytes(read_bytes(url + '.asc'))
        result = subprocess.run(['gpg', '--batch', '--status-fd=1', '--verify',
                                 str(signature), str(payload)], check=True,
                                capture_output=True, text=True)
        check_signature_status(result.stdout, fingerprint)
    public_key = subprocess.run(['gpg', '--batch', '--armor', '--export', fingerprint],
                                capture_output=True, check=True).stdout
    if not public_key:
        raise ValueError('public signing key missing')
    (output / 'signing-key.asc').write_bytes(public_key)
    checksums = ''.join(hashlib.sha256(p.read_bytes()).hexdigest() + '  ' + p.name + '\n'
                        for p in sorted(output.iterdir()))
    (output / 'SHA256SUMS').write_text(checksums)


if __name__ == '__main__':
    if sys.argv[1] == 'absent':
        require_absent(sys.argv[3:], sys.argv[2])
    elif sys.argv[1] == 'verify':
        verify(pathlib.Path(sys.argv[2]), pathlib.Path(sys.argv[3]), sys.argv[4], sys.argv[5])
    else:
        sys.exit('unknown registry command')
