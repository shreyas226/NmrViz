#!/usr/bin/env python3
"""
nmr_dimension.py
================
Bruker dataset dimensionality detection.

Given any path that points somewhere inside (or at) an NMR dataset, work out
whether it holds 1D, 2D or 3D data, and hand back the resolved processed-data
directory so the caller can go straight to plotting.

Bruker is the format the plotting pipeline actually consumes, so it gets the
full evidence ladder below.  Varian/Agilent and JCAMP-DX datasets are still
recognised and their dimensionality reported -- that way the front end can say
"this is a 1D Varian dataset, which I can't plot yet" instead of the far less
useful "not a Bruker directory".

Design goal: detection must be *cheap*.  Nothing in here opens a spectrum
binary -- it reads a few kilobytes of JCAMP-DX parameter text and calls
os.stat() on the binaries.  A full detect() on a 4 MB 2D dataset costs a
couple of milliseconds, so the front end can call it on every selection.

Detection ladder (first three are the ones that normally decide it):

  A  ##$PPARMOD in procs   0=1D 1=2D 2=3D   -- dimensionality of PROCESSED data
  B  ##$PARMODE in acqus   0=1D 1=2D 2=3D   -- dimensionality of ACQUIRED data
  C  processed binary names, ^[1-9][ri]+$   -- 1r/1i, 2rr/2ri/2ir/2ii, 3rrr...
  D  presence of acqu2s/proc2s (>=2D), acqu3s/proc3s (3D)
  E  raw data file: 'fid' => 1D, 'ser' => nD
  F  byte-size vs ##$SI arithmetic  -- validation only, never a vote
  G  PULPROG name  -- last-resort tiebreak, always logged as low confidence

Usage:
    from nmr_dimension import detect
    info = detect("/path/to/dataset")     # -> dict

    $ python nmr_dimension.py /path/to/dataset      # -> JSON on stdout
"""

import json
import os
import re
import sys

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

# A Bruker processed-data binary: leading digit is the DIMENSIONALITY (not an
# index), followed by exactly that many r/i characters.  '1r', '2rr', '3rri'.
# Note there is no such thing as '2r' for a second 1D spectrum -- that lives in
# a separate expno directory and is still called '1r'.
_BIN_RE = re.compile(r"^([1-9])([ri]+)$")

# Processed binaries we prefer to plot, per dimensionality.
REAL_FILE = {1: "1r", 2: "2rr", 3: "3rrr"}

# All quadrants, per dimensionality (used for phase correction).
QUADRANTS = {
    1: ["1r", "1i"],
    2: ["2rr", "2ri", "2ir", "2ii"],
    3: ["3rrr", "3rri", "3rir", "3irr"],
}

# Pulse programs that produce a structurally-2D dataset which is really a
# stack of 1D spectra (DOSY, relaxation series, kinetics, arrayed VT...).
_PSEUDO_2D_RE = re.compile(
    r"(dosy|ledbp|stebp|ste\b|bpp|t1ir|inv(rec)?|cpmg|relax|kinet|vtemp|arrayed|satrec)",
    re.IGNORECASE,
)

# Weights for the ladder.  Higher = more trusted.
_WEIGHT = {
    "binaries": 5.0,
    "pparmod": 4.0,
    "parmode": 3.0,
    "param_files": 2.0,
    "raw_file": 1.0,
    "pulprog": 0.5,
}

_MAX_WALK_DEPTH = 6

# Vendor marker files.  Order matters: a directory holding both acqus and a
# stray procpar is a Bruker dataset.
_JCAMP_EXTS = (".jdx", ".dx", ".jcm", ".jcamp")
_BRUKER_MARKERS = ("acqus", "acqu", "procs", "proc", "ser", "fid", "pulseprogram")


# ---------------------------------------------------------------------------
# Tiny JCAMP-DX reader
# ---------------------------------------------------------------------------

def read_params(path, keys):
    """
    Pull ``##$KEY= value`` entries out of a Bruker parameter file.

    Only the requested keys are kept and the scan stops as soon as they have
    all been seen, so this stays cheap even on a long acqus.
    Returns {key: raw_string}; missing keys are simply absent.
    """
    out = {}
    if not path or not os.path.isfile(path):
        return out
    wanted = set(keys)
    try:
        with open(path, "r", encoding="latin-1", errors="ignore") as fh:
            for line in fh:
                if not line.startswith("##$"):
                    continue
                head, sep, tail = line.partition("=")
                if not sep:
                    continue
                key = head[3:].strip()
                if key in wanted:
                    out[key] = tail.strip()
                    wanted.discard(key)
                    if not wanted:
                        break
    except OSError:
        pass
    return out


