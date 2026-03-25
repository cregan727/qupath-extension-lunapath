# LunaPath QuPath Extension

A QuPath extension for importing cell segmentation data, syncing marker classifications, 
and defining custom cell phenotypes. Designed to work with Lunaphore COMET output files.

## Requirements

- QuPath 0.6.0 or later

## Installation

1. Download the latest `qupath-extension-lunapath-X.X.X.jar` from the [Releases](../../releases) page
2. Drag and drop the `.jar` file onto an open QuPath window
3. Restart QuPath
4. The **LunaPath** menu will appear under **Extensions**

---

## Workflow Overview

LunaPath provides three commands under **Extensions > LunaPath**, designed to be run in order:

1. **Add Detections from GeoJSON** — import cell segmentations and measurements
2. **Sync Classes & Export** — binarize marker classifications and export to CSV
3. **Phenotype Classifier** — define and apply custom phenotypes

---

## Step 1: Set Up Your QuPath Project

Before running any LunaPath commands:

- Create a new project via **File > Project > Create project...**
- Load your image via **File > Open...** or drag and drop
- Set the image type to **Fluorescence** when prompted

---

## Step 2: Add Detections from GeoJSON

**Extensions > LunaPath > Add Detections from GeoJSON**

You will be prompted to select files in this order:

1. **Nucleus GeoJSON** — polygon boundaries for each nucleus (e.g. `nuclei.geojson`)
   - Must have an `id` property on each feature
2. **Measurements CSV** — per-cell marker intensities (e.g. `qupath_intensities.csv`)
   - First column must be `id`, matching the GeoJSON feature IDs
   - Remaining columns are marker measurements
3. **Cell boundary GeoJSON** *(optional)* — larger outer polygons wrapping each nucleus
   (e.g. `cell_boundaries.geojson`) — same format as nucleus GeoJSON

Once loaded, toggle cell/nucleus visibility via **View > Cell display**.

---

## Step 3: Binarize Markers (QuPath built-in)

This step uses QuPath's built-in classifier tools, not LunaPath directly:

1. Go to **Classify > Object Classification > Create single measurement classifier**
2. Set object filter to **Detections (all)**
3. Select the channel and measurement for the marker you want to threshold
4. Set the above/below threshold classes (e.g. `CD3 +` / `CD3 -`)
5. Enable **Live preview** and drag the threshold until cells are correctly split
6. Save the classifier
7. Repeat for each marker

Then combine them via **Classify > Object Classification > Create composite classifier**, 
select all your single-marker classifiers, and click **Save & apply**.

Each cell will be labelled with all marker classifications (e.g. `CD8 -: CD3 +: FOXP3 -: ...`).

---

## Step 4: Sync Classes & Export

**Extensions > LunaPath > Sync Classes & Export**

Run this after applying the composite classifier. It will:

- Register all assigned classes in QuPath's class list
- Store binary marker values (0/1) as hidden measurements on each cell
  so they can be read by the Phenotype Classifier even after reclassification
- Prompt you to save a binarized CSV export (one row per cell, one column per marker)

> **Important:** Run this before using the Phenotype Classifier. If you skip it, 
> the Phenotype Classifier will fall back to reading PathClasses directly, which 
> stops working after phenotypes are applied.

---

## Step 5: Phenotype Classifier

**Extensions > LunaPath > Phenotype Classifier**

Define custom cell phenotypes by specifying which markers must be present:

- Each row is a phenotype (e.g. "CD8 T cell")
- Click the marker buttons to toggle them to **Yes** (marker must be present) 
  or **—** (ignore)
- Click **Validate** to preview cell counts and check for duplicate rules
- Click **Classify All** to apply phenotypes to all cells

You can save and load phenotype definitions as CSV files using the **Save CSV** / **Load CSV** buttons.

**Tie-breaking:** if a cell matches multiple phenotypes, the most specific one wins 
(most required markers). True ties are labelled **Ambiguous**.

---

## File Format Reference

### Nucleus / Cell GeoJSON
```json
{
  "type": "FeatureCollection",
  "features": [
    {
      "type": "Feature",
      "properties": { "id": "ID_1" },
      "geometry": {
        "type": "Polygon",
        "coordinates": [[[x1, y1], [x2, y2], ...]]
      }
    }
  ]
}
```

### Measurements CSV
```
id,Marker1 - TRITC,Marker2 - Cy5,DAPI,...
ID_1,452.3,812.1,1204.5,...
ID_2,103.2,2341.8,987.2,...
```
The `id` column must match the GeoJSON feature IDs exactly.

---

## Part of the LunaPy Workflow

This extension is the QuPath component of the [LunaPy](https://github.com/cregan727/lunapy) 
pipeline for Lunaphore COMET multiplex imaging analysis.

## License

LunaPath is open source software built on [QuPath](https://github.com/qupath/qupath) and licensed under [GPL v3](LICENSE).  
Copyright (C) 2026 Claire Regan
