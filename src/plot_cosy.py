from pathlib import Path
import matplotlib.pyplot as plt
from matplotlib.gridspec import GridSpec
import nmrglue as ng
import numpy as np

def plot_bruker_2d(pdata_dir, output_png_path=None):
    """Reads a Bruker processed 2D directory (containing 2rr, proc2, procs)
    and plots a 2D COSY spectrum with 1D projections.
    """
    try:
        # Read processed data using nmrglue
        dic, data = ng.bruker.read_pdata(pdata_dir)
    except Exception as e:
        print(f"Skipping {pdata_dir}: Could not load ({e})")
        return

    # Check if data is 2D
    if data.ndim != 2:
        print(f"Skipping {pdata_dir}: Data is {data.ndim}D, expected 2D.")
        return

    # Extract PPM unit conversion objects using guess_udic
    udic = ng.bruker.guess_udic(dic, data)
    uc_f2 = ng.fileio.fileiobase.uc_from_udic(udic, 1)
    uc_f1 = ng.fileio.fileiobase.uc_from_udic(udic, 0)

    ppm_f2 = uc_f2.ppm_scale()
    ppm_f1 = uc_f1.ppm_scale()

    # Compute 1D projections (Max Intensity)
    proj_f2 = np.max(data, axis=0)  # Top projection
    proj_f1 = np.max(data, axis=1)  # Left projection

    # Setup figure grid layout
    fig = plt.figure(figsize=(10, 8), dpi=150)
    gs = GridSpec(
        2, 2, width_ratios=[1, 5], height_ratios=[1, 5], wspace=0.02, hspace=0.02
    )

    ax_top = fig.add_subplot(gs[0, 1])
    ax_left = fig.add_subplot(gs[1, 0])
    ax_2d = fig.add_subplot(gs[1, 1], sharex=ax_top, sharey=ax_left)

    # Plot 1D Projections
    ax_top.plot(ppm_f2, proj_f2, color="#0022cc", lw=0.7)
    ax_top.axis("off")

    ax_left.plot(proj_f1, ppm_f1, color="#0022cc", lw=0.7)
    ax_left.invert_xaxis()
    ax_left.axis("off")

    # Set contour threshold and levels (Geometrically spaced above noise)
    noise = np.std(data)
    pos_data = np.maximum(data, 0)
    max_val = np.max(pos_data)

    # Start levels at 5x noise level up to max intensity
    levels = np.geomspace(noise * 5, max_val, num=16)

    # Render 2D spectrum
    ax_2d.contour(
        ppm_f2, ppm_f1, pos_data, levels=levels, colors="#0022cc", linewidths=0.4
    )

    # Axis formatting (Reverse PPM standard)
    ax_2d.set_xlim(max(ppm_f2), min(ppm_f2))
    ax_2d.set_ylim(max(ppm_f1), min(ppm_f1))
    ax_2d.set_xlabel("F2 [ppm]", fontsize=10, fontweight="bold")
    ax_2d.set_ylabel("F1 [ppm]", fontsize=10, fontweight="bold")

    # Metadata label on top-left of the plot
    p = Path(pdata_dir)
    if len(p.parents) >= 3:
        folder_name = f"{p.parents[2].name} {p.parents[1].name}"
    elif len(p.parents) >= 2:
        folder_name = p.parents[1].name
    else:
        folder_name = p.parent.name
        
    ax_2d.text(
        0.02,
        0.96,
        f"Sample: {folder_name}",
        transform=ax_2d.transAxes,
        color="red",
        fontsize=11,
        fontweight="bold",
        verticalalignment="top",
    )

    # Save or Display
    if output_png_path:
        plt.savefig(output_png_path, bbox_inches="tight")
        plt.close()
        print(f"Saved: {output_png_path}")
    else:
        plt.show()


if __name__ == "__main__":
    # datasets/ sits next to src/, so derive the path instead of hardcoding a
    # home directory that only existed on one machine.
    root_dataset_dir = Path(__file__).resolve().parent.parent / "datasets" / "Data"

    # Find all subdirectories that contain a '2rr' file
    for pdata_path in root_dataset_dir.rglob("2rr"):
        pdata_dir = pdata_path.parent  # Usually named 'pdata/1'
        output_png = pdata_dir / "spectrum_plot.png"

        print(f"Processing: {pdata_dir}")
        plot_bruker_2d(pdata_dir, output_png_path=output_png)
