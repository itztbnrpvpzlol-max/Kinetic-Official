# Third-party AI model notices

## Anime character reference encoder
- Upstream implementation: [AnimeCV](https://github.com/kosuke1701/AnimeCV), MIT License.
- Upstream checkpoint: [character-reid release 0.0](https://github.com/kosuke1701/character-reid/releases/tag/0.0), asset `res18_0222.mdl`.
- The export workflow converts the author's released ResNet18/500-dimensional character embedding checkpoint to ONNX for local inference.
- The encoder compares user-provided reference images with sampled anime footage. It does not identify arbitrary named characters unless the user supplies reference images and names.

## Optical flow
- Model: [OpenCV Zoo RAFT optical flow](https://huggingface.co/opencv/optical_flow_estimation_raft), INT8 block-quantized ONNX variant.
- RAFT original implementation/model: BSD-3-Clause.
- ONNX conversion: MIT License.
- The app downloads this model on first use and keeps it in app-private storage.

The models are optional and run on-device. The application does not upload selected footage or reference images.
