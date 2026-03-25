package qupath.ext.lunapath

import qupath.lib.gui.QuPathGUI
import qupath.lib.objects.classes.PathClass
import qupath.lib.scripting.QP
import javafx.application.Platform
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.layout.*
import javafx.stage.Stage
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.beans.property.SimpleStringProperty

class PhenotypeClassifierCommand {

    private final QuPathGUI qupath

    PhenotypeClassifierCommand(QuPathGUI qupath) {
        this.qupath = qupath
    }

    void run() {
        def imageData = qupath.getImageData()
        if (imageData == null) {
            println "ERROR: No image open."
            return
        }

        def hierarchy = imageData.getHierarchy()

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

        // ================================
        // BUILD MARKER CACHE
        // ================================
        def allDets = hierarchy.getDetectionObjects()
        def allMarkers = new LinkedHashSet()
        def markerCache = [:]

        def hasBinaryMeasurements = allDets.find()?.getMeasurementList()
            ?.getNames()?.any { it.startsWith("_bin_") } ?: false

        if (hasBinaryMeasurements) {
            allDets.each { det ->
                def ml = det.getMeasurementList()
                def mm = [:]
                ml.getNames().findAll { it.startsWith("_bin_") }.each { name ->
                    def marker = name[5..-1]
                    def val = ml.get(name) as double
                    if (val >= 0) { mm[marker] = (int)val; allMarkers.add(marker) }
                }
                markerCache[det] = mm
            }
        } else {
            allDets.findAll { it.getPathClass() != null }.each { det ->
                def mm = parseClass(det.getPathClass())
                markerCache[det] = mm
                mm.keySet().each { allMarkers.add(it) }
            }
            if (markerCache.isEmpty()) {
                println "ERROR: No binary measurements found and no composite classes present."
                println "Run 'Sync Classes & Export' first, then re-run this command."
                return
            }
            println "WARNING: Using PathClass fallback. Run 'Sync Classes & Export' for reliable results."
        }

        def detections = allDets
        allMarkers = allMarkers.sort()

        // ================================
        // UI
        // ================================
        Platform.runLater {
            def stage = new Stage()
            stage.setTitle("LunaPath — Phenotype Classifier")
            stage.initOwner(qupath.getStage())

            def root = new VBox(10)
            root.setPadding(new Insets(12))

            int COL_NAME   = 160
            int COL_MARKER = 60
            int COL_STATUS = 160

            def makeStateBtn = { SimpleStringProperty prop ->
                def btn = new Button()
                btn.setPrefWidth(COL_MARKER); btn.setMaxWidth(COL_MARKER)
                def refresh = {
                    if (prop.get() == "Y") {
                        btn.setText("Yes")
                        btn.setStyle("-fx-background-color:#4CAF50;-fx-text-fill:white;-fx-font-weight:bold")
                    } else {
                        btn.setText("—")
                        btn.setStyle("-fx-background-color:#9E9E9E;-fx-text-fill:white")
                    }
                }
                prop.addListener { obs, old, nv -> refresh() }
                refresh()
                btn.setOnAction { prop.set(prop.get() == "M" ? "Y" : "M") }
                return btn
            }

            def headerRow = new HBox(4)
            headerRow.setAlignment(Pos.CENTER_LEFT)
            def nameHdr = new Label("Phenotype")
            nameHdr.setPrefWidth(COL_NAME); nameHdr.setMaxWidth(COL_NAME)
            nameHdr.setStyle("-fx-font-weight:bold")
            headerRow.getChildren().add(nameHdr)
            allMarkers.each { marker ->
                def lbl = new Label(marker)
                lbl.setPrefWidth(COL_MARKER); lbl.setMaxWidth(COL_MARKER)
                lbl.setAlignment(Pos.CENTER); lbl.setStyle("-fx-font-weight:bold")
                headerRow.getChildren().add(lbl)
            }
            def statusHdr = new Label("Status")
            statusHdr.setPrefWidth(COL_STATUS); statusHdr.setMaxWidth(COL_STATUS)
            statusHdr.setStyle("-fx-font-weight:bold")
            headerRow.getChildren().add(statusHdr)

            def rowsBox = new VBox(4)
            def phenotypeRows = []

            def addRow = {
                def nameField = new TextField()
                nameField.setPromptText("Phenotype name...")
                nameField.setPrefWidth(COL_NAME); nameField.setMaxWidth(COL_NAME)

                def states = allMarkers.collectEntries { marker ->
                    [marker, new SimpleStringProperty("M")]
                }

                def statusLabel = new Label("")
                statusLabel.setPrefWidth(COL_STATUS); statusLabel.setMaxWidth(COL_STATUS)

                def rowBox = new HBox(4)
                rowBox.setAlignment(Pos.CENTER_LEFT)
                rowBox.getChildren().add(nameField)
                allMarkers.each { marker -> rowBox.getChildren().add(makeStateBtn(states[marker])) }
                rowBox.getChildren().add(statusLabel)

                def removeBtn = new Button("✕")
                removeBtn.setStyle("-fx-background-color:transparent;-fx-text-fill:#aaa;-fx-padding:2 6")
                removeBtn.setOnAction {
                    rowsBox.getChildren().remove(rowBox)
                    phenotypeRows.removeIf { it.row.is(rowBox) }
                }
                rowBox.getChildren().add(removeBtn)

                phenotypeRows << [nameField: nameField, states: states,
                                  statusLabel: statusLabel, row: rowBox]
                rowsBox.getChildren().add(rowBox)
            }

            3.times { addRow() }

            def scroll = new ScrollPane(new VBox(4, headerRow, new Separator(), rowsBox))
            scroll.setFitToWidth(true)
            scroll.setPrefHeight(260)
            root.getChildren().add(scroll)

            def legend = new HBox(16)
            legend.setAlignment(Pos.CENTER_LEFT)
            [["Yes","#4CAF50","Marker must be present"],
             ["—","#9E9E9E","Ignore (either is fine)"]].each { text, color, label ->
                def b = new Label(text)
                b.setStyle("-fx-background-color:${color};-fx-text-fill:white;" +
                           "-fx-font-weight:bold;-fx-padding:1 6;-fx-background-radius:3")
                legend.getChildren().addAll(b, new Label(label))
            }
            root.getChildren().add(legend)
            root.getChildren().add(new Separator())

            def globalStatus = new Label("")
            globalStatus.setWrapText(true)
            root.getChildren().add(globalStatus)

            def addRowBtn   = new Button("+ Add Phenotype")
            def loadCsvBtn  = new Button("Load CSV")
            def saveCsvBtn  = new Button("Save CSV")
            def validateBtn = new Button("Validate")
            def classifyBtn = new Button("Classify All")
            classifyBtn.setStyle("-fx-font-weight:bold")
            def closeBtn    = new Button("Close")
            def btnBox = new HBox(10, addRowBtn, loadCsvBtn, saveCsvBtn,
                                  validateBtn, classifyBtn, closeBtn)
            root.getChildren().add(btnBox)

            addRowBtn.setOnAction { addRow() }

            // ---- Column mapping dialog ----
            def promptColumnMapping = { List unmatched ->
                def result = [cancelled: false, mapping: [:]]
                def mapStage = new Stage()
                mapStage.setTitle("Match CSV Columns")
                mapStage.initOwner(stage)

                def grid = new GridPane()
                grid.setHgap(12); grid.setVgap(6)
                grid.setPadding(new Insets(12))
                grid.add(new Label("CSV column"), 0, 0)
                grid.add(new Label("Map to current marker"), 1, 0)
                grid.add(new Separator(), 0, 1)
                GridPane.setColumnSpan(grid.getChildren().last(), 2)

                def combos = [:]
                unmatched.eachWithIndex { col, i ->
                    def lbl = new Label(col)
                    def combo = new ComboBox()
                    combo.getItems().add("(skip)")
                    combo.getItems().addAll(allMarkers as List)
                    def guess = allMarkers.find { m ->
                        m.equalsIgnoreCase(col) ||
                        m.equalsIgnoreCase(col.replace("_Good","").trim())
                    }
                    combo.setValue(guess ?: "(skip)")
                    combos[col] = combo
                    grid.add(lbl, 0, i + 2)
                    grid.add(combo, 1, i + 2)
                }

                def okBtn     = new Button("OK")
                def cancelBtn = new Button("Cancel")
                def btnRow    = new HBox(8, okBtn, cancelBtn)
                btnRow.setPadding(new Insets(8, 0, 0, 0))
                grid.add(btnRow, 0, unmatched.size() + 2)
                GridPane.setColumnSpan(btnRow, 2)

                okBtn.setOnAction {
                    combos.each { col, combo ->
                        def sel = combo.getValue()
                        if (sel != "(skip)") result.mapping[col] = sel
                    }
                    mapStage.close()
                }
                cancelBtn.setOnAction { result.cancelled = true; mapStage.close() }

                mapStage.setScene(new Scene(grid))
                mapStage.showAndWait()
                return result
            }

            // ---- Load CSV ----
            loadCsvBtn.setOnAction {
                def fc = new javafx.stage.FileChooser()
                fc.setTitle("Load Phenotype CSV")
                fc.getExtensionFilters().add(
                    new javafx.stage.FileChooser.ExtensionFilter("CSV files", "*.csv"))
                def file = fc.showOpenDialog(stage)
                if (file == null) return

                def lines = file.readLines().findAll { !it.trim().isEmpty() }
                if (lines.size() < 2) { globalStatus.setText("CSV has no data rows."); return }

                def csvHeaders = lines[0].split(",")*.trim()
                def csvMarkers = csvHeaders.size() > 1 ? csvHeaders[1..-1] : []

                def colMap = [:]
                def unmatched = []
                csvMarkers.each { col ->
                    if (allMarkers.contains(col)) colMap[col] = col
                    else unmatched << col
                }

                if (unmatched) {
                    def r = promptColumnMapping(unmatched)
                    if (r.cancelled) return
                    colMap.putAll(r.mapping)
                }

                rowsBox.getChildren().clear()
                phenotypeRows.clear()

                lines[1..-1].each { line ->
                    def values = line.split(",", -1)*.trim()
                    addRow()
                    def row = phenotypeRows.last()
                    row.nameField.setText(values[0])
                    csvMarkers.eachWithIndex { col, i ->
                        def marker = colMap[col]
                        if (marker && row.states.containsKey(marker)) {
                            def val = (i + 1 < values.size()) ? values[i + 1] : ""
                            row.states[marker].set(val == "Yes" ? "Y" : "M")
                        }
                    }
                }
                globalStatus.setText("Loaded ${phenotypeRows.size()} phenotypes from ${file.getName()}")
                globalStatus.setStyle("-fx-text-fill:green")
            }

            // ---- Save CSV ----
            saveCsvBtn.setOnAction {
                def fc = new javafx.stage.FileChooser()
                fc.setTitle("Save Phenotype CSV")
                fc.getExtensionFilters().add(
                    new javafx.stage.FileChooser.ExtensionFilter("CSV files", "*.csv"))
                fc.setInitialFileName("phenotypes.csv")
                def file = fc.showSaveDialog(stage)
                if (file == null) return

                def lines = [(["Phenotype"] + allMarkers).join(",")]
                phenotypeRows.each { row ->
                    def name = row.nameField.getText().trim()
                    if (name.isEmpty()) return
                    def vals = allMarkers.collect { m ->
                        row.states[m].get() == "Y" ? "Yes" : "No"
                    }
                    lines << ([name] + vals).join(",")
                }
                file.text = lines.join("\n")
                globalStatus.setText("Saved to ${file.getName()}")
                globalStatus.setStyle("-fx-text-fill:green")
            }

            def getRequired = { row ->
                row.states.findAll { it.value.get() == "Y" }.keySet()
            }

            def countMatches = { row ->
                def required = getRequired(row)
                detections.count { det ->
                    def mm = markerCache[det]
                    required.every { m -> mm.get(m) == 1 }
                }
            }

            // ---- Validate ----
            validateBtn.setOnAction {
                def named = phenotypeRows.findAll { it.nameField.getText().trim() }
                def issues = []

                named.each { row ->
                    def required = getRequired(row)
                    row.statusLabel.setStyle("")
                    if (required.isEmpty()) {
                        row.statusLabel.setText("⚠ No markers set")
                        row.statusLabel.setStyle("-fx-text-fill:orange")
                        issues << "'${row.nameField.getText()}' has no required markers"
                    } else {
                        def count = countMatches(row)
                        row.statusLabel.setText("~${count} cells")
                        row.statusLabel.setStyle("-fx-text-fill:gray")
                    }
                }

                for (int i = 0; i < named.size(); i++) {
                    for (int j = i + 1; j < named.size(); j++) {
                        if (getRequired(named[i]) == getRequired(named[j])) {
                            [named[i], named[j]].each {
                                it.statusLabel.setText("⚠ Duplicate rules")
                                it.statusLabel.setStyle("-fx-text-fill:red")
                            }
                            issues << "'${named[i].nameField.getText()}' and " +
                                      "'${named[j].nameField.getText()}' have identical rules"
                        }
                    }
                }

                if (issues.isEmpty()) {
                    globalStatus.setText("✓ No issues found.")
                    globalStatus.setStyle("-fx-text-fill:green")
                } else {
                    globalStatus.setText("Issues: " + issues.join("; "))
                    globalStatus.setStyle("-fx-text-fill:red")
                }
            }

            // ---- Classify All ----
            classifyBtn.setOnAction {
                def named = phenotypeRows.findAll { it.nameField.getText().trim() }
                if (named.isEmpty()) {
                    globalStatus.setText("No phenotypes defined.")
                    globalStatus.setStyle("-fx-text-fill:red")
                    return
                }

                def ambiguousClass = PathClass.fromString("Ambiguous")
                def counts = named.collectEntries { row -> [row, 0] }
                int ambiguous = 0, unclassified = 0

                detections.each { det ->
                    def mm = markerCache[det]
                    def matches = named.findAll { row ->
                        getRequired(row).every { m -> mm.get(m) == 1 }
                    }
                    if (matches.isEmpty()) {
                        det.setPathClass(null)
                        unclassified++
                    } else {
                        int maxRequired = matches.collect { getRequired(it).size() }.max()
                        def best = matches.findAll { getRequired(it).size() == maxRequired }
                        if (best.size() == 1) {
                            det.setPathClass(PathClass.fromString(
                                best[0].nameField.getText().trim()))
                            counts[best[0]]++
                        } else {
                            det.setPathClass(ambiguousClass)
                            ambiguous++
                        }
                    }
                }

                named.each { row ->
                    row.statusLabel.setText("✓ ${counts[row]}")
                    row.statusLabel.setStyle("-fx-text-fill:green")
                }

                def classList = qupath.getAvailablePathClasses()
                named.each { row ->
                    def c = PathClass.fromString(row.nameField.getText().trim())
                    if (!classList.contains(c)) classList.add(c)
                }
                if (ambiguous > 0 && !classList.contains(ambiguousClass))
                    classList.add(ambiguousClass)

                QP.fireHierarchyUpdate()

                def parts = named.collect { row -> "${counts[row]} ${row.nameField.getText()}" }
                if (ambiguous    > 0) parts << "${ambiguous} Ambiguous"
                if (unclassified > 0) parts << "${unclassified} unclassified"
                def msg = "Done: " + parts.join(", ")
                globalStatus.setText(msg)
                globalStatus.setStyle("-fx-text-fill:green")
                println msg
            }

            closeBtn.setOnAction { stage.close() }

            stage.setScene(new Scene(root))
            stage.show()
        }
    }
}
