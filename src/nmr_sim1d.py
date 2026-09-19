#!/usr/bin/env python3
"""
Synthetic 1D spin-system simulator.

Emits exactly the envelope nmr_1d.generate() returns, so the Java side cannot
tell a simulated spectrum from a loaded one: same trace encoding, same peak
list format, same axis labels.  That is the whole point of reusing
serialise_trace() and pick_peaks() from nmr_1d rather than reimplementing
them -- a simulated spectrum then exercises the same display, peak picking
and region-zoom paths as a real Bruker dataset.

A spin system is a list of groups, one per chemically distinct set of
equivalent protons:

    {"shift": 1.23, "protons": 3, "j": [7.5], "width": 0.9, "label": "CH3"}

`j` holds the coupling constants in Hz to neighbouring protons.  n couplings
of equal size split a line into a binomial multiplet, which is what first
order (weak coupling) analysis predicts and what TopSpin draws for a system
whose shift separation is comfortably larger than its couplings.  Second
order effects (roofing, deceptive simplicity) are deliberately not modelled:
they need a full density matrix treatment, and the point here is a clean
reference spectrum to test the viewer against.
"""

import json
import sys

import numpy as np

import nmr_1d


# ---------------------------------------------------------------------------
# Defaults
# ---------------------------------------------------------------------------

DEFAULT_SF = 400.0          # spectrometer 1H frequency, MHz
DEFAULT_PPM_MIN = -0.5
DEFAULT_PPM_MAX = 10.0
DEFAULT_POINTS = 32768      # spectrum points before trace envelope encoding
DEFAULT_WIDTH_HZ = 1.0      # peak FWHM
DEFAULT_NOISE = 0.0015      # fraction of the tallest line
MAX_MULTIPLET_LINES = 4096  # guard against a pathological coupling list

# Ethylbenzene in CDCl3: a triplet/quartet pair plus the aromatic envelope and
# the residual solvent line.  Chosen because the CH3 triplet and CH2 quartet
# make first-order splitting obvious at a glance, and the 1:2:3 integral ratio
# between CH3, CH2 and the aromatics is easy to check by eye.
DEFAULT_SYSTEM = [
    {"shift": 7.28, "protons": 2, "j": [],       "width": 1.4, "label": "ArH meta"},
    {"shift": 7.19, "protons": 3, "j": [],       "width": 1.4, "label": "ArH ortho/para"},
    {"shift": 7.26, "protons": 0.4, "j": [],     "width": 1.2, "label": "CHCl3"},
    {"shift": 2.65, "protons": 2, "j": [7.6, 7.6, 7.6], "width": 0.9, "label": "CH2"},
    {"shift": 1.24, "protons": 3, "j": [7.6, 7.6],      "width": 0.9, "label": "CH3"},
    {"shift": 0.00, "protons": 0.6, "j": [],     "width": 0.8, "label": "TMS"},
]


# ---------------------------------------------------------------------------
# Line shapes
# ---------------------------------------------------------------------------

def multiplet_lines(centre_hz, couplings):
    """
    Split one resonance into its first-order multiplet.

    Each coupling halves every existing line into two of equal weight, offset
    by +/- J/2.  Applying that repeatedly gives the binomial intensity pattern
    (1:1, 1:2:1, 1:3:3:1 ...) without hardcoding any of it, and handles
    unequal couplings -- a doublet of doublets -- for free.
    """
    lines = [(float(centre_hz), 1.0)]
    for j in couplings:
        j = float(j)
        if j == 0.0:
            continue
        split = []
        for freq, weight in lines:
            split.append((freq - j / 2.0, weight / 2.0))
            split.append((freq + j / 2.0, weight / 2.0))
        lines = split
        if len(lines) > MAX_MULTIPLET_LINES:
            break

    # Merge lines that landed on the same frequency, which is what turns the
    # 2^n split lines of n equal couplings back into an (n+1)-line binomial
    # multiplet instead of leaving thousands of coincident zero-width lines.
    merged = {}
    for freq, weight in lines:
        key = round(freq, 6)
        merged[key] = merged.get(key, 0.0) + weight
    return sorted(merged.items())


def lorentzian(axis_hz, centre_hz, fwhm_hz):
    """Unit-height Lorentzian. NMR lines are Lorentzian when relaxation, not
    field inhomogeneity, sets the width -- the right default for a simulation."""
    half = max(fwhm_hz, 1e-6) / 2.0
    return half * half / ((axis_hz - centre_hz) ** 2 + half * half)


# ---------------------------------------------------------------------------
# Simulation
# ---------------------------------------------------------------------------

