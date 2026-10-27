#!/usr/bin/env python3
"""Validate copied Boost ML assets without loading or enabling either model."""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3] / "app/src/main/assets/boost"
EXPECTED_BLOBS = {
    "hypo_risk_meta.json": "c34a3247177620bed5e06898821db74796eb717a",
    "hypo_risk_model.json": "ada7ad9f8907bca1a4d7b50a2cb086a60a8dea49",
    "meal_likelihood_meta.json": "9cb089af1670d7468d1583e4250b7cb4de383fc2",
    "meal_likelihood_model.json": "3f28e7c798e31a0c68173f842f6bbf529653a365",
}


def load_verified(name):
    raw = (ROOT / name).read_bytes()
    git_sha = hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest()
    if git_sha != EXPECTED_BLOBS[name]:
        raise ValueError(f"{name}: source content hash mismatch: {git_sha}")
    return json.loads(raw)


def check_pair(prefix):
    meta = load_verified(prefix + "_meta.json")
    model = load_verified(prefix + "_model.json")
    features = meta["features"]
    if not features or len(features) != len(set(features)):
        raise ValueError(f"{prefix}: empty or duplicate feature definitions")
    if model["feature_names"] != features:
        raise ValueError(f"{prefix}: model feature order differs from metadata")
    if model["n_features"] != len(features):
        raise ValueError(f"{prefix}: incompatible feature count")
    if "n_trees" in meta and model["n_trees"] != meta["n_trees"]:
        raise ValueError(f"{prefix}: incompatible tree count")
    if len(model["trees"]) != model["n_trees"]:
        raise ValueError(f"{prefix}: model tree array incomplete")
    print(f"{prefix}: verified {len(features)} features and {model['n_trees']} trees")


if __name__ == "__main__":
    check_pair("hypo_risk")
    check_pair("meal_likelihood")
    print("Boost ML assets validated (passive only; no inference or dosing tested)")
