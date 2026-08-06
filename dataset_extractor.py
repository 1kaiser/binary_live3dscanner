#!/usr/bin/env python3
"""
dataset_extractor.py
Pure Python + JAX reimplementation of the 3DLiveScanner dataset_extractor C++ tool.

Usage:
    conda run -n num_python python dataset_extractor.py <path_to_dataset>

Outputs (written inside the dataset directory):
    00000000.ply ... NNNNN.ply   — per-frame colorized point clouds
    output.ply                   — all frames merged
    floorplan.png                — top-down heightmap (only if depth points exist)

File format reference (matches the Android app's saveDatasetFrame):
    .pcl  — binary little-endian: [int32 num_points][float32 x,y,z,conf] × N
    .mat  — text, 3x GLM column-major 4x4 matrices (each text row = one GLM column)
    state.txt     — "count width height cx cy fx fy"
    rotation.txt  — yaw in degrees (float)
"""

import sys, os, struct, math
import numpy as np
from PIL import Image

# ── JAX import (with graceful CPU fallback) ───────────────────────────────────
try:
    import jax, jax.numpy as jnp
    JAX_OK = True
except ImportError:
    import numpy as jnp  # type: ignore
    JAX_OK = False

# ── Indices matching C++ exporter ─────────────────────────────────────────────
COLOR_CAMERA  = 0   # pose[0]: pcl -> world space
SCREEN_CAMERA = 2   # pose[2]: world -> screen NDC (transpose of COLOR_CAMERA)

# ═════════════════════════════════════════════════════════════════════════════
# File readers
# ═════════════════════════════════════════════════════════════════════════════

def read_state(p):
    with open(os.path.join(p, "state.txt")) as f:
        v = f.read().split()
    return int(v[0]), int(v[1]), int(v[2]), float(v[3]), float(v[4]), float(v[5]), float(v[6])

def read_yaw_deg(p):
    with open(os.path.join(p, "rotation.txt")) as f:
        return float(f.read().strip())

def read_pcl(filepath):
    """Binary .pcl: int32 num_points | float32[4] (x,y,z,conf) x N  (little-endian)"""
    with open(filepath, "rb") as f:
        (n,) = struct.unpack("<i", f.read(4))
        if n == 0:
            return np.zeros((0, 3), dtype=np.float32)
        raw = f.read(n * 16)
        pts = np.frombuffer(raw, dtype="<f4").reshape(n, 4)
    return pts[:, :3].copy()

def read_mat(filepath):
    """
    Text .mat: 3x GLM column-major 4x4 matrices.
    GLM mat[col][row] — each text line = one column.
    We read 4 lines per matrix, treat each line as a column vector,
    then stack columns -> (4,4) matrix.
    """
    with open(filepath) as f:
        lines = [l.strip() for l in f if l.strip()]
    matrices = []
    for m in range(3):
        cols = []
        for c in range(4):
            cols.append([float(x) for x in lines[m * 4 + c].split()])
        # cols[c] is column c; np.array(cols) is (4,4) with rows=cols
        # transposing gives the actual matrix in row-major form
        mat = np.array(cols, dtype=np.float32).T
        matrices.append(mat)
    return matrices

def fp(dataset_path, idx, ext):
    return os.path.join(dataset_path, f"{idx:08d}{ext}")

# ═════════════════════════════════════════════════════════════════════════════
# JAX geometry helpers
# ═════════════════════════════════════════════════════════════════════════════

def jax_mat_mul(pts_np, mat_np):
    """(N,3) points x (4,4) matrix -> (N,3). Handles empty arrays."""
    if pts_np.shape[0] == 0:
        return pts_np
    pts = jnp.array(pts_np)
    mat = jnp.array(mat_np)
    ones = jnp.ones((pts.shape[0], 1), dtype=pts.dtype)
    p4 = jnp.concatenate([pts, ones], axis=1)
    out = (mat @ p4.T).T
    return np.array(out[:, :3]) if JAX_OK else out[:, :3]