def _param_int(params, key):
    try:
        return int(float(params[key]))
    except (KeyError, TypeError, ValueError):
        return None


def _param_str(params, key):
    raw = params.get(key)
    if raw is None:
        return ""
    return raw.replace("<", "").replace(">", "").strip()


# ---------------------------------------------------------------------------
# Path resolution
# ---------------------------------------------------------------------------

def _binaries_in(directory):
    """Return {filename: ndim} for every Bruker processed binary in a dir."""
    found = {}
    try:
        entries = os.listdir(directory)
    except OSError:
        return found
    for name in entries:
        m = _BIN_RE.match(name)
        if not m:
            continue
        digit, letters = int(m.group(1)), m.group(2)
        # The digit must equal the number of r/i characters, otherwise it is
        # some other file that merely looks like one ('2ri' yes, '2rri' no).
        if digit != len(letters):
            continue
        if os.path.isfile(os.path.join(directory, name)):
            found[name] = digit
    return found


def _is_pdata_dir(directory):
    """A processed-data directory has procs plus at least one real binary."""
    if not os.path.isdir(directory):
        return False
    if not os.path.isfile(os.path.join(directory, "procs")):
        return False
    return any(letters == "r" * ndim
               for letters, ndim in
               ((n[1:], d) for n, d in _binaries_in(directory).items()))


def _sorted_procnos(pdata_root):
    """pdata subdirectories, numeric ones first and in numeric order."""
    try:
        subs = os.listdir(pdata_root)
    except OSError:
        return []
    def key(name):
        return (0, int(name)) if name.isdigit() else (1, 0)
    return [os.path.join(pdata_root, s) for s in sorted(subs, key=key)
            if os.path.isdir(os.path.join(pdata_root, s))]


def resolve_pdata_dir(start_path):
    """
    Normalise any user-supplied path to a processed-data directory.

    Accepts a binary file, a procs/acqus file, a pdata/<n> dir, an expno dir,
    a dataset dir holding several expnos, or a parent of any of those.
    Returns the directory, or None.
    """
    if not start_path or not os.path.exists(start_path):
        return None

    if os.path.isfile(start_path):
        start_path = os.path.dirname(start_path)

    # 1. The directory itself.
    if _is_pdata_dir(start_path):
        return start_path

    # 2/3. pdata/<n>, lowest procno first.
    pdata_root = os.path.join(start_path, "pdata")
    for candidate in _sorted_procnos(pdata_root):
        if _is_pdata_dir(candidate):
            return candidate

    # 4. Bounded walk downward (dataset dir holding expnos, unpacked archive).
    base_depth = start_path.rstrip(os.sep).count(os.sep)
    for root, dirs, _files in os.walk(start_path):
        dirs[:] = sorted(d for d in dirs if not d.startswith("."))
        if _is_pdata_dir(root):
            return root
        if root.rstrip(os.sep).count(os.sep) - base_depth >= _MAX_WALK_DEPTH:
            dirs[:] = []

    # 5. Walk up, for a path that sits beside or below the real location.
    curr = start_path
    for _ in range(3):
        parent = os.path.dirname(curr)
        if parent == curr:
            break
        if _is_pdata_dir(parent):
            return parent
        for candidate in _sorted_procnos(os.path.join(parent, "pdata")):
            if _is_pdata_dir(candidate):
                return candidate
        curr = parent

    return None


def _find_exp_dir_downward(start_path):
    """
    Bounded walk looking for an experiment directory (one holding acqus).

    Needed for raw-only datasets -- plenty of archives ship acqus/acqu2s and
    fid/ser with no pdata at all, so there is no processed directory to anchor
    on.  Shallowest match wins; among siblings, the lowest expno.
    """
    if not start_path or not os.path.isdir(start_path):
        return None
    if os.path.isfile(os.path.join(start_path, "acqus")):
        return start_path
    base_depth = start_path.rstrip(os.sep).count(os.sep)
    for root, dirs, files in os.walk(start_path):
        def key(name):
            return (0, int(name)) if name.isdigit() else (1, name)
        dirs[:] = sorted((d for d in dirs if not d.startswith(".")), key=key)
        if "acqus" in files:
            return root
        if root.rstrip(os.sep).count(os.sep) - base_depth >= _MAX_WALK_DEPTH:
            dirs[:] = []
    return None


