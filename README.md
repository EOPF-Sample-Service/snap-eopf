# SNAP EOPF Prototype Reader for Sentinel-2 L2A Products

This repository contains a prototype **SNAP reader** for the new
**EOPF Sentinel-2 Level-2A Zarr sample products**, provided by the
**EOPF Zarr Sample Service**. Its purpose is to demonstrate compatibility
between the EOPF Zarr format and the SNAP processing ecosystem.

> **Note**
> - This reader is a **prototype** and currently supports only **Sentinel-2 L2A** Zarr products.
> - Support for additional product types will be introduced in future SNAP development stages.

---

## Installation

### Install SNAP

Download and install **SNAP version 13** using the official installation guide:
https://step.esa.int/main/download/snap-download/


## Install the EOPF Sentinel-2 L2A Zarr Prototype Reader

> **Note:**
> The installation instructions for this plugin will be added soon.

---

## Using the EOPF Sentinel-2 L2A Zarr Prototype Reader

1. Open the
   **[EOPF Sentinel Zarr Sample Service: STAC API for Sentinel-2 L2A](https://stac.browser.user.eopf.eodc.eu/collections/sentinel-2-l2a?.language=en)**.
2. Select a **STAC Item** and locate the asset **“Zipped EOPF Product”**.
3. Click the **Download** button.
   This triggers a processing workflow that packages the Zarr sample into a ZIP file
   and downloads it to your local machine.
4. Once downloaded, import the product into SNAP by either:
   - dragging and dropping the ZIP file into the **Product Explorer**, or
   - using **File → Open Product…**
5. After loading, you can interact with the product in SNAP as usual. For general
   guidance and available processing features, see the **[STEP documentation](https://step.esa.int/main/)**.