def jax_project_uv(world_np, screen_mat_np):
    """Project (N,3) world -> UV [0,1] via screen_mat (4x4)."""
    if world_np.shape[0] == 0:
        return np.zeros((0, 2), dtype=np.float32)
    w  = jnp.array(world_np)
    sm = jnp.array(screen_mat_np)
    ones = jnp.ones((w.shape[0], 1), dtype=w.dtype)
    p4 = jnp.concatenate([w, ones], axis=1)
    proj = (sm @ p4.T).T
    denom = jnp.where(jnp.abs(proj[:, 3:4]) < 1e-7, jnp.ones_like(proj[:, 3:4]), jnp.abs(proj[:, 3:4]))
    ndc = proj / denom
    uv  = ndc[:, :2] * 0.5 + 0.5
    return np.array(uv) if JAX_OK else uv

def jax_apply_yaw(world_np, yaw_deg):
    """Rotate (N,3) around Y axis by -yaw (C++ convention)."""
    if world_np.shape[0] == 0:
        return world_np
    yr = math.radians(yaw_deg)
    s, c = math.sin(-yr), math.cos(-yr)
    pts = jnp.array(world_np)
    x = pts[:, 0] * s - pts[:, 2] * c
    y = pts[:, 1]
    z = pts[:, 0] * c + pts[:, 2] * s
    out = jnp.stack([x, y, z], axis=1)
    return np.array(out) if JAX_OK else out

# ═════════════════════════════════════════════════════════════════════════════
# Color sampling
# ═════════════════════════════════════════════════════════════════════════════

def sample_colors(img_np, uv_np):
    H, W = img_np.shape[:2]
    xs = np.clip((uv_np[:, 0] * W).astype(int), 0, W - 1)
    ys = np.clip((uv_np[:, 1] * H).astype(int), 0, H - 1)
    return img_np[ys, xs]

# ═════════════════════════════════════════════════════════════════════════════
# PLY writer (ASCII)
# ═════════════════════════════════════════════════════════════════════════════

def write_ply(path, verts, colors):
    n = len(verts)
    with open(path, "w") as f:
        f.write("ply\nformat ascii 1.0\ncomment ---\n")
        f.write(f"element vertex {n}\n")
        f.write("property float x\nproperty float y\nproperty float z\n")
        if n > 0:
            f.write("property uchar red\nproperty uchar green\nproperty uchar blue\n")
        f.write("element face 0\nproperty list uchar uint vertex_indices\nend_header\n")
        for i in range(n):
            v, c = verts[i], colors[i]
            f.write(f"{float(v[0]):.6f} {float(v[1]):.6f} {float(v[2]):.6f} {int(c[0])} {int(c[1])} {int(c[2])}\n")
    print(f"  -> {os.path.basename(path)}  ({n} vertices)")

# ═════════════════════════════════════════════════════════════════════════════
# Floorplan
# ═════════════════════════════════════════════════════════════════════════════

def build_floorplan(all_world_pts, all_cam_ys, dataset_path):
    hmap = {}
    for pts, cam_y in zip(all_world_pts, all_cam_ys):
        if pts.shape[0] == 0:
            continue
        floor = pts[pts[:, 1] < cam_y]
        for v in floor:
            k = (int(v[0] * 100), int(v[2] * 100))
            if k not in hmap or hmap[k] < v[1]:
                hmap[k] = float(v[1])

    if not hmap:
        print("  Floorplan: no depth points — skipping PNG.")
        return

    xs = [k[0] for k in hmap]; zs = [k[1] for k in hmap]
    min_x, max_x = min(xs) - 10, max(xs) + 10
    min_z, max_z = min(zs) - 10, max(zs) + 10
    w, h = max_x - min_x + 1, max_z - min_z + 1
    print(f"  Floorplan: {w} cm x {h} cm, coverage {100*len(hmap)/(w*h):.2f}%")

    img = np.zeros((h, w), dtype=np.uint8)
    for (px, pz), val in hmap.items():
        x, y = px - min_x, pz - min_z
        neighbors = [hmap.get((px+dx, pz+dz)) for dx in range(-1,2) for dz in range(-1,2)]
        neighbors = [n for n in neighbors if n is not None]
        diff = sum(abs(val - n) for n in neighbors) / max(len(neighbors), 1)
        f = max(0.0, min(1.0, 1.0 - diff * 2.0))
        r = int(255 * f)
        g = int(max(0, min(255, -val * 200.0)) * f)
        b = int(max(0, min(255, -val * 100.0)) * f)
        img[y, x] = (r + g + b) // 3

    out_png = os.path.join(dataset_path, "floorplan.png")
    Image.fromarray(img, mode="L").save(out_png)
    print(f"  -> floorplan.png")

