package qupath.ext.lunapath

import qupath.lib.gui.QuPathGUI
import qupath.lib.objects.PathObjects
import qupath.lib.roi.ROIs
import qupath.lib.regions.ImagePlane
import qupath.lib.geom.Point2
import qupath.lib.objects.classes.PathClass
import qupath.lib.scripting.QP
import com.google.gson.JsonParser
import java.nio.file.Files
import javafx.application.Platform
import javafx.stage.FileChooser
import javafx.scene.control.Alert
import javafx.scene.control.ButtonType

class AddDetectionsCommand {

    private final QuPathGUI qupath

    AddDetectionsCommand(QuPathGUI qupath) {
        this.qupath = qupath
    }

    private String chooseFile(String title, String... extensions) {
        def fc = new FileChooser()
        fc.setTitle(title)
        if (extensions) {
            fc.getExtensionFilters().add(
                new FileChooser.ExtensionFilter(
                    extensions[0].toUpperCase() + " files",
                    extensions.collect { "*.$it".toString() }
                )
            )
        }
        def file = fc.showOpenDialog(qupath.getStage())
        return file?.getAbsolutePath()
    }

    private boolean confirmDialog(String title, String message) {
        def alert = new Alert(Alert.AlertType.CONFIRMATION)
        alert.setTitle(title)
        alert.setHeaderText(null)
        alert.setContentText(message)
        alert.initOwner(qupath.getStage())
        def response = alert.showAndWait()
        return response.isPresent() && response.get() == ButtonType.OK
    }