def resolve_exp_dir(pdata_dir, fallback=None):
    """
    The experiment (expno) directory owning a pdata dir -- i.e. the one with
    acqus in it.  Normally <exp>/pdata/<procno>, but tolerate odd layouts.
    """
    if pdata_dir:
        candidate = os.path.dirname(os.path.dirname(pdata_dir))
        if os.path.isfile(os.path.join(candidate, "acqus")):
            return candidate
        curr = pdata_dir
        for _ in range(4):
            parent = os.path.dirname(curr)
            if parent == curr:
                break
            if os.path.isfile(os.path.join(parent, "acqus")):
                return parent
            curr = parent
    if fallback:
        if os.path.isfile(fallback):
            fallback = os.path.dirname(fallback)
        if os.path.isfile(os.path.join(fallback, "acqus")):
            return fallback
        # Raw-only dataset: no pdata to anchor on, so go looking for acqus.
        found = _find_exp_dir_downward(fallback)
        if found:
            return found
        curr = fallback
        for _ in range(3):
            parent = os.path.dirname(curr)
            if parent == curr:
                break
            if os.path.isfile(os.path.join(parent, "acqus")):
                return parent
            curr = parent
    return None


# ---------------------------------------------------------------------------
# Other vendors (recognition only -- plotting is Bruker-only for now)
# ---------------------------------------------------------------------------

def read_procpar(path, keys):
    """
    Pull parameters out of a Varian/Agilent procpar.

    Each entry is two lines: a header line starting with the parameter name,
    then a value line whose first token is the value count.  Returns
    {key: first_value_as_string}.
    """
    out = {}
    if not path or not os.path.isfile(path):
        return out
    wanted = set(keys)
    try:
        with open(path, "r", encoding="latin-1", errors="ignore") as fh:
            pending = None
            for line in fh:
                if pending is not None:
                    parts = line.split(None, 1)
                    value = parts[1].strip() if len(parts) > 1 else ""
                    out[pending] = value.strip('"').strip()
                    wanted.discard(pending)
                    pending = None
                    if not wanted:
                        break
                    continue
                name = line.split(None, 1)[0] if line.strip() else ""
                if name in wanted:
                    pending = name
    except OSError:
        pass
    return out


def read_jcamp_header(path, limit_bytes=262144):
    """
    Read the ``##KEY= value`` labels from the head of a JCAMP-DX file.

    Only the first chunk is scanned -- the data records that follow are large
    and we never need them here.  Keys are upper-cased with spaces stripped,
    so '##DATA TYPE=' arrives as 'DATATYPE'.  Later blocks do not overwrite
    earlier ones except for keys we care about being absent so far.
    """
    out = {}
    if not path or not os.path.isfile(path):
        return out
    try:
        with open(path, "r", encoding="latin-1", errors="ignore") as fh:
            chunk = fh.read(limit_bytes)
    except OSError:
        return out
    for line in chunk.splitlines():
        if not line.startswith("##"):
            continue
        head, sep, tail = line.partition("=")
        if not sep:
            continue
        key = head[2:].replace(" ", "").replace("$", "").upper()
        value = tail.split("$$")[0].strip()
        if key and (key not in out or not out[key]):
            out[key] = value
    return out


def _find_by(start_path, predicate, want_dir=False):
    """Bounded walk returning the first path (or its dir) matching predicate."""
    if os.path.isfile(start_path):
        if predicate(os.path.basename(start_path)):
            return os.path.dirname(start_path) if want_dir else start_path
        start_path = os.path.dirname(start_path)
    if not os.path.isdir(start_path):
        return None
    base_depth = start_path.rstrip(os.sep).count(os.sep)
    for root, dirs, files in os.walk(start_path):
        dirs[:] = sorted(d for d in dirs if not d.startswith("."))
        for name in sorted(files):
            if predicate(name):
                return root if want_dir else os.path.join(root, name)
        if root.rstrip(os.sep).count(os.sep) - base_depth >= _MAX_WALK_DEPTH:
            dirs[:] = []
    return None


