#!/usr/bin/env python3
"""Export the released AnimeCV ResNet18 character embedding checkpoint to ONNX.

Upstream implementation: kosuke1701/AnimeCV (MIT).
Checkpoint: kosuke1701/character-reid release 0.0 / res18_0222.mdl.
"""
import argparse
from pathlib import Path
import urllib.request

import torch
import torch.nn as nn
from torchvision import models

CHECKPOINT_URL = "https://github.com/kosuke1701/character-reid/releases/download/0.0/res18_0222.mdl"


class Identity(nn.Module):
    def forward(self, value):
        return value


class Normalize(nn.Module):
    def forward(self, value):
        return value / torch.linalg.vector_norm(value, dim=1, keepdim=True).clamp_min(1e-12)


class AnimeCharacterEncoder(nn.Module):
    def __init__(self):
        super().__init__()
        trunk = models.resnet18(weights=None)
        trunk.fc = Identity()
        self.model = nn.Sequential(
            trunk,
            nn.Sequential(Identity(), nn.Linear(512, 500), Normalize()),
        )

    def forward(self, images):
        return self.model(images)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    args.checkpoint.parent.mkdir(parents=True, exist_ok=True)
    if not args.checkpoint.exists():
        urllib.request.urlretrieve(CHECKPOINT_URL, args.checkpoint)
    if args.checkpoint.stat().st_size < 40_000_000:
        raise RuntimeError("AnimeCV checkpoint download is incomplete.")

    checkpoint = torch.load(args.checkpoint, map_location="cpu", weights_only=False)
    model = AnimeCharacterEncoder().eval()
    model.model[0].load_state_dict(checkpoint["trunk"], strict=True)
    model.model[1].load_state_dict(checkpoint["embedder"], strict=True)

    sample = torch.zeros(1, 3, 224, 224, dtype=torch.float32)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    torch.onnx.export(
        model,
        sample,
        str(args.output),
        input_names=["images"],
        output_names=["embedding"],
        dynamic_axes={"images": {0: "batch"}, "embedding": {0: "batch"}},
        opset_version=17,
        do_constant_folding=True,
    )
    if args.output.stat().st_size < 5_000_000:
        raise RuntimeError("Exported AnimeCV model is unexpectedly small.")
    print(f"Exported {args.output} ({args.output.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
