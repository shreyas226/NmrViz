#!/usr/bin/env python3
import sys
import json
import math
import re
import csv
import traceback


# ─────────────────────────────────────────────────────────────────────────────
# NMR-STAR / CSV PARSERS  (unchanged from original)
# ─────────────────────────────────────────────────────────────────────────────

def parse_nmr_star(file_content):
    lines = file_content.splitlines()
    in_loop = False
    loop_headers = []
    shifts = []
    chem_shift_loop_headers = [
        '_atom_chem_shift', '_atom_shift_assign', '_chem_shift'
    ]
    is_chem_shift_loop = False
    i = 0
    while i < len(lines):
        line = lines[i].strip()
        if not line or line.startswith('#'):
            i += 1
            continue
        if line == 'loop_':
            in_loop = True
            loop_headers = []
            is_chem_shift_loop = False
            i += 1
            continue
        if in_loop:
            if line.startswith('_'):
                loop_headers.append(line)
                lower_line = line.lower()
                if (any(h in lower_line for h in chem_shift_loop_headers)
                        or 'chem_shift_value' in lower_line
                        or 'atom_shift' in lower_line):
                    is_chem_shift_loop = True
                i += 1
            else:
                loop_data_rows = []
                while i < len(lines):
                    data_line = lines[i].strip()
                    if not data_line:
                        i += 1
                        continue
                    if data_line.startswith('#'):
                        i += 1
                        continue
                    if (data_line == 'stop_' or data_line == 'loop_'
                            or data_line.startswith('save_')
                            or (data_line.startswith('_')
                                and not data_line.startswith('_Atom_chem_shift')
                                and not data_line.startswith('_Atom_shift_assign'))):
                        break
                    tokens = re.findall(r'"[^"]*"|\'[^\']*\'|\S+', data_line)
                    if len(tokens) == len(loop_headers):
                        loop_data_rows.append(tokens)
                    i += 1
                if is_chem_shift_loop and loop_headers:
                    shifts.extend(process_loop_data(loop_headers, loop_data_rows))
                in_loop = False
                is_chem_shift_loop = False
        else:
            i += 1
    return shifts


def process_loop_data(headers, rows):
    mapped_shifts = []
    clean_headers = [h.strip().lower() for h in headers]
    seq_idx = res_label_idx = atom_name_idx = atom_type_idx = val_idx = -1
    for idx, h in enumerate(clean_headers):
        if (('seq_id' in h or 'seq_code' in h or 'residue_seq_code' in h)
                and not any(x in h for x in ['author', 'entity', 'db_'])):
            if seq_idx == -1 or h.endswith('seq_id') or h.endswith('seq_code'):
                seq_idx = idx
        if 'comp_id' in h or 'residue_label' in h or 'comp_name' in h or 'res_name' in h:
            if res_label_idx == -1 or h.endswith('comp_id') or h.endswith('residue_label'):
                res_label_idx = idx
        if ('atom_id' in h or 'atom_name' in h) and 'type' not in h:
            if atom_name_idx == -1 or h.endswith('atom_id') or h.endswith('atom_name'):
                atom_name_idx = idx
        if 'atom_type' in h:
            atom_type_idx = idx
        if (('val' in h or 'value' in h)
                and not any(x in h for x in ['error', 'ambiguity', 'dev', 'fit', 'method', 'flag', 'status'])):
            if val_idx == -1 or h.endswith('.val') or h == '_chem_shift_value':
                val_idx = idx
    if val_idx == -1 or atom_name_idx == -1:
        return []
    for row in rows:
        try:
            val_str = row[val_idx].strip()
            if val_str == '.':
                continue
            val = float(val_str)
            atom_name = row[atom_name_idx].strip().replace('"', '').replace("'", "")
            seq_id = row[seq_idx].strip() if seq_idx != -1 else "1"
            res_label = (row[res_label_idx].strip().replace('"', '').replace("'", "")
                         if res_label_idx != -1 else "UNK")
            if atom_type_idx != -1:
                atom_type = row[atom_type_idx].strip().replace('"', '').replace("'", "")
            else:
                if atom_name.startswith('H'):
                    atom_type = 'H'
                elif atom_name.startswith('C'):
                    atom_type = 'C'
                elif atom_name.startswith('N'):
                    atom_type = 'N'
                else:
                    atom_type = 'O'
            mapped_shifts.append({
                'seq_id': seq_id, 'residue': res_label,
                'atom_name': atom_name, 'atom_type': atom_type, 'val': val
            })
        except ValueError:
            continue
    return mapped_shifts


