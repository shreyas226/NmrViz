#!/usr/bin/env python3
import sys
import json
import re
import csv
import traceback

def parse_nmr_star(file_content):
    """
    Parses NMR-STAR file content to extract chemical shifts.
    Looks for the loop containing chemical shifts (e.g. _Atom_chem_shift or _Atom_shift_assign).
    """
    lines = file_content.splitlines()
    
    in_loop = False
    loop_headers = []
    shifts = []
    
    # We want to identify the chemical shifts loop.
    chem_shift_loop_headers = [
        '_atom_chem_shift',
        '_atom_shift_assign',
        '_chem_shift'
    ]
    
    is_chem_shift_loop = False
    
    i = 0
    while i < len(lines):
        line = lines[i].strip()
        if not line or line.startswith('#'):
            i += 1
            continue
            
        if line == 'loop_':
            # Start of a loop
            in_loop = True
            loop_headers = []
            is_chem_shift_loop = False
            i += 1
            continue
            
        if in_loop:
            if line.startswith('_'):
                # It's a header tag
                loop_headers.append(line)
                # Check if it looks like a chemical shift loop header
                lower_line = line.lower()
                if any(h in lower_line for h in chem_shift_loop_headers) or 'chem_shift_value' in lower_line or 'atom_shift' in lower_line:
                    is_chem_shift_loop = True
                i += 1
            else:
                # We reached data rows of the loop
                # Let's read all data rows for this loop
                loop_data_rows = []
                while i < len(lines):
                    data_line = lines[i].strip()
                    if not data_line:
                        i += 1
                        continue
                    if data_line.startswith('#'):
                        i += 1
                        continue
                    if data_line == 'stop_' or data_line == 'loop_' or data_line.startswith('save_') or (data_line.startswith('_') and not data_line.startswith('_Atom_chem_shift') and not data_line.startswith('_Atom_shift_assign')):
                        # Loop ended
                        break
                        
                    # Split by whitespace, respecting quotes
                    tokens = re.findall(r'"[^"]*"|\'[^\']*\'|\S+', data_line)
                    if len(tokens) == len(loop_headers):
                        loop_data_rows.append(tokens)
                    i += 1
                
                # If this was the chemical shift loop, process the data!
                if is_chem_shift_loop and loop_headers:
                    shifts.extend(process_loop_data(loop_headers, loop_data_rows))
                    in_loop = False
                    is_chem_shift_loop = False
                else:
                    # Not the loop we want, reset
                    in_loop = False
                    is_chem_shift_loop = False
        else:
            i += 1
            
    return shifts

