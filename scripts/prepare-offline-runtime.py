"""Assemble pinned official MySQL OCI layers and Termux PRoot into APK assets.

No container daemon, privileged install, or guest execution is used on the host.
All downloads and outputs are kept inside this project. SHA256 is checked before
parsing every layer/package. Symlinks are represented in a manifest for Android.
"""
import argparse, gzip, hashlib, io, json, os, pathlib, tarfile, urllib.request, zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
CACHE = ROOT / '.runtime/offline/cache'
CACHE.mkdir(parents=True, exist_ok=True)
MANIFESTS = {
    'arm64-v8a': ('arm64', 'aarch64', 'sha256:0e7040b532c0f2ac8cb822695d33025522acd5252175cb104a5929aa66b40222'),
    'x86_64': ('amd64', 'x86_64', 'sha256:bcfecfdd2f8c2988c0db7335dfeed0dc336defe7007e98c5308f58168d808a05'),
}
REGISTRY = 'https://registry-1.docker.io/v2/library/mysql'

def request(url, headers=None):
    return urllib.request.urlopen(urllib.request.Request(url, headers=headers or {}), timeout=180)

def fetch(url, name, digest=None, headers=None):
    dest = CACHE / name
    if dest.exists() and (not digest or hashlib.sha256(dest.read_bytes()).hexdigest() == digest):
        return dest
    print('Download', name, flush=True)
    temporary = dest.with_suffix(dest.suffix + '.part')
    h = hashlib.sha256()
    with request(url, headers) as source, temporary.open('wb') as target:
        while data := source.read(1024 * 1024):
            target.write(data)
            h.update(data)
    if digest and h.hexdigest() != digest:
        raise ValueError('Checksum mismatch: ' + name)
    temporary.replace(dest)
    return dest

def package_index(arch):
    lock_file = ROOT / 'offline-runtime-lock.json'
    if lock_file.exists():
        lock = json.loads(lock_file.read_text(encoding='utf-8'))
        abi = 'arm64-v8a' if arch == 'aarch64' else 'x86_64'
        if abi in lock:
            return {p['package']: {'Package': p['package'], 'Version': p['version'], 'Filename': p['url'].split('/termux-main/')[1], 'SHA256': p['sha256']} for p in lock[abi]['nativePackages']}
    url = f'https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-{arch}/Packages.gz'
    content = gzip.decompress(request(url).read()).decode()
    result = {}
    for section in content.split('\n\n'):
        fields = {}
        for line in section.splitlines():
            if ': ' in line and not line.startswith(' '):
                key, value = line.split(': ', 1)
                fields[key] = value
        if fields.get('Package') in ['proot', 'libtalloc', 'libandroid-shmem']:
            result[fields['Package']] = fields
    return result

def deb_members(data):
    if data[:8] != b'!<arch>\n':
        raise ValueError('Invalid Debian package')
    offset = 8
    while offset + 60 <= len(data):
        header = data[offset:offset + 60]
        name = header[:16].decode().strip().rstrip('/')
        size = int(header[48:58].decode().strip())
        start = offset + 60
        if name.startswith('data.tar'):
            with tarfile.open(fileobj=io.BytesIO(data[start:start + size]), mode='r:*') as archive:
                for member in archive:
                    if member.isfile():
                        yield member.name, archive.extractfile(member).read()
        offset = start + size + size % 2

def native_libraries(abi, arch):
    output = ROOT / '.runtime/offline/jniLibs' / abi
    output.mkdir(parents=True, exist_ok=True)
    packages = package_index(arch)
    records = []
    for name in ['proot', 'libtalloc', 'libandroid-shmem']:
        meta = packages[name]
        url = 'https://packages.termux.dev/apt/termux-main/' + meta['Filename']
        path = fetch(url, meta['Filename'].split('/')[-1], meta['SHA256'])
        records.append({'package': name, 'version': meta['Version'], 'url': url, 'sha256': meta['SHA256']})
        for member, data in deb_members(path.read_bytes()):
            target = None
            if member.endswith('/bin/proot'):
                target = 'libproot.so'
                # Keep ELF string-table offsets unchanged; Android extracts lib*.so only.
                data = data.replace(b'libtalloc.so.2\0', b'libtalloc.so\0\0\0')
            elif '/libexec/proot/' in member and member.endswith('/loader'):
                target = 'libproot_loader.so'
            elif '/lib/' in member and '/libtalloc.so.' in member:
                target = 'libtalloc.so'
            elif member.endswith('/libandroid-shmem.so'):
                target = 'libandroid-shmem.so'
            if target:
                (output / target).write_bytes(data)
    for name in ['libproot.so', 'libproot_loader.so', 'libtalloc.so', 'libandroid-shmem.so']:
        if not (output / name).exists():
            raise ValueError('Missing native library: ' + name)
    return records

