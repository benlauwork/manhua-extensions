#!/usr/bin/env python3

import argparse
import gzip
import json
from pathlib import Path

import index_pb2
from google.protobuf import json_format


def main() -> None:
    parser = argparse.ArgumentParser(description="Verify generated Mihon repository files")
    parser.add_argument("directory", type=Path)
    parser.add_argument("--expected-extensions", type=int)
    args = parser.parse_args()

    with args.directory.joinpath("index.pb").open("rb") as proto_file:
        proto_index = index_pb2.Index.FromString(gzip.decompress(proto_file.read()))
    with args.directory.joinpath("index.json").open(encoding="utf-8") as json_file:
        json_index = json_format.Parse(json_file.read(), index_pb2.Index())
    with args.directory.joinpath("repo.json").open(encoding="utf-8") as repo_file:
        repo = json.load(repo_file)
    with args.directory.joinpath("index.min.json").open(encoding="utf-8") as legacy_file:
        tachimanga_extensions = json.load(legacy_file)

    if proto_index.SerializeToString(deterministic=True) != json_index.SerializeToString(deterministic=True):
        raise ValueError("index.pb and index.json contain different data")
    extensions = proto_index.extensionList.extensions
    if args.expected_extensions is not None and len(extensions) != args.expected_extensions:
        raise ValueError(
            f"Repository must contain {args.expected_extensions} extensions; found {len(extensions)}"
        )
    if not extensions:
        raise ValueError("Repository must contain at least one extension")
    if repo["meta"]["signingKeyFingerprint"] != proto_index.signingKey:
        raise ValueError("repo.json signing fingerprint does not match index.pb")
    if repo["index_v2"].rsplit("/", 1)[-1] != "index.pb":
        raise ValueError("repo.json index_v2 does not point to index.pb")

    package_names = [extension.packageName for extension in extensions]
    if len(set(package_names)) != len(package_names):
        raise ValueError("Extension package names must be unique")
    if {extension["pkg"] for extension in tachimanga_extensions} != set(package_names):
        raise ValueError("Tachimanga and Mihon indexes contain different extension packages")

    for extension in extensions:
        if not extension.resources.apkUrl.startswith("https://"):
            raise ValueError(f"{extension.name} APK URL must use HTTPS")
        if not extension.resources.jarUrl.startswith("https://"):
            raise ValueError(f"{extension.name} JAR URL must use HTTPS")
        if not extension.resources.iconUrl.startswith("https://"):
            raise ValueError(f"{extension.name} icon URL must use HTTPS")

        tachimanga_extension = next(
            item for item in tachimanga_extensions if item["pkg"] == extension.packageName
        )
        if tachimanga_extension["version"] != extension.versionName:
            raise ValueError(f"{extension.name} has mismatched Mihon and Tachimanga versions")
        if not args.directory.joinpath("apk", tachimanga_extension["apk"]).is_file():
            raise ValueError(f"{extension.name} Tachimanga APK is missing")
        if not args.directory.joinpath("icon", f"{extension.packageName}.png").is_file():
            raise ValueError(f"{extension.name} Tachimanga icon is missing")

    summary = ", ".join(
        f"{extension.name} {extension.versionName}" for extension in extensions
    )
    print(f"Verified {len(extensions)} extensions: {summary}")


if __name__ == "__main__":
    main()
