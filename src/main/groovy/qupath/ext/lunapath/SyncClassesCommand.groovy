package qupath.ext.lunapath

import qupath.lib.gui.QuPathGUI
import qupath.lib.scripting.QP
import javafx.application.Platform
import javafx.stage.FileChooser

class SyncClassesCommand {

    private final QuPathGUI qupath

    SyncClassesCommand(QuPathGUI qupath) {
        this.qupath = qupath
    }

    void run() {
        Platform.runLater {
            def imageData = qupath.getImageData()
            if (imageData == null) {
                println "ERROR: No image open."
                return
            }

            def hierarchy = imageData.getHierarchy()
            def detections = hierarchy.getDetectionObjects()
                .findAll { it.getPathClass() != null }

            if (detections.isEmpty()) {
                println "ERROR: No classified detections found."
                return
            }

            // ================================
            // SYNC CLASSES
            // ================================
            def assignedClasses = detections.collect { it.getPathClass() }.unique()
            def classList = qupath.getAvailablePathClasses()
            assignedClasses.each { c ->
                if (!classList.contains(c)) classList.add(c)
            }
            println "Added ${assignedClasses.size()} classes to the list"

            // ================================
            // NORMALIZE + PARSE
            // ================================
            def normalizeMarker = { String name ->
                name.replace("_Good", "").trim()
            }

            def parseClass = { pathClass ->
                def result = [:]
                if (pathClass == null) return result
                pathClass.toString().split(": ").each { component ->
                    def m = component.trim() =~ /^(.+?)\s*([+-])$/
                    if (m.matches()) {
                        result[normalizeMarker(m[0][1].trim())] = m[0][2] == "+" ? 1 : 0
                    }
                }
                return result
            }

            def allMarkers = new LinkedHashSet()
            detections.each { det ->
                parseClass(det.getPathClass()).keySet().each { allMarkers.add(it) }
            }
            allMarkers = allMarkers.sort()

            // ================================
            // STORE BINARY MEASUREMENTS
            // ================================
            println "Storing binary measurements on cells..."
            detections.each { det ->
                def markerMap = parseClass(det.getPathClass())
                allMarkers.each { marker ->
                    det.getMeasurementList().put("_bin_${marker}",
                        markerMap.containsKey(marker) ? markerMap[marker].toDouble() : -1.0)
                }
            }
            QP.fireHierarchyUpdate()
            println "Stored binary measurements for ${detections.size()} cells"

            // ================================
            // EXPORT CSV
            // ================================
            def fc = new FileChooser()
            fc.setTitle("Save binarized export")
            fc.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("CSV files", "*.csv"))
            fc.setInitialFileName("binarized_export.csv")
            def file = fc.showSaveDialog(qupath.getStage())

            if (file == null) {
                println "No file selected, skipping export."
                return
            }

            def writer = new File(file.getAbsolutePath()).newWriter()
            writer.writeLine("id," + allMarkers.join(","))
            detections.each { det ->
                def markerMap = parseClass(det.getPathClass())
                def values = allMarkers.collect { marker ->
                    markerMap.containsKey(marker) ? markerMap[marker] : ""
                }
                writer.writeLine("${det.getName()}," + values.join(","))
            }
            writer.close()

            println "Exported binarized data for ${detections.size()} cells to ${file.getAbsolutePath()}"
        }
    }
}