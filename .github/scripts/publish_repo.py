#!/usr/bin/env python3

import argparse
import gzip
import html
import json
import shutil
from pathlib import Path
from urllib.parse import quote

import index_pb2
from google.protobuf import json_format


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Generate a multi-extension Mihon repository")
    parser.add_argument("--repository", required=True, help="GitHub owner/repository")
    parser.add_argument("--tag", required=True, help="GitHub release tag")
    parser.add_argument("--source-info", required=True, action="append", type=Path)
    parser.add_argument("--signing-fingerprint", required=True)
    parser.add_argument("--output", required=True, type=Path)
    return parser.parse_args()


def find_artifact(source_info: Path, artifact_type: str, suffix: str) -> Path:
    module_dir = source_info.parent.parent
    artifact_dir = module_dir / "build" / "outputs" / artifact_type / "release"
    matches = sorted(artifact_dir.glob(f"*{suffix}"))
    if len(matches) != 1:
        raise ValueError(f"Expected one {suffix} in {artifact_dir}, found {len(matches)}")
    return matches[0]


def make_extension(
    source_info: Path,
    repository: str,
    tag: str,
) -> index_pb2.Extension:
    with source_info.open(encoding="utf-8") as source_file:
        info = json.load(source_file)

    apk = find_artifact(source_info, "apk", ".apk")
    jar = find_artifact(source_info, "jar", ".jar")
    release_base = f"https://github.com/{repository}/releases/download/{quote(tag, safe='')}"
    raw_base = f"https://raw.githubusercontent.com/{repository}"
    module_path = info["module"].replace(".", "/")

    return index_pb2.Extension(
        name=info["name"],
        packageName=info["packageName"],
        resources=index_pb2.Resources(
            apkUrl=f"{release_base}/{quote(apk.name, safe='')}",
            jarUrl=f"{release_base}/{quote(jar.name, safe='')}",
            iconUrl=f"{raw_base}/main/src/{module_path}/res/mipmap-xhdpi/ic_launcher.png",
        ),
        extensionLib=info["extensionLib"],
        versionCode=info["versionCode"],
        versionName=info["versionName"],
        contentWarning=info["contentWarning"],
        sources=[
            index_pb2.Source(
                id=int(source["id"]),
                name=source["name"],
                language=source["lang"],
                homeUrl=source["baseUrl"],
                mirrorUrls=source.get("mirrorUrls", []),
            )
            for source in info["sources"]
        ],
    )


def make_tachimanga_extension(source_info: Path) -> tuple[dict, Path, Path]:
    with source_info.open(encoding="utf-8") as source_file:
        info = json.load(source_file)

    apk = find_artifact(source_info, "apk", ".apk")
    module_dir = source_info.parent.parent
    icon = module_dir / "res" / "mipmap-xhdpi" / "ic_launcher.png"
    if not icon.is_file():
        raise ValueError(f"Missing extension icon: {icon}")

    languages = {source["lang"] for source in info["sources"]}
    language = next(iter(languages)) if len(languages) == 1 else "all"
    extension_code = int(info["versionName"].rsplit(".", 1)[-1])
    entry = {
        "name": f"Tachiyomi: {info['name']}",
        "pkg": info["packageName"],
        "apk": apk.name,
        "lang": language,
        "code": extension_code,
        "version": info["versionName"],
        "nsfw": 0 if info["contentWarning"] == 1 else 1,
        "sources": [
            {
                "name": source["name"],
                "lang": source["lang"],
                "id": str(source["id"]),
                "baseUrl": source["baseUrl"],
                "versionId": 1,
            }
            for source in info["sources"]
        ],
    }
    return entry, apk, icon


def main() -> None:
    args = parse_args()
    fingerprint = args.signing_fingerprint.replace(":", "").lower()
    if len(fingerprint) != 64 or any(char not in "0123456789abcdef" for char in fingerprint):
        raise ValueError("Signing fingerprint must be a SHA-256 hex digest")

    extensions = sorted(
        [make_extension(path, args.repository, args.tag) for path in args.source_info],
        key=lambda extension: extension.name.lower(),
    )
    package_names = {extension.packageName for extension in extensions}
    if len(package_names) != len(extensions):
        raise ValueError("Extension package names must be unique")

    repository_url = f"https://github.com/{args.repository}"
    index = index_pb2.Index(
        name="Manhua Extensions",
        badgeLabel=f"{len(extensions)} sources",
        signingKey=fingerprint,
        contact=index_pb2.Contact(website=repository_url),
        extensionList=index_pb2.ExtensionList(extensions=extensions),
    )

    args.output.mkdir(parents=True, exist_ok=True)
    apk_output = args.output / "apk"
    icon_output = args.output / "icon"
    apk_output.mkdir(exist_ok=True)
    icon_output.mkdir(exist_ok=True)
    index_url = f"https://raw.githubusercontent.com/{args.repository}/repo/index.pb"

    tachimanga_extensions = []
    for source_info in args.source_info:
        entry, apk, icon = make_tachimanga_extension(source_info)
        tachimanga_extensions.append(entry)
        shutil.copy2(apk, apk_output / apk.name)
        shutil.copy2(icon, icon_output / f"{entry['pkg']}.png")
    tachimanga_extensions.sort(key=lambda extension: extension["name"].lower())

    with args.output.joinpath("index.min.json").open("w", encoding="utf-8") as output_file:
        json.dump(
            tachimanga_extensions,
            output_file,
            ensure_ascii=False,
            separators=(",", ":"),
        )
        output_file.write("\n")

    with args.output.joinpath("index.json").open("w", encoding="utf-8") as output_file:
        output_file.write(
            json_format.MessageToJson(
                index,
                always_print_fields_with_no_presence=False,
                preserving_proto_field_name=True,
            )
        )

    with args.output.joinpath("index.pb").open("wb") as output_file:
        output_file.write(gzip.compress(index.SerializeToString(deterministic=True), mtime=0))

    with args.output.joinpath("repo.json").open("w", encoding="utf-8") as output_file:
        json.dump(
            {
                "index_v2": index_url,
                "meta": {
                    "name": "Manhua Extensions",
                    "website": repository_url,
                    "signingKeyFingerprint": fingerprint,
                },
            },
            output_file,
            ensure_ascii=False,
            indent=2,
        )
        output_file.write("\n")

    with args.output.joinpath("index.html").open("w", encoding="utf-8") as output_file:
        download_links = "\n".join(
            f'<li><a href="{html.escape(extension.resources.apkUrl)}">'
            f'{html.escape(extension.name)}</a></li>'
            for extension in extensions
        )
        output_file.write(
            "<!doctype html>\n"
            '<html lang="zh-Hant">\n'
            '<meta charset="utf-8">\n'
            "<title>Manhua Extensions</title>\n"
            "<h1>Mihon extensions</h1>\n"
            f"<ul>\n{download_links}\n</ul>\n"
            "</html>\n"
        )

    with args.output.joinpath("README.md").open("w", encoding="utf-8") as output_file:
        source_names = "\n".join(
            f"- {source.name} — {source.homeUrl}"
            for extension in extensions
            for source in extension.sources
        )
        output_file.write(
            "# Manhua Extensions\n\n"
            f"{source_names}\n\n"
            "將以下地址加入 Mihon 的擴充套件儲存庫：\n\n"
            f"```text\n{index_url}\n```\n\n"
            "Tachimanga 請使用：\n\n"
            f"```text\nhttps://raw.githubusercontent.com/{args.repository}/repo/index.min.json\n```\n"
        )


if __name__ == "__main__":
    main()