def sniff_vendor(path):
    """
    Identify the dataset format at `path`.

    Returns (vendor, anchor) where vendor is 'bruker' | 'varian' | 'jcampdx' |
    None, and anchor is the directory (Bruker/Varian) or file (JCAMP-DX) that
    the matching detector should start from.
    """
    if not path or not os.path.exists(path):
        return None, None

    # A file the user picked directly is the strongest hint.
    if os.path.isfile(path):
        name = os.path.basename(path)
        lower = name.lower()
        if lower.endswith(_JCAMP_EXTS):
            return "jcampdx", path
        if name == "procpar":
            return "varian", os.path.dirname(path)
        if name in _BRUKER_MARKERS or _BIN_RE.match(name):
            return "bruker", os.path.dirname(path)

    # Bruker wins ties -- it is the format with real plotting support.
    if _find_by(path, lambda n: n in ("acqus", "procs"), want_dir=True):
        return "bruker", path
    varian_dir = _find_by(path, lambda n: n == "procpar", want_dir=True)
    if varian_dir:
        return "varian", varian_dir
    jcamp_file = _find_by(path, lambda n: n.lower().endswith(_JCAMP_EXTS))
    if jcamp_file:
        return "jcampdx", jcamp_file
    return None, None


def _detect_varian(path, anchor, result):
    """
    Varian/Agilent: dimensionality comes from 'ni' (indirect increments) and
    'ni2', cross-checked against 'procdim' and 'apptype'.
    """
    procpar = os.path.join(anchor, "procpar")
    params = read_procpar(procpar, ["ni", "ni2", "procdim", "apptype",
                                    "seqfil", "array", "tn", "dn", "solvent"])

    def as_int(key):
        try:
            return int(float(params[key]))
        except (KeyError, TypeError, ValueError):
            return None

    ni, ni2, procdim = as_int("ni"), as_int("ni2"), as_int("procdim")
    apptype = params.get("apptype", "")

    ndim = 1
    evidence = result["evidence"]
    if ni and ni > 1:
        ndim = 2
        evidence.append({"source": "ni", "ndim": 2, "weight": 5.0,
                         "detail": "ni=%d indirect increments" % ni})
    else:
        evidence.append({"source": "ni", "ndim": 1, "weight": 5.0,
                         "detail": "ni absent or 1 -- no indirect dimension"})
    if ni2 and ni2 > 1:
        ndim = 3
        evidence.append({"source": "ni2", "ndim": 3, "weight": 5.0,
                         "detail": "ni2=%d" % ni2})
    if procdim:
        evidence.append({"source": "procdim", "ndim": procdim, "weight": 4.0,
                         "detail": "procdim=%d" % procdim})
        if procdim != ndim:
            result["warnings"].append(
                "procdim=%d disagrees with ni-derived %dD; using %dD."
                % (procdim, ndim, ndim))
    if apptype:
        evidence.append({"source": "apptype", "ndim": ndim, "weight": 0.5,
                         "detail": "apptype='%s'" % apptype})

    result.update({
        "status": "success",
        "ndim": ndim,
        "confidence": "high" if (ni is not None or procdim is not None) else "low",
        "supported": False,
        "exp_dir": anchor,
        "raw_file": "fid" if os.path.exists(os.path.join(anchor, "fid")) else None,
        "pulprog": params.get("seqfil", ""),
        "solvent": params.get("solvent", ""),
        "nuc1": params.get("tn", ""),
        "nuc2": params.get("dn", "") if ndim > 1 else "",
        "pseudo_2d": bool(params.get("array")) and ndim == 2,
        "exp_name": os.path.basename(os.path.dirname(anchor)),
        "exp_no": os.path.basename(anchor),
        "message": ("%dD Varian/Agilent dataset (seqfil '%s'). Dimensionality "
                    "detected, but plotting currently supports Bruker only."
                    % (ndim, params.get("seqfil", "?"))),
    })
    return result