def simulate(cfg=None):
    """
    Build a synthetic spectrum. Returns (spectrum, ppm, groups, sf).

    The intensity of each group is its proton count spread over the multiplet
    lines, so integrals stay proportional to the number of protons however the
    multiplet splits -- the property anyone checks a simulation against first.
    """
    cfg = cfg or {}

    sf = _pos_float(cfg.get("sf"), DEFAULT_SF)
    ppm_min = _float(cfg.get("ppm_min"), DEFAULT_PPM_MIN)
    ppm_max = _float(cfg.get("ppm_max"), DEFAULT_PPM_MAX)
    if ppm_max <= ppm_min:
        ppm_min, ppm_max = DEFAULT_PPM_MIN, DEFAULT_PPM_MAX

    n_points = int(_pos_float(cfg.get("points"), DEFAULT_POINTS))
    n_points = max(1024, min(n_points, 262144))

    groups = cfg.get("system") or DEFAULT_SYSTEM
    if not isinstance(groups, list) or not groups:
        groups = DEFAULT_SYSTEM

    ppm = np.linspace(ppm_min, ppm_max, n_points)
    axis_hz = ppm * sf                      # ppm -> Hz on this spectrometer
    spectrum = np.zeros(n_points, dtype=float)

    for group in groups:
        if not isinstance(group, dict):
            continue
        shift = _float(group.get("shift"), 0.0)
        protons = _float(group.get("protons"), 1.0)
        if protons <= 0.0:
            continue
        width = _pos_float(group.get("width"), DEFAULT_WIDTH_HZ)
        couplings = group.get("j") or []
        if not isinstance(couplings, (list, tuple)):
            couplings = []

        lines = multiplet_lines(shift * sf, couplings)
        total = sum(w for _, w in lines) or 1.0
        for freq_hz, weight in lines:
            spectrum += (protons * weight / total) * lorentzian(axis_hz, freq_hz, width)

    peak_height = float(spectrum.max()) if spectrum.size else 0.0
    if peak_height <= 0.0:
        peak_height = 1.0

    noise_frac = _float(cfg.get("noise"), DEFAULT_NOISE)
    if noise_frac > 0.0:
        seed = cfg.get("seed")
        rng = np.random.default_rng(int(seed) if seed is not None else 20260920)
        spectrum = spectrum + rng.normal(0.0, noise_frac * peak_height, n_points)

    return spectrum, ppm, groups, sf


def simulate_envelope(cfg=None):
    """Full response envelope, shaped exactly like nmr_1d.generate()'s."""
    cfg = cfg or {}
    try:
        spectrum, ppm, groups, sf = simulate(cfg)
    except Exception as exc:                                  # pragma: no cover
        return {"status": "error", "message": "Simulation failed: %s" % exc}

    trace, n_pts = nmr_1d.serialise_trace(spectrum, ppm)
    peaks, sigma = nmr_1d.pick_peaks(
        spectrum, ppm,
        threshold_mult=_pos_float(cfg.get("peak_threshold"), nmr_1d.PEAK_THRESHOLD),
        max_peaks=int(_pos_float(cfg.get("max_peaks"), nmr_1d.MAX_PEAKS)),
    )

    label_bits = [str(g.get("label")) for g in groups
                  if isinstance(g, dict) and g.get("label")]

    return {
        "status": "success",
        "dimension": 1,
        "vendor": "simulated",
        "confidence": 1.0,
        "x_label": "1H",
        "y_label": "Intensity",
        "z_label": "Intensity",
        "unique_atoms": ["1H", "Intensity"],
        "data": peaks,
        "trace": trace,
        "trace_points": n_pts,
        "spectrum_size": int(len(spectrum)),
        "x_min": round(float(np.min(ppm)), 5),
        "x_max": round(float(np.max(ppm)), 5),
        "y_min": float(np.min(spectrum)),
        "y_max": float(np.max(spectrum)),
        "noise": float(sigma),
        "title": cfg.get("title") or "Simulated 1D spin system",
        "pulprog": "zg30 (simulated)",
        "solvent": cfg.get("solvent") or "CDCl3",
        "sfo1": float(sf),
        "date": 0.0,
        "processing": ["simulated %d points over %.2f..%.2f ppm at %.1f MHz"
                       % (len(spectrum), float(np.min(ppm)), float(np.max(ppm)), sf),
                       "groups: " + (", ".join(label_bits) if label_bits
                                     else "%d unlabelled" % len(groups))],
        "warnings": [],
    }


# ---------------------------------------------------------------------------
# Config helpers -- a bad value falls back rather than raising, so a typo in a
# simulation parameter still yields a spectrum instead of an error dialog.
# ---------------------------------------------------------------------------

def _float(value, default):
    try:
        out = float(value)
    except (TypeError, ValueError):
        return default
    return default if np.isnan(out) or np.isinf(out) else out


def _pos_float(value, default):
    out = _float(value, default)
    return out if out > 0.0 else default


def main():
    raw = sys.stdin.read().strip()
    try:
        cfg = json.loads(raw) if raw else {}
    except ValueError:
        cfg = {}
    if not isinstance(cfg, dict):
        cfg = {}
    print(json.dumps(simulate_envelope(cfg)))


if __name__ == "__main__":
    main()
