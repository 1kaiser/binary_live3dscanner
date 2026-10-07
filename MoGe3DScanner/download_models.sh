#!/usr/bin/env bash
# Downloads MoGe-3 LiteRT INT8 model from Hugging Face if not already present
set -euo pipefail

ASSETS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/app/src/main/assets"
MODEL_FILE="$ASSETS_DIR/moge3_dense_stage_weight_only_int8.tflite"
HF_URL="https://huggingface.co/1kaiser/moge3-litert/resolve/main/moge3_dense_stage_weight_only_int8.tflite"

if [ -f "$MODEL_FILE" ]; then
    echo "MoGe-3 model already present at $MODEL_FILE"
    exit 0
fi

echo "Downloading MoGe-3 INT8 model from Hugging Face (324 MB)..."
curl -L -o "$MODEL_FILE" "$HF_URL"
echo "Model downloaded successfully to $MODEL_FILE"