def assemble(abi):
    arch, termux_arch, digest = MANIFESTS[abi]
    with request('https://auth.docker.io/token?service=registry.docker.io&scope=repository:library/mysql:pull') as r:
        token = json.load(r)['token']
    headers = {'Authorization': 'Bearer ' + token, 'Accept': 'application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json'}
    manifest_file = fetch(REGISTRY + '/manifests/' + digest, abi + '-manifest.json', digest.split(':')[1], headers)
    manifest = json.loads(manifest_file.read_text())
    native = native_libraries(abi, termux_arch)
    entries = {}
    archives = []
    layers = []
    for layer in manifest['layers']:
        sha = layer['digest'].split(':')[1]
        file = fetch(REGISTRY + '/blobs/' + layer['digest'], sha + '.tar.gz', sha, headers)
        print('Index layer', abi, sha[:12], layer['size'], flush=True)
        archive = tarfile.open(file, 'r:*')
        archives.append(archive)
        for member in archive:
            name = member.name.removeprefix('./').lstrip('/')
            if '..' in name.split('/'):
                raise ValueError('Unsafe image path')
            base = pathlib.PurePosixPath(name).name
            if base.startswith('.wh.'):
                if base == '.wh..wh..opq':
                    parent = str(pathlib.PurePosixPath(name).parent) + '/'
                    entries = {key: value for key, value in entries.items() if not key.startswith(parent)}
                else:
                    dead = str(pathlib.PurePosixPath(name).with_name(base[4:]))
                    entries = {key: value for key, value in entries.items() if key != dead and not key.startswith(dead + '/')}
                continue
            if member.isfile() or member.issym() or member.islnk() or member.isdir():
                entries[name] = (archive, member)
        layers.append({'digest': layer['digest'], 'size': layer['size']})
    # Keep a compact immutable runtime. Data directories are created per project.
    omit = ('var/lib/mysql/', 'var/cache/', 'usr/share/man/', 'usr/share/locale/', 'usr/lib64/mysql/private/', 'usr/lib/debug/', 'usr/lib/mysqlsh/', 'usr/bin/mysqlsh', 'usr/share/mysqlsh/')
    omitted_plugins = ('group_replication.so', 'mysql_clone.so', 'ha_example.so', 'ha_federated.so', 'ha_blackhole.so')
    kept = {name: entry for name, entry in entries.items() if not name.startswith(omit) and not name.endswith(omitted_plugins)}
    # Private libraries are needed by client utilities on some image releases.
    for name, entry in entries.items():
        if name.startswith('usr/lib64/mysql/private/') and entry[1].size < 16 * 1024 * 1024:
            kept[name] = entry
    assets = ROOT / '.runtime/offline/android-assets' / abi / 'offline-engine'
    assets.mkdir(parents=True, exist_ok=True)
    output = assets / 'mysql-runtime.zip'
    metadata = {'format': 1, 'engine': 'MySQL Community Server', 'version': '8.0.45', 'abi': abi, 'source': 'docker.io/library/mysql:8.0.45', 'manifest': digest, 'layers': layers, 'nativePackages': native, 'entries': []}
    total = 0
    with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as target:
        for name, (archive, member) in sorted(kept.items()):
            if not name:
                continue
            record = {'path': name, 'mode': member.mode}
            if member.issym():
                record.update(type='symlink', target=member.linkname)
            elif member.islnk():
                # Resolve tar hard links as ordinary files, avoiding Android hard-link restrictions.
                link = member.linkname.removeprefix('./').lstrip('/')
                seen = set()
                while link in entries and entries[link][1].islnk():
                    if link in seen:
                        raise ValueError('Cyclic image hard link')
                    seen.add(link)
                    link = entries[link][1].linkname.removeprefix('./').lstrip('/')
                linked_archive, linked_member = entries[link]
                data = linked_archive.extractfile(linked_member).read()
                target.writestr(name, data)
                total += len(data)
                record['type'] = 'file'
            elif member.isdir():
                record['type'] = 'dir'
            else:
                data = archive.extractfile(member).read()
                target.writestr(name, data)
                total += len(data)
                record['type'] = 'file'
            metadata['entries'].append(record)
        target.writestr('runtime-manifest.json', json.dumps(metadata))
    for archive in archives:
        archive.close()
    metadata.pop('entries')
    metadata.update(runtimeSha256=hashlib.sha256(output.read_bytes()).hexdigest(), compressedBytes=output.stat().st_size, extractedBytes=total)
    (assets / 'runtime-info.json').write_text(json.dumps(metadata, indent=2), encoding='utf-8')
    print(json.dumps(metadata, indent=2), flush=True)

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--abi', choices=MANIFESTS, required=True)
    assemble(parser.parse_args().abi)