def process_loop_data(headers, rows):
    """
    Maps chemical shift loop headers to columns and extracts data.
    """
    mapped_shifts = []
    clean_headers = [h.strip().lower() for h in headers]
    
    # Find column indices with robust matching rules
    seq_idx = -1
    res_label_idx = -1
    atom_name_idx = -1
    atom_type_idx = -1
    val_idx = -1
    
    for idx, h in enumerate(clean_headers):
        # Match Seq ID (avoid author or entity seq id)
        if ('seq_id' in h or 'seq_code' in h or 'residue_seq_code' in h) and not any(x in h for x in ['author', 'entity', 'db_']):
            if seq_idx == -1 or h.endswith('seq_id') or h.endswith('seq_code'):
                seq_idx = idx
                
        # Match Residue label
        if 'comp_id' in h or 'residue_label' in h or 'comp_name' in h or 'res_name' in h:
            if res_label_idx == -1 or h.endswith('comp_id') or h.endswith('residue_label'):
                res_label_idx = idx
                
        # Match Atom Name
        if ('atom_id' in h or 'atom_name' in h) and 'type' not in h:
            if atom_name_idx == -1 or h.endswith('atom_id') or h.endswith('atom_name'):
                atom_name_idx = idx
                
        # Match Atom Type
        if 'atom_type' in h:
            atom_type_idx = idx
            
        # Match Shift Value (avoid error, ambiguity, deviation columns)
        if ('val' in h or 'value' in h) and not any(x in h for x in ['error', 'ambiguity', 'dev', 'fit', 'method', 'flag', 'status']):
            if val_idx == -1 or h.endswith('.val') or h == '_chem_shift_value':
                val_idx = idx

    # If we don't have basic fields, we can't parse it
    if val_idx == -1 or atom_name_idx == -1:
        return []

    for row in rows:
        try:
            val_str = row[val_idx].strip()
            if val_str == '.':  # Missing value
                continue
                
            val = float(val_str)
            atom_name = row[atom_name_idx].strip().replace('"', '').replace("'", "")
            
            seq_id = row[seq_idx].strip() if seq_idx != -1 else "1"
            res_label = row[res_label_idx].strip().replace('"', '').replace("'", "") if res_label_idx != -1 else "UNK"
            
            # Infer atom type if not explicitly available
            if atom_type_idx != -1:
                atom_type = row[atom_type_idx].strip().replace('"', '').replace("'", "")
            else:
                # Infer from atom name: e.g. HN -> H, CA -> C, N -> N
                if atom_name.startswith('H'):
                    atom_type = 'H'
                elif atom_name.startswith('C'):
                    atom_type = 'C'
                elif atom_name.startswith('N'):
                    atom_type = 'N'
                else:
                    atom_type = 'O'

            mapped_shifts.append({
                'seq_id': seq_id,
                'residue': res_label,
                'atom_name': atom_name,
                'atom_type': atom_type,
                'val': val
            })
        except ValueError:
            # Skip rows where shift value is not a float
            continue
            
    return mapped_shifts

def parse_csv_shifts(file_content):
    """
    Parses chemical shifts from a CSV file.
    """
    shifts = []
    lines = file_content.splitlines()
    reader = csv.reader(lines)
    
    rows = list(reader)
    if not rows:
        return []
        
    headers = [h.strip().lower() for h in rows[0]]
    
    # Try to map columns by header names (using exact matches where possible)
    seq_idx = -1
    res_label_idx = -1
    atom_name_idx = -1
    val_idx = -1
    
    for idx, h in enumerate(headers):
        if h in ['seq', 'seq_id', 'seq_num', 'res_num', 'residue_num', 'num', 'id']:
            seq_idx = idx
        elif any(x in h for x in ['residue', 'res_name', 'comp', 'comp_id']) and h not in ['seq', 'seq_id', 'seq_num']:
            res_label_idx = idx
        elif h in ['atom', 'atom_name', 'atom_id', 'name']:
            atom_name_idx = idx
        elif h in ['shift', 'val', 'value', 'ppm']:
            val_idx = idx

    # Fallbacks if headers are not descriptive
    start_row = 1
    if val_idx == -1 or atom_name_idx == -1:
        # Check if first row looks like data (if Column 3 is a float)
        try:
            float(rows[0][3])
            seq_idx = 0
            res_label_idx = 1
            atom_name_idx = 2
            val_idx = 3
            start_row = 0
        except (IndexError, ValueError):
            try:
                float(rows[0][2])
                seq_idx = 0
                res_label_idx = -1
                atom_name_idx = 1
                val_idx = 2
                start_row = 0
            except (IndexError, ValueError):
                return [] # Cannot parse

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
            
            # Infer atom type
            if atom_name.startswith('H'):
                atom_type = 'H'
            elif atom_name.startswith('C'):
                atom_type = 'C'
            elif atom_name.startswith('N'):
                atom_type = 'N'
            else:
                atom_type = 'O'
                
            shifts.append({
                'seq_id': seq_id,
                'residue': res_label,
                'atom_name': atom_name,
                'atom_type': atom_type,
                'val': val
            })
        except ValueError:
            continue
            
    return shifts

