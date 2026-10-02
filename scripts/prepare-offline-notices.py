"""Keep upstream notices and matching source archives beside the offline APK."""
import argparse, hashlib, json, pathlib, tarfile, urllib.request, zipfile
parser=argparse.ArgumentParser()
parser.add_argument('--abi',choices=['arm64-v8a','x86_64'],default='arm64-v8a')
args=parser.parse_args()
ROOT=pathlib.Path(__file__).resolve().parents[1]
OUT=ROOT/'outputs/source/offline'
OUT.mkdir(parents=True,exist_ok=True)
SOURCES=[
 ('proot-5.1.107.96.zip','https://github.com/termux/proot/archive/v5.1.107.96.zip','75f654fe60dea92dabff2bf083ae8bfe4f91baa6a1a374786a6bf391015eebaa'),
 ('talloc-2.4.3.tar.gz','https://www.samba.org/ftp/talloc/talloc-2.4.3.tar.gz','dc46c40b9f46bb34dd97fe41f548b0e8b247b77a918576733c528e83abd854dd'),
 ('libandroid-shmem-0.7.tar.gz','https://github.com/termux/libandroid-shmem/archive/refs/tags/v0.7.tar.gz','1e5ff8459bc0a8c229dd8a94b27d119987e09ef3414331c2b5ebfff20b98e867'),
 ('mysql-8.0.45.tar.gz','https://codeload.github.com/mysql/mysql-server/tar.gz/refs/tags/mysql-8.0.45','30bba507ccc4221e29107e0936ce01d85b784697f90f51db1b370d6164fde829'),
]
records=[];notices=['\n\nOFFLINE ANDROID MYSQL LAB — upstream notices\nMySQL runs as a separate process; the App communicates over a private Unix socket.\nPRoot libtalloc SONAME is patched by scripts/prepare-offline-runtime.py (2026-10-02).\nMatching upstream sources and source records accompany the APK in outputs/source/offline.\nMySQL and Oracle Linux source package access: https://repo.mysql.com/ and https://oss.oracle.com/ol9/SRPMS/\n']
for name,url,digest in SOURCES:
    p=OUT/name
    if not p.exists():
        print('Download source',name,flush=True)
        with urllib.request.urlopen(url,timeout=180) as r,p.with_suffix(p.suffix+'.part').open('wb') as f:
            while b:=r.read(1024*1024):f.write(b)
        p.with_suffix(p.suffix+'.part').replace(p)
    sha=hashlib.sha256(p.read_bytes()).hexdigest()
    if digest and sha!=digest:raise ValueError('Source checksum mismatch: '+name)
    records.append(dict(file=name,url=url,sha256=sha))
    if name.endswith('.zip'):
        with zipfile.ZipFile(p) as z:
            for entry in z.namelist():
                if entry.count('/')==1 and entry.rsplit('/',1)[-1] in ['COPYING','LICENSE']:
                    notices.append('\n\n'+entry+'\n'+z.read(entry).decode('utf-8',errors='replace'))
    else:
        with tarfile.open(p) as t:
            for entry in t:
                if entry.isfile() and entry.name.count('/')==1 and entry.name.rsplit('/',1)[-1] in ['COPYING','LICENSE']:
                    notices.append('\n\n'+entry.name+'\n'+t.extractfile(entry).read().decode('utf-8',errors='replace'))
# Preserve all license documents present in the shipped Oracle Linux image.
runtime=ROOT/f'.runtime/offline/android-assets/{args.abi}/offline-engine/mysql-runtime.zip'
if runtime.exists():
    with zipfile.ZipFile(runtime) as z:
        for entry in z.namelist():
            if entry.startswith('usr/share/licenses/') and z.getinfo(entry).file_size<1024*1024:
                notices.append('\n\nOracle Linux: '+entry+'\n'+z.read(entry).decode('utf-8',errors='replace'))
target=ROOT/'vendor/offline';target.mkdir(parents=True,exist_ok=True)
(target/'licenses.txt').write_text(''.join(notices),encoding='utf-8')
(OUT/'source-records.json').write_text(json.dumps(records,indent=2)+'\n',encoding='utf-8')
print('Offline source archives and notices retained',flush=True)
