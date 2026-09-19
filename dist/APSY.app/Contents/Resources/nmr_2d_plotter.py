#!/usr/bin/env python3
"""
plot_bruker_2d.py
-----------------
Standalone Bruker 2D NMR plotter.

Usage:
    python plot_bruker_2d.py /path/to/experiment/root
    python plot_bruker_2d.py /path/to/pdata/1

The script auto-navigates the given path to locate the processed-data
directory that contains 2rr (and optionally 2ri / 2ir / 2ii).
All four quadrants are loaded when available and used for phasing.
The plotting algorithm matches the canonical reference implementation
(fixed SF1 / SW_p1 / OFFSET1, noise from data[950:, 950:]).
"""

import sys
import os

import nmrglue as ng
import matplotlib.pyplot as plt
import numpy as np
from matplotlib.ticker import MultipleLocator


# ---------------------------------------------------------------------------
# Fallback F1 parameters.
#
# These are the proc2s values of the reference dataset. They are only used when
# a dataset does not ship readable indirect-dimension parameters - reading them
# from proc2s (as build_spectrum now does) reproduces exactly these numbers for
# the reference dataset while also being correct for every other one, which the
# hardcoded values were not.
# ---------------------------------------------------------------------------
SF1      = 800.2            # 15N carrier frequency [MHz]
SW_p1    = 8012.82051282052 # 15N sweep width [Hz]
OFFSET1  = 9.716571         # 15N spectral offset [ppm]

# Contour parameters (match reference script exactly)
NOISE_REGION_ROW = 950      # data[NOISE_REGION_ROW:, NOISE_REGION_COL:]
NOISE_REGION_COL = 950
CONTOUR_BASE_MULT = 8       # noise × this = first contour level
CONTOUR_FACTOR   = 1.4      # geometric spacing between levels
N_POS_LEVELS     = 14       # number of positive contour levels


def find_pdata_dir(start_path: str) -> str | None:
    """
    Walk start_path (or its parents / children) to find the processed-data
    directory that contains both '2rr' and 'procs'.
    Search order:
      1. start_path itself
      2. start_path/pdata/1  (most common Bruker layout)
      3. Any sub-directory under start_path/pdata/
      4. Recursive os.walk up to arbitrary depth
      5. Three parent levels upward (for when a file inside pdata is given)
    """
    if not os.path.exists(start_path):
        return None

    # If a file was given, use its directory
    if os.path.isfile(start_path):
        start_path = os.path.dirname(start_path)

    def has_pdata(d: str) -> bool:
        return (os.path.exists(os.path.join(d, "2rr")) and
                os.path.exists(os.path.join(d, "procs")))

    # 1. The directory itself
    if has_pdata(start_path):
        return start_path

    # 2. pdata/1
    p1 = os.path.join(start_path, "pdata", "1")
    if has_pdata(p1):
        return p1

    # 3. Any pdata/<n> subdirectory
    pdata_root = os.path.join(start_path, "pdata")
    if os.path.isdir(pdata_root):
        for sub in sorted(os.listdir(pdata_root)):
            candidate = os.path.join(pdata_root, sub)
            if os.path.isdir(candidate) and has_pdata(candidate):
                return candidate

    # 4. Recursive walk (handles nested experiment directories)
    for root, _dirs, files in os.walk(start_path):
        if "2rr" in files and "procs" in files:
            return root

    # 5. Walk up three parent levels (e.g. path points inside pdata already)
    curr = start_path
    for _ in range(3):
        parent = os.path.dirname(curr)
        if parent == curr:
            break
        for candidate in [
            parent,
            os.path.join(parent, "pdata", "1"),
        ]:
            if has_pdata(candidate):
                return candidate
        curr = parent

    return None


def load_quadrant(pdata_dir: str, fname: str, ref_shape: tuple) -> np.ndarray:
    """
    Load one binary quadrant file from pdata_dir.
    Returns a zero array of ref_shape if the file doesn't exist or fails.
    """
    fpath = os.path.join(pdata_dir, fname)
    if not os.path.exists(fpath):
        return np.zeros(ref_shape, dtype=np.float64)
    try:
        _, arr = ng.bruker.read_pdata(pdata_dir, bin_files=[fname])
        return arr.astype(np.float64)
    except Exception as exc:
        print(f"  [warn] Could not load {fname}: {exc}")
        return np.zeros(ref_shape, dtype=np.float64)