def pair_atoms(shifts, x_atom, y_atom, z_atom):
    """
    Groups chemical shifts by residue seq_id, and pairs values for x_atom, y_atom, and z_atom.
    """
    # Group shifts by residue
    by_residue = {}
    for s in shifts:
        seq_id = s['seq_id']
        if seq_id not in by_residue:
            by_residue[seq_id] = {
                'seq_id': seq_id,
                'residue': s['residue'],
                'atoms': {}
            }
        by_residue[seq_id]['atoms'][s['atom_name']] = s['val']
        
    paired_data = []
    for seq_id, res_info in by_residue.items():
        atoms = res_info['atoms']
        
        # Check if we have at least X and Y atoms
        x_val = atoms.get(x_atom)
        y_val = atoms.get(y_atom)
        
        # If we have both, we can add it!
        if x_val is not None and y_val is not None:
            z_val = atoms.get(z_atom) if z_atom else None
            paired_data.append({
                'seq_id': seq_id,
                'residue': res_info['residue'],
                'x': x_val,
                'y': y_val,
                'z': z_val
            })
            
    # Sort by sequence ID numerically if possible
    def try_int(s):
        try:
            return int(re.sub(r'\D', '', s))
        except ValueError:
            return 99999
            
    paired_data.sort(key=lambda item: try_int(item['seq_id']))
    return paired_data

def find_bruker_pdata_dir(start_path):
    import os
    if not os.path.exists(start_path):
        return None
    if os.path.isfile(start_path):
        start_path = os.path.dirname(start_path)
        
    # Check if this directory itself contains the processed files
    if os.path.exists(os.path.join(start_path, '2rr')) and os.path.exists(os.path.join(start_path, 'procs')):
        return start_path
        
    # Check if there is a pdata/1 subdirectory
    pdata_1 = os.path.join(start_path, 'pdata', '1')
    if os.path.exists(os.path.join(pdata_1, '2rr')) and os.path.exists(os.path.join(pdata_1, 'procs')):
        return pdata_1
        
    # Check any pdata subdirectories
    pdata_dir = os.path.join(start_path, 'pdata')
    if os.path.exists(pdata_dir):
        for sub in os.listdir(pdata_dir):
            sub_path = os.path.join(pdata_dir, sub)
            if os.path.isdir(sub_path):
                if os.path.exists(os.path.join(sub_path, '2rr')) and os.path.exists(os.path.join(sub_path, 'procs')):
                    return sub_path
                    
    # Recursive search up to depth 3
    for root, dirs, files in os.walk(start_path):
        if '2rr' in files and 'procs' in files:
            return root
            
    # Check parent directories
    curr = start_path
    for _ in range(3):
        parent = os.path.dirname(curr)
        if parent == curr:
            break
        pdata_1 = os.path.join(parent, 'pdata', '1')
        if os.path.exists(os.path.join(pdata_1, '2rr')) and os.path.exists(os.path.join(pdata_1, 'procs')):
            return pdata_1
        curr = parent
        
    return None

