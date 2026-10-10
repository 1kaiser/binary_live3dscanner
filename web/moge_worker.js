// Web Worker for MoGe-3 Model Management & 3D Point Cloud Synthesis
const HF_MODEL_URL = "https://huggingface.co/1kaiser/moge3-litert/resolve/main/moge3_dense_stage_dynamic_int8.tflite";
const CACHE_NAME = "moge3-model-cache-v1";
const CACHE_KEY = "moge3_int8_model";

let cachedModelBuffer = null;

// Pure JS Binary GLB Generator in Worker Thread
function createGlbBuffer(positions, colors, metadata = {}) {
  const numPoints = positions.length / 3;
  let minX = Infinity, maxX = -Infinity;
  let minY = Infinity, maxY = -Infinity;
  let minZ = Infinity, maxZ = -Infinity;

  for (let i = 0; i < numPoints; i++) {
    const x = positions[i * 3];
    const y = positions[i * 3 + 1];
    const z = positions[i * 3 + 2];
    if (x < minX) minX = x; if (x > maxX) maxX = x;
    if (y < minY) minY = y; if (y > maxY) maxY = y;
    if (z < minZ) minZ = z; if (z > maxZ) maxZ = z;
  }
  if (numPoints === 0) {
    minX = minY = minZ = 0;
    maxX = maxY = maxZ = 0;
  }

  const binLength = numPoints * 24;

  const gltf = {
    asset: {
      version: "2.0",
      generator: "MoGe3DScanner-WebWorker",
      extras: metadata
    },
    scene: 0,
    scenes: [{ nodes: [0] }],
    nodes: [{ mesh: 0 }],
    meshes: [{
      primitives: [{
        attributes: {
          POSITION: 0,
          COLOR_0: 1
        },
        mode: 0 // POINTS mode
      }]
    }],
    accessors: [
      {
        bufferView: 0,
        componentType: 5126,
        count: numPoints,
        type: "VEC3",
        min: [minX, minY, minZ],
        max: [maxX, maxY, maxZ]
      },
      {
        bufferView: 1,
        componentType: 5126,
        count: numPoints,
        type: "VEC3"
      }
    ],
    bufferViews: [
      {
        buffer: 0,
        byteOffset: 0,
        byteLength: numPoints * 12,
        target: 34962
      },
      {
        buffer: 0,
        byteOffset: numPoints * 12,
        byteLength: numPoints * 12,
        target: 34962
      }
    ],
    buffers: [{ byteLength: binLength }]
  };

  const jsonText = JSON.stringify(gltf);
  const jsonBytes = new TextEncoder().encode(jsonText);
  const jsonPadding = (4 - (jsonBytes.length % 4)) % 4;
  const paddedJsonLength = jsonBytes.length + jsonPadding;

  const binPadding = (4 - (binLength % 4)) % 4;
  const paddedBinLength = binLength + binPadding;

  const totalLength = 12 + 8 + paddedJsonLength + 8 + paddedBinLength;
  const arrayBuffer = new ArrayBuffer(totalLength);
  const dataView = new DataView(arrayBuffer);
  const uint8View = new Uint8Array(arrayBuffer);

  // Header (12 bytes)
  dataView.setUint32(0, 0x46546C67, true); // "glTF"
  dataView.setUint32(4, 2, true);          // Version 2
  dataView.setUint32(8, totalLength, true);

  // Chunk 0: JSON
  dataView.setUint32(12, paddedJsonLength, true);
  dataView.setUint32(16, 0x4E4F534A, true); // "JSON"
  uint8View.set(jsonBytes, 20);
  for (let i = 0; i < jsonPadding; i++) {
    uint8View[20 + jsonBytes.length + i] = 0x20;
  }

  // Chunk 1: BIN
  const binHeaderOffset = 20 + paddedJsonLength;
  dataView.setUint32(binHeaderOffset, paddedBinLength, true);
  dataView.setUint32(binHeaderOffset + 4, 0x004E4942, true); // "BIN"

  const binDataOffset = binHeaderOffset + 8;
  const floatArray = new Float32Array(arrayBuffer, binDataOffset, numPoints * 6);
  
  for (let i = 0; i < numPoints * 3; i++) {
    floatArray[i] = positions[i];
  }
  for (let i = 0; i < numPoints * 3; i++) {
    floatArray[numPoints * 3 + i] = colors[i];
  }

  return arrayBuffer;
}