def parse_csv_shifts(file_content):
    shifts = []
    lines = file_content.splitlines()
    reader = csv.reader(lines)
    rows = list(reader)
    if not rows:
        return []
    headers = [h.strip().lower() for h in rows[0]]
    seq_idx = res_label_idx = atom_name_idx = val_idx = -1
    for idx, h in enumerate(headers):
        if h in ['seq', 'seq_id', 'seq_num', 'res_num', 'residue_num', 'num', 'id']:
            seq_idx = idx
        elif (any(x in h for x in ['residue', 'res_name', 'comp', 'comp_id'])
              and h not in ['seq', 'seq_id', 'seq_num']):
            res_label_idx = idx
        elif h in ['atom', 'atom_name', 'atom_id', 'name']:
            atom_name_idx = idx
        elif h in ['shift', 'val', 'value', 'ppm']:
            val_idx = idx
    start_row = 1
    if val_idx == -1 or atom_name_idx == -1:
        try:
            float(rows[0][3])
            seq_idx, res_label_idx, atom_name_idx, val_idx = 0, 1, 2, 3
            start_row = 0
        except (IndexError, ValueError):
            try:
                float(rows[0][2])
                seq_idx, atom_name_idx, val_idx = 0, 1, 2
                start_row = 0
            except (IndexError, ValueError):
                return []
    for row_idx in range(start_row, len(rows)):
        row = rows[row_idx]
        if not row or len(row) <= max(val_idx, atom_name_idx):
            continue
        try:
            val_str = row[val_idx].strip()
            if not val_str or val_str == '.':
                continue
            val = float(val_str)
            atom_name = row[atom_name_idx].strip()
            seq_id = row[seq_idx].strip() if seq_idx != -1 else str(row_idx)
            res_label = row[res_label_idx].strip() if res_label_idx != -1 else "UNK"
            if atom_name.startswith('H'):
                atom_type = 'H'
            elif atom_name.startswith('C'):
                atom_type = 'C'
            elif atom_name.startswith('N'):
                atom_type = 'N'
            else:
                atom_type = 'O'
            shifts.append({
                'seq_id': seq_id, 'residue': res_label,
                'atom_name': atom_name, 'atom_type': atom_type, 'val': val
            })
        except ValueError:
            continue
    return shifts


def pair_atoms(shifts, x_atom, y_atom, z_atom):
    by_residue = {}
    for s in shifts:
        seq_id = s['seq_id']
        if seq_id not in by_residue:
            by_residue[seq_id] = {'seq_id': seq_id, 'residue': s['residue'], 'atoms': {}}
        by_residue[seq_id]['atoms'][s['atom_name']] = s['val']
    paired_data = []
    for seq_id, res_info in by_residue.items():
        atoms = res_info['atoms']
        x_val = atoms.get(x_atom)
        y_val = atoms.get(y_atom)
        if x_val is not None and y_val is not None:
            z_val = atoms.get(z_atom) if z_atom else None
            paired_data.append({
                'seq_id': seq_id, 'residue': res_info['residue'],
                'x': x_val, 'y': y_val, 'z': z_val
            })

    def try_int(s):
        try:
            return int(re.sub(r'\D', '', s))
        except ValueError:
            return 99999

    paired_data.sort(key=lambda item: try_int(item['seq_id']))
    return paired_data


# ─────────────────────────────────────────────────────────────────────────────
# BRUKER FILE DISCOVERY
# ─────────────────────────────────────────────────────────────────────────────