def main():
    if len(sys.argv) < 2:
        print(json.dumps({"status": "error", "message": "Usage: nmr_backend.py <file_type> [x_atom] [y_atom] [z_atom]"}))
        return

    file_type = sys.argv[1].lower() # 'str', 'csv', or 'bruker'
    x_atom = sys.argv[2] if len(sys.argv) > 2 else "H"
    y_atom = sys.argv[3] if len(sys.argv) > 3 else "N"
    z_atom = sys.argv[4] if len(sys.argv) > 4 else "CA"

    try:
        # Read from stdin
        file_content = sys.stdin.read()
        
        if file_type == 'bruker' or file_type == 'ser' or file_type == '2rr':
            # Check if JSON configuration is sent, else treat as raw path
            stdin_str = file_content.strip()
            phc0_f2, phc1_f2, phc0_f1, phc1_f1 = 0.0, 0.0, 0.0, 0.0
            baseline_order = -1
            calib_x, calib_y = 0.0, 0.0
            contour_base = 5.0
            path = stdin_str
            
            if stdin_str.startswith('{') and stdin_str.endswith('}'):
                try:
                    cfg = json.loads(stdin_str)
                    path = cfg.get("path", "")
                    phc0_f2 = float(cfg.get("phc0_f2", 0.0))
                    phc1_f2 = float(cfg.get("phc1_f2", 0.0))
                    phc0_f1 = float(cfg.get("phc0_f1", 0.0))
                    phc1_f1 = float(cfg.get("phc1_f1", 0.0))
                    baseline_order = int(cfg.get("baseline_order", -1))
                    calib_x = float(cfg.get("calib_x", 0.0))
                    calib_y = float(cfg.get("calib_y", 0.0))
                    contour_base = float(cfg.get("contour_base", 5.0))
                except Exception:
                    pass
                    
            pdata_dir = find_bruker_pdata_dir(path)
            if not pdata_dir:
                print(json.dumps({
                    "status": "error",
                    "message": f"Could not locate Bruker processed files (2rr and procs) in path: {path}"
                }))
                return
                
            import nmrglue as ng
            import matplotlib
            matplotlib.use('Agg')
            import matplotlib.pyplot as plt
            import numpy as np
            from scipy.ndimage import maximum_filter
            
            # Read processed data (load complex quadrants if phasing is requested)
            is_phasing = (phc0_f2 != 0.0 or phc1_f2 != 0.0 or phc0_f1 != 0.0 or phc1_f1 != 0.0)
            
            if is_phasing:
                dic, data_rr = ng.bruker.read_pdata(pdata_dir, bin_files=['2rr'])
                
                def load_quadrant(fname):
                    import os
                    fpath = os.path.join(pdata_dir, fname)
                    if os.path.exists(fpath):
                        try:
                            return ng.bruker.read_pdata(pdata_dir, bin_files=[fname])[1]
                        except Exception:
                            return np.zeros_like(data_rr)
                    return np.zeros_like(data_rr)
                
                data_ri = load_quadrant('2ri')
                data_ir = load_quadrant('2ir')
                data_ii = load_quadrant('2ii')
                
                n_f1, n_f2 = data_rr.shape
                theta2 = np.deg2rad(phc0_f2 + phc1_f2 * (np.arange(n_f2) / max(1, n_f2 - 1)))
                theta1 = np.deg2rad(phc0_f1 + phc1_f1 * (np.arange(n_f1) / max(1, n_f1 - 1)))
                
                cos2 = np.cos(theta2)
                sin2 = np.sin(theta2)
                cos1 = np.cos(theta1)[:, np.newaxis]
                sin1 = np.sin(theta1)[:, np.newaxis]
                
                rr_phased_F2 = data_rr * cos2 - data_ri * sin2
                ir_phased_F2 = data_ir * cos2 - data_ii * sin2
                data_matrix = rr_phased_F2 * cos1 - ir_phased_F2 * sin1
            else:
                dic, data_matrix = ng.bruker.read_pdata(pdata_dir)
                
            # Apply baseline correction if requested
            if baseline_order >= 0:
                n_rows, n_cols = data_matrix.shape
                x_idx = np.arange(n_cols)
                corrected = np.zeros_like(data_matrix)
                for r in range(n_rows):
                    row = data_matrix[r, :]
                    poly = np.polyfit(x_idx, row, baseline_order)
                    baseline = np.polyval(poly, x_idx)
                    corrected[r, :] = row - baseline
                data_matrix = corrected
                
            udic = ng.bruker.guess_udic(dic, data_matrix)
            uc0 = ng.fileio.fileiobase.uc_from_udic(udic, 0) # F1 (typically 15N/13C, vertical)
            uc1 = ng.fileio.fileiobase.uc_from_udic(udic, 1) # F2 (typically 1H, horizontal)
            f1_ppm = uc0.ppm_scale()
            f2_ppm = uc1.ppm_scale()
            
            # Apply axis calibration offsets
            if calib_x != 0.0:
                f2_ppm = f2_ppm + calib_x
            if calib_y != 0.0:
                f1_ppm = f1_ppm + calib_y
            
            std = data_matrix.std()
            mean = data_matrix.mean()
            
            # 6 Positive and 6 Negative levels starting at contour_base * std
            pos_levels = sorted([mean + std * contour_base * (1.5**i) for i in range(6)])
            neg_levels = sorted([mean - std * contour_base * (1.5**i) for i in range(6)])
            
            cs_pos = plt.contour(f2_ppm, f1_ppm, data_matrix, levels=pos_levels)
            cs_neg = plt.contour(f2_ppm, f1_ppm, data_matrix, levels=neg_levels)
            
            contours_out = []
            def serialize_contour_set(cs, is_pos):
                for i, level_val in enumerate(cs.levels):
                    segments = cs.allsegs[i]
                    serialized_segs = []
                    for seg in segments:
                        n_pts = len(seg)
                        if n_pts < 2:
                            continue
                        step = 1
                        if n_pts > 100:
                            step = n_pts // 50
                        dec_seg = seg[::step]
                        # Format points: x,y x,y x,y ...
                        pts_str = " ".join(f"{pt[0]:.4f},{pt[1]:.4f}" for pt in dec_seg)
                        serialized_segs.append(pts_str)
                    if serialized_segs:
                        contours_out.append({
                            "level": float(level_val),
                            "is_positive": is_pos,
                            "segments_str": ";".join(serialized_segs)
                        })
            
            serialize_contour_set(cs_pos, True)
            serialize_contour_set(cs_neg, False)
            
            # Peak picking (find local maxima)
            threshold = mean + std * 8
            neighborhood_size = 7
            data_max = maximum_filter(data_matrix, size=neighborhood_size)
            peaks_mask = (data_matrix == data_max) & (data_matrix > threshold)
            peak_indices = np.argwhere(peaks_mask)
            
            peaks_list = []
            for idx in peak_indices:
                val = data_matrix[idx[0], idx[1]]
                peaks_list.append((idx[0], idx[1], val))
            
            # Sort by intensity descending and select top 50 peaks
            peaks_list.sort(key=lambda x: x[2], reverse=True)
            top_peaks = peaks_list[:50]
            
            data_out = []
            for count, (r, c, val) in enumerate(top_peaks):
                p_x = float(f2_ppm[c])
                p_y = float(f1_ppm[r])
                data_out.append({
                    "seq_id": f"P{count+1}",
                    "residue": "PEAK",
                    "x": p_x,
                    "y": p_y,
                    "z": float(val)
                })
                
            # Extract axis labels from NUC1
            x_label = "1H"
            y_label = "15N"
            if 'acqus' in dic and 'NUC1' in dic['acqus']:
                x_label = str(dic['acqus']['NUC1']).replace('<', '').replace('>', '').strip()
            if 'acqu2s' in dic and 'NUC1' in dic['acqu2s']:
                y_label = str(dic['acqu2s']['NUC1']).replace('<', '').replace('>', '').strip()
                
            print(json.dumps({
                "status": "success",
                "x_label": x_label,
                "y_label": y_label,
                "z_label": "Intensity",
                "unique_atoms": [x_label, y_label, "Intensity"],
                "data": data_out,
                "contours": contours_out
            }))
            return
            
        elif file_type == 'str' or file_type == 'nmrstar':
            shifts = parse_nmr_star(file_content)
        elif file_type == 'csv':
            shifts = parse_csv_shifts(file_content)
        else:
            print(json.dumps({"status": "error", "message": f"Unknown file type: {file_type}"}))
            return

        if not shifts:
            print(json.dumps({
                "status": "error",
                "message": "No chemical shift data could be parsed. Check file format."
            }))
            return

        # Find unique atom names
        unique_atoms = sorted(list(set(s['atom_name'] for s in shifts)))
        
        # Pair residues
        paired = pair_atoms(shifts, x_atom, y_atom, z_atom)
        
        print(json.dumps({
            "status": "success",
            "unique_atoms": unique_atoms,
            "data": paired
        }))
        
    except Exception as e:
        print(json.dumps({
            "status": "error",
            "message": f"Exception occurred during parsing: {str(e)}",
            "traceback": traceback.format_exc()
        }))

if __name__ == '__main__':
    main()