    void run() {
        Platform.runLater {
            def imageData = qupath.getImageData()
            if (imageData == null) {
                println "ERROR: No image open."
                return
            }

            // ================================
            // INTRO DIALOG
            // ================================
            def info = new Alert(Alert.AlertType.INFORMATION)
            info.setTitle("Add Detections — Required Files")
            info.setHeaderText("You will be asked to select 3 files:")
            info.setContentText(
                "1. NUCLEUS GeoJSON  — polygon boundaries for each nucleus\n" +
                "   • Must have an 'id' property per feature\n\n" +
                "2. MEASUREMENTS CSV  — per-cell marker intensities\n" +
                "   • First column must be 'id' matching the GeoJSON\n\n" +
                "3. CELL BOUNDARY GeoJSON  (optional)\n" +
                "   • Larger polygons wrapping each nucleus\n" +
                "   • Same format as nucleus GeoJSON"
            )
            info.initOwner(qupath.getStage())
            info.showAndWait()

            // ================================
            // FILE SELECTION
            // ================================
            def geojsonPath = chooseFile(
                "Select NUCLEUS GeoJSON (e.g. nuclei_boundaries.geojson)",
                "geojson", "json")
            if (geojsonPath == null) { println "Cancelled."; return }

            def csvPath = chooseFile("Select CSV file with measurements", "csv")
            if (csvPath == null) { println "Cancelled."; return }

            def hasCellBoundaries = confirmDialog(
                "Cell Boundaries",
                "Do you have a cell boundary GeoJSON file?\n\n" +
                "This should contain the LARGER outer shapes\n" +
                "that wrap around each nucleus.\n" +
                "(e.g. cell_boundaries.geojson)"
            )

            def cellGeojsonPath = null
            if (hasCellBoundaries) {
                cellGeojsonPath = chooseFile(
                    "Select CELL BOUNDARY GeoJSON (e.g. cell_boundaries.geojson)",
                    "geojson", "json")
            }

            double DOWNSAMPLE = 1.0
            int BATCH_SIZE = 10000

            // ================================
            // LOAD MEASUREMENTS CSV
            // ================================
            println "Loading measurements..."
            def lines = new File(csvPath).readLines()
            def header = lines[0].split(',')
            def measurementNames = header[1..-1]

            def measurementMap = [:]
            lines[1..-1].each { line ->
                if (line.trim().isEmpty()) return
                def t = line.split(',')
                measurementMap[t[0].trim()] = t[1..-1]*.toDouble()
            }
            println "INFO: Loaded ${measurementMap.size()} measurement rows"

            // ================================
            // REGISTER CLASSES UPFRONT
            // ================================
            def markerNames = measurementNames
                .collect { it.replace("- TRITC", "").replace("- Cy5", "").trim() }
                .unique()
                .findAll { it != "DAPI" }

            def newClasses = []
            markerNames.each { marker ->
                newClasses << PathClass.fromString("${marker} +")
                newClasses << PathClass.fromString("${marker} -")
            }

            def classList = qupath.getAvailablePathClasses()
            newClasses.each { c ->
                if (!classList.contains(c)) classList.add(c)
            }

            // ================================
            // LOAD NUCLEUS GEOJSON
            // ================================
            println "Loading GeoJSON..."
            def jsonText = Files.readString(new File(geojsonPath).toPath())
            def geojson = JsonParser.parseString(jsonText).getAsJsonObject()
            def features = geojson.getAsJsonArray("features")
            println "INFO: Loaded ${features.size()} polygons"

            def plane = ImagePlane.getDefaultPlane()
            def numericId = { String id -> id.replaceAll(/\D/, '') }

            // ================================
            // LOAD CELL BOUNDARY GEOJSON (optional)
            // ================================
            def cellRoiMap = [:]
            if (cellGeojsonPath != null) {
                println "Loading cell boundaries..."
                def cellJson = JsonParser.parseString(
                    Files.readString(new File(cellGeojsonPath).toPath())).getAsJsonObject()
                cellJson.getAsJsonArray("features").each { f ->
                    def props = f.getAsJsonObject("properties")
                    if (props == null || !props.has("id")) return
                    def id = numericId(props.get("id").getAsString())
                    def coords = f.getAsJsonObject("geometry")
                                  .getAsJsonArray("coordinates")
                                  .get(0).getAsJsonArray()
                    def pts = new ArrayList<Point2>(coords.size())
                    for (int i = 0; i < coords.size(); i++) {
                        def c = coords.get(i).getAsJsonArray()
                        pts.add(new Point2(c.get(0).getAsDouble() / DOWNSAMPLE,
                                           c.get(1).getAsDouble() / DOWNSAMPLE))
                    }
                    cellRoiMap[id] = ROIs.createPolygonROI(pts, plane)
                }
                println "INFO: Loaded ${cellRoiMap.size()} cell boundaries"
            }

            // ================================
            // CLEAR + CREATE DETECTIONS
            // ================================
            QP.clearAllObjects()

            def batch = []
            int created = 0, matched = 0
            long t0 = System.currentTimeMillis()

            features.eachWithIndex { f, idx ->
                def props = f.getAsJsonObject("properties")
                if (props == null || !props.has("id")) return

                def id = props.get("id").getAsString()
                def coords = f.getAsJsonObject("geometry")
                              .getAsJsonArray("coordinates")
                              .get(0).getAsJsonArray()

                def points = new ArrayList<Point2>(coords.size())
                for (int i = 0; i < coords.size(); i++) {
                    def c = coords.get(i).getAsJsonArray()
                    points.add(new Point2(
                        c.get(0).getAsDouble() / DOWNSAMPLE,
                        c.get(1).getAsDouble() / DOWNSAMPLE))
                }

                def nucleusRoi = ROIs.createPolygonROI(points, plane)
                def cellRoi = cellRoiMap.get(
                    String.valueOf(numericId(id).toInteger() - 1), null)
                def det = cellRoi != null
                    ? PathObjects.createCellObject(cellRoi, nucleusRoi, null, null)
                    : PathObjects.createCellObject(nucleusRoi, null, null, null)
                det.setName(id)

                if (measurementMap.containsKey(id)) {
                    def values = measurementMap[id]
                    for (int i = 0; i < measurementNames.size(); i++) {
                        det.getMeasurementList().put(measurementNames[i], values[i])
                    }
                    matched++
                }

                batch.add(det)
                created++

                if (batch.size() >= BATCH_SIZE) {
                    QP.addObjects(batch)
                    batch.clear()
                    if (created % 50000 == 0) println "Created ${created} cells..."
                }
            }

            if (!batch.isEmpty()) QP.addObjects(batch)
            QP.fireHierarchyUpdate()

            long dt = (System.currentTimeMillis() - t0) / 1000
            println "================================="
            println "DONE"
            println "Created  : ${created}"
            println "Matched  : ${matched}"
            println "Time (s) : ${dt}"
            println "================================="
        }
    }
}