def find_bruker_pdata_dir(start_path):
    """
    Walk from start_path to locate the directory that contains both
    '2rr' and 'procs'.  Searches:
      1. The path itself
      2. pdata/1  (and other pdata/<n> siblings)
      3. Full recursive os.walk (depth-unlimited)
      4. Up to 3 parent directories, applying steps 1-3 each time
    Returns the directory path string, or None if not found.
    """
    import os

    def _has_processed(d):
        return (os.path.exists(os.path.join(d, '2rr'))
                and os.path.exists(os.path.join(d, 'procs')))

    if not os.path.exists(start_path):
        return None

    if os.path.isfile(start_path):
        start_path = os.path.dirname(start_path)

    # 1. The directory itself
    if _has_processed(start_path):
        return start_path

    # 2. pdata/<n> subdirectories
    pdata_dir = os.path.join(start_path, 'pdata')
    if os.path.isdir(pdata_dir):
        # Try numeric subdirs in order: 1, 2, …
        subs = sorted(
            (s for s in os.listdir(pdata_dir)
             if os.path.isdir(os.path.join(pdata_dir, s))),
            key=lambda s: (not s.isdigit(), s)
        )
        for sub in subs:
            candidate = os.path.join(pdata_dir, sub)
            if _has_processed(candidate):
                return candidate

    # 3. Recursive walk (finds any nested pdata)
    for root, dirs, files in os.walk(start_path):
        if '2rr' in files and 'procs' in files:
            return root

    # 4. Walk up three parent levels and repeat
    curr = start_path
    for _ in range(3):
        parent = os.path.dirname(curr)
        if parent == curr:
            break
        if _has_processed(parent):
            return parent
        pdata_dir = os.path.join(parent, 'pdata')
        if os.path.isdir(pdata_dir):
            subs = sorted(
                (s for s in os.listdir(pdata_dir)
                 if os.path.isdir(os.path.join(pdata_dir, s))),
                key=lambda s: (not s.isdigit(), s)
            )
            for sub in subs:
                candidate = os.path.join(pdata_dir, sub)
                if _has_processed(candidate):
                    return candidate
        for root, dirs, files in os.walk(parent):
            if '2rr' in files and 'procs' in files:
                return root
        curr = parent

    return None


# ─────────────────────────────────────────────────────────────────────────────
# BRUKER PROCESSING  (core, rewritten)
# ─────────────────────────────────────────────────────────────────────────────

# Everything up to (and including) the noise estimate depends only on the
# processing parameters, never on the contour threshold. The serve loop reuses
# one cached result across every contour request for the same parameters.
# Prepared spectra, most-recently-used last. Holding more than one matters now
# that the dataset browser makes moving between spectra a click: with a single
# slot, going back to the one looked at a moment ago re-read it from disk and
# re-ran the whole FT/phase/baseline chain.
#
# The cost of a slot is the processed matrix - 8 MB for a 1024x1024 double - so
# a handful is cheap next to the second and a half each re-preparation takes.
_PREP_CACHE = {}
_PREP_CACHE_MAX = 4


