#!/usr/bin/env python3
"""Validate the reviewed native-input notice set and stage unchanged texts."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import shutil


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def inventory(path):
    rows = []
    for line in path.read_text().splitlines():
        config, coordinate, artifact, sha = line.split('\t')
        rows.append(dict(configuration=config, coordinate=coordinate, artifact=artifact, sha256=sha))
    return sorted(rows, key=lambda x: (x['configuration'], x['coordinate'], x['artifact']))


def validate(root, resolved=None, destination=None, verify=False, archive_verify=False):
    if archive_verify and (destination is None or not verify or resolved is not None):
        raise ValueError('Archive-only verification requires destination/verify and cannot assert local resolution')
    source = root / 'distribution/licenses'
    manifest = json.loads((source / 'manifest.json').read_text())
    if manifest['schema'] != 1 or len(manifest['inputs']) != 48:
        raise ValueError('Unexpected native notice inventory schema or input count')
    properties = dict(line.split('=', 1) for line in (root / 'distribution/release.properties').read_text().splitlines() if '=' in line and not line.startswith('#'))
    if properties['KOTLIN_VERSION'] != manifest['kotlin']:
        raise ValueError('Kotlin runtime changed; review native notices')
    files = {'LICENSE': root / 'LICENSE', 'THIRD_PARTY_NOTICES/README.md': source / 'README.md', 'THIRD_PARTY_NOTICES/manifest.json': source / 'manifest.json'}
    for name, entry in manifest['notices'].items():
        relative = PurePosixPath(name)
        if relative.is_absolute() or '..' in relative.parts or '\\' in name:
            raise ValueError('Unsafe notice path')
        path = source / name
        if any(parent.is_symlink() for parent in (path, *path.parents)) or not path.is_file() or digest(path) != entry['sha256']:
            raise ValueError('Missing or changed reviewed notice: ' + name)
        files['THIRD_PARTY_NOTICES/' + name] = path
    actual = {str(p.relative_to(source)) for p in source.rglob('*') if p.is_file()}
    if actual != set(manifest['notices']) | {'README.md', 'manifest.json'}:
        raise ValueError('Uninventoried native notice file')
    if resolved is not None and inventory(resolved) != manifest['inputs']:
        raise ValueError('Native dependency inputs changed; review licenses before packaging')
    if destination is not None:
        if resolved is None and not archive_verify:
            raise ValueError('Packaging requires resolved dependency evidence')
        if not destination.is_dir() or destination.is_symlink():
            raise ValueError('Expected regular XCFramework directory')
        notices = destination / 'THIRD_PARTY_NOTICES'
        if notices.is_symlink() or any(path.is_symlink() for path in notices.rglob('*')):
            raise ValueError('Unsafe notice destination')
        if not verify:
            if notices.exists():
                shutil.rmtree(notices)
            for relative, original in files.items():
                target = destination / relative
                if target.is_symlink():
                    raise ValueError('Unsafe notice destination')
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(original, target)
        for relative, original in files.items():
            target = destination / relative
            if not target.is_file() or target.is_symlink() or digest(target) != digest(original):
                raise ValueError('Archive notice missing or changed: ' + relative)
        staged = {str(p.relative_to(destination)) for p in (destination / 'THIRD_PARTY_NOTICES').rglob('*') if p.is_file()}
        if staged != set(files) - {'LICENSE'}:
            raise ValueError('Unexpected archive notice contents')
    return len(manifest['inputs']), len(manifest['notices'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument('--resolved', type=Path)
    parser.add_argument('--destination', type=Path)
    parser.add_argument('--verify', action='store_true')
    parser.add_argument('--archive-verify', action='store_true', help='Read-only legal-byte comparison against frozen source; no local resolution claim')
    args = parser.parse_args()
    try:
        inputs, notices = validate(args.root, args.resolved, args.destination, args.verify, args.archive_verify)
    except (ValueError, KeyError, OSError) as error:
        parser.exit(1, str(error) + '\n')
    if args.archive_verify:
        print(f'Archive legal-byte verification passed: {inputs} reviewed source inputs, {notices} notice files; no local resolution asserted.')
    else:
        print(f'Native notice validation passed: {inputs} reviewed inputs, {notices} notice files.')


if __name__ == '__main__':
    main()
