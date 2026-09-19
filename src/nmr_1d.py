#!/usr/bin/env python3
"""
nmr_1d.py
=========
1D Bruker spectrum generation, shaped to match the 2D pipeline.

The 2D path in nmr_backend.py hands the Java front end a JSON envelope holding
contour polylines plus picked peaks plus experiment metadata.  This module
produces the same envelope for 1D data, with one substitution: where 2D sends
``contours``, 1D sends ``trace`` -- a single decimated polyline of the spectrum
in the identical ``"x,y x,y ..."`` encoding, so the front end can reuse its
existing point parser.

It accepts the same config keys the front end already sends for 2D (phasing,
baseline_order, calibration, do_ft, window_type, lb, gauss_sigma, zero-filling)
and ignores the ones that only make sense in the indirect dimension.

Processing routes, in the order they are tried:

  1. Processed spectrum -- read 1r (and 1i when phasing is asked for).
     This is what TopSpin already produced and is the default.
  2. do_ft -- read the raw fid, strip the Bruker digital-filter group delay,
     apodize, zero-fill, Fourier transform.  Real ppm axis from acqus.
  3. Reprocess-from-spectrum -- when a window is requested but no fid exists,
     inverse-FFT the stored spectrum to a pseudo-FID, apodize, zero-fill and
     transform back.  Approximate, and flagged as such in the output.

Usage:
    from nmr_1d import generate
    envelope = generate({"path": "/path/to/dataset", "lb": 1.0})

    $ python nmr_1d.py /path/to/dataset [--png out.png]
"""

import json
import os
import sys

import numpy as np

import nmr_dimension as nd


# ---------------------------------------------------------------------------
# Defaults
# ---------------------------------------------------------------------------

TRACE_POINTS = 4000      # envelope columns in the serialised trace
MAX_PEAKS = 50           # matches the 2D peak list length
PEAK_THRESHOLD = 8.0     # peak height in multiples of the noise estimate
PEAK_SEPARATION = 12     # minimum points between two picked peaks


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _cfg_float(cfg, key, default=0.0):
    try:
        return float(cfg.get(key, default))
    except (TypeError, ValueError):
        return default


def _cfg_int(cfg, key, default=0):
    try:
        return int(float(cfg.get(key, default)))
    except (TypeError, ValueError):
        return default


def estimate_noise(spectrum):
    """
    Robust noise estimate that does not need a user-chosen blank region.

    Median absolute deviation scaled to a Gaussian sigma.  Peaks are a small
    fraction of the points in a typical spectrum, so the MAD sees mostly
    baseline and is not dragged upward by strong signals the way std() is.
    """
    med = float(np.median(spectrum))
    mad = float(np.median(np.abs(spectrum - med)))
    sigma = 1.4826 * mad
    if sigma <= 0.0:
        sigma = float(np.std(spectrum)) or 1.0
    return med, sigma


def phase_1d(real, imag, phc0, phc1):
    """Zero- and first-order phase correction across the direct dimension."""
    n = len(real)
    theta = np.deg2rad(phc0 + phc1 * (np.arange(n) / max(1, n - 1)))
    return real * np.cos(theta) - imag * np.sin(theta)


def baseline_correct(spectrum, order):
    """Subtract a polynomial fitted to the whole spectrum."""
    if order < 0:
        return spectrum
    x = np.arange(len(spectrum))
    coeffs = np.polyfit(x, spectrum, order)
    return spectrum - np.polyval(coeffs, x)


def apodize(fid, window_type, lb, gauss_sigma, dwell):
    """
    Apply a window function to a time-domain signal.

    `dwell` is the sampling interval in seconds, so lb is a real line
    broadening in Hz rather than an index-scaled fudge factor.  When the dwell
    time is unknown (the reprocess-from-spectrum route) the caller passes a
    normalised one and the parameters behave like the 2D code's.
    """
    n = len(fid)
    t = np.arange(n) * dwell
    wt = (window_type or "none").lower()

    if wt in ("exp", "em", "exponential") and lb != 0.0:
        return fid * np.exp(-np.pi * lb * t), "exponential lb=%.3g Hz" % lb
    if wt in ("gauss", "gm", "gaussian") and gauss_sigma > 0.0:
        return fid * np.exp(-0.5 * (t / (gauss_sigma * dwell)) ** 2), \
            "gaussian sigma=%.3g pts" % gauss_sigma
    if wt in ("sine", "sp", "sinebell"):
        return fid * np.sin(np.pi * np.arange(n) / max(1, n - 1)), "sine bell"
    return fid, "none"