def _prepare_bruker(path, phc0_f2, phc1_f2, phc0_f1, phc1_f1,
                    baseline_order, calib_x, calib_y):
    key = (path, phc0_f2, phc1_f2, phc0_f1, phc1_f1,
           baseline_order, calib_x, calib_y)
    if key in _PREP_CACHE:
        # Re-insert so the most recently used entry is evicted last.
        _PREP_CACHE[key] = _PREP_CACHE.pop(key)
        return _PREP_CACHE[key]

    import os
    import nmrglue as ng
    import numpy as np

    # ── 2. Locate processed data directory ───────────────────────────────────
    pdata_dir = find_bruker_pdata_dir(path)
    if not pdata_dir:
        return {
            "status": "error",
            "message": (
                f"Could not locate Bruker processed files "
                f"(2rr + procs) starting from: {path}"
            )
        }

    # ── 3. Load the real-real (RR) quadrant via nmrglue ──────────────────────
    dic, data_rr = ng.bruker.read_pdata(pdata_dir)

    # ── 4. Phase correction ───────────────────────────────────────────────────
    # The imaginary quadrants (2ri/2ir/2ii) are only ever consumed by the phase
    # rotation below, so an unphased load skips three 4 MB reads entirely.
    is_phasing = any(v != 0.0 for v in (phc0_f2, phc1_f2, phc0_f1, phc1_f1))

    if is_phasing:
        def load_quad(fname):
            """Load a single binary quadrant; fall back to zeros."""
            if not os.path.isfile(os.path.join(pdata_dir, fname)):
                return np.zeros_like(data_rr)
            try:
                _, arr = ng.bruker.read_pdata(pdata_dir, bin_files=[fname])
                if arr.shape == data_rr.shape:
                    return arr
                return np.zeros_like(data_rr)
            except Exception:
                return np.zeros_like(data_rr)

        data_ri = load_quad('2ri')
        data_ir = load_quad('2ir')
        data_ii = load_quad('2ii')

        n_f1, n_f2 = data_rr.shape

        # Linear ramps from 0→1 across each dimension
        ramp_f2 = np.arange(n_f2) / max(1, n_f2 - 1)
        ramp_f1 = np.arange(n_f1) / max(1, n_f1 - 1)

        theta2 = np.deg2rad(phc0_f2 + phc1_f2 * ramp_f2)          # (n_f2,)
        theta1 = np.deg2rad(phc0_f1 + phc1_f1 * ramp_f1)          # (n_f1,)

        cos2 = np.cos(theta2)                                       # (n_f2,)
        sin2 = np.sin(theta2)
        cos1 = np.cos(theta1)[:, np.newaxis]                        # (n_f1, 1)
        sin1 = np.sin(theta1)[:, np.newaxis]

        # Phase along F2 first (rows kept intact)
        rr_f2 = data_rr * cos2 - data_ri * sin2
        ir_f2 = data_ir * cos2 - data_ii * sin2

        # Phase along F1
        data_matrix = rr_f2 * cos1 - ir_f2 * sin1
    else:
        data_matrix = data_rr

    # ── 5. Baseline correction (polynomial, every row at once) ───────────────
    # One least-squares solve against a shared Vandermonde matrix replaces a
    # per-row np.polyfit loop: the design matrix is identical for every row, so
    # fitting all of them at once is ~10x faster. The column scaling and rcond
    # below are exactly what np.polyfit applies internally, so the coefficients
    # match the old loop's to the last bit rather than merely closely.
    if baseline_order >= 0:
        n_cols = data_matrix.shape[1]
        x_idx = np.arange(n_cols, dtype=float)
        vander = np.vander(x_idx, baseline_order + 1)
        scale = np.sqrt((vander * vander).sum(axis=0))
        lhs = vander / scale
        rcond = n_cols * np.finfo(data_matrix.dtype).eps
        coeffs, *_ = np.linalg.lstsq(lhs, data_matrix.T, rcond=rcond)
        data_matrix = data_matrix - (lhs @ coeffs).T

    # ── 6. Build F2 (direct / 1H) ppm axis via nmrglue ──────────────────────
    C = ng.convert.converter()
    C.from_bruker(dic, data_matrix)
    pdic, pdata_ng = C.to_pipe()
    uc_f2 = ng.pipe.make_uc(pdic, pdata_ng, dim=1)
    f2_ppm = uc_f2.ppm_scale() + calib_x          # shape (n_f2,)

    # ── 7. Build F1 (indirect / 15N or 13C) ppm axis ────────────────────────
    #
    #   Prefer proc2s (indirect dimension parameters).
    #   Fall back to procs, then to nmrglue's own uc for dim=0.
    #
    def _ppm_from_procs(key):
        """Try to build a ppm scale from a Bruker procs-style dict."""
        d = dic.get(key, {})
        if not d:
            return None
        try:
            sf     = float(d['SF'])
            sw_p   = float(d['SW_p'])
            offset = float(d['OFFSET'])
            if sf <= 0 or sw_p <= 0:
                return None
            return np.linspace(offset, offset - sw_p / sf, data_matrix.shape[0])
        except (KeyError, TypeError, ValueError):
            return None

    f1_ppm = _ppm_from_procs('proc2s')
    if f1_ppm is None:
        f1_ppm = _ppm_from_procs('procs')
    if f1_ppm is None:
        f1_ppm = ng.pipe.make_uc(pdic, pdata_ng, dim=0).ppm_scale()
    f1_ppm = np.asarray(f1_ppm) + calib_y         # shape (n_f1,)

    # ── 8. Noise estimate ────────────────────────────────────────────────────
    # The far corner of the spectrum is signal-free. Index 950 is the canonical
    # cut for the 1024x1024 datasets this was built against; for anything smaller
    # fall back to the last ~7% of each axis rather than to the whole matrix,
    # which would fold real peaks into the "noise" and wash the contours out.
    n_rows, n_cols = data_matrix.shape
    r0 = 950 if n_rows > 950 else int(n_rows * 0.93)
    c0 = 950 if n_cols > 950 else int(n_cols * 0.93)
    noise_region = data_matrix[r0:, c0:]
    noise = float(np.std(noise_region)) if noise_region.size else float(np.std(data_matrix))
    if noise == 0.0:
        noise = 1.0

    # The tallest feature in the spectrum. Together with the noise it fixes how
    # far the contour threshold can usefully travel: below the noise everything
    # is drawn, above the peak nothing is.
    peak = float(np.abs(data_matrix).max())

    # ── 9. Extract nucleus labels from acqus ─────────────────────────────────
    def _nuc(key, sub):
        try:
            raw = str(dic[key][sub])
            return raw.replace('<', '').replace('>', '').strip()
        except (KeyError, TypeError):
            return None

    x_label = _nuc('acqus',  'NUC1') or '1H'
    y_label = _nuc('acqu2s', 'NUC1') or '15N'

    # Acquisition metadata, so a loaded 2D spectrum can say what produced it.
    # The 1D branch has always reported these; the 2D branch read the same
    # parameter files and then threw them away.
    def _param(key, sub, default=""):
        try:
            raw = dic[key][sub]
        except (KeyError, TypeError):
            return default
        if isinstance(raw, str):
            return raw.replace("<", "").replace(">", "").strip()
        return raw

    def _float_param(key, sub, default=0.0):
        try:
            return float(_param(key, sub, default))
        except (TypeError, ValueError):
            return default

    prepared = {
        "data_matrix": data_matrix,
        "f2_ppm": f2_ppm,
        "f1_ppm": f1_ppm,
        "noise": noise,
        "peak": peak,
        "x_label": x_label,
        "y_label": y_label,
        "pulprog": _param("acqus", "PULPROG"),
        "solvent": _param("acqus", "SOLVENT"),
        "sfo1": _float_param("acqus", "SFO1"),
    }
    _PREP_CACHE[key] = prepared
    # dicts preserve insertion order, so the oldest key is the first one.
    while len(_PREP_CACHE) > _PREP_CACHE_MAX:
        _PREP_CACHE.pop(next(iter(_PREP_CACHE)))
    return prepared