# ═════════════════════════════════════════════════════════════════════════════
# Main
# ═════════════════════════════════════════════════════════════════════════════

def main():
    if len(sys.argv) < 2:
        print("Usage: conda run -n num_python python dataset_extractor.py <dataset_path>")
        sys.exit(1)

    dataset_path = sys.argv[1].rstrip("/\\")
    if not os.path.isdir(dataset_path):
        print(f"Error: '{dataset_path}' is not a directory.")
        sys.exit(1)

    print("=" * 60)
    print("  3DLiveScanner Dataset Extractor — Python + JAX")
    print("=" * 60)
    if JAX_OK:
        print(f"  JAX {jax.__version__}  |  devices: {jax.devices()}")
    else:
        print("  JAX not found — using plain NumPy")
    print(f"  Dataset: {dataset_path}\n")

    num_frames, width, height, cx, cy, fx, fy = read_state(dataset_path)
    yaw_deg = read_yaw_deg(dataset_path)
    print(f"  Frames: {num_frames}   Resolution: {width}x{height}")
    print(f"  cx={cx} cy={cy} fx={fx} fy={fy}   Yaw: {yaw_deg} deg\n")

    all_vertices, all_colors, all_world_pts, all_cam_ys = [], [], [], []

    for i in range(num_frames):
        print(f"[Frame {i:04d}]  {os.path.basename(fp(dataset_path, i, '.jpg'))}")

        pts    = read_pcl(fp(dataset_path, i, ".pcl"))
        pose   = read_mat(fp(dataset_path, i, ".mat"))
        img_np = np.array(Image.open(fp(dataset_path, i, ".jpg")).convert("RGB"))

        if pts.shape[0] == 0:
            print("  0 depth points — empty PLY")
            write_ply(fp(dataset_path, i, ".ply"),
                      np.zeros((0,3), np.float32), np.zeros((0,3), np.uint8))
            all_world_pts.append(np.zeros((0,3), np.float32))
            all_cam_ys.append(0.0)
            continue

        color_mat  = pose[COLOR_CAMERA]
        screen_mat = pose[SCREEN_CAMERA]

        world_pts = jax_mat_mul(pts, color_mat)
        uv        = jax_project_uv(world_pts, screen_mat)
        final_pts = jax_apply_yaw(world_pts, yaw_deg)
        colors    = sample_colors(img_np, uv)

        write_ply(fp(dataset_path, i, ".ply"), final_pts, colors)
        all_vertices.append(final_pts)
        all_colors.append(colors)
        all_world_pts.append(world_pts)
        all_cam_ys.append(float(color_mat[1, 3]))

    print(f"\nMerging all frames ->")
    if all_vertices:
        merged_v = np.concatenate(all_vertices, axis=0)
        merged_c = np.concatenate(all_colors,   axis=0)
    else:
        merged_v = np.zeros((0,3), np.float32)
        merged_c = np.zeros((0,3), np.uint8)
    write_ply(os.path.join(dataset_path, "output.ply"), merged_v, merged_c)

    print(f"\nBuilding floorplan ->")
    build_floorplan(all_world_pts, all_cam_ys, dataset_path)

    total = sum(v.shape[0] for v in all_vertices)
    print(f"\n{'='*60}")
    print(f"  Done!  {num_frames} frames,  {total:,} total points")
    for f in sorted(os.listdir(dataset_path)):
        if f.endswith((".ply", ".png")):
            sz = os.path.getsize(os.path.join(dataset_path, f))
            print(f"  {f:<30} {sz:>10,} bytes")
    print("=" * 60)

if __name__ == "__main__":
    main()