def build_spectrum(pdata_dir: str,
                   phc0_f2: float = 0.0, phc1_f2: float = 0.0,
                   phc0_f1: float = 0.0, phc1_f1: float = 0.0) -> tuple:
    """
    Load all four quadrants and return (dic, data_matrix, ppm_f2, ppm_f1).

    If no phase corrections are requested, only 2rr is needed.
    Phase correction formula:
        RR_phased_F2 = RR·cos(θ₂) − RI·sin(θ₂)   (along F2 per column)
        IR_phased_F2 = IR·cos(θ₂) − II·sin(θ₂)
        final        = RR_phased_F2·cos(θ₁) − IR_phased_F2·sin(θ₁)
    """
    print(f"  Loading 2rr from {pdata_dir} …")
    dic, data_rr = ng.bruker.read_pdata(pdata_dir, bin_files=["2rr"])
    data_rr = data_rr.astype(np.float64)
    n_f1, n_f2 = data_rr.shape

    need_phase = any(v != 0.0 for v in (phc0_f2, phc1_f2, phc0_f1, phc1_f1))

    if need_phase:
        print("  Loading 2ri, 2ir, 2ii for phasing …")
        data_ri = load_quadrant(pdata_dir, "2ri", data_rr.shape)
        data_ir = load_quadrant(pdata_dir, "2ir", data_rr.shape)
        data_ii = load_quadrant(pdata_dir, "2ii", data_rr.shape)

        θ2 = np.deg2rad(phc0_f2 + phc1_f2 * np.arange(n_f2) / max(1, n_f2 - 1))
        θ1 = np.deg2rad(phc0_f1 + phc1_f1 * np.arange(n_f1) / max(1, n_f1 - 1))

        cos2 = np.cos(θ2)          # shape (n_f2,)
        sin2 = np.sin(θ2)
        cos1 = np.cos(θ1)[:, None] # shape (n_f1, 1)
        sin1 = np.sin(θ1)[:, None]

        rr_ph = data_rr * cos2 - data_ri * sin2   # F2-phased real rows
        ir_ph = data_ir * cos2 - data_ii * sin2   # F2-phased imag rows
        data_matrix = rr_ph * cos1 - ir_ph * sin1
    else:
        # No phasing — use 2rr directly (reference-script behaviour)
        data_matrix = data_rr

    # --- F2 axis via nmrglue unit-conversion ---
    C = ng.convert.converter()
    C.from_bruker(dic, data_matrix)
    pdic, pdata_pipe = C.to_pipe()
    uc_f2 = ng.pipe.make_uc(pdic, pdata_pipe, dim=1)
    ppm_f2 = uc_f2.ppm_scale()

    # --- F1 axis: from the dataset's own indirect-dimension parameters ---
    ppm_f1 = _f1_ppm_scale(dic, data_matrix.shape[0])

    return dic, data_matrix, ppm_f2, ppm_f1


def _f1_ppm_scale(dic: dict, n_points: int) -> np.ndarray:
    """
    Build the indirect-dimension ppm axis from proc2s (falling back to procs,
    then to the module-level constants). Returns an array of length n_points.
    """
    for key in ("proc2s", "procs"):
        block = dic.get(key) or {}
        try:
            sf = float(block["SF"])
            sw_p = float(block["SW_p"])
            offset = float(block["OFFSET"])
        except (KeyError, TypeError, ValueError):
            continue
        if sf > 0 and sw_p > 0:
            return np.linspace(offset, offset - sw_p / sf, n_points)

    print("  [warn] No usable proc2s/procs F1 parameters; using reference defaults.")
    return np.linspace(OFFSET1, OFFSET1 - SW_p1 / SF1, n_points)


def make_contour_levels(data_matrix: np.ndarray) -> tuple[list, list]:
    """
    Compute positive and negative contour levels using the reference algorithm:
        noise = std(data[950:, 950:])
        base  = noise × 8
        levels = [base × 1.4^i  for i in range(14)]
    """
    n_rows, n_cols = data_matrix.shape
    r0 = NOISE_REGION_ROW if n_rows > NOISE_REGION_ROW else int(n_rows * 0.93)
    c0 = NOISE_REGION_COL if n_cols > NOISE_REGION_COL else int(n_cols * 0.93)
    noise_region = data_matrix[r0:, c0:]
    noise = float(np.std(noise_region)) if noise_region.size > 0 else float(np.std(data_matrix))
    if noise == 0.0:
        noise = 1.0

    base = noise * CONTOUR_BASE_MULT
    cl_pos = [base * (CONTOUR_FACTOR ** i) for i in range(N_POS_LEVELS)]
    cl_neg = [-c for c in reversed(cl_pos)]
    return cl_pos, cl_neg