def process_bruker(cfg_str):
    """
    Full Bruker 2D processing pipeline.

    Accepts either:
      • a plain path string, or
      • a JSON object with keys:
          path, phc0_f2, phc1_f2, phc0_f1, phc1_f1,
          baseline_order, calib_x, calib_y, contour_base

    Returns a JSON-serialisable dict with keys:
      status, x_label, y_label, z_label, unique_atoms, data, contours
    """
    import contourpy
    import numpy as np

    # ── 1. Parse configuration ───────────────────────────────────────────────
    phc0_f2 = phc1_f2 = phc0_f1 = phc1_f1 = 0.0
    baseline_order = -1
    calib_x = calib_y = 0.0
    contour_base = 8.0      # noise multiplier for base contour level
    ladder = False          # emit a geometric ladder instead of one 14-level window
    ladder_k_min = None     # None -> derive from the spectrum (see below)
    ladder_k_max = None

    cfg = {}
    cfg_str = cfg_str.strip()
    if cfg_str.startswith('{') and cfg_str.endswith('}'):
        try:
            cfg = json.loads(cfg_str)
            path          = cfg.get("path", "")
            phc0_f2       = float(cfg.get("phc0_f2",       0.0))
            phc1_f2       = float(cfg.get("phc1_f2",       0.0))
            phc0_f1       = float(cfg.get("phc0_f1",       0.0))
            phc1_f1       = float(cfg.get("phc1_f1",       0.0))
            baseline_order= int(cfg.get("baseline_order",  -1))
            calib_x       = float(cfg.get("calib_x",       0.0))
            calib_y       = float(cfg.get("calib_y",       0.0))
            contour_base  = float(cfg.get("contour_base",  8.0))
            ladder        = bool(cfg.get("ladder",        False))
            if cfg.get("ladder_k_min") is not None:
                ladder_k_min = int(cfg["ladder_k_min"])
            if cfg.get("ladder_k_max") is not None:
                ladder_k_max = int(cfg["ladder_k_max"])
        except Exception:
            path = cfg_str
    else:
        path = cfg_str

    # ── Dimensionality dispatch ──────────────────────────────────────────────
    # Identify the dataset before loading any of it, then send it down the
    # matching pipeline. Detection reads a few kilobytes of parameter text and
    # never opens the spectrum binary, so it costs a couple of milliseconds --
    # cheap enough to run on every request, worker or one-shot.
    import nmr_dimension as nmrdim
    info = nmrdim.detect(path)

    if info.get("status") != "success":
        return {
            "status": "error",
            "message": info.get("message",
                                "Could not identify the dataset at %s" % path),
        }

    if info.get("ndim") == 1:
        import nmr_1d
        cfg_1d = dict(cfg) if isinstance(cfg, dict) else {}
        cfg_1d["path"] = path
        return nmr_1d.generate(cfg_1d, info)

    if not info.get("supported"):
        return {
            "status": "error",
            "message": info.get("message", "Unsupported dataset: %dD %s"
                                % (info.get("ndim", 0), info.get("vendor") or "")),
            "dimension": info.get("ndim"),
            "vendor": info.get("vendor"),
        }

    # ── 2D path ──────────────────────────────────────────────────────────────
    prepared = _prepare_bruker(path, phc0_f2, phc1_f2, phc0_f1, phc1_f1,
                               baseline_order, calib_x, calib_y)
    if isinstance(prepared, dict) and prepared.get("status") == "error":
        return prepared
    data_matrix = prepared["data_matrix"]
    f2_ppm      = prepared["f2_ppm"]
    f1_ppm      = prepared["f1_ppm"]
    noise       = prepared["noise"]
    peak        = prepared["peak"]
    x_label     = prepared["x_label"]
    y_label     = prepared["y_label"]
    pulprog     = prepared.get("pulprog", "")
    solvent     = prepared.get("solvent", "")
    sfo1        = prepared.get("sfo1", 0.0)

    factor  = 1.4
    n_pos   = 14

    # ── Threshold domain, derived from the spectrum rather than hardcoded ─────
    #
    # `contour_base` is a multiplier on the noise: the drawn levels run
    # base*noise * factor**i for i in 0..n_pos-1, so the topmost level sits at
    # base * factor**(n_pos-1) * noise.
    #
    #   base_min  puts the lowest level below the noise floor, which draws
    #             everything including the noise (TopSpin's densest view).
    #   base_max  puts the *topmost* level just under the tallest peak, which
    #             leaves only the strongest signals on screen (the sparsest
    #             view). The old fixed ceiling of 200 stopped ~90x short of
    #             that on a typical spectrum, so the strong peaks could never
    #             be isolated - everything above 15874x noise stayed buried
    #             inside the top contour.
    base_min = 0.25
    base_max = max(base_min * factor, (peak / noise) / (factor ** (n_pos - 1)))

    contour_base = min(max(contour_base, base_min), base_max)

    # The ladder has to span every window the client can scroll to. A window
    # starts at rung k0 and covers n_pos rungs, so the top rung must reach
    # k0(base_max) + n_pos - 1. Rungs above the tallest peak cost nothing (they
    # produce no segments), so overshooting the top is free; the bottom rungs
    # are the expensive ones, which is why the ladder floor stays at the noise
    # itself and sub-noise thresholds are served by the exact recompute.
    log_factor = math.log(factor)
    if ladder_k_min is None:
        ladder_k_min = 0
    if ladder_k_max is None:
        ladder_k_max = int(round(math.log(base_max) / log_factor)) + n_pos - 1
    ladder_k_max = max(ladder_k_max, ladder_k_min + n_pos - 1)

    if ladder:
        # One geometric rung per level: noise * factor**k. The client picks any
        # 14 consecutive rungs as its display window, so changing the contour
        # threshold needs no round trip.
        k_values = list(range(ladder_k_min, ladder_k_max + 1))
        cl_pos = [noise * (factor ** k) for k in k_values]
    else:
        base    = noise * contour_base
        k_values = list(range(n_pos))
        cl_pos  = [base * (factor ** i) for i in range(n_pos)]

    cl_neg  = [-c for c in reversed(cl_pos)]

    # ── 2. Compute contours ──────────────────────────────────────────────────
    # contourpy is what matplotlib's ax.contour() calls underneath; driving it
    # directly skips building a Figure, the line collections, and the legacy
    # `allsegs` conversion, none of which are used here. Same 'mpl2014'
    # algorithm and corner_mask, so the vertices are bit-for-bit identical to
    # what ax.contour().allsegs produced - about 3.5x faster.
    gen = contourpy.contour_generator(
        f2_ppm, f1_ppm, data_matrix,
        name="mpl2014",
        corner_mask=True,
        line_type=contourpy.LineType.SeparateCode,
    )

    # ── 3. Serialise contour paths ────────────────────────────────────────────
    contours_out = []

    # Drop sub-12-point specks (single-pixel noise, not real features). Applied to
    # ladder and exact responses alike, so settling from a snapped rung onto the
    # exact threshold refines the picture instead of visibly changing its density.
    min_pts, fmt_pair = 12, "%.3f,%.3f "

    # Thin by arc length, never by point count. Keeping a fixed number of points per
    # segment turns a long winding ridge into straight chords right across the plot
    # (an 11k-point contour became a 1.8 ppm straight line); spacing samples by
    # distance bounds every chord to `tol` regardless of how long the contour is.
    # ~1.5 screen pixels across a full-width view: fine enough that the sampling is
    # invisible, coarse enough to stay smaller than the old fixed-count output.
    span = max(abs(f2_ppm[-1] - f2_ppm[0]), abs(f1_ppm[-1] - f1_ppm[0]))
    tol = span / 600.0 if span > 0 else 0.01

    def _decimate(seg):
        # searchsorted over a monotonically increasing arc length already yields
        # sorted picks, so a neighbour-inequality mask dedupes them without the
        # full sort np.unique would do.
        deltas = seg[1:] - seg[:-1]
        arc = np.empty(len(seg))
        arc[0] = 0.0
        np.cumsum(np.hypot(deltas[:, 0], deltas[:, 1]), out=arc[1:])
        total = arc[-1]
        if total <= 0:
            return seg[:1]
        picks = np.searchsorted(arc, np.arange(0.0, total, tol))
        last = len(seg) - 1
        if picks[-1] != last:
            picks = np.append(picks, last)
        picks = picks[np.concatenate(([True], picks[1:] != picks[:-1]))]
        return seg[picks]

    def _format(dec):
        # One C-level `%` over the whole segment instead of a Python-level format
        # call per point. Building the repeated template is a memcpy; the
        # formatting loop then runs entirely inside CPython's string machinery,
        # which is ~2.5x faster over the ~600k points a spectrum produces.
        return (fmt_pair * len(dec))[:-1] % tuple(dec.reshape(-1).tolist())

    def _serialise(levels, is_positive, k_for_index):
        for i, level_val in enumerate(levels):
            serialised = []
            for seg in gen.lines(level_val)[0]:
                if len(seg) < min_pts:
                    continue
                dec = _decimate(seg)
                if len(dec) < 2:
                    continue
                serialised.append(_format(dec))
            if serialised:
                contours_out.append({
                    "level":        float(level_val),
                    "is_positive":  is_positive,
                    "k":            k_for_index(i),
                    "segments_str": ";".join(serialised)
                })

    n_levels = len(k_values)
    _serialise(cl_pos, True,  lambda i: k_values[i])
    # cl_neg is cl_pos reversed and negated, so its index runs the ladder backwards.
    _serialise(cl_neg, False, lambda i: k_values[n_levels - 1 - i])


    return {
        "status":       "success",
        "dimension":    2,
        "vendor":       "bruker",
        "x_label":      x_label,
        "y_label":      y_label,
        "z_label":      "Intensity",
        "unique_atoms": [x_label, y_label, "Intensity"],
        "data":         [],
        # How far the client may take the threshold, for this spectrum.
        "contour_base":     contour_base,
        "contour_base_min": base_min,
        "contour_base_max": base_max,
        "ladder":       1 if ladder else 0,
        "ladder_k_min": k_values[0],
        "ladder_k_max": k_values[-1],
        "ladder_window": n_pos,
        "contours":     contours_out,
        "pulprog":      pulprog,
        "solvent":      solvent,
        "sfo1":         sfo1,
        "noise":        noise,
        "spectrum_size": int(data_matrix.size),
        "x_min":        round(float(np.min(f2_ppm)), 5),
        "x_max":        round(float(np.max(f2_ppm)), 5),
        "y_min":        round(float(np.min(f1_ppm)), 5),
        "y_max":        round(float(np.max(f1_ppm)), 5),
    }