def zero_fill(fid, factor):
    """Pad with zeros to `factor` times the original length."""
    if factor <= 1:
        return fid
    return np.concatenate([fid, np.zeros(len(fid) * (factor - 1), dtype=fid.dtype)])


# ---------------------------------------------------------------------------
# Spectrum construction
# ---------------------------------------------------------------------------

def _ppm_axis(dic, data, ng):
    """ppm scale for a processed 1D spectrum."""
    udic = ng.bruker.guess_udic(dic, data)
    uc = ng.fileio.fileiobase.uc_from_udic(udic, 0)
    return uc.ppm_scale(), udic


def _ppm_axis_from_acqus(dic, n_points, ng):
    """
    ppm scale for a spectrum we transformed ourselves.

    guess_udic on raw data gives the sweep width, observe frequency and
    carrier; we only have to tell it the post-zero-fill size and that the data
    now lives in the frequency domain.
    """
    udic = ng.bruker.guess_udic(dic, np.zeros(n_points))
    udic[0]["size"] = n_points
    udic[0]["freq"] = True
    udic[0]["time"] = False
    uc = ng.fileio.fileiobase.uc_from_udic(udic, 0)
    return uc.ppm_scale(), udic


def _phase_complex(spec, phc0, phc1):
    """Rotate a complex spectrum by a zero- plus first-order phase ramp."""
    n = len(spec)
    theta = np.deg2rad(phc0 + phc1 * (np.arange(n) / max(1, n - 1)))
    return spec * np.exp(1j * theta)


def _apply_phase(spec, cfg, info, ng):
    """
    Phase a freshly transformed spectrum.

    Modes: "auto" (ACME, the default), "stored" (PHC0/PHC1 out of procs),
    "none".  Returns (spectrum, note) where note records what happened, so an
    auto-phase that silently failed is visible rather than mysterious.
    """
    mode = str(cfg.get("phase_mode", "auto")).lower()

    if mode == "auto":
        try:
            return ng.proc_autophase.autops(spec, "acme", disp=False), \
                {"mode": "auto", "algorithm": "acme"}
        except Exception as exc:
            # Fall back rather than hand back a dispersive spectrum.
            stored0, stored1 = _stored_phase(info)
            if stored0 or stored1:
                return _phase_complex(spec, stored0, stored1), {
                    "mode": "stored", "phc0": stored0, "phc1": stored1,
                    "auto_error": str(exc)}
            return spec, {"mode": "none", "auto_error": str(exc)}

    if mode == "stored":
        stored0, stored1 = _stored_phase(info)
        return _phase_complex(spec, stored0, stored1), \
            {"mode": "stored", "phc0": stored0, "phc1": stored1}

    return spec, {"mode": "none"}


def _stored_phase(info):
    """PHC0/PHC1 as TopSpin determined them, from procs. (0, 0) if absent."""
    pdata_dir = info.get("pdata_dir")
    if not pdata_dir:
        return 0.0, 0.0
    params = nd.read_params(os.path.join(pdata_dir, "procs"), ["PHC0", "PHC1"])
    def val(key):
        try:
            return float(params[key])
        except (KeyError, TypeError, ValueError):
            return 0.0
    return val("PHC0"), val("PHC1")