def _detect_jcampdx(path, anchor, result):
    """
    JCAMP-DX: ##NUMDIM= states the dimensionality outright; ##DATA TYPE= and
    ##DATA CLASS= back it up ('nD NMR SPECTRUM' + NTUPLES for 2D).
    """
    hdr = read_jcamp_header(anchor)
    evidence = result["evidence"]

    ndim = None
    numdim = hdr.get("NUMDIM")
    if numdim:
        try:
            ndim = int(float(numdim))
            evidence.append({"source": "numdim", "ndim": ndim, "weight": 5.0,
                             "detail": "##NUMDIM= %s" % numdim})
        except ValueError:
            ndim = None

    datatype = hdr.get("DATATYPE", "")
    if ndim is None and datatype:
        ndim = 2 if re.search(r"\bnD\b", datatype) else 1
        evidence.append({"source": "datatype", "ndim": ndim, "weight": 2.0,
                         "detail": "##DATA TYPE= %s" % datatype})

    if ndim is None:
        result["message"] = ("JCAMP-DX file at %s carries no ##NUMDIM or "
                             "##DATA TYPE label; dimensionality unknown."
                             % anchor)
        return result

    result.update({
        "status": "success",
        "ndim": ndim,
        "confidence": "high" if numdim else "medium",
        "supported": False,
        "exp_dir": os.path.dirname(anchor),
        "jcamp_file": anchor,
        "pulprog": hdr.get(".PULSESEQUENCE", ""),
        "solvent": hdr.get(".SOLVENTNAME", ""),
        "nuc1": hdr.get(".OBSERVENUCLEUS", ""),
        "exp_name": os.path.basename(os.path.dirname(anchor)),
        "message": ("%dD JCAMP-DX spectrum (%s). Dimensionality detected, but "
                    "plotting currently supports Bruker only."
                    % (ndim, datatype or "NMR")),
    })
    return result


# ---------------------------------------------------------------------------
# Detection
# ---------------------------------------------------------------------------

def _vote(votes, evidence, source, ndim, detail):
    """Record one signal's opinion about the dimensionality."""
    if ndim is None:
        return
    weight = _WEIGHT[source]
    votes[ndim] = votes.get(ndim, 0.0) + weight
    evidence.append({
        "source": source,
        "ndim": ndim,
        "weight": weight,
        "detail": detail,
    })


def _size_check(pdata_dir, ndim, procs_path):
    """
    Cross-check the real binary's byte size against the processed sizes.

    size(binary) must equal 4 * product(SI over all dimensions), since Bruker
    processed data is 32-bit.  Returns (ok, message).  ok is None when the
    check could not be performed.
    """
    real_name = REAL_FILE.get(ndim)
    if not real_name:
        return None, "no reference binary for %dD" % ndim
    real_path = os.path.join(pdata_dir, real_name)
    if not os.path.isfile(real_path):
        return None, "%s not present" % real_name

    sizes = []
    for dim in range(1, ndim + 1):
        proc_file = procs_path if dim == 1 else os.path.join(pdata_dir, "proc%ds" % dim)
        si = _param_int(read_params(proc_file, ["SI"]), "SI")
        if not si:
            return None, "SI missing for dimension %d" % dim
        sizes.append(si)

    # DTYPP 2 means double precision (8 bytes) rather than the usual int32.
    dtypp = _param_int(read_params(procs_path, ["DTYPP"]), "DTYPP") or 0
    word = 8 if dtypp == 2 else 4

    expected = word
    for si in sizes:
        expected *= si
    actual = os.path.getsize(real_path)
    if actual == expected:
        return True, "%s is %d bytes = %d x %s" % (
            real_name, actual, word, " x ".join(str(s) for s in sizes))
    return False, "%s is %d bytes, expected %d for SI %s" % (
        real_name, actual, expected, "x".join(str(s) for s in sizes))


def detect(path):
    """
    Work out the dimensionality of the NMR dataset at (or under) `path`.

    Sniffs the vendor first, then hands off to the matching detector.  Returns
    a dict -- see the module docstring.  Never raises for a bad path; it
    reports status "error" instead.
    """
    result = {
        "status": "error",
        "input_path": path,
        "vendor": None,
        "ndim": 0,
        "confidence": "none",
        "supported": False,
        "evidence": [],
        "warnings": [],
    }

    if not path or not os.path.exists(path):
        result["message"] = "Path does not exist: %s" % path
        return result

    vendor, anchor = sniff_vendor(path)
    result["vendor"] = vendor

    if vendor == "varian":
        return _detect_varian(path, anchor, result)
    if vendor == "jcampdx":
        return _detect_jcampdx(path, anchor, result)
    if vendor != "bruker":
        # Almost every dataset in the wild arrives zipped; say so rather than
        # leaving the user staring at a generic "not recognised".
        zips = []
        if os.path.isdir(path):
            try:
                zips = [f for f in os.listdir(path) if f.lower().endswith(".zip")]
            except OSError:
                pass
        if zips:
            result["message"] = (
                "%s holds %d zip archive(s) and no unpacked dataset. Extract "
                "one and select the extracted folder." % (path, len(zips)))
        else:
            result["message"] = (
                "Nothing recognisable at %s -- expected a Bruker dataset "
                "(acqus / procs / 1r / 2rr), a Varian procpar, or a JCAMP-DX "
                "file." % path)
        return result

    return _detect_bruker(path, result)