# ─────────────────────────────────────────────────────────────────────────────
# MAIN ENTRY POINT
# ─────────────────────────────────────────────────────────────────────────────

def serve():
    """Long-lived worker: one JSON request per line in, one JSON response per line
    out. Keeps the interpreter, the imported stack and the processed matrix warm,
    so a contour-only request skips everything except the contouring itself."""
    # Warm the heavy imports once, before announcing readiness.
    import numpy            # noqa: F401
    import nmrglue          # noqa: F401
    import contourpy        # noqa: F401

    sys.stdout.write(json.dumps({"status": "ready"}) + "\n")
    sys.stdout.flush()

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
        except Exception as exc:
            sys.stdout.write(json.dumps({
                "status": "error", "message": f"Bad request JSON: {exc}"}) + "\n")
            sys.stdout.flush()
            continue

        if req.get("op") == "quit":
            return

        try:
            result = process_bruker(json.dumps(req))
        except Exception as exc:
            result = {
                "status": "error",
                "message": f"Exception: {exc}",
                "traceback": traceback.format_exc(),
            }

        sys.stdout.write(json.dumps(result) + "\n")
        sys.stdout.flush()


def main():
    if len(sys.argv) >= 2 and sys.argv[1].lower() == 'serve':
        serve()
        return

    if len(sys.argv) < 2:
        print(json.dumps({
            "status": "error",
            "message": "Usage: nmr_backend.py <file_type> [x_atom] [y_atom] [z_atom]"
        }))
        return

    file_type = sys.argv[1].lower()
    x_atom = sys.argv[2] if len(sys.argv) > 2 else "H"
    y_atom = sys.argv[3] if len(sys.argv) > 3 else "N"
    z_atom = sys.argv[4] if len(sys.argv) > 4 else "CA"

    try:
        file_content = sys.stdin.read()

        # ── Bruker branch ────────────────────────────────────────────────────
        if file_type in ('bruker', 'ser', '2rr'):
            result = process_bruker(file_content)
            print(json.dumps(result))
            return

        # ── Synthetic 1D branch ──────────────────────────────────────────────
        # Imported here rather than at module scope so that loading a real
        # dataset never pays for the simulator's import.
        elif file_type in ('sim1d', 'ssg'):
            import nmr_sim1d
            try:
                cfg = json.loads(file_content) if file_content.strip() else {}
            except ValueError:
                cfg = {}
            if not isinstance(cfg, dict):
                cfg = {}
            print(json.dumps(nmr_sim1d.simulate_envelope(cfg)))
            return

        # ── NMR-STAR branch ──────────────────────────────────────────────────
        elif file_type in ('str', 'nmrstar'):
            shifts = parse_nmr_star(file_content)

        # ── CSV branch ───────────────────────────────────────────────────────
        elif file_type == 'csv':
            shifts = parse_csv_shifts(file_content)

        else:
            print(json.dumps({
                "status":  "error",
                "message": f"Unknown file type: {file_type}"
            }))
            return

        if not shifts:
            print(json.dumps({
                "status":  "error",
                "message": "No chemical shift data could be parsed. Check file format."
            }))
            return

        unique_atoms = sorted(set(s['atom_name'] for s in shifts))
        paired = pair_atoms(shifts, x_atom, y_atom, z_atom)

        print(json.dumps({
            "status":       "success",
            "unique_atoms": unique_atoms,
            "data":         paired
        }))

    except Exception as e:
        print(json.dumps({
            "status":    "error",
            "message":   f"Exception: {str(e)}",
            "traceback": traceback.format_exc()
        }))


if __name__ == '__main__':
    main()