def build_spectrum(info, cfg, ng):
    """
    Produce (spectrum, ppm, notes) for the dataset described by `info`.

    `notes` records what was actually applied so the caller can report it
    rather than the front end having to guess.
    """
    notes = {
        "route": "processed",
        "phased": False,
        "window": "none",
        "zero_fill": 1,
        "baseline_order": -1,
        "reprocessed_from_spectrum": False,
    }

    pdata_dir = info.get("pdata_dir")
    exp_dir = info.get("exp_dir")

    phc0 = _cfg_float(cfg, "phc0_f2")
    phc1 = _cfg_float(cfg, "phc1_f2")
    # The front end sends 2D key names; accept the 1D spellings too.
    phc0 = _cfg_float(cfg, "phc0", phc0)
    phc1 = _cfg_float(cfg, "phc1", phc1)

    window_type = str(cfg.get("window_type", "none")).lower()
    lb = _cfg_float(cfg, "lb")
    gauss_sigma = _cfg_float(cfg, "gauss_sigma")
    zf = max(1, _cfg_int(cfg, "zf2", _cfg_int(cfg, "zf", 1)))
    do_ft = bool(cfg.get("do_ft", False))
    wants_window = window_type not in ("", "none") or zf > 1

    # Plenty of datasets ship a raw fid and no pdata at all -- there is no
    # processed spectrum to fall back on, so transforming is the only route.
    if not pdata_dir and exp_dir and info.get("raw_file") == "fid":
        do_ft = True
        notes["ft_implicit"] = "no processed 1r -- transformed the raw fid"

    # --- Route 2: transform the raw fid ourselves --------------------------
    if do_ft and exp_dir:
        try:
            dic_raw, fid = ng.bruker.read(exp_dir)
            fid = np.asarray(fid, dtype=np.complex128)
            if fid.ndim != 1:
                raise ValueError("raw data is %dD, not 1D" % fid.ndim)
            # Bruker's digital filter puts a group delay at the head of the
            # fid; leaving it in produces a badly rolling baseline.
            try:
                fid = ng.bruker.remove_digital_filter(dic_raw, fid)
            except Exception:
                pass

            sw_hz = float(dic_raw["acqus"].get("SW_h", 0.0)) or 1.0
            dwell = 1.0 / sw_hz
            fid, win_desc = apodize(fid, window_type, lb, gauss_sigma, dwell)
            fid = zero_fill(fid, zf)

            spec = ng.proc_base.rev(ng.proc_base.fft(fid))

            # A freshly transformed fid is unphased -- pure dispersion, which
            # is useless to look at, so phase it before handing it back.
            #
            # Automatic phasing rather than the PHC0/PHC1 sitting in procs:
            # those constants are relative to TopSpin's own digital-filter
            # handling, which is not the same as nmrglue's group-delay
            # removal, so reusing them leaves one end of the spectrum
            # dispersive.  ACME finds the correction directly from the
            # lineshapes and, just as importantly, still works for the many
            # datasets that ship a raw fid with no pdata at all.
            spec, phase_note = _apply_phase(spec, cfg, info, ng)
            notes["phased"] = phase_note["mode"] != "none"
            notes["phase"] = phase_note

            # Any phc the caller passes is an adjustment on top, so the front
            # end's phase sliders stay live whichever mode produced the base.
            if phc0 or phc1:
                spec = _phase_complex(spec, phc0, phc1)
                notes["phase"]["manual_phc0"] = phc0
                notes["phase"]["manual_phc1"] = phc1
                notes["phased"] = True

            spectrum = spec.real

            ppm, _udic = _ppm_axis_from_acqus(dic_raw, len(spectrum), ng)
            notes.update({"route": "fid-ft", "window": win_desc, "zero_fill": zf})
            return spectrum, ppm, notes, dic_raw
        except Exception as exc:
            notes["ft_error"] = str(exc)
            # fall through to the processed spectrum

    if not pdata_dir:
        raise RuntimeError(
            "No processed 1D data (1r) found and the raw fid could not be "
            "transformed%s."
            % (": " + notes["ft_error"] if "ft_error" in notes else ""))

    # --- Route 1: the processed spectrum -----------------------------------
    dic, real = ng.bruker.read_pdata(pdata_dir, bin_files=["1r"])
    real = np.asarray(real, dtype=np.float64)

    if phc0 or phc1:
        imag_path = os.path.join(pdata_dir, "1i")
        if os.path.isfile(imag_path):
            _d, imag = ng.bruker.read_pdata(pdata_dir, bin_files=["1i"])
            spectrum = phase_1d(real, np.asarray(imag, dtype=np.float64), phc0, phc1)
            notes["phased"] = True
        else:
            spectrum = real
            notes["phase_warning"] = "1i missing -- phase correction skipped"
    else:
        spectrum = real

    ppm, _udic = _ppm_axis(dic, spectrum, ng)

    # --- Route 3: reprocess an already-transformed spectrum ----------------
    # No fid to work from, but the caller asked for apodization or zero
    # filling, so go back to a pseudo-FID and forward again.  Approximate:
    # the imaginary part is gone, so the pseudo-FID is not the real one.
    if wants_window and not do_ft:
        n = len(spectrum)
        pseudo_fid = np.fft.ifft(np.fft.ifftshift(spectrum[::-1]))
        pseudo_fid, win_desc = apodize(pseudo_fid, window_type, lb,
                                       gauss_sigma, 1.0 / max(1, n))
        pseudo_fid = zero_fill(pseudo_fid, zf)
        spectrum = np.fft.fftshift(np.fft.fft(pseudo_fid)).real[::-1]
        ppm = np.linspace(ppm[0], ppm[-1], len(spectrum))
        notes.update({
            "route": "reprocessed",
            "window": win_desc,
            "zero_fill": zf,
            "reprocessed_from_spectrum": True,
        })

    return spectrum, ppm, notes, dic