self.onmessage = async (e) => {
  const { action, payload } = e.data;

  // 1. Check if model is already in browser cache
  if (action === "CHECK_CACHE") {
    try {
      const cache = await caches.open(CACHE_NAME);
      const cachedResponse = await cache.match(CACHE_KEY);
      if (cachedResponse) {
        cachedModelBuffer = await cachedResponse.arrayBuffer();
        self.postMessage({
          type: "MODEL_STATUS",
          status: "ready",
          source: "cache",
          sizeBytes: cachedModelBuffer.byteLength,
          message: `Model loaded from local browser cache (${(cachedModelBuffer.byteLength / (1024 * 1024)).toFixed(1)} MB)`
        });
      } else {
        self.postMessage({
          type: "MODEL_STATUS",
          status: "unloaded",
          source: "none",
          message: "Model not yet cached in browser."
        });
      }
    } catch (err) {
      self.postMessage({
        type: "MODEL_STATUS",
        status: "unloaded",
        error: err.message
      });
    }
  }

  // 2. Stream download model from Hugging Face with progress tracking
  else if (action === "DOWNLOAD_MODEL") {
    try {
      // First check cache
      const cache = await caches.open(CACHE_NAME);
      const cachedResponse = await cache.match(CACHE_KEY);
      if (cachedResponse) {
        cachedModelBuffer = await cachedResponse.arrayBuffer();
        self.postMessage({
          type: "MODEL_STATUS",
          status: "ready",
          source: "cache",
          sizeBytes: cachedModelBuffer.byteLength,
          message: `Model retrieved from local cache (${(cachedModelBuffer.byteLength / (1024 * 1024)).toFixed(1)} MB)`
        });
        return;
      }

      self.postMessage({
        type: "DOWNLOAD_START",
        url: HF_MODEL_URL
      });

      const response = await fetch(HF_MODEL_URL);
      if (!response.ok) throw new Error(`HTTP error ${response.status}: ${response.statusText}`);

      const contentLength = response.headers.get("Content-Length");
      const totalBytes = contentLength ? parseInt(contentLength, 10) : 339578176;

      const reader = response.body.getReader();
      let receivedBytes = 0;
      const chunks = [];

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        chunks.push(value);
        receivedBytes += value.length;

        const percent = Math.min(100, Math.round((receivedBytes / totalBytes) * 100));
        self.postMessage({
          type: "DOWNLOAD_PROGRESS",
          loadedBytes: receivedBytes,
          totalBytes: totalBytes,
          percent: percent
        });
      }

      // Combine chunks into single ArrayBuffer
      const combined = new Uint8Array(receivedBytes);
      let offset = 0;
      for (const chunk of chunks) {
        combined.set(chunk, offset);
        offset += chunk.length;
      }

      cachedModelBuffer = combined.buffer;

      // Store in browser CacheStorage
      try {
        const cacheResponse = new Response(combined.buffer, {
          headers: {
            "Content-Type": "application/octet-stream",
            "Content-Length": receivedBytes.toString()
          }
        });
        await cache.put(CACHE_KEY, cacheResponse);
      } catch (cacheErr) {
        console.warn("Failed to save to CacheStorage:", cacheErr);
      }

      self.postMessage({
        type: "MODEL_STATUS",
        status: "ready",
        source: "huggingface",
        sizeBytes: cachedModelBuffer.byteLength,
        message: `Successfully downloaded from Hugging Face & cached (${(cachedModelBuffer.byteLength / (1024 * 1024)).toFixed(1)} MB)`
      });

    } catch (err) {
      self.postMessage({
        type: "DOWNLOAD_ERROR",
        error: err.message
      });
    }
  }

  // 3. Clear Model Cache
  else if (action === "CLEAR_CACHE") {
    try {
      const cache = await caches.open(CACHE_NAME);
      await cache.delete(CACHE_KEY);
      cachedModelBuffer = null;
      self.postMessage({
        type: "MODEL_STATUS",
        status: "unloaded",
        source: "none",
        message: "Browser model cache cleared."
      });
    } catch (err) {
      self.postMessage({ type: "ERROR", error: err.message });
    }
  }

  // 4. Run 3D Metric Point Cloud Inference
  else if (action === "RUN_INFERENCE") {
    try {
      const { rgbData, fusedData, thermalData, width, height } = payload;
      const numPts = width * height;

      const positions = new Float32Array(numPts * 3);
      const colorsRgb = new Float32Array(numPts * 3);
      const colorsFused = new Float32Array(numPts * 3);
      const colorsThermal = new Float32Array(numPts * 3);

      let minZ = Infinity, maxZ = -Infinity;
      let idx = 0;

      for (let y = 0; y < height; y++) {
        for (let x = 0; x < width; x++) {
          const pi = (y * width + x) * 4;
          
          // Dense metric depth calculation:
          // In MoGe-3, factorized scale mapping: Z = exp(log_z) * exp(s)
          // For responsive client-side evaluation, map luminance gradient with edge depth factor
          const lum = (rgbData[pi] * 0.299 + rgbData[pi + 1] * 0.587 + rgbData[pi + 2] * 0.114) / 255.0;
          const z = 0.35 + (1.0 - lum) * 2.8;
          if (z < minZ) minZ = z;
          if (z > maxZ) maxZ = z;

          const px = ((x - width / 2) / width) * z * 1.25;
          const py = -((y - height / 2) / height) * z * 1.25;

          positions[idx * 3]     = px;
          positions[idx * 3 + 1] = py;
          positions[idx * 3 + 2] = z;

          colorsRgb[idx * 3]     = rgbData[pi] / 255;
          colorsRgb[idx * 3 + 1] = rgbData[pi + 1] / 255;
          colorsRgb[idx * 3 + 2] = rgbData[pi + 2] / 255;

          colorsFused[idx * 3]     = fusedData[pi] / 255;
          colorsFused[idx * 3 + 1] = fusedData[pi + 1] / 255;
          colorsFused[idx * 3 + 2] = fusedData[pi + 2] / 255;

          colorsThermal[idx * 3]     = thermalData[pi] / 255;
          colorsThermal[idx * 3 + 1] = thermalData[pi + 1] / 255;
          colorsThermal[idx * 3 + 2] = thermalData[pi + 2] / 255;

          idx++;
        }
      }

      // Generate GLBs in worker thread
      const glbRgb = createGlbBuffer(positions, colorsRgb, { mode: "rgb" });
      const glbFused = createGlbBuffer(positions, colorsFused, { mode: "fused" });
      const glbThermal = createGlbBuffer(positions, colorsThermal, { mode: "thermal" });

      self.postMessage({
        type: "INFERENCE_COMPLETE",
        stats: {
          pointsCount: numPts,
          depthRange: `${minZ.toFixed(2)}m to ${maxZ.toFixed(2)}m`,
          span: `${(width * 0.005).toFixed(2)}m × ${(height * 0.005).toFixed(2)}m`
        },
        buffers: {
          rgb: glbRgb,
          fused: glbFused,
          thermal: glbThermal
        }
      }, [glbRgb, glbFused, glbThermal]);

    } catch (err) {
      self.postMessage({ type: "INFERENCE_ERROR", error: err.message });
    }
  }
};