def plot_spectrum(data_matrix: np.ndarray,
                  ppm_f2: np.ndarray,
                  ppm_f1: np.ndarray,
                  title: str = "Bruker 2D NMR Spectrum") -> None:
    """
    Draw the 2D contour plot exactly as in the reference script.
    """
    cl_pos, cl_neg = make_contour_levels(data_matrix)

    fig, ax = plt.subplots(figsize=(9, 9), facecolor="white")
    ax.set_facecolor("white")

    # Negative contours — red dashed
    ax.contour(ppm_f2, ppm_f1, data_matrix,
               levels=cl_neg,
               colors=["#ff4d4d"],
               linewidths=0.5,
               linestyles="dashed",
               alpha=0.6)

    # Positive contours — cool colormap
    ax.contour(ppm_f2, ppm_f1, data_matrix,
               levels=cl_pos,
               cmap="cool",
               linewidths=0.6,
               alpha=0.85)

    # Axis limits — descending ppm (high → low, left → right)
    ax.set_xlim(9.8, -0.3)
    ax.set_ylim(9.8, -0.3)

    # Diagonal reference line
    ax.plot([-0.3, 9.8], [-0.3, 9.8],
            color="#444455", lw=0.6, linestyle="--", zorder=0)

    # Ticks and spines
    ax.tick_params(color="#aaaaaa", labelsize=9)
    ax.xaxis.set_major_locator(MultipleLocator(1))
    ax.yaxis.set_major_locator(MultipleLocator(1))
    for spine in ax.spines.values():
        spine.set_edgecolor("#333344")

    ax.set_xlabel("F2 / ¹H [ppm]", fontsize=10, fontweight="bold")
    ax.set_ylabel("F1 / ¹⁵N [ppm]", fontsize=10, fontweight="bold")
    ax.set_title(title, fontsize=12, fontweight="bold")

    plt.tight_layout()
    plt.show()


def plot_bruker_2d(input_path: str,
                   phc0_f2: float = 0.0, phc1_f2: float = 0.0,
                   phc0_f1: float = 0.0, phc1_f1: float = 0.0) -> None:
    """
    Main entry point.  Locates the processed-data directory, loads all
    quadrants, and renders the 2D contour plot.
    """
    pdata_dir = find_pdata_dir(input_path)
    if pdata_dir is None:
        print(f"Error: Could not find Bruker processed data (2rr + procs) under: {input_path}")
        return

    print(f"Found pdata directory: {pdata_dir}")

    _dic, data_matrix, ppm_f2, ppm_f1 = build_spectrum(
        pdata_dir, phc0_f2, phc1_f2, phc0_f1, phc1_f1
    )

    print(f"  Spectrum shape : {data_matrix.shape}")
    print(f"  F2 range       : {ppm_f2[-1]:.3f} – {ppm_f2[0]:.3f} ppm")
    print(f"  F1 range       : {ppm_f1[-1]:.3f} – {ppm_f1[0]:.3f} ppm")

    plot_spectrum(data_matrix, ppm_f2, ppm_f1,
                  title=f"Bruker 2D NMR — {os.path.basename(pdata_dir)}")


# ---------------------------------------------------------------------------
if __name__ == "__main__":
    path = sys.argv[1] if len(sys.argv) > 1 else \
        "/Users/shreyasreddy/Desktop/APSI copy/Data/METABOLITE/158/pdata/1"

    # Optional phase corrections can be passed as positional args:
    #   python plot_bruker_2d.py <path> phc0_f2 phc1_f2 phc0_f1 phc1_f1
    phc0_f2 = float(sys.argv[2]) if len(sys.argv) > 2 else 0.0
    phc1_f2 = float(sys.argv[3]) if len(sys.argv) > 3 else 0.0
    phc0_f1 = float(sys.argv[4]) if len(sys.argv) > 4 else 0.0
    phc1_f1 = float(sys.argv[5]) if len(sys.argv) > 5 else 0.0

    plot_bruker_2d(path, phc0_f2, phc1_f2, phc0_f1, phc1_f1)