# ---------------------------------------------------------------------------
# Output shaping
# ---------------------------------------------------------------------------

def pick_peaks(spectrum, ppm, threshold_mult=PEAK_THRESHOLD,
               separation=PEAK_SEPARATION, max_peaks=MAX_PEAKS,
               height_frac=None):
    """
    Pick the strongest maxima, returned in the 2D pipeline's point format.

    Ranked by intensity so the caller gets the important peaks when the list
    is truncated, then labelled P1..Pn in that same order -- exactly what the
    2D branch does.
    """
    from scipy.signal import find_peaks

    med, sigma = estimate_noise(spectrum)
    if height_frac is not None:
        height = med + height_frac * (float(spectrum.max()) - med)
    else:
        height = med + threshold_mult * sigma

    idx, _props = find_peaks(spectrum, height=height, distance=max(1, separation))
    if len(idx) == 0:
        return [], sigma

    order = np.argsort(-spectrum[idx])[:max_peaks]
    peaks = []
    for rank, i in enumerate(idx[order]):
        peaks.append({
            "seq_id": "P%d" % (rank + 1),
            "residue": "PEAK",
            "x": round(float(ppm[i]), 5),
            "y": float(spectrum[i]),
            "z": float(spectrum[i]),
        })
    return peaks, sigma


def serialise_trace(spectrum, ppm, target_points=TRACE_POINTS):
    """
    Encode the spectrum as a min/max envelope polyline.

    Straight subsampling of a 128k-point spectrum drops narrow peaks between
    samples; taking the min and the max of each bin, emitted in the order they
    occur inside the bin, keeps every peak's true height while cutting the
    payload by an order of magnitude.  Format matches the 2D contour encoding:
    "x,y x,y x,y".
    """
    n = len(spectrum)
    if n <= target_points * 2:
        pts = ["%.5f,%.6g" % (ppm[i], spectrum[i]) for i in range(n)]
        return " ".join(pts), n

    bins = np.array_split(np.arange(n), target_points)
    pts = []
    for b in bins:
        if b.size == 0:
            continue
        seg = spectrum[b]
        i_min = int(b[0] + np.argmin(seg))
        i_max = int(b[0] + np.argmax(seg))
        for i in sorted((i_min, i_max)):
            pts.append("%.5f,%.6g" % (ppm[i], spectrum[i]))
    return " ".join(pts), len(pts)


def read_metadata(info):
    """Title / pulse program / solvent / frequency / date, as the 2D branch reports."""
    meta = {
        "title": "", "pulprog": info.get("pulprog", ""),
        "solvent": info.get("solvent", ""), "sfo1": 0.0, "date": 0.0,
        "exp_name": info.get("exp_name", ""), "exp_no": info.get("exp_no", ""),
        "proc_no": info.get("proc_no", ""),
    }

    pdata_dir = info.get("pdata_dir")
    if pdata_dir:
        title_path = os.path.join(pdata_dir, "title")
        if os.path.isfile(title_path):
            try:
                with open(title_path, "r", encoding="utf-8", errors="ignore") as fh:
                    meta["title"] = fh.readline().strip()
            except OSError:
                pass

    exp_dir = info.get("exp_dir")
    if exp_dir:
        params = nd.read_params(os.path.join(exp_dir, "acqus"),
                                ["SFO1", "DATE", "SOLVENT", "PULPROG"])
        try:
            meta["sfo1"] = float(params.get("SFO1", 0.0))
        except (TypeError, ValueError):
            pass
        try:
            meta["date"] = float(params.get("DATE", 0.0))
        except (TypeError, ValueError):
            pass
        if not meta["solvent"]:
            meta["solvent"] = (params.get("SOLVENT") or "").replace("<", "").replace(">", "")
        if not meta["pulprog"]:
            meta["pulprog"] = (params.get("PULPROG") or "").replace("<", "").replace(">", "")

    return meta


def render_png(spectrum, ppm, peaks, png_path, title=""):
    """Optional quick render, mirroring the 2D branch's render_png_path."""
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    fig, ax = plt.subplots(figsize=(10, 4.5))
    ax.plot(ppm, spectrum, linewidth=0.6, color="#2b6cb0")
    for p in peaks[:15]:
        ax.plot(p["x"], p["y"], "rx", markersize=4)
    ax.invert_xaxis()
    ax.set_xlabel("ppm")
    ax.set_ylabel("Intensity")
    if title:
        ax.set_title(title)
    fig.tight_layout()
    fig.savefig(png_path, dpi=110)
    plt.close(fig)


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