def _detect_bruker(path, result):
    """
    Full evidence ladder for a Bruker dataset.  See the module docstring.
    """
    pdata_dir = resolve_pdata_dir(path)
    exp_dir = resolve_exp_dir(pdata_dir, fallback=path)

    if pdata_dir is None and exp_dir is None:
        result["message"] = (
            "No Bruker data found at %s -- expected a processed-data directory "
            "(procs plus 1r/2rr/3rrr) or an experiment directory with acqus."
            % path
        )
        return result

    votes = {}
    evidence = result["evidence"]
    warnings = result["warnings"]

    # --- C: processed binaries -------------------------------------------
    binaries = _binaries_in(pdata_dir) if pdata_dir else {}
    if binaries:
        # If a directory somehow holds more than one dimensionality, trust the
        # one with a full real file (1r / 2rr / 3rrr) and the largest size.
        by_dim = {}
        for name, ndim in binaries.items():
            by_dim.setdefault(ndim, []).append(name)
        if len(by_dim) > 1:
            warnings.append(
                "Directory holds binaries of mixed dimensionality: %s"
                % ", ".join(sorted(binaries)))
        best = max(by_dim,
                   key=lambda d: (REAL_FILE.get(d) in by_dim[d], len(by_dim[d])))
        _vote(votes, evidence, "binaries", best,
              "found %s" % ", ".join(sorted(binaries)))

    # --- A: PPARMOD (processed) ------------------------------------------
    procs_path = os.path.join(pdata_dir, "procs") if pdata_dir else None
    procs = read_params(procs_path, ["PPARMOD", "SI", "DTYPP", "BYTORDP", "NC_proc"])
    pparmod = _param_int(procs, "PPARMOD")
    if pparmod is not None:
        _vote(votes, evidence, "pparmod", pparmod + 1, "##$PPARMOD= %d" % pparmod)

    # --- B: PARMODE (acquired) -------------------------------------------
    acqus_path = os.path.join(exp_dir, "acqus") if exp_dir else None
    acqus = read_params(acqus_path, ["PARMODE", "PULPROG", "NUC1", "SOLVENT",
                                     "SFO1", "DATE", "TD", "AQ_mod"])
    parmode = _param_int(acqus, "PARMODE")
    if parmode is not None:
        _vote(votes, evidence, "parmode", parmode + 1, "##$PARMODE= %d" % parmode)

    # --- D: indirect-dimension parameter files ---------------------------
    param_ndim = 1
    present = []
    for dim in (3, 2):
        acq_n = os.path.join(exp_dir, "acqu%ds" % dim) if exp_dir else ""
        proc_n = os.path.join(pdata_dir, "proc%ds" % dim) if pdata_dir else ""
        if os.path.isfile(acq_n) or os.path.isfile(proc_n):
            present.append("acqu%ds/proc%ds" % (dim, dim))
            param_ndim = max(param_ndim, dim)
    _vote(votes, evidence, "param_files", param_ndim,
          ("present: %s" % ", ".join(present)) if present
          else "no acqu2s/proc2s -- direct dimension only")

    # --- E: raw data file -------------------------------------------------
    raw_file = None
    if exp_dir:
        if os.path.isfile(os.path.join(exp_dir, "ser")):
            raw_file = "ser"
            # 'ser' only tells us "more than 1D"; let the params say how many.
            _vote(votes, evidence, "raw_file", max(2, param_ndim),
                  "raw file is 'ser' (multi-dimensional)")
        elif os.path.isfile(os.path.join(exp_dir, "fid")):
            raw_file = "fid"
            _vote(votes, evidence, "raw_file", 1, "raw file is 'fid' (1D)")

    # --- G: pulse program (tiebreak only) ---------------------------------
    pulprog = _param_str(acqus, "PULPROG")
    if pulprog and not votes:
        guess = 2 if re.search(r"(cosy|tocsy|noesy|roesy|hsqc|hmbc|hmqc|hetcor|jres|mlev|dipsi)",
                               pulprog, re.IGNORECASE) else 1
        _vote(votes, evidence, "pulprog", guess,
              "PULPROG '%s' looks %dD" % (pulprog, guess))

    if not votes:
        result["message"] = (
            "Found a Bruker-looking folder at %s but no parameter or data file "
            "that reveals its dimensionality." % path
        )
        return result

    # --- Decide ------------------------------------------------------------
    ndim = max(votes, key=lambda d: (votes[d], -d))
    agreeing = sum(w for d, w in votes.items() if d == ndim)
    dissenting = sum(w for d, w in votes.items() if d != ndim)

    # --- F: size arithmetic (validation, not a vote) -----------------------
    size_ok, size_msg = (None, "not checked")
    if pdata_dir:
        size_ok, size_msg = _size_check(pdata_dir, ndim, procs_path)
        if size_ok is False:
            warnings.append("Size cross-check failed: " + size_msg)

    strong = {e["source"] for e in evidence
              if e["ndim"] == ndim and e["source"] in ("binaries", "pparmod", "parmode")}
    if dissenting == 0 and len(strong) >= 2 and size_ok is not False:
        confidence = "high"
    elif dissenting < agreeing and strong:
        confidence = "medium"
    else:
        confidence = "low"
    if dissenting:
        warnings.append(
            "Signals disagree (%s); going with %dD."
            % (", ".join("%dD=%.1f" % (d, w) for d, w in sorted(votes.items())), ndim))

    # --- Pseudo-2D flag ----------------------------------------------------
    pseudo_2d = False
    if ndim == 2 and pulprog and _PSEUDO_2D_RE.search(pulprog):
        pseudo_2d = True
        warnings.append(
            "PULPROG '%s' looks like a pseudo-2D series (arrayed 1D); it is "
            "stored as 2D and will be treated as 2D." % pulprog)

    # --- Metadata the caller would otherwise re-read ------------------------
    acqu2s = read_params(os.path.join(exp_dir, "acqu2s"), ["NUC1", "TD", "FnMODE"]) \
        if exp_dir else {}

    proc_no = os.path.basename(pdata_dir) if pdata_dir else ""
    exp_no = os.path.basename(exp_dir) if exp_dir else ""
    exp_name = os.path.basename(os.path.dirname(exp_dir)) if exp_dir else ""

    result.update({
        "status": "success",
        "ndim": ndim,
        "confidence": confidence,
        "supported": ndim in (1, 2),
        "pdata_dir": pdata_dir,
        "exp_dir": exp_dir,
        "real_file": REAL_FILE.get(ndim),
        "quadrants": [q for q in QUADRANTS.get(ndim, [])
                      if pdata_dir and os.path.isfile(os.path.join(pdata_dir, q))],
        "binaries": sorted(binaries),
        "raw_file": raw_file,
        "pseudo_2d": pseudo_2d,
        "pulprog": pulprog,
        "solvent": _param_str(acqus, "SOLVENT"),
        "nuc1": _param_str(acqus, "NUC1"),
        "nuc2": _param_str(acqu2s, "NUC1"),
        "size_check": size_ok,
        "size_check_detail": size_msg,
        "exp_name": exp_name,
        "exp_no": exp_no,
        "proc_no": proc_no,
    })

    if not result["supported"]:
        result["message"] = (
            "%dD data detected (%s). Only 1D and 2D can be plotted."
            % (ndim, ", ".join(sorted(binaries)) or "from parameters")
        )

    return result


def describe(info):
    """One-line human summary of a detect() result, for logs."""
    if info.get("status") != "success":
        return info.get("message", "detection failed")
    bits = ["%dD" % info["ndim"], info.get("vendor") or "unknown vendor",
            "confidence=%s" % info["confidence"]]
    if info.get("pulprog"):
        bits.append("pulprog=%s" % info["pulprog"])
    if info.get("pseudo_2d"):
        bits.append("pseudo-2D")
    return " | ".join(bits)


def main():
    if len(sys.argv) < 2:
        print(json.dumps({"status": "error",
                          "message": "Usage: nmr_dimension.py <path>"}))
        return 1
    info = detect(sys.argv[1])
    print(json.dumps(info, indent=2))
    return 0 if info.get("status") == "success" else 1


if __name__ == "__main__":
    sys.exit(main())
