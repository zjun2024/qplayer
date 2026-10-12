#!/usr/bin/env python3
"""Unpack, edit and repack QPlayer .qplug packages on Windows.

A .qplug is a zip whose every file is listed (with its SHA-256) in
META-INF/qplayer-files.json; QPlayer verifies those hashes before install and
may additionally check an ECDSA signature over the hash manifest. Modified
packages can be packed unsigned - QPlayer accepts unsigned packages through the
same path a manual import uses - or signed with a key you control.

Commands:
  python repack-qplug.py unpack <pkg.qplug> <outdir>
  python repack-qplug.py pack   <indir> <out.qplug> [--sign <private.pem>]
  python repack-qplug.py genkey <private.pem>
"""
import argparse
import base64
import hashlib
import io
import json
import sys
import zipfile
from pathlib import Path

HASHES_PATH = "META-INF/qplayer-files.json"
SIGNATURE_PATH = "META-INF/qplayer.sig"


def unpack(pkg: Path, outdir: Path) -> None:
    outdir.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(pkg) as zf:
        for name in zf.namelist():
            if name.endswith("/"):
                continue
            target = outdir / name
            if not target.resolve().is_relative_to(outdir.resolve()):
                sys.exit(f"refusing to extract outside target: {name}")
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(zf.read(name))
    print(f"unpacked {pkg} -> {outdir}")


def package_files(indir: Path) -> list[str]:
    files = []
    for path in sorted(indir.rglob("*")):
        if not path.is_file():
            continue
        rel = path.relative_to(indir).as_posix()
        if rel.startswith("META-INF/"):
            continue
        files.append(rel)
    if "plugin.json" not in files:
        sys.exit("not a plugin directory: plugin.json is missing")
    return files


def pack(indir: Path, out: Path, sign_key: Path | None) -> None:
    files = package_files(indir)
    manifest = json.loads((indir / "plugin.json").read_text(encoding="utf-8"))
    plugin_id = manifest.get("id", "?")
    version = manifest.get("version", "?")

    hashes = {
        rel: hashlib.sha256((indir / rel).read_bytes()).hexdigest()
        for rel in files
    }
    hashes_bytes = json.dumps(hashes, indent=2).encode("utf-8")

    signature = None
    if sign_key is not None:
        from cryptography.hazmat.primitives import hashes as c_hashes
        from cryptography.hazmat.primitives import serialization
        from cryptography.hazmat.primitives.asymmetric import ec
        key = serialization.load_pem_private_key(
            sign_key.read_bytes(), password=None)
        if not isinstance(key, ec.EllipticCurvePrivateKey):
            sys.exit("signing key must be an EC (P-256) private key")
        der = key.sign(hashes_bytes, ec.ECDSA(c_hashes.SHA256()))
        signature = base64.b64encode(der)

    out.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zf:
        for rel in files:
            zf.writestr(rel, (indir / rel).read_bytes())
        zf.writestr(HASHES_PATH, hashes_bytes)
        if signature is not None:
            zf.writestr(SIGNATURE_PATH, signature.decode("ascii"))

    kind = "signed" if signature is not None else "unsigned"
    print(f"packed {plugin_id} {version} ({len(files)} files, {kind}) -> {out}")
    print(f"place it as bundled/{plugin_id}-{version}.qplug and list that name"
          f" in bundled/index.json")


def genkey(path: Path) -> None:
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    key = ec.generate_private_key(ec.SECP256R1())
    path.write_bytes(key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption()))
    pub = key.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo)
    path.with_suffix(path.suffix + ".pub").write_text(
        base64.b64encode(pub).decode("ascii") + "\n", encoding="ascii")
    print(f"wrote {path} (private) and {path}.pub (base64 public key)")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("unpack")
    p.add_argument("pkg", type=Path)
    p.add_argument("outdir", type=Path)
    p = sub.add_parser("pack")
    p.add_argument("indir", type=Path)
    p.add_argument("out", type=Path)
    p.add_argument("--sign", type=Path, default=None,
                   help="P-256 private key PEM; omit to pack unsigned")
    p = sub.add_parser("genkey")
    p.add_argument("key", type=Path)
    args = ap.parse_args()
    if args.cmd == "unpack":
        unpack(args.pkg, args.outdir)
    elif args.cmd == "pack":
        pack(args.indir, args.out, args.sign)
    else:
        genkey(args.key)


if __name__ == "__main__":
    main()