def generate(cfg, info=None):
    """
    Build the 1D JSON envelope for the dataset named by cfg["path"].

    `info` is an optional pre-computed nmr_dimension.detect() result, so the
    caller that already detected the dimensionality does not pay for it twice.
    """
    path = cfg.get("path", "")
    if info is None:
        info = nd.detect(path)

    if info.get("status") != "success":
        return {"status": "error", "message": info.get("message", "detection failed")}
    if info.get("ndim") != 1:
        return {"status": "error",
                "message": "nmr_1d.generate called on %dD data" % info.get("ndim")}  # noqa: UP031
    if info.get("vendor") != "bruker":
        return {"status": "error",
                "message": info.get("message", "unsupported vendor")}

    import nmrglue as ng

    spectrum, ppm, notes, _dic = build_spectrum(info, cfg, ng)

    baseline_order = _cfg_int(cfg, "baseline_order", -1)
    if baseline_order >= 0:
        spectrum = baseline_correct(spectrum, baseline_order)
        notes["baseline_order"] = baseline_order

    calib_x = _cfg_float(cfg, "calib_x")
    if calib_x:
        ppm = ppm + calib_x

    peaks, sigma = pick_peaks(
        spectrum, ppm,
        threshold_mult=_cfg_float(cfg, "peak_threshold", PEAK_THRESHOLD),
        max_peaks=_cfg_int(cfg, "max_peaks", MAX_PEAKS),
        height_frac=(_cfg_float(cfg, "peak_height_frac")
                     if cfg.get("peak_height_frac") is not None else None),
    )
    trace, n_pts = serialise_trace(
        spectrum, ppm, _cfg_int(cfg, "trace_points", TRACE_POINTS))

    meta = read_metadata(info)
    x_label = info.get("nuc1") or "1H"

    png_path = cfg.get("render_png_path")
    if png_path:
        try:
            render_png(spectrum, ppm, peaks, png_path, meta.get("title", ""))
        except Exception:
            pass

    envelope = {
        "status": "success",
        "dimension": 1,
        "vendor": "bruker",
        "confidence": info.get("confidence"),
        "x_label": x_label,
        "y_label": "Intensity",
        "z_label": "Intensity",
        "unique_atoms": [x_label, "Intensity"],
        "data": peaks,
        "trace": trace,
        "trace_points": n_pts,
        "spectrum_size": int(len(spectrum)),
        "x_min": round(float(np.min(ppm)), 5),
        "x_max": round(float(np.max(ppm)), 5),
        "y_min": float(np.min(spectrum)),
        "y_max": float(np.max(spectrum)),
        "noise": float(sigma),
        "processing": notes,
        "warnings": info.get("warnings", []),
    }
    envelope.update(meta)
    return envelope


def main():
    if len(sys.argv) < 2:
        print(json.dumps({"status": "error",
                          "message": "Usage: nmr_1d.py <path> [--png out.png]"}))
        return 1

    cfg = {"path": sys.argv[1]}
    if "--png" in sys.argv:
        cfg["render_png_path"] = sys.argv[sys.argv.index("--png") + 1]
    for flag in ("--lb", "--zf", "--phc0", "--phc1", "--baseline"):
        if flag in sys.argv:
            key = {"--lb": "lb", "--zf": "zf", "--phc0": "phc0",
                   "--phc1": "phc1", "--baseline": "baseline_order"}[flag]
            cfg[key] = sys.argv[sys.argv.index(flag) + 1]
    if "--ft" in sys.argv:
        cfg["do_ft"] = True
    if "--window" in sys.argv:
        cfg["window_type"] = sys.argv[sys.argv.index("--window") + 1]

    try:
        env = generate(cfg)
    except Exception as exc:
        import traceback
        print(json.dumps({"status": "error", "message": str(exc),
                          "traceback": traceback.format_exc()}))
        return 1

    # Keep stdout readable when run by hand: the trace is huge.
    if "--full" not in sys.argv:
        env = dict(env)
        env["trace"] = "<%d points>" % env.get("trace_points", 0)
    print(json.dumps(env, indent=2))
    return 0 if env.get("status") == "success" else 1


if __name__ == "__main__":
    sys.exit(main())
